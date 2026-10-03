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
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Main screen: session settings with Start and Stop, and the phase 0 test buttons below. */
public final class MainActivity extends Activity {

    /** Test hooks for adb, only in debug builds (see AGENTS.md, "Build and test"). */
    private static final String EXTRA_TAKE_PHOTO = "take_photo";
    private static final String EXTRA_START_SESSION = "start_session";
    private static final String EXTRA_STOP_SESSION = "stop_session";
    private static final int PERMISSION_REQUEST = 1;
    private static final int TEST_PHOTO = 0;
    private static final int SPEED_TEST = 1;
    private static final int SESSION = 2;

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
    private Button startButton;
    private Button stopButton;
    private TextView status;
    private TextView sessionStatus;
    private TextView phoneTime;
    private RadioGroup mode;
    private RadioGroup schedule;
    private RadioGroup videoQuality;
    private EditText stepS;
    private EditText burstS;
    private EditText squarePercent;
    private EditText savedSidePx;
    private EditText clipMin;
    private EditText breakMin;
    private EditText runH;
    private EditText windows;
    private EditText days;
    private int requested;
    private boolean cameraDenied;
    private String sessionMessage; // a problem with the values, shown until the next tap

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        TextView intro = findViewById(R.id.intro);
        intro.setText(getString(R.string.intro, CaptureService.START_DELAY_MS / 1000,
                CaptureService.outputDir(this).getAbsolutePath()));
        photoButton = findViewById(R.id.take_photo);
        photoButton.setOnClickListener(v -> start(TEST_PHOTO));
        speedButton = findViewById(R.id.speed_test);
        speedButton.setOnClickListener(v -> start(SPEED_TEST));
        startButton = findViewById(R.id.start_session);
        startButton.setOnClickListener(v -> startSession());
        stopButton = findViewById(R.id.stop_session);
        stopButton.setOnClickListener(v -> stopSession());
        status = findViewById(R.id.status);
        sessionStatus = findViewById(R.id.session_status);
        phoneTime = findViewById(R.id.phone_time);
        mode = findViewById(R.id.mode);
        schedule = findViewById(R.id.schedule);
        videoQuality = findViewById(R.id.video_quality);
        stepS = findViewById(R.id.step_s);
        burstS = findViewById(R.id.burst_s);
        squarePercent = findViewById(R.id.square_percent);
        savedSidePx = findViewById(R.id.saved_side_px);
        clipMin = findViewById(R.id.clip_min);
        breakMin = findViewById(R.id.break_min);
        runH = findViewById(R.id.run_h);
        windows = findViewById(R.id.windows);
        days = findViewById(R.id.days);
        showSettings(SessionSettings.load(this));
        mode.setOnCheckedChangeListener((group, id) -> showGroups());
        schedule.setOnCheckedChangeListener((group, id) -> showGroups());
        showGroups();
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

    // ---- Session settings -------------------------------------------------------------------

    private void showSettings(SessionSettings s) {
        mode.check(s.video ? R.id.mode_video : R.id.mode_photo);
        schedule.check(s.daily ? R.id.schedule_daily : R.id.schedule_run);
        videoQuality.check("480p".equals(s.videoQuality) ? R.id.video_480p
                : "720p".equals(s.videoQuality) ? R.id.video_720p : R.id.video_1080p);
        stepS.setText(number(s.stepS));
        burstS.setText(number(s.burstS));
        squarePercent.setText(String.valueOf(s.squarePercent));
        savedSidePx.setText(String.valueOf(s.savedSidePx));
        clipMin.setText(number(s.clipMin));
        breakMin.setText(number(s.breakMin));
        runH.setText(number(s.runH));
        windows.setText(s.windows);
        days.setText(String.valueOf(s.days));
    }

    /** Shows only the settings that apply to the chosen mode and schedule. */
    private void showGroups() {
        boolean video = mode.getCheckedRadioButtonId() == R.id.mode_video;
        boolean daily = schedule.getCheckedRadioButtonId() == R.id.schedule_daily;
        findViewById(R.id.photo_settings).setVisibility(video ? View.GONE : View.VISIBLE);
        findViewById(R.id.video_settings).setVisibility(video ? View.VISIBLE : View.GONE);
        findViewById(R.id.run_settings).setVisibility(daily ? View.GONE : View.VISIBLE);
        findViewById(R.id.daily_settings).setVisibility(daily ? View.VISIBLE : View.GONE);
    }

