package com.faunalapse.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

import org.junit.Test;

public class CapturePlanTest {

    private static final long S = 1000;
    private static final long MIN = 60 * S;
    private static final long H = 60 * MIN;
    private static final TimeZone BERLIN = TimeZone.getTimeZone("Europe/Berlin");

    /** "2026-10-02 06:00:00" in Berlin time, as ms since 1970. */
    private static long at(String text) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        f.setTimeZone(BERLIN);
        try {
            return f.parse(text).getTime();
        } catch (ParseException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void runGridStartsAtSessionStartAndCutsTheLastBurst() {
        long t0 = at("2026-10-02 10:00:00");
        // 60 s bursts, 4 min break, for 11 minutes: bursts at 0, 5 and 10 min; the last one is cut at 11 min.
        CapturePlan plan = CapturePlan.run(t0, 11 * MIN, 60 * S, 4 * MIN);
        assertEquals(new CapturePlan.Burst(t0, t0 + MIN), plan.burstAtOrAfter(t0));
        assertEquals(new CapturePlan.Burst(t0, t0 + MIN), plan.burstAtOrAfter(t0 + 30 * S));
        assertEquals(new CapturePlan.Burst(t0 + 5 * MIN, t0 + 6 * MIN), plan.burstAtOrAfter(t0 + MIN));
        assertEquals(new CapturePlan.Burst(t0 + 10 * MIN, t0 + 11 * MIN), plan.burstAtOrAfter(t0 + 7 * MIN));
        assertNull(plan.burstAtOrAfter(t0 + 11 * MIN));
        assertEquals(t0 + 11 * MIN, plan.end());

        CapturePlan cut = CapturePlan.run(t0, 10 * MIN + 30 * S, 60 * S, 4 * MIN);
        assertEquals(new CapturePlan.Burst(t0 + 10 * MIN, t0 + 10 * MIN + 30 * S), cut.burstAtOrAfter(t0 + 7 * MIN));
    }

    @Test
    public void noBreakMeansBurstsBackToBack() {
        long t0 = at("2026-10-02 10:00:00");
        CapturePlan plan = CapturePlan.run(t0, H, 5 * MIN, 0); // e.g. 5 min video clips without a break
        assertEquals(new CapturePlan.Burst(t0 + 5 * MIN, t0 + 10 * MIN), plan.burstAtOrAfter(t0 + 5 * MIN));
        assertEquals(new CapturePlan.Burst(t0 + 55 * MIN, t0 + H), plan.burstAtOrAfter(t0 + 59 * MIN));
    }

    @Test
    public void dailyWindowsKeepTheSameClockTimesEveryDay() {
        long start = at("2026-10-02 05:30:00");
        int[][] windows = CapturePlan.parseWindows("06:00-08:00, 17:00-18:00");
        CapturePlan plan = CapturePlan.daily(start, windows, 2, BERLIN, MIN, 9 * MIN);
        // Before the first window: the first burst is at 06:00.
        assertEquals(new CapturePlan.Burst(at("2026-10-02 06:00:00"), at("2026-10-02 06:01:00")),
                plan.burstAtOrAfter(start));
        // After the morning window: the evening window starts at 17:00.
        assertEquals(at("2026-10-02 17:00:00"), plan.burstAtOrAfter(at("2026-10-02 07:59:00")).start);
        // Day 2 has the same clock times.
        assertEquals(at("2026-10-03 06:10:00"), plan.burstAtOrAfter(at("2026-10-03 06:02:00")).start);
        assertEquals(at("2026-10-03 17:50:00"), plan.burstAtOrAfter(at("2026-10-03 17:45:00")).start);
        // Over after the last window of day 2.
        assertNull(plan.burstAtOrAfter(at("2026-10-03 18:00:00")));
        assertEquals(at("2026-10-03 18:00:00"), plan.end());
    }

    @Test
    public void sessionStartedInsideAWindowKeepsTheWindowGrid() {
        long start = at("2026-10-02 06:12:00");
        CapturePlan plan = CapturePlan.daily(start, CapturePlan.parseWindows("06:00-08:00"), 1, BERLIN, MIN, 4 * MIN);
        assertEquals(at("2026-10-02 06:15:00"), plan.burstAtOrAfter(start).start);
        // A window already over at the start is left out: the next day is used.
        CapturePlan late = CapturePlan.daily(at("2026-10-02 09:00:00"), CapturePlan.parseWindows("06:00-08:00"),
                2, BERLIN, MIN, 4 * MIN);
        assertEquals(at("2026-10-03 06:00:00"), late.burstAtOrAfter(at("2026-10-02 09:00:00")).start);
    }

    @Test
    public void windowsStayOnClockTimeAcrossDaylightSavingChange() {
        // Berlin changes from summer to winter time on 2026-10-25 at 03:00 (clock goes back to 02:00).
        long start = at("2026-10-24 12:00:00");
        CapturePlan plan = CapturePlan.daily(start, CapturePlan.parseWindows("06:00-07:00"), 3, BERLIN, MIN, 0);
        assertEquals(at("2026-10-25 06:00:00"), plan.burstAtOrAfter(at("2026-10-25 00:00:00")).start);
        assertEquals(at("2026-10-26 06:00:00"), plan.burstAtOrAfter(at("2026-10-25 08:00:00")).start);
    }

    @Test
    public void windowEndingAtMidnight() {
        long start = at("2026-10-02 12:00:00");
        CapturePlan plan = CapturePlan.daily(start, CapturePlan.parseWindows("23:00-24:00, 00:00-01:00"), 2,
                BERLIN, 30 * MIN, 0);
        assertEquals(new CapturePlan.Burst(at("2026-10-02 23:30:00"), at("2026-10-03 00:00:00")),
                plan.burstAtOrAfter(at("2026-10-02 23:40:00")));
        assertEquals(at("2026-10-03 00:00:00"), plan.burstAtOrAfter(at("2026-10-03 00:00:00")).start);
    }

    @Test
    public void photoTimesFollowTheStepFromTheBurstStart() {
        CapturePlan.Burst b = new CapturePlan.Burst(10 * S, 20 * S); // 10 s burst
        assertEquals(10 * S, CapturePlan.nextPhotoTime(b, 2 * S, 0));
        assertEquals(12 * S, CapturePlan.nextPhotoTime(b, 2 * S, 10 * S + 1));
        assertEquals(12 * S, CapturePlan.nextPhotoTime(b, 2 * S, 12 * S));
        assertEquals(18 * S, CapturePlan.nextPhotoTime(b, 2 * S, 17 * S));
        assertEquals(-1, CapturePlan.nextPhotoTime(b, 2 * S, 18 * S + 1)); // 20 s is the burst end
        // A slow photo (1.2 s at a 1 s step) skips one grid time.
        assertEquals(12 * S, CapturePlan.nextPhotoTime(b, S, 11 * S + 200));
    }

    @Test
    public void parseWindowsSortsAndChecks() {
        assertArrayEquals(new int[][] {{6 * 60, 8 * 60}, {17 * 60, 20 * 60}},
                CapturePlan.parseWindows(" 17:00-20:00 ,06:00 - 08:00"));
        assertArrayEquals(new int[][] {{0, 24 * 60}}, CapturePlan.parseWindows("0:00-24:00"));
        for (String bad : new String[] {"", "06:00", "08:00-06:00", "06:00-08:00, 07:00-09:00", "6h-8h",
                "25:00-26:00", "06:60-07:00"}) {
            try {
                CapturePlan.parseWindows(bad);
                fail("accepted \"" + bad + "\"");
            } catch (IllegalArgumentException expected) {
                // the message is shown to the user
            }
        }
    }
}
