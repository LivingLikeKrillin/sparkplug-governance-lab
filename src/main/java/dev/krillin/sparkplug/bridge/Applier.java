package dev.krillin.sparkplug.bridge;

/**
 * The physical apply seam the bridge drives after an edge-authorization ALLOW.
 * Implemented by {@link OpcUaApplier} (live Milo client) in production and by a fake in tests,
 * so the bridge core ({@link NcmdOpcUaBridge#handle}) is unit-testable with no live OPC-UA server.
 */
public interface Applier {

    /** Read a node's current value (no authorization — reads are observation, not command). */
    ReadBack read(String nodeId) throws Exception;

    /** Write a Double setpoint and confirm by numeric read-back equality. */
    Result write(String nodeId, double value) throws Exception;

    /**
     * Rising-edge Boolean trigger: write {@code true} to {@code triggerNodeId}, then poll
     * {@code doneNodeId} for a confirmed false→true transition within {@code timeoutMs}.
     */
    Result call(String triggerNodeId, String doneNodeId, long timeoutMs) throws Exception;

    /** Read outcome: the stringified value + whether the OPC-UA StatusCode was Good. */
    record ReadBack(String value, boolean good) {}

    /** Apply outcome: whether the write/call was confirmed, plus a human-readable detail. */
    record Result(boolean ok, String detail) {}
}
