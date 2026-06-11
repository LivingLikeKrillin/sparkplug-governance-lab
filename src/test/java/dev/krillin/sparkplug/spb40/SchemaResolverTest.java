package dev.krillin.sparkplug.spb40;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import dev.krillin.sparkplug.schema.Member;
import dev.krillin.sparkplug.schema.Param;
import dev.krillin.sparkplug.schema.SemVer;
import dev.krillin.sparkplug.schema.UdtDefinition;
import dev.krillin.sparkplug.spb40.DefinitionCodec.ParsedDefinition;
import dev.krillin.sparkplug.spb40.SchemaResolver.LearnResult;
import dev.krillin.sparkplug.spb40.SchemaResolver.Resolution;
import dev.krillin.sparkplug.spb40.SchemaResolver.ResolvedMetric;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.junit.jupiter.api.Test;

class SchemaResolverTest {

    private UdtDefinition motor() {
        return new UdtDefinition("Motor", SemVer.parse("1.1.0"),
                List.of(new Member("Rpm", "Double"), new Member("Running", "Boolean"), new Member("Temperature", "Double")),
                List.of(new Param("Location", "String")));
    }
    private ParsedDefinition parsedMotor() {
        return new ParsedDefinition(motor(), Map.of("Rpm", "rpm", "Temperature", "degC"));
    }
    private SchemaResolver learned() {
        SchemaResolver r = new SchemaResolver();
        r.learn(parsedMotor());
        return r;
    }
    private Map<String, Integer> goodAll() { return Map.of("Rpm", 0, "Running", 0, "Temperature", 0); }

    // --- learn() idempotency and drift outcomes (retained re-delivery double-learn fix) ---

    @Test void learn_newRef_returnsLearnedNew() {
        assertEquals(LearnResult.LEARNED_NEW, new SchemaResolver().learn(parsedMotor()));
    }

    @Test void learn_sameDefinitionAgain_returnsUnchanged() {
        SchemaResolver r = new SchemaResolver();
        r.learn(parsedMotor());
        assertEquals(LearnResult.UNCHANGED, r.learn(parsedMotor()));   // retained re-delivery = idempotent no-op
    }

    @Test void learn_redefinedSameRef_returnsRedefinedAndKeepsOriginal() throws Exception {
        SchemaResolver r = new SchemaResolver();
        r.learn(parsedMotor());                                        // Motor@1.1.0, units {Rpm:rpm, Temperature:degC}
        ParsedDefinition mutated = new ParsedDefinition(motor(), Map.of("Rpm", "RPM"));  // same ref@version, different content
        assertEquals(LearnResult.REDEFINED, r.learn(mutated));         // immutability violation = governance signal
        // original retained (first-definition-wins): resolve still uses original units
        SparkplugBPayload thin = ThinCodec.buildThin(motor(),
                Map.of("Rpm", 1500.0, "Running", true, "Temperature", 65.4), goodAll(), 0L);
        assertEquals("rpm", r.resolve(thin).metrics().get(0).engUnit());
    }

    @Test void resolve_thinData_reconstructsTypedViewWithQualityAndUnit() throws Exception {
        SparkplugBPayload thin = ThinCodec.buildThin(motor(),
                Map.of("Rpm", 1500.0, "Running", true, "Temperature", 65.4), goodAll(), 0L);
        Resolution res = learned().resolve(thin);

        assertFalse(res.awaitingDefinition());
        assertTrue(res.violations().isEmpty(), res.violations().toString());
        assertEquals(3, res.metrics().size());
        ResolvedMetric rpm = res.metrics().get(0);
        assertEquals("Rpm", rpm.name());
        assertEquals("Double", rpm.type());
        assertEquals(1500.0, rpm.value());
        assertEquals(Quality.GOOD, rpm.quality());
        assertEquals("rpm", rpm.engUnit());
        assertNull(res.metrics().get(1).engUnit());
    }

    @Test void resolve_beforeLearn_isAwaitingDefinition() throws Exception {
        SparkplugBPayload thin = ThinCodec.buildThin(motor(),
                Map.of("Rpm", 1500.0, "Running", true, "Temperature", 65.4), goodAll(), 0L);
        Resolution res = new SchemaResolver().resolve(thin);
        assertTrue(res.awaitingDefinition());
        assertTrue(res.metrics().isEmpty());
    }

    @Test void resolve_badQuality_flagged() throws Exception {
        Map<String, Integer> q = new HashMap<>(goodAll()); q.put("Temperature", Quality.BAD.code());
        SparkplugBPayload thin = ThinCodec.buildThin(motor(),
                Map.of("Rpm", 1500.0, "Running", true, "Temperature", 65.4), q, 0L);
        Resolution res = learned().resolve(thin);
        assertTrue(res.violations().stream().anyMatch(v -> v.rule().equals("quality.bad")));
    }

    @Test void resolve_missingQuality_flagged() throws Exception {
        Map<String, Integer> q = Map.of("Rpm", 0, "Running", 0);
        SparkplugBPayload thin = ThinCodec.buildThin(motor(),
                Map.of("Rpm", 1500.0, "Running", true, "Temperature", 65.4), q, 0L);
        Resolution res = learned().resolve(thin);
        assertTrue(res.violations().stream().anyMatch(v -> v.rule().equals("quality.missing")));
    }

    @Test void resolve_typeMismatch_flagged() throws Exception {
        org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder b =
                new org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder().setSeq(0L)
                .addMetric(new org.eclipse.tahu.message.model.Metric.MetricBuilder(
                        ThinCodec.SCHEMA_REF, org.eclipse.tahu.message.model.MetricDataType.String, "Motor@1.1.0").createMetric());
        b.addMetric(new org.eclipse.tahu.message.model.Metric.MetricBuilder(
                3L, org.eclipse.tahu.message.model.MetricDataType.Int32, 65).createMetric());
        Resolution res = learned().resolve(b.createPayload());
        assertTrue(res.violations().stream().anyMatch(v -> v.rule().equals("schema.typeMismatch")));
    }

    @Test void resolve_unknownAlias_flagged() throws Exception {
        org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder b =
                new org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder().setSeq(0L)
                .addMetric(new org.eclipse.tahu.message.model.Metric.MetricBuilder(
                        ThinCodec.SCHEMA_REF, org.eclipse.tahu.message.model.MetricDataType.String, "Motor@1.1.0").createMetric())
                .addMetric(new org.eclipse.tahu.message.model.Metric.MetricBuilder(
                        99L, org.eclipse.tahu.message.model.MetricDataType.Double, 1.0).createMetric());
        Resolution res = learned().resolve(b.createPayload());
        assertTrue(res.violations().stream().anyMatch(v -> v.rule().equals("schema.unknownMember")));
    }
}
