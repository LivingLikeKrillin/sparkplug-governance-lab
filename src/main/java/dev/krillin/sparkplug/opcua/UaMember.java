package dev.krillin.sparkplug.opcua;
import java.util.Optional;
public record UaMember(String browseName, UaDataType dataType, int valueRank,
                       Optional<UaEngInfo> eng, String modellingRule) {}
