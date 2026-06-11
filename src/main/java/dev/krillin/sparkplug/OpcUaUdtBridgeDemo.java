package dev.krillin.sparkplug;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.PropertySet;
import org.eclipse.tahu.message.model.PropertyValue;
import org.eclipse.tahu.message.model.SparkplugBPayload;

import dev.krillin.sparkplug.opcua.*;
import dev.krillin.sparkplug.schema.*;
import dev.krillin.sparkplug.spb40.*;
import dev.krillin.sparkplug.spb40.DefinitionCodec.ParsedDefinition;
import dev.krillin.sparkplug.spb40.SchemaResolver.ResolvedMetric;
import dev.krillin.sparkplug.spb40.SchemaResolver.Resolution;

/**
 * Live OPC UA information-model to Sparkplug UDT mapping demo, including explicit loss accounting.
 *
 * Flow:
 *   1. Browse a live OPC UA ObjectType via Milo (subtype inheritance + multiple interface inheritance).
 *   2. OpcUaTypeMapper.map -> UdtDefinition + LossLedger. Loss is made visible, not hidden.
 *   3. Consumer (separate Paho client): learns the retained DEFINITION, decodes thin NDATA,
 *      reads side-channel properties.
 *   4. Publisher (separate Paho client): publishes a #608 Definition (retained) then reads the
 *      Motor1 instance and publishes a thin NDATA payload.
 *   5. Consumer prints the typed view, governance flags, and side-channel restoration
 *      (ua_statuscode -> Uncertain, ua_ticks -> original 100ns ticks).
 *
 * Honesty: this mapping is NOT lossless. The truth is preserved in ua_statuscode (verbatim) and
 * ua_ticks (original 100ns ticks since 1601). The #603 quality 3-state is a lossy projection on
 * top of those. SpB 4.0 is not yet published -- this is a concept prototype on Sparkplug 3.0 primitives.
 *
 * Prerequisites: docker (sparkplug-hivemq-ce :1883) + python opcua-sim-server.py running.
 * Run from the repo root: mvn -q exec:java -Dexec.mainClass=dev.krillin.sparkplug.OpcUaUdtBridgeDemo
 */
public class OpcUaUdtBridgeDemo {

    static final String OPCUA  = "opc.tcp://127.0.0.1:4840/freeopcua/server/";
    static final String BROKER = "tcp://localhost:1883";
    static final String GROUP  = "Krillin", EDGE = "OpcUaEdge";
    static final NodeId MOTOR_TYPE = new NodeId(2, "MotorType");
    static final NodeId MOTOR1     = new NodeId(2, "Motor1");
    static final SemVer VERSION    = SemVer.parse("1.0.0");

    // Note: interface-sourced members are now derived from MapResult.flatMembers() provenance — no hardcoding.

    static void banner(String s) {
        System.out.println("\n==================== " + s + " ====================");
    }

