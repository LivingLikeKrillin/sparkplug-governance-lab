package dev.krillin.sparkplug;

/**
 * Interop Demo A runner — this Tahu-based HostApp subscribes to the full group wildcard
 * and decodes Sparkplug B messages published by Ignition (Cirrus Link Transmission).
 * Demonstrates interop from a vendor implementation to this implementation.
 *
 * Run: mvn -q exec:java -Dexec.mainClass=dev.krillin.sparkplug.InteropHost
 *      (optional broker arg: -Dexec.args="tcp://localhost:1883")
 * Stop: Ctrl+C
 */
public class InteropHost {
    public static void main(String[] args) throws Exception {
        String broker = args.length > 0 ? args[0] : "tcp://localhost:1883";
        HostApp host = new HostApp("interop"); // "interop" is a client-id label only; subscription covers all groups
        host.connect(broker, true);            // allGroups = spBv1.0/#
        System.out.println("[INTEROP-HOST] Waiting for Ignition to publish... (Ctrl+C to stop)");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { host.close(); } catch (Exception ignored) {}
        }));
        Thread.currentThread().join(); // block here; messages received in callback
    }
}
