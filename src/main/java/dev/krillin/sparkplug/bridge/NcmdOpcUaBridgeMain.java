package dev.krillin.sparkplug.bridge;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.krillin.sparkplug.acl.AclMapperFactory;
import dev.krillin.sparkplug.acl.CommandPolicy;

/**
 * Runnable self-bridge. Wires the broker, the OPC-UA endpoint, the Sparkplug identity, and the edge
 * policy from env/args (with demo defaults), then connects and prints {@code [BRIDGE] ready} — the
 * line the Chunk-3 gate waits on.
 *
 * <pre>
 *   MQTT_URL     tcp://localhost:1883            (broker)
 *   OPCUA_URL    opc.tcp://localhost:48400       (shared koshei Milo sim)
 *   SPB_GROUP    Koshei:Line1
 *   SPB_EDGE     recipe-edge
 *   POLICY_PATH  registry/koshei-line1-policy.json
 * </pre>
 *
 * Run: {@code mvn -q compile exec:java -Dexec.mainClass=dev.krillin.sparkplug.bridge.NcmdOpcUaBridgeMain}
 */
public final class NcmdOpcUaBridgeMain {

    public static void main(String[] args) throws Exception {
        String broker = env("MQTT_URL", "tcp://localhost:1883");
        String opcua = env("OPCUA_URL", "opc.tcp://localhost:48400");
        String group = env("SPB_GROUP", "Koshei:Line1");
        String edge = env("SPB_EDGE", "recipe-edge");
        String policyPath = env("POLICY_PATH", "registry/koshei-line1-policy.json");

        ObjectMapper mapper = AclMapperFactory.create();
        CommandPolicy policy = mapper.readValue(Path.of(policyPath).toFile(), CommandPolicy.class);
        System.out.println("[BRIDGE] policy loaded " + policyPath + " (rules=" + policy.rules().size()
                + ", default=" + policy.defaultEffect() + ")");

        OpcUaApplier applier = new OpcUaApplier(opcua).connect();
        System.out.println("[BRIDGE] OPC-UA connected " + opcua);

        NcmdOpcUaBridge bridge = new NcmdOpcUaBridge(group, edge, policy, applier);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                bridge.close();
            } catch (Exception ignore) {
                // best-effort
            }
            applier.close();
        }));

        bridge.connect(broker);
        System.out.println("[BRIDGE] ready");
    }

    private static String env(String key, String dflt) {
        String v = System.getenv(key);
        return v != null && !v.isBlank() ? v : dflt;
    }

    private NcmdOpcUaBridgeMain() {}
}
