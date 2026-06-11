package dev.krillin.sparkplug.drift;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import dev.krillin.sparkplug.schema.Member;
import dev.krillin.sparkplug.schema.SemVer;
import dev.krillin.sparkplug.schema.UdtDefinition;

class SchemaDriftDetectorTest {
    private final SchemaDriftDetector det = new SchemaDriftDetector();

    private UdtDefinition motor(String version, Member... members) {
        return new UdtDefinition("Motor", SemVer.parse(version), List.of(members), List.of());
    }
    private long count(List<DriftEvent> es, DriftKind k) {
        return es.stream().filter(e -> e.kind() == k).count();
    }
    private final UdtDefinition reg = motor("1.1.0",
            new Member("Rpm","Double"), new Member("Running","Boolean"), new Member("Temperature","Double"));

    @Test void unregistered_whenNoRegisteredDef() {
        UdtDefinition obs = new UdtDefinition("Pump", SemVer.parse("1.0.0"), List.of(), List.of());
        List<DriftEvent> es = det.detect("A", Optional.empty(), obs, 0L);
        assertEquals(1, es.size());
        assertEquals(DriftKind.UNREGISTERED, es.get(0).kind());
    }
    @Test void conformant_noEvents() {
        UdtDefinition obs = motor("1.1.0",
                new Member("Rpm","Double"), new Member("Running","Boolean"), new Member("Temperature","Double"));
        assertTrue(det.detect("A", Optional.of(reg), obs, 0L).isEmpty());
    }
    @Test void versionDrift() {
        UdtDefinition obs = motor("1.0.0",
                new Member("Rpm","Double"), new Member("Running","Boolean"), new Member("Temperature","Double"));
        assertEquals(1, count(det.detect("A", Optional.of(reg), obs, 0L), DriftKind.VERSION_DRIFT));
    }
    @Test void unknownMember() {
        UdtDefinition obs = motor("1.1.0",
                new Member("Rpm","Double"), new Member("Running","Boolean"),
                new Member("Temperature","Double"), new Member("Bonus","Int32"));
        assertEquals(1, count(det.detect("A", Optional.of(reg), obs, 0L), DriftKind.UNKNOWN_MEMBER));
    }
    @Test void missingMember() {
        UdtDefinition obs = motor("1.1.0", new Member("Rpm","Double"), new Member("Running","Boolean"));
        assertEquals(1, count(det.detect("A", Optional.of(reg), obs, 0L), DriftKind.MISSING_MEMBER));
    }
    @Test void typeDrift() {
        UdtDefinition obs = motor("1.1.0",
                new Member("Rpm","Double"), new Member("Running","String"), new Member("Temperature","Double"));
        assertEquals(1, count(det.detect("A", Optional.of(reg), obs, 0L), DriftKind.TYPE_DRIFT));
    }
    @Test void composite_versionTypeMissing() {
        UdtDefinition obs = motor("1.0.0", new Member("Rpm","Double"), new Member("Running","String"));
        List<DriftEvent> es = det.detect("A", Optional.of(reg), obs, 0L);
        assertEquals(1, count(es, DriftKind.VERSION_DRIFT));
        assertEquals(1, count(es, DriftKind.TYPE_DRIFT));
        assertEquals(1, count(es, DriftKind.MISSING_MEMBER));
    }
}
