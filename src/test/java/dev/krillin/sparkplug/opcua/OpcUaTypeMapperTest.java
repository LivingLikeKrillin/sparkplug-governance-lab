package dev.krillin.sparkplug.opcua;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.krillin.sparkplug.schema.SemVer;
import dev.krillin.sparkplug.spb40.DefinitionCodec;

class OpcUaTypeMapperTest {
    private UaMember m(String n, UaDataType t, UaEngInfo eng) {
        return new UaMember(n, t, -1, Optional.ofNullable(eng), "Mandatory");
    }

    private UaTypeSpace motorSpace() {
        return new UaTypeSpace(Map.of(
            "MotorType", new UaTypeNode("MotorType", Optional.of("EquipmentType"), List.of("IVendorNameplateType"),
                List.of(m("Rpm", UaDataType.DOUBLE, new UaEngInfo("rpm", 0.0, 3000.0)), m("Running", UaDataType.BOOLEAN, null))),
            "EquipmentType", new UaTypeNode("EquipmentType", Optional.empty(), List.of(),
                List.of(m("LastMaintenance", UaDataType.DATETIME, null))),
            "IVendorNameplateType", new UaTypeNode("IVendorNameplateType", Optional.empty(), List.of(),
                List.of(m("Manufacturer", UaDataType.STRING, null)))));
    }

    @Test void buildsUdtDefinitionMembersInFlattenOrderWithTahuTypeNames() {
        MapResult r = OpcUaTypeMapper.map(motorSpace(), "MotorType", SemVer.parse("1.0.0"));
        assertEquals(List.of("Rpm", "Running", "LastMaintenance", "Manufacturer"),
            r.udt().members().stream().map(x -> x.name()).toList());
        assertEquals("Double", r.udt().members().get(0).type());
        assertEquals("DateTime", r.udt().members().get(2).type());
    }

    @Test void aliasMatchesSpb40Convention() {
        MapResult r = OpcUaTypeMapper.map(motorSpace(), "MotorType", SemVer.parse("1.0.0"));
        assertEquals(1L, DefinitionCodec.aliasOf(r.udt(), "Rpm"));
        assertEquals(3L, DefinitionCodec.aliasOf(r.udt(), "LastMaintenance"));
    }

    @Test void engInfoCapturedForRpm() {
        MapResult r = OpcUaTypeMapper.map(motorSpace(), "MotorType", SemVer.parse("1.0.0"));
        assertEquals("rpm", r.engByMember().get("Rpm").unit());
    }

    @Test void ledgerHasDateTimePrecisionLossEntry() {
        MapResult r = OpcUaTypeMapper.map(motorSpace(), "MotorType", SemVer.parse("1.0.0"));
        assertTrue(r.ledger().entries().stream()
            .anyMatch(e -> e.memberName().equals("LastMaintenance") && e.lossClass() == LossClass.PRECISION_LOSS));
        assertTrue(r.ledger().summary().contains("clean"));
    }

    @Test void flatMembersProvenanceExposedInterfaceSource() {
        MapResult r = OpcUaTypeMapper.map(motorSpace(), "MotorType", SemVer.parse("1.0.0"));
        assertFalse(r.flatMembers().isEmpty(), "flatMembers must not be empty");
        // Manufacturer comes from IVendorNameplateType via INTERFACE origin
        FlatMember manufacturer = r.flatMembers().stream()
            .filter(fm -> fm.name().equals("Manufacturer")).findFirst().orElseThrow();
        assertTrue(manufacturer.provenance().stream().anyMatch(p -> p.origin() == Origin.INTERFACE),
            "Manufacturer must have INTERFACE provenance");
        assertEquals("IVendorNameplateType",
            manufacturer.provenance().stream()
                .filter(p -> p.origin() == Origin.INTERFACE)
                .map(Provenance::sourceType)
                .findFirst().orElse(null));
    }
}
