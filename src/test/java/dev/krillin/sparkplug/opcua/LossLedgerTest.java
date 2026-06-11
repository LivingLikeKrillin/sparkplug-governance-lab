package dev.krillin.sparkplug.opcua;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*; import org.eclipse.tahu.message.model.MetricDataType;
import org.junit.jupiter.api.Test;
class LossLedgerTest {
    private LedgerEntry e(LossClass c, Optional<SideChannelKind> sc) {
        return new LedgerEntry("m", UaDataType.DOUBLE, MetricDataType.Double, c, sc, "");
    }
    @Test void countsByClass() {
        LossLedger l = new LossLedger(List.of(
            e(LossClass.CLEAN, Optional.empty()),
            e(LossClass.CLEAN, Optional.empty()),
            e(LossClass.PRECISION_LOSS, Optional.of(SideChannelKind.UA_TICKS)),
            e(LossClass.SIDE_CHANNEL_REQUIRED, Optional.of(SideChannelKind.UA_STATUSCODE)),
            e(LossClass.TYPE_IDENTITY_LOSS, Optional.empty())));   // identity-loss, no side-channel
        assertEquals(2, l.cleanCount());          // lossClass==CLEAN
        assertEquals(2, l.sideChannelCount());    // sideChannel().isPresent()
        assertEquals(1, l.identityLossCount());   // TYPE_IDENTITY_LOSS && sideChannel empty
        assertTrue(l.summary().contains("clean"));
    }
}
