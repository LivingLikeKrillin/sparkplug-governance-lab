package dev.krillin.sparkplug.kafka;

import java.util.List;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.model.SparkplugBPayload;

import dev.krillin.sparkplug.kafka.ContractValidator.Conformance;
import dev.krillin.sparkplug.kafka.RecordBuilder.Emission;
import dev.krillin.sparkplug.kafka.TopicMapper.UnsAddress;
import dev.krillin.sparkplug.kafka.UnsStateStore.ResolvedMetric;
import dev.krillin.sparkplug.schema.UdtDefinition;

/**
 * Stateful bridge from Sparkplug (MQTT) to Kafka. All logic is delegated to the pure core
 * (UnsStateStore / TopicMapper / ContractValidator / RecordBuilder); the MQTT callback
 * only handles decode and routing wiring. Uses a dedicated client per the standard
 * Paho concurrency recommendation.
 */
public class SparkplugToKafkaBridge implements MqttCallback {

    private final String group;
    private final UdtDefinition contract;          // nullable
    private final KafkaUnsSink sink;
    private final UnsStateStore state = new UnsStateStore();
    private final TopicMapper mapper = new TopicMapper();
    private final ContractValidator validator = new ContractValidator();
    private final RecordBuilder builder;
    private final SparkplugBPayloadDecoder dec = new SparkplugBPayloadDecoder();
    private MqttClient sub;

    public SparkplugToKafkaBridge(String group, UdtDefinition contract, KafkaUnsSink sink, String dlqTopic) {
        this.group = group; this.contract = contract; this.sink = sink;
        this.builder = new RecordBuilder(mapper, validator, dlqTopic);
    }

    public void connect(String broker) throws Exception {
        MqttConnectOptions o = new MqttConnectOptions(); o.setCleanSession(true);
        sub = new MqttClient(broker, "spb-kafka-bridge", new MemoryPersistence());
        sub.setCallback(this);
        sub.connect(o);
        sub.subscribe("spBv1.0/" + group + "/#", 1);
        System.out.println("[BRIDGE] subscribed spBv1.0/" + group + "/# -> Kafka");
    }

    @Override public void connectionLost(Throwable cause) { }
    @Override public void deliveryComplete(IMqttDeliveryToken token) { }

    @Override public void messageArrived(String topic, MqttMessage msg) {
        String[] p = topic.split("/");
        if (p.length < 4) return;
        String type = p[2], edge = p[3];
        if (type.endsWith("CMD")) return;

        SparkplugBPayload payload;
        try { payload = dec.buildFromByteArray(msg.getPayload(), null); }
        catch (Exception e) { System.out.println("[BRIDGE] decode failed " + topic + ": " + e.getMessage()); return; }

        UnsAddress addr;
        try { addr = mapper.map(topic); }
        catch (RuntimeException e) { System.out.println("[BRIDGE] topic mapping failed " + topic + ": " + e.getMessage()); return; }

        long ts = payload.getTimestamp() != null ? payload.getTimestamp().getTime() : 0L;
        long seq = payload.getSeq() != null ? payload.getSeq() : -1L;

        switch (type) {
            case "NBIRTH", "DBIRTH" -> {
                state.learnBirth(edge, payload);
                List<ResolvedMetric> birth = List.copyOf(state.lastKnown(edge));
                if (contract != null) {
                    Conformance c = validator.validateBirth(contract, birth);
                    if (!c.ok()) System.out.println("[BRIDGE] " + type + " contract violation (" + edge + "): " + c.violations());
                }
                emitAll(builder.build(addr, contract, birth, seq, ts));
                System.out.println("[BRIDGE] " + type + " learned " + edge + " (" + birth.size() + " metrics)");
            }
            case "NDATA", "DDATA" -> {
                if (state.awaitingBirth(edge)) {
                    System.out.println("[BRIDGE] " + type + " before NBIRTH(" + edge + ") → drop, await rebirth");
                    return;
                }
                emitAll(builder.build(addr, contract, state.applyData(edge, payload), seq, ts));
            }
            case "NDEATH", "DDEATH" -> {
                List<ResolvedMetric> stale = state.markDeath(edge);
                emitAll(builder.build(addr, contract, stale, seq, ts));
                System.out.println("[BRIDGE] " + type + " → " + stale.size() + " STALE tombstones");
            }
            default -> { }
        }
    }

    private void emitAll(List<Emission> es) { for (Emission e : es) sink.emit(e); }

    public void close() throws Exception {
        if (sub != null) { if (sub.isConnected()) sub.disconnect(); sub.close(); }
    }
}
