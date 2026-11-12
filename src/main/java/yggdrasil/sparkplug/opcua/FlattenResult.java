package yggdrasil.sparkplug.opcua;
import java.util.List;
public record FlattenResult(List<FlatMember> members, List<Conflict> conflicts) {}
