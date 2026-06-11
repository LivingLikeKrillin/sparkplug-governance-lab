package dev.krillin.sparkplug;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.*;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;

import dev.krillin.sparkplug.kafka.*;
import dev.krillin.sparkplug.schema.DefinitionStore;
import dev.krillin.sparkplug.schema.UdtDefinition;

/**
 * End-to-end demo (live HiveMQ + live Kafka): classic Sparkplug UNS → stateful bridge → Kafka.
 * RBE ↔ compaction (key = metric identity, last-known-value), ISA-95 → topics, contract violations → DLQ.
 * Honesty: JSON records + file-based schema registry (ADR-0007; not Confluent/Avro),
 * single-node KRaft, at-least-once, no seq reordering.
 * Prerequisites: docker compose up -d (HiveMQ :1883 + Kafka :9092)
 * Run from the repo root: mvn -q exec:java -Dexec.mainClass=dev.krillin.sparkplug.UnsToKafkaDemo
 */
public class UnsToKafkaDemo {

    static final String MQTT = "tcp://localhost:1883";
    static final String KAFKA = "localhost:9092";
    static final String GROUP = "Acme:Busan:Press";
    static final String EDGE = "L1:GW3";
    static final String MAIN_TOPIC = "uns.Acme.Busan.Press.L1.GW3";
    static final String DLQ_TOPIC = "uns.dlq";

    public static void main(String[] args) throws Exception {
        System.out.println(">>> [Honesty] JSON records + file-based schema registry (not Confluent/Avro), single-node KRaft, at-least-once, no seq reordering.\n");

        // 0) Load contract from the schema registry (ADR-0007); Motor@1.0.0 = {Rpm:Double, Running:Boolean}
        UdtDefinition motor = new DefinitionStore(Path.of("registry")).latest("Motor")
                .orElseThrow(() -> new IllegalStateException("registry/udt/Motor not found — run from the repo root"));
        System.out.println("[REG] Contract: Motor@" + motor.version() + " " + motor.members());

        // 1) Create Kafka topics: main (compacted) + dlq
        createTopics();

        // 2) Start the bridge (MQTT subscriber + Kafka producer)
        KafkaUnsSink sink = new KafkaUnsSink(KAFKA);
        SparkplugToKafkaBridge bridge = new SparkplugToKafkaBridge(GROUP, motor, sink, DLQ_TOPIC);
        bridge.connect(MQTT);
        Thread.sleep(600);

        // 3) Classic Sparkplug edge node (inline Paho)
        MqttClient edge = new MqttClient(MQTT, "demo-edge", new MemoryPersistence());
        MqttConnectOptions o = new MqttConnectOptions(); o.setCleanSession(true); edge.connect(o);
        SparkplugBPayloadEncoder enc = new SparkplugBPayloadEncoder();

        // NBIRTH: define name↔alias mappings (Rpm alias=1 Double, Running alias=2 Boolean) — contract-conforming
        publish(edge, enc, "NBIRTH", new SparkplugBPayloadBuilder().setTimestamp(new Date()).setSeq(0L)
                .addMetric(new MetricBuilder("bdSeq", MetricDataType.Int64, 0L).createMetric())
                .addMetric(new MetricBuilder("Rpm", MetricDataType.Double, 1500.0).alias(1L).createMetric())
                .addMetric(new MetricBuilder("Running", MetricDataType.Boolean, true).alias(2L).createMetric())
                .createPayload());
        Thread.sleep(300);

        // NDATA seq1 (RBE: only Rpm changed, alias-only payload)
        publish(edge, enc, "NDATA", new SparkplugBPayloadBuilder().setTimestamp(new Date()).setSeq(1L)
                .addMetric(new MetricBuilder(1L, MetricDataType.Double, 1520.0).createMetric())
                .createPayload());
        Thread.sleep(200);

        // NDATA seq2 (RBE: both Rpm and Running changed)
        publish(edge, enc, "NDATA", new SparkplugBPayloadBuilder().setTimestamp(new Date()).setSeq(2L)
                .addMetric(new MetricBuilder(1L, MetricDataType.Double, 1535.0).createMetric())
                .addMetric(new MetricBuilder(2L, MetricDataType.Boolean, false).createMetric())
                .createPayload());
        Thread.sleep(400);

        // 4) Demonstrate RBE ↔ compaction: read main topic from beginning; even with partial RBE,
        //    all metrics show their latest value (last-known-value isomorphism).
        System.out.println("\n>>> [RBE↔compaction] Reading main topic from beginning — latest value per metric:");
        dumpLatest(MAIN_TOPIC);

        // 5) Contract violation: send alias 1 (Rpm) as String → typeMismatch → DLQ
        publish(edge, enc, "NDATA", new SparkplugBPayloadBuilder().setTimestamp(new Date()).setSeq(3L)
                .addMetric(new MetricBuilder(1L, MetricDataType.String, "SENSOR_ERR").createMetric())
                .createPayload());
        Thread.sleep(400);
        System.out.println("\n>>> [DLQ] Contract-violation records (main topic unaffected):");
        dumpLatest(DLQ_TOPIC);

        // 6) NDEATH → STALE tombstones
        publish(edge, enc, "NDEATH", new SparkplugBPayloadBuilder().setTimestamp(new Date())
                .addMetric(new MetricBuilder("bdSeq", MetricDataType.Int64, 0L).createMetric())
                .createPayload());
        Thread.sleep(400);

        edge.disconnect(); edge.close();
        bridge.close();
        sink.close();
        System.out.println("\n=== UNS→KAFKA DEMO DONE ===");
        System.exit(0);
    }

    static void publish(MqttClient edge, SparkplugBPayloadEncoder enc, String type, SparkplugBPayload pl) throws Exception {
        byte[] bytes = enc.getBytes(pl, false);
        String topic = "spBv1.0/" + GROUP + "/" + type + "/" + EDGE;
        edge.publish(topic, bytes, 0, false);
        System.out.println("[EDGE] >> " + topic + " (" + bytes.length + "B, seq=" + (pl.getSeq() != null ? pl.getSeq() : "-") + ")");
    }

    static void createTopics() throws Exception {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA);
        try (Admin admin = Admin.create(p)) {
            List<NewTopic> topics = List.of(
                    new NewTopic(MAIN_TOPIC, 1, (short) 1).configs(Map.of(
                            "cleanup.policy", "compact",
                            "segment.ms", "100",
                            "min.cleanable.dirty.ratio", "0.01")),
                    new NewTopic(DLQ_TOPIC, 1, (short) 1));
            try {
                admin.createTopics(topics).all().get();
                System.out.println("[KAFKA] Topics created: " + MAIN_TOPIC + " (compact) + " + DLQ_TOPIC);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof TopicExistsException)
                    System.out.println("[KAFKA] Topics already exist, reusing.");
                else throw e;
            }
        }
    }

    /** Reads a topic from the beginning and prints the latest value per key (isomorphic to compaction output). */
    static void dumpLatest(String topic) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "demo-reader-" + topic);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            c.subscribe(List.of(topic));
            Map<String, String> latest = new LinkedHashMap<>();
            long end = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < end)
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(300)))
                    latest.put(r.key(), r.value());
            if (latest.isEmpty()) System.out.println("    (empty)");
            latest.forEach((k, v) -> System.out.println("    " + k + " = " + v));
        }
    }
}
