package com.faunalapse.app;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.OrientationEventListener;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Aim screen: the live camera view, to point the phone at the flower. The ROI square (for photos
 * and video) is moved with a finger placed on it and sized with two fingers or the slider; it is
 * saved at once, and focus and exposure follow it. The controls scroll under the camera view, with
 * "More below" while some are hidden; Done and Start stay at the bottom. Focus is fixed at a distance where Camera2 allows it, or
 * automatic. The camera is open only while this screen is shown. "Start session here" starts the
 * session with the saved settings while the phone is already in its place (its position counts).
 * While a session is paused (SessionRunner.userPaused) the same screen adjusts it: a red bar says
 * that nothing is recorded, and "Continue session" replaces Start.
 *
 * The screen stays upright (portrait, set in the manifest), so the view turns with the phone like
 * a window, and the photo is shown turned by the sensor orientation.
 */
public final class AimActivity extends Activity implements TextureView.SurfaceTextureListener {

    /** Share of the screen height the camera view may take (owner): the controls below get the
     *  rest. A smaller view still shows the whole camera field, only smaller. */
    private static final double VIEW_SHARE = 0.4;

    /** Height the scrolling controls keep at least on small screens (the square's size slider and
     *  its pixels stay in sight); the camera view gets smaller instead. */
    private static final int MIN_CONTROLS_DP = 150;

