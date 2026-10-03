package com.faunalapse.app;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Takes one photo with the old Camera1 interface (android.hardware.Camera), without showing a
 * preview on screen. Steps: open the back camera, start an invisible preview, wait for the wake
 * lead so exposure can settle, autofocus once, take the photo, save the JPEG, close the camera.
 *
 * All calls run on one background thread (the "camera thread" behind {@code handler}); Camera1
 * delivers its callbacks on the thread that opened the camera. The time of each step is written
 * into a record, so phase 0 can compare phones.
 */
@SuppressWarnings("deprecation") // Camera1 is deprecated since Android 5 but works on every version.
final class Camera1Backend {

    interface Listener {
        /** Called once, on the camera thread, after the camera is closed again. */
        void onDone(JSONObject record);
    }

    /** Time from camera open to photo, so exposure and white balance can settle. The plan starts
     *  at 5 s; phase 0 measures what each phone needs. */
    static final long WAKE_LEAD_MS = 5000;
    /** Take the photo anyway if autofocus has not answered after this time. */
    private static final long FOCUS_TIMEOUT_MS = 3000;
    /** Give up if the photo has not arrived after this time. */
    private static final long PICTURE_TIMEOUT_MS = 10000;
    /** Log the preview brightness this often. */
    private static final long BRIGHTNESS_STEP_MS = 500;

    private final Handler handler;
    private final File dir;
    private final Listener listener;
    private final JSONObject record = new JSONObject();

    private Camera camera;
    private SurfaceTexture previewTexture;
    private long startMs;
    private int previewFrames;
    private final JSONArray previewBrightness = new JSONArray();
    private long nextBrightnessMs;
    private boolean pictureRequested;
    private boolean finished;

    Camera1Backend(Handler handler, File dir, Listener listener) {
        this.handler = handler;
        this.dir = dir;
        this.listener = listener;
    }

    void start() {
        handler.post(this::openAndPreview);
    }

