package com.faunalapse.app;

import android.content.Context;
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
 * per clip, and writes every event into session.jsonl.
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
    /** Stop the session after this many camera errors in a row. */
    static final int MAX_ERRORS_IN_A_ROW = 3;
    /** Longest single wait, so a changed phone clock is noticed within a minute. */
    private static final long MAX_SLEEP_MS = 60000;

    /** What the session does now, and a warning if any; read by the screen. */
    static volatile String status = "";
    static volatile String warning = "";

    private final Context context;
    private final SessionSettings settings;
    private final CapturePlan plan;
    private final File dir;
    private final SessionLog log;
    private final Camera1Backend.Listener onDone;
    private final boolean useCamera2;
    private SessionCamera camera;
    private ThreadPoolExecutor saver;
    private int bursts;
    private int photos;
    private int skipped;
    private int clips;
    private int errors;

    SessionRunner(Context context, SessionSettings settings, CapturePlan plan, File dir,
                  Camera1Backend.Listener onDone) {
        this.context = context;
        this.settings = settings;
        this.plan = plan;
        this.dir = dir;
        this.log = new SessionLog(new File(dir, "session.jsonl"));
        this.onDone = onDone;
        this.useCamera2 = Camera2Session.suitable(context);
    }

    @Override
    public void run() {
        status = "Session starting.";
        warning = "";
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
                if (camera != null && b.start - WAKE_LEAD_MS - now > KEEP_ON_MS) closeCamera();
                if (camera == null) {
                    status = "Camera off. Next " + (settings.video ? "clip" : "burst") + " at " + clock(b.start) + ".";
                    sleepUntil(b.start - WAKE_LEAD_MS);
                }
                try {
                    if (camera == null) openCamera();
                    sleepUntil(b.start - FOCUS_LEAD_MS);
                    if (settings.video) clip(b);
                    else burst(b);
                    doneUntil = b.end;
                    errorsInRow = 0;
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
            log.write(end);
            String counts = settings.video ? count(clips, "clip")
                    : count(photos, "photo") + " in " + count(bursts, "burst");
            status = "Session ended (" + reason + "): " + counts + ", " + count(errors, "error") + ".";

            JSONObject summary = new JSONObject();
            SessionLog.put(summary, "test", "session");
            SessionLog.put(summary, "file", dir.getName());
            boolean ok = reason.equals("schedule finished") || reason.equals("stopped on the screen");
            SessionLog.put(summary, "result", ok ? "ok" : "error");
            if (!ok) SessionLog.put(summary, "error", reason);
            SessionLog.put(summary, "reason", reason);
            SessionLog.put(summary, "counts", counts);
            SessionLog.put(summary, "errors", errors);
            onDone.onDone(summary);
        }
    }

    private void writeStart() {
        JSONObject r = SessionLog.record("session_start");
        SessionLog.put(r, "settings", settings.toJson());
        SessionLog.put(r, "camera_api", useCamera2 ? "camera2" : "camera1");
        SessionLog.put(r, "phone", Build.MANUFACTURER + " " + Build.MODEL);
        SessionLog.put(r, "android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        SessionLog.put(r, "app_version", appVersion());
        SessionLog.put(r, "folder", dir.getName());
        SessionLog.put(r, "wake_lead_s", WAKE_LEAD_MS / 1000.0);
        SessionLog.put(r, "focus_lead_s", FOCUS_LEAD_MS / 1000.0);
        SessionLog.put(r, "keep_camera_on_if_break_below_s", KEEP_ON_MS / 1000.0);
        JSONArray periods = new JSONArray();
        for (long[] p : plan.periods()) {
            periods.put(SessionLog.isoTime(p[0]) + " to " + SessionLog.isoTime(p[1]));
        }
        SessionLog.put(r, "recording_periods", periods);
        log.write(r);
    }

    private void openCamera() throws Exception {
        long t = SpeedTest.now();
        SessionCamera c = useCamera2
                ? new Camera2Session(context, settings.video, settings.videoQualityConstant(), SessionSettings.VIDEO_FPS)
                : new Camera1Session(settings.video, settings.videoQualityConstant(), SessionSettings.VIDEO_FPS);
        try {
            c.open();
        } catch (Exception e) {
            c.close();
            throw e;
        }
        camera = c;
        JSONObject r = SessionLog.record("camera_open");
        SessionLog.put(r, "open_ms", SpeedTest.now() - t);
        SessionLog.put(r, "camera", c.describe());
        log.write(r);
    }

    private void closeCamera() {
        if (camera == null) return;
        camera.close();
        camera = null;
        log.write(SessionLog.record("camera_close"));
    }

    /** One photo burst: focus once, then one photo per step on the grid from the burst start. */
    private void burst(CapturePlan.Burst b) throws Exception {
        bursts++;
        JSONObject r = SessionLog.record("burst_start");
        putBurstFacts(r, b);
        SessionLog.put(r, "focus", camera.focus());
        log.write(r);
        sleepUntil(b.start);

        long step = settings.stepMs();
        int taken = 0;
        int missed = 0;
        long captureMsSum = 0;
        long t = CapturePlan.nextPhotoTime(b, step, System.currentTimeMillis());
        // Grid times already over when the burst could start (camera or focus too slow).
        long firstPossible = t >= 0 ? t : b.end;
        int lateStart = (int) Math.max(0, (firstPossible - b.start + step - 1) / step);
        while (t >= 0) {
            sleepUntil(t);
            status = "Burst " + bursts + ": photo " + (taken + 1) + ". Burst ends at " + clock(b.end) + ".";
            long takenAt = System.currentTimeMillis();
            long c0 = SpeedTest.now();
            byte[] jpeg = camera.takePhoto();
            long captureMs = SpeedTest.now() - c0;
            save(jpeg, t, takenAt, captureMs);
            taken++;
            photos++;
            captureMsSum += captureMs;
            long next = CapturePlan.nextPhotoTime(b, step, System.currentTimeMillis());
            // Grid times after t that passed while this photo was taken were missed.
            long limit = next >= 0 ? next : Math.min(b.end, System.currentTimeMillis());
            missed += (int) Math.max(0, (limit - t - 1) / step);
            t = next;
        }
        skipped += missed;
        JSONObject e = SessionLog.record("burst_end");
        SessionLog.put(e, "photos", taken);
        SessionLog.put(e, "skipped_photo_times", missed);
        SessionLog.put(e, "photo_times_missed_at_start", lateStart);
        if (taken > 0) SessionLog.put(e, "mean_photo_ms", captureMsSum / taken);
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
    }

    /** Cuts and saves the ROI square on the saver thread; one "photo" record per photo. */
    private void save(final byte[] jpeg, final long planned, final long takenAt, final long captureMs) {
        final File file = new File(dir, SessionLog.fileStem(takenAt) + ".jpg");
        saver.execute(() -> {
            JSONObject r = SessionLog.record("photo");
            SessionLog.put(r, "time", SessionLog.isoTime(takenAt)); // when the photo was asked for
            SessionLog.put(r, "file", file.getName());
            SessionLog.put(r, "late_ms", takenAt - planned);
            SessionLog.put(r, "photo_ms", captureMs);
            try {
                long s0 = SpeedTest.now();
                int[] sides = PhotoSaver.saveSquare(jpeg, settings.squarePercent / 100.0, settings.savedSidePx, file);
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

    /** One video clip: prepare and focus before the start, record from start to end. */
    private void clip(CapturePlan.Burst b) throws Exception {
        clips++;
        File file = new File(dir, SessionLog.fileStem(Math.max(b.start, System.currentTimeMillis())) + ".mp4");
        String focus = camera.prepareClip(file);
        sleepUntil(b.start);
        long started = System.currentTimeMillis();
        camera.startClip();
        status = "Recording clip " + clips + " until " + clock(b.end) + ".";
        boolean stoppedEarly = false;
        try {
            sleepUntil(b.end);
        } catch (InterruptedException e) {
            stoppedEarly = true; // Stop on the screen: finish the file and record it, then end
        }
        long stopped = System.currentTimeMillis();
        camera.stopClip();
        JSONObject r = SessionLog.record("clip");
        SessionLog.put(r, "time", SessionLog.isoTime(started));
        SessionLog.put(r, "file", file.getName());
        putBurstFacts(r, b);
        SessionLog.put(r, "focus", focus);
        SessionLog.put(r, "start_late_ms", started - b.start);
        SessionLog.put(r, "recorded_s", (stopped - started) / 1000.0);
        SessionLog.put(r, "kb", file.length() / 1024);
        if (stoppedEarly) SessionLog.put(r, "stopped_early", true);
        log.write(r);
        if (stoppedEarly) throw new InterruptedException();
    }

    private void putBurstFacts(JSONObject r, CapturePlan.Burst b) {
        SessionLog.put(r, "planned_start", SessionLog.isoTime(b.start));
        SessionLog.put(r, "planned_end", SessionLog.isoTime(b.end));
        SessionLog.put(r, "battery_temp_c", CaptureService.batteryTempC(context));
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

    private static String clock(long ms) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ms));
    }
}
