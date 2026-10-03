package com.faunalapse.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Session camera through Camera2, used on phones whose Camera2 level is "limited" or
 * better. Photos: preview (invisible, or on the aim screen) plus a full-size JPEG output, made by
 * the camera hardware. Video: MediaRecorder (Android's hardware video recorder) fed straight by the
 * camera.
 *
 * Camera2 calls back on its own thread ("camera2"); the session thread waits for the answers.
 */
final class Camera2Session implements SessionCamera {

    private static final long TIMEOUT_MS = 5000;
    private static final long FOCUS_TIMEOUT_MS = 3000;
    private static final byte JPEG_QUALITY = 90;

    private final Context context;
    private final boolean video;
    private final int videoQuality;
    private final int fps;
    private final SurfaceTexture shown;
    private final JSONObject facts = new JSONObject();
    private final LinkedBlockingQueue<byte[]> jpegs = new LinkedBlockingQueue<>();
    /** Tag of the request that starts autofocus: focus states count from its result on. */
    private static final Object FOCUS_START = new Object();
    private volatile Integer afState;
    private volatile boolean focusStarted;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private SurfaceTexture texture;
    private Surface preview;
    private CaptureRequest.Builder previewRequest;
    private CaptureRequest stillRequest;
    private MediaRecorder recorder;
    private Surface recorderSurface;
    private CamcorderProfile profile;
    private Range<Integer> fpsRange;
    private Size photoSize;
    private Rect pixelArray;
    private boolean focusOnSquare;
    private boolean exposureOnSquare;
    private MeteringRectangle[] square;
    private boolean canFocus;
    private boolean recording;

    private final CameraCaptureSession.CaptureCallback afWatcher = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
            // Earlier frames still report the last focus ("sharp" in a short break).
            if (r.getTag() == FOCUS_START) focusStarted = true;
            if (focusStarted) afState = result.get(CaptureResult.CONTROL_AF_STATE);
        }
    };

    /** {@code shown}: the aim screen's view to show the preview on, or null for none. */
    Camera2Session(Context context, boolean video, int videoQuality, int fps, SurfaceTexture shown) {
        this.context = context;
        this.video = video;
        this.videoQuality = videoQuality;
        this.fps = fps;
        this.shown = shown;
    }

    /** True when the back camera's Camera2 level is better than "legacy" (a Camera1 wrapper). */
    static boolean suitable(Context context) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            Integer level = manager.getCameraCharacteristics(backCameraId(manager))
                    .get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            return level != null && level != CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void open() throws Exception {
        thread = new HandlerThread("camera2");
        thread.start();
        handler = new Handler(thread.getLooper());
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        String id = backCameraId(manager);
        CameraCharacteristics c = manager.getCameraCharacteristics(id);
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        int[] afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (afModes != null) for (int m : afModes) canFocus |= m == CameraMetadata.CONTROL_AF_MODE_AUTO;
        Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Integer afRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        Integer aeRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        focusOnSquare = !video && canFocus && pixelArray != null && afRegions != null && afRegions > 0;
        exposureOnSquare = !video && pixelArray != null && aeRegions != null && aeRegions > 0;
        put("camera_api", "camera2");
        put("camera_id", id);
        Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        put("sensor_orientation", orientation == null ? 0 : orientation);
        put("focus", canFocus ? "auto, once per burst" : "fixed");

        Size shape;
        if (video) {
            profile = Camera1Session.videoProfile(numericId(id), videoQuality);
            shape = new Size(profile.videoFrameWidth, profile.videoFrameHeight);
            fpsRange = rangeFor(ranges, fps);
            put("video_size", shape.getWidth() + "x" + shape.getHeight());
            put("video_fps", fps);
            put("video_bit_rate_mbit_s", Math.round(Camera1Session.bitRate(profile, fps) / 1e5) / 10.0);
        } else {
            shape = largest(map.getOutputSizes(ImageFormat.JPEG));
            reader = ImageReader.newInstance(shape.getWidth(), shape.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(rd -> {
                Image image = rd.acquireNextImage();
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                image.close();
                jpegs.offer(bytes);
            }, handler);
            fpsRange = lowest(ranges); // least work while the camera waits between photos
            photoSize = shape;
            put("photo_size", shape.getWidth() + "x" + shape.getHeight());
            put("focus_on_square", focusOnSquare);
            put("exposure_on_square", exposureOnSquare);
        }
        if (fpsRange != null) put("preview_fps_range", fpsRange.getLower() + "-" + fpsRange.getUpper());
        Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
        // On the aim screen, frames sharp enough to judge focus; otherwise the smallest (least work).
        Size previewSize = shown != null ? largestWithShape(previewSizes, shape, 1920)
                : smallestWithShape(previewSizes, shape);
        put("preview_size", previewSize.getWidth() + "x" + previewSize.getHeight());
        texture = shown != null ? shown : new SurfaceTexture(0); // invisible: frames are never drawn
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        preview = new Surface(texture);

        openDevice(manager, id);
        startPreview(video ? Arrays.asList(preview) : Arrays.asList(preview, reader.getSurface()));
        if (!video) buildStill();
    }

    private void buildStill() throws Exception {
        CaptureRequest.Builder still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        still.addTarget(reader.getSurface());
        setCommon(still);
        still.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY);
        stillRequest = still.build();
    }

    @Override
    public void setSquare(double sideShare, double centreX, double centreY) throws Exception {
        if (!focusOnSquare && !exposureOnSquare) return;
        int[] r = RoiMath.square(photoSize.getWidth(), photoSize.getHeight(), sideShare, centreX, centreY);
        int[] a = RoiMath.inArray(pixelArray.width(), pixelArray.height(),
                photoSize.getWidth(), photoSize.getHeight(), r);
        square = new MeteringRectangle[] {new MeteringRectangle(pixelArray.left + a[0], pixelArray.top + a[1],
                a[2], a[3], MeteringRectangle.METERING_WEIGHT_MAX)};
        setCommon(previewRequest);
        session.setRepeatingRequest(previewRequest.build(), afWatcher, handler);
        buildStill();
    }

    /** New capture session with these outputs and a repeating preview-only request. */
    private void startPreview(List<Surface> outputs) throws Exception {
        if (session != null) session.close();
        session = null;
        createSession(outputs);
        previewRequest = device.createCaptureRequest(video ? CameraDevice.TEMPLATE_RECORD
                : CameraDevice.TEMPLATE_PREVIEW);
        previewRequest.addTarget(preview);
        setCommon(previewRequest);
        session.setRepeatingRequest(previewRequest.build(), afWatcher, handler);
    }

    private void setCommon(CaptureRequest.Builder b) {
        // Autofocus mode "auto": the lens moves only when focus() asks, then stays.
        if (canFocus) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
        if (fpsRange != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
        if (square != null && focusOnSquare) b.set(CaptureRequest.CONTROL_AF_REGIONS, square);
        if (square != null && exposureOnSquare) b.set(CaptureRequest.CONTROL_AE_REGIONS, square);
    }

    @Override
    public String focus() throws Exception {
        if (!canFocus) return "fixed focus";
        long t = SpeedTest.now();
        focusStarted = false;
        afState = null;
        previewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
        previewRequest.setTag(FOCUS_START);
        session.capture(previewRequest.build(), afWatcher, handler);
        previewRequest.setTag(null);
        previewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
        while (SpeedTest.now() - t < FOCUS_TIMEOUT_MS) {
            Integer s = afState;
            if (s != null && s == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED) {
                return "sharp after " + (SpeedTest.now() - t) + " ms";
            }
            if (s != null && s == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                return "failed after " + (SpeedTest.now() - t) + " ms";
            }
            Thread.sleep(20);
        }
        return "timeout";
    }

    @Override
    public byte[] takePhoto() throws Exception {
        jpegs.clear(); // a photo that arrived after an earlier timeout is not this one
        session.capture(stillRequest, null, handler);
        byte[] jpeg = jpegs.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (jpeg == null) throw new IllegalStateException("no photo after " + TIMEOUT_MS + " ms");
        return jpeg;
    }

    @Override
    public String prepareClip(File file) throws Exception {
        recorder = new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        recorder.setVideoFrameRate(fps);
        recorder.setVideoEncodingBitRate(Camera1Session.bitRate(profile, fps));
        recorder.setOutputFile(file.getAbsolutePath());
        recorder.prepare();
        recorderSurface = recorder.getSurface();
        // The session must know the recorder's input; frames go there only after startClip().
        startPreview(Arrays.asList(preview, recorderSurface));
        return focus();
    }

    @Override
    public void startClip() throws Exception {
        recorder.start();
        recording = true;
        previewRequest.addTarget(recorderSurface);
        session.setRepeatingRequest(previewRequest.build(), afWatcher, handler);
    }

    @Override
    public void stopClip() throws Exception {
        try {
            previewRequest.removeTarget(recorderSurface);
            session.setRepeatingRequest(previewRequest.build(), afWatcher, handler);
            recorder.stop(); // throws if no frame arrived; the clip file is then useless
        } finally {
            recording = false;
            recorder.release();
            recorder = null;
            recorderSurface = null;
        }
        // Drop the recorder's old input from the session, so the camera can wait for the next clip.
        startPreview(Arrays.asList(preview));
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
        if (recorder != null) recorder.release();
        recorder = null;
        if (session != null) session.close();
        session = null;
        if (device != null) device.close();
        device = null;
        if (reader != null) reader.close();
        reader = null;
        if (preview != null) preview.release();
        preview = null;
        if (texture != null && texture != shown) texture.release(); // the aim screen owns its view
        texture = null;
        if (thread != null) thread.quitSafely();
        thread = null;
    }

    @SuppressLint("MissingPermission") // the screen asks for the camera permission before a session
    private void openDevice(CameraManager manager, String id) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final int[] error = {0};
        manager.openCamera(id, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice d) {
                device = d;
                done.countDown();
            }

            @Override
            public void onDisconnected(CameraDevice d) {
                d.close();
                done.countDown();
            }

            @Override
            public void onError(CameraDevice d, int e) {
                error[0] = e;
                d.close();
                done.countDown();
            }
        }, handler);
        if (!done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) || device == null) {
            throw new IllegalStateException("camera did not open (error " + error[0] + ")");
        }
    }

    @SuppressWarnings("deprecation") // the newer session call needs Android 9
    private void createSession(List<Surface> outputs) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final CameraCaptureSession[] made = new CameraCaptureSession[1];
        device.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession s) {
                made[0] = s;
                done.countDown();
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession s) {
                done.countDown();
            }
        }, handler);
        if (!done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) || made[0] == null) {
            throw new IllegalStateException("capture session failed");
        }
        session = made[0];
    }

    private void put(String key, Object value) {
        try {
            facts.put(key, value);
        } catch (JSONException ignored) {
            // Only thrown for NaN or infinite numbers, which we never write.
        }
    }

    static String backCameraId(CameraManager manager) throws Exception {
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraMetadata.LENS_FACING_BACK) return id;
        }
        throw new IllegalStateException("no back camera");
    }

    /** CamcorderProfile wants a number; Camera2 ids are numbers on almost all phones. */
    private static int numericId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Range<Integer> lowest(Range<Integer>[] ranges) {
        Range<Integer> best = null;
        if (ranges != null) {
            for (Range<Integer> r : ranges) {
                if (best == null || r.getUpper() < best.getUpper()
                        || (r.getUpper().equals(best.getUpper()) && r.getLower() < best.getLower())) {
                    best = r;
                }
            }
        }
        return best;
    }

    /** The range that contains {@code fps} with the lowest maximum (best a fixed rate); else the
     *  lowest range. */
    private static Range<Integer> rangeFor(Range<Integer>[] ranges, int fps) {
        Range<Integer> best = null;
        if (ranges != null) {
            for (Range<Integer> r : ranges) {
                if (!r.contains(fps)) continue;
                if (best == null || r.getUpper() < best.getUpper()
                        || (r.getUpper().equals(best.getUpper()) && r.getLower() > best.getLower())) {
                    best = r;
                }
            }
        }
        return best != null ? best : lowest(ranges);
    }

    private static Size largest(Size[] sizes) {
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best;
    }

    /** Same width-to-height ratio, within 1 %. */
    private static boolean sameShape(Size a, Size b) {
        return Math.abs((double) a.getWidth() * b.getHeight() - (double) a.getHeight() * b.getWidth())
                <= 0.01 * a.getHeight() * b.getWidth();
    }

    private static Size smallestWithShape(Size[] sizes, Size shape) {
        Size best = null;
        for (Size s : sizes) {
            if (sameShape(s, shape) && s.getWidth() >= 320
                    && (best == null || s.getWidth() * s.getHeight() < best.getWidth() * best.getHeight())) {
                best = s;
            }
        }
        return best != null ? best : sizes[0];
    }

    private static Size largestWithShape(Size[] sizes, Size shape, int maxWidth) {
        Size best = null;
        for (Size s : sizes) {
            if (sameShape(s, shape) && s.getWidth() <= maxWidth
                    && (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight())) {
                best = s;
            }
        }
        return best != null ? best : smallestWithShape(sizes, shape);
    }
}
