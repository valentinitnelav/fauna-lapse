package com.faunalapse.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;
import java.util.TimeZone;

/**
 * The camera and schedule settings: shown on the screen, saved on the phone, and written into the
 * session record. The reasons for the defaults are in the screen texts (strings.xml). Where and
 * what is recorded (site, plant, location) is in FieldInfo.
 */
final class SessionSettings {

    boolean video = false;
    double stepS = 2;            // a 3 s step caught about 90 % of visits (Edwards et al. 2015)
    double burstS = 60;          // longer than a typical visit (about 11 s)
    double clipMin = 5;
    double breakMin = 5;         // camera off about 83 % of the time with photo bursts, half with clips (owner)
    boolean daily = false;
    int runMin = 60;             // one hour, a usual observation period (owner, 2026-10-04)
    String windows = "05:00-21:00"; // early morning and evening visits count (Toivonen and Humberg 2026)
    int days = 1;
    int squarePercent = 100;     // square side as a share of the short side of the photo
    double squareX = 0.5;        // square centre as shares of the photo width and height (RoiMath
    double squareY = 0.5;        // "photo"); placed on the aim screen, default the middle
    boolean focusFixed = true;   // autofocus may pick another flower in a dense patch (owner, 2026-10-04)
    double focusDiopters = 5;    // 1 / distance in m: 20 cm, a usual distance from phone to flower (owner)
    int savedSidePx = 1280;      // photos and video; input size of many insect detectors (owner, 2026-10-04)
    int videoFps = 5;            // enough to track insects on the PC, with smaller files (owner, 2026-10-04)
    double heatLimitC = 45;      // phones still work; most stop charging near 45 °C (owner, 2026-10-03)

    /** Saved square side limits: the smallest common classifier input; a sanity bound (squares are
     *  never enlarged beyond their real pixels anyway). */
    static final int MIN_SIDE_PX = 224;
    static final int MAX_SIDE_PX = 4096;

    private static final String PREFS = "session_settings";

