package com.faunalapse.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Field metadata: which phone recorded what, where and how it was set up. The user changes these at
 * each site or plant, so they are kept apart from the camera settings (SessionSettings). Saved on
 * the phone and written into each session record. The phone model and ID come from Android; the ID
 * and the start time make each session folder name unique. Record keys follow the Camtrap DP
 * deployment terms where one fits (setup_by, habitat, camera_height_m, detection_distance_m,
 * coordinate_uncertainty_m), in this record's style: lower case with the unit.
 */
final class FieldInfo {

    /** Longest text in a field: enough for a site or a plant description. */
    static final int MAX_TEXT = 100;
    /** Custom fields: the longest name (also of a choice), the longest text (a short value), the
     *  longest notes (about 1.5 pages: a phone keyboard is slow for more, and the record stays
     *  small) and the most choices in a list. */
    static final int MAX_NAME = 40;
    static final int MAX_CUSTOM_TEXT = 100;
    static final int MAX_NOTES = 5000;
    static final int MAX_CHOICES = 30;
    /** Highest uncertainty that may be typed, in m: a position from a map is rarely worse. */
    static final float MAX_UNCERTAINTY_M = 10000;
    /** Highest camera height and distance to the target, in m. */
    static final double MAX_SETUP_M = 100;
    /** Location extras key: the satellites used for the position (as Android's GPS sets it). */
    static final String SATELLITES = "satellites";
    /** Get location stops at this uncertainty or less, in whole m (the user's goal; default 10,
     *  maintainer); 0 searches the full time. Phones often do not reach under 5 m; above 1000 m any
     *  first position would do, so it is no goal. */
    static final int GPS_GOAL_M = 10;
    static final int LOW_GPS_GOAL_M = 5;
    static final int MAX_GPS_GOAL_M = 1000;
    /** Location extras key: the goal of the search that found the position. */
    static final String GOAL = "goal_m";

    String setupBy = "";
    String site = "";
    String transect = "";
    String plant = "";
    String habitat = "";
    String sampleId = "";
    String sessionId = "";
    /** In m as typed ("" = not given): text, so the field shows what was typed. */
    String cameraHeight = "";
    String distance = "";
    /** The distance comes from the fixed focus (the main screen fills it in) until the user types. */
    boolean distanceFromFocus = true;
    /** What the site photos show (optional); the photos themselves: SitePhotos. */
    String sitePhotosAbout = "";
    /** The position: from "Get location" (provider GPS, with its uncertainty, time and satellites)
     *  or typed by hand from another device (provider TYPED, uncertainty only if given); null when
     *  there is none. Android's getAccuracy() is the uncertainty: a radius in m with a 68 % chance. */
    Location location;
    /** The uncertainty goal of Get location, in m. */
    int gpsGoalM = GPS_GOAL_M;
    /** Fields the user added (for example Observer or Weather), in the order added. */
    List<Custom> custom = new ArrayList<>();

    /** A field the user added: its name, type, value ("" = empty) and, for CHOICE, its choices.
     *  Dates and times come from Android's pickers (2026-10-05 and 14:30), yes or no and choices
     *  from a list, so they are always written the same way. */
    static final class Custom {
        static final String TEXT = "text";
        static final String NOTES = "notes";
        static final String NUMBER = "number";
        static final String DATE = "date";
        static final String TIME = "time";
        static final String YES_NO = "yes_no";
        static final String CHOICE = "choice";
        static final String YES = "yes";
        static final String NO = "no";

        final String name;
        final String type;
        final String value;
        final List<String> choices; // CHOICE only, else empty

        Custom(String name, String type, String value) {
            this(name, type, value, new ArrayList<String>());
        }

        Custom(String name, String type, String value, List<String> choices) {
            this.name = name;
            this.type = type;
            this.value = value;
            this.choices = choices;
        }

        /** What is wrong with the value, or null. */
        String problem() {
            if (value.isEmpty()) return null;
            if (NUMBER.equals(type) && number(value) == null) return name + ": write a number.";
            if (DATE.equals(type) && !value.matches("\\d{4}-\\d{2}-\\d{2}")) return name + ": choose a date.";
            if (TIME.equals(type) && !value.matches("\\d{2}:\\d{2}")) return name + ": choose a time.";
            if (YES_NO.equals(type) && !YES.equals(value) && !NO.equals(value)) return name + ": choose yes or no.";
            if (CHOICE.equals(type) && !choices.contains(value)) return name + ": choose from the list.";
            int max = NOTES.equals(type) ? MAX_NOTES : MAX_CUSTOM_TEXT;
            if (value.length() > max) return name + ": use at most " + max + " characters.";
            return null;
        }

