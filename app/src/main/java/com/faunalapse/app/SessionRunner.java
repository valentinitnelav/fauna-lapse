package com.faunalapse.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaScannerConnection;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs one session: follows the CapturePlan, opens the camera shortly before each photo burst or
 * video clip, closes it again when the break is long, saves the ROI square of each photo or one MP4
 * per clip, and writes every event into session.jsonl, with the battery use at the end
 * (BatteryMeter). Guards: a heat pause, and clean stops when the battery runs low or the storage is
 * nearly full. The user can pause it to adjust the phone (pause(), resume()): the current photo or
 * clip is finished, the camera closes, and the session goes on by itself after PAUSE_LIMIT_MS.
 *
 * Runs on its own thread without a message loop (Camera1Session needs that) and simply waits between
 * events. All times come from the phone clock, so a late wake-up or a changed clock heals itself on
 * the next step. Stop it with Thread.interrupt(): the camera is closed and an open clip is finished.
 */
final class SessionRunner implements Runnable {

    /** Camera on this long before a burst, so exposure can settle (Samsung M12: about 1.4 s). */
    static final long WAKE_LEAD_MS = 5000;
    /** Autofocus starts this long before the burst (it took 1 to 2.5 s on the Samsung M12). */
    static final long FOCUS_LEAD_MS = 3000;
    /** Keep the camera on if it would be off for less than this: a wake costs more (FaunaPulse). */
    static final long KEEP_ON_MS = 30000;
    /** Shorter clips (cut by a window or run end) are skipped: they would hold almost nothing. */
    static final long MIN_CLIP_MS = 3000;
    /** Stop the session after this many camera errors in a row. */
    static final int MAX_ERRORS_IN_A_ROW = 3;
    /** Battery temperature checks during a burst or clip, this often. */
    static final long HEAT_CHECK_MS = 10000;
    /** Temperature checks during a heat pause, this often. */
    private static final long PAUSE_CHECK_MS = 30000;
    /** Longest single wait, so a changed phone clock is noticed within a minute. */
    private static final long MAX_SLEEP_MS = 60000;
    /** A pause by the user ends by itself after this long (maintainer): time to adjust the phone without
     *  hurry, while a forgotten pause costs little. */
    static final long PAUSE_LIMIT_MS = 10 * 60000L;
    /** How a burst or clip ended. */
    private static final int DONE = 0;
    private static final int HOT = 1;
    private static final int PAUSED = 2;

    /** Debug builds: use Camera1 even where Camera2 is suitable, to test the Camera1 path (adb
     *  hook; lasts until the app is closed). */
    static volatile boolean forceCamera1;

    /** What the session does now, the counts so far, a warning if any, and whether it waits for
     *  the phone to cool down; read by the screen. */
    static volatile String status = "";
    static volatile String progress = "";
    static volatile String warning = "";
    static volatile boolean heatPaused;
    /** Paused by the user (the camera is closed then), and until when at most; when that is the
     *  session end, pauseEndsSession. */
    static volatile boolean userPaused;
    static volatile long pauseEnds;
    static volatile boolean pauseEndsSession;
    /** The newest saved photo or finished clip, for the screen; null until the first. */
    static volatile File lastFile;
    /** The running session's folder, and the bytes of the photos and clips saved there so far
     *  (counted here, so the screen never has to walk through thousands of files). */
    static volatile File folder;
    static final AtomicLong savedBytes = new AtomicLong();

