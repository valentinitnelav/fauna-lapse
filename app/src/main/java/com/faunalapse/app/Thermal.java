package com.faunalapse.app;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

/**
 * Heat readings for the records, next to the battery temperature: the chip sensors (ChipTemps)
 * and Android's own view (PowerManager). Android's thermal status (Android 10 and newer) says
 * whether the phone is slowing itself down to cool off: none, light, moderate, severe, critical,
 * emergency, shutdown. The headroom (Android 11 and newer) says how close it is: 0 = cool,
 * 1 = at "severe"; it can pass 1. Phones decide alone whether they report it (the Samsung M12 does,
 * the Redmi Note 9S not), so every value may be null. Only recorded: the heat pause stays on the
 * battery. Keys and words as in FaunaPulse, so both apps' files read alike.
 */
final class Thermal {

    /** Android may answer NaN when the headroom is asked for more than about once a second. */
    private static final long HEADROOM_CACHE_MS = 1000;
    private static long headroomAtMs = -HEADROOM_CACHE_MS;
    private static Double headroom;

    private Thermal() {
    }

    /** Adds cpu_temp_c, gpu_temp_c, thermal_status and thermal_headroom to a record; null where
     *  the phone gives nothing. */
    static void put(Context context, JSONObject r) {
        ChipTemps chip = ChipTemps.phone();
        SessionLog.put(r, "cpu_temp_c", orNull(chip.cpuC()));
        SessionLog.put(r, "gpu_temp_c", orNull(chip.gpuC()));
        SessionLog.put(r, "thermal_status", orNull(status(context)));
        SessionLog.put(r, "thermal_headroom", orNull(headroom(context)));
    }

    /** Android's thermal status in words, or null before Android 10. */
    static String status(Context context) {
        if (Build.VERSION.SDK_INT < 29) return null;
        return word(power(context).getCurrentThermalStatus());
    }

    /** Android's thermal headroom now (3 decimals), or null where the phone gives none. */
    static synchronized Double headroom(Context context) {
        if (Build.VERSION.SDK_INT < 30) return null;
        long now = SystemClock.elapsedRealtime();
        if (now - headroomAtMs < HEADROOM_CACHE_MS) return headroom;
        headroomAtMs = now;
        try {
            float h = power(context).getThermalHeadroom(0); // 0 s ahead: now
            headroom = Float.isNaN(h) ? null : Math.round(h * 1000) / 1000.0;
        } catch (RuntimeException e) {
            headroom = null;
        }
        return headroom;
    }

    /** From Android 10 on: a "thermal_status" record in {@code log} at the start (Android sends the
     *  status at once) and each time the status changes (the moment the phone starts or stops
     *  slowing down), with the heat readings of that moment.
     *  Returns what stopWatching needs, or null on older phones. */
    static Object watch(Context context, SessionLog log) {
        return Build.VERSION.SDK_INT >= 29 ? Watch.start(context, log) : null;
    }

    static void stopWatching(Context context, Object watch) {
        if (watch != null) Watch.stop(context, watch);
    }

    /** Apart, so that phones older than Android 10 never load the listener type (watch returns
     *  null there; the checks inside are for the Android code checks). */
    private static final class Watch {
        static Object start(final Context context, final SessionLog log) {
            if (Build.VERSION.SDK_INT < 29) return null;
            PowerManager.OnThermalStatusChangedListener listener = s -> {
                JSONObject r = SessionLog.record("thermal_status");
                SessionLog.put(r, "battery_temp_c", CaptureService.batteryTempC(context));
                put(context, r);
                SessionLog.put(r, "thermal_status", word(s)); // the new status itself
                log.write(r);
            };
            // Called on a system thread: writing one line is quick, and SessionLog is thread safe.
            power(context).addThermalStatusListener(Runnable::run, listener);
            return listener;
        }

        static void stop(Context context, Object listener) {
            if (Build.VERSION.SDK_INT < 29) return;
            power(context).removeThermalStatusListener((PowerManager.OnThermalStatusChangedListener) listener);
        }
    }

    /** PowerManager.THERMAL_STATUS_NONE (0) to THERMAL_STATUS_SHUTDOWN (6) in words. */
    private static String word(int status) {
        switch (status) {
            case 0: return "none";
            case 1: return "light";
            case 2: return "moderate";
            case 3: return "severe";
            case 4: return "critical";
            case 5: return "emergency";
            case 6: return "shutdown";
            default: return "unknown";
        }
    }

    private static PowerManager power(Context context) {
        return (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    }

    private static Object orNull(Object value) {
        return value == null ? JSONObject.NULL : value;
    }
}
