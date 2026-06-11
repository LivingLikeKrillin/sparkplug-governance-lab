package dev.krillin.sparkplug;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.SparkplugBPayload;

/**
 * Sparkplug (protobuf) to plain-JSON UNS bridge.
 * Subscribes to spBv1.0/{group}/# → decodes → republishes retained JSON to
 * uns/{group}/{edge}/{metric}.
 * The bridge must be Sparkplug-stateful: resolving NDATA alias-only metrics requires
 * tracking the name<->alias map established in NBIRTH.
 * Result: a retained current-state namespace readable by any IT tool or late joiner,
 * at the cost of two parallel namespaces (protobuf spBv1.0 + JSON uns) and the
 * associated maintenance and schema-drift governance burden.
 */
public class SparkplugToJsonBridge implements MqttCallback {

    private final String group;
    private MqttClient sub;   // subscribe-only client
    private MqttClient pub;   // publish-only client — publishing from the subscriber's callback thread causes deadlock (same trap as rebirth/flush)
    private final SparkplugBPayloadDecoder dec = new SparkplugBPayloadDecoder();
    private final Map<String, String> aliasToName = new ConcurrentHashMap<>(); // key: edge#alias

    public SparkplugToJsonBridge(String group) { this.group = group; }

    public void connect(String broker) throws Exception {
        MqttConnectOptions o = new MqttConnectOptions(); o.setCleanSession(true);
        pub = new MqttClient(broker, "spb-json-bridge-pub", new MemoryPersistence());
        pub.connect(o);
        sub = new MqttClient(broker, "spb-json-bridge-sub", new MemoryPersistence());
        sub.setCallback(this);
        sub.connect(o);
        sub.subscribe("spBv1.0/" + group + "/#", 1);
        System.out.println("[BRIDGE] subscribed spBv1.0/" + group + "/# -> uns/" + group + "/... (separate pub client)");
    }

    public void close() throws Exception {
        if (sub != null) { if (sub.isConnected()) sub.disconnect(); sub.close(); }
        if (pub != null) { if (pub.isConnected()) pub.disconnect(); pub.close(); }
    }

    @Override public void connectionLost(Throwable cause) { }
    @Override public void deliveryComplete(IMqttDeliveryToken token) { }
    @Override public void messageArrived(String topic, MqttMessage m) throws Exception {
        String[] p = topic.split("/");
        if (p.length < 4) return;
        String type = p[2], edge = p[3];
        if (!type.startsWith("N") && !type.startsWith("D")) return;
        if (type.endsWith("CMD")) return;

        byte[] raw = m.getPayload();
        SparkplugBPayload pl = dec.buildFromByteArray(raw, null);

        if (type.equals("NDATA") || type.equals("DDATA")) {
            System.out.println("[BRIDGE] raw " + type + " = " + raw.length + "B protobuf (opaque binary: " + hexPreview(raw) + " ...) -> republishing as JSON");
        }

        for (Metric metric : pl.getMetrics()) {
            String name = metric.getName();
            if ((name == null || name.isEmpty()) && metric.getAlias() != null) {
                name = aliasToName.get(edge + "#" + metric.getAlias());      // NDATA: resolve alias -> name
            } else if (name != null && metric.getAlias() != null) {
                aliasToName.put(edge + "#" + metric.getAlias(), name);       // NBIRTH: learn the mapping
            }
            if (name == null) continue;
            if (name.startsWith("_types_/") || name.startsWith("Node Control/") || name.equals("bdSeq")) continue;

            String unsTopic = "uns/" + group + "/" + edge + "/" + name;
            String json = "{\"value\":" + toJson(metric.getValue()) + "}";
            pub.publish(unsTopic, json.getBytes(), 0, true);   // retained — published via separate pub client
            System.out.println("[BRIDGE] >> " + unsTopic + " = " + json + " (retained)");
        }
    }

    private static String toJson(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return "\"" + v.toString().replace("\"", "\\\"") + "\"";
    }

    private static String hexPreview(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, b.length); i++) sb.append(String.format("%02x ", b[i]));
        return sb.toString().trim();
    }
}