    private final Context context;
    private final SessionSettings settings;
    private final CapturePlan plan;
    private final File dir;
    private final JSONObject field; // FieldInfo at the start
    private final SessionLog log;
    private final Camera1Backend.Listener onDone;
    private final boolean useCamera2;
    private volatile int phoneTurn;
    private final long videoBitRate; // for the storage check
    private final BatteryMeter meter;
    private SessionCamera camera;
    private int photoTurn;
    private ThreadPoolExecutor saver;
    private int bursts;
    private int photos;
    private int skipped;
    private int clips;
    private int errors;
    private int heatPauses;
    private long heatPauseMs;
    private int userPauses;
    private long userPauseMs;
    /** Asked on the screen: pause after the current photo or clip, or go on again. */
    private volatile boolean pauseAsked;
    private volatile boolean resumeAsked;
    private final Object bell = new Object(); // wakes the waits when one of them is asked
    private long cutBurstStart = -1; // the burst a pause cut, so the rest of it is marked as such
    private long cutAt;              // when it was cut

    /** {@code phoneTurn}: how the phone was held at Start (RoiMath.quarterTurn), to save the
     *  squares upright; {@code field}: FieldInfo.toJson at Start. */
    SessionRunner(Context context, SessionSettings settings, CapturePlan plan, File dir, int phoneTurn,
                  JSONObject field, Camera1Backend.Listener onDone) {
        this.context = context;
        this.settings = settings;
        this.plan = plan;
        this.dir = dir;
        this.field = field;
        this.phoneTurn = phoneTurn;
        this.log = new SessionLog(new File(dir, "session.jsonl"));
        this.onDone = onDone;
        this.useCamera2 = useCamera2(context);
        this.meter = new BatteryMeter(context);
        // Twice the asked rate: some encoders keep a quality floor (Samsung M12: 3.9 Mbit/s for 2.2 asked).
        this.videoBitRate = settings.video ? 2L * SquareRecorder.bitRate(Camera1Backend.backCameraId(),
                settings.savedSidePx, settings.videoFps) : 0;
    }

    /** Camera2 where the phone's level is "limited" or better, else Camera1 (AGENTS.md). */
    static boolean useCamera2(Context context) {
        return !forceCamera1 && Camera2Session.suitable(context);
    }

    /** A session camera; {@code shown} is the aim screen's view, or null for an invisible preview. */
    static SessionCamera newCamera(Context context, boolean camera2, boolean video, int squareSide,
                                   int videoFps, SurfaceTexture shown) {
        return camera2
                ? new Camera2Session(context, video, squareSide, videoFps, shown)
                : new Camera1Session(video, squareSide, videoFps, shown);
    }

    /** From the screen: pause after the current photo or clip, and close the camera. */
    void pause() {
        resumeAsked = false;
        pauseAsked = true;
        ring();
    }

    /** From the screen: go on; {@code turn}: how the phone is held now (RoiMath.quarterTurn). */
    void resume(int turn) {
        phoneTurn = turn;
        resumeAsked = true;
        ring();
    }

    private void ring() {
        synchronized (bell) {
            bell.notifyAll();
        }
    }

