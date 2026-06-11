package dev.krillin.sparkplug.spb40;

import java.util.Map;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import dev.krillin.sparkplug.schema.UdtDefinition;

/** SpB 4.0 #608 thin edge: publishes schemaRef + alias+value + #603 quality. No inline _types_ Template. */
public class Spb40Edge {
    private final String group, edge;
    private MqttClient pub;
    private final SparkplugBPayloadEncoder enc = new SparkplugBPayloadEncoder();

    public Spb40Edge(String group, String edge) { this.group = group; this.edge = edge; }

    public void connect(String broker) throws Exception {
        MqttConnectOptions o = new MqttConnectOptions(); o.setCleanSession(true);
        pub = new MqttClient(broker, "spb40-edge-" + edge, new MemoryPersistence());
        pub.connect(o);
    }

    public int publishThin(String type, UdtDefinition def, Map<String, Object> values,
                           Map<String, Integer> qualities, long seq) throws Exception {
        SparkplugBPayload p = ThinCodec.buildThin(def, values, qualities, seq);
        byte[] bytes = enc.getBytes(p, false);
        String topic = "spBv1.0/" + group + "/" + type + "/" + edge;
        pub.publish(topic, bytes, 0, false);
        System.out.println("[EDGE] >> " + topic + " thin seq=" + seq + " (" + bytes.length + "B)");
        return bytes.length;
    }

    public void close() throws Exception { if (pub != null) { if (pub.isConnected()) pub.disconnect(); pub.close(); } }
}
