package com.faunalapse.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.ScrollView;
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

/**
 * Main screen: a status card (ready, or what the running session does), then the steps of a setup:
 * 1 aim (photos or video, the aim screen), 2 schedule (a one-line summary; the settings open on
 * "Change"), 3 Start. The phase 0 test buttons are folded away at the bottom.
 */
public final class MainActivity extends Activity {

    /** Test hooks for adb, only in debug builds (see AGENTS.md, "Build and test"). */
    private static final String EXTRA_TAKE_PHOTO = "take_photo";
    private static final String EXTRA_START_SESSION = "start_session";
    private static final String EXTRA_STOP_SESSION = "stop_session";
    private static final String EXTRA_AIM = "aim";
    private static final String EXTRA_CAMERA1 = "camera1";
    private static final int PERMISSION_REQUEST = 1;
    private static final int TEST_PHOTO = 0;
    private static final int SPEED_TEST = 1;
    private static final int SESSION = 2;
    private static final int AIM = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };
    private ScrollView scroll;
    private TextView statusTitle;
    private TextView sessionStatus;
    private TextView sessionProgress;
    private TextView sessionWarning;
    private TextView battery;
    private TextView phoneTime;
    private Button stopButton;
    private View aimCard;
    private View scheduleCard;
    private Button aimButton;
    private TextView aimSummary;
    private TextView scheduleSummary;
    private ColorStateList bodyColor;
    private Button changeButton;
    private View settingsGroup;
    private TextView startMessage;
    private Button startButton;
    private TextView startHint;
    private Button testsToggle;
    private View testsGroup;
    private Button photoButton;
    private Button speedButton;
    private TextView status;
    private OrientationEventListener orientationListener;
    private volatile int phoneTurn; // how the phone is held now (RoiMath.quarterTurn)
    private RadioGroup mode;
    private RadioGroup schedule;
    private RadioGroup videoQuality;
    private RadioGroup videoFps;
    private EditText stepS;
    private EditText burstS;
    private EditText savedSidePx;
    private EditText clipMin;
    private EditText breakMin;
    private EditText runH;
    private EditText windows;
    private EditText days;
    private EditText heatLimitC;
    private EditText phoneName;
    private int requested;
    private boolean cameraDenied;
    private String problem; // why Start or Aim did not go on, shown above Start until the next tap
    private Boolean controlsEnabled; // null until first set
    private String lastSession; // from the log; null = read it again

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        scroll = findViewById(R.id.scroll);
        statusTitle = findViewById(R.id.status_title);
        sessionStatus = findViewById(R.id.session_status);
        sessionProgress = findViewById(R.id.session_progress);
        sessionWarning = findViewById(R.id.session_warning);
        battery = findViewById(R.id.battery);
        phoneTime = findViewById(R.id.phone_time);
        stopButton = findViewById(R.id.stop_session);
        stopButton.setOnClickListener(v -> askToStop());
        stopButton.setText(withIcon(stopButton, R.drawable.ic_stop, R.string.stop_session));
        aimCard = findViewById(R.id.aim_card);
        scheduleCard = findViewById(R.id.schedule_card);
        aimButton = findViewById(R.id.aim);
        aimButton.setOnClickListener(v -> start(AIM));
        aimSummary = findViewById(R.id.aim_summary);
        scheduleSummary = findViewById(R.id.schedule_summary);
        bodyColor = scheduleSummary.getTextColors();
        changeButton = findViewById(R.id.change);
        settingsGroup = findViewById(R.id.settings);
        changeButton.setOnClickListener(v -> showSettingsGroup(settingsGroup.getVisibility() != View.VISIBLE));
        findViewById(R.id.defaults).setOnClickListener(v -> showSettings(new SessionSettings(), false));
        startMessage = findViewById(R.id.start_message);
        startButton = findViewById(R.id.start_session);
        startButton.setOnClickListener(v -> startSession());
        startButton.setText(withIcon(startButton, R.drawable.ic_start, R.string.start_session));
        startHint = findViewById(R.id.start_hint);
        testsToggle = findViewById(R.id.tests_toggle);
        testsGroup = findViewById(R.id.tests);
        testsToggle.setOnClickListener(v -> {
            boolean show = testsGroup.getVisibility() != View.VISIBLE;
            testsGroup.setVisibility(show ? View.VISIBLE : View.GONE);
            testsToggle.setText(show ? R.string.tests_hide : R.string.tests_show);
            refresh();
        });
        TextView intro = findViewById(R.id.intro);
        intro.setText(getString(R.string.intro, CaptureService.START_DELAY_MS / 1000,
                CaptureService.outputDir(this).getAbsolutePath()));
        photoButton = findViewById(R.id.take_photo);
        photoButton.setOnClickListener(v -> start(TEST_PHOTO));
        speedButton = findViewById(R.id.speed_test);
        speedButton.setOnClickListener(v -> start(SPEED_TEST));
        status = findViewById(R.id.status);
        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int degrees) {
                phoneTurn = RoiMath.quarterTurn(degrees);
            }
        };
        mode = findViewById(R.id.mode);
        schedule = findViewById(R.id.schedule);
        videoQuality = findViewById(R.id.video_quality);
        videoFps = findViewById(R.id.video_fps);
        stepS = findViewById(R.id.step_s);
        burstS = findViewById(R.id.burst_s);
        savedSidePx = findViewById(R.id.saved_side_px);
        clipMin = findViewById(R.id.clip_min);
        breakMin = findViewById(R.id.break_min);
        runH = findViewById(R.id.run_h);
        windows = findViewById(R.id.windows);
        days = findViewById(R.id.days);
        heatLimitC = findViewById(R.id.heat_limit_c);
        phoneName = findViewById(R.id.device_name);
        showSettings(SessionSettings.load(this), true);
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
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
        showAimSummary(); // the aim screen may have moved the square
        showStartHint(); // the storage permission may have changed
        lastSession = null;
        if (CaptureService.sessionRunning) scroll.post(() -> scroll.smoothScrollTo(0, 0)); // started on the aim screen
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        orientationListener.disable();
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    // ---- Session settings -------------------------------------------------------------------

    /** Puts the values on the screen; {@code all}: also photos or video (card 1) and the phone name,
     *  which "Default values" keeps. */
    private void showSettings(SessionSettings s, boolean all) {
        if (all) {
            mode.check(s.video ? R.id.mode_video : R.id.mode_photo);
            phoneName.setText(s.phoneName);
        }
        schedule.check(s.daily ? R.id.schedule_daily : R.id.schedule_run);
        videoQuality.check("480p".equals(s.videoQuality) ? R.id.video_480p
                : "720p".equals(s.videoQuality) ? R.id.video_720p : R.id.video_1080p);
        videoFps.check(s.videoFps == 15 ? R.id.video_fps_15 : s.videoFps == 10 ? R.id.video_fps_10 : R.id.video_fps_5);
        stepS.setText(number(s.stepS));
        burstS.setText(number(s.burstS));
        savedSidePx.setText(String.valueOf(s.savedSidePx));
        clipMin.setText(number(s.clipMin));
        breakMin.setText(number(s.breakMin));
        runH.setText(number(s.runH));
        windows.setText(s.windows);
        days.setText(String.valueOf(s.days));
        heatLimitC.setText(number(s.heatLimitC));
    }

    /** Shows only the settings that apply to the chosen mode and schedule. */
    private void showGroups() {
        boolean video = mode.getCheckedRadioButtonId() == R.id.mode_video;
        boolean daily = schedule.getCheckedRadioButtonId() == R.id.schedule_daily;
        findViewById(R.id.photo_settings).setVisibility(video ? View.GONE : View.VISIBLE);
        findViewById(R.id.photo_size_settings).setVisibility(video ? View.GONE : View.VISIBLE);
        findViewById(R.id.video_settings).setVisibility(video ? View.VISIBLE : View.GONE);
        findViewById(R.id.run_settings).setVisibility(daily ? View.GONE : View.VISIBLE);
        findViewById(R.id.daily_settings).setVisibility(daily ? View.VISIBLE : View.GONE);
        showAimSummary();
        showScheduleSummary();
    }

    private void showSettingsGroup(boolean show) {
        settingsGroup.setVisibility(show ? View.VISIBLE : View.GONE);
        changeButton.setText(show ? R.string.change_close : R.string.change_open);
    }

    /** The square as saved by the aim screen (photo mode), or what the aim screen is for (video). */
    private void showAimSummary() {
        if (mode.getCheckedRadioButtonId() == R.id.mode_video) {
            aimSummary.setText(R.string.aim_summary_video);
            return;
        }
        SessionSettings s = SessionSettings.load(this);
        boolean middle = Math.abs(s.squareX - 0.5) < 0.005 && Math.abs(s.squareY - 0.5) < 0.005;
        aimSummary.setText(getString(R.string.aim_summary_photo, s.squarePercent,
                getString(middle ? R.string.aim_square_middle : R.string.aim_square_placed)));
    }

    /** The schedule in words, from the values on the screen; or what is wrong with them. */
    private void showScheduleSummary() {
        String text;
        boolean ok = true;
        try {
            SessionSettings s = settingsOnScreen();
            String what = s.video
                    ? s.breakMin > 0 ? getString(R.string.summary_video, s.videoQuality, s.videoFps, number(s.clipMin),
                            number(s.breakMin))
                            : getString(R.string.summary_video_no_break, s.videoQuality, s.videoFps, number(s.clipMin))
                    : s.breakMin > 0 ? getString(R.string.summary_photo, number(s.stepS), number(s.burstS), number(s.breakMin))
                            : getString(R.string.summary_photo_no_break, number(s.stepS));
            String when = s.daily ? getString(R.string.summary_daily, s.windows, s.days)
                    : getString(R.string.summary_run, number(s.runH));
            text = what + "\n" + when + "\n" + getString(R.string.summary_heat, number(s.heatLimitC));
        } catch (IllegalArgumentException e) {
            text = e.getMessage();
            ok = false;
        }
        if (!text.contentEquals(scheduleSummary.getText())) scheduleSummary.setText(text);
        if (ok) {
            scheduleSummary.setTextColor(bodyColor);
            scheduleSummary.setTypeface(null, Typeface.NORMAL);
            problem = null; // fixed since the last tap
        } else {
            scheduleSummary.setTextColor(getColor(R.color.warning));
            scheduleSummary.setTypeface(null, Typeface.BOLD); // a problem shows also without colour
        }
    }

    /** The settings on the screen (the square from the aim screen). Throws with a plain-language
     *  message when a value is not usable. */
    private SessionSettings settingsOnScreen() {
        SessionSettings s = SessionSettings.load(this);
        s.video = mode.getCheckedRadioButtonId() == R.id.mode_video;
        s.daily = schedule.getCheckedRadioButtonId() == R.id.schedule_daily;
        s.videoQuality = chosenVideoQuality();
        int fps = videoFps.getCheckedRadioButtonId();
        s.videoFps = fps == R.id.video_fps_15 ? 15 : fps == R.id.video_fps_10 ? 10 : 5;
        s.stepS = decimal(stepS, R.string.step_label);
        s.burstS = decimal(burstS, R.string.burst_label);
        s.savedSidePx = (int) Math.round(decimal(savedSidePx, R.string.saved_side_label));
        s.clipMin = decimal(clipMin, R.string.clip_label);
        s.breakMin = decimal(breakMin, R.string.break_label);
        s.runH = decimal(runH, R.string.run_label);
        s.windows = windows.getText().toString().trim();
        s.days = (int) Math.round(decimal(days, R.string.days_label));
        s.heatLimitC = decimal(heatLimitC, R.string.heat_label);
        s.phoneName = phoneName.getText().toString().trim();
        String p = s.problem();
        if (p != null) throw new IllegalArgumentException(p);
        return s;
    }

    /** Saves the values on the screen; false (and the reason shown, the settings open) when a value
     *  is not usable. */
    private boolean saveSettings() {
        try {
            settingsOnScreen().save(this);
            problem = null;
            return true;
        } catch (IllegalArgumentException e) {
            problem = e.getMessage();
            showSettingsGroup(true);
            return false;
        } finally {
            refresh();
        }
    }

    private String chosenVideoQuality() {
        int id = videoQuality.getCheckedRadioButtonId();
        return id == R.id.video_480p ? "480p" : id == R.id.video_720p ? "720p" : "1080p";
    }

    private double decimal(EditText field, int labelId) {
        try {
            return Double.parseDouble(field.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(getString(R.string.number_needed, getString(labelId)));
        }
    }

    private static String number(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** Where the files go; in the app's own folder (Android 10 and older without the storage
     *  permission) also that they are removed with the app. */
    private void showStartHint() {
        File dir = CaptureService.sessionsDir(this);
        String hint = getString(R.string.start_hint, CaptureService.shownPath(dir));
        if (dir.equals(CaptureService.appSessionsDir(this))) hint += " " + getString(R.string.start_hint_app_folder);
        startHint.setText(hint);
    }

    private void startSession() {
        if (!saveSettings()) return;
        showSettingsGroup(false);
        start(SESSION);
        scroll.post(() -> scroll.smoothScrollTo(0, 0)); // the status card shows the session
    }

    private void askToStop() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.stop_question)
                .setMessage(R.string.stop_question_text)
                .setPositiveButton(R.string.stop_yes, (dialog, which) -> stopSession())
                .setNegativeButton(R.string.stop_no, null)
                .show();
    }

    private void stopSession() {
        startService(new Intent(this, CaptureService.class).putExtra(CaptureService.EXTRA_STOP, true));
    }
    // ---- Starting the service ---------------------------------------------------------------

    private void handleTestIntent(Intent intent) {
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debuggable) return;
        if (intent.hasExtra(EXTRA_CAMERA1)) SessionRunner.forceCamera1 = intent.getBooleanExtra(EXTRA_CAMERA1, false);
        if (intent.getBooleanExtra(EXTRA_AIM, false)) start(AIM);
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
            String fps = intent.getStringExtra("video_fps");
            if (fps != null) {
                videoFps.check("15".equals(fps) ? R.id.video_fps_15 : "10".equals(fps) ? R.id.video_fps_10 : R.id.video_fps_5);
            }
            setFromExtra(intent, "step_s", stepS);
            setFromExtra(intent, "burst_s", burstS);
            setFromExtra(intent, "saved_side_px", savedSidePx);
            setFromExtra(intent, "clip_min", clipMin);
            setFromExtra(intent, "break_min", breakMin);
            setFromExtra(intent, "run_h", runH);
            setFromExtra(intent, "windows", windows);
            setFromExtra(intent, "days", days);
            setFromExtra(intent, "heat_limit_c", heatLimitC);
            setFromExtra(intent, "phone_name", phoneName);
            // The square has no field on this screen: save it, settingsOnScreen then loads it.
            SessionSettings saved = SessionSettings.load(this);
            saved.squarePercent = (int) Math.round(fromExtra(intent, "square_percent", saved.squarePercent));
            saved.squareX = fromExtra(intent, "square_x", saved.squareX);
            saved.squareY = fromExtra(intent, "square_y", saved.squareY);
            saved.save(this);
            startSession();
        }
    }

    private static void setFromExtra(Intent intent, String key, EditText field) {
        String value = intent.getStringExtra(key);
        if (value != null) field.setText(value);
    }

    private static double fromExtra(Intent intent, String key, double fallback) {
        String value = intent.getStringExtra(key);
        return value == null ? fallback : Double.parseDouble(value);
    }

    private void start(int what) {
        requested = what;
        cameraDenied = false;
        if (what == AIM && !saveSettings()) return; // the aim screen and its Start use the saved values
        List<String> missing = missingPermissions();
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
            return;
        }
        if (CaptureService.running) return;
        if (what == AIM) {
            startActivity(new Intent(this, AimActivity.class));
            return;
        }
        if (what == SESSION) {
            CaptureService.startSession(this, phoneTurn);
        } else {
            Intent intent = new Intent(this, CaptureService.class)
                    .putExtra(CaptureService.EXTRA_SPEED_TEST, what == SPEED_TEST);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        }
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        // The notification and storage permissions are optional: without them the camera works.
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            start(requested);
        } else {
            cameraDenied = true;
            refresh();
        }
    }

    /** Permissions still to ask for: the camera; with it, on Android 13+ notifications and on
     *  Android 10 and older the storage (for Documents/FaunaLapse). */
    private List<String> missingPermissions() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT <= 29 && !missing.isEmpty()
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE); // optional, like notifications
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
        boolean running = CaptureService.running;
        boolean session = CaptureService.sessionRunning;
        int title;
        if (session) {
            title = SessionRunner.heatPaused ? R.string.status_paused : R.string.status_session;
            show(sessionStatus, SessionRunner.status);
            show(sessionProgress, SessionRunner.progress);
            show(sessionWarning, SessionRunner.warning);
        } else if (running) {
            title = R.string.status_test;
            show(sessionStatus, getString(R.string.status_running, CaptureService.START_DELAY_MS / 1000));
            show(sessionProgress, "");
            show(sessionWarning, "");
        } else {
            if (lastSession == null) lastSession = lastSession();
            title = R.string.status_ready;
            show(sessionStatus, lastSession);
            show(sessionProgress, "");
            show(sessionWarning, "");
        }
        // The phone name on top tells the phones of a team apart.
        String name = SessionSettings.load(this).phoneName;
        String titleText = getString(title) + (name.isEmpty() ? "" : " · " + name);
        if (!titleText.contentEquals(statusTitle.getText())) statusTitle.setText(titleText);
        if (running) lastSession = null; // read again when it has ended
        CaptureService.Battery b = CaptureService.Battery.read(this);
        battery.setText(getString(R.string.battery_line, b.percent >= 0 ? b.percent + " %" : "?",
                getString(b.charging ? R.string.charging : R.string.not_charging),
                b.tempC == null ? "?" : String.format(Locale.US, "%.1f", b.tempC)));
        phoneTime.setText(getString(R.string.phone_time,
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(new Date())));
        // One big button at a time: Start while idle, Stop during a session.
        stopButton.setVisibility(session ? View.VISIBLE : View.GONE);
        startButton.setVisibility(running ? View.GONE : View.VISIBLE);
        startHint.setVisibility(running ? View.GONE : View.VISIBLE);
        setControlsEnabled(!running);
        if (!running) showScheduleSummary();
        String message = cameraDenied ? getString(R.string.camera_permission_needed) : problem;
        show(startMessage, message == null ? "" : message);
        if (testsGroup.getVisibility() == View.VISIBLE) showTestStatus(running && !session);
    }

    /** Settings and buttons can be changed only while nothing runs (the camera is busy). */
    private void setControlsEnabled(boolean enabled) {
        if (controlsEnabled != null && controlsEnabled == enabled) return;
        controlsEnabled = enabled;
        setEnabled(aimCard, enabled);
        setEnabled(scheduleCard, enabled);
        aimCard.setAlpha(enabled ? 1 : 0.5f);
        scheduleCard.setAlpha(enabled ? 1 : 0.5f);
        startButton.setEnabled(enabled);
        photoButton.setEnabled(enabled);
        speedButton.setEnabled(enabled);
    }

    private static void setEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setEnabled(g.getChildAt(i), enabled);
        }
    }

    /** Sets the text, and hides the view while there is none. */
    /** The text with a shape before it (play, stop), so the big buttons differ also for people who
     *  do not see red and green apart. Sized to the height of a capital letter. */
    static CharSequence withIcon(TextView view, int icon, int text) {
        Drawable d = view.getContext().getDrawable(icon);
        int size = Math.round(view.getTextSize() * 0.72f);
        d.setBounds(0, 0, size, size);
        SpannableString s = new SpannableString("  " + view.getContext().getString(text));
        s.setSpan(new ImageSpan(d, ImageSpan.ALIGN_BASELINE), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private static void show(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** The last session from the log (it also survives a restart of the app), in words. */
    private String lastSession() {
        JSONObject last = null;
        for (String line : lines(CaptureService.logFile(this))) {
            try {
                JSONObject record = new JSONObject(line);
                if ("session".equals(record.optString("test"))) last = record;
            } catch (JSONException ignored) {
                // not a record
            }
        }
        if (last == null) return getString(R.string.no_session);
        String text = getString(R.string.last_session, last.optString("file"), last.optString("counts"),
                last.optString("reason"));
        String warning = last.optString("warning");
        return warning.isEmpty() ? text : text + "\n" + warning;
    }

    /** Phase 0 tests: the last test record, key by key. */
    private void showTestStatus(boolean testRunning) {
        if (cameraDenied) {
            status.setText(R.string.camera_permission_needed);
            return;
        }
        if (testRunning) {
            status.setText(getString(R.string.status_running, CaptureService.START_DELAY_MS / 1000));
            return;
        }
        List<String> all = lines(CaptureService.logFile(this));
        if (all.isEmpty()) {
            status.setText(R.string.status_none);
            return;
        }
        String line = all.get(all.size() - 1);
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

    private static List<String> lines(File file) {
        List<String> lines = new ArrayList<>();
        if (!file.exists()) return lines;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (!line.trim().isEmpty()) lines.add(line);
            }
        } catch (IOException e) {
            return lines;
        }
        return lines;
    }
}
