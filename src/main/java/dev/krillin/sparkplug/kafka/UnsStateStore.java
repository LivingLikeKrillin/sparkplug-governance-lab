package dev.krillin.sparkplug.kafka;

import java.util.*;

import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.SparkplugBPayload;

import dev.krillin.sparkplug.spb40.Quality;

/**
 * Restores per-edge Sparkplug state (alias→name mapping + last-known-value) in memory (pure, no I/O).
 * Aliases are defined only in NBIRTH; NDATA carries alias-only RBE (changed metrics only).
 * Without this store it is impossible to produce correct Kafka records from RBE payloads.
 * last-known-value here is isomorphic to a Kafka log-compacted store:
 * key = metric identity, compacted value = current reading.
 */
public final class UnsStateStore {

    public record ResolvedMetric(String name, String type, Object value, Quality quality) {}

    private record MetricMeta(String name, String type) {}

    private static final class EdgeState {
        final Map<Long, MetricMeta> aliasMeta = new HashMap<>();
        final Map<String, ResolvedMetric> lastKnown = new LinkedHashMap<>();
    }

    private final Map<String, EdgeState> edges = new HashMap<>();

    private static boolean reserved(String name) {
        return name == null || name.isEmpty()
                || name.startsWith("_types_/") || name.startsWith("Node Control/") || name.equals("bdSeq");
    }

    /** NBIRTH: learn name↔alias+type mappings and seed last-known values. */
    public void learnBirth(String edge, SparkplugBPayload nbirth) {
        EdgeState st = edges.computeIfAbsent(edge, k -> new EdgeState());
        // NBIRTH is a full redeclaration of current state; clear and reseed on rebirth
        // so that metrics dropped from the new birth do not linger in last-known.
        st.aliasMeta.clear();
        st.lastKnown.clear();
        for (Metric m : nbirth.getMetrics()) {
            if (reserved(m.getName()) || m.getAlias() == null) continue;
            String type = m.getDataType().toString();
            st.aliasMeta.put(m.getAlias(), new MetricMeta(m.getName(), type));
            st.lastKnown.put(m.getName(), new ResolvedMetric(m.getName(), type, m.getValue(), Quality.GOOD));
        }
    }

    public boolean awaitingBirth(String edge) {
        EdgeState st = edges.get(edge);
        return st == null || st.aliasMeta.isEmpty();
    }

    /** Resolves alias-only RBE metrics in a data payload; updates and returns the changed metrics. */
    public List<ResolvedMetric> applyData(String edge, SparkplugBPayload ndata) {
        List<ResolvedMetric> out = new ArrayList<>();
        EdgeState st = edges.get(edge);
        if (st == null || st.aliasMeta.isEmpty()) return out;     // awaitingBirth → drop
        for (Metric m : ndata.getMetrics()) {
            if (m.getAlias() == null) continue;
            MetricMeta meta = st.aliasMeta.get(m.getAlias());
            if (meta == null) continue;                            // alias absent from birth → skip (known limitation)
            // Use the wire type (not the birth type) so that in-session type changes
            // surface as typeMismatch violations in ContractValidator; overwriting with
            // the birth type would hide the violation.
            ResolvedMetric rm = new ResolvedMetric(meta.name(), m.getDataType().toString(),
                    m.getValue(), Quality.GOOD);
            st.lastKnown.put(meta.name(), rm);
            out.add(rm);
        }
        return out;
    }

    /** Returns all current last-known metrics for the edge (isomorphic to the compacted store's truth). */
    public Collection<ResolvedMetric> lastKnown(String edge) {
        EdgeState st = edges.get(edge);
        return st == null ? List.of() : List.copyOf(st.lastKnown.values());
    }

    /** NDEATH: marks all metrics STALE as tombstones (not drops — consumers must observe staleness). */
    public List<ResolvedMetric> markDeath(String edge) {
        List<ResolvedMetric> out = new ArrayList<>();
        EdgeState st = edges.get(edge);
        if (st == null) return out;
        for (ResolvedMetric rm : new ArrayList<>(st.lastKnown.values())) {
            ResolvedMetric stale = new ResolvedMetric(rm.name(), rm.type(), rm.value(), Quality.STALE);
            st.lastKnown.put(rm.name(), stale);
            out.add(stale);
        }
        return out;
    }
}
