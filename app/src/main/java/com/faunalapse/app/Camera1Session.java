package com.faunalapse.app;

import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Session camera through the old Camera1 interface, for "legacy" phones. Video: SquareRecorder cuts
 * the ROI square out of the preview frames (Camera1 has no hardware crop) and records it.
 *
 * Must be used from a thread without a message loop (Looper): Camera1 then delivers its callbacks on
 * the main thread, while this thread waits for them.
 */
@SuppressWarnings("deprecation") // Camera1 still works on every version
final class Camera1Session implements SessionCamera {

    private static final long FOCUS_TIMEOUT_MS = 3000;
    private static final long PICTURE_TIMEOUT_MS = 10000;
    private static final long FRAME_TIMEOUT_MS = 2000;

    private final boolean video;
    private final int squareSide; // the saved side asked for
    private final int fps;
    private final SurfaceTexture shown;
    private final JSONObject facts = new JSONObject();
    private int cameraId;
    private Camera camera;
    private Camera.Size photoSize;   // the full photo: the square's frame of reference, also for video
    private Camera.Size previewSize;
    private boolean focusOnSquare;
    private boolean exposureOnSquare;
    private SurfaceTexture texture;
    private SquareRecorder recorder; // video session only (not on the aim screen)
    private SquareRecorder.Encoder encoder;
    private int bitRate;
    private boolean canFocus;
    private boolean recording;

    /** {@code shown}: the aim screen's view to show the preview on, or null for none. */
    Camera1Session(boolean video, int squareSide, int fps, SurfaceTexture shown) {
        this.video = video;
        this.squareSide = squareSide;
        this.fps = fps;
        this.shown = shown;
    }

