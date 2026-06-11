package dev.krillin.sparkplug.drift;
import java.util.Map;
/** Immutable governance health snapshot.
 *  conformanceRate is computed per distinct node; driftCountByKind is computed per event. */
public record HealthSnapshot(int totalNodes, int conformantNodes, double conformanceRate,
                             Map<DriftKind,Integer> driftCountByKind, int staleNodeCount) {}
