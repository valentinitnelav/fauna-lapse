package com.faunalapse.app;

/**
 * Video below the camera's slowest rate: the camera runs at a fixed rate that is a whole multiple
 * of the video's frame rate, and only every n-th frame goes into the video. Most cameras cannot run
 * slower than 8 to 15 frames per s; a fixed rate also keeps each exposure at most one camera frame
 * long (less motion blur) and the frames evenly spaced in time.
 */
final class FrameRates {

    private FrameRates() {
    }

    /**
     * The lowest fixed camera rate (a range with min = max) that is a whole multiple of {@code fps},
     * as {rate, keepEvery}; null when the camera has none. {@code ranges}: {min, max} in frames per s.
     */
    static int[] fixedRateFor(int[][] ranges, int fps) {
        int best = 0;
        for (int[] r : ranges) {
            if (r[0] == r[1] && r[0] >= fps && r[0] % fps == 0 && (best == 0 || r[0] < best)) best = r[0];
        }
        return best == 0 ? null : new int[] {best, best / fps};
    }
}