    /** The settings on the screen, or null when a value is not usable (the reason is shown). */
    private SessionSettings readSettings() {
        SessionSettings s = new SessionSettings();
        try {
            s.video = mode.getCheckedRadioButtonId() == R.id.mode_video;
            s.daily = schedule.getCheckedRadioButtonId() == R.id.schedule_daily;
            int quality = videoQuality.getCheckedRadioButtonId();
            s.videoQuality = quality == R.id.video_480p ? "480p" : quality == R.id.video_720p ? "720p" : "1080p";
            s.stepS = decimal(stepS, R.string.step_label);
            s.burstS = decimal(burstS, R.string.burst_label);
            s.squarePercent = (int) Math.round(decimal(squarePercent, R.string.square_label));
            s.savedSidePx = (int) Math.round(decimal(savedSidePx, R.string.saved_side_label));
            s.clipMin = decimal(clipMin, R.string.clip_label);
            s.breakMin = decimal(breakMin, R.string.break_label);
            s.runH = decimal(runH, R.string.run_label);
            s.windows = windows.getText().toString().trim();
            s.days = (int) Math.round(decimal(days, R.string.days_label));
        } catch (NumberFormatException e) {
            sessionMessage = e.getMessage();
            return null;
        }
        sessionMessage = s.problem();
        return sessionMessage == null ? s : null;
    }

    private double decimal(EditText field, int labelId) {
        try {
            return Double.parseDouble(field.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new NumberFormatException(getString(R.string.number_needed, getString(labelId)));
        }
    }

    private static String number(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private void startSession() {
        SessionSettings s = readSettings();
        if (s == null) {
            refresh();
            return;
        }
        s.save(this);
        start(SESSION);
    }

    private void stopSession() {
        startService(new Intent(this, CaptureService.class).putExtra(CaptureService.EXTRA_STOP, true));
    }

    // ---- Starting the service ---------------------------------------------------------------

    private void handleTestIntent(Intent intent) {
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debuggable) return;
        if (intent.getBooleanExtra(EXTRA_TAKE_PHOTO, false)) start(TEST_PHOTO);
        if (intent.getBooleanExtra(CaptureService.EXTRA_SPEED_TEST, false)) start(SPEED_TEST);
        if (intent.getBooleanExtra(EXTRA_STOP_SESSION, false)) stopSession();
        if (intent.getBooleanExtra(EXTRA_START_SESSION, false)) {
            // Optional values, for example: --es mode video --es clip_min 0.5 --es run_h 0.1
            String m = intent.getStringExtra("mode");
            if (m != null) mode.check("video".equals(m) ? R.id.mode_video : R.id.mode_photo);
            String sc = intent.getStringExtra("schedule");
            if (sc != null) schedule.check("daily".equals(sc) ? R.id.schedule_daily : R.id.schedule_run);
            String vq = intent.getStringExtra("video_quality");
            if (vq != null) {
                videoQuality.check("480p".equals(vq) ? R.id.video_480p
                        : "720p".equals(vq) ? R.id.video_720p : R.id.video_1080p);
            }
            setFromExtra(intent, "step_s", stepS);
            setFromExtra(intent, "burst_s", burstS);
            setFromExtra(intent, "square_percent", squarePercent);
            setFromExtra(intent, "saved_side_px", savedSidePx);
            setFromExtra(intent, "clip_min", clipMin);
            setFromExtra(intent, "break_min", breakMin);
            setFromExtra(intent, "run_h", runH);
            setFromExtra(intent, "windows", windows);
            setFromExtra(intent, "days", days);
            startSession();
        }
    }

    private static void setFromExtra(Intent intent, String key, EditText field) {
        String value = intent.getStringExtra(key);
        if (value != null) field.setText(value);
    }

    private void start(int what) {
        requested = what;
        cameraDenied = false;
        List<String> missing = missingPermissions();
        if (!missing.isEmpty() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
            return;
        }
        if (CaptureService.running) return;
        Intent intent = new Intent(this, CaptureService.class)
                .putExtra(CaptureService.EXTRA_SPEED_TEST, what == SPEED_TEST)
                .putExtra(CaptureService.EXTRA_SESSION, what == SESSION);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        // The notification permission is optional: without it the camera still works.
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            start(requested);
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

    // ---- What the screen shows --------------------------------------------------------------

    private void refresh() {
        phoneTime.setText(getString(R.string.phone_time,
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(new Date())));
        boolean running = CaptureService.running;
        boolean session = CaptureService.sessionRunning;
        photoButton.setEnabled(!running);
        speedButton.setEnabled(!running);
        startButton.setEnabled(!running);
        stopButton.setEnabled(session);

        String warning = SessionRunner.warning;
        if (session || !SessionRunner.status.isEmpty()) {
            sessionStatus.setText(SessionRunner.status + (warning.isEmpty() ? "" : "\n\n" + warning));
        } else {
            sessionStatus.setText(getString(R.string.session_idle, CaptureService.sessionsDir(this).getAbsolutePath()));
        }
        if (sessionMessage != null && !running) sessionStatus.setText(sessionMessage);

        if (cameraDenied) {
            status.setText(R.string.camera_permission_needed);
            return;
        }
        if (running && !session) {
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
