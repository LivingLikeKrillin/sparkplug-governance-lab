package dev.krillin.sparkplug.spb40;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class QualityTest {
    @Test void fromCode_mapsKnownCodes() {
        assertEquals(Quality.GOOD, Quality.fromCode(0));
        assertEquals(Quality.STALE, Quality.fromCode(1));
        assertEquals(Quality.BAD, Quality.fromCode(2));
    }
    @Test void code_isStable() {
        assertEquals(0, Quality.GOOD.code());
        assertEquals(2, Quality.BAD.code());
    }
    @Test void isUsable_onlyGood() {
        assertTrue(Quality.GOOD.isUsable());
        assertFalse(Quality.BAD.isUsable());
        assertFalse(Quality.STALE.isUsable());
    }
    @Test void fromCode_rejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> Quality.fromCode(99));
    }
}
