package dev.krillin.sparkplug;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.eclipse.tahu.message.model.Template;

import dev.krillin.sparkplug.drift.*;
import dev.krillin.sparkplug.schema.DefinitionStore;
import dev.krillin.sparkplug.schema.TemplateAdapter;
import dev.krillin.sparkplug.schema.UdtDefinition;

/**
 * Passively monitors runtime schema and liveness drift (detect-only). Subscribes to spBv1.0/#.
 * Compares NBIRTH definitions (_types_/ metrics) against the registry source-of-truth and tracks
 * liveness. OT data is never dropped or modified.
 */
public class DriftMonitor implements MqttCallback {

    private final DefinitionStore registry;
    private final SchemaDriftDetector detector = new SchemaDriftDetector();
    private final LivenessTracker liveness = new LivenessTracker();
    private final GovernanceHealth health = new GovernanceHealth();
    // Note: audit and liveness collections are unsynchronized. messageArrived (Paho callback thread)
    //   mutates them; report() reads from the caller thread. This is safe only because the demo
    //   is sequential (publish → sleep → report). Multi-threaded collection and HA are out of scope for this PoC.
    private final List<DriftEvent> audit = new ArrayList<>();
    private final SparkplugBPayloadDecoder decoder = new SparkplugBPayloadDecoder();
    private MqttClient client;

    public DriftMonitor(Path registryRoot) { this.registry = new DefinitionStore(registryRoot); }

    public void connect(String broker) throws Exception {
        client = new MqttClient(broker, "drift-monitor", new MemoryPersistence());
        client.setCallback(this);
        client.connect();
        client.subscribe("spBv1.0/#", 0);
        System.out.println("[DRIFT] connected, subscribed spBv1.0/# (detect-only)");
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String[] parts = topic.split("/");
        if (parts.length < 4) return;                       // spBv1.0/{group}/{type}/{edge}
        String type = parts[2];
        String nodeKey = parts[1] + "/" + parts[3];
        long ts = System.currentTimeMillis();
        try {
            switch (type) {
                case "NBIRTH" -> {
                    liveness.markSeen(nodeKey, ts);
                    SparkplugBPayload p = decoder.buildFromByteArray(message.getPayload(), null);
                    for (Metric m : p.getMetrics()) {
                        if (m.getDataType() == MetricDataType.Template
                                && m.getName() != null && m.getName().startsWith("_types_/")) {
                            Template t = (Template) m.getValue();
                            if (t == null || !t.isDefinition()) continue;    // skip instances, definitions only
                            String ref = m.getName().substring("_types_/".length());
                            UdtDefinition observed = TemplateAdapter.fromTahuTemplate(ref, t);
                            Optional<UdtDefinition> registered = registry.latest(ref);
                            record(detector.detect(nodeKey, registered, observed, ts));
                        }
                    }
                }
                case "NDATA" -> liveness.markSeen(nodeKey, ts);
                case "NDEATH" -> liveness.markDeath(nodeKey);
                default -> { }
            }
        } catch (Exception e) {   // error isolation: non-fatal — must not propagate through Paho callback (detect-only)
            System.out.println("[DRIFT] WARN non-fatal — " + nodeKey + ": " + e.getMessage());
        }
    }

    private void record(List<DriftEvent> es) {
        for (DriftEvent e : es) {
            audit.add(e);
            System.out.println("[DRIFT] WARN " + e.kind() + " @" + e.nodeKey() + " -- " + e.detail());
        }
    }

    /** Detects newly stale nodes (appending STALE events) and prints a governance health snapshot.
     *  now and thresholdMs are injected for deterministic testing. */
    public HealthSnapshot report(long now, long thresholdMs) {
        for (String node : liveness.stale(now, thresholdMs)) {
            boolean already = audit.stream().anyMatch(e -> e.kind() == DriftKind.STALE && e.nodeKey().equals(node));
            if (!already) {
                audit.add(new DriftEvent(node, DriftKind.STALE, "no message received within threshold " + thresholdMs + " ms", now));
                System.out.println("[DRIFT] WARN STALE @" + node);
            }
        }
        HealthSnapshot s = health.summarize(liveness.known(), audit);
        System.out.println("[DRIFT] health: total=" + s.totalNodes() + " / conformant=" + s.conformantNodes()
                + " / " + Math.round(s.conformanceRate() * 100) + "% / drift=" + s.driftCountByKind()
                + " / stale=" + s.staleNodeCount());
        return s;
    }

    public List<DriftEvent> audit() { return List.copyOf(audit); }

    public void close() throws Exception {
        if (client != null) { if (client.isConnected()) client.disconnect(); client.close(); }
    }

    @Override public void connectionLost(Throwable cause) { System.out.println("[DRIFT] lost: " + cause); }
    @Override public void deliveryComplete(IMqttDeliveryToken token) { }
}