    public static void main(String[] args) throws Exception {
        System.out.println(">>> [HONESTY] This mapping is NOT lossless. Loss is acknowledged in the ledger;"
                + " the lossless truth is preserved in the ua_statuscode/ua_ticks side-channels."
                + " SpB 4.0 is not yet published -- this is a concept prototype.");

        // ---- 1. Live browse ----
        banner("1. Live OPC UA browse (Milo)");
        OpcUaClient client = OpcUaClient.create(OPCUA);
        client.connect();
        System.out.println("[OPCUA] connected " + OPCUA);
        UaTypeSpace space = OpcUaBrowser.browse(client, MOTOR_TYPE);
        System.out.println("[OPCUA] browsed type space nodes = " + new ArrayList<>(space.nodes().keySet()));
        for (Map.Entry<String, UaTypeNode> e : space.nodes().entrySet()) {
            UaTypeNode n = e.getValue();
            System.out.println("    " + e.getKey()
                    + "  super=" + n.superType().orElse("-")
                    + "  interfaces=" + n.interfaces()
                    + "  members=" + n.declaredMembers().stream().map(UaMember::browseName).toList());
        }

        // ---- 2. Mapping + loss ledger ----
        banner("2. OPC UA information model -> UDT mapping + loss ledger");
        MapResult mr = OpcUaTypeMapper.map(space, "MotorType", VERSION);
        UdtDefinition udt = mr.udt();
        System.out.println("[MAP] UdtDefinition " + udt.templateRef() + "@" + udt.version()
                + "  members=" + udt.members().size());
        System.out.println("[LEDGER] " + mr.ledger().summary());
        System.out.println("[LEDGER] NOTE: all 4 LossClasses are unit-tested; this live instance exercises CLEAN + PRECISION_LOSS (UNKNOWN handling now exists for unmapped DataTypes).");
        // Build provenance map from real FlatMember data
        Map<String, FlatMember> flatByName = new LinkedHashMap<>();
        for (FlatMember fm : mr.flatMembers()) flatByName.put(fm.name(), fm);

        System.out.println("[LEDGER] Per-member entries (alias = flattening order):");
        List<String> interfaceMembers = new ArrayList<>();
        for (LedgerEntry le : mr.ledger().entries()) {
            long alias = DefinitionCodec.aliasOf(udt, le.memberName());
            FlatMember fm = flatByName.get(le.memberName());
            String src;
            if (fm != null) {
                List<String> ifaceSources = fm.provenance().stream()
                        .filter(p -> p.origin() == Origin.INTERFACE)
                        .map(Provenance::sourceType)
                        .toList();
                if (!ifaceSources.isEmpty()) {
                    src = "INTERFACE(" + String.join("+", ifaceSources) + ")";
                    interfaceMembers.add(le.memberName() + " via " + String.join("+", ifaceSources));
                } else {
                    List<String> otherSources = fm.provenance().stream()
                            .map(p -> p.origin() + ":" + p.sourceType())
                            .toList();
                    src = "ObjectType chain(" + String.join(",", otherSources) + ")";
                }
            } else {
                src = "no provenance";
            }
            String sc = le.sideChannel().map(k -> "  side-channel=" + k).orElse("");
            System.out.printf("    alias#%d  %-16s %-9s -> %-9s  [%s]%s%s%n",
                    alias, le.memberName(), le.uaType(), le.metricType(), le.lossClass(), sc,
                    "  <" + src + ">");
        }
        System.out.println("[PROVENANCE] Interface members inlined into UDT via flattening (based on real provenance) = "
                + interfaceMembers + "  (HasInterface multiple inheritance)");
        System.out.println("[CONFLICTS] " + (mr.conflicts().isEmpty()
                ? "none (deterministic flattening -- no interface type conflicts)" : mr.conflicts()));

        // ---- 3. Consumer connects + subscribes first (to avoid missing messages) ----
        banner("3. Consumer connect + subscribe (before publish)");
        Consumer consumer = new Consumer();
        consumer.connect();
        Thread.sleep(400);

        // ---- 4. Publish: #608 Definition (retained) -> instance read -> thin NDATA ----
        banner("4. Publish -- #608 Definition (retained) + thin NDATA");
        MqttClient pub = new MqttClient(BROKER, "opcua-udt-pub", new MemoryPersistence());
        MqttConnectOptions o = new MqttConnectOptions(); o.setCleanSession(true);
        pub.connect(o);
        SparkplugBPayloadEncoder enc = new SparkplugBPayloadEncoder();

        // Engineering unit map (#607): unit strings from engByMember
        Map<String, String> units = new LinkedHashMap<>();
        mr.engByMember().forEach((m, info) -> { if (info.unit() != null) units.put(m, info.unit()); });

        SparkplugBPayload defPayload = DefinitionCodec.buildDefinition(udt, units);
        byte[] defBytes = enc.getBytes(defPayload, false);
        String defTopic = "spBv1.0/" + GROUP + "/DEFINITION/" + EDGE + "/" + udt.templateRef() + "@" + udt.version();
        pub.publish(defTopic, defBytes, 1, true);  // retained, QoS1
        System.out.println("[PUB] >> DEFINITION " + defTopic + "  (retained, " + defBytes.length + "B, units=" + units + ")");
        Thread.sleep(600);

        List<MemberSample> samples = OpcUaInstanceReader.read(client, MOTOR1, udt);
        System.out.println("[OPCUA] Motor1 samples read = " + samples.size());
        SparkplugBPayload thin = OpcUaThinCodec.buildThin(new SchemaRef(udt.templateRef(), udt.version()), samples, udt);
        byte[] thinBytes = enc.getBytes(thin, false);
        String dataTopic = "spBv1.0/" + GROUP + "/NDATA/" + EDGE;
        pub.publish(dataTopic, thinBytes, 0, false);
        System.out.println("[PUB] >> NDATA " + dataTopic + "  (thin, " + thinBytes.length + "B)");
        Thread.sleep(800);

        // ---- 5. Consumer output is printed via callback (already shown above) ----
        banner("Shutdown");
        consumer.close();
        if (pub.isConnected()) pub.disconnect();
        pub.close();
        client.disconnect();
        System.out.println("\n=== OPC UA -> UDT BRIDGE DEMO DONE ===");
        System.out.println(">>> Live type hierarchy browse -> UDT (with inlined interface members) -> loss ledger -> "
                + "#608 Definition + thin round-trip -> side-channel restoration. NOT lossless -- demonstrated in code.");
        System.exit(0);
    }

    /** Separate Paho consumer: learns the DEFINITION, decodes thin payloads, reads side-channels directly. */
    static final class Consumer implements MqttCallback {
        private MqttClient sub;
        private final SparkplugBPayloadDecoder dec = new SparkplugBPayloadDecoder();
        private final SchemaResolver resolver = new SchemaResolver();
        private final AtomicBoolean shownData = new AtomicBoolean(false);

