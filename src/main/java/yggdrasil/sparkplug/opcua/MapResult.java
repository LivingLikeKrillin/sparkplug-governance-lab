package yggdrasil.sparkplug.opcua;

import yggdrasil.sparkplug.schema.UdtDefinition;
import java.util.List;
import java.util.Map;

public record MapResult(UdtDefinition udt, LossLedger ledger, Map<String, UaEngInfo> engByMember, List<Conflict> conflicts, List<FlatMember> flatMembers) {}
