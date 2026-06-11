package dev.krillin.sparkplug.drift;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Tracks the last-seen timestamp per node (clock injected for deterministic testing).
 *  A node is stale when it is not dead and (now - lastSeen) strictly exceeds the threshold. */
public final class LivenessTracker {
    private final Map<String, Long> lastSeen = new HashMap<>();
    private final Set<String> dead = new HashSet<>();

    public void markSeen(String nodeKey, long ts) { lastSeen.put(nodeKey, ts); dead.remove(nodeKey); }
    public void markDeath(String nodeKey) { dead.add(nodeKey); }

    public Set<String> stale(long now, long thresholdMs) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Long> e : lastSeen.entrySet()) {
            if (dead.contains(e.getKey())) continue;
            if (now - e.getValue() > thresholdMs) out.add(e.getKey());
        }
        return out;
    }
    public Set<String> known() { return new LinkedHashSet<>(lastSeen.keySet()); }
}
