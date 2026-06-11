package dev.krillin.sparkplug;

import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.krillin.sparkplug.acl.*;

/**
 * Layered NCMD command authorization demo. Honesty note: broker ACL entries are projection/validation artifacts only
 * (no live RBAC enforcement); identity is the broker's responsibility; no signing; PoC scale.
 * Run: docker compose up -d, then from the repo root:
 *   mvn -q exec:java -Dexec.mainClass=dev.krillin.sparkplug.CommandAclDemo
 */
public class CommandAclDemo {
    static final String BROKER = "tcp://localhost:1883";
    static final String GROUP = "Acme:Busan:Press", EDGE = "L1:GW3";
    static final String NCMD = "spBv1.0/" + GROUP + "/NCMD/" + EDGE;
    static final Path POLICY = Path.of("registry/command-policy.json");

    public static void main(String[] args) throws Exception {
        System.out.println("=== NCMD Command Authorization Demo (layered policy-as-code) ===");
        System.out.println("[NOTE] Broker ACL = projection/validation only / identity = broker / no signing / PoC\n");

        GuardedEdgeNode edge = new GuardedEdgeNode(GROUP, EDGE, POLICY);
        edge.connect(BROKER); edge.birth();

        MqttClient pub = new MqttClient(BROKER, "cmd-issuer", new MemoryPersistence());
        pub.connect();

        send(pub, "1) Rebirth (ops, allowed)",           "Node Control/Rebirth", MetricDataType.Boolean, true);
        send(pub, "2) Rpm=1500 (in range, allowed)",     "Setpoint/Rpm", MetricDataType.Double, 1500.0);
        send(pub, "3) Rpm=99999 (above max, edge DENY)", "Setpoint/Rpm", MetricDataType.Double, 99999.0);
        send(pub, "4) Reboot (not in allowlist, DENY)",  "Node Control/Reboot", MetricDataType.Boolean, true);

        Thread.sleep(500);
        pub.disconnect(); pub.close(); edge.close();

        // 5) broker ACL projection artifact
        ObjectMapper m = AclMapperFactory.create();
        CommandPolicy policy = m.readValue(POLICY.toFile(), CommandPolicy.class);
        System.out.println("\n5) Broker ACL projection (issuer -> node layer, not live-enforced):");
        for (AclEntry e : new BrokerAclProjector().project(policy))
            System.out.println("   " + e.principal() + " -> " + e.topicFilter() + " " + e.permission());

        // 6) CI gate: good policy vs bad policy
        System.out.println("\n6) CI gate:");
        System.out.print("   good policy -> exit ");
        System.out.println(CommandPolicyGate.run(new String[]{ POLICY.toString() }));
        System.out.println("   (see CommandPolicyGateTest for bad-policy examples)");

        System.out.println("\n=== DONE ===");
    }

    static void send(MqttClient pub, String label, String cmd, MetricDataType t, Object v) throws Exception {
        System.out.println("\n--- " + label + " ---");
        SparkplugBPayload p = new SparkplugBPayloadBuilder().setTimestamp(new Date())
                .addMetric(new MetricBuilder(cmd, t, v).createMetric()).createPayload();
        pub.publish(NCMD, new SparkplugBPayloadEncoder().getBytes(p, false), 1, false);
        Thread.sleep(400);
    }
}
