package com.faunalapse.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.text.style.ImageSpan;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
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
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Main screen: a status card (what runs now, where the files go; the last session, battery and
 * clock fold away), then the steps of a setup: 1 aim (photos or video, the aim screen), 2 field
 * metadata and 3 schedule (a summary of the saved values; "Change" opens FieldActivity or
 * ScheduleActivity), 4 the phone for the field (PhoneCheck), then Start and Support. The phase 0
 * test buttons are folded away at the bottom.
 */
public final class MainActivity extends Activity {

    /** Test hooks for adb, only in debug builds (see AGENTS.md, "Build and test"). */
    private static final String EXTRA_TAKE_PHOTO = "take_photo";
    private static final String EXTRA_START_SESSION = "start_session";
    private static final String EXTRA_STOP_SESSION = "stop_session";
    private static final String EXTRA_PACK = "pack"; // pack the photo sessions (SessionZip)
    private static final String EXTRA_AIM = "aim";
    private static final String EXTRA_CAMERA1 = "camera1";
    private static final int PERMISSION_REQUEST = 1;
    private static final int SCREEN_OFF_REQUEST = 3;
    /** The last photo view is read again at most this often: a little work for the processor. */
    private static final long LAST_PHOTO_MS = 3000;
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
    private View storageNote; // memory card and short photo steps: test first
    private List<Storage.Place> places; // listed when the screen opens
    private boolean buildingPlaces; // the place buttons are being made: not a choice
    private long[][] storageUse; // per place: bytes and sessions in its FaunaLapse folder; null until counted
    private long[] unpacked; // photo sessions not packed (SessionZip): number and bytes; null until counted
    private View packGroup;
    private TextView packStatus;
    private Button packButton;
    private boolean wasPacking;
    private boolean wasRunning;
    private boolean wasSession;
    private long lastTouchMs;        // elapsedRealtime of the last touch on this screen
    private int sessionScreenOffMin; // screen off by itself in the running session (0: never)
    private Button stopButton;
    private Button pauseButton;
    private Button adjustButton;
    private Button continueButton;
    private ImageView lastPhoto;
    private TextView lastPhotoText;
    private File lastShown;      // the file the last photo view shows, or is being read
    private long lastShownAt;    // when it was read: at most one every LAST_PHOTO_MS
    private Button screenOffButton;
    private View screenOffRight;
    private View aimCard;
    private View fieldCard;
    private View scheduleCard;
    private Button aimButton;
    private TextView aimSummary;
    private TextView scheduleSummary;
    private ColorStateList bodyColor;
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
    private TextView fieldSummary;
    private int requested;
    private static boolean optionalAsked; // notifications or storage asked in this app run (missingPermissions)
    private boolean cameraDenied;
    private String problem; // why Start or Aim did not go on, shown above Start until the next tap
    private Class<? extends Activity> problemScreen; // the screen to correct that problem on
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
        storageNote = findViewById(R.id.storage_note);
        storagePlaces.setOnCheckedChangeListener((group, id) -> {
            View b = group.findViewById(id);
            if (b == null || buildingPlaces) return;
            Storage.choose(this, (String) b.getTag());
            showStorage();
        });
        findViewById(R.id.open_folder).setOnClickListener(v -> openFolder());
        packGroup = findViewById(R.id.pack_group);
        packStatus = findViewById(R.id.pack_status);
        packButton = findViewById(R.id.pack);
        packButton.setOnClickListener(v -> {
            if (SessionZip.running()) SessionZip.last.stop = true;
            else if (!CaptureService.running) pack(); // never a folder a session still writes to
            refresh();
        });
        screenOffButton = findViewById(R.id.screen_off);
        screenOffButton.setOnClickListener(v -> screenOff());
        screenOffRight = findViewById(R.id.screen_off_right);
        findViewById(R.id.screen_off_remove).setOnClickListener(v -> {
            ScreenOff.remove(this);
            screenOffRight.setVisibility(View.GONE);
        });
        places = Storage.places(this);
        showPlaces();
        stopButton = findViewById(R.id.stop_session);
        stopButton.setOnClickListener(v -> askToStop());
        stopButton.setText(withIcon(stopButton, R.drawable.ic_stop, R.string.stop_session));
        pauseButton = findViewById(R.id.pause_session);
        pauseButton.setOnClickListener(v -> {
            pauseButton.setEnabled(false); // until the session has paused
            CaptureService.pauseSession(this, true, phoneTurn);
        });
        adjustButton = findViewById(R.id.adjust_camera);
        adjustButton.setOnClickListener(v -> startActivity(new Intent(this, AimActivity.class)));
        continueButton = findViewById(R.id.continue_session);
        continueButton.setText(withIcon(continueButton, R.drawable.ic_start, R.string.continue_session));
        continueButton.setOnClickListener(v -> {
            continueButton.setEnabled(false);
            CaptureService.pauseSession(this, false, phoneTurn);
        });
        lastPhoto = findViewById(R.id.last_photo);
        lastPhotoText = findViewById(R.id.last_photo_text);
        lastPhoto.setOnClickListener(v -> showLastPhotoLarge());
        aimCard = findViewById(R.id.aim_card);
        fieldCard = findViewById(R.id.field_card);
        scheduleCard = findViewById(R.id.schedule_card);
        aimButton = findViewById(R.id.aim);
        aimButton.setOnClickListener(v -> start(AIM));
        aimSummary = findViewById(R.id.aim_summary);
        scheduleSummary = findViewById(R.id.schedule_summary);
        bodyColor = scheduleSummary.getTextColors();
        findViewById(R.id.change).setOnClickListener(v -> startActivity(new Intent(this, ScheduleActivity.class)));
        fieldSummary = findViewById(R.id.field_summary);
        findViewById(R.id.field_change).setOnClickListener(v -> startActivity(new Intent(this, FieldActivity.class)));
        findViewById(R.id.share).setOnClickListener(v -> Support.share(this));
        findViewById(R.id.cite).setOnClickListener(v -> Support.cite(this));
        findViewById(R.id.report).setOnClickListener(v -> Support.report(this));
        phoneChecks = findViewById(R.id.phone_checks);
        startMessage = findViewById(R.id.start_message);
        startButton = findViewById(R.id.start_session);
        startButton.setOnClickListener(v -> startSession());
        startButton.setText(withIcon(startButton, R.drawable.ic_start, R.string.start_session));
        startHint = findViewById(R.id.start_hint);
        testsToggle = findViewById(R.id.tests_toggle);
        testsGroup = findViewById(R.id.tests);
        // The phone tests measure a new phone model's camera for the developers; sessions do not
        // need them, so only debug builds (installed with adb) show them.
        testsToggle.setVisibility(debugBuild() ? View.VISIBLE : View.GONE);
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
        mode.check(SessionSettings.load(this).video ? R.id.mode_video : R.id.mode_photo);
        mode.setOnCheckedChangeListener((group, id) -> {
            SessionSettings s = SessionSettings.load(this); // saved at once: the other screens use it
            s.video = id == R.id.mode_video;
            s.save(this);
            showScheduleSummary();
        });
        HelpToggles.wire(scroll);
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
        showFieldSummary(); // the field metadata and schedule screens may have saved new values
        showScheduleSummary();
        lastSession = null;
        places = Storage.places(this); // a card may have come or gone
        storageUse = null;
        showPlaces();
        countStorage();
        if (CaptureService.sessionRunning) scroll.post(() -> scroll.smoothScrollTo(0, 0)); // started on the aim screen
        lastTouchMs = SystemClock.elapsedRealtime(); // coming back counts as a touch
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        orientationListener.disable();
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    // ---- Cards 1 to 3: what is saved, in short ----------------------------------------------------

