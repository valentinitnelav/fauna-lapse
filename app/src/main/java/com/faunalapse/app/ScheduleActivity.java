package com.faunalapse.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

/**
 * Schedule screen (card 3 of the main screen): bursts or clips, breaks, a run or daily windows, the
 * saved size, the heat pause and the screen off (SessionSettings). Photos or video is chosen on
 * card 1, the square and focus on the aim screen. Its own screen, so the main screen stays short
 * (maintainer, 2026-10-11). Done and Back save and go back; a value that is not usable is shown first.
 */
public final class ScheduleActivity extends Activity {

    private boolean video; // card 1: only the settings for photos or for video show
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
    private EditText screenOffMin;
    private TextView screenOffNote;
    private boolean discard; // left without saving: onPause saves nothing

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_schedule);
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
        screenOffMin = findViewById(R.id.screen_off_min);
        screenOffNote = findViewById(R.id.screen_off_note);
        SessionSettings saved = SessionSettings.load(this);
        video = saved.video;
        ((TextView) findViewById(R.id.mode_note)).setText(video ? R.string.schedule_for_video : R.string.schedule_for_photos);
        findViewById(R.id.photo_settings).setVisibility(video ? View.GONE : View.VISIBLE);
        findViewById(R.id.video_settings).setVisibility(video ? View.VISIBLE : View.GONE);
        showSettings(saved);
        schedule.setOnCheckedChangeListener((group, id) -> showGroups());
        showGroups();
        screenOffMin.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                showScreenOffNote();
            }
        });
        findViewById(R.id.defaults).setOnClickListener(v -> showSettings(new SessionSettings()));
        findViewById(R.id.done).setOnClickListener(v -> leave());
        if (Build.VERSION.SDK_INT >= 33) { // Android 16 calls this instead of onBackPressed
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::leave);
        }
        HelpToggles.wire(findViewById(R.id.schedule_root));
    }

    /** Back on Android 7 to 15; Android 16 calls the callback set in onCreate instead. */
    @Override
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        leave();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showScreenOffNote(); // the Screen off right may have changed
    }

    @Override
    protected void onPause() {
        if (!discard) {
            try { // keep what was typed, if it is usable
                settingsOnScreen().save(this);
            } catch (IllegalArgumentException ignored) {
                // shown on Done or Back
            }
        }
        super.onPause();
    }

    /** Done or Back: saves and goes back; a value that is not usable first, with the choice to
     *  correct it or leave without saving. */
    private void leave() {
        try {
            settingsOnScreen().save(this);
            finish();
        } catch (IllegalArgumentException e) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.problem_title)
                    .setMessage(getString(R.string.two_paragraphs, e.getMessage(), getString(R.string.leave_note)))
                    .setPositiveButton(R.string.leave_correct, null)
                    .setNegativeButton(R.string.leave_anyway, (dialog, which) -> {
                        discard = true;
                        finish();
                    })
                    .show();
        }
    }

    /** Puts the values on the screen (photos or video stays as chosen on card 1). */
    private void showSettings(SessionSettings s) {
        schedule.check(s.daily ? R.id.schedule_daily : R.id.schedule_run);
        videoFps.check(s.videoFps == 15 ? R.id.video_fps_15 : s.videoFps == 10 ? R.id.video_fps_10 : R.id.video_fps_5);
        stepS.setText(MainActivity.number(s.stepS));
        burstS.setText(MainActivity.number(s.burstS));
        savedSide.setText(String.valueOf(s.savedSidePx));
        clipMin.setText(MainActivity.number(s.clipMin));
        breakMin.setText(MainActivity.number(s.breakMin));
        runH.setText(String.valueOf(s.runMin / 60));
        runM.setText(String.valueOf(s.runMin % 60));
        windows.setText(s.windows);
        days.setText(String.valueOf(s.days));
        heatLimitC.setText(MainActivity.number(s.heatLimitC));
        screenOffMin.setText(String.valueOf(s.screenOffMin));
    }

    /** Shows only the settings of the chosen schedule. */
    private void showGroups() {
        boolean daily = schedule.getCheckedRadioButtonId() == R.id.schedule_daily;
        findViewById(R.id.run_settings).setVisibility(daily ? View.GONE : View.VISIBLE);
        findViewById(R.id.daily_settings).setVisibility(daily ? View.VISIBLE : View.GONE);
    }

    /** The settings on the screen (photos or video, the square and focus as saved). Throws with a
     *  plain-language message when a value is not usable. */
    private SessionSettings settingsOnScreen() {
        SessionSettings s = SessionSettings.load(this);
        s.daily = schedule.getCheckedRadioButtonId() == R.id.schedule_daily;
        int fps = videoFps.getCheckedRadioButtonId();
        s.videoFps = fps == R.id.video_fps_15 ? 15 : fps == R.id.video_fps_10 ? 10 : 5;
        s.stepS = MainActivity.decimal(this, stepS, R.string.step_label);
        s.burstS = MainActivity.decimal(this, burstS, R.string.burst_label);
        s.savedSidePx = (int) Math.round(MainActivity.decimal(this, savedSide, R.string.saved_side_label));
        s.clipMin = MainActivity.decimal(this, clipMin, R.string.clip_label);
        s.breakMin = MainActivity.decimal(this, breakMin, R.string.break_label);
        s.runMin = (int) Math.round(wholeOrZero(runH) * 60 + wholeOrZero(runM));
        s.windows = windows.getText().toString().trim();
        s.days = (int) Math.round(MainActivity.decimal(this, days, R.string.days_label));
        s.heatLimitC = MainActivity.decimal(this, heatLimitC, R.string.heat_label);
        s.screenOffMin = (int) Math.round(MainActivity.decimal(this, screenOffMin, R.string.screen_off_label));
        String p = s.problem();
        if (p != null) throw new IllegalArgumentException(p);
        return s;
    }

    /** A number, or 0 for an empty field (for example no minutes). */
    private double wholeOrZero(EditText field) {
        return field.getText().toString().trim().isEmpty() ? 0 : MainActivity.decimal(this, field, R.string.run_label);
    }

    /** Under the setting, when FaunaLapse will not switch the screen off: what does it instead. */
    private void showScreenOffNote() {
        Double minutes = FieldInfo.Custom.number(MainActivity.text(screenOffMin));
        String text = minutes == null ? ""
                : minutes == 0 ? getString(R.string.screen_off_note_never, phoneTimeout())
                : !ScreenOff.allowed(this) ? getString(R.string.screen_off_note_right, phoneTimeout()) : "";
        MainActivity.show(screenOffNote, text);
    }

    /** The phone's own screen timeout in words (Android settings, Display). */
    private String phoneTimeout() {
        int ms = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, -1);
        if (ms <= 0) return "?";
        if (ms >= 24 * 3600000) return getString(R.string.timeout_never); // some phones: "never"
        return ms < 60000 ? ms / 1000 + " s" : MainActivity.duration(Math.round(ms / 60000f));
    }
}
