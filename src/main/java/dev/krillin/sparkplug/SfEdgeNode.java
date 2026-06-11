package dev.krillin.sparkplug;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Store-and-forward Edge Node.
 * Subscribes to the Primary Host STATE topic — buffers NDATA while the host is OFFLINE
 * and flushes in order when the host comes back ONLINE.
 * Key concurrency concern: the STATE callback (Paho thread) and publishData (main thread)
 * share the buffer and flag — a lock is required for consistency.
 */
public class SfEdgeNode implements MqttCallback {

    private final String group, edgeId, stateTopic, tNbirth, tNdata;
    private final SparkplugBPayloadEncoder enc = new SparkplugBPayloadEncoder();
    private final ObjectMapper mapper = new ObjectMapper();
    private MqttClient client;

    private int seq = 0;
    private double temperature = 20.0;

    private final Object lock = new Object();
    private boolean primaryOnline = false;          // updated from STATE messages
    private final List<byte[]> buffer = new ArrayList<>();  // store-and-forward buffer

    public SfEdgeNode(String group, String edgeId, String primaryHostId) {
        this.group = group;
        this.edgeId = edgeId;
        this.stateTopic = "spBv1.0/STATE/" + primaryHostId;
        this.tNbirth = "spBv1.0/" + group + "/NBIRTH/" + edgeId;
        this.tNdata  = "spBv1.0/" + group + "/NDATA/"  + edgeId;
    }

    private int nextSeq() { int s = seq; seq = (seq + 1) & 0xFF; return s; }

    public void connect(String broker) throws Exception {
        client = new MqttClient(broker, "sfedge-" + group + "-" + edgeId, new MemoryPersistence());
        client.setCallback(this);
        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(true);
        client.connect(o);
        client.subscribe(stateTopic, 1);  // watch primary host STATE
        System.out.println("[SFEDGE] connected, watching " + stateTopic);
    }

    public void birth() throws Exception {
        seq = 0;
        SparkplugBPayload p = new SparkplugBPayloadBuilder()
                .setTimestamp(new Date()).setSeq((long) nextSeq())
                .addMetric(new MetricBuilder("Temperature", MetricDataType.Double, temperature).alias(1L).createMetric())
                .createPayload();
        client.publish(tNbirth, enc.getBytes(p, false), 0, false);
        System.out.println("[SFEDGE] >> NBIRTH seq=0");
    }

    /** Produces a data point: publishes immediately if the primary host is online, otherwise buffers for later flush. */
    public void publishData() throws Exception {
        temperature = Math.round((temperature + 0.5) * 10) / 10.0;
        int s = nextSeq();
        SparkplugBPayload p = new SparkplugBPayloadBuilder()
                .setTimestamp(new Date()).setSeq((long) s)
                .addMetric(new MetricBuilder(1L, MetricDataType.Double, temperature).createMetric())
                .createPayload();
        byte[] bytes = enc.getBytes(p, false);
        synchronized (lock) {
            if (primaryOnline) {
                client.publish(tNdata, bytes, 0, false);
                System.out.println("[SFEDGE] >> NDATA seq=" + s + " temp=" + temperature + "  (LIVE)");
            } else {
                buffer.add(bytes);
                System.out.println("[SFEDGE] ~~ NDATA seq=" + s + " temp=" + temperature + "  (BUFFERED, host offline)  buffer size=" + buffer.size());
            }
        }
    }

    /** Flushes the buffer in order.
     *  Must be called from a separate thread — publishing from a Paho callback thread causes deadlock.
     *  Snapshot the buffer under the lock; publish outside the lock. */
    private void flush() throws Exception {
        List<byte[]> toSend;
        synchronized (lock) {
            if (buffer.isEmpty()) return;
            toSend = new ArrayList<>(buffer);
            buffer.clear();
        }
        System.out.println("[SFEDGE] -- host ONLINE: flushing " + toSend.size() + " buffered message(s) in order --");
        for (byte[] b : toSend) client.publish(tNdata, b, 0, false);
    }

    @Override public void connectionLost(Throwable cause) { }
    @Override public void deliveryComplete(IMqttDeliveryToken token) { }
    @Override public void messageArrived(String topic, MqttMessage m) throws Exception {
        if (!topic.equals(stateTopic)) return;
        boolean online = false;
        try { JsonNode n = mapper.readTree(m.getPayload()); online = n.get("online").asBoolean(); } catch (Exception ignore) { }
        boolean shouldFlush;
        synchronized (lock) {
            boolean was = primaryOnline;
            primaryOnline = online;
            shouldFlush = online && !was && !buffer.isEmpty();
        }
        System.out.println("[SFEDGE] << STATE primaryOnline=" + online);
        // Do not publish from the Paho callback thread — use a separate thread (same rule as rebirth)
        if (shouldFlush) new Thread(() -> { try { flush(); } catch (Exception e) { e.printStackTrace(); } }).start();
    }

    public void close() throws Exception {
        if (client != null) { if (client.isConnected()) client.disconnect(); client.close(); }
    }
}
