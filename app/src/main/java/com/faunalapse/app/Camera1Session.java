package com.faunalapse.app;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.view.Surface;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Session camera through the old Camera1 interface, for "legacy" phones.
 *
 * Must be used from a thread without a message loop (Looper): Camera1 then delivers its callbacks on
 * the main thread, while this thread waits for them.
 */
@SuppressWarnings("deprecation") // Camera1 and CamcorderProfile.get() still work on every version
final class Camera1Session implements SessionCamera {

    private static final long FOCUS_TIMEOUT_MS = 3000;
    private static final long PICTURE_TIMEOUT_MS = 10000;
    private static final long FRAME_TIMEOUT_MS = 2000;

    private final boolean video;
    private final int videoQuality;
    private final int fps;
    private final JSONObject facts = new JSONObject();
    private Camera camera;
    private SurfaceTexture texture;
    private Surface recorderPreview;
    private MediaRecorder recorder;
    private CamcorderProfile profile;
    private boolean canFocus;
    private boolean unlocked;
    private boolean recording;

    Camera1Session(boolean video, int videoQuality, int fps) {
        this.video = video;
        this.videoQuality = videoQuality;
        this.fps = fps;
    }

    /** The phone maker's video preset for {@code quality}, or the next lower one that exists. */
    static CamcorderProfile videoProfile(int cameraId, int quality) {
        int[] order = {CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P,
            CamcorderProfile.QUALITY_480P, CamcorderProfile.QUALITY_LOW};
        boolean reached = false;
        for (int q : order) {
            reached |= q == quality;
            if (reached && CamcorderProfile.hasProfile(cameraId, q)) return CamcorderProfile.get(cameraId, q);
        }
        return CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_LOW);
    }

    /** Bit rate for {@code fps}: the same bits per frame as the phone maker's preset. */
    static int bitRate(CamcorderProfile profile, int fps) {
        return (int) ((long) profile.videoBitRate * fps / profile.videoFrameRate);
    }

    @Override
    public void open() throws Exception {
        int id = Camera1Backend.backCameraId();
        camera = Camera.open(id);
        texture = new SurfaceTexture(0); // invisible preview: frames are never drawn
        camera.setPreviewTexture(texture);
        Camera.Parameters p = camera.getParameters();
        canFocus = p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO);
        if (canFocus) p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
        put("camera_api", "camera1");
        put("camera_id", id);
        if (video) {
            profile = videoProfile(id, videoQuality);
            int[] range = Camera1Backend.fpsRangeFor(p.getSupportedPreviewFpsRange(), fps);
            p.setPreviewFpsRange(range[0], range[1]);
            Camera.Size preview = p.getPreferredPreviewSizeForVideo();
            if (p.getSupportedVideoSizes() == null || preview == null) {
                // The recorder gets the preview frames, so they must have the video size.
                p.setPreviewSize(profile.videoFrameWidth, profile.videoFrameHeight);
            } else {
                p.setPreviewSize(preview.width, preview.height);
            }
            p.setRecordingHint(true);
            put("video_size", profile.videoFrameWidth + "x" + profile.videoFrameHeight);
            put("video_fps", fps);
            put("video_bit_rate_mbit_s", Math.round(bitRate(profile, fps) / 1e5) / 10.0);
        } else {
            Camera.Size picture = Camera1Backend.largest(p.getSupportedPictureSizes());
            p.setPictureSize(picture.width, picture.height);
            Camera.Size preview = Camera1Backend.smallestWithSameShape(p.getSupportedPreviewSizes(), picture);
            p.setPreviewSize(preview.width, preview.height);
            int[] range = Camera1Backend.lowestFpsRange(p.getSupportedPreviewFpsRange());
            if (range != null) p.setPreviewFpsRange(range[0], range[1]);
            put("photo_size", picture.width + "x" + picture.height);
        }
        camera.setParameters(p);
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(id, info);
        put("sensor_orientation", info.orientation);
        put("focus", canFocus ? "auto, once per burst" : "fixed");
        if (info.canDisableShutterSound) put("shutter_sound_off", camera.enableShutterSound(false));
        camera.startPreview();
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
    public String prepareClip(File file) throws Exception {
        String focusResult = focus(); // before unlock: the app cannot use the camera after it
        camera.unlock();
        unlocked = true;
        recorder = new MediaRecorder();
        recorder.setCamera(camera);
        recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        recorder.setVideoFrameRate(fps);
        recorder.setVideoEncodingBitRate(bitRate(profile, fps));
        recorder.setOutputFile(file.getAbsolutePath());
        recorderPreview = new Surface(texture);
        recorder.setPreviewDisplay(recorderPreview);
        recorder.prepare();
        return focusResult;
    }

    @Override
    public void startClip() {
        recorder.start();
        recording = true;
    }

    @Override
    public void stopClip() {
        try {
            recorder.stop(); // throws if no frame arrived; the clip file is then useless
        } finally {
            recording = false;
            releaseRecorder();
        }
    }

    private void releaseRecorder() {
        if (recorder != null) recorder.release();
        recorder = null;
        if (recorderPreview != null) recorderPreview.release();
        recorderPreview = null;
        if (unlocked) {
            try {
                camera.lock();
                camera.startPreview(); // keep exposure running in a short break
            } catch (RuntimeException ignored) {
                // the next clip or the close reports camera problems
            }
            unlocked = false;
        }
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
            } catch (RuntimeException ignored) {
                // closing anyway
            }
        }
        releaseRecorder();
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.release();
            } catch (RuntimeException ignored) {
                // the camera is gone anyway
            }
            camera = null;
        }
        if (texture != null) texture.release();
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
