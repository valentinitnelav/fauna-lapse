package com.faunalapse.app;

import android.content.Context;
import android.os.BatteryManager;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Battery use of a session, for the record: the battery current every 10 s, kept apart for the
 * times with the camera open and closed, and the battery level at the start and the end. Off the
 * charger this tells what one hour of a schedule costs on this phone; on the charger the current
 * is mixed with the charging and says nothing about use. Reading the battery costs almost nothing,
 * and the session's wake lock keeps the processor awake anyway.
 */
final class BatteryMeter implements Runnable {

    static final long SAMPLE_MS = 10000;

    private final Context context;
    private final BatteryManager manager;
    private final long startMs = System.currentTimeMillis();
    private final CaptureService.Battery start;
    private volatile boolean cameraOpen;
    private final double[] sumMa = new double[2]; // [0] camera closed, [1] open
    private final int[] samples = new int[2];
    private boolean charger;
    private boolean noCurrent;

    BatteryMeter(Context context) {
        this.context = context;
        this.manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        start = CaptureService.Battery.read(context);
        charger = start.charging;
    }

    void cameraOpen(boolean open) {
        cameraOpen = open;
    }

    /** Samples until interrupted. */
    @Override
    public void run() {
        try {
            while (true) {
                sample();
                Thread.sleep(SAMPLE_MS);
            }
        } catch (InterruptedException ignored) {
            // the session ended
        }
    }

    private synchronized void sample() {
        if (CaptureService.Battery.read(context).charging) charger = true;
        Double ma = milliamperes(manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
        if (ma == null) {
            noCurrent = true;
            return;
        }
        int i = cameraOpen ? 1 : 0;
        sumMa[i] += ma;
        samples[i]++;
    }

    /** The current in mA (always positive), or null when the phone does not tell it. Android's
     *  documentation says microamperes, but some phones give milliamperes (Samsung M12). A phone
     *  that runs a session uses more than 10 mA, so larger numbers are microamperes. Off the
     *  charger the current can only flow out of the battery, so its sign (which differs between
     *  phones) is left out. */
    static Double milliamperes(int raw) {
        if (raw == 0 || raw == Integer.MIN_VALUE) return null;
        double a = Math.abs((double) raw);
        return a >= 10000 ? a / 1000 : a;
    }

    /** For the session_end record. */
    synchronized JSONObject toJson() {
        CaptureService.Battery end = CaptureService.Battery.read(context);
        double hours = hours();
        JSONObject j = new JSONObject();
        SessionLog.put(j, "start_percent", start.percent);
        SessionLog.put(j, "end_percent", end.percent);
        SessionLog.put(j, "start_mv", start.mv);
        SessionLog.put(j, "end_mv", end.mv);
        SessionLog.put(j, "charger_connected", charger || end.charging);
        if (!charger && !end.charging && hours > 0 && start.percent >= 0 && end.percent >= 0) {
            SessionLog.put(j, "percent_per_hour", Math.round((start.percent - end.percent) / hours * 10) / 10.0);
        }
        if (noCurrent && samples[0] + samples[1] == 0) {
            SessionLog.put(j, "current", "not reported by this phone");
        } else {
            SessionLog.put(j, "sample_s", SAMPLE_MS / 1000);
            SessionLog.put(j, "mean_ma_camera_open", mean(1));
            SessionLog.put(j, "mean_ma_camera_closed", mean(0));
            SessionLog.put(j, "samples_camera_open", samples[1]);
            SessionLog.put(j, "samples_camera_closed", samples[0]);
        }
        return j;
    }

    /** One line for the screen. */
    synchronized String words() {
        CaptureService.Battery end = CaptureService.Battery.read(context);
        if (charger || end.charging) return "Battery use not measured: the charger was connected.";
        StringBuilder s = new StringBuilder(String.format(Locale.US, "Battery: %d %% to %d %% in %.1f h",
                start.percent, end.percent, hours()));
        if (samples[1] > 0 && samples[0] > 0) {
            s.append(String.format(Locale.US, " (camera open %.0f mA, closed %.0f mA)",
                    sumMa[1] / samples[1], sumMa[0] / samples[0]));
        }
        return s.append('.').toString();
    }

    private double hours() {
        return (System.currentTimeMillis() - startMs) / 3600000.0;
    }

    private Object mean(int i) {
        return samples[i] == 0 ? JSONObject.NULL : Math.round(sumMa[i] / samples[i] * 10) / 10.0;
    }
}
