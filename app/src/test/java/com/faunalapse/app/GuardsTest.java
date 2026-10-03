package com.faunalapse.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GuardsTest {

    @Test
    public void heatPauseStartsAtTheLimitAndEndsThreeDegreesLower() {
        assertFalse(Guards.tooHot(44.9, 45, false));
        assertTrue(Guards.tooHot(45.0, 45, false));
        assertTrue(Guards.tooHot(43.0, 45, true)); // paused: still too warm
        assertTrue(Guards.tooHot(42.1, 45, true));
        assertFalse(Guards.tooHot(42.0, 45, true)); // cool enough to go on
    }

    @Test
    public void lowBatteryOnlyWhenNotCharging() {
        assertTrue(Guards.batteryLow(15, false));
        assertFalse(Guards.batteryLow(16, false));
        assertFalse(Guards.batteryLow(5, true)); // on a power bank: keep going
        assertFalse(Guards.batteryLow(-1, false)); // unknown
    }

    @Test
    public void spaceForTheNextBurstOrClip() {
        // 60 s at a 2 s step: 31 squares of 1024 px at 0.4 byte per pixel.
        assertEquals(31L * 419430, Guards.burstBytes(60000, 2000, 1024));
        // 5 min of video at 8 Mbit/s: 300 MB plus 10 %.
        assertEquals(330000000L, Guards.clipBytes(300000, 8000000));
        long reserve = Guards.RESERVE_BYTES;
        assertTrue(Guards.storageLow(reserve + 999, 1000));
        assertFalse(Guards.storageLow(reserve + 1000, 1000));
    }
}
