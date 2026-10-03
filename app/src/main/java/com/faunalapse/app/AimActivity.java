package com.faunalapse.app;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.OrientationEventListener;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Aim screen: the live camera view, to point the phone at the flower. In photo mode the ROI square
 * is moved with a finger and sized with the slider; it is saved at once, and focus and exposure
 * follow it. The camera is open only while this screen is shown.
 *
 * The screen stays upright (portrait, set in the manifest), so the view turns with the phone like
 * a window, and the photo is shown turned by the sensor orientation.
 */
public final class AimActivity extends Activity implements TextureView.SurfaceTextureListener {

    static final String EXTRA_VIDEO = "video";
    static final String EXTRA_VIDEO_QUALITY = "video_quality";
    /** Share of the screen height the camera view may take; the controls get the rest. */
    private static final double VIEW_SHARE = 0.6;

    /** Camera calls wait for answers, so they run here: a plain thread, as Camera1Session needs. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SessionCamera camera; // used only on the worker thread
    private TextureView view;
    private SquareView square;
    private TextView info;
    private TextView focusResult;
    private Button focusButton;
    private OrientationEventListener orientationListener;
    private boolean video;
    private int videoQuality;
    private int savedSidePx;
    private boolean resumed;
    private JSONObject facts; // from the open camera; null until then

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aim);
        SessionSettings s = SessionSettings.load(this);
        // Mode and video size as chosen on the main screen, even if not saved yet.
        s.video = getIntent().getBooleanExtra(EXTRA_VIDEO, s.video);
        String quality = getIntent().getStringExtra(EXTRA_VIDEO_QUALITY);
        if (quality != null) s.videoQuality = quality;
        video = s.video;
        videoQuality = s.videoQualityConstant();
        savedSidePx = s.savedSidePx;

        view = findViewById(R.id.camera_view);
        view.setSurfaceTextureListener(this);
        square = findViewById(R.id.square);
        square.sideShare = s.squarePercent / 100.0;
        square.centreX = s.squareX;
        square.centreY = s.squareY;
        square.setVisibility(video ? View.GONE : View.VISIBLE);
        findViewById(R.id.size_group).setVisibility(video ? View.GONE : View.VISIBLE);
        findViewById(R.id.aim_hint).setVisibility(video ? View.GONE : View.VISIBLE);
        info = findViewById(R.id.aim_info);
        info.setText(R.string.aim_opening);
        focusResult = findViewById(R.id.focus_result);
        focusButton = findViewById(R.id.focus);
        focusButton.setOnClickListener(v -> focus());
        findViewById(R.id.done).setOnClickListener(v -> finish());
        View middle = findViewById(R.id.middle);
        middle.setVisibility(video ? View.GONE : View.VISIBLE);
        middle.setOnClickListener(v -> {
            square.centreX = 0.5;
            square.centreY = 0.5;
            square.keepInside();
            squareChanged();
        });

        SeekBar size = findViewById(R.id.square_size);
        size.setProgress(s.squarePercent - 10); // the slider runs from 0 to 90: 10 to 100 %
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                square.sideShare = (progress + 10) / 100.0;
                square.keepInside();
                showInfo();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                squareChanged();
            }
        });

        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int degrees) {
                square.setPhoneTurn(RoiMath.quarterTurn(degrees));
            }
        };
        fitView(3, 4); // a first guess; corrected when the camera is open
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
        if (view.isAvailable()) openCamera(view.getSurfaceTexture());
    }

    @Override
    protected void onPause() {
        resumed = false;
        orientationListener.disable();
        worker.execute(this::closeCamera); // free the camera for the session
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // With a camera view, Android destroys it only after this; the worker ends there.
        if (!view.isAvailable()) worker.shutdown();
        super.onDestroy();
    }

    // ---- The camera view (TextureView) ------------------------------------------------------

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (resumed) openCamera(texture);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
        // Release the view only after the camera has stopped drawing into it.
        worker.execute(() -> {
            closeCamera();
            texture.release();
        });
        if (isDestroyed()) worker.shutdown(); // the screen is closing: this was the last task
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }

    // ---- Camera (on the worker thread) ------------------------------------------------------

    private void openCamera(final SurfaceTexture texture) {
        final double share = square.sideShare;
        final double x = square.centreX;
        final double y = square.centreY;
        worker.execute(() -> {
            if (camera != null) return;
            SessionCamera c = SessionRunner.newCamera(this, SessionRunner.useCamera2(this), video, videoQuality, texture);
            try {
                c.open();
                if (!video) c.setSquare(share, x, y);
            } catch (Exception e) {
                c.close();
                runOnUiThread(() -> info.setText(getString(R.string.aim_camera_failed, e.toString())));
                return;
            }
            camera = c;
            final JSONObject d = c.describe();
            runOnUiThread(() -> cameraReady(d));
        });
    }

    private void closeCamera() {
        if (camera == null) return;
        camera.close();
        camera = null;
    }

    private void cameraReady(JSONObject d) {
        facts = d;
        int turn = d.optInt("sensor_orientation");
        int[] photo = size(d.optString(video ? "video_size" : "photo_size"));
        int[] preview = size(d.optString("preview_size"));
        boolean sideways = turn % 180 != 0;
        fitView(sideways ? preview[1] : preview[0], sideways ? preview[0] : preview[1]);
        square.setUp(photo[0], photo[1], turn, this::squareChanged);
        showInfo();
        focus(); // a sharp view from the start
    }

    private void focus() {
        focusButton.setEnabled(false);
        focusResult.setText(R.string.aim_focusing);
        worker.execute(() -> {
            String result;
            try {
                result = camera == null ? "camera not open" : camera.focus();
            } catch (Exception e) {
                result = e.toString();
            }
            final String text = result;
            runOnUiThread(() -> {
                focusButton.setEnabled(true);
                focusResult.setText(getString(R.string.aim_focus_result, text));
            });
        });
    }

    /** Saves the square, moves focus and exposure to it and focuses again. */
    private void squareChanged() {
        SessionSettings s = SessionSettings.load(this);
        s.squarePercent = (int) Math.round(square.sideShare * 100);
        s.squareX = square.centreX;
        s.squareY = square.centreY;
        s.save(this);
        showInfo();
        final double share = square.sideShare;
        final double x = square.centreX;
        final double y = square.centreY;
        worker.execute(() -> {
            try {
                if (camera != null) camera.setSquare(share, x, y);
            } catch (Exception ignored) {
                // focus and exposure stay on the old square; the session sets them again
            }
        });
        focus();
    }

