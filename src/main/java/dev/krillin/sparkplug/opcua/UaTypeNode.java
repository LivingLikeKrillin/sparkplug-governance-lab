package dev.krillin.sparkplug.opcua;
import java.util.List;
import java.util.Optional;
public record UaTypeNode(String browseName, Optional<String> superType,
                         List<String> interfaces, List<UaMember> declaredMembers) {}
