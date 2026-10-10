package com.faunalapse.app;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import org.json.JSONObject;

/**
 * Phone settings that matter when a phone is only a camera trap: the clock, radios off (airplane
 * mode), no battery limits for FaunaLapse and no Stay awake. Apps may only read them; the main screen opens
 * the settings pages and the session record keeps them. Read on the main thread: each value is a
 * quick lookup.
 */
final class PhoneCheck {

    /** 2026-10-01 UTC, before this app version was written. A phone without SIM or network often
     *  loses its time with an empty battery and starts again at 1970 or its factory date. */
    private static final long CLOCK_MIN_MS = 1790812800000L;

    final boolean clockWrong;           // the clock is before CLOCK_MIN_MS or before the app's last install

    final boolean airplaneMode;
    final boolean wifiOn;
    final boolean bluetoothOn;
    final boolean locationOn;
    final boolean batteryLimited;       // Android's battery optimisation applies to FaunaLapse
    final boolean backgroundRestricted; // the user restricted FaunaLapse in the background (Android 9+)
    final boolean batterySaver;
    final boolean stayAwake;            // Developer options: the screen never sleeps by itself while charging

    private PhoneCheck(Context c) {
        ContentResolver r = c.getContentResolver();
        clockWrong = System.currentTimeMillis() < Math.max(CLOCK_MIN_MS, installedMs(c));
        airplaneMode = Settings.Global.getInt(r, Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        int wifi = Settings.Global.getInt(r, Settings.Global.WIFI_ON, 0);
        wifiOn = wifi == 1 || wifi == 2; // 2: switched on again in airplane mode; 3: off by airplane mode
        int bluetooth = Settings.Global.getInt(r, Settings.Global.BLUETOOTH_ON, 0);
        bluetoothOn = bluetooth == 1 || (bluetooth == 2 && !airplaneMode); // 2: off while in airplane mode
        locationOn = locationOn(c);
        stayAwake = Settings.Global.getInt(r, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != 0;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        batteryLimited = !pm.isIgnoringBatteryOptimizations(c.getPackageName());
        batterySaver = pm.isPowerSaveMode();
        backgroundRestricted = Build.VERSION.SDK_INT >= 28
                && ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).isBackgroundRestricted();
    }

    static PhoneCheck read(Context c) {
        return new PhoneCheck(c);
    }

    /** When the app was last installed or updated, by the phone clock then; 0 if unknown. */
    private static long installedMs(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    @SuppressWarnings("deprecation") // LOCATION_MODE is the only way before Android 9
    static boolean locationOn(Context c) {
        if (Build.VERSION.SDK_INT >= 28) {
            return ((LocationManager) c.getSystemService(Context.LOCATION_SERVICE)).isLocationEnabled();
        }
        return Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF;
    }

    /** True when nothing needs the user's attention before a field session. */
    boolean ready() {
        return !clockWrong && airplaneMode && !wifiOn && !bluetoothOn && !locationOn && !stayAwake && !batteryLimited
                && !backgroundRestricted;
    }

    /** For the session record. */
    JSONObject toJson() {
        JSONObject j = new JSONObject();
        SessionLog.put(j, "clock_looks_wrong", clockWrong);
        SessionLog.put(j, "airplane_mode", airplaneMode);
        SessionLog.put(j, "wifi_on", wifiOn);
        SessionLog.put(j, "bluetooth_on", bluetoothOn);
        SessionLog.put(j, "location_on", locationOn);
        SessionLog.put(j, "stay_awake_while_charging", stayAwake);
        SessionLog.put(j, "battery_optimisation_on", batteryLimited);
        SessionLog.put(j, "background_restricted", backgroundRestricted);
        SessionLog.put(j, "battery_saver_on", batterySaver);
        return j;
    }
}
