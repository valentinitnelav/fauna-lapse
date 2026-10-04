package com.faunalapse.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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

    /** The kept frame numbers among {@code count} frames at {@code cameraFps}, each late by up to
     *  {@code jitterMs} (a fixed pattern). */
    private static String kept(int fps, double cameraFps, int count, double jitterMs) {
        FrameRates.Picker p = new FrameRates.Picker(fps);
        StringBuilder s = new StringBuilder();
        double[] late = {0, 0.7, 0.2, 1.0, 0.4};
        for (int i = 0; i < count; i++) {
            long ns = Math.round((1000 + i * 1000 / cameraFps + late[i % 5] * jitterMs) * 1e6);
            if (p.take(ns)) s.append(i).append(' ');
        }
        return s.toString().trim();
    }

    @Test
    public void picksEveryNthFrameDespiteSmallDelays() {
        assertEquals("0 3 6 9 12", kept(5, 15, 15, 0));
        assertEquals("0 3 6 9 12", kept(5, 15, 15, 10)); // up to 10 ms late
        assertEquals("0 2 4 6 8", kept(10, 20, 10, 8));
        assertEquals("0 1 2 3 4", kept(15, 15, 5, 5));
    }

    @Test
    public void startsAgainAfterLostFrames() {
        FrameRates.Picker p = new FrameRates.Picker(5); // steps of 200 ms
        assertEquals(true, p.take(0));
        assertEquals(false, p.take(66_000_000L));
        assertEquals(true, p.take(200_000_000L));
        assertEquals(true, p.take(733_000_000L)); // frames lost: steps count from here
        assertEquals(false, p.take(800_000_000L));
        assertEquals(true, p.take(933_000_000L));
    }

    @Test
    public void slowerOrUnevenCamerasGiveEveryFrame() {
        assertEquals("0 1 2 3", kept(15, 10, 4, 0)); // camera slower than the video
    }
}
