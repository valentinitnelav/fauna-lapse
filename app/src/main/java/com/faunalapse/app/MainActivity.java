package com.faunalapse.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.text.style.ImageSpan;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
 * Main screen: a status card (what runs now, where the files go; the last session, battery and
 * clock fold away), then
 * the steps of a setup: 1 aim (photos or video, the aim screen), 2 field metadata (FieldInfo, with
 * the GPS position), 3 schedule (a short summary; the settings open on "Change"), 4 the phone for
 * the field (PhoneCheck), then Start. The phase 0 test buttons are folded away at the bottom.
 */
public final class MainActivity extends Activity {

    /** Test hooks for adb, only in debug builds (see AGENTS.md, "Build and test"). */
    private static final String EXTRA_TAKE_PHOTO = "take_photo";
    private static final String EXTRA_START_SESSION = "start_session";
    private static final String EXTRA_STOP_SESSION = "stop_session";
    private static final String EXTRA_AIM = "aim";
    private static final String EXTRA_CAMERA1 = "camera1";
    private static final int PERMISSION_REQUEST = 1;
    private static final int LOCATION_REQUEST = 2;
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
    private TextView sessionState;
    private TextView sessionStatus;
    private TextView sessionProgress;
    private TextView sessionWarning;
    private LinearLayout sessionChecks;
    private TextView detailsToggle;
    private View details;
    private boolean detailsOpen;
    private String detailsShown; // the toggle as drawn: rebuilt only when it changes
    private TextView lastSessionText;
    private TextView battery;
    private TextView phoneTime;
    private TextView storage;
    private RadioGroup storagePlaces; // the phone and each memory card, when there is a card
    private TextView storageTotal;
    private List<Storage.Place> places; // listed when the screen opens
    private boolean buildingPlaces; // the place buttons are being made: not a choice
    private long[][] storageUse; // per place: bytes and sessions in its FaunaLapse folder; null until counted
    private boolean wasRunning;
    private Button stopButton;
    private View aimCard;
    private View fieldCard;
    private View scheduleCard;
    private Button aimButton;
    private TextView aimSummary;
    private TextView scheduleSummary;
    private ColorStateList bodyColor;
    private Button changeButton;
    private View settingsGroup;
    private LinearLayout phoneChecks;
    private LinearLayout checks; // where the phone check lines go now: card 4, or the status card
    private String phoneState; // the PhoneCheck shown, as JSON text: rebuilt only when it changes
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
    private RadioGroup videoFps;
    private EditText stepS;
    private EditText burstS;
    private EditText savedSide;
    private EditText clipMin;
    private EditText breakMin;
    private EditText runH;
    private EditText runM;
    private EditText windows;
    private EditText days;
    private EditText heatLimitC;
    private EditText site;
    private EditText transect;
    private EditText plant;
    private EditText sampleId;
    private EditText sessionId;
    private CustomFields customFields;
    private EditText latitude;
    private EditText longitude;
    private EditText accuracy;
    private boolean gpsFilled; // the position fields show this phone's GPS position: a tap asks first
    private TextView locationText;
    private Button locationButton;
    private Location location; // the saved position (GPS or typed), or null
    private GpsFix gps;        // while searching
    private boolean gpsStopped; // the user stopped the search (not the time limit)
    private int requested;
    private boolean cameraDenied;
    private String problem; // why Start or Aim did not go on, shown above Start until the next tap
    private View problemCard; // the card with that problem
    private Boolean controlsEnabled; // null until first set
    private String lastSession; // from the log; null = read it again
    private String lastWarning; // the last session's warning or early end, or ""

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        scroll = findViewById(R.id.scroll);
        sessionState = findViewById(R.id.session_state);
        sessionStatus = findViewById(R.id.session_status);
        sessionProgress = findViewById(R.id.session_progress);
        sessionWarning = findViewById(R.id.session_warning);
        sessionChecks = findViewById(R.id.session_checks);
        detailsToggle = findViewById(R.id.details_toggle);
        details = findViewById(R.id.details);
        detailsToggle.setOnClickListener(v -> {
            detailsOpen = !detailsOpen;
            showDetailsToggle();
        });
        lastSessionText = findViewById(R.id.last_session);
        battery = findViewById(R.id.battery);
        phoneTime = findViewById(R.id.phone_time);
        storage = findViewById(R.id.storage);
        storagePlaces = findViewById(R.id.storage_places);
        storageTotal = findViewById(R.id.storage_total);
        storagePlaces.setOnCheckedChangeListener((group, id) -> {
            View b = group.findViewById(id);
            if (b == null || buildingPlaces) return;
            Storage.choose(this, (String) b.getTag());
            showStorage();
        });
        findViewById(R.id.open_folder).setOnClickListener(v -> openFolder());
        places = Storage.places(this);
        showPlaces();
        stopButton = findViewById(R.id.stop_session);
        stopButton.setOnClickListener(v -> askToStop());
        stopButton.setText(withIcon(stopButton, R.drawable.ic_stop, R.string.stop_session));
        aimCard = findViewById(R.id.aim_card);
        fieldCard = findViewById(R.id.field_card);
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
        phoneChecks = findViewById(R.id.phone_checks);
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
        videoFps = findViewById(R.id.video_fps);
        stepS = findViewById(R.id.step_s);
        burstS = findViewById(R.id.burst_s);
        savedSide = findViewById(R.id.saved_side);
        clipMin = findViewById(R.id.clip_min);
        breakMin = findViewById(R.id.break_min);
        runH = findViewById(R.id.run_h);
        runM = findViewById(R.id.run_m);
        windows = findViewById(R.id.windows);
        days = findViewById(R.id.days);
        heatLimitC = findViewById(R.id.heat_limit_c);
        site = findViewById(R.id.site);
        transect = findViewById(R.id.transect);
        plant = findViewById(R.id.plant);
        sampleId = findViewById(R.id.sample_id);
        sessionId = findViewById(R.id.session_id);
        customFields = new CustomFields(this, findViewById(R.id.custom_fields), findViewById(R.id.custom_add));
        ((TextView) findViewById(R.id.phone_model)).setText(getString(R.string.phone_model, Build.MANUFACTURER, Build.MODEL));
        ((TextView) findViewById(R.id.phone_id)).setText(FieldInfo.phoneId(this));
        latitude = findViewById(R.id.latitude);
        longitude = findViewById(R.id.longitude);
        accuracy = findViewById(R.id.accuracy);
        TextWatcher typed = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (gps == null) showLocation(null); // GPS or typed by hand
            }
        };
        for (EditText f : new EditText[] {latitude, longitude, accuracy}) {
            f.addTextChangedListener(typed);
            f.setOnClickListener(v -> askToEditPosition((EditText) v)); // only while not focusable
        }
        locationText = findViewById(R.id.location_text);
        locationButton = findViewById(R.id.get_location);
        locationButton.setOnClickListener(v -> getLocation());
        HelpToggles.wire(scroll);
        showSettings(SessionSettings.load(this), true);
        showField(FieldInfo.load(this));
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
        lastSession = null;
        places = Storage.places(this); // a card may have come or gone
        storageUse = null;
        showPlaces();
        countStorage();
        if (CaptureService.sessionRunning) scroll.post(() -> scroll.smoothScrollTo(0, 0)); // started on the aim screen
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        orientationListener.disable();
        handler.removeCallbacks(refresher);
        if (gps != null) { // the GPS is not left on in the background
            gps.cancel();
            locationDone();
            showLocation(getString(R.string.location_stopped));
        }
        try { // keep what was typed, if it is usable
            if (!CaptureService.running) {
                settingsOnScreen().save(this);
                fieldOnScreen().save(this);
            }
        } catch (IllegalArgumentException ignored) {
            // shown at the next Start
        }
        super.onPause();
    }

    // ---- Session settings -------------------------------------------------------------------

    /** Puts the values on the screen; {@code all}: also photos or video (card 1), which "Default
     *  values" keeps. */
    private void showSettings(SessionSettings s, boolean all) {
        if (all) mode.check(s.video ? R.id.mode_video : R.id.mode_photo);
        schedule.check(s.daily ? R.id.schedule_daily : R.id.schedule_run);
        videoFps.check(s.videoFps == 15 ? R.id.video_fps_15 : s.videoFps == 10 ? R.id.video_fps_10 : R.id.video_fps_5);
        stepS.setText(number(s.stepS));
        burstS.setText(number(s.burstS));
        savedSide.setText(String.valueOf(s.savedSidePx));
        clipMin.setText(number(s.clipMin));
        breakMin.setText(number(s.breakMin));
        runH.setText(String.valueOf(s.runMin / 60));
        runM.setText(String.valueOf(s.runMin % 60));
        windows.setText(s.windows);
        days.setText(String.valueOf(s.days));
        heatLimitC.setText(number(s.heatLimitC));
    }

    /** Shows only the settings that apply to the chosen mode and schedule. */
    private void showGroups() {
        boolean video = mode.getCheckedRadioButtonId() == R.id.mode_video;
        boolean daily = schedule.getCheckedRadioButtonId() == R.id.schedule_daily;
        findViewById(R.id.photo_settings).setVisibility(video ? View.GONE : View.VISIBLE);
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

    /** The square as saved by the aim screen, and the focus. */
    private void showAimSummary() {
        SessionSettings s = SessionSettings.load(this);
        String calibration = Camera2Session.fixedFocusCalibration(this); // null: autofocus only
        String focus = !s.focusFixed ? getString(R.string.summary_focus_auto)
                : calibration == null ? getString(R.string.summary_focus_cannot_fix)
                : "calibrated".equals(calibration) || "approximate".equals(calibration)
                        ? getString(R.string.summary_focus_fixed, SessionSettings.distanceWords(s.focusDiopters))
                        : getString(R.string.summary_focus_fixed_position);
        boolean middle = Math.abs(s.squareX - 0.5) < 0.005 && Math.abs(s.squareY - 0.5) < 0.005;
        aimSummary.setText(getString(R.string.aim_summary, s.squarePercent,
                getString(middle ? R.string.aim_square_middle : R.string.aim_square_placed), focus));
    }

    /** The schedule in words, from the values on the screen; or what is wrong with them. */
    private void showScheduleSummary() {
        String text;
        boolean ok = true;
        try {
            SessionSettings s = settingsOnScreen();
            String what = s.video
                    ? s.breakMin > 0 ? getString(R.string.summary_video, s.videoFps, number(s.clipMin), number(s.breakMin))
                            : getString(R.string.summary_video_no_break, s.videoFps, number(s.clipMin))
                    : s.breakMin > 0 ? getString(R.string.summary_photo, number(s.stepS), number(s.burstS), number(s.breakMin))
                            : getString(R.string.summary_photo_no_break, number(s.stepS));
            String when = s.daily ? getString(R.string.summary_daily, s.windows, s.days)
                    : getString(R.string.summary_run, duration(s.runMin));
            text = what + "\n" + when + "\n" + getString(R.string.summary_heat, number(s.heatLimitC));
        } catch (IllegalArgumentException e) {
            text = e.getMessage();
            ok = false;
        }
        if (!text.contentEquals(scheduleSummary.getText())) scheduleSummary.setText(text);
        if (ok) {
            scheduleSummary.setTextColor(bodyColor);
            scheduleSummary.setTypeface(null, Typeface.NORMAL);
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
        int fps = videoFps.getCheckedRadioButtonId();
        s.videoFps = fps == R.id.video_fps_15 ? 15 : fps == R.id.video_fps_10 ? 10 : 5;
        s.stepS = decimal(stepS, R.string.step_label);
        s.burstS = decimal(burstS, R.string.burst_label);
        s.savedSidePx = (int) Math.round(decimal(savedSide, R.string.saved_side_label));
        s.clipMin = decimal(clipMin, R.string.clip_label);
        s.breakMin = decimal(breakMin, R.string.break_label);
        s.runMin = (int) Math.round(wholeOrZero(runH) * 60 + wholeOrZero(runM));
        s.windows = windows.getText().toString().trim();
        s.days = (int) Math.round(decimal(days, R.string.days_label));
        s.heatLimitC = decimal(heatLimitC, R.string.heat_label);
        String p = s.problem();
        if (p != null) throw new IllegalArgumentException(p);
        return s;
    }

    /** Saves the values on the screen; false (and the reason shown) when a value is not usable. */
    private boolean saveSettings() {
        try {
            problemCard = fieldCard;
            FieldInfo f = fieldOnScreen();
            problemCard = scheduleCard;
            try {
                settingsOnScreen().save(this);
            } catch (IllegalArgumentException e) {
                showSettingsGroup(true);
                throw e;
            }
            f.save(this);
            problem = null;
            return true;
        } catch (IllegalArgumentException e) {
            problem = e.getMessage();
            return false;
        } finally {
            refresh();
        }
    }

    /** What still stops Start or Aim (field card first, as in saveSettings), or null. */
    private String currentProblem() {
        try {
            fieldOnScreen();
            settingsOnScreen();
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Why a tap did not go on, in a dialog (the message above Start may be out of sight); OK
     *  scrolls to the card with the problem. */
    private void showProblem() {
        final View card = problemCard;
        new AlertDialog.Builder(this)
                .setTitle(R.string.problem_title)
                .setMessage(problem)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> scroll.smoothScrollTo(0, card.getTop()))
                .show();
    }

    /** "1 h 30 min", "2 h", "45 min". */
    private static String duration(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        return h == 0 ? m + " min" : m == 0 ? h + " h" : h + " h " + m + " min";
    }

    /** A number, or 0 for an empty field (for example no minutes). */
    private double wholeOrZero(EditText field) {
        return field.getText().toString().trim().isEmpty() ? 0 : decimal(field, R.string.run_label);
    }

    // ---- Field metadata ---------------------------------------------------------------------

    private void showField(FieldInfo f) {
        site.setText(f.site);
        transect.setText(f.transect);
        plant.setText(f.plant);
        sampleId.setText(f.sampleId);
        sessionId.setText(f.sessionId);
        customFields.show(f.custom);
        showPosition(f.location);
    }

    /** Puts a position into the latitude, longitude and accuracy fields (empty for none). A GPS
     *  position is guarded: a tap on its fields asks before they can be changed. */
    private void showPosition(Location l) {
        location = l;
        latitude.setText(l == null ? "" : FieldInfo.degrees(l.getLatitude()));
        longitude.setText(l == null ? "" : FieldInfo.degrees(l.getLongitude()));
        accuracy.setText(l == null ? "" : accuracyText(l));
        guardPosition(l != null && FieldInfo.fromGps(l));
        showLocation(null);
    }

    /** The accuracy in m as the field shows it: one decimal, as in the record ("" when not given). */
    private static String accuracyText(Location l) {
        return l.hasAccuracy() ? number(Math.round(l.getAccuracy() * 10) / 10.0) : "";
    }

    /** {@code on}: the position fields take no typing; a tap asks first (askToEditPosition), so a
     *  touch while scrolling cannot change a GPS position by accident. */
    private void guardPosition(boolean on) {
        gpsFilled = on;
        for (EditText f : new EditText[] {latitude, longitude, accuracy}) {
            f.setFocusable(!on);
            f.setFocusableInTouchMode(!on);
        }
    }

    private void askToEditPosition(final EditText field) {
        if (!gpsFilled) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.position_edit_title)
                .setMessage(R.string.position_edit_text)
                .setPositiveButton(R.string.position_edit_yes, (dialog, which) -> {
                    guardPosition(false);
                    field.requestFocus();
                    field.setSelection(field.length());
                    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                            .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
                })
                .setNegativeButton(R.string.position_edit_no, null)
                .show();
    }

    /** The field metadata on the screen; throws with a plain-language message when the position is
     *  not usable. */
    private FieldInfo fieldOnScreen() {
        FieldInfo f = new FieldInfo();
        f.site = text(site);
        f.transect = text(transect);
        f.plant = text(plant);
        f.sampleId = text(sampleId);
        f.sessionId = text(sessionId);
        f.custom = customFields.read();
        f.location = locationOnScreen();
        String p = f.problem();
        if (p != null) throw new IllegalArgumentException(p);
        return f;
    }

    private static String text(EditText field) {
        return field.getText().toString().trim();
    }

    /** The position in the fields: the GPS position while they still show it, else typed by hand;
     *  null when all are empty. Throws with a plain-language message when it is not usable. */
    private Location locationOnScreen() {
        String lat = text(latitude);
        String lon = text(longitude);
        String acc = text(accuracy);
        if (lat.isEmpty() && lon.isEmpty() && acc.isEmpty()) return null;
        if (lat.isEmpty() || lon.isEmpty()) throw new IllegalArgumentException(getString(R.string.location_both));
        double la = decimal(latitude, R.string.field_latitude);
        double lo = decimal(longitude, R.string.field_longitude);
        Float ac = acc.isEmpty() ? null : (float) decimal(accuracy, R.string.field_accuracy);
        String p = FieldInfo.positionProblem(la, lo, ac);
        if (p != null) throw new IllegalArgumentException(p);
        if (location != null && lat.equals(FieldInfo.degrees(location.getLatitude()))
                && lon.equals(FieldInfo.degrees(location.getLongitude())) && acc.equals(accuracyText(location))) {
            return location;
        }
        return FieldInfo.typed(la, lo, ac);
    }

    /** Where the position in the fields comes from, or what is wrong with it; after {@code note}
     *  (null: none). */
    private void showLocation(String note) {
        String text;
        boolean ok = true;
        try {
            Location l = locationOnScreen();
            if (l == null) {
                text = getString(R.string.location_none);
            } else if (!FieldInfo.fromGps(l)) {
                text = getString(R.string.location_typed);
            } else {
                text = getString(R.string.location_saved,
                        new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(l.getTime())));
                if (l.getAccuracy() > GpsFix.GOOD_M) text += " " + getString(R.string.location_weak);
            }
        } catch (IllegalArgumentException e) {
            text = e.getMessage();
            ok = false;
        }
        locationText.setText(note == null ? text : note + "\n" + text);
        // A problem shows at once, in the warning colour and bold (also without colour).
        locationText.setTextColor(ok ? bodyColor : ColorStateList.valueOf(getColor(R.color.warning)));
        locationText.setTypeface(null, ok ? Typeface.NORMAL : Typeface.BOLD);
    }

    /** Get location: the GPS runs until the position is good enough (GpsFix); a second tap stops it
     *  and keeps the best position so far. */
    private void getLocation() {
        if (gps != null) {
            gpsStopped = true;
            gps.stop();
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Both: Android 12+ then offers "precise" or "approximate"; the GPS needs precise.
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
            return;
        }
        if (!GpsFix.gpsOn(this)) {
            showLocation(getString(R.string.location_off));
            openPage(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        gps = new GpsFix(this, new GpsFix.Listener() {
            @Override
            public void onSearching(Location best, long elapsedMs) {
                String text = getString(R.string.location_searching, elapsedMs / 1000, GpsFix.TIMEOUT_MS / 1000);
                // From the first second, so the wait has a reason; its own line, as the seconds before
                // it would move it at each tick.
                text += "\n" + (best == null ? getString(R.string.location_best_none)
                        : getString(R.string.location_best, Math.round(best.getAccuracy())));
                locationText.setText(text);
            }

            @Override
            public void onDone(Location best) {
                locationDone();
                if (best == null) {
                    showLocation(gpsStopped ? getString(R.string.location_cancelled)
                            : getString(R.string.location_failed, GpsFix.TIMEOUT_MS / 1000));
                    return;
                }
                showPosition(best);
                FieldInfo saved = FieldInfo.load(MainActivity.this);
                saved.location = best;
                saved.save(MainActivity.this);
            }
        });
        gpsStopped = false;
        gps.start();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // a screen timeout would stop it
        locationButton.setText(R.string.location_stop);
    }

    private void locationDone() {
        gps = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        locationButton.setText(R.string.location_get);
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

    /** Counts what each FaunaLapse folder holds, on a second thread: many files take a moment. */
    private void countStorage() {
        final List<Storage.Place> counted = places;
        new Thread(() -> {
            long[][] use = new long[counted.size()][];
            for (int i = 0; i < use.length; i++) {
                File[] sessions = counted.get(i).dir.listFiles(File::isDirectory);
                use[i] = new long[] {0, sessions == null ? 0 : sessions.length};
                if (sessions != null) for (File f : sessions) use[i][0] += bytes(f);
            }
            handler.post(() -> {
                if (counted != places) return; // listed again meanwhile
                storageUse = use;
                showStorage();
            });
        }, "storage").start();
    }

    private static long bytes(File f) {
        File[] inside = f.listFiles();
        if (inside == null) return f.length();
        long sum = 0;
        for (File g : inside) sum += bytes(g);
        return sum;
    }

    /** One button per place (shown only when there is a memory card). */
    private void showPlaces() {
        buildingPlaces = true;
        storagePlaces.removeAllViews();
        String chosen = Storage.chosen(this, places).key;
        for (Storage.Place p : places) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setTag(p.key);
            b.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
            b.setTextSize(15);
            storagePlaces.addView(b);
            if (p.key.equals(chosen)) storagePlaces.check(b.getId());
        }
        buildingPlaces = false;
    }

    /** Where the files go, what FaunaLapse uses there and what is free; with a memory card, each
     *  place to choose from and the total. During a session: its folder and what it saved so far.
     *  Reading the free space costs almost nothing; the folders are counted only when the screen
     *  opens and after a session. UsableSpace: leaves out caches Android could clear, a cautious
     *  number (as SessionRunner). */
    @SuppressLint("UsableSpace")
    private void showStorage() {
        File folder = SessionRunner.folder;
        boolean session = CaptureService.sessionRunning && folder != null;
        storagePlaces.setVisibility(session || places.size() < 2 ? View.GONE : View.VISIBLE);
        if (session) {
            show(storage, getString(R.string.storage_session, Storage.shownPath(this, places, folder),
                    size(SessionRunner.savedBytes.get()), size(folder.getUsableSpace())));
            show(storageTotal, "");
            return;
        }
        Storage.Place chosen = Storage.chosen(this, places);
        int c = places.indexOf(chosen);
        String path = Storage.shownPath(this, places, chosen.dir);
        String free = size(chosen.dir.getUsableSpace());
        String text;
        if (places.size() > 1) {
            text = getString(R.string.storage_where, path);
        } else {
            text = storageUse == null ? getString(R.string.storage_counting, path, free)
                    : getString(R.string.storage_line, path, size(storageUse[c][0]), sessions(storageUse[c][1]), free);
        }
        if (chosen.appFolder) {
            text += " " + getString(chosen.key.equals(Storage.PHONE) ? R.string.storage_app_folder
                    : R.string.storage_card_app_folder);
        }
        if (Storage.missingChoice(this, places)) text = getString(R.string.storage_missing) + " " + text;
        show(storage, text);
        if (places.size() < 2) {
            show(storageTotal, "");
            return;
        }
        long[] total = {0, 0};
        for (int i = 0; i < places.size(); i++) {
            Storage.Place p = places.get(i);
            RadioButton b = (RadioButton) storagePlaces.getChildAt(i);
            String line = storageUse == null
                    ? getString(R.string.storage_place_counting, p.name, size(p.dir.getUsableSpace()))
                    : getString(R.string.storage_place, p.name, size(storageUse[i][0]), sessions(storageUse[i][1]),
                            size(p.dir.getUsableSpace()));
            if (!line.contentEquals(b.getText())) b.setText(line);
            if (storageUse != null) {
                total[0] += storageUse[i][0];
                total[1] += storageUse[i][1];
            }
        }
        show(storageTotal, storageUse == null ? "" : getString(R.string.storage_total, size(total[0]), sessions(total[1])));
    }

    private String size(long bytes) {
        return Formatter.formatShortFileSize(this, bytes);
    }

    private String sessions(long n) {
        return getResources().getQuantityString(R.plurals.sessions, (int) n, (int) n);
    }

    /** Opens the folder of the running session, or of the chosen place, in Android's file app. */
    private void openFolder() {
        File dir = CaptureService.sessionRunning && SessionRunner.folder != null ? SessionRunner.folder
                : Storage.chosen(this, places).dir;
        String id = Storage.documentId(places, dir);
        try {
            if (id == null) throw new ActivityNotFoundException();
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(
                    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", id),
                    DocumentsContract.Document.MIME_TYPE_DIR));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, getString(R.string.open_folder_failed, Storage.shownPath(this, places, dir)),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startSession() {
        if (!saveSettings()) {
            showProblem();
            return;
        }
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
            // Optional values, for example: --es mode video --es clip_min 0.5 --es run_min 6
            String m = intent.getStringExtra("mode");
            if (m != null) mode.check("video".equals(m) ? R.id.mode_video : R.id.mode_photo);
            String sc = intent.getStringExtra("schedule");
            if (sc != null) schedule.check("daily".equals(sc) ? R.id.schedule_daily : R.id.schedule_run);
            String fps = intent.getStringExtra("video_fps");
            if (fps != null) {
                videoFps.check("15".equals(fps) ? R.id.video_fps_15 : "10".equals(fps) ? R.id.video_fps_10 : R.id.video_fps_5);
            }
            setFromExtra(intent, "step_s", stepS);
            setFromExtra(intent, "burst_s", burstS);
            setFromExtra(intent, "saved_side_px", savedSide);
            setFromExtra(intent, "clip_min", clipMin);
            setFromExtra(intent, "break_min", breakMin);
            String run = intent.getStringExtra("run_min");
            if (run != null) {
                runH.setText(String.valueOf(Integer.parseInt(run) / 60));
                runM.setText(String.valueOf(Integer.parseInt(run) % 60));
            }
            setFromExtra(intent, "windows", windows);
            setFromExtra(intent, "days", days);
            setFromExtra(intent, "heat_limit_c", heatLimitC);
            // The square has no field on this screen: save it, settingsOnScreen then loads it.
            SessionSettings saved = SessionSettings.load(this);
            saved.squarePercent = (int) Math.round(fromExtra(intent, "square_percent", saved.squarePercent));
            saved.squareX = fromExtra(intent, "square_x", saved.squareX);
            saved.squareY = fromExtra(intent, "square_y", saved.squareY);
            if (intent.hasExtra("focus_diopters")) { // below 0: autofocus
                double d = fromExtra(intent, "focus_diopters", -1);
                saved.focusFixed = d >= 0;
                if (d >= 0) saved.focusDiopters = d;
            }
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
        if (what == AIM && !saveSettings()) { // the aim screen and its Start use the saved values
            showProblem();
            return;
        }
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
        if (requestCode == LOCATION_REQUEST) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                getLocation();
            } else {
                showLocation(getString(R.string.location_permission));
            }
            return;
        }
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
        if (session) {
            show(sessionState, getString(SessionRunner.heatPaused ? R.string.status_paused : R.string.status_session));
            show(sessionStatus, SessionRunner.status);
            show(sessionProgress, SessionRunner.progress);
            show(sessionWarning, SessionRunner.warning);
        } else if (running) {
            show(sessionState, getString(R.string.status_test));
            show(sessionStatus, getString(R.string.status_running, CaptureService.START_DELAY_MS / 1000));
            show(sessionProgress, "");
            show(sessionWarning, "");
        } else { // nothing runs: the last session folds away, its warning stays
            if (lastSession == null) readLastSession();
            show(sessionState, getString(R.string.status_idle));
            show(sessionStatus, "");
            show(sessionProgress, "");
            show(sessionWarning, lastWarning);
        }
        show(lastSessionText, running ? "" : lastSession);
        showDetailsToggle();
        if (running) lastSession = null; // read again when it has ended
        if (wasRunning && !running) countStorage();
        wasRunning = running;
        showStorage();
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
        showPhoneCheck(session);
        if (problem != null && !running) problem = currentProblem(); // gone once it is corrected
        String message = cameraDenied ? getString(R.string.camera_permission_needed) : problem;
        show(startMessage, message == null ? "" : message);
        if (testsGroup.getVisibility() == View.VISIBLE) showTestStatus(running && !session);
    }

    /** Settings and buttons can be changed only while nothing runs (the camera is busy). */
    private void setControlsEnabled(boolean enabled) {
        if (controlsEnabled != null && controlsEnabled == enabled) return;
        controlsEnabled = enabled;
        for (View card : new View[] {aimCard, fieldCard, scheduleCard}) {
            setEnabled(card, enabled);
            card.setAlpha(enabled ? 1 : 0.5f);
        }
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

    /** The line that opens and closes the last session, battery and clock, with an arrow after it. */
    private void showDetailsToggle() {
        String words = getString(CaptureService.running ? R.string.details_running : R.string.details_idle);
        if ((words + detailsOpen).equals(detailsShown)) return;
        detailsShown = words + detailsOpen;
        details.setVisibility(detailsOpen ? View.VISIBLE : View.GONE);
        Drawable d = getDrawable(detailsOpen ? R.drawable.ic_less : R.drawable.ic_more);
        int size = Math.round(detailsToggle.getTextSize());
        d.setBounds(0, 0, size, size);
        SpannableStringBuilder s = new SpannableStringBuilder(words).append("  ");
        s.setSpan(new ImageSpan(d, ImageSpan.ALIGN_BASELINE), s.length() - 1, s.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        detailsToggle.setText(s);
        detailsToggle.setContentDescription(words + ". " + getString(detailsOpen ? R.string.help_hide : R.string.help_show));
    }

    /** Sets the text, and hides the view while there is none. */
    private static void show(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** What to change on the phone before a field session (PhoneCheck), each with the button that
     *  opens its settings page; apps cannot change these settings themselves. During a session the
     *  lines move into the status card (only what should change), so something forgotten shows
     *  there. BatteryLife: Google Play allows the battery dialog only for some kinds of apps;
     *  FaunaLapse is not on Google Play, and its core work (long sessions with the screen off) is
     *  what the battery limits stop. */
    @SuppressLint("BatteryLife")
    private void showPhoneCheck(boolean session) {
        PhoneCheck c = PhoneCheck.read(this);
        String state = session + c.toJson().toString();
        if (state.equals(phoneState)) return;
        phoneState = state;
        phoneChecks.removeAllViews();
        sessionChecks.removeAllViews();
        checks = session ? sessionChecks : phoneChecks;
        if (c.ready()) {
            if (!session) addCheck(getString(R.string.check_ok), 0, null);
            return;
        }
        List<String> radios = new ArrayList<>();
        if (!c.airplaneMode) radios.add(getString(R.string.radio_mobile));
        if (c.wifiOn) radios.add(getString(R.string.radio_wifi));
        if (c.bluetoothOn) radios.add(getString(R.string.radio_bluetooth));
        if (!radios.isEmpty()) {
            addCheck(getString(R.string.check_radios, TextUtils.join(", ", radios)), R.string.check_radios_button,
                    new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
        }
        if (c.locationOn) {
            addCheck(getString(R.string.check_location), R.string.check_location_button,
                    new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        }
        Uri app = Uri.parse("package:" + getPackageName());
        if (c.batteryLimited) {
            addCheck(getString(R.string.check_battery), R.string.check_battery_button,
                    new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app));
        }
        if (c.backgroundRestricted) {
            addCheck(getString(R.string.check_background), R.string.check_background_button,
                    new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app));
        }
    }

    /** One line of the phone check; {@code button} 0: text only. */
    private void addCheck(String text, int button, final Intent page) {
        View line = getLayoutInflater().inflate(R.layout.phone_check, checks, false);
        ((TextView) line.findViewById(R.id.check_text)).setText(text);
        Button b = line.findViewById(R.id.check_button);
        if (button == 0) {
            b.setVisibility(View.GONE);
        } else {
            b.setText(button);
            b.setOnClickListener(v -> openPage(page));
        }
        checks.addView(line);
    }

    private void openPage(Intent page) {
        try {
            startActivity(page);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS)); // a phone without that page
        }
    }

    /** The last session from the log (it also survives a restart of the app), in words; its
     *  warning or early end apart, as it stays visible. */
    private void readLastSession() {
        JSONObject last = null;
        for (String line : lines(CaptureService.logFile(this))) {
            try {
                JSONObject record = new JSONObject(line);
                if ("session".equals(record.optString("test"))) last = record;
            } catch (JSONException ignored) {
                // not a record
            }
        }
        lastWarning = "";
        if (last == null) {
            lastSession = getString(R.string.no_session);
            return;
        }
        String text = getString(R.string.last_session, last.optString("file"), last.optString("counts"),
                last.optString("reason"));
        String battery = last.optString("battery");
        lastSession = battery.isEmpty() ? text : text + " " + battery;
        List<String> problems = new ArrayList<>();
        if ("error".equals(last.optString("result"))) {
            problems.add(getString(R.string.last_session_early, last.optString("reason")));
        }
        if (!last.optString("warning").isEmpty()) problems.add(last.optString("warning"));
        if (!problems.isEmpty()) lastWarning = getString(R.string.last_session_warning, TextUtils.join(" ", problems));
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