        void connect() throws Exception {
            MqttConnectOptions o = new MqttConnectOptions(); o.setCleanSession(true);
            sub = new MqttClient(BROKER, "opcua-udt-consumer", new MemoryPersistence());
            sub.setCallback(this);
            sub.connect(o);
            // subscribe to both DEFINITION (retained) and data topics before the publisher sends
            sub.subscribe("spBv1.0/" + GROUP + "/DEFINITION/#", 1);
            sub.subscribe("spBv1.0/" + GROUP + "/+/" + EDGE, 1);
            System.out.println("[CONSUMER] subscribed DEFINITION/# + +/" + EDGE);
        }

        @Override public void connectionLost(Throwable cause) {}
        @Override public void deliveryComplete(IMqttDeliveryToken token) {}

        @Override public void messageArrived(String topic, MqttMessage m) {
            String[] p = topic.split("/");
            if (p.length < 3) return;
            String type = p[2];
            SparkplugBPayload payload;
            try { payload = dec.buildFromByteArray(m.getPayload(), null); }
            catch (Exception e) { System.out.println("[CONSUMER] decode failed " + topic + ": " + e.getMessage()); return; }

            if ("DEFINITION".equals(type)) {
                try {
                    ParsedDefinition pd = DefinitionCodec.parse(payload);
                    String ref = pd.def().templateRef() + "@" + pd.def().version();
                    switch (resolver.learn(pd)) {
                        case LEARNED_NEW -> System.out.println("[CONSUMER] << DEFINITION learned (new) " + ref
                                + " members=" + pd.def().members().size() + " units=" + pd.units());
                        case REDEFINED   -> System.out.println("[CONSUMER] << ! DEFINITION re-definition rejected (immutability violation) " + ref);
                        case UNCHANGED   -> {}
                    }
                } catch (RuntimeException e) { System.out.println("[CONSUMER] DEFINITION parse failed: " + e.getMessage()); }
                return;
            }
            if (type.startsWith("N") && !type.endsWith("CMD")) {
                printResolved(type, payload);
            }
        }

        /** Print the typed view (SchemaResolver), governance violations, and side-channel loss-boundary restoration. */
        private void printResolved(String type, SparkplugBPayload payload) {
            Resolution res = resolver.resolve(payload);
            if (res.awaitingDefinition()) {
                System.out.println("[CONSUMER] << " + type + " unresolved (awaiting Definition " + res.ref() + ")");
                return;
            }
            System.out.println("\n[CONSUMER] << " + type + " resolved (" + res.ref() + "): typed view");
            for (ResolvedMetric rm : res.metrics()) {
                System.out.printf("    %-16s (%-8s) = %-14s quality=%s%s%n",
                        rm.name(), rm.type(), String.valueOf(rm.value()), rm.quality(),
                        rm.engUnit() != null ? "  [" + rm.engUnit() + "]" : "");
            }
            if (!res.violations().isEmpty()) {
                System.out.println("    ! governance violations:");
                res.violations().forEach(v -> System.out.println("      - [" + v.rule() + "] " + v.detail()));
            }
            printSideChannels(payload);
            shownData.set(true);
        }

        /** Read ua_statuscode/ua_ticks directly from the raw payload and display the loss boundary. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void printSideChannels(SparkplugBPayload payload) {
            System.out.println("    --- side-channel (lossless truth) restoration ---");
            for (Metric metric : payload.getMetrics()) {
                if (metric.getAlias() == null) continue;
                PropertySet ps = metric.getProperties();
                if (ps == null) continue;
                long alias = metric.getAlias();

                PropertyValue scPv = ps.getPropertyValue(UaSideChannel.UA_STATUSCODE);
                if (scPv != null && scPv.getValue() != null) {
                    long sc = ((Number) scPv.getValue()).longValue() & 0xFFFFFFFFL;
                    String sev = severityName(sc);
                    Quality projected = UaSideChannel.toQuality(sc);
                    if (!"Good".equals(sev)) {
                        System.out.printf("    alias#%d  ua_statuscode=0x%08X severity=%s "
                                + "(quality projection=%s; truth is in ua_statuscode -- loss boundary)%n",
                                alias, sc, sev, projected);
                    }
                }
                PropertyValue tkPv = ps.getPropertyValue(UaSideChannel.UA_TICKS);
                if (tkPv != null && tkPv.getValue() != null) {
                    long ticks = ((Number) tkPv.getValue()).longValue();
                    long sparkplugMs = (ticks - 116444736000000000L) / 10000L; // 1601->1970 epoch shift, 100ns->ms
                    System.out.printf("    alias#%d  ua_ticks=%d (OPC UA 100ns/1601, lossless) "
                            + "vs Sparkplug ms=%d (10,000x precision + epoch loss)%n",
                            alias, ticks, sparkplugMs);
                }
            }
        }

        private static String severityName(long sc) {
            long sev = sc & 0xC0000000L;
            if (sev == 0x00000000L) return "Good";
            if (sev == 0x80000000L) return "Bad";
            return "Uncertain";
        }

        void close() throws Exception { if (sub != null) { if (sub.isConnected()) sub.disconnect(); sub.close(); } }
    }
}
