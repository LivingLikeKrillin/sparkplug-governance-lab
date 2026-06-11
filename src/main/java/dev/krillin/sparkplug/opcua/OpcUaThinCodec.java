package dev.krillin.sparkplug.opcua;

import java.util.*;
import org.eclipse.tahu.message.model.*;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.PropertySet.PropertySetBuilder;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;
import dev.krillin.sparkplug.schema.*;
import dev.krillin.sparkplug.spb40.*;

public final class OpcUaThinCodec {
    private OpcUaThinCodec() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static SparkplugBPayload buildThin(SchemaRef ref, List<MemberSample> samples, UdtDefinition udt) throws Exception {
        SparkplugBPayloadBuilder b = new SparkplugBPayloadBuilder().setTimestamp(new Date()).setSeq(0L);
        // schemaRef sentinel metric (same key "schemaRef" as the spb40 Definition codec, ADR-0008)
        b.addMetric(new MetricBuilder("schemaRef", MetricDataType.String, ref.format()).createMetric());
        for (MemberSample s : samples) {
            long alias = DefinitionCodec.aliasOf(udt, s.name());
            String typeName = udt.members().stream().filter(m -> m.name().equals(s.name())).findFirst().orElseThrow().type();
            MetricDataType mdt = TahuTypes.metricType(typeName);
            PropertySetBuilder ps = new PropertySetBuilder()
                .addProperty(UaSideChannel.QUALITY, new PropertyValue(PropertyDataType.Int32, UaSideChannel.toQuality(s.statusCode()).code()))
                .addProperty(UaSideChannel.UA_STATUSCODE, new PropertyValue(PropertyDataType.UInt32, s.statusCode()));
            if (s.uaTicks().isPresent())
                ps.addProperty(UaSideChannel.UA_TICKS, new PropertyValue(PropertyDataType.Int64, s.uaTicks().get()));
            b.addMetric(new MetricBuilder(alias, mdt, s.value()).properties(ps.createPropertySet()).createMetric());
        }
        return b.createPayload();
    }
}
