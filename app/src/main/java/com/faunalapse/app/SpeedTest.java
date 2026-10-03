package com.faunalapse.app;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 0 speed test of the back camera, meant to run with the screen off. It answers:
 * - Full photos (takePicture): how fast can photos follow each other, at 4 picture sizes?
 * - Preview frames: how fast can frames be saved as JPEG, whole and as the ROI square?
 * - Video: do 1080p, 720p and 480p work at 15 fps? Does time-lapse video at 5 and 1 frames per s?
 * - On Android 5+, full photos through Camera2 (Camera2StillTest), to compare with Camera1.
 * Each test writes one JSON line to results.jsonl in {@code dir}, next to the files it made.
 * facts.json lists what the camera offers.
 *
 * Unlike Camera1Backend this is a plain script: it runs on its own thread and waits for each
 * camera answer. That thread has no message loop (Looper), so Camera1 delivers its callbacks on the
 * app's main thread, where the waiting cannot block them.
 */
@SuppressWarnings("deprecation") // Camera1 and CamcorderProfile.get() still work on every version
final class SpeedTest implements Runnable {

    private static final long WARM_UP_MS = 3000;     // exposure settles after each restart
    private static final long FOCUS_TIMEOUT_MS = 3000;
    private static final long PICTURE_TIMEOUT_MS = 10000;
    private static final long FRAME_TIMEOUT_MS = 2000;
    private static final int STILLS_PER_SIZE = 5;
    private static final int SLOW_FRAMES = 10;         // preview test: one frame per second
    private static final long FAST_MS = 5000;          // preview test: as many frames as possible
    private static final long VIDEO_MS = 10000;
    private static final int JPEG_QUALITY = 90;        // the Samsung M12 default
    private static final int ROI_SIDE = 1024;          // default saved square side (plan)

    private final Context context;
    private final File dir;
    private final Camera1Backend.Listener listener;
    private Camera camera;
    private SurfaceTexture texture;
    private int tests;
    private int failed;

    SpeedTest(Context context, File dir, Camera1Backend.Listener listener) {
        this.context = context;
        this.dir = dir;
        this.listener = listener;
    }

    @Override
    public void run() {
        long start = now();
        JSONObject summary = new JSONObject();
        put(summary, "test", "speed");
        put(summary, "file", dir.getName());
        try {
            int cameraId = Camera1Backend.backCameraId();
            camera = Camera.open(cameraId);
            texture = new SurfaceTexture(0);
            camera.setPreviewTexture(texture);
            writeText(new File(dir, "facts.json"),
                    CameraFacts.collect(context, cameraId, camera.getParameters()).toString(2));
            stillTests();
            previewTests();
            videoTests(cameraId);
            closeCamera1();
            if (Build.VERSION.SDK_INT >= 21) {
                for (JSONObject r : new Camera2StillTest(context, dir).run()) finish(r);
            }
            put(summary, "result", failed == 0 ? "ok" : "error");
            if (failed > 0) put(summary, "error", failed + " tests failed, see results.jsonl");
        } catch (Exception e) {
            put(summary, "result", "error");
            put(summary, "error", e.toString());
        } finally {
            closeCamera1();
        }
        put(summary, "tests", tests);
        put(summary, "failed", failed);
        put(summary, "total_s", (now() - start) / 1000);
        listener.onDone(summary);
    }