    static SessionSettings load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SessionSettings s = new SessionSettings();
        s.video = p.getBoolean("video", s.video);
        s.stepS = p.getFloat("step_s", (float) s.stepS);
        s.burstS = p.getFloat("burst_s", (float) s.burstS);
        s.clipMin = p.getFloat("clip_min", (float) s.clipMin);
        s.breakMin = p.getFloat("break_min", (float) s.breakMin);
        s.daily = p.getBoolean("daily", s.daily);
        s.runMin = p.getInt("run_min", s.runMin);
        s.windows = p.getString("windows", s.windows);
        s.days = p.getInt("days", s.days);
        s.squarePercent = p.getInt("square_percent", s.squarePercent);
        s.squareX = p.getFloat("square_x", (float) s.squareX);
        s.squareY = p.getFloat("square_y", (float) s.squareY);
        s.focusFixed = p.getBoolean("focus_fixed", s.focusFixed);
        s.focusDiopters = p.getFloat("focus_diopters", (float) s.focusDiopters);
        s.savedSidePx = p.getInt("saved_side_px", s.savedSidePx);
        s.videoFps = p.getInt("video_fps", s.videoFps);
        s.heatLimitC = p.getFloat("heat_limit_c", (float) s.heatLimitC);
        return s;
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("video", video)
                .putFloat("step_s", (float) stepS)
                .putFloat("burst_s", (float) burstS)
                .putFloat("clip_min", (float) clipMin)
                .putFloat("break_min", (float) breakMin)
                .putBoolean("daily", daily)
                .putInt("run_min", runMin)
                .remove("run_h") // the run length in hours, saved by older versions
                .putString("windows", windows)
                .putInt("days", days)
                .putInt("square_percent", squarePercent)
                .putFloat("square_x", (float) squareX)
                .putFloat("square_y", (float) squareY)
                .putBoolean("focus_fixed", focusFixed)
                .putFloat("focus_diopters", (float) focusDiopters)
                .putInt("saved_side_px", savedSidePx)
                .putInt("video_fps", videoFps)
                .putFloat("heat_limit_c", (float) heatLimitC)
                .apply();
    }

    /** A plain-language problem with the values, or null when they are usable. */
    String problem() {
        if (!video && (stepS < 0.2 || stepS > 3600)) return "Photo step: use 0.2 to 3600 s.";
        if (!video && (burstS < stepS || burstS > 86400)) return "Burst length: use the photo step to 86400 s.";
        if (video && (clipMin < 0.1 || clipMin > 1440)) return "Clip length: use 0.1 to 1440 min.";
        if (video && videoFps != 5 && videoFps != 10 && videoFps != 15) return "Video frames per s: use 5, 10 or 15.";
        if (breakMin < 0 || breakMin > 1440) return "Break: use 0 to 1440 min.";
        if (!daily && (runMin < 1 || runMin > 720 * 60)) return "Run length: use 1 min to 720 h.";
        if (daily && (days < 1 || days > 365)) return "Days: use 1 to 365.";
        if (squarePercent < 10 || squarePercent > 100) return "Square side: use 10 to 100 %.";
        if (squareX < 0 || squareX > 1 || squareY < 0 || squareY > 1) return "Square position: use 0 to 1.";
        if (savedSidePx < MIN_SIDE_PX || savedSidePx > MAX_SIDE_PX || savedSidePx % RoiMath.SIDE_STEP != 0) {
            return sideProblem(savedSidePx);
        }
        if (focusFixed && (focusDiopters < 0 || focusDiopters > 50)) return "Fixed focus: set it again on the aim screen.";
        if (heatLimitC < 35 || heatLimitC > 50) return "Heat pause: use 35 to 50 °C.";
        if (daily) {
            try {
                CapturePlan.parseWindows(windows);
            } catch (IllegalArgumentException e) {
                return "Daily windows: " + e.getMessage() + ".";
            }
        }
        return null;
    }

    /** What to write instead of a saved side that is not a multiple of 32 in the limits: the two
     *  nearest that are. */
    static String sideProblem(int px) {
        int step = RoiMath.SIDE_STEP;
        int below = Math.max(MIN_SIDE_PX, Math.min(MAX_SIDE_PX - step, Math.floorDiv(px, step) * step));
        return String.format(Locale.US, "Saved square side: use a multiple of %d from %d to %d px, for example"
                + " %d or %d.", step, MIN_SIDE_PX, MAX_SIDE_PX, below, below + step);
    }

    /** A lens distance in words ({@code diopters} = 1 / distance in m), for the screen and the record. */
    static String distanceWords(double diopters) {
        if (diopters < 0.05) return "far away";
        double cm = 100 / diopters;
        return cm < 100 ? String.format(Locale.US, "about %d cm", Math.round(cm))
                : String.format(Locale.US, "about %.1f m", cm / 100);
    }

    long stepMs() {
        return Math.round(round(stepS) * 1000);
    }

    long burstMs() {
        return video ? Math.round(round(clipMin) * 60000) : Math.round(round(burstS) * 1000);
    }

    long breakMs() {
        return Math.round(round(breakMin) * 60000);
    }

    CapturePlan plan(long sessionStart) {
        return daily
                ? CapturePlan.daily(sessionStart, CapturePlan.parseWindows(windows), days, TimeZone.getDefault(),
                        burstMs(), breakMs())
                : CapturePlan.run(sessionStart, runMin * 60000L, burstMs(), breakMs());
    }

    /** Saved values are floats (0.06 comes back as 0.0599999986): round them for the record. */
    private static double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

    /** All settings, for the session record. Every key in every record, null where it does not
     *  apply (for example the photo step in a video session), so tables built from many sessions
     *  have the same columns and no value that was not used. */
    JSONObject toJson() {
        JSONObject j = new JSONObject();
        SessionLog.put(j, "mode", video ? "video" : "photo");
        SessionLog.put(j, "photo_step_s", only(!video, round(stepS)));
        SessionLog.put(j, "burst_s", only(!video, round(burstS)));
        SessionLog.put(j, "saved_jpeg_quality", only(!video, PhotoSaver.JPEG_QUALITY));
        SessionLog.put(j, "clip_min", only(video, round(clipMin)));
        SessionLog.put(j, "video_fps", only(video, videoFps));
        SessionLog.put(j, "break_min", round(breakMin));
        SessionLog.put(j, "square_percent_of_short_side", squarePercent);
        SessionLog.put(j, "square_centre_x", round(squareX)); // shares of the full photo, before turning
        SessionLog.put(j, "square_centre_y", round(squareY));
        SessionLog.put(j, "saved_side_px", savedSidePx);
        SessionLog.put(j, "focus", focusFixed ? "fixed" : "auto, once per " + (video ? "clip" : "burst"));
        SessionLog.put(j, "focus_diopters", only(focusFixed, round(focusDiopters)));
        SessionLog.put(j, "focus_distance", only(focusFixed, distanceWords(focusDiopters)));
        SessionLog.put(j, "heat_pause_c", round(heatLimitC));
        SessionLog.put(j, "heat_resume_c", round(heatLimitC - Guards.RESUME_GAP_C));
        SessionLog.put(j, "schedule", daily ? "daily windows" : "run from start");
        SessionLog.put(j, "run_min", only(!daily, runMin));
        SessionLog.put(j, "daily_windows", only(daily, windows));
        SessionLog.put(j, "days", only(daily, days));
        return j;
    }

    private static Object only(boolean applies, Object value) {
        return applies ? value : JSONObject.NULL;
    }
}
