package dev.krillin.sparkplug.drift;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GovernanceHealthTest {
    private final GovernanceHealth gh = new GovernanceHealth();

    @Test void rate_threeNodesTwoNonconformant() {
        HealthSnapshot s = gh.summarize(Set.of("A","B","C"), List.of(
                new DriftEvent("B", DriftKind.VERSION_DRIFT, "", 0L),
                new DriftEvent("C", DriftKind.UNREGISTERED, "", 0L)));
        assertEquals(3, s.totalNodes());
        assertEquals(1, s.conformantNodes());
        assertEquals(1.0/3, s.conformanceRate(), 1e-9);
    }
    @Test void allConformant_rateOne() {
        HealthSnapshot s = gh.summarize(Set.of("A","B"), List.of());
        assertEquals(2, s.conformantNodes());
        assertEquals(1.0, s.conformanceRate(), 1e-9);
    }
    @Test void empty_rateOne() {
        HealthSnapshot s = gh.summarize(Set.of(), List.of());
        assertEquals(0, s.totalNodes());
        assertEquals(1.0, s.conformanceRate(), 1e-9);
    }
    @Test void driftCountByKind_countsEvents() {
        HealthSnapshot s = gh.summarize(Set.of("A","B"), List.of(
                new DriftEvent("A", DriftKind.VERSION_DRIFT, "", 0L),
                new DriftEvent("B", DriftKind.VERSION_DRIFT, "", 0L),
                new DriftEvent("B", DriftKind.STALE, "", 0L)));
        assertEquals(2, s.driftCountByKind().get(DriftKind.VERSION_DRIFT));
        assertEquals(1, s.driftCountByKind().get(DriftKind.STALE));
    }
    @Test void staleNodeCount_distinctNodes() {
        HealthSnapshot s = gh.summarize(Set.of("C"), List.of(
                new DriftEvent("C", DriftKind.STALE, "", 0L),
                new DriftEvent("C", DriftKind.STALE, "", 1L)));
        assertEquals(1, s.staleNodeCount());                           // distinct node
        assertEquals(2, s.driftCountByKind().get(DriftKind.STALE));    // events
    }
}
