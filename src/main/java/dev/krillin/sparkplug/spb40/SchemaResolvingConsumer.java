package dev.krillin.sparkplug.spb40;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import dev.krillin.sparkplug.spb40.SchemaResolver.Resolution;
import dev.krillin.sparkplug.spb40.SchemaResolver.ResolvedMetric;

/** SpB 4.0 #608 consumer: learns schemas from retained DEFINITION payloads, resolves thin data, and prints governance flags. */
public class SchemaResolvingConsumer implements MqttCallback {
    private final String group;
    private MqttClient sub;
    private final SparkplugBPayloadDecoder dec = new SparkplugBPayloadDecoder();
    private final SchemaResolver resolver = new SchemaResolver();

    public SchemaResolvingConsumer(String group) { this.group = group; }

    public void connect(String broker) throws Exception {
        MqttConnectOptions o = new MqttConnectOptions(); o.setCleanSession(true);
        sub = new MqttClient(broker, "spb40-consumer", new MemoryPersistence());
        sub.setCallback(this);
        sub.connect(o);
        sub.subscribe("spBv1.0/" + group + "/#", 1);
        System.out.println("[CONSUMER] subscribed spBv1.0/" + group + "/#");
    }

    @Override public void connectionLost(Throwable cause) { }
    @Override public void deliveryComplete(IMqttDeliveryToken token) { }
    @Override public void messageArrived(String topic, MqttMessage m) throws Exception {
        String[] p = topic.split("/");
        if (p.length < 3) return;
        String type = p[2];
        SparkplugBPayload payload;
        try { payload = dec.buildFromByteArray(m.getPayload(), null); }
        catch (Exception e) { System.out.println("[CONSUMER] decode failed " + topic + ": " + e.getMessage()); return; }

        if ("DEFINITION".equals(type)) {
            try {
                DefinitionCodec.ParsedDefinition pd = DefinitionCodec.parse(payload);
                String ref = pd.def().templateRef() + "@" + pd.def().version();
                switch (resolver.learn(pd)) {
                    case LEARNED_NEW -> System.out.println("[CONSUMER] << DEFINITION learned (new): " + ref
                            + " members=" + pd.def().members().size() + " units=" + pd.units());
                    case REDEFINED -> System.out.println("[CONSUMER] << ! DEFINITION redefinition rejected (immutability violation): " + ref + " — original retained");
                    case UNCHANGED -> { /* retained re-delivery / republish — idempotent no-op */ }
                }
            } catch (RuntimeException e) { System.out.println("[CONSUMER] DEFINITION parse failed: " + e.getMessage()); }
            return;
        }
        if (type.startsWith("N") && !type.endsWith("CMD")) {
            Resolution res = resolver.resolve(payload);
            if (res.awaitingDefinition()) {
                System.out.println("[CONSUMER] << " + type + " unresolved (awaiting Definition " + res.ref() + ")");
                return;
            }
            System.out.println("[CONSUMER] << " + type + " resolved (" + res.ref() + "):");
            for (ResolvedMetric rm : res.metrics())
                System.out.println("    " + rm.name() + " (" + rm.type() + ") = " + rm.value()
                        + "  quality=" + rm.quality() + (rm.engUnit() != null ? "  [" + rm.engUnit() + "]" : ""));
            if (!res.violations().isEmpty()) {
                System.out.println("    ! governance violations:");
                res.violations().forEach(v -> System.out.println("      - [" + v.rule() + "] " + v.detail()));
            }
        }
    }

    public void close() throws Exception { if (sub != null) { if (sub.isConnected()) sub.disconnect(); sub.close(); } }
}
