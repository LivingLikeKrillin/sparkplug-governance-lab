package dev.krillin.sparkplug.spb40;

import java.util.*;

import org.eclipse.tahu.message.model.*;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.PropertySet.PropertySetBuilder;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;
import org.eclipse.tahu.message.model.Template.TemplateBuilder;

import dev.krillin.sparkplug.schema.Member;
import dev.krillin.sparkplug.schema.Param;
import dev.krillin.sparkplug.schema.TemplateAdapter;
import dev.krillin.sparkplug.schema.UdtDefinition;

/**
 * SpB 4.0 #608: converts a UdtDefinition (schema registry structure) + per-member engineering
 * units (#607 engUnit) to and from a Sparkplug Definition payload.
 * A Definition payload carries exactly one _types_/&lt;ref&gt; Template outside the data path
 * and is published retained so late-joining consumers receive it on connect.
 * Member order is the basis for the alias convention (§3.0), so both build and parse preserve it.
 */
public final class DefinitionCodec {
    private DefinitionCodec() {}

    public static final String TYPES_PREFIX = "_types_/";
    public static final String ENG_UNIT = "engUnit";

    public record ParsedDefinition(UdtDefinition def, Map<String, String> units) {}

    public static long aliasOf(UdtDefinition def, String memberName) {
        List<Member> ms = def.members();
        for (int i = 0; i < ms.size(); i++)
            if (ms.get(i).name().equals(memberName)) return i + 1L;
        throw new IllegalArgumentException("Unknown member: " + memberName);
    }

    public static Member memberByAlias(UdtDefinition def, long alias) {
        int idx = (int) alias - 1;
        List<Member> ms = def.members();
        if (idx < 0 || idx >= ms.size())
            throw new IllegalArgumentException("Alias out of range: " + alias + " (member count=" + ms.size() + ")");
        return ms.get(idx);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Template buildDefinitionTemplate(UdtDefinition def, Map<String, String> units) throws Exception {
        TemplateBuilder tb = new TemplateBuilder().version(def.version().toString()).definition(true);
        for (Param p : def.params())
            tb.addParameter(new Parameter(p.name(), TahuTypes.parameterType(p.type()), null));
        for (Member m : def.members()) {
            MetricBuilder mb = new MetricBuilder(m.name(), TahuTypes.metricType(m.type()), defaultValue(m.type()));
            String unit = units.get(m.name());
            if (unit != null)
                mb.properties(new PropertySetBuilder()
                        .addProperty(ENG_UNIT, new PropertyValue(PropertyDataType.String, unit))
                        .createPropertySet());
            tb.addMetric(mb.createMetric());
        }
        return tb.createTemplate();
    }

    public static SparkplugBPayload buildDefinition(UdtDefinition def, Map<String, String> units) throws Exception {
        return new SparkplugBPayloadBuilder().setTimestamp(new Date())
                .addMetric(new MetricBuilder(TYPES_PREFIX + def.templateRef(), MetricDataType.Template,
                        buildDefinitionTemplate(def, units)).createMetric())
                .createPayload();
    }

    public static ParsedDefinition parse(SparkplugBPayload payload) {
        for (Metric metric : payload.getMetrics()) {
            String name = metric.getName();
            if (name != null && name.startsWith(TYPES_PREFIX) && metric.getDataType() == MetricDataType.Template) {
                String ref = name.substring(TYPES_PREFIX.length());
                Template t = (Template) metric.getValue();
                UdtDefinition def = TemplateAdapter.fromTahuTemplate(ref, t);
                Map<String, String> units = new LinkedHashMap<>();
                for (Metric mm : t.getMetrics()) {
                    PropertySet ps = mm.getProperties();
                    if (ps != null) {
                        PropertyValue pv = ps.getPropertyValue(ENG_UNIT);
                        if (pv != null && pv.getValue() != null) units.put(mm.getName(), pv.getValue().toString());
                    }
                }
                return new ParsedDefinition(def, units);
            }
        }
        throw new IllegalArgumentException("No " + TYPES_PREFIX + " Template found in Definition payload");
    }

    private static Object defaultValue(String type) {
        switch (type) {
            case "Double": return 0.0;
            case "Float": return 0.0f;
            case "Int8": case "Int16": case "Int32":
            case "UInt8": case "UInt16": case "UInt32": return 0;
            case "Int64": case "UInt64": return 0L;
            case "Boolean": return false;
            case "String": case "Text": return "";
            default: return null;
        }
    }
}
