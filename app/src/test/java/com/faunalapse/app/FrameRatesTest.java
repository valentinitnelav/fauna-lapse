package com.faunalapse.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class FrameRatesTest {

    /** The back camera of the Samsung M12 (Camera2; Camera1 lists the same, times 1000). */
    private static final int[][] SAMSUNG_M12 = {
        {15, 15}, {15, 20}, {20, 20}, {24, 24}, {8, 30}, {10, 30}, {15, 30}, {30, 30},
    };

    @Test
    public void lowestFixedRateThatIsAMultiple() {
        assertArrayEquals(new int[] {15, 3}, FrameRates.fixedRateFor(SAMSUNG_M12, 5));
        assertArrayEquals(new int[] {20, 2}, FrameRates.fixedRateFor(SAMSUNG_M12, 10));
        assertArrayEquals(new int[] {15, 1}, FrameRates.fixedRateFor(SAMSUNG_M12, 15));
    }

    @Test
    public void onlyFixedRangesCount() {
        assertArrayEquals(new int[] {30, 6}, FrameRates.fixedRateFor(new int[][] {{5, 30}, {30, 30}}, 5));
        assertNull(FrameRates.fixedRateFor(new int[][] {{5, 30}, {7, 30}}, 5));
        assertNull(FrameRates.fixedRateFor(new int[][] {{24, 24}}, 10));
    }
}
