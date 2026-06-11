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
 * Sparkplug B Edge Node — session lifecycle implemented directly on top of
 * the Tahu encoder/decoder and Paho.
 * Covers: NBIRTH/NDATA/NDEATH, LWT (= NDEATH), bdSeq (incremented per connection),
 *         seq (0–255 roll-over), alias-based RBE (NDATA carries alias only),
 *         and NCMD "Node Control/Rebirth" handling.
 * Node-level only; device-level DBIRTH/DDATA is left as an extension.
 */
public class EdgeNode implements MqttCallback {

    private final String group, edgeId;
    private final String tNbirth, tNdata, tNdeath, tNcmd;
    private final SparkplugBPayloadEncoder encoder = new SparkplugBPayloadEncoder();
    private final SparkplugBPayloadDecoder decoder = new SparkplugBPayloadDecoder();
    private MqttClient client;

    private long bdSeq = 0;   // birth/death seq — incremented per connection; pairs NBIRTH with NDEATH
    private int seq = 0;      // payload seq — 0–255, starts at 0 on NBIRTH

    // simulated process values (data source is mocked — Sparkplug session is the focus)
    private double temperature = 20.0;
    private boolean pumpRunning = false;

    public EdgeNode(String group, String edgeId) {
        this.group = group;
        this.edgeId = edgeId;
        this.tNbirth = "spBv1.0/" + group + "/NBIRTH/" + edgeId;
        this.tNdata  = "spBv1.0/" + group + "/NDATA/"  + edgeId;
        this.tNdeath = "spBv1.0/" + group + "/NDEATH/" + edgeId;
        this.tNcmd   = "spBv1.0/" + group + "/NCMD/"   + edgeId;
    }

    private int nextSeq() { int s = seq; seq = (seq + 1) & 0xFF; return s; }
    private byte[] enc(SparkplugBPayload p) throws Exception { return encoder.getBytes(p, false); }

    /** NDEATH payload: contains only bdSeq (no seq field per spec). Used for both LWT and graceful death. */
    private SparkplugBPayload deathPayload() throws Exception {
        return new SparkplugBPayloadBuilder()
                .setTimestamp(new Date())
                .addMetric(new MetricBuilder("bdSeq", MetricDataType.Int64, bdSeq).createMetric())
                .createPayload();
    }

    public void connect(String broker) throws Exception {
        client = new MqttClient(broker, "edge-" + group + "-" + edgeId, new MemoryPersistence());
        client.setCallback(this);
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        // LWT = NDEATH: broker publishes this on abnormal disconnect
        opts.setWill(tNdeath, enc(deathPayload()), 1, false);
        client.connect(opts);
        client.subscribe(tNcmd, 1);
        System.out.println("[EDGE] connected  bdSeq=" + bdSeq + "  (LWT=NDEATH set, sub " + tNcmd + ")");
    }

    /** NBIRTH — full metric set with alias definitions, seq=0. Called on rebirth too: seq resets to 0, bdSeq is preserved. */
    public void birth() throws Exception {
        seq = 0;
        int s = nextSeq(); // 0
        SparkplugBPayload p = new SparkplugBPayloadBuilder()
                .setTimestamp(new Date())
                .setSeq((long) s)
                .addMetric(new MetricBuilder("bdSeq", MetricDataType.Int64, bdSeq).createMetric())
                .addMetric(new MetricBuilder("Node Control/Rebirth", MetricDataType.Boolean, false).createMetric())
                .addMetric(new MetricBuilder("Temperature", MetricDataType.Double, temperature).alias(1L).createMetric())
                .addMetric(new MetricBuilder("Pump/Running", MetricDataType.Boolean, pumpRunning).alias(2L).createMetric())
                .createPayload();
        client.publish(tNbirth, enc(p), 0, false);
        System.out.println("[EDGE] >> NBIRTH seq=" + s + " bdSeq=" + bdSeq + "  (Temperature#1, Pump/Running#2)");
    }

    /** NDATA — alias-only RBE publish. Metrics are identified by alias, not by name. */
    public void publishData() throws Exception {
        temperature = Math.round((temperature + 0.5) * 10) / 10.0;
        pumpRunning = !pumpRunning;
        int s = nextSeq();
        SparkplugBPayload p = new SparkplugBPayloadBuilder()
                .setTimestamp(new Date())
                .setSeq((long) s)
                .addMetric(new MetricBuilder(1L, MetricDataType.Double, temperature).createMetric())   // alias-only
                .addMetric(new MetricBuilder(2L, MetricDataType.Boolean, pumpRunning).createMetric())  // alias-only
                .createPayload();
        client.publish(tNdata, enc(p), 0, false);
        System.out.println("[EDGE] >> NDATA  seq=" + s + "  #1=" + temperature + " #2=" + pumpRunning);
    }

    /** Publishes an explicit NDEATH on graceful shutdown. */
    public void deathGraceful() throws Exception {
        client.publish(tNdeath, enc(deathPayload()), 1, false);
        System.out.println("[EDGE] >> NDEATH (graceful) bdSeq=" + bdSeq);
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
        System.out.println("[EDGE] connection lost: " + cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        if (!topic.equals(tNcmd)) return;
        SparkplugBPayload p = decoder.buildFromByteArray(message.getPayload(), null);
        for (Metric m : p.getMetrics()) {
            if ("Node Control/Rebirth".equals(m.getName()) && Boolean.TRUE.equals(m.getValue())) {
                System.out.println("[EDGE] << NCMD Rebirth received -> republishing NBIRTH");
                // Publishing directly from a Paho callback thread causes deadlock — use a separate thread
                new Thread(() -> {
                    try { birth(); } catch (Exception e) { e.printStackTrace(); }
                }).start();
            }
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) { }
}