        /** For the record: a number for NUMBER, true or false for YES_NO, else the text; null when
         *  empty. */
        Object jsonValue() {
            if (value.isEmpty()) return JSONObject.NULL;
            if (NUMBER.equals(type)) return number(value);
            if (YES_NO.equals(type)) return YES.equals(value);
            return value;
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
        f.setupBy = p.getString("setup_by", "");
        f.site = p.getString("site", "");
        f.transect = p.getString("transect", "");
        f.plant = p.getString("plant", "");
        f.habitat = p.getString("habitat", "");
        f.sampleId = p.getString("sample_id", "");
        f.sessionId = p.getString("session_id", "");
        f.cameraHeight = p.getString("camera_height_m", "");
        f.distance = p.getString("detection_distance_m", "");
        f.distanceFromFocus = p.getBoolean("distance_from_focus", true);
        f.sitePhotosAbout = p.getString("site_photos_about", "");
        f.gpsGoalM = p.getInt("gps_goal_m", GPS_GOAL_M);
        try {
            JSONArray a = new JSONArray(p.getString("custom_fields", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.getJSONObject(i);
                List<String> choices = new ArrayList<>();
                JSONArray ch = c.optJSONArray("choices");
                for (int k = 0; ch != null && k < ch.length(); k++) choices.add(ch.getString(k));
                f.custom.add(new Custom(c.getString("name"), c.getString("type"), c.optString("value"), choices));
            }
        } catch (JSONException ignored) {
            // never written that way; the fields can be added again
        }
        if (p.contains("latitude")) {
            // Strings: a float would round the position to about 1 m.
            double lat = Double.parseDouble(p.getString("latitude", "0"));
            double lon = Double.parseDouble(p.getString("longitude", "0"));
            Float uncertainty = p.contains("accuracy_m") ? p.getFloat("accuracy_m", 0) : null;
            if (TYPED.equals(p.getString("location_source", GPS))) {
                f.location = typed(lat, lon, uncertainty);
            } else {
                Location l = new Location(GPS);
                l.setLatitude(lat);
                l.setLongitude(lon);
                l.setAccuracy(uncertainty == null ? 0 : uncertainty);
                l.setTime(p.getLong("fix_time", 0));
                Bundle b = new Bundle();
                if (p.contains(SATELLITES)) b.putInt(SATELLITES, p.getInt(SATELLITES, 0));
                if (p.contains("location_goal_m")) b.putInt(GOAL, p.getInt("location_goal_m", 0));
                l.setExtras(b);
                f.location = l;
            }
        }
        return f;
    }

    void save(Context context) {
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("phone_name") // a name the user could change, saved by older versions
                .putString("setup_by", setupBy)
                .putString("site", site)
                .putString("transect", transect)
                .putString("plant", plant)
                .putString("habitat", habitat)
                .putString("sample_id", sampleId)
                .putString("session_id", sessionId)
                .putString("camera_height_m", cameraHeight)
                .putString("detection_distance_m", distance)
                .putBoolean("distance_from_focus", distanceFromFocus)
                .putString("site_photos_about", sitePhotosAbout)
                .putInt("gps_goal_m", gpsGoalM);
        JSONArray a = new JSONArray();
        for (Custom c : custom) {
            JSONObject o = new JSONObject();
            SessionLog.put(o, "name", c.name);
            SessionLog.put(o, "type", c.type);
            SessionLog.put(o, "value", c.value);
            if (Custom.CHOICE.equals(c.type)) SessionLog.put(o, "choices", new JSONArray(c.choices));
            a.put(o);
        }
        e.putString("custom_fields", a.toString());
        e.remove("latitude").remove("longitude").remove("location_source").remove("accuracy_m").remove("fix_time")
                .remove(SATELLITES).remove("location_goal_m");
        if (location != null) {
            e.putString("latitude", String.valueOf(location.getLatitude()))
                    .putString("longitude", String.valueOf(location.getLongitude()))
                    .putString("location_source", location.getProvider());
            if (location.hasAccuracy()) e.putFloat("accuracy_m", location.getAccuracy());
            if (fromGps(location)) e.putLong("fix_time", location.getTime());
            Integer n = satellites(location);
            if (n != null) e.putInt(SATELLITES, n);
            Integer g = goal(location);
            if (g != null) e.putInt("location_goal_m", g);
        }
        e.apply();
    }

    /** A plain-language problem with the values, or null when they are usable. */
    String problem() {
        String p = setupProblem("Camera height", cameraHeight);
        if (p == null) p = setupProblem("Distance to target", distance);
        for (int i = 0; p == null && i < custom.size(); i++) p = custom.get(i).problem();
        if (p != null) return p;
        return location == null ? null : positionProblem(location.getLatitude(), location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : null);
    }

    /** A camera height or distance in m: empty, or 0 to MAX_SETUP_M. */
    static String setupProblem(String label, String value) {
        if (value.isEmpty()) return null;
        Double m = Custom.number(value);
        if (m == null || m < 0 || m > MAX_SETUP_M) {
            return label + ": use 0 to " + (int) MAX_SETUP_M + " m, or leave it empty.";
        }
        return null;
    }

    /** What is wrong with the choices of a new list field, or null. */
    static String choicesProblem(List<String> choices) {
        if (choices.size() < 2) return "Write at least 2 choices, one per line.";
        if (choices.size() > MAX_CHOICES) return "Use at most " + MAX_CHOICES + " choices.";
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).length() > MAX_NAME) return "Use at most " + MAX_NAME + " characters per choice.";
            for (int k = 0; k < i; k++) {
                if (choices.get(k).equalsIgnoreCase(choices.get(i))) return "\"" + choices.get(i) + "\" is there twice.";
            }
        }
        return null;
    }

    /** The lines of a text as choices: trimmed, empty lines left out. */
    static List<String> choiceLines(String text) {
        List<String> list = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.trim().isEmpty()) list.add(line.trim());
        }
        return list;
    }

    /** The fixed focus distance in m (two decimals) as a first guess of the distance to the target,
     *  or null: autofocus, a lens that does not tell distances (calibration "uncalibrated" or
     *  none), or farther than 10 m. */
    static Double focusDistanceM(SessionSettings s, String calibration) {
        boolean real = "calibrated".equals(calibration) || "approximate".equals(calibration);
        if (!s.focusFixed || !real || s.focusDiopters < 0.1) return null;
        return Math.round(100 / s.focusDiopters) / 100.0;
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

    /** What is wrong with a position in decimal degrees and its uncertainty in m (null: not
     *  given), or null. */
    static String positionProblem(double latitude, double longitude, Float uncertaintyM) {
        if (!(latitude >= -90 && latitude <= 90)) return "Latitude: use -90 to 90 (decimal degrees; south is negative).";
        if (!(longitude >= -180 && longitude <= 180)) return "Longitude: use -180 to 180 (decimal degrees; west is negative).";
        if (uncertaintyM != null && !(uncertaintyM >= 0 && uncertaintyM <= MAX_UNCERTAINTY_M)) {
            return "Uncertainty: use 0 to 10000 m, or leave it empty.";
        }
        return null;
    }

    /** A position typed by hand: no time; the uncertainty only if given. */
    static Location typed(double latitude, double longitude, Float uncertaintyM) {
        Location l = new Location(TYPED);
        l.setLatitude(latitude);
        l.setLongitude(longitude);
        if (uncertaintyM != null) l.setAccuracy(uncertaintyM);
        return l;
    }

    static boolean fromGps(Location l) {
        return GPS.equals(l.getProvider());
    }

    /** The satellites used for a GPS position, or null when not known. */
    static Integer satellites(Location l) {
        Bundle b = l.getExtras();
        return b != null && b.containsKey(SATELLITES) ? b.getInt(SATELLITES) : null;
    }

    /** The uncertainty goal of the search that found a GPS position, or null when not known. */
    static Integer goal(Location l) {
        Bundle b = l.getExtras();
        return b != null && b.containsKey(GOAL) ? b.getInt(GOAL) : null;
    }

    /** A typed uncertainty goal in whole m, or null when it is not 0 to MAX_GPS_GOAL_M. */
    static Integer gpsGoal(String text) {
        if (!text.matches("\\d{1,4}")) return null;
        int g = Integer.parseInt(text);
        return g <= MAX_GPS_GOAL_M ? g : null;
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
        SessionLog.put(j, "setup_by", setupBy);
        SessionLog.put(j, "site", site);
        SessionLog.put(j, "transect", transect);
        SessionLog.put(j, "plant", plant);
        SessionLog.put(j, "habitat", habitat);
        SessionLog.put(j, "sample_id", sampleId);
        SessionLog.put(j, "session_id", sessionId);
        SessionLog.put(j, "camera_height_m", metres(cameraHeight));
        SessionLog.put(j, "detection_distance_m", metres(distance)); // camera to target
        SessionLog.put(j, "detection_distance_source", distance.isEmpty() ? JSONObject.NULL
                : distanceFromFocus ? "fixed focus" : "typed");
        SessionLog.put(j, "site_photos_about", sitePhotosAbout);
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
            // Android: a radius with a 68 % chance of holding the true position (one standard error)
            SessionLog.put(l, "coordinate_uncertainty_m", location.hasAccuracy()
                    ? Math.round(location.getAccuracy() * 10) / 10.0 : JSONObject.NULL);
            Integer n = satellites(location);
            SessionLog.put(l, "satellites_used", n == null ? JSONObject.NULL : n);
            Integer g = goal(location);
            SessionLog.put(l, "uncertainty_goal_m", g == null ? JSONObject.NULL : g); // of Get location
            SessionLog.put(l, "fix_time", fromGps(location) ? SessionLog.isoTime(location.getTime()) : JSONObject.NULL);
            SessionLog.put(j, "location", l);
        }
        return j;
    }

    /** A typed number of m for the record, or null when empty. */
    private static Object metres(String value) {
        Double m = Custom.number(value);
        return m == null ? JSONObject.NULL : m;
    }
}
