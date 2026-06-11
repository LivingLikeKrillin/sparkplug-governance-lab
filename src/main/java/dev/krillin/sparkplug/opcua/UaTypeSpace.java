package dev.krillin.sparkplug.opcua;
import java.util.Map;
import java.util.Optional;
public record UaTypeSpace(Map<String, UaTypeNode> nodes) {
    public Optional<UaTypeNode> node(String browseName) { return Optional.ofNullable(nodes.get(browseName)); }
}
