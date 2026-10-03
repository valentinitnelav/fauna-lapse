package com.faunalapse.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Phase 0 test screen: start one photo or the speed test; shows the record of the last test. */
public final class MainActivity extends Activity {

    /** Test hooks for adb, only in debug builds: start a test without a tap. */
    private static final String EXTRA_TAKE_PHOTO = "take_photo";
    private static final int PERMISSION_REQUEST = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };
    private Button photoButton;
    private Button speedButton;
    private boolean speedTestRequested;
    private TextView status;
    private boolean cameraDenied;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        TextView intro = findViewById(R.id.intro);
        intro.setText(getString(R.string.intro, CaptureService.START_DELAY_MS / 1000,
                CaptureService.outputDir(this).getAbsolutePath()));
        photoButton = findViewById(R.id.take_photo);
        photoButton.setOnClickListener(v -> start(false));
        speedButton = findViewById(R.id.speed_test);
        speedButton.setOnClickListener(v -> start(true));
        status = findViewById(R.id.status);
        if (savedInstanceState == null) handleTestIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleTestIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    private void handleTestIntent(Intent intent) {
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debuggable) return;
        if (intent.getBooleanExtra(EXTRA_TAKE_PHOTO, false)) start(false);
        if (intent.getBooleanExtra(CaptureService.EXTRA_SPEED_TEST, false)) start(true);
    }

    private void start(boolean speedTest) {
        speedTestRequested = speedTest;
        cameraDenied = false;
        List<String> missing = missingPermissions();
        if (!missing.isEmpty() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
            return;
        }
        if (CaptureService.running) return;
        Intent intent = new Intent(this, CaptureService.class)
                .putExtra(CaptureService.EXTRA_SPEED_TEST, speedTest);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        // The notification permission is optional: without it the photo is still taken.
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            start(speedTestRequested);
        } else {
            cameraDenied = true;
            refresh();
        }
    }

    /** Runtime permissions exist only from Android 6; older phones grant them at install. */
    private List<String> missingPermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 23) return missing;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33 && !missing.isEmpty()
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS); // asked together with the camera
        }
        return missing;
    }

    private void refresh() {
        boolean running = CaptureService.running;
        photoButton.setEnabled(!running);
        speedButton.setEnabled(!running);
        if (cameraDenied) {
            status.setText(R.string.camera_permission_needed);
            return;
        }
        if (running) {
            status.setText(getString(R.string.status_running, CaptureService.START_DELAY_MS / 1000));
            return;
        }
        String line = lastLine(CaptureService.logFile(this));
        if (line == null) {
            status.setText(R.string.status_none);
            return;
        }
        try {
            JSONObject record = new JSONObject(line);
            String headline = "ok".equals(record.optString("result"))
                    ? getString(R.string.status_ok, record.optString("file"))
                    : getString(R.string.status_error, record.optString("error"));
            StringBuilder text = new StringBuilder(headline).append('\n');
            for (Iterator<String> keys = record.keys(); keys.hasNext(); ) {
                String key = keys.next();
                text.append('\n').append(key).append(": ").append(record.opt(key));
            }
            status.setText(text);
        } catch (JSONException e) {
            status.setText(line);
        }
    }

    private static String lastLine(File file) {
        if (!file.exists()) return null;
        String last = null;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (!line.trim().isEmpty()) last = line;
            }
        } catch (IOException e) {
            return null;
        }
        return last;
    }
}
