package com.faunalapse.app;

import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.view.Surface;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
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
    private final SurfaceTexture shown;
    private final JSONObject facts = new JSONObject();
    private Camera camera;
    private Camera.Size photoSize;
    private boolean focusOnSquare;
    private boolean exposureOnSquare;
    private SurfaceTexture texture;
    private Surface recorderPreview;
    private MediaRecorder recorder;
    private CamcorderProfile profile;
    private int cameraFps;     // video: the fixed camera rate, a multiple of fps (FrameRates)
    private int keepEvery = 1; // video: only every n-th camera frame goes into the clip
    private File clipFile;
    private boolean canFocus;
    private boolean unlocked;
    private boolean recording;

    /** {@code shown}: the aim screen's view to show the preview on, or null for none. */
    Camera1Session(boolean video, int videoQuality, int fps, SurfaceTexture shown) {
        this.video = video;
        this.videoQuality = videoQuality;
        this.fps = fps;
        this.shown = shown;
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
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(id, info);
        texture = shown != null ? shown : new SurfaceTexture(0); // invisible: frames are never drawn
        camera.setPreviewTexture(texture);
        // The aim screen is always upright (portrait), so the view turns by the sensor orientation.
        if (shown != null) camera.setDisplayOrientation(info.orientation);
        Camera.Parameters p = camera.getParameters();
        canFocus = p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO);
        if (canFocus) p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
        put("camera_api", "camera1");
        put("camera_id", id);
        put("sensor_orientation", info.orientation);
        if (video) {
            profile = videoProfile(id, videoQuality);
            List<int[]> ranges = p.getSupportedPreviewFpsRange();
            int[][] perS = new int[ranges.size()][];
            for (int i = 0; i < perS.length; i++) {
                perS[i] = new int[] {ranges.get(i)[0] / 1000, ranges.get(i)[1] / 1000};
            }
            int[] fixed = FrameRates.fixedRateFor(perS, fps);
            if (fixed != null) {
                cameraFps = fixed[0];
                keepEvery = fixed[1];
                p.setPreviewFpsRange(cameraFps * 1000, cameraFps * 1000);
            } else {
                cameraFps = fps; // the clip then gets every camera frame
                int[] range = Camera1Backend.fpsRangeFor(ranges, fps);
                p.setPreviewFpsRange(range[0], range[1]);
            }
            Camera.Size preview = p.getPreferredPreviewSizeForVideo();
            if (shown != null) {
                // The aim screen shows the video's own shape (the preview for recording may be 4:3).
                preview = Camera1Backend.largestWithSameShape(p.getSupportedPreviewSizes(),
                        camera.new Size(profile.videoFrameWidth, profile.videoFrameHeight), 1920);
                p.setPreviewSize(preview.width, preview.height);
            } else if (p.getSupportedVideoSizes() == null || preview == null) {
                // The recorder gets the preview frames, so they must have the video size.
                p.setPreviewSize(profile.videoFrameWidth, profile.videoFrameHeight);
            } else {
                p.setPreviewSize(preview.width, preview.height);
            }
            p.setRecordingHint(true);
            put("video_size", profile.videoFrameWidth + "x" + profile.videoFrameHeight);
            put("video_fps", fps);
            put("video_keeps_every_nth_camera_frame", keepEvery);
            put("video_bit_rate_asked_mbit_s", Math.round(bitRate(profile, fps) / 1e5) / 10.0);
        } else {
            Camera.Size picture = Camera1Backend.largest(p.getSupportedPictureSizes());
            p.setPictureSize(picture.width, picture.height);
            // On the aim screen, frames sharp enough to judge focus; otherwise the smallest.
            List<Camera.Size> previews = p.getSupportedPreviewSizes();
            Camera.Size preview = shown != null ? Camera1Backend.largestWithSameShape(previews, picture, 1920)
                    : Camera1Backend.smallestWithSameShape(previews, picture);
            p.setPreviewSize(preview.width, preview.height);
            int[] range = Camera1Backend.lowestFpsRange(p.getSupportedPreviewFpsRange());
            if (range != null) p.setPreviewFpsRange(range[0], range[1]);
            photoSize = picture;
            focusOnSquare = canFocus && p.getMaxNumFocusAreas() > 0;
            exposureOnSquare = p.getMaxNumMeteringAreas() > 0;
            put("photo_size", picture.width + "x" + picture.height);
            put("focus_on_square", focusOnSquare);
            put("exposure_on_square", exposureOnSquare);
        }
        camera.setParameters(p);
        Camera.Size preview = camera.getParameters().getPreviewSize();
        put("preview_size", preview.width + "x" + preview.height);
        put("focus", canFocus ? "auto, once per burst" : "fixed");
        if (info.canDisableShutterSound) put("shutter_sound_off", camera.enableShutterSound(false));
        camera.startPreview();
    }

    @Override
    public void setSquare(double sideShare, double centreX, double centreY) {
        if (!focusOnSquare && !exposureOnSquare) return;
        int[] r = RoiMath.square(photoSize.width, photoSize.height, sideShare, centreX, centreY);
        int[] a = RoiMath.camera1Area(photoSize.width, photoSize.height, r);
        List<Camera.Area> areas = Collections.singletonList(new Camera.Area(new Rect(a[0], a[1], a[2], a[3]), 1000));
        Camera.Parameters p = camera.getParameters();
        if (focusOnSquare) p.setFocusAreas(areas);
        if (exposureOnSquare) p.setMeteringAreas(areas);
        camera.setParameters(p);
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
        // Below the camera's rate: time-lapse mode keeps every n-th frame (asked halfway between the
        // (n-1)-th and the n-th, so small delays do not matter), but the file then plays n times
        // too fast; stopClip() fixes the timing.
        recorder.setVideoFrameRate(cameraFps);
        recorder.setVideoEncodingBitRate(bitRate(profile, cameraFps));
        if (keepEvery > 1) recorder.setCaptureRate(cameraFps / (keepEvery - 0.5));
        recorder.setOutputFile(file.getAbsolutePath());
        clipFile = file;
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
    public void stopClip() throws IOException {
        try {
            recorder.stop(); // throws if no frame arrived; the clip file is then useless
        } finally {
            recording = false;
            releaseRecorder();
        }
        if (keepEvery > 1) slowDown(clipFile, keepEvery);
    }

    /** Copies the clip with each frame's time stamp times {@code factor} (no new encoding), so a
     *  time-lapse clip plays in real time. Keeps the fast clip when this fails. */
    static void slowDown(File file, int factor) throws IOException {
        File copy = new File(file.getPath() + ".part");
        MediaExtractor in = new MediaExtractor();
        MediaMuxer out = null;
        try {
            in.setDataSource(file.getPath());
            MediaFormat format = in.getTrackFormat(0); // the only track: no sound is recorded
            in.selectTrack(0);
            out = new MediaMuxer(copy.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = out.addTrack(format);
            out.start();
            ByteBuffer buffer = ByteBuffer.allocate(format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 4 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int size;
            while ((size = in.readSampleData(buffer, 0)) >= 0) {
                boolean key = (in.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                info.set(0, size, in.getSampleTime() * factor, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                out.writeSampleData(track, buffer, info);
                in.advance();
            }
            out.stop();
        } catch (RuntimeException e) {
            copy.delete();
            throw new IOException("the clip plays " + factor + " times too fast: " + e, e);
        } finally {
            in.release();
            if (out != null) out.release();
        }
        if (!copy.renameTo(file)) throw new IOException("the clip plays " + factor + " times too fast");
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
            } catch (IOException | RuntimeException ignored) {
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