    private void openAndPreview() {
        startMs = SystemClock.elapsedRealtime();
        try {
            int id = backCameraId();
            camera = Camera.open(id);
            put("open_ms", sinceStart());
            camera.setErrorCallback((error, cam) -> finish("camera error " + error));

            Camera.Parameters p = camera.getParameters();
            Camera.Size picture = largest(p.getSupportedPictureSizes());
            p.setPictureSize(picture.width, picture.height);
            // Smallest preview with the same shape as the photo: some phones crop the photo to
            // the preview shape, and a small preview means less work for the camera chip.
            Camera.Size preview = smallestWithSameShape(p.getSupportedPreviewSizes(), picture);
            p.setPreviewSize(preview.width, preview.height);
            int[] fps = lowestFpsRange(p.getSupportedPreviewFpsRange());
            if (fps != null) p.setPreviewFpsRange(fps[0], fps[1]);
            boolean canFocus = p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO);
            if (canFocus) p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            camera.setParameters(p);

            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(id, info);
            put("camera_id", id);
            put("sensor_orientation", info.orientation);
            put("picture_size", picture.width + "x" + picture.height);
            put("preview_size", preview.width + "x" + preview.height);
            // Camera1 gives frame rates in frames per 1000 s.
            if (fps != null) put("preview_fps", fps[0] / 1000.0 + "-" + fps[1] / 1000.0);
            put("focus_mode", p.getFocusMode());
            put("jpeg_quality", p.getJpegQuality());
            // Some countries require the shutter sound; then the phone refuses to switch it off.
            put("shutter_sound_off", info.canDisableShutterSound && camera.enableShutterSound(false));

            // Invisible preview: frames go to a texture that is never drawn. A callback counts
            // the frames (does the preview run with the screen off?) and logs their brightness
            // (when has exposure settled?).
            previewTexture = new SurfaceTexture(0);
            camera.setPreviewTexture(previewTexture);
            final int pixels = preview.width * preview.height;
            camera.addCallbackBuffer(new byte[pixels * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8]);
            camera.setPreviewCallbackWithBuffer((data, cam) -> {
                if (previewFrames++ == 0) put("first_frame_ms", sinceStart());
                long t = sinceStart();
                if (t >= nextBrightnessMs) {
                    previewBrightness.put(new JSONArray().put(t).put(meanBrightness(data, pixels)));
                    nextBrightnessMs = t + BRIGHTNESS_STEP_MS;
                }
                cam.addCallbackBuffer(data);
            });
            put("preview_brightness", previewBrightness); // pairs: [ms since start, 0 to 255]
            camera.startPreview();
            put("preview_started_ms", sinceStart());

            long wait = Math.max(0, WAKE_LEAD_MS - sinceStart());
            handler.postDelayed(canFocus ? this::focus : this::takePicture, wait);
        } catch (RuntimeException | IOException e) {
            finish("open: " + e);
        }
    }

    /** Autofocus once; in FOCUS_MODE_AUTO the lens then stays where it is. */
    private void focus() {
        if (finished) return;
        final long t = SystemClock.elapsedRealtime();
        final Runnable timeout = () -> {
            put("focus_result", "timeout");
            takePicture();
        };
        handler.postDelayed(timeout, FOCUS_TIMEOUT_MS);
        try {
            camera.autoFocus((success, cam) -> {
                handler.removeCallbacks(timeout);
                put("focus_ms", SystemClock.elapsedRealtime() - t);
                put("focus_result", success ? "sharp" : "failed");
                takePicture();
            });
        } catch (RuntimeException e) {
            handler.removeCallbacks(timeout);
            put("focus_result", "error: " + e);
            takePicture();
        }
    }

    private void takePicture() {
        if (finished || pictureRequested) return;
        pictureRequested = true;
        put("preview_frames", previewFrames);
        put("lead_ms", sinceStart());
        long now = System.currentTimeMillis();
        put("time", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date(now)));
        // Named by time, so files sort correctly across midnight.
        final File file = new File(dir,
                new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date(now)) + ".jpg");
        final long t = SystemClock.elapsedRealtime();
        handler.postDelayed(() -> finish("no photo after " + PICTURE_TIMEOUT_MS + " ms"),
                PICTURE_TIMEOUT_MS);
        try {
            camera.takePicture(null, null, (jpeg, cam) -> {
                put("picture_ms", SystemClock.elapsedRealtime() - t);
                save(jpeg, file);
            });
        } catch (RuntimeException e) {
            finish("takePicture: " + e);
        }
    }

    private void save(byte[] jpeg, File file) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(jpeg);
            out.getFD().sync(); // make sure the photo is on disk even if the phone dies next
        } catch (IOException e) {
            finish("save: " + e);
            return;
        }
        put("file", file.getName());
        put("bytes", jpeg.length);
        finish(null);
    }

    /** Closes the camera and reports. {@code error} is null when the photo was saved. */
    private void finish(String error) {
        if (finished) return;
        finished = true;
        handler.removeCallbacksAndMessages(null); // drop pending steps and timeouts
        if (camera != null) {
            try {
                camera.setPreviewCallbackWithBuffer(null);
                camera.stopPreview();
                camera.release();
            } catch (RuntimeException e) {
                put("close_error", e.toString());
            }
            camera = null;
        }
        if (previewTexture != null) {
            previewTexture.release();
            previewTexture = null;
        }
        put("camera_closed_ms", sinceStart());
        put("result", error == null ? "ok" : "error");
        if (error != null) put("error", error);
        listener.onDone(record);
    }

    private long sinceStart() {
        return SystemClock.elapsedRealtime() - startMs;
    }

    private void put(String key, Object value) {
        try {
            record.put(key, value);
        } catch (JSONException ignored) {
            // Only thrown for NaN or infinite numbers, which we never write.
        }
    }

    /** Mean brightness (0 to 255) of a preview frame. In the NV21 format the first
     *  {@code pixels} bytes are the brightness of each pixel; every 16th is enough. */
    private static int meanBrightness(byte[] nv21, int pixels) {
        long sum = 0;
        int n = 0;
        for (int i = 0; i < pixels; i += 16, n++) sum += nv21[i] & 0xFF;
        return (int) (sum / n);
    }

    static int backCameraId() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        return 0; // no back camera: use the first one
    }

    static Camera.Size largest(List<Camera.Size> sizes) {
        Camera.Size best = sizes.get(0);
        for (Camera.Size s : sizes) {
            if ((long) s.width * s.height > (long) best.width * best.height) best = s;
        }
        return best;
    }

    /** Same width-to-height ratio, within 1 %. */
    static boolean sameShape(Camera.Size a, Camera.Size b) {
        return Math.abs((double) a.width * b.height - (double) a.height * b.width)
                <= 0.01 * a.height * b.width;
    }

    static Camera.Size smallestWithSameShape(List<Camera.Size> sizes, Camera.Size target) {
        Camera.Size best = null;
        for (Camera.Size s : sizes) {
            if (sameShape(s, target) && (best == null || s.width * s.height < best.width * best.height)) {
                best = s;
            }
        }
        if (best != null) return best;
        for (Camera.Size s : sizes) {
            if (best == null || s.width * s.height < best.width * best.height) best = s;
        }
        return best;
    }

    /** The range with the lowest maximum frame rate (then the lowest minimum): least work. */
    static int[] lowestFpsRange(List<int[]> ranges) {
        if (ranges == null) return null;
        int[] best = null;
        for (int[] r : ranges) {
            int max = r[Camera.Parameters.PREVIEW_FPS_MAX_INDEX];
            int min = r[Camera.Parameters.PREVIEW_FPS_MIN_INDEX];
            if (best == null || max < best[1] || (max == best[1] && min < best[0])) {
                best = new int[] {min, max};
            }
        }
        return best;
    }
}
