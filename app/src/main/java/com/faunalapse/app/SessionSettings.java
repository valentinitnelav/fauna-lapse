package com.faunalapse.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.CamcorderProfile;

import org.json.JSONObject;

import java.util.TimeZone;

/**
 * The session settings: shown on the screen, saved on the phone, and written into the session
 * record. The reasons for the defaults are in the screen texts (strings.xml).
 */
final class SessionSettings {

    static final int VIDEO_FPS = 15; // fixed for now; slower rates need time-lapse mode (later)

    boolean video = false;
    double stepS = 2;            // a 3 s step caught about 90 % of visits (Edwards et al. 2015)
    double burstS = 60;          // longer than a typical visit (about 11 s)
    double clipMin = 5;
    double breakMin = 4;         // camera off about 80 % of the time
    boolean daily = false;
    double runH = 2;
    String windows = "05:00-21:00"; // early morning and evening visits count (Toivonen and Humberg 2026)
    int days = 1;
    int squarePercent = 100;     // square side as a share of the short side of the photo
    double squareX = 0.5;        // square centre as shares of the photo width and height (RoiMath
    double squareY = 0.5;        // "photo"); placed on the aim screen, default the middle
    int savedSidePx = 1024;      // pixels on the insect (FaunaPulse default)
    String videoQuality = "1080p";
    double heatLimitC = 45;      // phones still work; most stop charging near 45 °C (owner, 2026-10-03)
    String phoneName = "";       // optional, added to the folder name: files from several phones do not mix

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
        s.runH = p.getFloat("run_h", (float) s.runH);
        s.windows = p.getString("windows", s.windows);
        s.days = p.getInt("days", s.days);
        s.squarePercent = p.getInt("square_percent", s.squarePercent);
        s.squareX = p.getFloat("square_x", (float) s.squareX);
        s.squareY = p.getFloat("square_y", (float) s.squareY);
        s.savedSidePx = p.getInt("saved_side_px", s.savedSidePx);
        s.videoQuality = p.getString("video_quality", s.videoQuality);
        s.heatLimitC = p.getFloat("heat_limit_c", (float) s.heatLimitC);
        s.phoneName = p.getString("phone_name", s.phoneName);
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
                .putFloat("run_h", (float) runH)
                .putString("windows", windows)
                .putInt("days", days)
                .putInt("square_percent", squarePercent)
                .putFloat("square_x", (float) squareX)
                .putFloat("square_y", (float) squareY)
                .putInt("saved_side_px", savedSidePx)
                .putString("video_quality", videoQuality)
                .putFloat("heat_limit_c", (float) heatLimitC)
                .putString("phone_name", phoneName)
                .apply();
    }

    /** A plain-language problem with the values, or null when they are usable. */
    String problem() {
        if (!video && (stepS < 0.2 || stepS > 3600)) return "Photo step: use 0.2 to 3600 s.";
        if (!video && (burstS < stepS || burstS > 86400)) return "Burst length: use the photo step to 86400 s.";
        if (video && (clipMin < 0.1 || clipMin > 1440)) return "Clip length: use 0.1 to 1440 min.";
        if (breakMin < 0 || breakMin > 1440) return "Break: use 0 to 1440 min.";
        if (!daily && (runH <= 0 || runH > 720)) return "Run length: use more than 0 and up to 720 h.";
        if (daily && (days < 1 || days > 365)) return "Days: use 1 to 365.";
        if (squarePercent < 10 || squarePercent > 100) return "Square side: use 10 to 100 %.";
        if (squareX < 0 || squareX > 1 || squareY < 0 || squareY > 1) return "Square position: use 0 to 1.";
        if (savedSidePx < 480 || savedSidePx > 1280) return "Saved square side: use 480 to 1280 px.";
        if (heatLimitC < 35 || heatLimitC > 50) return "Heat pause: use 35 to 50 °C.";
        if (!validPhoneName(phoneName)) return "Phone name: use up to 20 letters (a to z), digits, - or _.";
        if (daily) {
            try {
                CapturePlan.parseWindows(windows);
            } catch (IllegalArgumentException e) {
                return "Daily windows: " + e.getMessage() + ".";
            }
        }
        return null;
    }

    /** Letters a to z, digits, - and _ only: safe in folder names on every computer. */
    static boolean validPhoneName(String name) {
        return name.matches("[A-Za-z0-9_-]{0,20}");
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
                : CapturePlan.run(sessionStart, Math.round(round(runH) * 3600000), burstMs(), breakMs());
    }

    int videoQualityConstant() {
        if ("480p".equals(videoQuality)) return CamcorderProfile.QUALITY_480P;
        if ("720p".equals(videoQuality)) return CamcorderProfile.QUALITY_720P;
        return CamcorderProfile.QUALITY_1080P;
    }

    /** Saved values are floats (0.06 comes back as 0.0599999986): round them for the record. */
    private static double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

    /** All settings, for the session record (only those that apply to the chosen mode). */
    JSONObject toJson() {
        JSONObject j = new JSONObject();
        if (!phoneName.isEmpty()) SessionLog.put(j, "phone_name", phoneName);
        SessionLog.put(j, "mode", video ? "video" : "photo");
        if (video) {
            SessionLog.put(j, "clip_min", round(clipMin));
            SessionLog.put(j, "video_quality", videoQuality);
            SessionLog.put(j, "video_fps", VIDEO_FPS);
        } else {
            SessionLog.put(j, "photo_step_s", round(stepS));
            SessionLog.put(j, "burst_s", round(burstS));
            SessionLog.put(j, "square_percent_of_short_side", squarePercent);
            SessionLog.put(j, "square_centre_x", round(squareX)); // shares of the full photo, before turning
            SessionLog.put(j, "square_centre_y", round(squareY));
            SessionLog.put(j, "saved_side_px", savedSidePx);
            SessionLog.put(j, "saved_jpeg_quality", PhotoSaver.JPEG_QUALITY);
        }
        SessionLog.put(j, "break_min", round(breakMin));
        SessionLog.put(j, "heat_pause_c", round(heatLimitC));
        SessionLog.put(j, "heat_resume_c", round(heatLimitC - Guards.RESUME_GAP_C));
        if (daily) {
            SessionLog.put(j, "daily_windows", windows);
            SessionLog.put(j, "days", days);
        } else {
            SessionLog.put(j, "run_h", round(runH));
        }
        return j;
    }
}
