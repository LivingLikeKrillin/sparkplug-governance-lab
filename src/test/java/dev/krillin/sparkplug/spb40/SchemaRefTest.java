package dev.krillin.sparkplug.spb40;

import static org.junit.jupiter.api.Assertions.*;
import dev.krillin.sparkplug.schema.SemVer;
import org.junit.jupiter.api.Test;

class SchemaRefTest {
    @Test void format_joinsRefAndVersion() {
        assertEquals("Motor@1.1.0", new SchemaRef("Motor", SemVer.parse("1.1.0")).format());
    }
    @Test void parse_splitsOnAt() {
        SchemaRef r = SchemaRef.parse("Motor@1.1.0");
        assertEquals("Motor", r.templateRef());
        assertEquals(SemVer.parse("1.1.0"), r.version());
    }
    @Test void parse_roundTrips() {
        assertEquals("Motor2@2.0.0", SchemaRef.parse("Motor2@2.0.0").format());
    }
    @Test void parse_rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> SchemaRef.parse("Motor"));
        assertThrows(IllegalArgumentException.class, () -> SchemaRef.parse("@1.0.0"));
        assertThrows(IllegalArgumentException.class, () -> SchemaRef.parse("Motor@"));
    }
}