    // ---- What the screen shows --------------------------------------------------------------

    /** Sizes the camera view to the frame's shape (width x height as shown), as large as fits. */
    private void fitView(int width, int height) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        double scale = Math.min((double) m.widthPixels / width, m.heightPixels * VIEW_SHARE / height);
        View frame = findViewById(R.id.camera_frame);
        ViewGroup.LayoutParams lp = frame.getLayoutParams();
        lp.width = (int) Math.round(width * scale);
        lp.height = (int) Math.round(height * scale);
        frame.setLayoutParams(lp);
    }

    private void showInfo() {
        if (facts == null) return;
        String api = "camera2".equals(facts.optString("camera_api")) ? "Camera2" : "Camera1";
        if (video) {
            info.setText(getString(R.string.aim_video_info, facts.optString("video_size"),
                    SessionSettings.VIDEO_FPS, api));
            return;
        }
        int[] photo = size(facts.optString("photo_size"));
        int[] r = RoiMath.square(photo[0], photo[1], square.sideShare, square.centreX, square.centreY);
        int real = r[2] - r[0];
        String text = getString(R.string.aim_photo_info, Math.round(square.sideShare * 100), real,
                RoiMath.savedSide(real, savedSidePx), api, facts.optString("photo_size"));
        if (real < savedSidePx) text += " " + getString(R.string.aim_not_enlarged, savedSidePx);
        info.setText(text);
    }

    /** "4000x3000" from the camera facts as {4000, 3000}. */
    private static int[] size(String wxh) {
        String[] p = wxh.split("x");
        try {
            return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        } catch (RuntimeException e) {
            return new int[] {4, 3};
        }
    }
}
