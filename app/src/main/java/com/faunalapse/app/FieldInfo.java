package com.faunalapse.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Field metadata: which phone recorded what, and where. The user changes these at each site or
 * plant, so they are kept apart from the camera settings (SessionSettings). Saved on the phone and
 * written into each session record. The phone model and ID come from Android; with the start time
 * they make each session folder name unique.
 */
final class FieldInfo {

    /** Longest text in a field: enough for a site or a plant description. */
    static final int MAX_TEXT = 100;
    /** Custom fields: the longest name, and the longest text value (a short note; longer notes
     *  belong in the field notebook). */
    static final int MAX_NAME = 40;
    static final int MAX_CUSTOM_TEXT = 200;
    /** Highest accuracy that may be typed, in m: a position from a map is rarely worse. */
    static final float MAX_ACCURACY_M = 10000;

    String site = "";
    String transect = "";
    String plant = "";
    String sampleId = "";
    String sessionId = "";
    /** The position: from "Get location" (provider GPS, with its accuracy and time) or typed by
     *  hand from another device (provider TYPED, accuracy only if given); null when there is none. */
    Location location;
    /** Fields the user added (for example Observer or Weather), in the order added. */
    List<Custom> custom = new ArrayList<>();

    /** A field the user added: its name, type (TEXT, NUMBER, DATE or TIME) and value ("" = empty).
     *  Dates and times come from Android's pickers: 2026-10-05 and 14:30. */
    static final class Custom {
        static final String TEXT = "text";
        static final String NUMBER = "number";
        static final String DATE = "date";
        static final String TIME = "time";

        final String name;
        final String type;
        final String value;

        Custom(String name, String type, String value) {
            this.name = name;
            this.type = type;
            this.value = value;
        }

        /** What is wrong with the value, or null. */
        String problem() {
            if (value.isEmpty()) return null;
            if (NUMBER.equals(type) && number(value) == null) return name + ": write a number.";
            if (DATE.equals(type) && !value.matches("\\d{4}-\\d{2}-\\d{2}")) return name + ": choose a date.";
            if (TIME.equals(type) && !value.matches("\\d{2}:\\d{2}")) return name + ": choose a time.";
            if (value.length() > MAX_CUSTOM_TEXT) return name + ": use at most " + MAX_CUSTOM_TEXT + " characters.";
            return null;
        }

        /** For the record: a number for NUMBER, else the text; null when empty. */
        Object jsonValue() {
            if (value.isEmpty()) return JSONObject.NULL;
            return NUMBER.equals(type) ? number(value) : value;
        }

