package com.faunalapse.app;

/**
 * Safety rules for unattended sessions: a heat pause, and clean stops when the battery runs low or
 * the storage is nearly full. Pure Java, tested on the PC; SessionRunner reads the phone and acts.
 */
final class Guards {

    /** Stop when the phone is not charging and its battery is at or below this (the power bank is
     *  empty: stop while there is still power to close the files cleanly). */
    static final int LOW_BATTERY_PERCENT = 15;
    /** A heat pause ends this much below the limit, so the camera does not flick on and off at the
     *  limit (FaunaPulse uses the same gap). */
    static final double RESUME_GAP_C = 3;
    /** Free space left for the phone itself, beyond the next burst or clip. */
    static final long RESERVE_BYTES = 200L * 1024 * 1024;
    /** Generous JPEG size per saved pixel (a 1024 px square was about 0.16 byte per pixel on the
     *  Samsung M12, 170 KB). */
    static final double BYTES_PER_SAVED_PIXEL = 0.4;

    private Guards() {
    }

    /** True while the session should wait for the phone to cool down: from {@code limitC} on, and
     *  while paused until the battery is {@link #RESUME_GAP_C} cooler. */
    static boolean tooHot(double tempC, double limitC, boolean paused) {
        return paused ? tempC > limitC - RESUME_GAP_C : tempC >= limitC;
    }

    /** True when the battery is low and not charging ({@code percent} -1 = unknown: never low). */
    static boolean batteryLow(int percent, boolean charging) {
        return !charging && percent >= 0 && percent <= LOW_BATTERY_PERCENT;
    }

    /** Space one photo burst may need: one square per step, at a generous size. */
    static long burstBytes(long burstMs, long stepMs, int savedSidePx) {
        long photos = burstMs / stepMs + 1;
        return photos * (long) (BYTES_PER_SAVED_PIXEL * savedSidePx * savedSidePx);
    }

    /** Space one video clip may need: its bit rate for its length, plus 10 % for the file. */
    static long clipBytes(long clipMs, long bitsPerSecond) {
        return clipMs * bitsPerSecond / 8000 * 11 / 10;
    }

    /** True when the next burst or clip would leave less than the reserve free. */
    static boolean storageLow(long freeBytes, long nextBytes) {
        return freeBytes < nextBytes + RESERVE_BYTES;
    }
}
