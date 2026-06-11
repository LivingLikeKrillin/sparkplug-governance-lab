package dev.krillin.sparkplug.kafka;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import dev.krillin.sparkplug.kafka.RecordBuilder.Emission;
import dev.krillin.sparkplug.kafka.TopicMapper.UnsAddress;
import dev.krillin.sparkplug.kafka.UnsStateStore.ResolvedMetric;
import dev.krillin.sparkplug.schema.*;
import dev.krillin.sparkplug.spb40.Quality;
import org.junit.jupiter.api.Test;

class RecordBuilderTest {

    private UdtDefinition motor() {
        return new UdtDefinition("Motor", SemVer.parse("1.0.0"),
                List.of(new Member("Rpm", "Double"), new Member("Running", "Boolean")),
                List.of(new Param("Location", "String")));
    }
    private final TopicMapper mapper = new TopicMapper();
    private final RecordBuilder builder = new RecordBuilder(mapper, new ContractValidator(), "uns.dlq");
    private UnsAddress addr() { return mapper.map("spBv1.0/Acme:Busan:Press/NDATA/L1:GW3"); }

    @Test void conformingMetric_routesToMainTopic() {
        List<Emission> es = builder.build(addr(), motor(),
                List.of(new ResolvedMetric("Rpm", "Double", 1535.0, Quality.GOOD)), 2L, 100L);
        assertEquals(1, es.size());
        Emission e = es.get(0);
        assertFalse(e.dlq());
        assertEquals("uns.Acme.Busan.Press.L1.GW3", e.topic());
        assertEquals("GW3/Rpm", e.key());
        assertEquals("Rpm", e.value().metricPath());
        assertEquals("Motor", e.value().contractRef());
    }

    @Test void violatingMetric_routesToDlq() {
        List<Emission> es = builder.build(addr(), motor(),
                List.of(new ResolvedMetric("Rpm", "String", "ERR", Quality.GOOD)), 3L, 100L);
        Emission e = es.get(0);
        assertTrue(e.dlq());
        assertEquals("uns.dlq", e.topic());
        assertFalse(e.violations().isEmpty());
    }

    @Test void nullContract_passesThroughToMain() {
        List<Emission> es = builder.build(addr(), null,
                List.of(new ResolvedMetric("Anything", "Double", 1.0, Quality.GOOD)), 0L, 0L);
        assertFalse(es.get(0).dlq());
    }
}
