package com.faunalapse.app;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The session record, `session.jsonl`: one JSON object per line, one line per event. Every line
 * starts with "event" and "time" (phone clock, with time zone). Safe to call from several threads.
 */
final class SessionLog {

    private final File file;

    SessionLog(File file) {
        this.file = file;
    }

    /** A new record for {@code event}, stamped with the current phone time. */
    static JSONObject record(String event) {
        JSONObject r = new JSONObject();
        put(r, "event", event);
        put(r, "time", isoTime(System.currentTimeMillis()));
        return r;
    }

    synchronized void write(JSONObject r) {
        Log.i(CaptureService.TAG, r.toString());
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            w.write(r.toString());
            w.write('\n');
        } catch (IOException e) {
            Log.e(CaptureService.TAG, "Cannot write " + file, e);
        }
    }

    static String isoTime(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date(ms));
    }

    /** File name stem from a time: sorts correctly across midnight. */
    static String fileStem(long ms) {
        return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date(ms));
    }

    static void put(JSONObject r, String key, Object value) {
        try {
            r.put(key, value);
        } catch (JSONException ignored) {
            // Only thrown for NaN or infinite numbers, which we never write.
        }
    }
}
