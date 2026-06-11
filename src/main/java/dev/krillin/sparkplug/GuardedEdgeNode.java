package dev.krillin.sparkplug;

import java.nio.file.Path;
import java.util.Date;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.krillin.sparkplug.acl.*;

/**
 * Self-contained Sparkplug Edge Node with an embedded authorization hook (a sibling of EdgeNode, not a subclass).
 * Incoming NCMDs are evaluated by CommandAuthorizer; only ALLOW decisions are executed
 * (Rebirth triggers a re-birth, Setpoint/Rpm updates a mock state value).
 * DENY decisions are rejected and logged for audit. Identity (principal) is the broker's responsibility.
 */
public class GuardedEdgeNode implements MqttCallback {

    private final String group, edgeId, tNbirth, tNdata, tNdeath, tNcmd;
    private final SparkplugBPayloadEncoder encoder = new SparkplugBPayloadEncoder();
    private final SparkplugBPayloadDecoder decoder = new SparkplugBPayloadDecoder();
    private final CommandAuthorizer authorizer = new CommandAuthorizer();
    private final CommandPolicy policy;
    private MqttClient client;

    private long bdSeq = 0;
    private int seq = 0;
    private double rpm = 0.0;            // mock setpoint state (not present in base EdgeNode)

    public GuardedEdgeNode(String group, String edgeId, Path policyJson) throws Exception {
        this.group = group; this.edgeId = edgeId;
        this.tNbirth = "spBv1.0/" + group + "/NBIRTH/" + edgeId;
        this.tNdata  = "spBv1.0/" + group + "/NDATA/"  + edgeId;
        this.tNdeath = "spBv1.0/" + group + "/NDEATH/" + edgeId;
        this.tNcmd   = "spBv1.0/" + group + "/NCMD/"   + edgeId;
        ObjectMapper m = AclMapperFactory.create();
        this.policy = m.readValue(policyJson.toFile(), CommandPolicy.class);
    }

    private int nextSeq() { int s = seq; seq = (seq + 1) & 0xFF; return s; }
    private byte[] enc(SparkplugBPayload p) throws Exception { return encoder.getBytes(p, false); }

    private SparkplugBPayload deathPayload() throws Exception {
        return new SparkplugBPayloadBuilder().setTimestamp(new Date())
                .addMetric(new MetricBuilder("bdSeq", MetricDataType.Int64, bdSeq).createMetric())
                .createPayload();
    }

    public void connect(String broker) throws Exception {
        client = new MqttClient(broker, "guarded-" + group + "-" + edgeId, new MemoryPersistence());
        client.setCallback(this);
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setWill(tNdeath, enc(deathPayload()), 1, false);
        client.connect(opts);
        client.subscribe(tNcmd, 1);
        System.out.println("[GUARD] connected (sub " + tNcmd + ", policy rules=" + policy.rules().size() + ")");
    }

    public void birth() throws Exception {
        seq = 0;
        int s = nextSeq();
        SparkplugBPayload p = new SparkplugBPayloadBuilder().setTimestamp(new Date()).setSeq((long) s)
                .addMetric(new MetricBuilder("bdSeq", MetricDataType.Int64, bdSeq).createMetric())
                .addMetric(new MetricBuilder("Node Control/Rebirth", MetricDataType.Boolean, false).createMetric())
                .addMetric(new MetricBuilder("Setpoint/Rpm", MetricDataType.Double, rpm).alias(1L).createMetric())
                .createPayload();
        client.publish(tNbirth, enc(p), 0, false);
        System.out.println("[GUARD] >> NBIRTH seq=" + s + " Rpm=" + rpm);
    }

    public void close() throws Exception {
        if (client != null) { if (client.isConnected()) client.disconnect(); client.close(); }
    }

    @Override public void connectionLost(Throwable cause) { System.out.println("[GUARD] lost: " + cause); }
    @Override public void deliveryComplete(IMqttDeliveryToken token) { }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        if (!topic.equals(tNcmd)) return;
        SparkplugBPayload p = decoder.buildFromByteArray(message.getPayload(), null);
        for (Metric m : p.getMetrics()) {
            CommandRequest req = new CommandRequest(
                    new Target(group, edgeId, null), m.getName(), m.getValue(),
                    m.getDataType().toString());
            Decision d = authorizer.authorize(policy, req);
            if (!d.allowed()) {
                System.out.println("[GUARD] << NCMD DENY  cmd=" + m.getName()
                        + " val=" + m.getValue() + " reason=" + d.reason());
                continue;
            }
            System.out.println("[GUARD] << NCMD ALLOW cmd=" + m.getName() + " rule=" + d.ruleId());
            execute(m);
        }
    }

    /** Executes a command that has passed authorization. Publishing from the MQTT callback thread is not allowed; dispatch to a separate thread. */
    private void execute(Metric m) {
        if ("Node Control/Rebirth".equals(m.getName()) && Boolean.TRUE.equals(m.getValue())) {
            new Thread(() -> { try { birth(); } catch (Exception e) { e.printStackTrace(); } }).start();
        } else if ("Setpoint/Rpm".equals(m.getName())) {
            rpm = ((Number) m.getValue()).doubleValue();
            System.out.println("[GUARD]    Rpm setpoint applied -> " + rpm);
        }
    }
}