    /** The square as saved by the aim screen, and the focus. */
    private void showAimSummary() {
        SessionSettings s = SessionSettings.load(this);
        String calibration = Camera2Session.fixedFocusCalibration(this); // null: autofocus only
        // Android hides the lens range until the camera is allowed (first start); Camera1 phones never fix it.
        boolean known = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                || !SessionRunner.useCamera2(this);
        String focus = !s.focusFixed ? getString(R.string.summary_focus_auto)
                : !known ? getString(R.string.summary_focus_unchecked, SessionSettings.distanceWords(s.focusDiopters))
                : calibration == null ? getString(R.string.summary_focus_cannot_fix)
                : "calibrated".equals(calibration) || "approximate".equals(calibration)
                        ? getString(R.string.summary_focus_fixed, SessionSettings.distanceWords(s.focusDiopters))
                        : getString(R.string.summary_focus_fixed_position);
        boolean middle = Math.abs(s.squareX - 0.5) < 0.005 && Math.abs(s.squareY - 0.5) < 0.005;
        aimSummary.setText(getString(R.string.aim_summary, s.squarePercent,
                getString(middle ? R.string.aim_square_middle : R.string.aim_square_placed), focus));
    }

    /** The schedule in words (the schedule screen saves it), or what is wrong with it. */
    private void showScheduleSummary() {
        SessionSettings s = SessionSettings.load(this);
        String text = s.problem();
        boolean ok = text == null;
        if (ok) {
            String what = s.video
                    ? s.breakMin > 0 ? getString(R.string.summary_video, s.videoFps, number(s.clipMin), number(s.breakMin))
                            : getString(R.string.summary_video_no_break, s.videoFps, number(s.clipMin))
                    : s.breakMin > 0 ? getString(R.string.summary_photo, number(s.stepS), number(s.burstS), number(s.breakMin))
                            : getString(R.string.summary_photo_no_break, number(s.stepS));
            String when = s.daily ? getString(R.string.summary_daily, s.windows, s.days)
                    : getString(R.string.summary_run, duration(s.runMin));
            text = what + "\n" + when + "\n" + getString(R.string.summary_heat, number(s.heatLimitC)) + " "
                    + (s.screenOffMin > 0 ? getString(R.string.summary_screen_off, s.screenOffMin)
                            : getString(R.string.summary_screen_off_never));
        }
        if (!text.contentEquals(scheduleSummary.getText())) scheduleSummary.setText(text);
        warn(scheduleSummary, !ok);
    }

