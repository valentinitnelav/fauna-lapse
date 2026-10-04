package com.faunalapse.app;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.location.LocationManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import org.json.JSONObject;

/**
 * Phone settings that matter when a phone is only a camera trap: radios off (airplane mode) and no
 * battery limits for FaunaLapse. Apps may only read them; the main screen opens the settings pages
 * and the session record keeps them. Read on the main thread: each value is a quick lookup.
 */
final class PhoneCheck {

    final boolean airplaneMode;
    final boolean wifiOn;
    final boolean bluetoothOn;
    final boolean locationOn;
    final boolean batteryLimited;       // Android's battery optimisation applies to FaunaLapse
    final boolean backgroundRestricted; // the user restricted FaunaLapse in the background (Android 9+)
    final boolean batterySaver;

    private PhoneCheck(Context c) {
        ContentResolver r = c.getContentResolver();
        airplaneMode = Settings.Global.getInt(r, Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        int wifi = Settings.Global.getInt(r, Settings.Global.WIFI_ON, 0);
        wifiOn = wifi == 1 || wifi == 2; // 2: switched on again in airplane mode; 3: off by airplane mode
        int bluetooth = Settings.Global.getInt(r, Settings.Global.BLUETOOTH_ON, 0);
        bluetoothOn = bluetooth == 1 || (bluetooth == 2 && !airplaneMode); // 2: off while in airplane mode
        locationOn = locationOn(c);
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        batteryLimited = !pm.isIgnoringBatteryOptimizations(c.getPackageName());
        batterySaver = pm.isPowerSaveMode();
        backgroundRestricted = Build.VERSION.SDK_INT >= 28
                && ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).isBackgroundRestricted();
    }

    static PhoneCheck read(Context c) {
        return new PhoneCheck(c);
    }

    @SuppressWarnings("deprecation") // LOCATION_MODE is the only way before Android 9
    private static boolean locationOn(Context c) {
        if (Build.VERSION.SDK_INT >= 28) {
            return ((LocationManager) c.getSystemService(Context.LOCATION_SERVICE)).isLocationEnabled();
        }
        return Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF;
    }

    /** True when nothing needs the user's attention before a field session. */
    boolean ready() {
        return airplaneMode && !wifiOn && !bluetoothOn && !locationOn && !batteryLimited && !backgroundRestricted;
    }

    /** For the session record. */
    JSONObject toJson() {
        JSONObject j = new JSONObject();
        SessionLog.put(j, "airplane_mode", airplaneMode);
        SessionLog.put(j, "wifi_on", wifiOn);
        SessionLog.put(j, "bluetooth_on", bluetoothOn);
        SessionLog.put(j, "location_on", locationOn);
        SessionLog.put(j, "battery_optimisation_on", batteryLimited);
        SessionLog.put(j, "background_restricted", backgroundRestricted);
        SessionLog.put(j, "battery_saver_on", batterySaver);
        return j;
    }
}
