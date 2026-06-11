package dev.krillin.sparkplug.drift;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Aggregates accumulated DriftEvents against the full known-node set to produce a health snapshot.
 *  Non-conformance is measured per distinct nodeKey; drift counts are per event. */
public final class GovernanceHealth {

    public HealthSnapshot summarize(Set<String> allNodes, List<DriftEvent> events) {
        Set<String> nonConformant = new HashSet<>();
        Set<String> staleNodes = new HashSet<>();
        Map<DriftKind, Integer> byKind = new EnumMap<>(DriftKind.class);
        for (DriftEvent e : events) {
            nonConformant.add(e.nodeKey());
            byKind.merge(e.kind(), 1, Integer::sum);
            if (e.kind() == DriftKind.STALE) staleNodes.add(e.nodeKey());
        }
        int total = allNodes.size();
        int nonConf = (int) allNodes.stream().filter(nonConformant::contains).count();
        int conformant = total - nonConf;
        double rate = total == 0 ? 1.0 : (double) conformant / total;
        return new HealthSnapshot(total, conformant, rate, byKind, staleNodes.size());
    }
}
