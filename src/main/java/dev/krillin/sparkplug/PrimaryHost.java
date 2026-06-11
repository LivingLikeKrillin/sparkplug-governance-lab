package dev.krillin.sparkplug;

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
 * Sparkplug 3.0 Primary Host Application.
 * STATE topic: spBv1.0/STATE/{hostId}, JSON {"online":bool,"timestamp":...}, retained.
 * On connect: publishes online=true (retained) and registers LWT = online=false (retained).
 * Subscribes to and consumes group data.
 */
public class PrimaryHost implements MqttCallback {

    private final String group, hostId, stateTopic;
    private MqttClient client;
    private final SparkplugBPayloadDecoder dec = new SparkplugBPayloadDecoder();

    public PrimaryHost(String group, String hostId) {
        this.group = group;
        this.hostId = hostId;
        this.stateTopic = "spBv1.0/STATE/" + hostId;
    }

    public void connect(String broker) throws Exception {
        client = new MqttClient(broker, "primaryhost-" + hostId, new MemoryPersistence());
        client.setCallback(this);
        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(true);
        o.setWill(stateTopic, offline().getBytes(), 1, true); // LWT: broker publishes STATE offline (retained) on abnormal disconnect
        client.connect(o);
        // Order matters: subscribe to data BEFORE publishing STATE online — otherwise an edge flush
        // that arrives before the subscription completes will be silently lost (flush race condition).
        client.subscribe("spBv1.0/" + group + "/#", 1);
        client.publish(stateTopic, online().getBytes(), 1, true); // STATE online (retained)
        System.out.println("[HOST:" + hostId + "] subscribed data THEN STATE ONLINE (retained)");
    }

    public void goOfflineGraceful() throws Exception {
        client.publish(stateTopic, offline().getBytes(), 1, true);
        System.out.println("[HOST:" + hostId + "] STATE OFFLINE (graceful)");
        client.disconnect();
        client.close();
    }

    public void close() throws Exception {
        if (client != null) { if (client.isConnected()) client.disconnect(); client.close(); }
    }

    private String online()  { return "{\"online\":true,\"timestamp\":" + System.currentTimeMillis() + "}"; }
    private String offline() { return "{\"online\":false,\"timestamp\":" + System.currentTimeMillis() + "}"; }

    @Override public void connectionLost(Throwable cause) { }
    @Override public void deliveryComplete(IMqttDeliveryToken token) { }
    @Override public void messageArrived(String topic, MqttMessage m) throws Exception {
        String[] p = topic.split("/");
        String type = p.length > 2 ? p[2] : "?";
        SparkplugBPayload pl = dec.buildFromByteArray(m.getPayload(), null);
        StringBuilder sb = new StringBuilder();
        for (Metric mm : pl.getMetrics()) {
            String id = (mm.getName() != null && !mm.getName().isEmpty()) ? mm.getName() : ("#" + mm.getAlias());
            sb.append(id).append("=").append(mm.getValue()).append(" ");
        }
        System.out.println("[HOST:" + hostId + "] << " + type + " seq=" + pl.getSeq() + "  " + sb.toString().trim());
    }
}
