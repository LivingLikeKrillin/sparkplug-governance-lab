package dev.krillin.sparkplug;

/**
 * Primary Host STATE + store-and-forward demo.
 * Scenario:
 *   1) PrimaryHost comes online (STATE retained)
 *   2) Edge detects STATE online → publishes NBIRTH + live NDATA
 *   3) PrimaryHost goes gracefully OFFLINE
 *   4) Edge detects offline → buffers NDATA (not delivered)
 *   5) PrimaryHost (same hostId) reconnects online → Edge flushes buffer in order
 *      → host receives the backlog without data loss
 * Prerequisite: docker compose up -d.
 */
public class StateStoreForwardDemo {
    public static void main(String[] args) throws Exception {
        String broker = "tcp://localhost:1883";
        String group = "Krillin", edge = "SfEdge1", hostId = "PrimaryA";

        // 1) Primary host online
        PrimaryHost host = new PrimaryHost(group, hostId);
        host.connect(broker);
        Thread.sleep(400);

        // 2) Edge online — detects STATE online, publishes NBIRTH + live data
        SfEdgeNode node = new SfEdgeNode(group, edge, hostId);
        node.connect(broker);
        Thread.sleep(400);   // wait to receive retained STATE
        node.birth();
        node.publishData();  // LIVE
        node.publishData();  // LIVE
        Thread.sleep(500);

        // 3) Primary host goes offline
        System.out.println("\n--- Primary host down ---");
        host.goOfflineGraceful();
        Thread.sleep(500);   // let the edge detect STATE offline

        // 4) Continue producing data — buffered while host is offline
        node.publishData();
        node.publishData();
        node.publishData();
        Thread.sleep(500);

        // 5) Primary host recovers → edge flushes → backlog delivered without loss
        System.out.println("\n--- Primary host recovered ---");
        PrimaryHost host2 = new PrimaryHost(group, hostId);
        host2.connect(broker);
        Thread.sleep(1500);  // allow STATE online to propagate + flush + host2 to receive

        node.publishData();  // back to live
        Thread.sleep(800);

        System.out.println("\n>>> [Observation] Data is held in the buffer while offline; on transition to online it is");
        System.out.println(">>> flushed in order and the consumer receives the backlog without loss.");
        System.out.println(">>> Note: this store-and-forward assumes exactly one primary host. With N consumers,");
        System.out.println(">>> 'who is primary?' becomes a contradiction — the primary-host multi-consumer problem.");

        node.close(); host2.close();
        System.out.println("\n=== STATE / STORE-AND-FORWARD DEMO DONE ===");
        System.exit(0);
    }
}
