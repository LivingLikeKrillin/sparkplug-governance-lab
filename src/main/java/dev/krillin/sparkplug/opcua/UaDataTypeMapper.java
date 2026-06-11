package dev.krillin.sparkplug.opcua;
import java.util.Optional;
import org.eclipse.tahu.message.model.MetricDataType;

public final class UaDataTypeMapper {
    private UaDataTypeMapper() {}
    public static MappedType map(UaDataType t) {
        return switch (t) {
            case BOOLEAN -> clean(MetricDataType.Boolean);
            case INT32   -> clean(MetricDataType.Int32);
            case UINT32  -> clean(MetricDataType.UInt32);
            case INT64   -> clean(MetricDataType.Int64);
            case FLOAT   -> clean(MetricDataType.Float);
            case DOUBLE  -> clean(MetricDataType.Double);
            case STRING  -> clean(MetricDataType.String);
            case DATETIME -> new MappedType(MetricDataType.DateTime, LossClass.PRECISION_LOSS,
                    Optional.of(SideChannelKind.UA_TICKS),
                    "OPC UA 100ns/1601 -> Sparkplug ms/1970 (10,000x precision loss + epoch shift); original ticks preserved in ua_ticks");
            case GUID -> new MappedType(MetricDataType.String, LossClass.TYPE_IDENTITY_LOSS,
                    Optional.empty(), "Guid (16 bytes) -> UUID string (type identity lost)");
            case LOCALIZEDTEXT -> new MappedType(MetricDataType.String, LossClass.TYPE_IDENTITY_LOSS,
                    Optional.of(SideChannelKind.UA_LOCALE), "LocalizedText -> text only (locale lost; optionally preserved in ua_locale)");
            case STATUSCODE -> new MappedType(MetricDataType.UInt32, LossClass.SIDE_CHANNEL_REQUIRED,
                    Optional.of(SideChannelKind.UA_STATUSCODE), "StatusCode -> UInt32 verbatim (no native Sparkplug metric type)");
            case UNKNOWN -> new MappedType(MetricDataType.String, LossClass.TYPE_IDENTITY_LOSS,
                    Optional.empty(), "unmapped/unsupported OPC UA DataType -> String (type identity lost)");
        };
    }
    private static MappedType clean(MetricDataType m) { return new MappedType(m, LossClass.CLEAN, Optional.empty(), ""); }
}
