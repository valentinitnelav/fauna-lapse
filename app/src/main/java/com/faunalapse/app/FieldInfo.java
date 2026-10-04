package com.faunalapse.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Random;

/**
 * Field metadata: which phone recorded what, and where. The user changes these at each site or
 * plant, so they are kept apart from the camera settings (SessionSettings). Saved on the phone and
 * written into each session record; the phone name also starts the session folder name.
 */
final class FieldInfo {

    /** Longest text in a field: enough for a site or a plant description. */
    static final int MAX_TEXT = 100;

    String phoneName = "";
    String site = "";
    String transect = "";
    String plant = "";
    String sampleId = "";
    String sessionId = "";
    /** The last GPS position saved with "Get location"; null when there is none. */
    Location location;

    private static final String PREFS = "field_info";

    static FieldInfo load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        FieldInfo f = new FieldInfo();
        f.phoneName = p.getString("phone_name", "");
        if (f.phoneName.isEmpty()) f.phoneName = defaultPhoneName(context);
        f.site = p.getString("site", "");
        f.transect = p.getString("transect", "");
        f.plant = p.getString("plant", "");
        f.sampleId = p.getString("sample_id", "");
        f.sessionId = p.getString("session_id", "");
        if (p.contains("latitude")) {
            Location l = new Location("gps");
            // Strings: a float would round the position to about 1 m.
            l.setLatitude(Double.parseDouble(p.getString("latitude", "0")));
            l.setLongitude(Double.parseDouble(p.getString("longitude", "0")));
            l.setAccuracy(p.getFloat("accuracy_m", 0));
            l.setTime(p.getLong("fix_time", 0));
            f.location = l;
        }
        return f;
    }

    void save(Context context) {
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("phone_name", phoneName)
                .putString("site", site)
                .putString("transect", transect)
                .putString("plant", plant)
                .putString("sample_id", sampleId)
                .putString("session_id", sessionId);
        if (location == null) {
            e.remove("latitude").remove("longitude").remove("accuracy_m").remove("fix_time");
        } else {
            e.putString("latitude", String.valueOf(location.getLatitude()))
                    .putString("longitude", String.valueOf(location.getLongitude()))
                    .putFloat("accuracy_m", location.getAccuracy())
                    .putLong("fix_time", location.getTime());
        }
        e.apply();
    }

    /** A plain-language problem with the values, or null when they are usable. */
    String problem() {
        if (!validPhoneName(phoneName)) return "Phone name: use 1 to 20 letters (a to z), digits, - or _.";
        return null;
    }

    /** Letters a to z, digits, - and _ only: safe in folder names on every computer. */
    static boolean validPhoneName(String name) {
        return name.matches("[A-Za-z0-9_-]{1,20}");
    }

    /** The phone model and 4 characters of the phone's ID for this app, for example SM-M127F-3A9C:
     *  phones of the same model differ, and the user may change it. */
    static String defaultPhoneName(Context context) {
        String model = Build.MODEL.replaceAll("[^A-Za-z0-9-]+", "-").replaceAll("^-+|-+$", "");
        if (model.length() > 15) model = model.substring(0, 15);
        return (model.isEmpty() ? "Phone" : model) + "-" + phoneId(context).substring(0, 4);
    }

    /** 8 hex characters of Android's ID for this app on this phone (it stays the same when the app
     *  is installed again; a factory reset changes it). It tells the phones of a team apart in the
     *  session records. HardwareIds: FaunaLapse has no network access, so the ID never leaves the
     *  phone except in the user's own files. */
    @SuppressLint("HardwareIds")
    static String phoneId(Context context) {
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (id == null || !id.matches("[0-9a-fA-F]{8,}")) {
            // Rare (some old or modified phones): a random ID, kept for this installation.
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            id = p.getString("random_id", null);
            if (id == null) {
                id = String.format(Locale.US, "%08x", new Random().nextInt());
                p.edit().putString("random_id", id).apply();
            }
        }
        return id.substring(0, 8).toUpperCase(Locale.US);
    }

    /** "48.858370, 2.294481": WGS 84 degrees (as GPS gives them), 6 decimals (about 0.1 m). */
    static String coordinates(Location l) {
        return String.format(Locale.US, "%.6f, %.6f", l.getLatitude(), l.getLongitude());
    }

    /** For the session record: every key always present (empty text, or null without a
     *  location), so tables built from many records have the same columns. */
    JSONObject toJson(Context context) {
        JSONObject j = new JSONObject();
        SessionLog.put(j, "phone_name", phoneName);
        SessionLog.put(j, "phone_id", phoneId(context));
        SessionLog.put(j, "site", site);
        SessionLog.put(j, "transect", transect);
        SessionLog.put(j, "plant", plant);
        SessionLog.put(j, "sample_id", sampleId);
        SessionLog.put(j, "session_id", sessionId);
        if (location == null) {
            SessionLog.put(j, "location", JSONObject.NULL);
        } else {
            JSONObject l = new JSONObject();
            SessionLog.put(l, "latitude", Math.round(location.getLatitude() * 1e6) / 1e6);
            SessionLog.put(l, "longitude", Math.round(location.getLongitude() * 1e6) / 1e6);
            SessionLog.put(l, "datum", "WGS 84");
            SessionLog.put(l, "accuracy_m", Math.round(location.getAccuracy() * 10) / 10.0);
            SessionLog.put(l, "fix_time", SessionLog.isoTime(location.getTime()));
            SessionLog.put(j, "location", l);
        }
        return j;
    }
}
