package com.faunalapse.app;

import android.graphics.SurfaceTexture;
import android.media.CamcorderProfile;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Video of the ROI square only, for Camera1 and Camera2 alike. The camera sends its frames into a
 * texture (an image the graphics processor, GPU, can read). For each kept frame the GPU cuts out
 * the square, scales it to the saved side, turns it upright and draws it into the hardware video
 * encoder, whose output goes into an MP4 file. Drawing one square a few times per second is a tiny
 * job for a GPU, and the encoder then has fewer pixels to compress than a full frame. Frames are
 * kept on a time grid (FrameRates.Picker) and keep the camera's time stamps, so the file plays in
 * real time.
 *
 * The graphics and encoder work runs on the recorder's own thread; the other calls wait for it.
 * The way the camera texture, the encoder's input surface and the presentation time fit together
 * follows Google's Grafika examples (Apache-2.0, ideas only, no code copied).
 */
final class SquareRecorder {

    private static final long TIMEOUT_MS = 5000;
    private static final long DRAIN_WAIT_US = 10000;
    private static final int END_WAITS = 200;        // up to 2 s for the encoder's last frames
    private static final int KEY_FRAME_INTERVAL_S = 2; // a full frame every 2 s: smaller files
    private static final int EGL_RECORDABLE_ANDROID = 0x3142; // named in EGLExt only from Android 8
    private static final String AVC = MediaFormat.MIMETYPE_VIDEO_AVC; // H.264

    private static final String VERTEX_SHADER = ""
            + "attribute vec4 aPosition;\n"
            + "attribute vec2 aTexCoord;\n"
            + "varying vec2 vTexCoord;\n"
            + "void main() {\n"
            + "  gl_Position = aPosition;\n"
            + "  vTexCoord = aTexCoord;\n"
            + "}\n";
    // High precision where the GPU has it: medium precision can be off by a pixel in large frames.
    private static final String FRAGMENT_SHADER = ""
            + "#extension GL_OES_EGL_image_external : require\n"
            + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "#endif\n"
            + "varying vec2 vTexCoord;\n"
            + "uniform samplerExternalOES sTexture;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(sTexture, vTexCoord);\n"
            + "}\n";

    private interface Job {
        void run() throws Exception;
    }

    private final HandlerThread thread = new HandlerThread("square-video");
    private final Handler handler;
    private final float[] texMatrix = new float[16];
    private final FloatBuffer corners = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private float[] squareCorners; // RoiMath.squareCorners for the clip
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface idle = EGL14.EGL_NO_SURFACE;  // 1 x 1 pixel: the GPU needs a target between clips
    private EGLSurface input = EGL14.EGL_NO_SURFACE; // the encoder's input during a clip
    private EGLSurface current = EGL14.EGL_NO_SURFACE;
    private int program;
    private int positionLoc;
    private int texCoordLoc;
    private int textureId;
    private SurfaceTexture texture;
    private double[] square = {0, 0, 1, 1}; // shares of the camera frame: left, top, width, height
    private MediaCodec encoder;
    private Surface encoderInput;
    private MediaMuxer muxer;
    private int track;
    private boolean muxing;
    private volatile boolean recording;
    private FrameRates.Picker picker;
    private int side;
    private int frames;
    private Exception failure; // from a frame; stop() reports it

