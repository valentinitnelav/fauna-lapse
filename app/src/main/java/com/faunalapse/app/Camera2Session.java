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
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Session camera through Camera2, used on phones whose Camera2 level is "limited" or
 * better. Photos: preview (invisible, or on the aim screen) plus a full-size JPEG output, made by
 * the camera hardware. Video: the camera hardware zooms onto the ROI square (crop region), and
 * SquareRecorder cuts the square out of the stream and records it.
 *
 * Camera2 calls back on its own thread ("camera2"); the session thread waits for the answers.
 */
final class Camera2Session implements SessionCamera {

    private static final long TIMEOUT_MS = 5000;
    private static final long FOCUS_TIMEOUT_MS = 3000;
    private static final long CLOSE_TIMEOUT_MS = 2000;
    private static final long RESULT_WAIT_MS = 500; // the report on a photo, after its JPEG
    private static final byte JPEG_QUALITY = 90;
    // A photo size listed apart as "high resolution" is used when the camera can still take 10 per
    // second (Redmi Note 9S: 4000x3000 at 15); slower ones are full 48 to 200 MP modes (hot, slow).
    private static final long HIGH_RES_MAX_FRAME_NS = 100_000_000;

    private final Context context;
    private final boolean video;
    private final int squareSide; // the saved side asked for
    private final int fps;
    private final SurfaceTexture shown;
    private final JSONObject facts = new JSONObject();
    private final LinkedBlockingQueue<byte[]> jpegs = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<TotalCaptureResult> stillResults = new LinkedBlockingQueue<>();
    private TotalCaptureResult lastStill; // the camera's report on the last photo, or null
    /** Tag of the request that starts autofocus: focus states count from its result on. */
    private static final Object FOCUS_START = new Object();
    private volatile Integer afState;
    private volatile boolean focusStarted;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private final CountDownLatch closed = new CountDownLatch(1); // Android has freed the camera
    private CameraCaptureSession session;
    private ImageReader reader;
    private SurfaceTexture texture;
    private Surface preview;
    // Video session: a tiny output that is never drawn. The camera cannot run slower than its
    // slowest rate (Samsung M12: 15 per s), so only every n-th request also asks for a frame for the
    // recorder; the others, and all requests between clips, go only here. The camera then makes
    // its large frames only when they are kept.
    private SurfaceTexture idleTexture;
    private Surface idle;
    private int keepEvery = 1;
    private CaptureRequest.Builder previewRequest;
    private CaptureRequest stillRequest;
    private SquareRecorder recorder; // video session only (not on the aim screen)
    private Range<Integer> fpsRange;
    private Size photoSize;   // the full photo: the square's frame of reference, also for video
    private Size streamSize;  // video: the camera stream the square is cut from
    private String zoom = "none"; // video: "anywhere", "centred" or "none" (RoiMath.videoSquare)
    private float maxZoom = 1;
    private Rect crop;        // video session: the part of the pixel array the camera zooms onto
    private volatile Rect usedCrop; // the crop the camera last reported
    private SquareRecorder.Encoder encoder;
    private int bitRate;
    private String cameraId;
    private Rect pixelArray;
    private boolean focusOnSquare;
    private boolean exposureOnSquare;
    private MeteringRectangle[] square;
    private boolean canFocus;
    private boolean canFixFocus;    // the lens can be set to a distance (autofocus off)
    private float closestDiopters;  // the nearest distance the lens reaches
    private Float fixedDiopters;    // fixed focus, or null for autofocus
    private volatile Float lensDiopters; // where the lens is, from the camera's last report
    private boolean recording;

