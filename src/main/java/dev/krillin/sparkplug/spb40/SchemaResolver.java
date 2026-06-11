package dev.krillin.sparkplug.spb40;

import java.util.*;

import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.PropertySet;
import org.eclipse.tahu.message.model.PropertyValue;
import org.eclipse.tahu.message.model.SparkplugBPayload;

import dev.krillin.sparkplug.schema.Member;
import dev.krillin.sparkplug.schema.UdtDefinition;
import dev.krillin.sparkplug.schema.Violation;
import dev.krillin.sparkplug.spb40.DefinitionCodec.ParsedDefinition;

/**
 * SpB 4.0 #608 pure consumer-side resolver. Learns schemas from Definition payloads,
 * then reconstructs thin payloads via schemaRef → schema → alias → member name + type,
 * collecting governance violations along the way.
 */
public final class SchemaResolver {

    private final Map<SchemaRef, ParsedDefinition> cache = new HashMap<>();

    /** Outcome of a learn() call: new schema / identical re-delivery (retained idempotent) / same ref@version with different content (immutability violation). */
    public enum LearnResult { LEARNED_NEW, UNCHANGED, REDEFINED }

    /**
     * Learns a Definition. Because a retained Definition is re-delivered on every connect and
     * republish, this method must be idempotent for identical content.
     * If the same ref@version arrives with *different* content it is an immutability violation
     * (REDEFINED): the original is kept (first-definition-wins) and the caller can treat it
     * as a governance signal.
     */
    public LearnResult learn(ParsedDefinition pd) {
        SchemaRef ref = new SchemaRef(pd.def().templateRef(), pd.def().version());
        ParsedDefinition prev = cache.get(ref);
        if (prev == null) { cache.put(ref, pd); return LearnResult.LEARNED_NEW; }
        if (prev.equals(pd)) return LearnResult.UNCHANGED;
        return LearnResult.REDEFINED;   // original retained (immutability); incoming definition is not adopted
    }

    public record ResolvedMetric(String name, String type, Object value, Quality quality, String engUnit) {}
    public record Resolution(SchemaRef ref, boolean awaitingDefinition,
                             List<ResolvedMetric> metrics, List<Violation> violations) {}

    public Resolution resolve(SparkplugBPayload thin) {
        List<ResolvedMetric> resolved = new ArrayList<>();
        List<Violation> violations = new ArrayList<>();

        SchemaRef ref = null;
        for (Metric m : thin.getMetrics()) {
            if (ThinCodec.SCHEMA_REF.equals(m.getName())) {
                try { ref = SchemaRef.parse(String.valueOf(m.getValue())); }
                catch (RuntimeException e) { violations.add(new Violation("schemaRef.invalid", String.valueOf(m.getValue()))); }
            }
        }
        if (ref == null) {
            violations.add(new Violation("schemaRef.missing", "no schemaRef metric in thin payload"));
            return new Resolution(null, false, resolved, violations);
        }

        ParsedDefinition pd = cache.get(ref);
        if (pd == null) return new Resolution(ref, true, resolved, violations);

        UdtDefinition def = pd.def();
        for (Metric m : thin.getMetrics()) {
            if (ThinCodec.SCHEMA_REF.equals(m.getName())) continue;
            if (m.getAlias() == null) continue;

            long alias = m.getAlias();
            Member member;
            try { member = DefinitionCodec.memberByAlias(def, alias); }
            catch (RuntimeException e) { violations.add(new Violation("schema.unknownMember", "alias=" + alias)); continue; }

            String wireType = m.getDataType().toString();
            if (!member.type().equals(wireType))
                violations.add(new Violation("schema.typeMismatch",
                        member.name() + " schema=" + member.type() + " wire=" + wireType));

            Quality q = readQuality(m, member.name(), violations);
            resolved.add(new ResolvedMetric(member.name(), member.type(), m.getValue(), q, pd.units().get(member.name())));
        }
        return new Resolution(ref, false, resolved, violations);
    }

    private Quality readQuality(Metric m, String memberName, List<Violation> violations) {
        PropertySet ps = m.getProperties();
        PropertyValue pv = (ps == null) ? null : ps.getPropertyValue(ThinCodec.QUALITY);
        if (pv == null || pv.getValue() == null) {
            violations.add(new Violation("quality.missing", memberName));
            return null;
        }
        int code = ((Number) pv.getValue()).intValue();
        Quality q;
        try { q = Quality.fromCode(code); }
        catch (RuntimeException e) { violations.add(new Violation("quality.invalid", memberName + " code=" + code)); return null; }
        if (q != Quality.GOOD) violations.add(new Violation("quality." + q.name().toLowerCase(Locale.ROOT), memberName));
        return q;
    }
}
