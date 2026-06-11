package dev.krillin.sparkplug.kafka;

import java.util.*;

import dev.krillin.sparkplug.kafka.UnsStateStore.ResolvedMetric;
import dev.krillin.sparkplug.schema.Member;
import dev.krillin.sparkplug.schema.UdtDefinition;
import dev.krillin.sparkplug.schema.Violation;

/**
 * Validates runtime metric conformance against a UdtDefinition contract (pure, no I/O).
 * The schema registry's CompatMode verdict (ADR-0007) is intentionally not reused here;
 * Conformance is a separate, narrower result for runtime routing.
 * Missing-member checks run at birth only (NBIRTH member set vs. contract);
 * the data path raises only typeMismatch and unknownMetric violations (consistent with RBE).
 */
public final class ContractValidator {

    public record Conformance(boolean ok, List<Violation> violations) {
        static Conformance of(List<Violation> v) { return new Conformance(v.isEmpty(), List.copyOf(v)); }
    }

    /** Validates a single metric: unknown metric or type mismatch (used for data-path routing). */
    public Conformance validateMetric(UdtDefinition contract, ResolvedMetric m) {
        List<Violation> v = new ArrayList<>();
        Member member = memberByName(contract, m.name());
        if (member == null) {
            v.add(new Violation("contract.unknownMetric",
                    m.name() + " is not a member of contract " + contract.templateRef()));
        } else if (!member.type().equals(m.type())) {
            v.add(new Violation("contract.typeMismatch",
                    m.name() + " expected=" + member.type() + " received=" + m.type()));
        }
        return Conformance.of(v);
    }

    /** Validates the full birth set: unknown/type violations per metric, plus missing-member check. */
    public Conformance validateBirth(UdtDefinition contract, List<ResolvedMetric> birthMetrics) {
        List<Violation> v = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (ResolvedMetric m : birthMetrics) {
            present.add(m.name());
            v.addAll(validateMetric(contract, m).violations());
        }
        for (Member mem : contract.members())
            if (!present.contains(mem.name()))
                v.add(new Violation("contract.missingMember",
                        mem.name() + " is required by the contract but absent from NBIRTH"));
        return Conformance.of(v);
    }

    private static Member memberByName(UdtDefinition c, String name) {
        for (Member m : c.members()) if (m.name().equals(name)) return m;
        return null;
    }
}
