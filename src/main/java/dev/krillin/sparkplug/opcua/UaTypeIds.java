package dev.krillin.sparkplug.opcua;
import java.util.Map;
import java.util.Optional;
public final class UaTypeIds {
    private UaTypeIds() {}
    private static final Map<Integer, UaDataType> BY_ID = Map.ofEntries(
        Map.entry(1, UaDataType.BOOLEAN), Map.entry(6, UaDataType.INT32), Map.entry(7, UaDataType.UINT32),
        Map.entry(8, UaDataType.INT64), Map.entry(10, UaDataType.FLOAT), Map.entry(11, UaDataType.DOUBLE),
        Map.entry(12, UaDataType.STRING), Map.entry(13, UaDataType.DATETIME), Map.entry(14, UaDataType.GUID),
        Map.entry(19, UaDataType.STATUSCODE), Map.entry(21, UaDataType.LOCALIZEDTEXT));
    public static Optional<UaDataType> of(int numericId) { return Optional.ofNullable(BY_ID.get(numericId)); }
}
