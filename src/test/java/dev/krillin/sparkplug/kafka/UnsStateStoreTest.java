package dev.krillin.sparkplug.kafka;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import dev.krillin.sparkplug.kafka.UnsStateStore.ResolvedMetric;
import dev.krillin.sparkplug.spb40.Quality;
import org.eclipse.tahu.message.model.*;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;
import org.junit.jupiter.api.Test;

class UnsStateStoreTest {

    private SparkplugBPayload nbirth() throws Exception {
        return new SparkplugBPayloadBuilder().setSeq(0L)
                .addMetric(new MetricBuilder("bdSeq", MetricDataType.Int64, 0L).createMetric())
                .addMetric(new MetricBuilder("Rpm", MetricDataType.Double, 1500.0).alias(1L).createMetric())
                .addMetric(new MetricBuilder("Running", MetricDataType.Boolean, true).alias(2L).createMetric())
                .createPayload();
    }

    @Test void learnBirth_thenApplyData_resolvesAliasToNameTypeValue() throws Exception {
        UnsStateStore s = new UnsStateStore();
        s.learnBirth("L1:GW3", nbirth());
        SparkplugBPayload ndata = new SparkplugBPayloadBuilder().setSeq(1L)
                .addMetric(new MetricBuilder(1L, MetricDataType.Double, 1520.0).createMetric())
                .createPayload();
        List<ResolvedMetric> out = s.applyData("L1:GW3", ndata);
        assertEquals(1, out.size());
        assertEquals("Rpm", out.get(0).name());
        assertEquals("Double", out.get(0).type());
        assertEquals(1520.0, out.get(0).value());
        assertEquals(Quality.GOOD, out.get(0).quality());
    }

    @Test void rbe_partialData_lastKnownHoldsFullState() throws Exception {
        UnsStateStore s = new UnsStateStore();
        s.learnBirth("L1:GW3", nbirth());                  // Rpm=1500, Running=true
        s.applyData("L1:GW3", new SparkplugBPayloadBuilder().setSeq(1L)
                .addMetric(new MetricBuilder(1L, MetricDataType.Double, 1520.0).createMetric())  // RBE: only Rpm
                .createPayload());
        Map<String, Object> latest = new HashMap<>();
        for (ResolvedMetric rm : s.lastKnown("L1:GW3")) latest.put(rm.name(), rm.value());
        assertEquals(1520.0, latest.get("Rpm"));           // updated
        assertEquals(true, latest.get("Running"));         // retains birth value (RBE)
        assertEquals(2, latest.size());
    }

    @Test void applyData_beforeBirth_isAwaitingBirth() throws Exception {
        UnsStateStore s = new UnsStateStore();
        assertTrue(s.awaitingBirth("L1:GW3"));
        List<ResolvedMetric> out = s.applyData("L1:GW3", new SparkplugBPayloadBuilder().setSeq(1L)
                .addMetric(new MetricBuilder(1L, MetricDataType.Double, 1520.0).createMetric())
                .createPayload());
        assertTrue(out.isEmpty());
    }

    @Test void rebirth_dropsMetric_lastKnownReflectsNewBaseline() throws Exception {
        UnsStateStore s = new UnsStateStore();
        s.learnBirth("L1:GW3", nbirth());                  // Rpm alias=1, Running alias=2
        s.learnBirth("L1:GW3", new SparkplugBPayloadBuilder().setSeq(0L)   // rebirth: Rpm only (Running dropped)
                .addMetric(new MetricBuilder("Rpm", MetricDataType.Double, 1600.0).alias(1L).createMetric())
                .createPayload());
        Map<String, Object> latest = new HashMap<>();
        for (ResolvedMetric rm : s.lastKnown("L1:GW3")) latest.put(rm.name(), rm.value());
        assertEquals(1, latest.size());                    // exactly one metric
        assertEquals(1600.0, latest.get("Rpm"));
        assertFalse(latest.containsKey("Running"));        // dropped metric does not linger
    }

    @Test void markDeath_marksAllStale() throws Exception {
        UnsStateStore s = new UnsStateStore();
        s.learnBirth("L1:GW3", nbirth());
        List<ResolvedMetric> stale = s.markDeath("L1:GW3");
        assertEquals(2, stale.size());
        assertTrue(stale.stream().allMatch(rm -> rm.quality() == Quality.STALE));
        assertTrue(s.lastKnown("L1:GW3").stream().allMatch(rm -> rm.quality() == Quality.STALE));
    }
}
