package dev.krillin.sparkplug.opcua;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*; import org.junit.jupiter.api.Test;
class TypeFlattenerTest {
    private UaMember m(String n, UaDataType t) { return new UaMember(n, t, -1, Optional.empty(), "Mandatory"); }
    private UaTypeNode node(String bn, String sup, List<String> ifaces, UaMember... ms) {
        return new UaTypeNode(bn, Optional.ofNullable(sup), ifaces, List.of(ms));
    }
    @Test void supertypeMembersInlinedInOrderWithProvenance() {
        UaTypeSpace s = new UaTypeSpace(Map.of(
            "MotorType", node("MotorType","EquipmentType",List.of(), m("Rpm",UaDataType.DOUBLE)),
            "EquipmentType", node("EquipmentType",null,List.of(), m("State",UaDataType.INT32))));
        FlattenResult r = TypeFlattener.flatten(s, "MotorType");
        assertEquals(List.of("Rpm","State"), r.members().stream().map(FlatMember::name).toList()); // own members first
        assertEquals(Origin.SUPERTYPE, r.members().get(1).provenance().get(0).origin());
        assertTrue(r.conflicts().isEmpty());
    }
    @Test void subtypeOverridesBaseTypeMostDerivedWins() {
        UaTypeSpace s = new UaTypeSpace(Map.of(
            "Sub", node("Sub","Base",List.of(), m("V",UaDataType.DOUBLE)),
            "Base", node("Base",null,List.of(), m("V",UaDataType.INT32))));
        FlattenResult r = TypeFlattener.flatten(s, "Sub");
        assertEquals(1, r.members().size());
        assertEquals(UaDataType.DOUBLE, r.members().get(0).dataType());            // most-derived wins
        assertEquals(2, r.members().get(0).provenance().size());                   // OWN + SUPERTYPE
        assertTrue(r.conflicts().isEmpty());                                       // most-derived override is not a conflict
    }
    @Test void twoInterfacesSameMemberSameTypeDedup() {
        UaTypeSpace s = new UaTypeSpace(Map.of(
            "T", node("T",null,List.of("IA","IB")),
            "IA", node("IA",null,List.of(), m("Serial",UaDataType.STRING)),
            "IB", node("IB",null,List.of(), m("Serial",UaDataType.STRING))));
        FlattenResult r = TypeFlattener.flatten(s, "T");
        assertEquals(1, r.members().size());
        assertEquals(2, r.members().get(0).provenance().size());   // two INTERFACE sources
        assertTrue(r.conflicts().isEmpty());
    }
    @Test void twoInterfacesSameMemberTypeConflictSurfaced() {
        UaTypeSpace s = new UaTypeSpace(Map.of(
            "T", node("T",null,List.of("IA","IB")),
            "IA", node("IA",null,List.of(), m("Serial",UaDataType.STRING)),
            "IB", node("IB",null,List.of(), m("Serial",UaDataType.INT32))));
        FlattenResult r = TypeFlattener.flatten(s, "T");
        assertEquals(1, r.members().size());                       // deterministic fallback = first occurrence (IA, STRING)
        assertEquals(UaDataType.STRING, r.members().get(0).dataType());
        assertEquals(1, r.conflicts().size());
        assertEquals("Serial", r.conflicts().get(0).memberName());
    }
    @Test void objectTypeOwnWinsOverInterfaceSameName() {
        UaTypeSpace s = new UaTypeSpace(Map.of(
            "T", node("T",null,List.of("IA"), m("X",UaDataType.DOUBLE)),
            "IA", node("IA",null,List.of(), m("X",UaDataType.INT32))));
        FlattenResult r = TypeFlattener.flatten(s, "T");
        assertEquals(1, r.members().size());
        assertEquals(UaDataType.DOUBLE, r.members().get(0).dataType());  // own member takes precedence
        assertTrue(r.conflicts().isEmpty());                            // object-chain involvement means no Conflict recorded
    }
    @Test void orderIsDeterministicForAlias() {
        UaTypeSpace s = new UaTypeSpace(Map.of(
            "T", node("T","B",List.of("I"), m("a",UaDataType.DOUBLE), m("b",UaDataType.DOUBLE)),
            "B", node("B",null,List.of(), m("c",UaDataType.DOUBLE)),
            "I", node("I",null,List.of(), m("d",UaDataType.DOUBLE))));
        assertEquals(List.of("a","b","c","d"),
            TypeFlattener.flatten(s,"T").members().stream().map(FlatMember::name).toList());
    }
}
