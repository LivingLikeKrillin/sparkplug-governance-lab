package dev.krillin.sparkplug.opcua;
import static org.junit.jupiter.api.Assertions.*;
import org.eclipse.tahu.message.model.MetricDataType;
import org.junit.jupiter.api.Test;

class UaDataTypeMapperTest {
    @Test void cleanTypesMapOneToOne() {
        assertEquals(MetricDataType.Double, UaDataTypeMapper.map(UaDataType.DOUBLE).metricType());
        assertEquals(LossClass.CLEAN, UaDataTypeMapper.map(UaDataType.DOUBLE).lossClass());
        assertEquals(LossClass.CLEAN, UaDataTypeMapper.map(UaDataType.BOOLEAN).lossClass());
        assertEquals(MetricDataType.UInt32, UaDataTypeMapper.map(UaDataType.UINT32).metricType());
    }
    @Test void dateTimeIsPrecisionLossWithTicksSideChannel() {
        MappedType m = UaDataTypeMapper.map(UaDataType.DATETIME);
        assertEquals(MetricDataType.DateTime, m.metricType());
        assertEquals(LossClass.PRECISION_LOSS, m.lossClass());
        assertEquals(java.util.Optional.of(SideChannelKind.UA_TICKS), m.sideChannel());
    }
    @Test void guidAndLocalizedTextAreTypeIdentityLoss() {
        assertEquals(MetricDataType.String, UaDataTypeMapper.map(UaDataType.GUID).metricType());
        assertEquals(LossClass.TYPE_IDENTITY_LOSS, UaDataTypeMapper.map(UaDataType.GUID).lossClass());
        assertEquals(LossClass.TYPE_IDENTITY_LOSS, UaDataTypeMapper.map(UaDataType.LOCALIZEDTEXT).lossClass());
    }
    @Test void statusCodeRequiresSideChannel() {
        MappedType m = UaDataTypeMapper.map(UaDataType.STATUSCODE);
        assertEquals(LossClass.SIDE_CHANNEL_REQUIRED, m.lossClass());
        assertEquals(java.util.Optional.of(SideChannelKind.UA_STATUSCODE), m.sideChannel());
    }
    @Test void unknownDataTypeIsTypeIdentityLossToString() {
        MappedType m = UaDataTypeMapper.map(UaDataType.UNKNOWN);
        assertEquals(MetricDataType.String, m.metricType());
        assertEquals(LossClass.TYPE_IDENTITY_LOSS, m.lossClass());
    }
}