    private final CameraCaptureSession.CaptureCallback afWatcher = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
            // Earlier frames still report the last focus ("sharp" in a short break).
            if (r.getTag() == FOCUS_START) focusStarted = true;
            if (focusStarted) afState = result.get(CaptureResult.CONTROL_AF_STATE);
            Float lens = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
            if (lens != null) lensDiopters = lens;
            if (crop != null) usedCrop = result.get(CaptureResult.SCALER_CROP_REGION);
        }
    };

    private final CameraCaptureSession.CaptureCallback stillWatcher = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
            stillResults.offer(result);
        }
    };

    /** {@code shown}: the aim screen's view to show the preview on, or null for none. */
    Camera2Session(Context context, boolean video, int squareSide, int fps, SurfaceTexture shown) {
        this.context = context;
        this.video = video;
        this.squareSide = squareSide;
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
        cameraId = id;
        CameraCharacteristics c = manager.getCameraCharacteristics(id);
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        canFocus = hasAfMode(c, CameraMetadata.CONTROL_AF_MODE_AUTO);
        canFixFocus = canFixFocus(c);
        if (canFixFocus) closestDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Integer afRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        Integer aeRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        focusOnSquare = canFocus && pixelArray != null && afRegions != null && afRegions > 0;
        exposureOnSquare = pixelArray != null && aeRegions != null && aeRegions > 0;
        put("camera_api", "camera2");
        put("camera_id", id);
        Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        put("sensor_orientation", orientation == null ? 0 : orientation);
        put("focus", canFocus ? "auto, once per " + (video ? "clip" : "burst") : "fixed lens");
        put("fixed_focus_possible", canFixFocus);
        if (canFixFocus) {
            put("closest_focus_diopters", closestDiopters);
            put("focus_distance_calibration", calibration(c));
        }

        Size shape = largest(photoSizes(map));
        photoSize = shape;
        put("photo_size", shape.getWidth() + "x" + shape.getHeight());
        put("focus_on_square", focusOnSquare);
        put("exposure_on_square", exposureOnSquare);
        if (video) {
            int[] fixed = ranges == null ? null : FrameRates.fixedRateFor(asArrays(ranges), fps);
            // A fixed camera rate that is a multiple of fps keeps the kept frames evenly spaced.
            fpsRange = fixed != null ? new Range<>(fixed[0], fixed[0]) : rangeFor(ranges, fps);
            keepEvery = fixed != null ? fixed[1] : 1;
            Float z = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            Integer cropping = c.get(CameraCharacteristics.SCALER_CROPPING_TYPE);
            maxZoom = z == null ? 1 : z;
            zoom = pixelArray == null || maxZoom <= 1 ? "none"
                    : cropping != null && cropping == CameraMetadata.SCALER_CROPPING_TYPE_FREEFORM ? "anywhere" : "centred";
            streamSize = videoStreamSize(map, shape, squareSide, fpsRange == null ? 30 : fpsRange.getUpper());
            put("video_fps", fps);
            put("video_stream_size", streamSize.getWidth() + "x" + streamSize.getHeight());
            put("video_zoom", zoom);
            put("max_digital_zoom", (double) maxZoom);
            if (pixelArray != null) put("pixel_array", pixelArray.width() + "x" + pixelArray.height());
        } else {
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
        }
        if (fpsRange != null) put("preview_fps_range", fpsRange.getLower() + "-" + fpsRange.getUpper());
        Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
        // On the aim screen, frames sharp enough to judge focus; otherwise the smallest (least work).
        Size previewSize = shown != null ? largestWithShape(previewSizes, shape, 1920)
                : video ? streamSize : smallestWithShape(previewSizes, shape);
        put("preview_size", previewSize.getWidth() + "x" + previewSize.getHeight());
        if (shown == null && video) {
            recorder = new SquareRecorder(previewSize.getWidth(), previewSize.getHeight());
            texture = recorder.texture();
            Size small = smallestWithShape(previewSizes, shape);
            idleTexture = new SurfaceTexture(0);
            idleTexture.setDefaultBufferSize(small.getWidth(), small.getHeight());
            idle = new Surface(idleTexture);
            put("video_keeps_every_nth_camera_frame", keepEvery);
        } else {
            texture = shown != null ? shown : new SurfaceTexture(0); // invisible: frames are never drawn
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        }
        preview = new Surface(texture);

        openDevice(manager, id);
        startPreview(idle != null ? Arrays.asList(preview, idle)
                : video ? Arrays.asList(preview) : Arrays.asList(preview, reader.getSurface()));
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
        int pw = photoSize.getWidth();
        int ph = photoSize.getHeight();
        if (pixelArray != null) {
            int[] r = RoiMath.square(pw, ph, sideShare, centreX, centreY);
            // Areas are on the pixel array, with (0, 0) at its top left corner.
            int[] a = RoiMath.inArray(pixelArray.width(), pixelArray.height(), pw, ph, r);
            square = new MeteringRectangle[] {new MeteringRectangle(a[0], a[1], a[2], a[3],
                    MeteringRectangle.METERING_WEIGHT_MAX)};
        }
        if (recorder != null) {
            int aw = pixelArray != null ? pixelArray.width() : pw;
            int ah = pixelArray != null ? pixelArray.height() : ph;
            RoiMath.VideoSquare v = RoiMath.videoSquare(pw, ph, aw, ah, streamSize.getWidth(), streamSize.getHeight(),
                    maxZoom, zoom, sideShare, centreX, centreY);
            crop = v.crop == null ? null : new Rect(v.crop[0], v.crop[1], v.crop[0] + v.crop[2], v.crop[1] + v.crop[3]);
            recorder.setSquare(v.inStream);
            encoder = SquareRecorder.encoderFor(RoiMath.savedSide(v.realPx, squareSide), fps);
            bitRate = SquareRecorder.bitRate(numericId(cameraId), encoder.side, fps);
            put("video_crop", crop == null ? "none" : rectWords(crop));
            put("square_real_px", v.realPx);
            put("saved_side_px", encoder.side);
            put("video_encoder", encoder.name + (encoder.hardware ? "" : " (software)"));
            put("video_bit_rate_asked_mbit_s", Math.round(bitRate / 1e5) / 10.0);
        }
        setCommon(previewRequest);
        session.setRepeatingRequest(previewRequest.build(), afWatcher, handler);
        if (!video) buildStill();
    }

    /** "left,top widthxheight", as in the session record. */
    private static String rectWords(Rect r) {
        return r.left + "," + r.top + " " + r.width() + "x" + r.height();
    }

    /** New capture session with these outputs and a repeating preview-only request. */
    private void startPreview(List<Surface> outputs) throws Exception {
        if (session != null) session.close();
        session = null;
        createSession(outputs);
        previewRequest = device.createCaptureRequest(video ? CameraDevice.TEMPLATE_RECORD
                : CameraDevice.TEMPLATE_PREVIEW);
        previewRequest.addTarget(idle != null ? idle : preview);
        setCommon(previewRequest);
        session.setRepeatingRequest(previewRequest.build(), afWatcher, handler);
    }

    private void setCommon(CaptureRequest.Builder b) {
        if (fixedDiopters != null) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, fixedDiopters);
        } else if (canFocus) {
            // Autofocus mode "auto": the lens moves only when focus() asks, then stays.
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
        }
        if (fpsRange != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
        if (crop != null) b.set(CaptureRequest.SCALER_CROP_REGION, crop);
        if (video) {
            // Stabilisation would move the view from frame to frame, and the square with it.
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        }
        if (square != null && focusOnSquare) b.set(CaptureRequest.CONTROL_AF_REGIONS, square);
        if (square != null && exposureOnSquare) b.set(CaptureRequest.CONTROL_AE_REGIONS, square);
    }

    @Override
    public void setFixedFocus(Float diopters) throws Exception {
        if (!canFixFocus) return;
        fixedDiopters = diopters == null ? null : Math.max(0, Math.min(closestDiopters, diopters));
        put("focus", fixedDiopters == null ? "auto, once per " + (video ? "clip" : "burst")
                : String.format(Locale.US, "fixed at %.2f diopters", fixedDiopters));
        setCommon(previewRequest);
        session.setRepeatingRequest(previewRequest.build(), afWatcher, handler);
        if (!video) buildStill();
    }

    @Override
    public float lensDiopters() {
        Float d = lensDiopters;
        return d == null ? Float.NaN : d;
    }

    @Override
    public String focus() throws Exception {
        if (fixedDiopters != null) {
            return String.format(Locale.US, "fixed at %s (%.2f diopters)",
                    SessionSettings.distanceWords(fixedDiopters), fixedDiopters);
        }
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
        stillResults.clear();
        session.capture(stillRequest, stillWatcher, handler);
        byte[] jpeg = jpegs.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (jpeg == null) throw new IllegalStateException("no photo after " + TIMEOUT_MS + " ms");
        lastStill = stillResults.poll(RESULT_WAIT_MS, TimeUnit.MILLISECONDS); // usually there already
        return jpeg;
    }

    @Override
    public void putExposure(JSONObject r) {
        if (lastStill == null) return;
        Long ns = lastStill.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = lastStill.get(CaptureResult.SENSOR_SENSITIVITY);
        if (ns != null) SessionLog.put(r, "exposure_ms", Math.round(ns / 1e4) / 100.0);
        if (iso != null) SessionLog.put(r, "iso", iso);
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
        // A repeating group of requests: the first also sends its frame to the recorder.
        previewRequest.addTarget(preview);
        List<CaptureRequest> group = new ArrayList<>();
        group.add(previewRequest.build());
        previewRequest.removeTarget(preview);
        CaptureRequest idleOnly = previewRequest.build();
        for (int i = 1; i < keepEvery; i++) group.add(idleOnly);
        session.setRepeatingBurst(group, null, null); // no reports needed during a clip: less work
    }

    @Override
    public JSONObject stopClip() throws Exception {
        recording = false;
        JSONObject r = new JSONObject();
        session.setRepeatingRequest(previewRequest.build(), afWatcher, handler);
        SessionLog.put(r, "frames", recorder.stop());
        Rect used = usedCrop;
        if (used != null) SessionLog.put(r, "camera_crop_used", rectWords(used));
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
        if (session != null) session.close();
        session = null;
        if (device != null) {
            device.close();
            try {
                // Closing goes on after close() returns: wait, so the camera can be opened again
                // at once (by the session after the aim screen).
                closed.await(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // a Stop: keep the request for the session
            }
        }
        device = null;
        if (reader != null) reader.close();
        reader = null;
        if (preview != null) preview.release();
        preview = null;
        if (recorder != null) { // after the camera: no frames arrive any more
            recorder.release();
            recorder = null;
            texture = null;
        }
        if (idle != null) idle.release();
        idle = null;
        if (idleTexture != null) idleTexture.release();
        idleTexture = null;
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
            public void onClosed(CameraDevice d) {
                closed.countDown();
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

    /** How this phone's lens distances can be read ("calibrated", "approximate", "uncalibrated" or
     *  "unknown") when a session can fix the focus; null when it can only autofocus (Camera1, or
     *  a lens that cannot be set to a distance). */
    static String fixedFocusCalibration(Context context) {
        if (!SessionRunner.useCamera2(context)) return null;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics c = manager.getCameraCharacteristics(backCameraId(manager));
            return canFixFocus(c) ? calibration(c) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The lens can be set to a distance: autofocus can be switched off and the lens has a range. */
    private static boolean canFixFocus(CameraCharacteristics c) {
        Float closest = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        return hasAfMode(c, CameraMetadata.CONTROL_AF_MODE_OFF) && closest != null && closest > 0;
    }

    private static boolean hasAfMode(CameraCharacteristics c, int mode) {
        int[] modes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (modes != null) {
            for (int m : modes) {
                if (m == mode) return true;
            }
        }
        return false;
    }

    /** Uncalibrated: the lens positions are in order, but not real distances. */
    private static String calibration(CameraCharacteristics c) {
        Integer cal = c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
        return cal == null ? "unknown"
                : cal == CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED ? "calibrated"
                : cal == CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE ? "approximate"
                : "uncalibrated";
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

    private static int[][] asArrays(Range<Integer>[] ranges) {
        int[][] a = new int[ranges.length][];
        for (int i = 0; i < ranges.length; i++) a[i] = new int[] {ranges[i].getLower(), ranges[i].getUpper()};
        return a;
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

    /** Video: the smallest stream of the photo's shape with at least {@code side} rows that runs at
     *  {@code cameraFps} (the square then needs little scaling); else the largest that runs at it. */
    private static Size videoStreamSize(StreamConfigurationMap map, Size shape, int side, int cameraFps) {
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        Size best = null;
        Size largest = null;
        for (Size s : sizes) {
            long minFrameNs = map.getOutputMinFrameDuration(SurfaceTexture.class, s); // 0 = not told
            if (!sameShape(s, shape) || minFrameNs > 1.01e9 / cameraFps) continue;
            long area = (long) s.getWidth() * s.getHeight();
            if (Math.min(s.getWidth(), s.getHeight()) >= side
                    && (best == null || area < (long) best.getWidth() * best.getHeight())) best = s;
            if (largest == null || area > (long) largest.getWidth() * largest.getHeight()) largest = s;
        }
        return best != null ? best : largest != null ? largest : smallestWithShape(sizes, shape);
    }

    /** JPEG sizes: the usual list and the "high resolution" sizes (below 20 per second) that still
     *  run at 10 per second. Without them, some phones offer only 16:9 at full width. */
    static Size[] photoSizes(StreamConfigurationMap map) {
        List<Size> sizes = new ArrayList<>(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)));
        Size[] high = map.getHighResolutionOutputSizes(ImageFormat.JPEG);
        if (high != null) {
            for (Size s : high) {
                long frameNs = map.getOutputMinFrameDuration(ImageFormat.JPEG, s);
                if (frameNs > 0 && frameNs <= HIGH_RES_MAX_FRAME_NS) sizes.add(s);
            }
        }
        return sizes.toArray(new Size[0]);
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
