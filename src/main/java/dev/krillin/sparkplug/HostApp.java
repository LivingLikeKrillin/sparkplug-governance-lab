package dev.krillin.sparkplug;

import java.util.Date;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;

/**
 * Sparkplug B Host Application — subscribes to a full group, decodes payloads,
 * prints each message type, checks seq continuity (gap detection), and
 * publishes NCMD Rebirth.
 */
public class HostApp implements MqttCallback {

    private final String group;
    private MqttClient client;
    private final SparkplugBPayloadDecoder decoder = new SparkplugBPayloadDecoder();
    private final SparkplugBPayloadEncoder encoder = new SparkplugBPayloadEncoder();

    private long lastSeq = -1; // last received seq, used for continuity checking

    public HostApp(String group) { this.group = group; }

    public void connect(String broker) throws Exception {
        connect(broker, false);
    }

    /**
     * @param allGroups when true, subscribes to spBv1.0/# regardless of group — useful for
     *                  interop testing where external vendors (e.g. Ignition/Cirrus Link) may
     *                  publish under a different group_id.
     */
    public void connect(String broker, boolean allGroups) throws Exception {
        client = new MqttClient(broker, "host-" + group, new MemoryPersistence());
        client.setCallback(this);
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        client.connect(opts);
        String filter = allGroups ? "spBv1.0/#" : ("spBv1.0/" + group + "/#");
        client.subscribe(filter, 1);
        System.out.println("[HOST] connected, subscribed " + filter);
    }

    /** Sends an NCMD Rebirth request to the specified edge node. */
    public void requestRebirth(String edgeId) throws Exception {
        String tNcmd = "spBv1.0/" + group + "/NCMD/" + edgeId;
        SparkplugBPayload p = new SparkplugBPayloadBuilder()
                .setTimestamp(new Date())
                .addMetric(new MetricBuilder("Node Control/Rebirth", MetricDataType.Boolean, true).createMetric())
                .createPayload();
        client.publish(tNcmd, encoder.getBytes(p, false), 1, false);
        System.out.println("[HOST] >> NCMD Rebirth -> " + edgeId);
    }

    public void close() throws Exception {
        if (client != null) {
            if (client.isConnected()) client.disconnect();
            client.close();
        }
    }

    // --- MqttCallback ---
    @Override
    public void connectionLost(Throwable cause) {
        System.out.println("[HOST] connection lost: " + cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        // spBv1.0/{group}/{type}/{edge}[/{device}]
        String[] parts = topic.split("/");
        String grp  = parts.length > 1 ? parts[1] : "?";
        String type = parts.length > 2 ? parts[2] : "?";
        String edge = parts.length > 3 ? parts[3] : "?";
        String dev  = parts.length > 4 ? "/" + parts[4] : "";
        if ("NCMD".equals(type) || "DCMD".equals(type)) return; // skip echo of our own commands

        // STATE is the only non-Protobuf payload in Sparkplug (spBv1.0/STATE/{host_id} carries JSON).
        // When subscribed to spBv1.0/#, attempting to decode it as Protobuf will throw — branch required.
        if ("STATE".equals(grp)) {
            String json = new String(message.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("[HOST] << [STATE host=" + type + "] (JSON) " + json);
            return;
        }

        // Defensively decode: non-Sparkplug payloads may arrive on a wildcard subscription
        // and must not kill the callback.
        SparkplugBPayload p;
        try {
            p = decoder.buildFromByteArray(message.getPayload(), null);
        } catch (Exception e) {
            System.out.println("[HOST] << [" + grp + "/" + edge + dev + "] " + type
                    + "  WARNING decode failed (non-Sparkplug payload?): " + e.getMessage());
            return;
        }
        Long seq = p.getSeq();

        StringBuilder sb = new StringBuilder();
        for (Metric m : p.getMetrics()) {
            String id = (m.getName() != null && !m.getName().isEmpty())
                    ? m.getName() : ("#" + m.getAlias());
            sb.append(id).append("=").append(m.getValue()).append("  ");
        }

        String gap = "";
        if (("NDATA".equals(type) || "DDATA".equals(type)) && lastSeq >= 0) {
            long expected = (lastSeq + 1) & 0xFF;
            if (seq != null && seq != expected) gap = "  WARNING seq gap (expected " + expected + ") — rebirth required";
        }
        if (seq != null) lastSeq = seq;

        System.out.println("[HOST] << [" + grp + "/" + edge + dev + "] " + type
                + " seq=" + seq + "  " + sb.toString().trim() + gap);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) { }
}