    /** What is filled in (so values left from the last site show before Start), or what is wrong. */
    private void showFieldSummary() {
        FieldInfo f = FieldInfo.load(this);
        if (f.distanceFromFocus) f.distance = FieldInfo.focusDistance(this);
        String text = f.problem();
        boolean ok = text == null;
        if (ok) {
            List<String> lines = new ArrayList<>();
            line(lines, R.string.field_setup_by, f.setupBy);
            line(lines, R.string.field_site, f.site);
            line(lines, R.string.field_transect, f.transect);
            line(lines, R.string.field_plant, f.plant);
            line(lines, R.string.field_habitat, f.habitat);
            line(lines, R.string.field_sample, f.sampleId);
            line(lines, R.string.field_session, f.sessionId);
            line(lines, R.string.field_height, f.cameraHeight);
            line(lines, R.string.field_distance, f.distance);
            for (FieldInfo.Custom c : f.custom) {
                String v = c.value.replace('\n', ' ');
                if (!v.isEmpty()) lines.add(getString(R.string.field_summary_line, c.name,
                        v.length() > 40 ? v.substring(0, 39) + "…" : v));
            }
            boolean empty = lines.isEmpty();
            Location l = f.location;
            lines.add(l == null ? getString(R.string.field_summary_no_position)
                    : getString(R.string.field_summary_position, FieldInfo.degrees(l.getLatitude()),
                            FieldInfo.degrees(l.getLongitude()),
                            l.hasAccuracy() ? getString(R.string.field_summary_uncertainty, FieldActivity.accuracyText(l)) : "",
                            getString(FieldInfo.fromGps(l) ? R.string.field_summary_gps : R.string.field_summary_typed)));
            int photos = SitePhotos.waiting(this).size();
            if (photos > 0) lines.add(getResources().getQuantityString(R.plurals.field_summary_photos, photos, photos));
            if (empty && l == null && photos == 0) lines = Collections.singletonList(getString(R.string.field_summary_empty));
            text = TextUtils.join("\n", lines);
        }
        show(fieldSummary, text);
        warn(fieldSummary, !ok);
    }

    private void line(List<String> lines, int label, String value) {
        if (!value.isEmpty()) lines.add(getString(R.string.field_summary_line, getString(label), value));
    }

