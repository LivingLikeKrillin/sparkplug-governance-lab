package dev.krillin.sparkplug.opcua;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.eclipse.tahu.message.model.*;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import dev.krillin.sparkplug.schema.*;
import dev.krillin.sparkplug.spb40.DefinitionCodec;
import dev.krillin.sparkplug.spb40.Quality;
import dev.krillin.sparkplug.spb40.SchemaRef;
import dev.krillin.sparkplug.spb40.TahuTypes;

class OpcUaThinCodecTest {
    private UdtDefinition motorUdt() {
        return new UdtDefinition("Motor", SemVer.parse("1.0.0"),
            List.of(new Member("Rpm", "Double"), new Member("LastMaintenance", "DateTime")), List.of());
    }

    @Test void qualityKeyBindingMatchesThinCodec() {
        // Invariant: UaSideChannel.QUALITY must equal ThinCodec.QUALITY so both codecs write the same property key
        assertEquals(dev.krillin.sparkplug.spb40.ThinCodec.QUALITY, UaSideChannel.QUALITY);
    }

    @Test void severityProjection() {
        assertEquals(Quality.GOOD,  UaSideChannel.toQuality(0x00000000L));
        assertEquals(Quality.BAD,   UaSideChannel.toQuality(0x80000000L));
        assertEquals(Quality.STALE, UaSideChannel.toQuality(0x40900000L)); // UncertainLastUsableValue
        assertEquals(Quality.GOOD,  UaSideChannel.toQuality(0x40000000L)); // generic Uncertain -> GOOD (projection loss)
        // #3: reserved severity 0xC0 → BAD
        assertEquals(Quality.BAD,   UaSideChannel.toQuality(0xC0000000L));
        // #4: UncertainLastUsableValue with InfoBits → still STALE
        assertEquals(Quality.STALE, UaSideChannel.toQuality(0x40900000L | 0xFFL));
    }

    @Test void thinPayloadCarriesAliasAndSideChannels() throws Exception {
        UdtDefinition udt = motorUdt();
        List<MemberSample> samples = List.of(
            new MemberSample("Rpm", 1535.0, 0x00000000L, Optional.empty()),
            new MemberSample("LastMaintenance", new Date(0L), 0x40000000L, Optional.of(132000000000000000L)));
        SparkplugBPayload p = OpcUaThinCodec.buildThin(new SchemaRef("Motor", SemVer.parse("1.0.0")), samples, udt);
        byte[] bytes = new SparkplugBPayloadEncoder().getBytes(p, false);
        SparkplugBPayload back = new SparkplugBPayloadDecoder().buildFromByteArray(bytes, null);
        // schemaRef sentinel metric present + alias-only data metrics + ua_statuscode/ua_ticks properties
        Metric rpm = back.getMetrics().stream().filter(x -> x.getAlias() != null && x.getAlias() == 1L).findFirst().orElseThrow();
        assertNotNull(rpm.getProperties().getPropertyValue(UaSideChannel.UA_STATUSCODE));
        Metric lm = back.getMetrics().stream().filter(x -> x.getAlias() != null && x.getAlias() == 2L).findFirst().orElseThrow();
        assertNotNull(lm.getProperties().getPropertyValue(UaSideChannel.UA_TICKS));      // DateTime members only
        assertNull(rpm.getProperties().getPropertyValue(UaSideChannel.UA_TICKS));        // Double members have no ua_ticks
    }
}