    /** Starts the recorder thread with a camera texture of {@code width} x {@code height}. */
    SquareRecorder(final int width, final int height) throws Exception {
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            run(() -> setUp(width, height));
        } catch (Exception e) {
            release();
            throw e;
        }
    }

    /** Where the camera sends its frames. */
    SurfaceTexture texture() {
        return texture;
    }

    /** The square as shares {left, top, width, height} of the camera frame; used from the next clip. */
    void setSquare(final double[] shares) throws Exception {
        run(() -> square = shares.clone());
    }

    /** Gets a clip ready: a {@code side} x {@code side} H.264 video at {@code fps} into {@code file},
     *  the square turned {@code turn} degrees clockwise. */
    void prepare(final File file, final Encoder e, final int bitRate, final int fps, final int turn) throws Exception {
        run(() -> {
            closeClip();
            failure = null;
            frames = 0;
            muxing = false;
            side = e.side;
            picker = new FrameRates.Picker(fps);
            squareCorners = RoiMath.squareCorners(square, turn);
            MediaFormat f = MediaFormat.createVideoFormat(AVC, side, side);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            encoder = MediaCodec.createByCodecName(e.name);
            int rate = encoder.getCodecInfo().getCapabilitiesForType(AVC).getVideoCapabilities()
                    .getBitrateRange().clamp(bitRate);
            f.setInteger(MediaFormat.KEY_BIT_RATE, rate);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_S);
            encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderInput = encoder.createInputSurface();
            input = EGL14.eglCreateWindowSurface(display, config, encoderInput, new int[] {EGL14.EGL_NONE}, 0);
            check(input != EGL14.EGL_NO_SURFACE, "encoder surface");
            encoder.start();
            muxer = new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        });
    }

    /** Starts recording with the next camera frame. */
    void start() throws Exception {
        run(() -> recording = true);
    }

    /** Stops recording and closes the file; returns the number of frames in it. Throws when the clip
     *  failed or got no frame (the file is then useless). */
    int stop() throws Exception {
        final int[] n = new int[1];
        run(() -> {
            recording = false;
            Exception problem = failure;
            try {
                encoder.signalEndOfInputStream();
                drain(true);
            } catch (Exception e) {
                if (problem == null) problem = e;
            }
            boolean written = muxing;
            n[0] = frames;
            try {
                closeClip();
            } catch (Exception e) {
                if (problem == null) problem = e;
            }
            if (problem != null) throw problem;
            if (!written) throw new IllegalStateException("no video frame arrived");
        });
        return n[0];
    }

    /** Frees everything; the camera must have stopped sending frames. Never throws. */
    void release() {
        try {
            run(this::tearDown);
        } catch (Exception ignored) {
            // the thread is gone or stuck; it ends with the app
        }
        thread.quitSafely();
    }

    // ---- On the recorder thread -------------------------------------------------------------

    private void setUp(int width, int height) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        check(EGL14.eglInitialize(display, version, 0, version, 1), "graphics display");
        int[] attributes = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0,
                "graphics setup for video");
        config = configs[0];
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        check(context != EGL14.EGL_NO_CONTEXT, "graphics context");
        idle = EGL14.eglCreatePbufferSurface(display, config, new int[] {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE}, 0);
        check(idle != EGL14.EGL_NO_SURFACE, "graphics surface");
        makeCurrent(idle);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER));
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER));
        GLES20.glLinkProgram(program);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        check(ok[0] != 0, "graphics program: " + GLES20.glGetProgramInfoLog(program));
        positionLoc = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        textureId = t[0];
        int target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
        GLES20.glBindTexture(target, textureId);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        texture = new SurfaceTexture(textureId);
        texture.setDefaultBufferSize(width, height);
        texture.setOnFrameAvailableListener(st -> onFrame(), handler);
    }

    /** Each camera frame: take it from the camera (also between clips, so the camera never waits),
     *  and during a clip draw the kept ones into the encoder. */
    private void onFrame() {
        if (texture == null) return;
        try {
            makeCurrent(recording ? input : idle);
            texture.updateTexImage();
            if (!recording) return;
            long ns = texture.getTimestamp();
            if (ns <= 0) ns = System.nanoTime(); // a camera without time stamps
            if (!picker.take(ns)) return;
            texture.getTransformMatrix(texMatrix);
            corners.clear();
            corners.put(RoiMath.inTexture(squareCorners, texMatrix));
            GLES20.glViewport(0, 0, side, side);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            corners.position(0);
            GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 16, corners);
            GLES20.glEnableVertexAttribArray(positionLoc);
            corners.position(2);
            GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 16, corners);
            GLES20.glEnableVertexAttribArray(texCoordLoc);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            EGLExt.eglPresentationTimeANDROID(display, input, ns);
            EGL14.eglSwapBuffers(display, input);
            frames++;
            drain(false);
        } catch (Exception e) {
            if (failure == null) failure = e;
            recording = false; // the clip ends here; stop() reports why
        }
    }

    /** Moves the encoded frames into the file; at the end of a clip, waits for the last ones. */
    private void drain(boolean end) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int waits = 0;
        while (true) {
            int i = encoder.dequeueOutputBuffer(info, end ? DRAIN_WAIT_US : 0);
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end || ++waits > END_WAITS) return;
            } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
                muxing = true;
            } else if (i >= 0) {
                ByteBuffer data = encoder.getOutputBuffer(i);
                boolean setup = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0; // already in the format
                if (!setup && info.size > 0 && muxing && data != null) {
                    data.position(info.offset);
                    data.limit(info.offset + info.size);
                    muxer.writeSampleData(track, data, info);
                }
                encoder.releaseOutputBuffer(i, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    /** Frees the clip's encoder and file; throws when the file could not be finished. */
    private void closeClip() {
        recording = false;
        if (input != EGL14.EGL_NO_SURFACE) {
            makeCurrent(idle);
            EGL14.eglDestroySurface(display, input);
            input = EGL14.EGL_NO_SURFACE;
        }
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (RuntimeException ignored) {
                // already stopped or broken; released next
            }
            encoder.release();
            encoder = null;
        }
        if (encoderInput != null) encoderInput.release();
        encoderInput = null;
        if (muxer != null) {
            MediaMuxer m = muxer;
            muxer = null;
            try {
                if (muxing) m.stop(); // writes the index that players need
            } finally {
                muxing = false;
                m.release();
            }
        }
    }

    private void tearDown() {
        try {
            closeClip();
        } catch (RuntimeException ignored) {
            // the clip is lost anyway
        }
        if (texture != null) {
            texture.setOnFrameAvailableListener(null);
            texture.release();
            texture = null;
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (idle != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, idle);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            // No eglTerminate: the screen's own drawing shares the display.
            display = EGL14.EGL_NO_DISPLAY;
        }
    }

    private void makeCurrent(EGLSurface surface) {
        if (surface == current) return;
        check(EGL14.eglMakeCurrent(display, surface, surface, context), "graphics surface");
        current = surface;
    }

    private static int shader(int type, String source) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, source);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        check(ok[0] != 0, "graphics shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new IllegalStateException(what + " failed (EGL error " + EGL14.eglGetError() + ")");
    }

    /** Runs {@code job} on the recorder thread and waits for it; its exception comes back here. */
    private void run(final Job job) throws Exception {
        final Exception[] error = new Exception[1];
        final CountDownLatch done = new CountDownLatch(1);
        boolean posted = handler.post(() -> {
            try {
                job.run();
            } catch (Exception e) {
                error[0] = e;
            } finally {
                done.countDown();
            }
        });
        if (!posted) throw new IllegalStateException("the video thread has ended");
        if (!done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw new IllegalStateException("the video thread did not answer");
        if (error[0] != null) throw error[0];
    }

    // ---- Encoder choice and bit rate --------------------------------------------------------

    /** An H.264 encoder (its name) and the square side it will get. */
    static final class Encoder {
        final String name;
        final int side;
        final boolean hardware;

        Encoder(String name, int side, boolean hardware) {
            this.name = name;
            this.side = side;
            this.hardware = hardware;
        }
    }

    /** The phone's H.264 encoder for a square video at {@code fps}, at the largest side up to
     *  {@code wanted} (a multiple of RoiMath.SIDE_STEP) that it accepts. A hardware encoder where the
     *  phone has one: encoding in software would heat the phone. */
    static Encoder encoderFor(int wanted, int fps) {
        List<MediaCodecInfo> hardware = new ArrayList<>();
        List<MediaCodecInfo> software = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(AVC)) (isSoftware(info) ? software : hardware).add(info);
            }
        }
        List<MediaCodecInfo> usable = hardware.isEmpty() ? software : hardware;
        for (int side = wanted; side >= RoiMath.SIDE_STEP; side -= RoiMath.SIDE_STEP) {
            for (MediaCodecInfo info : usable) {
                if (info.getCapabilitiesForType(AVC).getVideoCapabilities().areSizeAndRateSupported(side, side, fps)) {
                    return new Encoder(info.getName(), side, usable == hardware);
                }
            }
        }
        throw new IllegalStateException("no H.264 video encoder on this phone");
    }

    /** A software encoder: Android 10 and newer say so; before, Android's own ones have these names. */
    private static boolean isSoftware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) return info.isSoftwareOnly();
        String n = info.getName().toLowerCase(Locale.US);
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.");
    }

    /** Bit rate for a square video: the same bits per pixel and frame as the phone maker's best video
     *  preset, so the quality stays like the phone's own videos. */
    @SuppressWarnings("deprecation") // CamcorderProfile.get works on every version
    static int bitRate(int cameraId, int side, int fps) {
        CamcorderProfile p = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH);
        double perPixel = (double) p.videoBitRate / ((double) p.videoFrameWidth * p.videoFrameHeight * p.videoFrameRate);
        return (int) Math.round(perPixel * side * side * fps);
    }
}
