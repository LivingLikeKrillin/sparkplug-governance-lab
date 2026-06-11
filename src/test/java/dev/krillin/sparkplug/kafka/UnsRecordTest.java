package dev.krillin.sparkplug.kafka;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.krillin.sparkplug.schema.JsonMapperFactory;
import org.junit.jupiter.api.Test;

class UnsRecordTest {
    @Test void jsonRoundTrip_preservesFields() throws Exception {
        ObjectMapper m = JsonMapperFactory.create();
        UnsRecord r = new UnsRecord("Acme:Busan:Press", "L1:GW3", null, "Rpm",
                1500.0, "Double", "GOOD", "Motor", "1.0.0", 1L, 123L);
        String json = m.writeValueAsString(r);
        assertTrue(json.contains("\"metricPath\":\"Rpm\""), json);
        assertTrue(json.contains("\"quality\":\"GOOD\""), json);
        UnsRecord back = m.readValue(json, UnsRecord.class);
        assertEquals("Rpm", back.metricPath());
        assertEquals(1500.0, back.value());
        assertEquals("Double", back.type());
        assertEquals("Motor", back.contractRef());
        assertEquals(1L, back.seq());
    }
}
