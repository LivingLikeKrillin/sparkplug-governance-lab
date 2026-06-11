package dev.krillin.sparkplug;

/**
 * Sparkplug B session demo — runs Host and EdgeNode in a single JVM, each with its own
 * Paho client, against a live HiveMQ broker.
 * Scenario: Host subscribes → Edge NBIRTH → NDATA x4 → Host sends Rebirth command →
 *           Edge re-publishes NBIRTH → NDATA → graceful NDEATH.
 * Prerequisite: docker compose up -d (tcp://localhost:1883).
 *
 * This demo runs the full scenario end-to-end and exits on completion.
 */
public class SessionDemo {
    public static void main(String[] args) throws Exception {
        String broker = "tcp://localhost:1883";
        String group = "Krillin";
        String edge = "Edge1";

        HostApp host = new HostApp(group);
        host.connect(broker);
        Thread.sleep(500);

        EdgeNode node = new EdgeNode(group, edge);
        node.connect(broker);
        node.birth();

        for (int i = 0; i < 4; i++) {
            Thread.sleep(900);
            node.publishData();
        }

        Thread.sleep(600);
        System.out.println("--- Host sending Rebirth command ---");
        host.requestRebirth(edge);
        Thread.sleep(1200);

        node.publishData();   // data after rebirth (seq restarts from 1)
        Thread.sleep(600);
        node.publishData();

        Thread.sleep(600);
        System.out.println("--- Edge graceful shutdown ---");
        node.deathGraceful();
        Thread.sleep(800);

        node.close();
        host.close();
        System.out.println("=== SESSION DEMO DONE ===");
        System.exit(0);
    }
}
