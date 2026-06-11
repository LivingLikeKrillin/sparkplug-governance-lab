package dev.krillin.sparkplug.opcua;
import java.util.List;
import java.util.Optional;
public record FlatMember(String name, UaDataType dataType, int valueRank,
                         Optional<UaEngInfo> eng, List<Provenance> provenance) {}
