package dev.krillin.sparkplug;

/**
 * Interop Demo B runner — this Tahu-based EdgeNode publishes NBIRTH/NDATA;
 * Ignition MQTT Engine subscribes and surfaces the tags in the Tag Browser.
 * Demonstrates interop from this implementation to a vendor implementation.
 *
 * Run: mvn -q exec:java -Dexec.mainClass=dev.krillin.sparkplug.InteropEdge
 *      (optional args: -Dexec.args="tcp://localhost:1883 Krillin TahuEdge1")
 * Stop: Ctrl+C — triggers a graceful NDEATH via shutdown hook.
 *
 * Verify that tags appear in Ignition for the configured group/edge (default: Krillin/TahuEdge1).
 */
public class InteropEdge {
    public static void main(String[] args) throws Exception {
        String broker = args.length > 0 ? args[0] : "tcp://localhost:1883";
        String group  = args.length > 1 ? args[1] : "Krillin";
        String edge   = args.length > 2 ? args[2] : "TahuEdge1";

        EdgeNode node = new EdgeNode(group, edge);
        node.connect(broker);
        node.birth(); // publish NBIRTH immediately — no host-wait; Ignition Engine receives it directly
        System.out.println("[INTEROP-EDGE] " + group + "/" + edge
                + " NBIRTH complete. Publishing NDATA every 2 s... (Ctrl+C to stop)");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { node.deathGraceful(); node.close(); } catch (Exception ignored) {}
        }));

        while (true) {
            Thread.sleep(2000);
            node.publishData();
        }
    }
}