    @Override
    public void run() {
        status = "Session starting.";
        warning = "";
        heatPaused = false;
        userPaused = false;
        lastFile = null;
        folder = dir;
        savedBytes.set(0);
        updateProgress();
        // Before the record: a copy to a memory card can take some seconds.
        List<String> sitePhotos = SitePhotos.moveInto(context, dir);
        SessionLog.put(field, "site_photos", new JSONArray(sitePhotos)); // in the folder site_photos
        writeStart();
        Thread meterThread = new Thread(meter, "battery");
        meterThread.start();
        // Photos are cut and saved on a second thread; when two are waiting, the session thread
        // saves the next one itself, which slows the photos instead of filling the memory.
        saver = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(2), new ThreadPoolExecutor.CallerRunsPolicy());
        String reason = "schedule finished";
        int errorsInRow = 0;
        long doneUntil = 0; // end of the last burst or clip handled: never run it twice
        try {
            while (true) {
                if (pauseAsked) {
                    userPause();
                    continue; // plan again from the new time
                }
                long now = System.currentTimeMillis();
                CapturePlan.Burst b = plan.burstAtOrAfter(Math.max(now, doneUntil));
                if (b == null) break;
                if (settings.video && b.end - Math.max(b.start, now) < MIN_CLIP_MS) {
                    JSONObject r = SessionLog.record("clip_skipped");
                    SessionLog.put(r, "planned_start", SessionLog.isoTime(b.start));
                    SessionLog.put(r, "planned_end", SessionLog.isoTime(b.end));
                    SessionLog.put(r, "reason", "shorter than " + MIN_CLIP_MS / 1000 + " s");
                    log.write(r);
                    doneUntil = b.end;
                    continue;
                }
                if (camera != null && b.start - WAKE_LEAD_MS - now > KEEP_ON_MS) closeCamera();
                if (camera == null) {
                    status = "Camera off. Next " + (settings.video ? "clip" : "burst") + " at " + clock(b.start) + ".";
                    sleepUntil(b.start - WAKE_LEAD_MS);
                    if (pauseAsked) continue;
                }
                String stop = stopReason();
                if (stop != null) {
                    reason = stop;
                    break;
                }
                if (tooHot(false)) {
                    coolDown();
                    continue; // plan again from the new time
                }
                try {
                    if (camera == null) openCamera();
                    sleepUntil(b.start - FOCUS_LEAD_MS);
                    if (pauseAsked) continue;
                    int end = settings.video ? clip(b) : burst(b);
                    // A pause may end within this burst or clip: then its rest is recorded.
                    doneUntil = end == PAUSED ? System.currentTimeMillis() : b.end;
                    if (end == PAUSED && System.currentTimeMillis() > b.start) {
                        cutBurstStart = b.start;
                        cutAt = System.currentTimeMillis();
                    }
                    errorsInRow = 0;
                    if (end == HOT) coolDown();
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    errors++;
                    errorsInRow++;
                    JSONObject r = SessionLog.record("error");
                    SessionLog.put(r, "error", e.toString());
                    log.write(r);
                    closeCamera();
                    if (errorsInRow >= MAX_ERRORS_IN_A_ROW) {
                        reason = errorsInRow + " camera errors in a row";
                        break;
                    }
                    sleepUntil(System.currentTimeMillis() + WAKE_LEAD_MS); // short pause, then try again
                }
            }
        } catch (InterruptedException e) {
            reason = "stopped on the screen";
        } catch (RuntimeException e) {
            reason = "error: " + e;
        } finally {
            closeCamera();
            meterThread.interrupt();
            saver.shutdown();
            try {
                saver.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // the photos still waiting are lost
            }
            JSONObject end = SessionLog.record("session_end");
            SessionLog.put(end, "reason", reason);
            SessionLog.put(end, "bursts", bursts);
            SessionLog.put(end, "photos", photos);
            SessionLog.put(end, "skipped_photo_times", skipped);
            SessionLog.put(end, "clips", clips);
            SessionLog.put(end, "errors", errors);
            SessionLog.put(end, "heat_pauses", heatPauses);
            SessionLog.put(end, "heat_pause_min", Math.round(heatPauseMs / 6000.0) / 10.0);
            SessionLog.put(end, "user_pauses", userPauses);
            SessionLog.put(end, "user_pause_min", Math.round(userPauseMs / 6000.0) / 10.0);
            SessionLog.put(end, "battery", meter.toJson());
            log.write(end);
            if (Build.VERSION.SDK_INT <= 29) {
                // Android 10 and older add new files to their file index (which a PC sees over USB)
                // only when asked; newer Android does it by itself.
                String[] names = dir.list();
                if (names != null) {
                    String[] paths = new String[names.length];
                    for (int i = 0; i < names.length; i++) paths[i] = new File(dir, names[i]).getAbsolutePath();
                    MediaScannerConnection.scanFile(context, paths, null, null);
                }
            }
            String counts = (settings.video ? count(clips, "clip")
                    : count(photos, "photo") + " in " + count(bursts, "burst")) + ", " + count(errors, "error")
                    + (heatPauses > 0 ? ", " + count(heatPauses, "heat pause") : "");
            status = "Session ended (" + reason + "): " + counts + ".";
            progress = "";
            heatPaused = false;
            userPaused = false;

            JSONObject summary = new JSONObject();
            SessionLog.put(summary, "test", "session");
            SessionLog.put(summary, "file", dir.getName());
            boolean ok = reason.equals("schedule finished") || reason.equals("stopped on the screen");
            SessionLog.put(summary, "result", ok ? "ok" : "error");
            if (!ok) SessionLog.put(summary, "error", reason);
            SessionLog.put(summary, "reason", reason);
            SessionLog.put(summary, "counts", counts);
            SessionLog.put(summary, "errors", errors);
            SessionLog.put(summary, "battery", meter.words());
            if (!warning.isEmpty()) SessionLog.put(summary, "warning", warning);
            onDone.onDone(summary);
        }
    }

    /** UsableSpace: the cautious free space, as in stopReason. */
    @SuppressLint("UsableSpace")
    private void writeStart() {
        JSONObject r = SessionLog.record("session_start");
        SessionLog.put(r, "field", field); // site, plant, location, site photos
        SessionLog.put(r, "settings", settings.toJson());
        SessionLog.put(r, "camera_api", useCamera2 ? "camera2" : "camera1");
        SessionLog.put(r, "phone_turn_deg", phoneTurn); // 0 upright or flat, 90 left side up
        SessionLog.put(r, "phone", Build.MANUFACTURER + " " + Build.MODEL);
        SessionLog.put(r, "android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        SessionLog.put(r, "app_version", appVersion());
        SessionLog.put(r, "phone_state", PhoneCheck.read(context).toJson()); // radios, battery limits
        SessionLog.put(r, "screen_off_allowed", ScreenOff.allowed(context)); // needed by screen_off_after_min
        SessionLog.put(r, "folder", dir.getName());
        SessionLog.put(r, "folder_path", dir.getAbsolutePath());
        SessionLog.put(r, "storage_free_mb", dir.getUsableSpace() / (1024 * 1024));
        SessionLog.put(r, "time_zone", TimeZone.getDefault().getID()); // the times also carry the UTC offset
        SessionLog.put(r, "wake_lead_s", WAKE_LEAD_MS / 1000.0);
        SessionLog.put(r, "focus_lead_s", FOCUS_LEAD_MS / 1000.0);
        SessionLog.put(r, "keep_camera_on_if_break_below_s", KEEP_ON_MS / 1000.0);
        SessionLog.put(r, "low_battery_stop_percent", Guards.LOW_BATTERY_PERCENT);
        SessionLog.put(r, "storage_reserve_mb", Guards.RESERVE_BYTES / (1024 * 1024));
        JSONArray periods = new JSONArray();
        for (long[] p : plan.periods()) {
            periods.put(SessionLog.isoTime(p[0]) + " to " + SessionLog.isoTime(p[1]));
        }
        SessionLog.put(r, "recording_periods", periods);
        log.write(r);
    }

    private void openCamera() throws Exception {
        // The aim screen may still hold the camera after a pause; it lets go when it closes.
        for (int i = 0; i < 100 && AimActivity.cameraInUse; i++) Thread.sleep(100);
        long t = SpeedTest.now();
        SessionCamera c = newCamera(context, useCamera2, settings.video, settings.savedSidePx,
                settings.videoFps, null);
        try {
            c.open();
            c.setSquare(settings.squarePercent / 100.0, settings.squareX, settings.squareY);
            if (settings.focusFixed) c.setFixedFocus((float) settings.focusDiopters);
        } catch (Exception e) {
            c.close();
            throw e;
        }
        camera = c;
        meter.cameraOpen(true);
        photoTurn = RoiMath.photoTurn(c.describe().optInt("sensor_orientation"), phoneTurn);
        JSONObject r = SessionLog.record("camera_open");
        SessionLog.put(r, "open_ms", SpeedTest.now() - t);
        SessionLog.put(r, "camera", c.describe());
        SessionLog.put(r, "saved_turn_deg", photoTurn);
        log.write(r);
        if (settings.focusFixed && !c.describe().optBoolean("fixed_focus_possible") && warning.isEmpty()) {
            warning = "This phone cannot fix the focus, so it focuses automatically at each "
                    + (settings.video ? "clip." : "burst.");
            JSONObject w = SessionLog.record("warning");
            SessionLog.put(w, "warning", warning);
            log.write(w);
        }
    }

    private void closeCamera() {
        if (camera == null) return;
        camera.close();
        camera = null;
        meter.cameraOpen(false);
        log.write(SessionLog.record("camera_close"));
    }

    /** A reason to end the session now (battery low and not charging, or too little storage for
     *  the next burst or clip), or null. Usable space leaves out caches that Android could clear: a
     *  cautious number, and the newer allocatable-bytes call needs Android 8. */
    @SuppressLint("UsableSpace")
    private String stopReason() {
        CaptureService.Battery battery = CaptureService.Battery.read(context);
        if (Guards.batteryLow(battery.percent, battery.charging)) {
            return "battery low: " + battery.percent + " %, not charging";
        }
        long free = dir.getUsableSpace();
        long next = settings.video ? Guards.clipBytes(settings.burstMs(), videoBitRate)
                : Guards.burstBytes(settings.burstMs(), settings.stepMs(), settings.savedSidePx);
        if (Guards.storageLow(free, next)) {
            return "not enough storage: " + free / (1024 * 1024) + " MB free, the next "
                    + (settings.video ? "clip" : "burst") + " needs up to " + next / (1024 * 1024) + " MB";
        }
        return null;
    }

    /** True when the battery is at the heat limit (while paused: not yet cool enough to go on). */
    private boolean tooHot(boolean paused) {
        Double t = CaptureService.batteryTempC(context);
        return t != null && Guards.tooHot(t, settings.heatLimitC, paused);
    }

    /** Heat pause: the camera stays off until the battery is Guards.RESUME_GAP_C below the limit,
     *  or until the schedule ends. */
    private void coolDown() throws InterruptedException {
        closeCamera();
        heatPauses++;
        long start = System.currentTimeMillis();
        double resumeC = settings.heatLimitC - Guards.RESUME_GAP_C;
        JSONObject r = SessionLog.record("heat_pause");
        SessionLog.put(r, "battery_temp_c", CaptureService.batteryTempC(context));
        SessionLog.put(r, "resume_below_c", resumeC);
        log.write(r);
        heatPaused = true;
        do {
            status = String.format(Locale.US, "Heat pause since %s (battery %.1f °C). The camera"
                    + " stays off until the battery is below %.0f °C.", clock(start),
                    CaptureService.batteryTempC(context), resumeC);
            long left = plan.end() - System.currentTimeMillis();
            if (left <= 0) break;
            sleepUntil(System.currentTimeMillis() + Math.min(PAUSE_CHECK_MS, left));
        } while (tooHot(true) && !pauseAsked); // a pause by the user takes over; the heat is checked after it
        heatPaused = false;
        long end = System.currentTimeMillis();
        heatPauseMs += end - start;
        JSONObject e = SessionLog.record("heat_pause_end");
        SessionLog.put(e, "battery_temp_c", CaptureService.batteryTempC(context));
        SessionLog.put(e, "paused_min", Math.round((end - start) / 6000.0) / 10.0);
        SessionLog.put(e, settings.video ? "clips_missed" : "bursts_missed", plan.burstsStartingIn(start, end));
        log.write(e);
    }

    /** Paused by the user: the camera closes (the aim screen may use it) until the user goes on,
     *  PAUSE_LIMIT_MS is over, or the schedule ends. Then the session takes the square and the
     *  focus as saved now, and the way the phone is held. */
    private void userPause() throws InterruptedException {
        pauseAsked = false;
        closeCamera();
        userPauses++;
        long start = System.currentTimeMillis();
        pauseEnds = Math.min(start + PAUSE_LIMIT_MS, plan.end());
        pauseEndsSession = pauseEnds == plan.end();
        log.write(SessionLog.record("pause"));
        status = "Paused at " + clock(start) + ": nothing is recorded. The session "
                + (pauseEndsSession ? "ends" : "goes on by itself") + " at " + clock(pauseEnds) + ".";
        userPaused = true;
        CaptureService.showPaused(context, true);
        for (long left; !resumeAsked && (left = pauseEnds - System.currentTimeMillis()) > 0; ) {
            synchronized (bell) {
                if (!resumeAsked) bell.wait(Math.min(left, MAX_SLEEP_MS));
            }
        }
        boolean byUser = resumeAsked;
        resumeAsked = false;
        long end = System.currentTimeMillis();
        userPauseMs += end - start;
        SessionSettings now = SessionSettings.load(context); // the aim screen saves its changes at once
        settings.squarePercent = now.squarePercent;
        settings.squareX = now.squareX;
        settings.squareY = now.squareY;
        settings.focusFixed = now.focusFixed;
        settings.focusDiopters = now.focusDiopters;
        JSONObject r = SessionLog.record("pause_end");
        SessionLog.put(r, "ended_by", byUser ? "user" : "time limit");
        SessionLog.put(r, "paused_min", Math.round((end - start) / 6000.0) / 10.0);
        SessionLog.put(r, settings.video ? "clips_starting_in_pause" : "bursts_starting_in_pause",
                plan.burstsStartingIn(start, end));
        JSONObject now2 = settings.toJson();
        for (String key : new String[] {"square_percent_of_short_side", "square_centre_x", "square_centre_y",
                "focus", "focus_diopters", "focus_distance"}) {
            SessionLog.put(r, key, now2.opt(key));
        }
        SessionLog.put(r, "phone_turn_deg", phoneTurn);
        log.write(r);
        userPaused = false;
        CaptureService.showPaused(context, false);
    }

    /** One photo burst: focus once, then one photo per step on the grid from the burst start.
     *  Returns HOT when it stopped early because the phone got too warm, PAUSED when the user
     *  paused it, else DONE. */
    private int burst(CapturePlan.Burst b) throws Exception {
        boolean continued = b.start == cutBurstStart;
        if (!continued) bursts++;
        JSONObject r = SessionLog.record("burst_start");
        putBurstFacts(r, b);
        if (continued) SessionLog.put(r, "continues_after_pause", true);
        SessionLog.put(r, "focus", camera.focus());
        log.write(r);

        long step = settings.stepMs();
        int taken = 0;
        int missed = 0;
        long captureMsSum = 0;
        // The first photo time is found before waiting for it: after the wait the clock is often a
        // few ms past the burst start, which would push the first photo one step later.
        long t = CapturePlan.nextPhotoTime(b, step, System.currentTimeMillis());
        // Grid times already over when the burst could start (camera or focus too slow).
        long firstPossible = t >= 0 ? t : b.end;
        // In the rest of a burst a pause cut: only the grid times since the cut.
        long from = CapturePlan.nextPhotoTime(b, step, continued ? cutAt : b.start);
        int lateStart = from < 0 ? 0 : (int) Math.max(0, (firstPossible - from + step - 1) / step);
        boolean hot = false;
        boolean paused = false;
        long nextHeatCheck = System.currentTimeMillis() + HEAT_CHECK_MS;
        while (t >= 0) {
            if (System.currentTimeMillis() >= nextHeatCheck) {
                if (tooHot(false)) {
                    hot = true;
                    break;
                }
                nextHeatCheck = System.currentTimeMillis() + HEAT_CHECK_MS;
            }
            sleepUntil(t);
            if (pauseAsked) {
                paused = true;
                break;
            }
            status = "Burst " + bursts + ": photo " + (taken + 1) + ". Burst ends at " + clock(b.end) + ".";
            long takenAt = System.currentTimeMillis();
            long c0 = SpeedTest.now();
            byte[] jpeg = camera.takePhoto();
            long captureMs = SpeedTest.now() - c0;
            save(jpeg, t, takenAt, captureMs);
            taken++;
            photos++;
            updateProgress();
            captureMsSum += captureMs;
            long next = CapturePlan.nextPhotoTime(b, step, System.currentTimeMillis());
            // Grid times after t that passed while this photo was taken were missed.
            long limit = next >= 0 ? next : Math.min(b.end, System.currentTimeMillis());
            missed += (int) Math.max(0, (limit - t - 1) / step);
            t = next;
        }
        skipped += missed;
        updateProgress();
        JSONObject e = SessionLog.record("burst_end");
        SessionLog.put(e, "photos", taken);
        SessionLog.put(e, "skipped_photo_times", missed);
        SessionLog.put(e, "photo_times_missed_at_start", lateStart);
        if (taken > 0) SessionLog.put(e, "mean_photo_ms", captureMsSum / taken);
        if (hot) SessionLog.put(e, "stopped_for_heat", true);
        if (paused) SessionLog.put(e, "paused_by_user", true);
        log.write(e);
        if (missed > 0 && warning.isEmpty() && taken > 0) {
            double photoS = captureMsSum / (double) taken / 1000;
            double suggested = Math.ceil(photoS * 2 + 0.4) / 2; // a little more, rounded up to 0.5 s
            warning = String.format(Locale.US, "Photo step %.1f s is too short: this phone takes about %.1f s"
                    + " per photo. Use a photo step of at least %.1f s, or video.",
                    settings.stepS, photoS, suggested);
            JSONObject w = SessionLog.record("warning");
            SessionLog.put(w, "warning", warning);
            log.write(w);
        }
        return hot ? HOT : paused ? PAUSED : DONE;
    }

    /** Cuts and saves the ROI square on the saver thread; one "photo" record per photo. */
    private void save(final byte[] jpeg, final long planned, final long takenAt, final long captureMs) {
        final File file = new File(dir, SessionLog.fileStem(takenAt) + ".jpg");
        final int turn = photoTurn;
        final JSONObject r = SessionLog.record("photo");
        SessionLog.put(r, "time", SessionLog.isoTime(takenAt)); // when the photo was asked for
        SessionLog.put(r, "file", file.getName());
        SessionLog.put(r, "late_ms", takenAt - planned);
        SessionLog.put(r, "photo_ms", captureMs);
        camera.putExposure(r); // now: the next photo replaces it
        saver.execute(() -> {
            try {
                long s0 = SpeedTest.now();
                int[] sides = PhotoSaver.saveSquare(jpeg, settings, turn, file);
                SessionLog.put(r, "square_real_px", sides[0]);
                SessionLog.put(r, "saved_side_px", sides[1]);
                SessionLog.put(r, "save_ms", SpeedTest.now() - s0);
                SessionLog.put(r, "kb", file.length() / 1024);
                savedBytes.addAndGet(file.length());
                lastFile = file;
            } catch (Exception e) {
                SessionLog.put(r, "error", e.toString());
            }
            log.write(r);
        });
    }

    /** One video clip: prepare and focus before the start, record from start to end. Returns HOT
     *  when it stopped early because the phone got too warm, PAUSED when the user paused it, else
     *  DONE. */
    private int clip(CapturePlan.Burst b) throws Exception {
        clips++;
        boolean continued = b.start == cutBurstStart;
        File file = new File(dir, SessionLog.fileStem(Math.max(b.start, System.currentTimeMillis())) + ".mp4");
        String focus = camera.prepareClip(file, photoTurn);
        sleepUntil(b.start);
        if (pauseAsked) { // before the clip began: no file
            clips--;
            closeCamera();
            file.delete();
            return PAUSED;
        }
        long started = System.currentTimeMillis();
        camera.startClip();
        status = "Recording clip " + clips + " until " + clock(b.end) + ".";
        boolean stoppedEarly = false;
        boolean hot = false;
        try {
            while (!hot && !pauseAsked && System.currentTimeMillis() < b.end) {
                sleepUntil(Math.min(b.end, System.currentTimeMillis() + HEAT_CHECK_MS));
                hot = tooHot(false);
            }
        } catch (InterruptedException e) {
            stoppedEarly = true; // Stop on the screen: finish the file and record it, then end
        }
        long stopped = System.currentTimeMillis();
        JSONObject facts = camera.stopClip();
        long stopMs = System.currentTimeMillis() - stopped;
        JSONObject r = SessionLog.record("clip");
        SessionLog.put(r, "time", SessionLog.isoTime(started));
        SessionLog.put(r, "file", file.getName());
        putBurstFacts(r, b);
        SessionLog.put(r, "focus", focus);
        SessionLog.put(r, "start_late_ms", started - b.start);
        SessionLog.put(r, "recorded_s", (stopped - started) / 1000.0);
        SessionLog.put(r, "stop_ms", stopMs);
        for (Iterator<String> k = facts.keys(); k.hasNext(); ) {
            String key = k.next();
            SessionLog.put(r, key, facts.opt(key));
        }
        SessionLog.put(r, "saved_side_px", camera.describe().optInt("saved_side_px"));
        SessionLog.put(r, "kb", file.length() / 1024);
        savedBytes.addAndGet(file.length());
        if (stoppedEarly) SessionLog.put(r, "stopped_early", true);
        if (hot) SessionLog.put(r, "stopped_for_heat", true);
        boolean paused = !stoppedEarly && !hot && stopped < b.end;
        if (paused) SessionLog.put(r, "paused_by_user", true);
        if (continued) SessionLog.put(r, "continues_after_pause", true);
        log.write(r);
        lastFile = file;
        updateProgress();
        if (stoppedEarly) throw new InterruptedException();
        return hot ? HOT : paused ? PAUSED : DONE;
    }

    /** The counts so far and the session end, for the screen. */
    private void updateProgress() {
        String counts = settings.video ? count(clips, "clip")
                : count(photos, "photo") + " in " + count(bursts, "burst")
                        + (skipped > 0 ? " (" + count(skipped, "photo time") + " skipped)" : "");
        // Two lines: the growing counts would push the end time along.
        progress = "So far: " + counts + ".\nThe session ends at " + clock(plan.end()) + ".";
    }

    private void putBurstFacts(JSONObject r, CapturePlan.Burst b) {
        SessionLog.put(r, "planned_start", SessionLog.isoTime(b.start));
        SessionLog.put(r, "planned_end", SessionLog.isoTime(b.end));
        CaptureService.Battery battery = CaptureService.Battery.read(context);
        SessionLog.put(r, "battery_temp_c", battery.tempC);
        SessionLog.put(r, "battery_percent", battery.percent);
        SessionLog.put(r, "charging", battery.charging);
        SessionLog.put(r, "screen_on", CaptureService.isScreenOn(context));
    }

    private String appVersion() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Waits until {@code target}, or until the user asks for a pause. */
    private void sleepUntil(long target) throws InterruptedException {
        for (long left; !pauseAsked && (left = target - System.currentTimeMillis()) > 0; ) {
            synchronized (bell) {
                if (!pauseAsked) bell.wait(Math.min(left, MAX_SLEEP_MS));
            }
        }
    }

    private static String count(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    /** "05:00:00" today, "Sun 5 Oct 05:00:00" on another day. */
    private static String clock(long ms) {
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(ms));
        boolean today = day.equals(new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()));
        return new SimpleDateFormat(today ? "HH:mm:ss" : "EEE d MMM HH:mm:ss", Locale.US).format(new Date(ms));
    }
}