        /** "21,5" or "21.5": the value as a finite number, or null. */
        static Double number(String text) {
            try {
                double d = Double.parseDouble(text.trim().replace(',', '.'));
                return Double.isInfinite(d) || Double.isNaN(d) ? null : d;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    static final String GPS = "gps";
    static final String TYPED = "typed";
    private static final String PREFS = "field_info";

    static FieldInfo load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        FieldInfo f = new FieldInfo();
        f.site = p.getString("site", "");
        f.transect = p.getString("transect", "");
        f.plant = p.getString("plant", "");
        f.sampleId = p.getString("sample_id", "");
        f.sessionId = p.getString("session_id", "");
        try {
            JSONArray a = new JSONArray(p.getString("custom_fields", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.getJSONObject(i);
                f.custom.add(new Custom(c.getString("name"), c.getString("type"), c.optString("value")));
            }
        } catch (JSONException ignored) {
            // never written that way; the fields can be added again
        }
        if (p.contains("latitude")) {
            // Strings: a float would round the position to about 1 m.
            double lat = Double.parseDouble(p.getString("latitude", "0"));
            double lon = Double.parseDouble(p.getString("longitude", "0"));
            Float accuracy = p.contains("accuracy_m") ? p.getFloat("accuracy_m", 0) : null;
            if (TYPED.equals(p.getString("location_source", GPS))) {
                f.location = typed(lat, lon, accuracy);
            } else {
                Location l = new Location(GPS);
                l.setLatitude(lat);
                l.setLongitude(lon);
                l.setAccuracy(accuracy == null ? 0 : accuracy);
                l.setTime(p.getLong("fix_time", 0));
                f.location = l;
            }
        }
        return f;
    }

    void save(Context context) {
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("phone_name") // a name the user could change, saved by older versions
                .putString("site", site)
                .putString("transect", transect)
                .putString("plant", plant)
                .putString("sample_id", sampleId)
                .putString("session_id", sessionId);
        JSONArray a = new JSONArray();
        for (Custom c : custom) {
            JSONObject o = new JSONObject();
            SessionLog.put(o, "name", c.name);
            SessionLog.put(o, "type", c.type);
            SessionLog.put(o, "value", c.value);
            a.put(o);
        }
        e.putString("custom_fields", a.toString());
        e.remove("latitude").remove("longitude").remove("location_source").remove("accuracy_m").remove("fix_time");
        if (location != null) {
            e.putString("latitude", String.valueOf(location.getLatitude()))
                    .putString("longitude", String.valueOf(location.getLongitude()))
                    .putString("location_source", location.getProvider());
            if (location.hasAccuracy()) e.putFloat("accuracy_m", location.getAccuracy());
            if (fromGps(location)) e.putLong("fix_time", location.getTime());
        }
        e.apply();
    }

    /** A plain-language problem with the values, or null when they are usable. */
    String problem() {
        for (Custom c : custom) {
            String p = c.problem();
            if (p != null) return p;
        }
        return location == null ? null : positionProblem(location.getLatitude(), location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : null);
    }

    /** What is wrong with the name of a new custom field, or null. Names are unique (in any case):
     *  they become keys in the record. */
    static String nameProblem(String name, List<Custom> existing) {
        if (name.isEmpty()) return "Write a name for the field.";
        if (name.length() > MAX_NAME) return "Use at most " + MAX_NAME + " characters.";
        for (Custom c : existing) {
            if (c.name.equalsIgnoreCase(name)) return "There is already a field with this name.";
        }
        return null;
    }

    /** What is wrong with a position in decimal degrees and its accuracy in m (null: not given),
     *  or null. */
    static String positionProblem(double latitude, double longitude, Float accuracyM) {
        if (!(latitude >= -90 && latitude <= 90)) return "Latitude: use -90 to 90 (decimal degrees; south is negative).";
        if (!(longitude >= -180 && longitude <= 180)) return "Longitude: use -180 to 180 (decimal degrees; west is negative).";
        if (accuracyM != null && !(accuracyM >= 0 && accuracyM <= MAX_ACCURACY_M)) return "Accuracy: use 0 to 10000 m, or leave it empty.";
        return null;
    }

    /** A position typed by hand: no time; the accuracy only if given. */
    static Location typed(double latitude, double longitude, Float accuracyM) {
        Location l = new Location(TYPED);
        l.setLatitude(latitude);
        l.setLongitude(longitude);
        if (accuracyM != null) l.setAccuracy(accuracyM);
        return l;
    }

    static boolean fromGps(Location l) {
        return GPS.equals(l.getProvider());
    }

    /** The start of each session folder name: the phone model and ID, for example
     *  SM-M127F_1A2B3C4D, so folders from several phones never mix on one computer. Letters, digits
     *  and - only in the model part: safe in folder names on every computer, and "_" splits the parts. */
    static String folderPrefix(Context context) {
        String model = Build.MODEL.replaceAll("[^A-Za-z0-9-]+", "-").replaceAll("^-+|-+$", "");
        if (model.length() > 20) model = model.substring(0, 20);
        return (model.isEmpty() ? "Phone" : model) + "_" + phoneId(context);
    }

    /** 8 hex characters of Android's ID for this app on this phone. The user cannot change it; it
     *  stays the same when the app is installed again, but a factory reset (and on Android 8+ an app
     *  signed with another key, such as a test build) gives another one. It tells the phones of a
     *  team apart in the session records. HardwareIds: FaunaLapse has no network access, so the ID
     *  never leaves the phone except in the user's own files. */
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

    /** "48.858370": WGS 84 decimal degrees (as GPS gives them), 6 decimals (about 0.1 m). */
    static String degrees(double d) {
        return String.format(Locale.US, "%.6f", d);
    }

    /** For the session record: every key always present (empty text, or null when not known),
     *  so tables built from many records have the same columns. */
    JSONObject toJson(Context context) {
        JSONObject j = new JSONObject();
        SessionLog.put(j, "phone_maker", Build.MANUFACTURER);
        SessionLog.put(j, "phone_model", Build.MODEL);
        SessionLog.put(j, "phone_id", phoneId(context));
        SessionLog.put(j, "site", site);
        SessionLog.put(j, "transect", transect);
        SessionLog.put(j, "plant", plant);
        SessionLog.put(j, "sample_id", sampleId);
        SessionLog.put(j, "session_id", sessionId);
        JSONObject c = new JSONObject(); // name: value, as the user named them
        for (Custom f : custom) SessionLog.put(c, f.name, f.jsonValue());
        SessionLog.put(j, "custom", c);
        if (location == null) {
            SessionLog.put(j, "location", JSONObject.NULL);
        } else {
            JSONObject l = new JSONObject();
            SessionLog.put(l, "latitude", Math.round(location.getLatitude() * 1e6) / 1e6);
            SessionLog.put(l, "longitude", Math.round(location.getLongitude() * 1e6) / 1e6);
            SessionLog.put(l, "datum", "WGS 84");
            SessionLog.put(l, "source", location.getProvider()); // "gps" of this phone, or "typed" by hand
            SessionLog.put(l, "accuracy_m", location.hasAccuracy()
                    ? Math.round(location.getAccuracy() * 10) / 10.0 : JSONObject.NULL);
            SessionLog.put(l, "fix_time", fromGps(location) ? SessionLog.isoTime(location.getTime()) : JSONObject.NULL);
            SessionLog.put(j, "location", l);
        }
        return j;
    }
}