    private void closeCamera1() {
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.release();
            } catch (RuntimeException ignored) {
                // the camera is gone anyway
            }
            camera = null;
        }
        if (texture != null) {
            texture.release();
            texture = null;
        }
    }

    // ---- Full photos ------------------------------------------------------------------------

    private void stillTests() {
        List<Camera.Size> all = camera.getParameters().getSupportedPictureSizes();
        Camera.Size largest = Camera1Backend.largest(all);
        double full = (double) largest.width * largest.height;
        // The largest size and those closest to 1/2, 1/4 and 1/10 of its pixels, same shape.
        List<Camera.Size> picked = new ArrayList<>();
        for (double share : new double[] {1, 0.5, 0.25, 0.1}) {
            Camera.Size best = null;
            for (Camera.Size s : all) {
                if (Camera1Backend.sameShape(s, largest) && (best == null
                        || Math.abs(s.width * s.height - share * full)
                        < Math.abs(best.width * best.height - share * full))) {
                    best = s;
                }
            }
            if (best != null && !picked.contains(best)) picked.add(best);
        }
        for (Camera.Size size : picked) stillTest(size);
    }

    /** Photos one after the other, camera kept open: photo, save, restart preview, next. */
    private void stillTest(Camera.Size size) {
        JSONObject r = newRecord("still", size.width, size.height);
        try {
            camera.stopPreview();
            Camera.Parameters p = camera.getParameters();
            p.setPictureSize(size.width, size.height);
            Camera.Size preview = Camera1Backend.smallestWithSameShape(p.getSupportedPreviewSizes(), size);
            p.setPreviewSize(preview.width, preview.height);
            int[] fps = Camera1Backend.lowestFpsRange(p.getSupportedPreviewFpsRange());
            if (fps != null) p.setPreviewFpsRange(fps[0], fps[1]);
            boolean canFocus = setAutoFocusMode(p);
            camera.setParameters(p);
            camera.startPreview();
            Thread.sleep(WARM_UP_MS);
            if (canFocus) put(r, "focus", autoFocus());

            JSONArray photoMs = new JSONArray(), saveMs = new JSONArray(), restartMs = new JSONArray(),
                    cycleMs = new JSONArray(), kb = new JSONArray();
            long totalCycle = 0;
            for (int i = 1; i <= STILLS_PER_SIZE; i++) {
                long t0 = now();
                byte[] jpeg = takePicture();
                long t1 = now();
                writeBytes(new File(dir, name("still", size.width, size.height, i)), jpeg);
                long t2 = now();
                camera.startPreview(); // takePicture stops the preview
                waitForPreviewFrame();
                long t3 = now();
                photoMs.put(t1 - t0);
                saveMs.put(t2 - t1);
                restartMs.put(t3 - t2);
                cycleMs.put(t3 - t0);
                kb.put(jpeg.length / 1024);
                totalCycle += t3 - t0;
            }
            put(r, "photo_ms", photoMs);
            put(r, "save_ms", saveMs);
            put(r, "restart_preview_ms", restartMs);
            put(r, "cycle_ms", cycleMs);
            put(r, "mean_cycle_ms", totalCycle / STILLS_PER_SIZE);
            put(r, "kb", kb);
        } catch (Exception e) {
            put(r, "error", e.toString());
        }
        finish(r);
    }

    // ---- Preview frames ---------------------------------------------------------------------

    private void previewTests() {
        Camera.Parameters p = camera.getParameters();
        List<Camera.Size> sizes = p.getSupportedPreviewSizes();
        Camera.Size photo = Camera1Backend.largest(p.getSupportedPictureSizes());
        Camera.Size sameShape = null;
        for (Camera.Size s : sizes) {
            if (Camera1Backend.sameShape(s, photo)
                    && (sameShape == null || s.width * s.height > sameShape.width * sameShape.height)) {
                sameShape = s;
            }
        }
        Camera.Size biggest = Camera1Backend.largest(sizes);
        if (sameShape != null) previewTest(sameShape);
        if (!biggest.equals(sameShape)) previewTest(biggest);
    }

    /** Frames from the running preview, turned into JPEG on the processor (the plan's fast path). */
    private void previewTest(Camera.Size size) {
        JSONObject r = newRecord("preview", size.width, size.height);
        final LinkedBlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
        final AtomicInteger delivered = new AtomicInteger();
        try {
            camera.stopPreview();
            Camera.Parameters p = camera.getParameters();
            p.setPreviewSize(size.width, size.height);
            int[] fps = highestFpsRange(p.getSupportedPreviewFpsRange());
            p.setPreviewFpsRange(fps[0], fps[1]);
            boolean canFocus = setAutoFocusMode(p);
            camera.setParameters(p);
            put(r, "preview_fps_range", fps[0] / 1000.0 + "-" + fps[1] / 1000.0);
            int bytes = size.width * size.height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            for (int i = 0; i < 3; i++) camera.addCallbackBuffer(new byte[bytes]);
            camera.setPreviewCallbackWithBuffer((data, cam) -> {
                delivered.incrementAndGet();
                frames.offer(data);
            });
            camera.startPreview();
            recycleUntil(frames, now() + WARM_UP_MS);
            if (canFocus) put(r, "focus", autoFocus());
            int d0 = delivered.get();
            recycleUntil(frames, now() + 2000);
            put(r, "camera_frames_per_s", (delivered.get() - d0) / 2.0);

            Rect whole = new Rect(0, 0, size.width, size.height);
            int side = Math.min(ROI_SIDE, Math.min(size.width, size.height)) & ~1;
            int left = ((size.width - side) / 2) & ~1;
            int top = ((size.height - side) / 2) & ~1;
            Rect roi = new Rect(left, top, left + side, top + side);
            put(r, "roi_side", side);

            // One frame per second, saved whole and as the ROI square.
            JSONArray wholeMs = new JSONArray(), roiMs = new JSONArray(),
                    wholeKb = new JSONArray(), roiKb = new JSONArray();
            long cpu0 = android.os.Process.getElapsedCpuTime();
            long t0 = now();
            long next = t0;
            for (int i = 1; i <= SLOW_FRAMES; i++) {
                recycleUntil(frames, next); // keep frames flowing, so the next one is fresh
                next += 1000;
                byte[] frame = nextFrame(frames);
                long a = now();
                wholeKb.put(saveJpeg(frame, size, whole, name("preview_whole", size.width, size.height, i)) / 1024);
                long b = now();
                roiKb.put(saveJpeg(frame, size, roi, name("preview_roi", size.width, size.height, i)) / 1024);
                long c = now();
                camera.addCallbackBuffer(frame);
                wholeMs.put(b - a);
                roiMs.put(c - b);
            }
            put(r, "every_1s_whole_ms", wholeMs);
            put(r, "every_1s_roi_ms", roiMs);
            put(r, "every_1s_whole_kb", wholeKb);
            put(r, "every_1s_roi_kb", roiKb);
            put(r, "every_1s_app_cpu_percent", cpuPercent(cpu0, t0));

            // As fast as possible: save the ROI square of every frame we get.
            int saved = 0;
            cpu0 = android.os.Process.getElapsedCpuTime();
            t0 = now();
            while (now() - t0 < FAST_MS) {
                byte[] frame = frames.poll(100, TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                saveJpeg(frame, size, roi, name("preview_fast", size.width, size.height, ++saved));
                camera.addCallbackBuffer(frame);
            }
            put(r, "fast_roi_saved_per_s", Math.round(saved * 10000.0 / (now() - t0)) / 10.0);
            put(r, "fast_app_cpu_percent", cpuPercent(cpu0, t0));
        } catch (Exception e) {
            put(r, "error", e.toString());
        } finally {
            try {
                camera.setPreviewCallbackWithBuffer(null);
                camera.stopPreview();
            } catch (RuntimeException ignored) {
                // next test reports the problem
            }
        }
        finish(r);
    }

    /** Hands frames straight back to the camera until {@code deadline}. */
    private void recycleUntil(LinkedBlockingQueue<byte[]> frames, long deadline) throws InterruptedException {
        for (long left; (left = deadline - now()) > 0; ) {
            byte[] frame = frames.poll(left, TimeUnit.MILLISECONDS);
            if (frame != null) camera.addCallbackBuffer(frame);
        }
    }

    private static byte[] nextFrame(LinkedBlockingQueue<byte[]> frames) throws InterruptedException {
        byte[] frame = frames.poll(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (frame == null) throw new IllegalStateException("no preview frame after " + FRAME_TIMEOUT_MS + " ms");
        return frame;
    }

    /** JPEG of one rectangle of an NV21 preview frame; returns the file size in bytes. */
    private int saveJpeg(byte[] nv21, Camera.Size size, Rect rect, String fileName) throws IOException {
        File file = new File(dir, fileName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            new YuvImage(nv21, ImageFormat.NV21, size.width, size.height, null)
                    .compressToJpeg(rect, JPEG_QUALITY, out);
            out.getFD().sync();
        }
        return (int) file.length();
    }

    /** Processor time used by this app, as a share of one processor core. */
    private static long cpuPercent(long cpuStart, long wallStart) {
        long wall = Math.max(1, now() - wallStart);
        return 100 * (android.os.Process.getElapsedCpuTime() - cpuStart) / wall;
    }

    // ---- Video ------------------------------------------------------------------------------

    private void videoTests(int cameraId) {
        int[] qualities = {CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P,
            CamcorderProfile.QUALITY_480P};
        String[] names = {"1080p", "720p", "480p"};
        for (int i = 0; i < qualities.length; i++) {
            if (CamcorderProfile.hasProfile(cameraId, qualities[i])) {
                videoTest(CamcorderProfile.get(cameraId, qualities[i]), names[i], 15, 0);
            }
        }
        // Slower than the camera's slowest rate: time-lapse mode, where the recorder keeps only
        // some camera frames. (Asking 5 fps directly failed on the Samsung M12: "start failed".)
        if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_1080P)) {
            CamcorderProfile hd = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_1080P);
            videoTest(hd, "1080p", 15, 5);
            videoTest(hd, "1080p", 15, 1);
        }
    }

    /**
     * One 10 s video with MediaRecorder (Android's hardware video recorder) at {@code fps} frames
     * per second. If {@code capturePerS} is above 0, time-lapse mode keeps only that many frames per
     * second; the video then plays faster than real time (each frame is 1 / capturePerS s apart).
     */
    private void videoTest(CamcorderProfile profile, String name, int fps, int capturePerS) {
        JSONObject r = newRecord("video", profile.videoFrameWidth, profile.videoFrameHeight);
        put(r, "requested_fps", fps);
        if (capturePerS > 0) put(r, "time_lapse_frames_per_s", capturePerS);
        File file = new File(dir, capturePerS > 0
                ? String.format(Locale.US, "video_%s_timelapse_%dper_s.mp4", name, capturePerS)
                : String.format(Locale.US, "video_%s_%dfps.mp4", name, fps));
        MediaRecorder recorder = null;
        Surface surface = null;
        boolean unlocked = false;
        try {
            camera.stopPreview();
            Camera.Parameters p = camera.getParameters();
            int[] range = fpsRangeFor(p.getSupportedPreviewFpsRange(), fps);
            p.setPreviewFpsRange(range[0], range[1]);
            put(r, "preview_fps_range", range[0] / 1000.0 + "-" + range[1] / 1000.0);
            Camera.Size preview = p.getPreferredPreviewSizeForVideo();
            if (p.getSupportedVideoSizes() == null || preview == null) {
                // The recorder gets the preview frames, so they must have the video size.
                p.setPreviewSize(profile.videoFrameWidth, profile.videoFrameHeight);
            } else {
                p.setPreviewSize(preview.width, preview.height);
            }
            p.setRecordingHint(true);
            boolean canFocus = setAutoFocusMode(p);
            camera.setParameters(p);
            camera.startPreview();
            Thread.sleep(WARM_UP_MS);
            if (canFocus) put(r, "focus", autoFocus());

            camera.unlock(); // lets MediaRecorder use the camera
            unlocked = true;
            recorder = new MediaRecorder();
            recorder.setCamera(camera);
            recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
            recorder.setVideoFrameRate(fps);
            // Same bits per frame as the phone maker's preset.
            int bitRate = (int) ((long) profile.videoBitRate * fps / profile.videoFrameRate);
            recorder.setVideoEncodingBitRate(bitRate);
            put(r, "bit_rate_mbit_s", Math.round(bitRate / 1e5) / 10.0);
            if (capturePerS > 0) recorder.setCaptureRate(capturePerS);
            recorder.setOutputFile(file.getAbsolutePath());
            surface = new Surface(texture);
            recorder.setPreviewDisplay(surface);
            long t0 = now();
            recorder.prepare();
            recorder.start();
            long started = now();
            put(r, "start_ms", started - t0);
            Thread.sleep(VIDEO_MS);
            long t1 = now();
            recorder.stop();
            put(r, "recorded_ms", t1 - started);
            put(r, "stop_ms", now() - t1);
            put(r, "file", file.getName());
            put(r, "kb", file.length() / 1024);
        } catch (Exception e) {
            put(r, "error", e.toString());
        } finally {
            if (recorder != null) recorder.release();
            if (surface != null) surface.release();
            try {
                if (unlocked) camera.lock();
                camera.stopPreview();
            } catch (RuntimeException ignored) {
                // next test reports the problem
            }
        }
        finish(r);
    }

    /** The range that contains {@code fps} with the lowest maximum; else the lowest range. */
    private static int[] fpsRangeFor(List<int[]> ranges, int fps) {
        int target = fps * 1000;
        int[] best = null;
        for (int[] r : ranges) {
            int min = r[Camera.Parameters.PREVIEW_FPS_MIN_INDEX];
            int max = r[Camera.Parameters.PREVIEW_FPS_MAX_INDEX];
            if (min <= target && target <= max
                    && (best == null || max < best[1] || (max == best[1] && min > best[0]))) {
                best = new int[] {min, max};
            }
        }
        return best != null ? best : Camera1Backend.lowestFpsRange(ranges);
    }

    private static int[] highestFpsRange(List<int[]> ranges) {
        int[] best = null;
        for (int[] r : ranges) {
            int min = r[Camera.Parameters.PREVIEW_FPS_MIN_INDEX];
            int max = r[Camera.Parameters.PREVIEW_FPS_MAX_INDEX];
            if (best == null || max > best[1] || (max == best[1] && min > best[0])) best = new int[] {min, max};
        }
        return best;
    }

    // ---- Waiting for the camera -------------------------------------------------------------

    private static boolean setAutoFocusMode(Camera.Parameters p) {
        boolean can = p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO);
        if (can) p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
        return can;
    }

    private String autoFocus() throws InterruptedException {
        final boolean[] sharp = new boolean[1];
        final CountDownLatch done = new CountDownLatch(1);
        long t = now();
        camera.autoFocus((success, cam) -> {
            sharp[0] = success;
            done.countDown();
        });
        if (!done.await(FOCUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            camera.cancelAutoFocus();
            return "timeout";
        }
        return (sharp[0] ? "sharp" : "failed") + " after " + (now() - t) + " ms";
    }

    private byte[] takePicture() throws InterruptedException {
        final byte[][] jpeg = new byte[1][];
        final CountDownLatch done = new CountDownLatch(1);
        camera.takePicture(null, null, (data, cam) -> {
            jpeg[0] = data;
            done.countDown();
        });
        if (!done.await(PICTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("no photo after " + PICTURE_TIMEOUT_MS + " ms");
        }
        return jpeg[0];
    }

    private void waitForPreviewFrame() throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        camera.setOneShotPreviewCallback((data, cam) -> done.countDown());
        if (!done.await(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("no preview frame after " + FRAME_TIMEOUT_MS + " ms");
        }
    }

    // ---- Records and files ------------------------------------------------------------------

    static JSONObject newRecord(String test, int width, int height) {
        JSONObject r = new JSONObject();
        put(r, "test", test);
        put(r, "size", width + "x" + height);
        return r;
    }

    private void finish(JSONObject r) {
        tests++;
        if (r.has("error")) failed++;
        put(r, "battery_temp_c", CaptureService.batteryTempC(context));
        Log.i(CaptureService.TAG, r.toString());
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(new File(dir, "results.jsonl"), true), StandardCharsets.UTF_8)) {
            w.write(r.toString());
            w.write('\n');
        } catch (IOException e) {
            Log.e(CaptureService.TAG, "Cannot write results.jsonl", e);
        }
    }

    static String name(String kind, int width, int height, int i) {
        return String.format(Locale.US, "%s_%dx%d_%03d.jpg", kind, width, height, i);
    }

    static void writeBytes(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            out.getFD().sync();
        }
    }

    private static void writeText(File file, String text) throws IOException {
        writeBytes(file, text.getBytes(StandardCharsets.UTF_8));
    }

    static long now() {
        return SystemClock.elapsedRealtime();
    }

    static void put(JSONObject record, String key, Object value) {
        try {
            record.put(key, value);
        } catch (JSONException ignored) {
            // Only thrown for NaN or infinite numbers, which we never write.
        }
    }
}
