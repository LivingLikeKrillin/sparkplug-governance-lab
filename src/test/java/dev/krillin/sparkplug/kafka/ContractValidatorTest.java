package dev.krillin.sparkplug.kafka;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import dev.krillin.sparkplug.kafka.ContractValidator.Conformance;
import dev.krillin.sparkplug.kafka.UnsStateStore.ResolvedMetric;
import dev.krillin.sparkplug.schema.Member;
import dev.krillin.sparkplug.schema.Param;
import dev.krillin.sparkplug.schema.SemVer;
import dev.krillin.sparkplug.schema.UdtDefinition;
import dev.krillin.sparkplug.spb40.Quality;
import org.junit.jupiter.api.Test;

class ContractValidatorTest {
    private final ContractValidator v = new ContractValidator();

    private UdtDefinition motor() {   // same structure as Motor@1.0.0 in the schema registry (ADR-0007)
        return new UdtDefinition("Motor", SemVer.parse("1.0.0"),
                List.of(new Member("Rpm", "Double"), new Member("Running", "Boolean")),
                List.of(new Param("Location", "String")));
    }
    private ResolvedMetric rm(String n, String t, Object val) { return new ResolvedMetric(n, t, val, Quality.GOOD); }

    @Test void validateMetric_conforming_isOk() {
        assertTrue(v.validateMetric(motor(), rm("Rpm", "Double", 1500.0)).ok());
    }
    @Test void validateMetric_typeMismatch_flagged() {
        Conformance c = v.validateMetric(motor(), rm("Rpm", "String", "x"));
        assertFalse(c.ok());
        assertTrue(c.violations().stream().anyMatch(x -> x.rule().equals("contract.typeMismatch")));
    }
    @Test void validateMetric_unknownMetric_flagged() {
        Conformance c = v.validateMetric(motor(), rm("Temperature", "Double", 65.0));
        assertTrue(c.violations().stream().anyMatch(x -> x.rule().equals("contract.unknownMetric")));
    }
    @Test void validateMetric_doesNotFlagMissing() {   // single-metric path has no missing-member concept (consistent with RBE)
        assertTrue(v.validateMetric(motor(), rm("Rpm", "Double", 1500.0)).ok());
    }
    @Test void validateBirth_fullSet_isOk() {
        Conformance c = v.validateBirth(motor(), List.of(rm("Rpm", "Double", 1500.0), rm("Running", "Boolean", true)));
        assertTrue(c.ok(), c.violations().toString());
    }
    @Test void validateBirth_missingMember_flagged() {
        Conformance c = v.validateBirth(motor(), List.of(rm("Rpm", "Double", 1500.0)));   // Running missing
        assertTrue(c.violations().stream().anyMatch(x -> x.rule().equals("contract.missingMember")));
    }
}
