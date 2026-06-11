package dev.krillin.sparkplug.opcua;
import java.util.List;
public record Conflict(String memberName, String reason, List<Provenance> sources) {}
