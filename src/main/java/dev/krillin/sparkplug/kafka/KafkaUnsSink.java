package dev.krillin.sparkplug.kafka;

import java.util.Properties;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.krillin.sparkplug.kafka.RecordBuilder.Emission;
import dev.krillin.sparkplug.schema.JsonMapperFactory;

/** Produces Emissions to Kafka (value = UnsRecord JSON, key = metric identity). at-least-once. */
public class KafkaUnsSink implements AutoCloseable {

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper mapper = JsonMapperFactory.create();

    public KafkaUnsSink(String bootstrap) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        this.producer = new KafkaProducer<>(p);
    }

    public void emit(Emission e) {
        try {
            String json = mapper.writeValueAsString(e.value());
            // Async send failures are only logged in the callback (at-least-once; acks=all).
            producer.send(new ProducerRecord<>(e.topic(), e.key(), json), (Callback) (metadata, exception) -> {
                if (exception != null)
                    System.out.println("[KAFKA] send failed (async) " + e.topic() + " key=" + e.key() + ": " + exception.getMessage());
            });
            System.out.println("[KAFKA] >> " + e.topic() + " key=" + e.key()
                    + (e.dlq() ? "  [DLQ] " + e.violations() : "  " + json));
        } catch (Exception ex) {
            System.out.println("[KAFKA] emit failed " + e.topic() + ": " + ex.getMessage());
        }
    }

    @Override public void close() { producer.flush(); producer.close(); }
}
