package dev.krillin.sparkplug.spb40;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import dev.krillin.sparkplug.schema.Member;
import dev.krillin.sparkplug.schema.Param;
import dev.krillin.sparkplug.schema.SemVer;
import dev.krillin.sparkplug.schema.UdtDefinition;
import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.junit.jupiter.api.Test;

class DefinitionCodecTest {

    private UdtDefinition motor() {
        return new UdtDefinition("Motor", SemVer.parse("1.1.0"),
                List.of(new Member("Rpm", "Double"), new Member("Running", "Boolean"), new Member("Temperature", "Double")),
                List.of(new Param("Location", "String")));
    }

    @Test void alias_isOneBasedMemberOrder() {
        UdtDefinition def = motor();
        assertEquals(1L, DefinitionCodec.aliasOf(def, "Rpm"));
        assertEquals(3L, DefinitionCodec.aliasOf(def, "Temperature"));
        assertEquals("Rpm", DefinitionCodec.memberByAlias(def, 1L).name());
        assertEquals("Temperature", DefinitionCodec.memberByAlias(def, 3L).name());
    }

    @Test void aliasOutOfRange_throws() {
        assertThrows(IllegalArgumentException.class, () -> DefinitionCodec.memberByAlias(motor(), 9L));
    }

    @Test void roundTrip_preservesStructureOrderAndEngUnit() throws Exception {
        UdtDefinition def = motor();
        Map<String, String> units = Map.of("Rpm", "rpm", "Temperature", "degC");
        SparkplugBPayload payload = DefinitionCodec.buildDefinition(def, units);

        byte[] bytes = new SparkplugBPayloadEncoder().getBytes(payload, false);
        SparkplugBPayload back = new SparkplugBPayloadDecoder().buildFromByteArray(bytes, null);
        DefinitionCodec.ParsedDefinition parsed = DefinitionCodec.parse(back);

        assertEquals("Motor", parsed.def().templateRef());
        assertEquals(SemVer.parse("1.1.0"), parsed.def().version());
        assertEquals(List.of("Rpm", "Running", "Temperature"),
                parsed.def().members().stream().map(Member::name).toList());
        assertEquals("Boolean", parsed.def().members().get(1).type());
        assertEquals("rpm", parsed.units().get("Rpm"));
        assertEquals("degC", parsed.units().get("Temperature"));
        assertFalse(parsed.units().containsKey("Running"));
    }

    @Test void parse_rejectsPayloadWithoutTypesMetric() {
        SparkplugBPayload empty = new org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder().createPayload();
        assertThrows(IllegalArgumentException.class, () -> DefinitionCodec.parse(empty));
    }
}
