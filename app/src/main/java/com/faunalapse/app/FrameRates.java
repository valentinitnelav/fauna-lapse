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

    /**
     * Picks the frames for a video of {@code fps} frames per s from a faster camera: the first frame,
     * then the first frame at or after each step of 1 / fps. A frame up to a quarter step early
     * still counts, so a camera at a whole multiple of fps gives every n-th frame despite small
     * delays. After a gap (frames lost), the steps count again from the next frame.
     */
    static final class Picker {
        private final long stepNs;
        private long nextNs;
        private boolean started;

        Picker(int fps) {
            stepNs = 1000000000L / fps;
        }

        /** True when the frame with this time stamp (ns) goes into the video. */
        boolean take(long ns) {
            if (started && ns < nextNs - stepNs / 4) return false;
            nextNs = started && ns - nextNs <= stepNs ? nextNs + stepNs : ns + stepNs;
            started = true;
            return true;
        }
    }
}
