package dev.krillin.sparkplug.spb40;

import java.util.Map;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import dev.krillin.sparkplug.schema.UdtDefinition;

/** SpB 4.0 #608: publishes a registered Definition payload retained to spBv1.0/{group}/DEFINITION/{edge}/{ref}. */
public class DefinitionPublisher {
    private final String group, edge;
    private MqttClient pub;
    private final SparkplugBPayloadEncoder enc = new SparkplugBPayloadEncoder();

    public DefinitionPublisher(String group, String edge) { this.group = group; this.edge = edge; }

    public void connect(String broker) throws Exception {
        MqttConnectOptions o = new MqttConnectOptions(); o.setCleanSession(true);
        pub = new MqttClient(broker, "spb40-def-pub", new MemoryPersistence());
        pub.connect(o);
    }

    public int publishDefinition(UdtDefinition def, Map<String, String> units) throws Exception {
        SparkplugBPayload p = DefinitionCodec.buildDefinition(def, units);
        byte[] bytes = enc.getBytes(p, false);
        String topic = "spBv1.0/" + group + "/DEFINITION/" + edge + "/" + def.templateRef();
        pub.publish(topic, bytes, 1, true);
        System.out.println("[DEF-PUB] >> " + topic + " (retained, " + bytes.length + "B)");
        return bytes.length;
    }

    public void close() throws Exception { if (pub != null) { if (pub.isConnected()) pub.disconnect(); pub.close(); } }
}
