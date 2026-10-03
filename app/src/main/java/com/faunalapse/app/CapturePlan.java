package com.faunalapse.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * When to record. Pure schedule maths without Android classes, so it is tested on the PC
 * (`./gradlew test`).
 *
 * A session records inside periods: either one run of a fixed length from the session start, or
 * daily windows (clock times such as 06:00 to 08:00) on a number of calendar days. Inside each period
 * bursts (photo bursts or video clips) follow a fixed grid counted from the period start: burst,
 * break, burst, break, and so on. A burst is cut at the period end. Every answer is computed from the
 * clock time passed in, never from counters, so a late wake-up or a clock change heals itself.
 *
 * Idea from FaunaPulse (SchedulePlan and TimeLapsePlan by the same owner), rewritten in Java.
 */
final class CapturePlan {

    /** One photo burst or video clip, in ms since 1970: start included, end excluded. */
    static final class Burst {
        final long start;
        final long end;

        Burst(long start, long end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Burst && ((Burst) o).start == start && ((Burst) o).end == end;
        }

        @Override
        public int hashCode() {
            return Long.valueOf(start).hashCode() * 31 + Long.valueOf(end).hashCode();
        }

        @Override
        public String toString() {
            return "Burst(" + start + ", " + end + ")";
        }
    }

    private final long burstMs;
    private final long breakMs;
    /** Recording periods as {start, end} in ms since 1970, sorted, not overlapping. */
    private final List<long[]> periods;

    private CapturePlan(List<long[]> periods, long burstMs, long breakMs) {
        if (burstMs <= 0 || breakMs < 0) throw new IllegalArgumentException("burst must be > 0, break >= 0");
        this.periods = Collections.unmodifiableList(periods);
        this.burstMs = burstMs;
        this.breakMs = breakMs;
    }

    /** One run of {@code runMs} from {@code sessionStart}. */
    static CapturePlan run(long sessionStart, long runMs, long burstMs, long breakMs) {
        if (runMs <= 0) throw new IllegalArgumentException("run length must be > 0");
        List<long[]> periods = new ArrayList<>();
        periods.add(new long[] {sessionStart, sessionStart + runMs});
        return new CapturePlan(periods, burstMs, breakMs);
    }

    /**
     * Daily windows (from {@link #parseWindows}) on {@code days} calendar days, counted from the
     * date of {@code sessionStart} in {@code zone}. Windows already over at the session start are
     * left out; a window that is running keeps its grid (bursts stay at the same clock times).
     */
    static CapturePlan daily(long sessionStart, int[][] windows, int days, TimeZone zone,
                             long burstMs, long breakMs) {
        if (days < 1) throw new IllegalArgumentException("days must be at least 1");
        List<long[]> periods = new ArrayList<>();
        for (int day = 0; day < days; day++) {
            for (int[] w : windows) {
                long start = clockTime(sessionStart, day, w[0], zone);
                long end = clockTime(sessionStart, day, w[1], zone);
                if (end > sessionStart) periods.add(new long[] {start, end});
            }
        }
        if (periods.isEmpty()) throw new IllegalArgumentException("no window left after the start");
        return new CapturePlan(periods, burstMs, breakMs);
    }

    /** The burst running at {@code now}, or else the next one; null when the schedule is over. */
    Burst burstAtOrAfter(long now) {
        long cycle = burstMs + breakMs;
        for (long[] p : periods) {
            if (p[1] <= now) continue; // this period is over
            long from = Math.max(now, p[0]);
            long start = p[0] + (from - p[0]) / cycle * cycle;
            long end = Math.min(start + burstMs, p[1]);
            if (from < end) return new Burst(start, end); // inside this burst
            start += cycle;
            if (start < p[1]) return new Burst(start, Math.min(start + burstMs, p[1]));
        }
        return null;
    }

    /** First photo time of the burst at or after {@code now}: the burst start plus a whole number of
     *  steps, before the burst end; -1 when no photo is left in this burst. */
    static long nextPhotoTime(Burst burst, long stepMs, long now) {
        if (now <= burst.start) return burst.start;
        long steps = (now - burst.start + stepMs - 1) / stepMs; // rounded up
        long t = burst.start + steps * stepMs;
        return t < burst.end ? t : -1;
    }

    /** End of the last period: the session cannot last longer. */
    long end() {
        return periods.get(periods.size() - 1)[1];
    }

    List<long[]> periods() {
        return periods;
    }

    /**
     * Reads daily windows such as "06:00-08:00, 17:00-20:00" as {start, end} minutes since midnight,
     * sorted by start. "24:00" ends a window at midnight. A window may not cross midnight (write
     * "20:00-24:00, 00:00-06:00" instead) and windows may not overlap.
     */
    static int[][] parseWindows(String text) {
        List<int[]> list = new ArrayList<>();
        for (String part : text.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            String[] ends = part.split("-");
            if (ends.length != 2) throw new IllegalArgumentException("write windows like 06:00-08:00");
            int start = minutes(ends[0]);
            int end = minutes(ends[1]);
            if (start >= end) {
                throw new IllegalArgumentException(part + ": the end must be later than the start"
                        + " (split windows at midnight)");
            }
            list.add(new int[] {start, end});
        }
        if (list.isEmpty()) throw new IllegalArgumentException("give at least one window, like 06:00-08:00");
        int[][] windows = list.toArray(new int[0][]);
        Arrays.sort(windows, (a, b) -> Integer.compare(a[0], b[0]));
        for (int i = 1; i < windows.length; i++) {
            if (windows[i][0] < windows[i - 1][1]) throw new IllegalArgumentException("windows overlap");
        }
        return windows;
    }

    private static int minutes(String hhmm) {
        String[] parts = hhmm.trim().split(":");
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            if (parts.length > 2 || h < 0 || m < 0 || m > 59 || h * 60 + m > 24 * 60) throw new NumberFormatException();
            return h * 60 + m;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + hhmm.trim() + "\" is not a time like 06:00");
        }
    }

    /** Clock time {@code minute} (0 to 1440) on day {@code day} after the date of {@code ref}.
     *  Built from calendar fields, so "06:00" stays 06:00 across a daylight saving change. */
    private static long clockTime(long ref, int day, int minute, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(ref);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, day);
        c.set(Calendar.HOUR_OF_DAY, minute / 60); // 24 rolls over to 00:00 of the next day
        c.set(Calendar.MINUTE, minute % 60);
        return c.getTimeInMillis();
    }
}
