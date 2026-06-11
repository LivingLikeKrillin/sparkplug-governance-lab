package dev.krillin.sparkplug;

/**
 * "Stolen session" demo: when two edge nodes connect with the same MQTT client-id
 * (i.e. the same group/edge identity), the MQTT spec requires the broker to disconnect
 * the existing connection (takeover). The disconnection is abnormal, so the broker
 * publishes the LWT (NDEATH), which the host receives.
 * If both instances keep reconnecting this pattern becomes a connect/disconnect and
 * birth/death storm (flapping).
 * Prerequisite: docker compose up -d.
 */
public class StolenSessionDemo {
    public static void main(String[] args) throws Exception {
        String broker = "tcp://localhost:1883";
        String group = "Krillin", edge = "Edge1";

        HostApp host = new HostApp(group);
        host.connect(broker);
        Thread.sleep(400);

        // A connects (client-id = edge-Krillin-Edge1)
        EdgeNode a = new EdgeNode(group, edge);
        a.connect(broker);
        a.birth();
        Thread.sleep(800);

        System.out.println("\n--- B connects with the same edge_node_id (same client-id) -> takes over A's session ---");
        EdgeNode b = new EdgeNode(group, edge);   // identical client-id
        b.connect(broker);                         // broker disconnects A (takeover) -> A's LWT NDEATH is published
        b.birth();
        Thread.sleep(1200);

        System.out.println("\n>>> [Observation] Host log shows: A's NBIRTH -> (B connects) A's NDEATH (LWT) -> B's NBIRTH.");
        System.out.println(">>> If both instances stay alive and keep reconnecting, this pattern becomes a flapping storm.");
        System.out.println(">>> Governance: edge_node_id (= client-id) must be globally unique across the enterprise");
        System.out.println(">>> and enforced by a registry. A collision triggers a takeover + NDEATH storm that corrupts data and state.");

        b.close();
        try { a.close(); } catch (Exception ignore) { }
        host.close();
        System.out.println("\n=== STOLEN SESSION DEMO DONE ===");
        System.exit(0);
    }
}