    @Override
    public void open() throws Exception {
        cameraId = Camera1Backend.backCameraId();
        camera = Camera.open(cameraId);
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        // The aim screen is always upright (portrait), so the view turns by the sensor orientation.
        if (shown != null) camera.setDisplayOrientation(info.orientation);
        Camera.Parameters p = camera.getParameters();
        canFocus = p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO);
        if (canFocus) p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
        put("camera_api", "camera1");
        put("camera_id", cameraId);
        put("sensor_orientation", info.orientation);
        Camera.Size picture = Camera1Backend.largest(p.getSupportedPictureSizes());
        photoSize = picture;
        focusOnSquare = canFocus && p.getMaxNumFocusAreas() > 0;
        exposureOnSquare = p.getMaxNumMeteringAreas() > 0;
        put("photo_size", picture.width + "x" + picture.height);
        put("focus_on_square", focusOnSquare);
        put("exposure_on_square", exposureOnSquare);
        List<Camera.Size> previews = p.getSupportedPreviewSizes();
        if (video) {
            List<int[]> ranges = p.getSupportedPreviewFpsRange();
            int[][] perS = new int[ranges.size()][];
            for (int i = 0; i < perS.length; i++) {
                perS[i] = new int[] {ranges.get(i)[0] / 1000, ranges.get(i)[1] / 1000};
            }
            // A fixed camera rate that is a multiple of fps keeps the kept frames evenly spaced.
            int[] fixed = FrameRates.fixedRateFor(perS, fps);
            int[] range = fixed != null ? new int[] {fixed[0] * 1000, fixed[0] * 1000} : Camera1Backend.fpsRangeFor(ranges, fps);
            p.setPreviewFpsRange(range[0], range[1]);
            // The square is cut from the preview frames: the largest of the photo's shape (up to 1920
            // wide, as on the aim screen) gives it the most pixels.
            Camera.Size preview = Camera1Backend.largestWithSameShape(previews, picture, 1920);
            p.setPreviewSize(preview.width, preview.height);
            put("video_fps", fps);
            put("video_zoom", "none"); // Camera1 has no hardware crop
            put("preview_fps_range", range[0] / 1000 + "-" + range[1] / 1000);
        } else {
            p.setPictureSize(picture.width, picture.height);
            // On the aim screen, frames sharp enough to judge focus; otherwise the smallest.
            Camera.Size preview = shown != null ? Camera1Backend.largestWithSameShape(previews, picture, 1920)
                    : Camera1Backend.smallestWithSameShape(previews, picture);
            p.setPreviewSize(preview.width, preview.height);
            int[] range = Camera1Backend.lowestFpsRange(p.getSupportedPreviewFpsRange());
            if (range != null) p.setPreviewFpsRange(range[0], range[1]);
        }
        camera.setParameters(p);
        previewSize = camera.getParameters().getPreviewSize();
        put("preview_size", previewSize.width + "x" + previewSize.height);
        if (video) put("video_stream_size", previewSize.width + "x" + previewSize.height);
        if (shown == null && video) {
            recorder = new SquareRecorder(previewSize.width, previewSize.height);
            texture = recorder.texture();
        } else {
            texture = shown != null ? shown : new SurfaceTexture(0); // invisible: frames are never drawn
        }
        camera.setPreviewTexture(texture);
        put("focus", canFocus ? "auto, once per " + (video ? "clip" : "burst") : "fixed");
        put("fixed_focus_possible", false); // Camera1 cannot set a lens distance
        if (info.canDisableShutterSound) put("shutter_sound_off", camera.enableShutterSound(false));
        camera.startPreview();
    }

    @Override
    public void setSquare(double sideShare, double centreX, double centreY) throws Exception {
        int[] r = RoiMath.square(photoSize.width, photoSize.height, sideShare, centreX, centreY);
        if (focusOnSquare || exposureOnSquare) {
            int[] a = RoiMath.camera1Area(photoSize.width, photoSize.height, r);
            List<Camera.Area> areas = Collections.singletonList(new Camera.Area(new Rect(a[0], a[1], a[2], a[3]), 1000));
            Camera.Parameters p = camera.getParameters();
            if (focusOnSquare) p.setFocusAreas(areas);
            if (exposureOnSquare) p.setMeteringAreas(areas);
            camera.setParameters(p);
        }
        if (recorder != null) {
            RoiMath.VideoSquare v = RoiMath.videoSquare(photoSize.width, photoSize.height, photoSize.width,
                    photoSize.height, previewSize.width, previewSize.height, 1, "none", sideShare, centreX, centreY);
            recorder.setSquare(v.inStream);
            encoder = SquareRecorder.encoderFor(RoiMath.savedSide(v.realPx, squareSide), fps);
            bitRate = SquareRecorder.bitRate(cameraId, encoder.side, fps);
            put("square_real_px", v.realPx);
            put("saved_side_px", encoder.side);
            put("video_encoder", encoder.name + (encoder.hardware ? "" : " (software)"));
            put("video_bit_rate_asked_mbit_s", Math.round(bitRate / 1e5) / 10.0);
        }
    }

    @Override
    public String focus() throws InterruptedException {
        if (!canFocus) return "fixed focus";
        final boolean[] sharp = new boolean[1];
        final CountDownLatch done = new CountDownLatch(1);
        long t = SpeedTest.now();
        camera.autoFocus((success, cam) -> {
            sharp[0] = success;
            done.countDown();
        });
        if (!done.await(FOCUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            camera.cancelAutoFocus();
            return "timeout";
        }
        return (sharp[0] ? "sharp" : "failed") + " after " + (SpeedTest.now() - t) + " ms";
    }

    @Override
    public byte[] takePhoto() throws InterruptedException {
        final byte[][] jpeg = new byte[1][];
        final CountDownLatch done = new CountDownLatch(1);
        camera.takePicture(null, null, (data, cam) -> {
            jpeg[0] = data;
            done.countDown();
        });
        if (!done.await(PICTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("no photo after " + PICTURE_TIMEOUT_MS + " ms");
        }
        // takePicture stops the preview; restart it and wait for a frame, so the next photo works.
        camera.startPreview();
        final CountDownLatch frame = new CountDownLatch(1);
        camera.setOneShotPreviewCallback((data, cam) -> frame.countDown());
        if (!frame.await(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("no preview frame after " + FRAME_TIMEOUT_MS + " ms");
        }
        return jpeg[0];
    }

    @Override
    public void setFixedFocus(Float diopters) {
        // not possible with Camera1: focus() keeps autofocusing
    }

    @Override
    public float lensDiopters() {
        return Float.NaN;
    }

    @Override
    public void putExposure(JSONObject r) {
        // Camera1 has no per-photo exposure values; its EXIF values are not reliable (docs/PHONES.md).
    }

    @Override
    public String prepareClip(File file, int turn) throws Exception {
        recorder.prepare(file, encoder, bitRate, fps, turn);
        return focus();
    }

    @Override
    public void startClip() throws Exception {
        recorder.start();
        recording = true;
    }

    @Override
    public JSONObject stopClip() throws Exception {
        recording = false;
        JSONObject r = new JSONObject();
        SessionLog.put(r, "frames", recorder.stop());
        return r;
    }

    @Override
    public JSONObject describe() {
        return facts;
    }

    @Override
    public void close() {
        if (recording) {
            try {
                stopClip();
            } catch (Exception ignored) {
                // closing anyway
            }
        }
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.release();
            } catch (RuntimeException ignored) {
                // the camera is gone anyway
            }
            camera = null;
        }
        if (recorder != null) { // after the camera: no frames arrive any more
            recorder.release();
            recorder = null;
            texture = null;
        }
        if (texture != null && texture != shown) texture.release(); // the aim screen owns its view
        texture = null;
    }

    private void put(String key, Object value) {
        try {
            facts.put(key, value);
        } catch (JSONException ignored) {
            // Only thrown for NaN or infinite numbers, which we never write.
        }
    }
}
