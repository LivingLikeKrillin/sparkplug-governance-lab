package dev.krillin.sparkplug.drift;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LivenessTrackerTest {

    @Test void notStale_withinThreshold() {
        LivenessTracker t = new LivenessTracker();
        t.markSeen("A", 1000);
        assertTrue(t.stale(1500, 1000).isEmpty());   // delta 500 <= 1000
    }
    @Test void stale_afterThreshold() {
        LivenessTracker t = new LivenessTracker();
        t.markSeen("A", 1000);
        assertTrue(t.stale(2500, 1000).contains("A")); // delta 1500 > 1000
    }
    @Test void boundary_equalThreshold_notStale() {
        LivenessTracker t = new LivenessTracker();
        t.markSeen("A", 1000);
        assertTrue(t.stale(2000, 1000).isEmpty());     // delta 1000, strict >
    }
    @Test void boundary_justOver_stale() {
        LivenessTracker t = new LivenessTracker();
        t.markSeen("A", 1000);
        assertTrue(t.stale(2001, 1000).contains("A"));
    }
    @Test void death_excludedFromStale() {
        LivenessTracker t = new LivenessTracker();
        t.markSeen("A", 1000);
        t.markDeath("A");
        assertTrue(t.stale(9999, 1000).isEmpty());
    }
    @Test void rebirth_clearsDeath() {
        LivenessTracker t = new LivenessTracker();
        t.markDeath("A");
        t.markSeen("A", 1000);                         // rebirth
        assertTrue(t.stale(2500, 1000).contains("A")); // tracking resumes after rebirth, node can become stale again
    }
}
