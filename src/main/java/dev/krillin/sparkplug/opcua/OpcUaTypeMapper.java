package dev.krillin.sparkplug.opcua;

import java.util.*;
import dev.krillin.sparkplug.schema.*;

public final class OpcUaTypeMapper {
    private OpcUaTypeMapper() {}

    public static MapResult map(UaTypeSpace space, String target, SemVer version) {
        FlattenResult fr = TypeFlattener.flatten(space, target);
        List<Member> members = new ArrayList<>();
        List<LedgerEntry> ledger = new ArrayList<>();
        Map<String, UaEngInfo> engByMember = new LinkedHashMap<>();
        for (FlatMember fm : fr.members()) {
            MappedType mt = UaDataTypeMapper.map(fm.dataType());
            members.add(new Member(fm.name(), mt.metricType().toString()));
            ledger.add(new LedgerEntry(fm.name(), fm.dataType(), mt.metricType(), mt.lossClass(), mt.sideChannel(), mt.note()));
            fm.eng().ifPresent(e -> engByMember.put(fm.name(), e));
        }
        UdtDefinition udt = new UdtDefinition(target, version, members, List.of());
        return new MapResult(udt, new LossLedger(ledger), engByMember, fr.conflicts(), List.copyOf(fr.members()));
    }
}