    /** A problem shows in the warning colour and bold (also without colour). */
    private void warn(TextView view, boolean problem) {
        view.setTextColor(problem ? ColorStateList.valueOf(getColor(R.color.warning)) : bodyColor);
        view.setTypeface(null, problem ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** What still stops Start or Aim in the saved values (field metadata first), or null; the
     *  screen to correct it on goes to problemScreen. */
    private String savedProblem() {
        String p = FieldInfo.load(this).problem();
        problemScreen = FieldActivity.class;
        if (p == null) {
            p = SessionSettings.load(this).problem();
            problemScreen = ScheduleActivity.class;
        }
        return p;
    }

    /** True when the saved values are usable; else the problem shows above Start. */
    private boolean usable() {
        problem = savedProblem();
        refresh();
        return problem == null;
    }

    /** Why a tap did not go on, in a dialog (the message above Start may be out of sight); OK
     *  opens the screen with the problem. */
    private void showProblem() {
        final Class<? extends Activity> screen = problemScreen;
        new AlertDialog.Builder(this)
                .setTitle(R.string.problem_title)
                .setMessage(problem)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> startActivity(new Intent(this, screen)))
                .show();
    }

    /** "1 h 30 min", "2 h", "45 min". */
    static String duration(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        return h == 0 ? m + " min" : m == 0 ? h + " h" : h + " h " + m + " min";
    }

    static String text(EditText field) {
        return field.getText().toString().trim();
    }

    /** The number in a field ("21,5" too); throws with a plain-language message naming its label. */
    static double decimal(Context c, EditText field, int labelId) {
        try {
            return Double.parseDouble(text(field).replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(c.getString(R.string.number_needed, c.getString(labelId)));
        }
    }

    /** "5", "0.1": at most 4 decimals, so a saved float (0.1 comes back as 0.10000000149) shows as typed. */
    static String number(double d) {
        double r = Math.round(d * 10000) / 10000.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    /** Counts what each FaunaLapse folder holds (session folders and zips), and the photo sessions
     *  not packed yet, on a second thread: many files take a moment. */
    private void countStorage() {
        final List<Storage.Place> counted = places;
        final List<File> dirs = sessionDirs();
        final SessionZip run = SessionZip.last;
        // Sessions the last packing left as they are (hidden files) do not count as waiting.
        final List<String> hidden = run == null || !run.finished ? Collections.emptyList() : run.hidden;
        new Thread(() -> {
            long[][] use = new long[counted.size()][];
            long[] waiting = {0, 0};
            for (int i = 0; i < dirs.size(); i++) {
                File[] items = dirs.get(i).listFiles(f -> f.isDirectory() || f.getName().endsWith(".zip"));
                long bytes = 0;
                Set<String> sessions = new HashSet<>(); // a session may have more zips
                if (items != null) {
                    for (File f : items) {
                        long b = SessionZip.bytes(f);
                        bytes += b;
                        sessions.add(SessionZip.sessionName(f));
                        if (SessionZip.isPhotoSession(f) && !hidden.contains(f.getName())) {
                            waiting[0]++;
                            waiting[1] += b;
                        }
                    }
                }
                if (i < use.length) use[i] = new long[] {bytes, sessions.size()};
            }
            handler.post(() -> {
                if (counted != places) return; // listed again meanwhile
                storageUse = use;
                unpacked = waiting;
                showStorage();
            });
        }, "storage").start();
    }

    /** The FaunaLapse folder of each place, then the app's own one if no place uses it. */
    private List<File> sessionDirs() {
        List<File> dirs = new ArrayList<>();
        for (Storage.Place p : places) dirs.add(p.dir);
        File own = Storage.appSessionsDir(this);
        if (!dirs.contains(own)) dirs.add(own);
        return dirs;
    }

    /** Packs the photo sessions of all places (SessionZip in PackService). */
    private void pack() {
        PackService.start(this, sessionDirs());
    }

    /** While nothing records: the photo sessions not packed yet, packing, and how it ended. */
    private void showPack(boolean running, boolean packing) {
        SessionZip run = SessionZip.last;
        boolean waiting = unpacked != null && unpacked[0] > 0;
        packGroup.setVisibility(!running && (packing || waiting || run != null) ? View.VISIBLE : View.GONE);
        List<String> lines = new ArrayList<>();
        if (packing) {
            lines.add(getString(R.string.pack_progress, run.current + 1, run.folders.size(), run.percent()));
        } else {
            if (run != null) {
                if (run.packed > 0) {
                    lines.add(getResources().getQuantityString(R.plurals.pack_done, run.packed, run.packed));
                }
                if (run.stopped) {
                    lines.add(getString(R.string.pack_stopped));
                } else if (run.failedFolder != null) {
                    lines.add(run.failure == null
                            ? getString(R.string.pack_no_space, run.failedFolder.getName(), size(run.neededBytes))
                            : getString(R.string.pack_failed, run.failedFolder.getName(), run.failure));
                }
                if (!run.hidden.isEmpty()) {
                    lines.add(getResources().getQuantityString(R.plurals.pack_hidden, run.hiddenFiles,
                            TextUtils.join(", ", run.hidden), run.hiddenFiles));
                }
                if (run.notDeleted > 0) {
                    lines.add(getResources().getQuantityString(R.plurals.pack_not_deleted, run.notDeleted,
                            run.notDeleted));
                }
            }
            if (waiting) {
                lines.add(getResources().getQuantityString(R.plurals.pack_waiting, (int) unpacked[0],
                        (int) unpacked[0], size(unpacked[1])));
            }
        }
        show(packStatus, TextUtils.join(" ", lines));
        String button = getString(packing ? R.string.pack_stop : R.string.pack_button);
        if (!button.contentEquals(packButton.getText())) packButton.setText(button);
        packButton.setVisibility(packing || waiting ? View.VISIBLE : View.GONE);
        packButton.setEnabled(!packing || !run.stop);
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
        Storage.Place chosen = Storage.chosen(this, places);
        storageNote.setVisibility(!CaptureService.running && !chosen.key.equals(Storage.PHONE) && shortPhotoStep()
                ? View.VISIBLE : View.GONE);
        if (session) {
            show(storage, getString(R.string.storage_session, Storage.shownPath(this, places, folder),
                    size(SessionRunner.savedBytes.get()), size(folder.getUsableSpace())));
            show(storageTotal, "");
            return;
        }
        int c = places.indexOf(chosen);
        String path = Storage.shownPath(this, places, chosen.dir);
        String free = size(Storage.free(chosen.dir));
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
                    ? getString(R.string.storage_place_counting, p.name, size(Storage.free(p.dir)))
                    : getString(R.string.storage_place, p.name, size(storageUse[i][0]), sessions(storageUse[i][1]),
                            size(Storage.free(p.dir)));
            if (!line.contentEquals(b.getText())) b.setText(line);
            if (storageUse != null) {
                total[0] += storageUse[i][0];
                total[1] += storageUse[i][1];
            }
        }
        show(storageTotal, storageUse == null ? "" : getString(R.string.storage_total, size(total[0]), sessions(total[1])));
    }

    /** Photos every 2 s or faster: on a memory card, worth a test first (one save took 1.5 s on
     *  the Samsung M12's card, where the phone took 0.4 s at most). */
    private boolean shortPhotoStep() {
        SessionSettings s = SessionSettings.load(this);
        return !s.video && s.stepS <= 2;
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
        if (!usable()) {
            showProblem();
            return;
        }
        start(SESSION);
        scroll.post(() -> scroll.smoothScrollTo(0, 0)); // the status card shows the session
    }

    /** Screen off: at once when allowed; else first why Android asks, then Android's own screen. */
    private void screenOff() {
        if (ScreenOff.allowed(this)) {
            ScreenOff.lock(this);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.screen_off_title)
                .setMessage(R.string.screen_off_text)
                .setPositiveButton(R.string.screen_off_yes, (d, w) -> {
                    try {
                        startActivityForResult(ScreenOff.ask(this), SCREEN_OFF_REQUEST);
                    } catch (ActivityNotFoundException e) {
                        openPage(this, new Intent(Settings.ACTION_SECURITY_SETTINGS)); // a phone without that screen
                    }
                })
                .setNegativeButton(R.string.screen_off_no, null)
                .show();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        lastTouchMs = SystemClock.elapsedRealtime();
    }

    /** Screen off by itself, in case Screen off was forgotten: during a session (not in a pause),
     *  with the Screen off right, the screen stays on until the set minutes after the last touch on
     *  this screen (also where the phone's own timeout is shorter), then FaunaLapse switches it off.
     *  Without the right, or set to never, the phone's own screen timeout acts, as outside sessions. */
    private void autoScreenOff(boolean recording) {
        boolean auto = recording && sessionScreenOffMin > 0 && ScreenOff.allowed(this);
        boolean due = SystemClock.elapsedRealtime() - lastTouchMs >= sessionScreenOffMin * 60000L;
        scroll.setKeepScreenOn(auto && !due);
        if (auto && due) {
            lastTouchMs = SystemClock.elapsedRealtime(); // once; waking the screen counts as a touch
            ScreenOff.lock(this);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCREEN_OFF_REQUEST && ScreenOff.allowed(this)) ScreenOff.lock(this);
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

    private boolean debugBuild() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void handleTestIntent(Intent intent) {
        if (!debugBuild()) return;
        if (intent.hasExtra(EXTRA_CAMERA1)) SessionRunner.forceCamera1 = intent.getBooleanExtra(EXTRA_CAMERA1, false);
        if (intent.getBooleanExtra(EXTRA_AIM, false)) start(AIM);
        if (intent.getBooleanExtra(EXTRA_TAKE_PHOTO, false)) start(TEST_PHOTO);
        if (intent.getBooleanExtra(CaptureService.EXTRA_SPEED_TEST, false)) start(SPEED_TEST);
        if (intent.getBooleanExtra(EXTRA_STOP_SESSION, false)) stopSession();
        if (intent.getBooleanExtra(EXTRA_PACK, false) && !CaptureService.running && !SessionZip.running()) pack();
        if (intent.getBooleanExtra(EXTRA_START_SESSION, false)) {
            // Optional values, for example: --es mode video --es clip_min 0.5 --es run_min 6; saved
            // as the schedule screen would.
            SessionSettings s = SessionSettings.load(this);
            String m = intent.getStringExtra("mode");
            if (m != null) s.video = "video".equals(m);
            String sc = intent.getStringExtra("schedule");
            if (sc != null) s.daily = "daily".equals(sc);
            String fps = intent.getStringExtra("video_fps");
            if (fps != null) s.videoFps = "15".equals(fps) ? 15 : "10".equals(fps) ? 10 : 5;
            s.stepS = fromExtra(intent, "step_s", s.stepS);
            s.burstS = fromExtra(intent, "burst_s", s.burstS);
            s.savedSidePx = (int) Math.round(fromExtra(intent, "saved_side_px", s.savedSidePx));
            s.clipMin = fromExtra(intent, "clip_min", s.clipMin);
            s.breakMin = fromExtra(intent, "break_min", s.breakMin);
            s.runMin = (int) Math.round(fromExtra(intent, "run_min", s.runMin));
            if (intent.getStringExtra("windows") != null) s.windows = intent.getStringExtra("windows");
            s.days = (int) Math.round(fromExtra(intent, "days", s.days));
            s.heatLimitC = fromExtra(intent, "heat_limit_c", s.heatLimitC);
            s.screenOffMin = (int) Math.round(fromExtra(intent, "screen_off_min", s.screenOffMin));
            s.squarePercent = (int) Math.round(fromExtra(intent, "square_percent", s.squarePercent));
            s.squareX = fromExtra(intent, "square_x", s.squareX);
            s.squareY = fromExtra(intent, "square_y", s.squareY);
            if (intent.hasExtra("focus_diopters")) { // below 0: autofocus
                double d = fromExtra(intent, "focus_diopters", -1);
                s.focusFixed = d >= 0;
                if (d >= 0) s.focusDiopters = d;
            }
            s.save(this);
            mode.check(s.video ? R.id.mode_video : R.id.mode_photo);
            showScheduleSummary();
            startSession();
        }
    }

    private static double fromExtra(Intent intent, String key, double fallback) {
        String value = intent.getStringExtra(key);
        return value == null ? fallback : Double.parseDouble(value);
    }

    private void start(int what) {
        requested = what;
        cameraDenied = false;
        if (what == AIM && !usable()) { // the aim screen and its Start use the saved values
            showProblem();
            return;
        }
        List<String> missing = missingPermissions();
        if (!missing.isEmpty()) {
            optionalAsked = true;
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
            return;
        }
        if (CaptureService.running || SessionZip.running()) return;
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
     *  Android 10 and older the storage (for Documents/FaunaLapse). These two are optional, so
     *  without the camera request they are asked only at the first Start of each app run (the
     *  camera may have been allowed for a site photo); Android stops showing a twice refused one. */
    private List<String> missingPermissions() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        boolean optional = !missing.isEmpty() || (requested == SESSION && !optionalAsked);
        if (Build.VERSION.SDK_INT <= 29 && optional && !Storage.canShare(this)) {
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            missing.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= 33 && optional
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return missing;
    }

    // ---- What the screen shows --------------------------------------------------------------

    private void refresh() {
        boolean running = CaptureService.running;
        boolean session = CaptureService.sessionRunning;
        boolean paused = session && SessionRunner.userPaused;
        if (session) {
            show(sessionState, getString(paused ? R.string.status_user_paused
                    : SessionRunner.heatPaused ? R.string.status_paused : R.string.status_session));
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
        boolean packing = SessionZip.running();
        if ((wasRunning && !running) || (wasPacking && !packing)) countStorage();
        wasPacking = packing;
        // A session took the site photos and their text at its start: show what is left.
        if ((session && !wasRunning) || (wasSession && !running)) showFieldSummary();
        if (session && !wasSession) {
            sessionScreenOffMin = SessionSettings.load(this).screenOffMin;
            lastTouchMs = SystemClock.elapsedRealtime(); // the minutes count from the start
        }
        autoScreenOff(session && !paused);
        wasRunning = running;
        wasSession = session;
        showStorage();
        CaptureService.Battery b = CaptureService.Battery.read(this);
        battery.setText(getString(R.string.battery_line, b.percent >= 0 ? b.percent + " %" : "?",
                getString(b.charging ? R.string.charging : R.string.not_charging),
                b.tempC == null ? "?" : String.format(Locale.US, "%.1f", b.tempC)));
        phoneTime.setText(getString(R.string.phone_time,
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(new Date())));
        // A pause shows in the warning colour (bold also without colour).
        sessionState.setTextColor(paused || (session && SessionRunner.heatPaused)
                ? ColorStateList.valueOf(getColor(R.color.warning)) : bodyColor);
        showLastPhoto(session);
        // Start while idle; during a session Stop, and Pause or (while paused) Continue.
        stopButton.setVisibility(session ? View.VISIBLE : View.GONE);
        pauseButton.setVisibility(session && !paused ? View.VISIBLE : View.GONE);
        adjustButton.setVisibility(paused ? View.VISIBLE : View.GONE);
        continueButton.setVisibility(paused ? View.VISIBLE : View.GONE);
        if (!paused) continueButton.setEnabled(true);
        if (paused || !session) pauseButton.setEnabled(true);
        screenOffButton.setVisibility(session ? View.VISIBLE : View.GONE);
        screenOffRight.setVisibility(!running && detailsOpen && ScreenOff.allowed(this) ? View.VISIBLE : View.GONE);
        startButton.setVisibility(running ? View.GONE : View.VISIBLE);
        startHint.setVisibility(running ? View.GONE : View.VISIBLE);
        setControlsEnabled(!running && !packing); // a session waits until packing ends
        showPack(running, packing);
        showPhoneCheck(session);
        if (problem != null && !running) problem = savedProblem(); // gone once it is corrected
        String message = cameraDenied ? getString(R.string.camera_permission_needed) : problem;
        show(startMessage, message == null ? "" : message);
        if (testsGroup.getVisibility() == View.VISIBLE) showTestStatus(running && !session);
    }

    /** During a session: the newest saved square (or the last frame of the last clip), small. Read
     *  on a second thread, at most every LAST_PHOTO_MS. */
    private void showLastPhoto(boolean session) {
        final File f = session ? SessionRunner.lastFile : null;
        if (f == null) {
            // During a session the space stays, so the buttons below do not move under a finger
            // when the first photo comes.
            lastPhoto.setImageDrawable(null);
            lastPhoto.setVisibility(session ? View.INVISIBLE : View.GONE);
            lastPhotoText.setText(R.string.last_photo_none);
            lastPhotoText.setVisibility(session ? View.VISIBLE : View.GONE);
            lastShown = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (f.equals(lastShown) || now - lastShownAt < LAST_PHOTO_MS) return;
        lastShown = f;
        lastShownAt = now;
        final int side = Math.round(160 * getResources().getDisplayMetrics().density);
        new Thread(() -> {
            final Bitmap b = picture(f, side);
            handler.post(() -> {
                if (b == null || !f.equals(lastShown)) return;
                lastPhoto.setImageBitmap(b);
                lastPhoto.setVisibility(View.VISIBLE);
                String n = f.getName(); // yyyyMMdd_HHmmss_SSS: the time it was taken
                String time = n.substring(9, 11) + ":" + n.substring(11, 13) + ":" + n.substring(13, 15);
                lastPhotoText.setText(getString(n.endsWith(".mp4") ? R.string.last_clip : R.string.last_photo, time));
                lastPhotoText.setVisibility(View.VISIBLE);
                lastPhoto.setContentDescription(lastPhotoText.getText());
            });
        }, "last-photo").start();
    }

    /** A saved square, or a frame near the end of a clip, about {@code side} px; null if unreadable. */
    private static Bitmap picture(File f, int side) {
        if (!f.getName().endsWith(".mp4")) return SitePhotos.picture(f, side);
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            String ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long us = ms == null ? 0 : Math.max(0, Long.parseLong(ms) * 1000 - 500000);
            Bitmap b = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null || b.getWidth() <= side) return b;
            return Bitmap.createScaledBitmap(b, side, Math.round((float) b.getHeight() * side / b.getWidth()), true);
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
                // Android 10+ declares an IOException here
            }
        }
    }

    /** The last photo or frame as large as the screen allows. */
    private void showLastPhotoLarge() {
        final File f = lastShown;
        if (f == null) return;
        final int side = getResources().getDisplayMetrics().widthPixels;
        new Thread(() -> {
            final Bitmap b = picture(f, side);
            handler.post(() -> {
                if (b == null || isFinishing()) return;
                ImageView v = new ImageView(this);
                v.setAdjustViewBounds(true);
                v.setImageBitmap(b);
                new AlertDialog.Builder(this)
                        .setTitle(lastPhotoText.getText().toString().replaceFirst("\\. .*", ""))
                        .setView(v)
                        .setPositiveButton(R.string.site_photo_close, null)
                        .show();
            });
        }, "last-photo-large").start();
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
    static void show(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** What to change on the phone before a field session (PhoneCheck), each with the button that
     *  opens its settings page; apps cannot change these settings themselves. During a session the
     *  lines move into the status card (only what should change), so something forgotten shows
     *  there. BatteryLife: Google Play allows the battery dialog only for some kinds of apps;
     *  FaunaLapse is not on Google Play, and its core work (long sessions with the screen off) is
     *  what the battery limits stop. */
    private void showPhoneCheck(boolean session) {
        PhoneCheck c = PhoneCheck.read(this);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String state = session + today + c.toJson();
        if (state.equals(phoneState)) return;
        phoneState = state;
        phoneChecks.removeAllViews();
        sessionChecks.removeAllViews();
        checks = session ? sessionChecks : phoneChecks;
        if (c.ready()) {
            if (!session) addCheck(getString(R.string.check_ok), 0, null);
            return;
        }
        if (c.clockWrong) {
            addCheck(getString(R.string.check_clock, today), R.string.check_clock_button,
                    new Intent(Settings.ACTION_DATE_SETTINGS));
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
        if (c.stayAwake) {
            addCheck(getString(R.string.check_stay_awake), R.string.check_stay_awake_button,
                    new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        }
        Uri app = Uri.parse("package:" + getPackageName());
        if (c.batteryLimited) {
            // Android's settings page, not its direct "ignore battery optimisations" dialog: Google
            // Play allows that dialog's permission only for a few kinds of apps. Android 12+ has the
            // choice on the app's own page (Battery: Unrestricted); older ones in a list of apps.
            boolean appPage = Build.VERSION.SDK_INT >= 31;
            addCheck(getString(appPage ? R.string.check_battery : R.string.check_battery_list),
                    R.string.check_battery_button, appPage
                            ? new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)
                            : new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
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
            b.setOnClickListener(v -> openPage(this, page));
        }
        checks.addView(line);
    }

    static void openPage(Activity a, Intent page) {
        try {
            a.startActivity(page);
        } catch (ActivityNotFoundException e) {
            a.startActivity(new Intent(Settings.ACTION_SETTINGS)); // a phone without that page
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
