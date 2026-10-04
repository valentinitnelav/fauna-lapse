package com.faunalapse.app;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.OrientationEventListener;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Aim screen: the live camera view, to point the phone at the flower. In photo mode the ROI square
 * is moved with a finger and sized with two fingers or the slider; it is saved at once, and focus
 * and exposure follow it. Focus is automatic, or fixed at a distance where Camera2 allows it. The camera is open only while this screen is shown. "Start session here" starts the
 * session with the saved settings while the phone is already in its place (its position counts).
 *
 * The screen stays upright (portrait, set in the manifest), so the view turns with the phone like
 * a window, and the photo is shown turned by the sensor orientation.
 */
public final class AimActivity extends Activity implements TextureView.SurfaceTextureListener {

    /** Share of the screen height the camera view may take; the controls get the rest. */
    private static final double VIEW_SHARE = 0.6;

    /** Camera calls wait for answers, so they run here: a plain thread, as Camera1Session needs. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SessionCamera camera; // used only on the worker thread
    private TextureView view;
    private SquareView square;
    private TextView info;
    private TextView focusResult;
    private SeekBar size;
    private Button focusButton;
    private Button startButton;
    private OrientationEventListener orientationListener;
    private int phoneTurn; // how the phone is held now (RoiMath.quarterTurn)
    private boolean video;
    private int videoQuality;
    private int videoFps;
    private int savedSidePx;
    private boolean resumed;
    private RadioGroup focusMode;
    private SeekBar focusSlider;
    private TextView focusDistance;
    private TextView focusHint;
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
        SessionSettings s = SessionSettings.load(this); // the main screen saved its values
        video = s.video;
        videoQuality = s.videoQualityConstant();
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
        square.setVisibility(video ? View.GONE : View.VISIBLE);
        findViewById(R.id.size_group).setVisibility(video ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.aim_hint)).setText(video ? R.string.aim_hint_video : R.string.aim_hint);
        info = findViewById(R.id.aim_info);
        info.setText(R.string.aim_opening);
        focusResult = findViewById(R.id.focus_result);
        focusButton = findViewById(R.id.focus);
        focusButton.setOnClickListener(v -> focus());
        findViewById(R.id.done).setOnClickListener(v -> finish());
        startButton = findViewById(R.id.start_here);
        startButton.setOnClickListener(v -> startHere());
        startButton.setText(MainActivity.withIcon(startButton, R.drawable.ic_start, R.string.aim_start));
        View middle = findViewById(R.id.middle);
        middle.setVisibility(video ? View.GONE : View.VISIBLE);
        middle.setOnClickListener(v -> {
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
        focusHint = findViewById(R.id.focus_hint);
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
            SessionCamera c = SessionRunner.newCamera(this, SessionRunner.useCamera2(this), video, videoQuality,
                    videoFps, texture);
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
        focusHint.setText(!canFixFocus ? R.string.focus_not_possible : fixed ? R.string.focus_hint_fixed
                : video ? R.string.focus_hint_auto_video : R.string.focus_hint_auto);
        // Autofocus only: a warning, clear also without colour.
        focusHint.setTextColor(canFixFocus ? focusResult.getCurrentTextColor() : getColor(R.color.warning));
        focusHint.setTypeface(null, canFixFocus ? Typeface.NORMAL : Typeface.BOLD);
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

    /** Closes the camera, then starts the session (it opens the camera at once) and goes back to
     *  the main screen, which shows the session. */
    private void startHere() {
        startButton.setEnabled(false);
        final int turn = phoneTurn;
        worker.execute(() -> {
            closeCamera();
            runOnUiThread(() -> {
                if (!CaptureService.running) CaptureService.startSession(this, turn);
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
                    videoFps, api));
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
