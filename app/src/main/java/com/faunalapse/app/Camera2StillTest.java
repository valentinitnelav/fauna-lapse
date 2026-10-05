package com.faunalapse.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Phase 0: full-size photos of the back camera through Camera2, to compare with Camera1's
 * takePicture. Same steps as there: invisible preview, 3 s warm-up, one autofocus, then
 * photos once per second and as fast as possible (one at a time). The photo is made and compressed
 * to JPEG by the camera hardware; the app only saves the bytes.
 *
 * Runs on the SpeedTest thread and waits for each answer; Camera2 calls back on its own thread.
 */
final class Camera2StillTest {

    private static final long TIMEOUT_MS = 5000;
    private static final long WARM_UP_MS = 3000;
    private static final int SLOW_PHOTOS = 10;
    private static final long FAST_MS = 5000;

    private final Context context;
    private final File dir;
    private final LinkedBlockingQueue<byte[]> jpegs = new LinkedBlockingQueue<>();
    private volatile Integer afState;
    private HandlerThread thread;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private SurfaceTexture texture;
    private Surface preview;

    Camera2StillTest(Context context, File dir) {
        this.context = context;
        this.dir = dir;
    }

    /** One record per test (once per second, then as fast as possible). */
    List<JSONObject> run() {
        List<JSONObject> records = new ArrayList<>();
        JSONObject r = SpeedTest.newRecord("camera2_still", 0, 0);
        records.add(r);
        try {
            thread = new HandlerThread("camera2");
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String id = backCameraId(manager);
            StreamConfigurationMap map = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = largest(Camera2Session.photoSizes(map));
            SpeedTest.put(r, "size", size.getWidth() + "x" + size.getHeight());
            Size previewSize = smallestWithShape(map.getOutputSizes(SurfaceTexture.class), size);

            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(rd -> {
                Image image = rd.acquireNextImage();
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                image.close();
                jpegs.offer(bytes);
            }, handler);
            texture = new SurfaceTexture(0);
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            preview = new Surface(texture);

            openCamera(manager, id, handler);
            createSession(handler);

            // Invisible preview with autofocus mode "auto": the lens moves only when asked.
            CaptureRequest.Builder previewRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequest.addTarget(preview);
            previewRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
            session.setRepeatingRequest(previewRequest.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest q, TotalCaptureResult result) {
                    afState = result.get(CaptureResult.CONTROL_AF_STATE);
                }
            }, handler);
            Thread.sleep(WARM_UP_MS);
            SpeedTest.put(r, "focus", autoFocus(previewRequest, handler));

            CaptureRequest.Builder still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            still.addTarget(reader.getSurface());
            still.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
            still.set(CaptureRequest.JPEG_QUALITY, (byte) 90);
            CaptureRequest stillRequest = still.build();

            // Once per second.
            JSONArray photoMs = new JSONArray(), saveMs = new JSONArray(), kb = new JSONArray();
            long next = SpeedTest.now();
            for (int i = 1; i <= SLOW_PHOTOS; i++) {
                long wait = next - SpeedTest.now();
                if (wait > 0) Thread.sleep(wait);
                next += 1000;
                long t0 = SpeedTest.now();
                byte[] jpeg = capture(stillRequest, handler);
                long t1 = SpeedTest.now();
                SpeedTest.writeBytes(new File(dir, SpeedTest.name("camera2_still", size.getWidth(),
                        size.getHeight(), i)), jpeg);
                photoMs.put(t1 - t0);
                saveMs.put(SpeedTest.now() - t1);
                kb.put(jpeg.length / 1024);
            }
            SpeedTest.put(r, "every_1s_photo_ms", photoMs);
            SpeedTest.put(r, "every_1s_save_ms", saveMs);
            SpeedTest.put(r, "kb", kb);

            // As fast as possible, one photo at a time.
            int saved = 0;
            long t0 = SpeedTest.now();
            while (SpeedTest.now() - t0 < FAST_MS) {
                byte[] jpeg = capture(stillRequest, handler);
                SpeedTest.writeBytes(new File(dir, SpeedTest.name("camera2_fast", size.getWidth(),
                        size.getHeight(), ++saved)), jpeg);
            }
            SpeedTest.put(r, "fast_saved_per_s", Math.round(saved * 10000.0 / (SpeedTest.now() - t0)) / 10.0);
        } catch (Exception e) {
            SpeedTest.put(r, "error", e.toString());
        } finally {
            close();
        }
        return records;
    }

    @SuppressLint("MissingPermission") // the screen asked for the camera permission before the test
    private void openCamera(CameraManager manager, String id, Handler handler) throws Exception {
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
    private void createSession(Handler handler) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        device.createCaptureSession(Arrays.asList(preview, reader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession s) {
                        session = s;
                        done.countDown();
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession s) {
                        done.countDown();
                    }
                }, handler);
        if (!done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) || session == null) {
            throw new IllegalStateException("capture session failed");
        }
    }

    /** Starts one autofocus scan and waits until the lens is locked. */
    private String autoFocus(CaptureRequest.Builder previewRequest, Handler handler) throws Exception {
        long t = SpeedTest.now();
        previewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
        session.capture(previewRequest.build(), null, handler);
        previewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
        while (SpeedTest.now() - t < 3000) {
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

    private byte[] capture(CaptureRequest request, Handler handler) throws Exception {
        session.capture(request, null, handler);
        byte[] jpeg = jpegs.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (jpeg == null) throw new IllegalStateException("no photo after " + TIMEOUT_MS + " ms");
        return jpeg;
    }

    private void close() {
        if (session != null) session.close();
        if (device != null) device.close();
        if (reader != null) reader.close();
        if (preview != null) preview.release();
        if (texture != null) texture.release();
        if (thread != null) thread.quitSafely();
    }

    private static String backCameraId(CameraManager manager) throws Exception {
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraMetadata.LENS_FACING_BACK) return id;
        }
        throw new IllegalStateException("no back camera");
    }

    private static Size largest(Size[] sizes) {
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best;
    }

    private static Size smallestWithShape(Size[] sizes, Size shape) {
        Size best = null;
        for (Size s : sizes) {
            boolean same = Math.abs((double) s.getWidth() * shape.getHeight()
                    - (double) s.getHeight() * shape.getWidth()) <= 0.01 * s.getHeight() * shape.getWidth();
            if (same && s.getWidth() >= 320
                    && (best == null || s.getWidth() * s.getHeight() < best.getWidth() * best.getHeight())) {
                best = s;
            }
        }
        return best != null ? best : sizes[0];
    }
}
