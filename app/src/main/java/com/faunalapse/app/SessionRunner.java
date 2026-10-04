package com.faunalapse.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaScannerConnection;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs one session: follows the CapturePlan, opens the camera shortly before each photo burst or
 * video clip, closes it again when the break is long, saves the ROI square of each photo or one MP4
 * per clip, and writes every event into session.jsonl. Guards: a heat pause, and clean stops when
 * the battery runs low or the storage is nearly full.
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
    /** Shorter clips (cut by a window or run end) are skipped: the recorder needs about 1 s for its
     *  first frame, so they would have no frames. */
    static final long MIN_CLIP_MS = 3000;
    /** Stop the session after this many camera errors in a row. */
    static final int MAX_ERRORS_IN_A_ROW = 3;
    /** Battery temperature checks during a burst or clip, this often. */
    static final long HEAT_CHECK_MS = 10000;
    /** Temperature checks during a heat pause, this often. */
    private static final long PAUSE_CHECK_MS = 30000;
    /** Longest single wait, so a changed phone clock is noticed within a minute. */
    private static final long MAX_SLEEP_MS = 60000;

    /** Debug builds: use Camera1 even where Camera2 is suitable, to test the Camera1 path (adb
     *  hook; lasts until the app is closed). */
    static volatile boolean forceCamera1;

    /** What the session does now, the counts so far, a warning if any, and whether it waits for
     *  the phone to cool down; read by the screen. */
    static volatile String status = "";
    static volatile String progress = "";
    static volatile String warning = "";
    static volatile boolean heatPaused;

    private final Context context;
    private final SessionSettings settings;
    private final CapturePlan plan;
    private final File dir;
    private final SessionLog log;
    private final Camera1Backend.Listener onDone;
    private final boolean useCamera2;
    private final int phoneTurn;
    private final long videoBitRate; // for the storage check
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

    /** {@code phoneTurn}: how the phone was held at Start (RoiMath.quarterTurn), to save the
     *  squares upright. */
    SessionRunner(Context context, SessionSettings settings, CapturePlan plan, File dir, int phoneTurn,
                  Camera1Backend.Listener onDone) {
        this.context = context;
        this.settings = settings;
        this.plan = plan;
        this.dir = dir;
        this.phoneTurn = phoneTurn;
        this.log = new SessionLog(new File(dir, "session.jsonl"));
        this.onDone = onDone;
        this.useCamera2 = useCamera2(context);
        this.videoBitRate = settings.video ? Camera1Session.bitRate(Camera1Session.videoProfile(
                Camera1Backend.backCameraId(), settings.videoQualityConstant()), settings.videoFps) : 0;
    }

    /** Camera2 where the phone's level is "limited" or better, else Camera1 (AGENTS.md). */
    static boolean useCamera2(Context context) {
        return !forceCamera1 && Camera2Session.suitable(context);
    }

    /** A session camera; {@code shown} is the aim screen's view, or null for an invisible preview. */
    static SessionCamera newCamera(Context context, boolean camera2, boolean video, int videoQuality,
                                   int videoFps, SurfaceTexture shown) {
        return camera2
                ? new Camera2Session(context, video, videoQuality, videoFps, shown)
                : new Camera1Session(video, videoQuality, videoFps, shown);
    }

    @Override
    public void run() {
        status = "Session starting.";
        warning = "";
        heatPaused = false;
        updateProgress();
        writeStart();
        // Photos are cut and saved on a second thread; when two are waiting, the session thread
        // saves the next one itself, which slows the photos instead of filling the memory.
        saver = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(2), new ThreadPoolExecutor.CallerRunsPolicy());
        String reason = "schedule finished";
        int errorsInRow = 0;
        long doneUntil = 0; // end of the last burst or clip handled: never run it twice
        try {
            while (true) {
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
                    boolean hot = settings.video ? clip(b) : burst(b);
                    doneUntil = b.end;
                    errorsInRow = 0;
                    if (hot) coolDown();
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

            JSONObject summary = new JSONObject();
            SessionLog.put(summary, "test", "session");
            SessionLog.put(summary, "file", dir.getName());
            boolean ok = reason.equals("schedule finished") || reason.equals("stopped on the screen");
            SessionLog.put(summary, "result", ok ? "ok" : "error");
            if (!ok) SessionLog.put(summary, "error", reason);
            SessionLog.put(summary, "reason", reason);
            SessionLog.put(summary, "counts", counts);
            SessionLog.put(summary, "errors", errors);
            if (!warning.isEmpty()) SessionLog.put(summary, "warning", warning);
            onDone.onDone(summary);
        }
    }

    private void writeStart() {
        JSONObject r = SessionLog.record("session_start");
        SessionLog.put(r, "settings", settings.toJson());
        SessionLog.put(r, "camera_api", useCamera2 ? "camera2" : "camera1");
        SessionLog.put(r, "phone_turn_deg", phoneTurn); // 0 upright or flat, 90 left side up
        SessionLog.put(r, "phone", Build.MANUFACTURER + " " + Build.MODEL);
        SessionLog.put(r, "android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        SessionLog.put(r, "app_version", appVersion());
        SessionLog.put(r, "folder", dir.getName());
        SessionLog.put(r, "folder_path", dir.getAbsolutePath());
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
        long t = SpeedTest.now();
        SessionCamera c = newCamera(context, useCamera2, settings.video, settings.videoQualityConstant(),
                settings.videoFps, null);
        try {
            c.open();
            if (!settings.video) c.setSquare(settings.squarePercent / 100.0, settings.squareX, settings.squareY);
        } catch (Exception e) {
            c.close();
            throw e;
        }
        camera = c;
        photoTurn = RoiMath.photoTurn(c.describe().optInt("sensor_orientation"), phoneTurn);
        JSONObject r = SessionLog.record("camera_open");
        SessionLog.put(r, "open_ms", SpeedTest.now() - t);
        SessionLog.put(r, "camera", c.describe());
        if (!settings.video) SessionLog.put(r, "saved_photo_turn_deg", photoTurn);
        log.write(r);
    }

    private void closeCamera() {
        if (camera == null) return;
        camera.close();
        camera = null;
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
            status = String.format(Locale.US, "Paused since %s: the phone is too warm (%.1f °C). The camera"
                    + " stays off until the battery is below %.0f °C.", clock(start),
                    CaptureService.batteryTempC(context), resumeC);
            long left = plan.end() - System.currentTimeMillis();
            if (left <= 0) break;
            sleepUntil(System.currentTimeMillis() + Math.min(PAUSE_CHECK_MS, left));
        } while (tooHot(true));
        heatPaused = false;
        long end = System.currentTimeMillis();
        heatPauseMs += end - start;
        JSONObject e = SessionLog.record("heat_pause_end");
        SessionLog.put(e, "battery_temp_c", CaptureService.batteryTempC(context));
        SessionLog.put(e, "paused_min", Math.round((end - start) / 6000.0) / 10.0);
        SessionLog.put(e, settings.video ? "clips_missed" : "bursts_missed", plan.burstsStartingIn(start, end));
        log.write(e);
    }

    /** One photo burst: focus once, then one photo per step on the grid from the burst start.
     *  Returns true when it stopped early because the phone got too warm. */
    private boolean burst(CapturePlan.Burst b) throws Exception {
        bursts++;
        JSONObject r = SessionLog.record("burst_start");
        putBurstFacts(r, b);
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
        int lateStart = (int) Math.max(0, (firstPossible - b.start + step - 1) / step);
        boolean hot = false;
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
        log.write(e);
        if (missed > 0 && warning.isEmpty() && taken > 0) {
            double photoS = captureMsSum / (double) taken / 1000;
            double suggested = Math.ceil(photoS * 2 + 0.4) / 2; // a little more, rounded up to 0.5 s
            warning = String.format(Locale.US, "The photo step of %.1f s is too short for this phone: a photo"
                    + " takes about %.1f s. Use video (15 frames per s) or a photo step of at least %.1f s.",
                    settings.stepS, photoS, suggested);
            JSONObject w = SessionLog.record("warning");
            SessionLog.put(w, "warning", warning);
            log.write(w);
        }
        return hot;
    }

    /** Cuts and saves the ROI square on the saver thread; one "photo" record per photo. */
    private void save(final byte[] jpeg, final long planned, final long takenAt, final long captureMs) {
        final File file = new File(dir, SessionLog.fileStem(takenAt) + ".jpg");
        final int turn = photoTurn;
        saver.execute(() -> {
            JSONObject r = SessionLog.record("photo");
            SessionLog.put(r, "time", SessionLog.isoTime(takenAt)); // when the photo was asked for
            SessionLog.put(r, "file", file.getName());
            SessionLog.put(r, "late_ms", takenAt - planned);
            SessionLog.put(r, "photo_ms", captureMs);
            try {
                long s0 = SpeedTest.now();
                int[] sides = PhotoSaver.saveSquare(jpeg, settings, turn, file);
                SessionLog.put(r, "square_real_px", sides[0]);
                SessionLog.put(r, "saved_side_px", sides[1]);
                SessionLog.put(r, "save_ms", SpeedTest.now() - s0);
                SessionLog.put(r, "kb", file.length() / 1024);
            } catch (Exception e) {
                SessionLog.put(r, "error", e.toString());
            }
            log.write(r);
        });
    }

    /** One video clip: prepare and focus before the start, record from start to end. Returns true
     *  when it stopped early because the phone got too warm. */
    private boolean clip(CapturePlan.Burst b) throws Exception {
        clips++;
        File file = new File(dir, SessionLog.fileStem(Math.max(b.start, System.currentTimeMillis())) + ".mp4");
        String focus = camera.prepareClip(file);
        sleepUntil(b.start);
        long started = System.currentTimeMillis();
        camera.startClip();
        status = "Recording clip " + clips + " until " + clock(b.end) + ".";
        boolean stoppedEarly = false;
        boolean hot = false;
        try {
            while (!hot && System.currentTimeMillis() < b.end) {
                sleepUntil(Math.min(b.end, System.currentTimeMillis() + HEAT_CHECK_MS));
                hot = tooHot(false);
            }
        } catch (InterruptedException e) {
            stoppedEarly = true; // Stop on the screen: finish the file and record it, then end
        }
        long stopped = System.currentTimeMillis();
        camera.stopClip();
        long stopMs = System.currentTimeMillis() - stopped;
        JSONObject r = SessionLog.record("clip");
        SessionLog.put(r, "time", SessionLog.isoTime(started));
        SessionLog.put(r, "file", file.getName());
        putBurstFacts(r, b);
        SessionLog.put(r, "focus", focus);
        SessionLog.put(r, "start_late_ms", started - b.start);
        SessionLog.put(r, "recorded_s", (stopped - started) / 1000.0);
        SessionLog.put(r, "stop_ms", stopMs); // with Camera1 below its slowest rate: includes the timing fix
        SessionLog.put(r, "kb", file.length() / 1024);
        if (stoppedEarly) SessionLog.put(r, "stopped_early", true);
        if (hot) SessionLog.put(r, "stopped_for_heat", true);
        log.write(r);
        updateProgress();
        if (stoppedEarly) throw new InterruptedException();
        return hot;
    }

    /** The counts so far and the session end, for the screen. */
    private void updateProgress() {
        String counts = settings.video ? count(clips, "clip")
                : count(photos, "photo") + " in " + count(bursts, "burst")
                        + (skipped > 0 ? " (" + count(skipped, "photo time") + " skipped)" : "");
        progress = "So far: " + counts + ". The session ends at " + clock(plan.end()) + ".";
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

    private static void sleepUntil(long target) throws InterruptedException {
        for (long left; (left = target - System.currentTimeMillis()) > 0; ) {
            Thread.sleep(Math.min(left, MAX_SLEEP_MS));
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