    /** Camera calls wait for answers, so they run here: a plain thread, as Camera1Session needs. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** True while this screen holds the camera: a session going on after a pause waits for it. */
    static volatile boolean cameraInUse;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean forPause; // opened while a session is paused
    private final Runnable pauseWatch = new Runnable() {
        @Override
        public void run() {
            // The session went on by itself (or ended): let go of the camera at once.
            if (!CaptureService.sessionRunning || !SessionRunner.userPaused) {
                Toast.makeText(AimActivity.this, CaptureService.sessionRunning ? R.string.aim_continued
                        : R.string.aim_session_ended, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            handler.postDelayed(this, 1000);
        }
    };
    private SessionCamera camera; // used only on the worker thread
    private TextureView view;
    private SquareView square;
    private TextView info;
    private TextView squareHelp;
    private ScrollView controls;
    private TextView moreBelow;
    private int frameW = 3; // shape of the camera view as shown
    private int frameH = 4;
    private TextView focusResult;
    private SeekBar size;
    private Button focusButton;
    private Button startButton;
    private OrientationEventListener orientationListener;
    private int phoneTurn; // how the phone is held now (RoiMath.quarterTurn)
    private boolean video;
    private int videoFps;
    private int savedSidePx;
    private boolean resumed;
    private RadioGroup focusMode;
    private SeekBar focusSlider;
    private TextView focusDistance;
    private TextView focusWarning;
    private TextView focusHelp;
    private boolean canFixFocus;     // from the open camera
    private double closestDiopters;  // the nearest lens distance
    private boolean calibrated;      // the camera's lens distances are real distances
    private boolean focusFixed;      // as saved
    private double focusDiopters;    // as saved
    private JSONObject facts; // from the open camera; null until then

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aim);
        if (CaptureService.running && !(CaptureService.sessionRunning && SessionRunner.userPaused)) {
            finish(); // the session or a test uses the camera
            return;
        }
        forPause = CaptureService.sessionRunning;
        SessionSettings s = SessionSettings.load(this); // the main screen saved its values
        video = s.video;
        videoFps = s.videoFps;
        focusFixed = s.focusFixed;
        focusDiopters = s.focusDiopters;
        savedSidePx = s.savedSidePx;

        view = findViewById(R.id.camera_view);
        view.setSurfaceTextureListener(this);
        square = findViewById(R.id.square);
        square.sideShare = s.squarePercent / 100.0;
        square.centreX = s.squareX;
        square.centreY = s.squareY;
        info = findViewById(R.id.aim_info);
        info.setText(R.string.aim_opening);
        squareHelp = findViewById(R.id.square_help);
        squareHelp.setText(R.string.aim_hint);
        focusResult = findViewById(R.id.focus_result);
        focusButton = findViewById(R.id.focus);
        focusButton.setOnClickListener(v -> focus());
        findViewById(R.id.done).setOnClickListener(v -> finish());
        startButton = findViewById(R.id.start_here);
        startButton.setOnClickListener(v -> startHere());
        startButton.setText(MainActivity.withIcon(startButton, R.drawable.ic_start,
                forPause ? R.string.continue_session : R.string.aim_start));
        if (forPause) {
            TextView bar = findViewById(R.id.paused_bar);
            bar.setText(getString(SessionRunner.pauseEndsSession ? R.string.aim_paused_end : R.string.aim_paused,
                    new SimpleDateFormat("HH:mm", Locale.US).format(new Date(SessionRunner.pauseEnds))));
            bar.setVisibility(View.VISIBLE);
        }
        findViewById(R.id.middle).setOnClickListener(v -> {
            square.centreX = 0.5;
            square.centreY = 0.5;
            square.keepInside();
            squareChanged();
        });

        size = findViewById(R.id.square_size);
        size.setProgress(s.squarePercent - 10);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                square.sideShare = (progress + 10) / 100.0; // the slider runs from 0 to 90: 10 to 100 %
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

        focusMode = findViewById(R.id.focus_mode);
        focusSlider = findViewById(R.id.focus_slider);
        focusDistance = findViewById(R.id.focus_distance);
        focusWarning = findViewById(R.id.focus_warning);
        focusHelp = findViewById(R.id.focus_help);
        focusSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                focusDiopters = closestDiopters * (1 - progress / 1000.0); // left near, right far
                showFocus();
                applyFixedFocus();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                saveFocus();
            }
        });

        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int degrees) {
                phoneTurn = RoiMath.quarterTurn(degrees);
                square.setPhoneTurn(phoneTurn);
            }
        };
        HelpToggles.wire(findViewById(R.id.controls_content));
        controls = findViewById(R.id.controls);
        fitView(3, 4); // a first guess; corrected after the layout and when the camera is open
        moreBelow = findViewById(R.id.more_below);
        moreBelow.setText(MainActivity.withIcon(moreBelow, R.drawable.ic_more, R.string.aim_more_below));
        moreBelow.setOnClickListener(v -> controls.smoothScrollBy(0, controls.getHeight() * 2 / 3));
        controls.setOnScrollChangeListener((v, x, y, oldX, oldY) -> showMoreBelow());
        findViewById(R.id.controls_content).addOnLayoutChangeListener(
                (v, l, t, r, b, oldL, oldT, oldR, oldB) -> controls.post(this::showMoreBelow));
        // The camera view's size depends on the screen space left for it, known after the layout.
        findViewById(R.id.aim_root).addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (b - t != oldB - oldT) v.post(() -> fitView(frameW, frameH));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
        if (forPause) handler.post(pauseWatch);
        if (view.isAvailable()) openCamera(view.getSurfaceTexture());
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(pauseWatch);
        orientationListener.disable();
        worker.execute(this::closeCamera); // free the camera for the session
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // With a camera view, Android destroys it only after this; the worker ends there.
        if (view == null || !view.isAvailable()) worker.shutdown();
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
            SessionCamera c = SessionRunner.newCamera(this, SessionRunner.useCamera2(this), video, savedSidePx,
                    videoFps, texture);
            try {
                c.open();
                c.setSquare(share, x, y);
            } catch (Exception e) {
                c.close();
                runOnUiThread(() -> info.setText(getString(R.string.aim_camera_failed, e.toString())));
                return;
            }
            camera = c;
            cameraInUse = true;
            final JSONObject d = c.describe();
            runOnUiThread(() -> cameraReady(d));
        });
    }

    private void closeCamera() {
        if (camera == null) return;
        camera.close();
        camera = null;
        cameraInUse = false;
    }

    private void cameraReady(JSONObject d) {
        facts = d;
        int turn = d.optInt("sensor_orientation");
        int[] photo = size(d.optString("photo_size"));
        int[] preview = size(d.optString("preview_size"));
        boolean sideways = turn % 180 != 0;
        fitView(sideways ? preview[1] : preview[0], sideways ? preview[0] : preview[1]);
        square.setUp(photo[0], photo[1], turn, new SquareView.Listener() {
            @Override
            public void onResized() {
                size.setProgress((int) Math.round(square.sideShare * 100) - 10);
                showInfo();
            }

            @Override
            public void onChanged() {
                squareChanged();
            }

            @Override
            public void onTouchedOutside() {
                Toast.makeText(AimActivity.this, R.string.aim_touch_inside, Toast.LENGTH_SHORT).show();
            }
        });
        showInfo();
        canFixFocus = d.optBoolean("fixed_focus_possible");
        closestDiopters = d.optDouble("closest_focus_diopters", 0);
        if (canFixFocus) focusDiopters = Math.max(0, Math.min(closestDiopters, focusDiopters)); // as the lens can
        String calibration = d.optString("focus_distance_calibration");
        calibrated = "calibrated".equals(calibration) || "approximate".equals(calibration);
        findViewById(R.id.focus_group).setVisibility(View.VISIBLE);
        focusMode.setVisibility(canFixFocus ? View.VISIBLE : View.GONE);
        focusMode.setOnCheckedChangeListener(null);
        focusMode.check(focusFixed ? R.id.focus_fixed : R.id.focus_auto);
        focusMode.setOnCheckedChangeListener((g, id) -> setFocusFixed(id == R.id.focus_fixed));
        showFocus();
        if (canFixFocus && focusFixed) {
            applyFixedFocus(); // the lens goes to the saved distance
            focusResult.setText(getString(R.string.aim_focus_result, lensWords()));
        } else {
            focus(); // a sharp view from the start
        }
    }

    // ---- Focus: auto or fixed ---------------------------------------------------------------

    /** Auto to fixed starts where autofocus left the lens; fixed to auto focuses again. */
    private void setFocusFixed(final boolean fixed) {
        if (fixed == focusFixed) return;
        focusFixed = fixed;
        if (!fixed) {
            worker.execute(() -> {
                try {
                    if (camera != null) camera.setFixedFocus(null);
                } catch (Exception ignored) {
                    // the next focus() reports camera problems
                }
            });
            saveFocus();
            showFocus();
            focus();
            return;
        }
        worker.execute(() -> {
            final float lens = camera == null ? Float.NaN : camera.lensDiopters();
            runOnUiThread(() -> {
                if (!Float.isNaN(lens)) focusDiopters = lens;
                focusDiopters = Math.max(0, Math.min(closestDiopters, focusDiopters));
                applyFixedFocus();
                saveFocus();
                showFocus();
            });
        });
    }

    private void applyFixedFocus() {
        final float d = (float) focusDiopters;
        worker.execute(() -> {
            try {
                if (camera != null) camera.setFixedFocus(d);
            } catch (Exception ignored) {
                // the lens stays; the session sets it again
            }
        });
    }

    private void showFocus() {
        boolean fixed = canFixFocus && focusFixed;
        findViewById(R.id.fixed_group).setVisibility(fixed ? View.VISIBLE : View.GONE);
        focusWarning.setVisibility(canFixFocus ? View.GONE : View.VISIBLE); // bold: clear also without colour
        focusHelp.setText(fixed ? R.string.focus_hint_fixed
                : video ? R.string.focus_hint_auto_video : R.string.focus_hint_auto);
        if (!fixed || closestDiopters <= 0) return;
        focusSlider.setProgress(lensProgress());
        focusDistance.setText(calibrated ? getString(R.string.focus_distance, lensWords())
                : getString(R.string.focus_position, Math.round(lensProgress() / 10f)));
    }

    /** The fixed focus on the slider: 0 (nearest) to 1000 (far away). */
    private int lensProgress() {
        return (int) Math.round((1 - focusDiopters / closestDiopters) * 1000);
    }

    /** The fixed focus in words: a distance where the phone tells it, else a lens position. */
    private String lensWords() {
        return calibrated ? SessionSettings.distanceWords(focusDiopters)
                : getString(R.string.focus_position_short, Math.round(lensProgress() / 10f));
    }

    private void saveFocus() {
        SessionSettings s = SessionSettings.load(this);
        s.focusFixed = focusFixed;
        s.focusDiopters = focusDiopters;
        s.save(this);
    }

    /** Autofocus once. With a fixed focus: autofocus once, then fix the focus where the lens is. */
    private void focus() {
        focusButton.setEnabled(false);
        focusResult.setText(R.string.aim_focusing);
        final boolean fixed = canFixFocus && focusFixed;
        final float before = (float) focusDiopters;
        worker.execute(() -> {
            String result;
            float lens = Float.NaN;
            try {
                if (camera == null) {
                    result = "camera not open";
                } else if (!fixed) {
                    result = camera.focus();
                } else {
                    camera.setFixedFocus(null);
                    result = camera.focus();
                    lens = camera.lensDiopters();
                    camera.setFixedFocus(Float.isNaN(lens) ? before : lens);
                }
            } catch (Exception e) {
                result = e.toString();
            }
            final String text = result;
            final float found = lens;
            runOnUiThread(() -> {
                focusButton.setEnabled(true);
                focusResult.setText(getString(R.string.aim_focus_result, text));
                if (fixed && !Float.isNaN(found)) {
                    focusDiopters = found;
                    saveFocus();
                    showFocus();
                }
            });
        });
    }

    /** Closes the camera, then starts the session (it opens the camera at once), or lets a paused
     *  one go on, and goes back to the main screen, which shows the session. */
    private void startHere() {
        startButton.setEnabled(false);
        final int turn = phoneTurn;
        worker.execute(() -> {
            closeCamera();
            runOnUiThread(() -> {
                if (forPause) CaptureService.pauseSession(this, false, turn);
                else if (!CaptureService.running) CaptureService.startSession(this, turn);
                finish();
            });
        });
    }

    /** Saves the square, moves focus and exposure to it and focuses again. */
    private void squareChanged() {
        square.sideShare = Math.round(square.sideShare * 100) / 100.0; // whole percent, as saved
        square.keepInside();
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

    /** Sizes the camera view to the frame's shape (width x height as shown): as large as fits, at
     *  most VIEW_SHARE of the screen height, and leaving MIN_CONTROLS_DP for the controls. */
    private void fitView(int width, int height) {
        frameW = width;
        frameH = height;
        DisplayMetrics m = getResources().getDisplayMetrics();
        double maxHeight = m.heightPixels * VIEW_SHARE;
        View root = findViewById(R.id.aim_root);
        View frame = findViewById(R.id.camera_frame);
        if (root.getHeight() > 0) { // after the first layout: the space left by the bars and buttons
            double left = frame.getHeight() + controls.getHeight() - MIN_CONTROLS_DP * m.density;
            maxHeight = Math.min(maxHeight, left);
        }
        double scale = Math.min((double) m.widthPixels / width, maxHeight / height);
        ViewGroup.LayoutParams lp = frame.getLayoutParams();
        int w = (int) Math.round(width * scale);
        int h = (int) Math.round(height * scale);
        if (lp.width == w && lp.height == h) return;
        lp.width = w;
        lp.height = h;
        frame.setLayoutParams(lp);
    }

    /** "More below" while some controls are hidden under the bottom edge. */
    private void showMoreBelow() {
        moreBelow.setVisibility(controls.canScrollVertically(1) ? View.VISIBLE : View.GONE);
    }

    /** The square's pixels, live under its slider; the camera's zoom for video in its explanation. */
    private void showInfo() {
        if (facts == null) return;
        int[] photo = size(facts.optString("photo_size"));
        long percent = Math.round(square.sideShare * 100);
        int real;
        String text;
        if (video) {
            int[] stream = size(facts.optString("video_stream_size"));
            int[] array = facts.has("pixel_array") ? size(facts.optString("pixel_array")) : photo;
            String zoom = facts.optString("video_zoom", "none");
            real = RoiMath.videoSquare(photo[0], photo[1], array[0], array[1], stream[0], stream[1],
                    facts.optDouble("max_digital_zoom", 1), zoom, square.sideShare, square.centreX, square.centreY).realPx;
            text = getString(R.string.aim_video_info, percent, real, facts.optString("video_stream_size"),
                    RoiMath.savedSide(real, savedSidePx), videoFps);
            squareHelp.setText(getString(R.string.two_paragraphs, getString(R.string.aim_hint),
                    getString("anywhere".equals(zoom) ? R.string.aim_zoom_anywhere
                            : "centred".equals(zoom) ? R.string.aim_zoom_centred : R.string.aim_zoom_none)));
        } else {
            int[] r = RoiMath.square(photo[0], photo[1], square.sideShare, square.centreX, square.centreY);
            real = r[2] - r[0];
            text = getString(R.string.aim_photo_info, percent, real, facts.optString("photo_size"),
                    RoiMath.savedSide(real, savedSidePx));
        }
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
