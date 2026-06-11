package dev.krillin.sparkplug.spb40;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import dev.krillin.sparkplug.schema.Member;
import dev.krillin.sparkplug.schema.Param;
import dev.krillin.sparkplug.schema.SemVer;
import dev.krillin.sparkplug.schema.UdtDefinition;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.junit.jupiter.api.Test;

class Spb40SizeTest {

    private UdtDefinition motor() {
        return new UdtDefinition("Motor", SemVer.parse("1.1.0"),
                List.of(new Member("Rpm", "Double"), new Member("Running", "Boolean"), new Member("Temperature", "Double")),
                List.of(new Param("Location", "String")));
    }

    /**
     * SpB 4.0 #608 gain: fat NBIRTH (inline _types_ Template + instance) and thin NBIRTH (schemaRef + values)
     * carry the same member values, but only fat bundles the schema (Template definition structure).
     * The difference equals the size of the separated schema.
     * Data types are present on both wire formats (not part of the difference).
     * Both are encoded with getBytes(p, false).
     */
    @Test void thinBirthIsSmallerThanFatBirth() throws Exception {
        UdtDefinition def = motor();
        Map<String, String> units = Map.of("Rpm", "rpm", "Temperature", "degC");
        Map<String, Object> values = Map.of("Rpm", 1500.0, "Running", true, "Temperature", 65.4);
        Map<String, Integer> qualities = Map.of("Rpm", 0, "Running", 0, "Temperature", 0);

        SparkplugBPayloadEncoder enc = new SparkplugBPayloadEncoder();
        byte[] fat = enc.getBytes(ThinCodec.buildFatBirth(def, units, values, 0L), false);
        byte[] thin = enc.getBytes(ThinCodec.buildThin(def, values, qualities, 0L), false);

        assertTrue(thin.length < fat.length, "thin=" + thin.length + " fat=" + fat.length);
    }
}
