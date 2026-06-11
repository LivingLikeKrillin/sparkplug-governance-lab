package dev.krillin.sparkplug.kafka;

import static org.junit.jupiter.api.Assertions.*;
import dev.krillin.sparkplug.kafka.TopicMapper.UnsAddress;
import org.junit.jupiter.api.Test;

class TopicMapperTest {
    private final TopicMapper mapper = new TopicMapper();

    @Test void map_nodeLevel_decodesIsa95() {
        UnsAddress a = mapper.map("spBv1.0/Acme:Busan:Press/NDATA/L1:GW3");
        assertEquals("Acme", a.enterprise());
        assertEquals("Busan", a.site());
        assertEquals("Press", a.area());
        assertEquals("L1", a.line());
        assertEquals("GW3", a.cell());
        assertNull(a.device());
    }

    @Test void kafkaTopic_joinsHierarchyWithDots() {
        assertEquals("uns.Acme.Busan.Press.L1.GW3",
                mapper.kafkaTopic(mapper.map("spBv1.0/Acme:Busan:Press/NDATA/L1:GW3")));
    }

    @Test void recordKey_nodeLevel_usesCellAndMetric() {
        UnsAddress a = mapper.map("spBv1.0/Acme:Busan:Press/NDATA/L1:GW3");
        assertEquals("GW3/Rpm", mapper.recordKey(a, "Rpm"));
    }

    @Test void map_deviceLevel_capturesDevice() {
        UnsAddress a = mapper.map("spBv1.0/Acme:Busan:Press/DDATA/L1:GW3/Press01");
        assertEquals("Press01", a.device());
        assertEquals("Press01/Rpm", mapper.recordKey(a, "Rpm"));
    }

    @Test void map_rejectsIllegalChars() {
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map("spBv1.0/Acme Inc:Busan:Press/NDATA/L1:GW3"));   // space in identifier
    }

    @Test void map_rejectsMalformedGroup() {
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map("spBv1.0/Acme:Busan/NDATA/L1:GW3"));            // group_id not 3-part
    }

    @Test void map_rejectsEmptyIdentifier() {
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map("spBv1.0/:Busan:Press/NDATA/L1:GW3"));        // empty enterprise segment
    }

    @Test void map_rejectsMalformedEdge() {
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map("spBv1.0/Acme:Busan:Press/NDATA/GW3"));         // edge not Line:Cell format
    }
}
