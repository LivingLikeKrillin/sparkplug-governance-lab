package dev.krillin.sparkplug.spb40;

import java.util.*;

import org.eclipse.tahu.message.model.*;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.PropertySet.PropertySetBuilder;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;
import org.eclipse.tahu.message.model.Template.TemplateBuilder;

import dev.krillin.sparkplug.schema.Member;
import dev.krillin.sparkplug.schema.UdtDefinition;

/**
 * SpB 4.0 #608 thin data payload: schemaRef (schema reference) + per-member alias+value + #603 quality property.
 * No inline _types_ Template — the schema lives in a separate retained Definition payload.
 * Member names are not published; consumers reconstruct them from the Definition via alias.
 * {@code buildFatBirth} is the Sparkplug 3.0-style baseline for size comparison.
 */
public final class ThinCodec {
    private ThinCodec() {}

    public static final String SCHEMA_REF = "schemaRef";
    public static final String QUALITY = "quality";

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static SparkplugBPayload buildThin(UdtDefinition def, Map<String, Object> values,
                                              Map<String, Integer> qualities, long seq) throws Exception {
        SparkplugBPayloadBuilder b = new SparkplugBPayloadBuilder().setTimestamp(new Date()).setSeq(seq)
                .addMetric(new MetricBuilder(SCHEMA_REF, MetricDataType.String,
                        new SchemaRef(def.templateRef(), def.version()).format()).createMetric());
        for (Member m : def.members()) {
            long alias = DefinitionCodec.aliasOf(def, m.name());
            MetricBuilder mb = new MetricBuilder(alias, TahuTypes.metricType(m.type()), values.get(m.name()));
            Integer qc = qualities.get(m.name());
            if (qc != null)
                mb.properties(new PropertySetBuilder()
                        .addProperty(QUALITY, new PropertyValue(PropertyDataType.Int32, qc))
                        .createPropertySet());
            b.addMetric(mb.createMetric());
        }
        return b.createPayload();
    }

    public static SparkplugBPayload buildFatBirth(UdtDefinition def, Map<String, String> units,
                                                  Map<String, Object> values, long seq) throws Exception {
        Template defT = DefinitionCodec.buildDefinitionTemplate(def, units);
        TemplateBuilder inst = new TemplateBuilder().version(def.version().toString())
                .templateRef(def.templateRef()).definition(false);
        for (Member m : def.members())
            inst.addMetric(new MetricBuilder(m.name(), TahuTypes.metricType(m.type()), values.get(m.name())).createMetric());
        return new SparkplugBPayloadBuilder().setTimestamp(new Date()).setSeq(seq)
                .addMetric(new MetricBuilder(DefinitionCodec.TYPES_PREFIX + def.templateRef(),
                        MetricDataType.Template, defT).createMetric())
                .addMetric(new MetricBuilder(def.templateRef() + "s/" + def.templateRef() + "1",
                        MetricDataType.Template, inst.createTemplate()).createMetric())
                .createPayload();
    }
}
