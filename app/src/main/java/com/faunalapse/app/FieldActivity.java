package com.faunalapse.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Field metadata screen (card 2 of the main screen): who, where and what (FieldInfo), in three
 * cards: the setup with the user's own fields (CustomFields), the position (typed, or one GPS
 * position from Get location, GpsFix) and the site photos (SitePhotos). Its own screen, like the
 * aim screen, so the main screen stays short (maintainer, 2026-10-11). Done and Back save and go
 * back; a value that is not usable is shown first.
 */
public final class FieldActivity extends Activity {

    private static final int LOCATION_REQUEST = 1;
    private static final int SITE_PHOTO_REQUEST = 2;
    private static final int SITE_PHOTO_PERMISSION = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ColorStateList bodyColor;
    private EditText setupBy;
    private EditText site;
    private EditText transect;
    private EditText plant;
    private EditText habitat;
    private EditText sampleId;
    private EditText sessionId;
    private EditText cameraHeight;
    private EditText distance;
    private TextView distanceNote;
    private boolean distanceFromFocus; // the distance shows the fixed focus until the user types
    private boolean settingDistance;   // the app writes into the distance field, not the user
    private CustomFields customFields;
    private View sitePhotosScroll;
    private LinearLayout sitePhotos;   // the waiting site photos, small
    private TextView sitePhotosText;
    private EditText sitePhotosAbout;
    private File photoToTake;          // the file the camera app writes now, or null
    private TextView locationSwitch;
    private EditText latitude;
    private EditText longitude;
    private EditText accuracy;
    private EditText gpsGoal;
    private TextView gpsGoalNote;
    private boolean gpsFilled; // the position fields show this phone's GPS position: a tap asks first
    private TextView locationText;
    private Button locationButton;
    private Location location; // the saved position (GPS or typed), or null
    private GpsFix gps;        // while searching
    private boolean gpsStopped; // the user stopped the search (not the time limit)
    private boolean discard;    // left without saving: onPause saves nothing

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_field);
        setupBy = findViewById(R.id.setup_by);
        site = findViewById(R.id.site);
        transect = findViewById(R.id.transect);
        plant = findViewById(R.id.plant);
        habitat = findViewById(R.id.habitat);
        sampleId = findViewById(R.id.sample_id);
        sessionId = findViewById(R.id.session_id);
        cameraHeight = findViewById(R.id.camera_height);
        bodyColor = setupBy.getTextColors();
        distance = findViewById(R.id.distance);
        distanceNote = findViewById(R.id.distance_note);
        distance.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (settingDistance) return;
                distanceFromFocus = s.toString().trim().isEmpty(); // typed; empty: the focus again
                distanceNote.setVisibility(View.GONE);
            }
        });
        distance.setOnFocusChangeListener((v, focused) -> {
            if (!focused && distanceFromFocus) showFocusDistance();
        });
        customFields = new CustomFields(this, findViewById(R.id.custom_fields), findViewById(R.id.custom_add));
        sitePhotosScroll = findViewById(R.id.site_photos_scroll);
        sitePhotos = findViewById(R.id.site_photos);
        sitePhotosText = findViewById(R.id.site_photos_text);
        sitePhotosAbout = findViewById(R.id.site_photos_about);
        findViewById(R.id.site_photo_take).setOnClickListener(v -> takeSitePhoto());
        if (savedInstanceState != null && savedInstanceState.getString("photo_to_take") != null) {
            photoToTake = new File(savedInstanceState.getString("photo_to_take")); // Android closed us meanwhile
        }
        ((TextView) findViewById(R.id.phone_model)).setText(getString(R.string.phone_model, Build.MANUFACTURER, Build.MODEL));
        ((TextView) findViewById(R.id.phone_id)).setText(FieldInfo.phoneId(this));
        latitude = findViewById(R.id.latitude);
        longitude = findViewById(R.id.longitude);
        accuracy = findViewById(R.id.accuracy);
        TextWatcher typed = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                showGpsGoal(); // and the position line: GPS or typed by hand, above the goal or not
            }
        };
        for (EditText f : new EditText[] {latitude, longitude, accuracy}) {
            f.addTextChangedListener(typed);
            f.setOnClickListener(v -> askToEditPosition((EditText) v)); // only while not focusable
        }
        gpsGoal = findViewById(R.id.gps_goal);
        gpsGoalNote = findViewById(R.id.gps_goal_note);
        gpsGoal.addTextChangedListener(typed);
        locationText = findViewById(R.id.location_text);
        locationButton = findViewById(R.id.get_location);
        locationButton.setOnClickListener(v -> getLocation());
        locationSwitch = findViewById(R.id.location_switch);
        findViewById(R.id.location_settings).setOnClickListener(
                v -> MainActivity.openPage(this, new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        findViewById(R.id.done).setOnClickListener(v -> leave());
        if (Build.VERSION.SDK_INT >= 33) { // Android 16 calls this instead of onBackPressed
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::leave);
        }
        HelpToggles.wire(findViewById(R.id.field_root));
        showField(FieldInfo.load(this));
    }

    /** Back on Android 7 to 15; Android 16 calls the callback set in onCreate instead. */
    @Override
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        leave();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (distanceFromFocus) showFocusDistance();
        showSitePhotos();
        showLocationSwitch();
    }

    /** Back from the quick settings over this screen: the Location switch may have changed. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) showLocationSwitch();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (photoToTake != null) out.putString("photo_to_take", photoToTake.getPath());
    }

    @Override
    protected void onPause() {
        if (gps != null) { // the GPS is not left on in the background
            gps.cancel();
            locationDone();
            showLocation(getString(R.string.location_stopped));
        }
        if (!discard) {
            try { // keep what was typed, if it is usable (also while the camera app takes a site photo)
                fieldOnScreen().save(this);
            } catch (IllegalArgumentException ignored) {
                // shown on Done or Back
            }
        }
        super.onPause();
    }

    /** Done or Back: saves and goes back; a value that is not usable first, with the choice to
     *  correct it or leave without saving. */
    private void leave() {
        try {
            fieldOnScreen().save(this);
            finish();
        } catch (IllegalArgumentException e) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.problem_title)
                    .setMessage(getString(R.string.two_paragraphs, e.getMessage(), getString(R.string.leave_note)))
                    .setPositiveButton(R.string.leave_correct, null)
                    .setNegativeButton(R.string.leave_anyway, (dialog, which) -> {
                        discard = true;
                        finish();
                    })
                    .show();
        }
    }

    private void showField(FieldInfo f) {
        setupBy.setText(f.setupBy);
        site.setText(f.site);
        transect.setText(f.transect);
        plant.setText(f.plant);
        habitat.setText(f.habitat);
        sampleId.setText(f.sampleId);
        sessionId.setText(f.sessionId);
        cameraHeight.setText(f.cameraHeight);
        distanceFromFocus = f.distanceFromFocus;
        settingDistance = true;
        distance.setText(f.distance);
        settingDistance = false;
        if (distanceFromFocus) showFocusDistance();
        customFields.show(f.custom);
        sitePhotosAbout.setText(f.sitePhotosAbout);
        gpsGoal.setText(String.valueOf(f.gpsGoalM));
        showPosition(f.location);
    }

    /** While the distance follows the fixed focus: the focus distance in m (empty where the phone
     *  does not tell lens distances), with a note to check it. */
    private void showFocusDistance() {
        String m = FieldInfo.focusDistance(this);
        settingDistance = true;
        distance.setText(m);
        settingDistance = false;
        if (!m.isEmpty()) {
            distanceNote.setText(getString(R.string.distance_note,
                    SessionSettings.distanceWords(SessionSettings.load(this).focusDiopters)));
        }
        distanceNote.setVisibility(m.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---- Site photos (SitePhotos) -------------------------------------------------------------

    /** Opens the phone's camera app for one site photo; it writes into a waiting file. */
    private void takeSitePhoto() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Android lets an app that declares the camera permission open the camera app only with it.
            requestPermissions(new String[] {Manifest.permission.CAMERA}, SITE_PHOTO_PERMISSION);
            return;
        }
        File file = SitePhotos.newFile(this);
        Uri uri = SitePhotos.uri(this, file);
        Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        camera.setClipData(ClipData.newRawUri("", uri)); // the right to write goes with the link
        try {
            photoToTake = file;
            startActivityForResult(camera, SITE_PHOTO_REQUEST);
        } catch (ActivityNotFoundException | SecurityException e) {
            photoToTake = null;
            Toast.makeText(this, R.string.site_photo_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** The waiting site photos, small, in a row; a tap shows one larger. Decoded on a second thread. */
    private void showSitePhotos() {
        final List<File> files = SitePhotos.waiting(this);
        sitePhotos.removeAllViews();
        sitePhotosScroll.setVisibility(files.isEmpty() ? View.GONE : View.VISIBLE);
        sitePhotosText.setText(files.isEmpty() ? getString(R.string.site_photos_none)
                : getResources().getQuantityString(R.plurals.site_photos_count, files.size(), files.size()));
        final int side = Math.round(72 * getResources().getDisplayMetrics().density);
        final List<ImageView> views = new ArrayList<>();
        for (final File f : files) {
            ImageView v = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
            lp.setMarginEnd(side / 8);
            lp.topMargin = side / 8;
            v.setLayoutParams(lp);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setContentDescription(getString(R.string.site_photo_title, photoTime(f)));
            v.setOnClickListener(x -> showSitePhoto(f));
            sitePhotos.addView(v);
            views.add(v);
        }
        new Thread(() -> {
            for (int i = 0; i < files.size(); i++) {
                final Bitmap b = SitePhotos.picture(files.get(i), side);
                final ImageView v = views.get(i);
                handler.post(() -> v.setImageBitmap(b));
            }
        }, "site-photos").start();
    }

    /** One site photo, as large as the screen allows, with Delete. */
    private void showSitePhoto(final File f) {
        final int side = getResources().getDisplayMetrics().widthPixels;
        new Thread(() -> {
            final Bitmap b = SitePhotos.picture(f, side / 2);
            handler.post(() -> {
                if (isFinishing()) return;
                AlertDialog.Builder d = new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.site_photo_title, photoTime(f)))
                        .setPositiveButton(R.string.site_photo_close, null)
                        .setNegativeButton(R.string.site_photo_delete, (dialog, w) -> {
                            f.delete();
                            showSitePhotos();
                        });
                if (b == null) {
                    d.setMessage(R.string.site_photo_unreadable);
                } else {
                    ImageView v = new ImageView(this);
                    v.setAdjustViewBounds(true);
                    v.setImageBitmap(b);
                    d.setView(v);
                }
                d.show();
            });
        }, "site-photo").start();
    }

    /** "2026-10-05 15:01" from a photo's name (its time). */
    private static String photoTime(File f) {
        String n = f.getName();
        return n.length() < 13 ? n : n.substring(0, 4) + "-" + n.substring(4, 6) + "-" + n.substring(6, 8) + " "
                + n.substring(9, 11) + ":" + n.substring(11, 13);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SITE_PHOTO_REQUEST && photoToTake != null) {
            if (resultCode != RESULT_OK) photoToTake.delete(); // cancelled; an empty file goes anyway
            photoToTake = null;
            showSitePhotos();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == SITE_PHOTO_PERMISSION
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            takeSitePhoto();
        }
        if (requestCode == LOCATION_REQUEST) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                getLocation();
            } else {
                showLocation(getString(R.string.location_permission));
            }
        }
    }

    // ---- Position (GpsFix) ----------------------------------------------------------------------

    /** Puts a position into the latitude, longitude and accuracy fields (empty for none). A GPS
     *  position is guarded: a tap on its fields asks before they can be changed. */
    private void showPosition(Location l) {
        location = l;
        latitude.setText(l == null ? "" : FieldInfo.degrees(l.getLatitude()));
        longitude.setText(l == null ? "" : FieldInfo.degrees(l.getLongitude()));
        accuracy.setText(l == null ? "" : accuracyText(l));
        guardPosition(l != null && FieldInfo.fromGps(l));
        showLocation(null);
    }

    /** The accuracy in m as the field shows it: one decimal, as in the record ("" when not given). */
    static String accuracyText(Location l) {
        return l.hasAccuracy() ? MainActivity.number(Math.round(l.getAccuracy() * 10) / 10.0) : "";
    }

    /** {@code on}: the position fields take no typing; a tap asks first (askToEditPosition), so a
     *  touch while scrolling cannot change a GPS position by accident. */
    private void guardPosition(boolean on) {
        gpsFilled = on;
        for (EditText f : new EditText[] {latitude, longitude, accuracy}) {
            f.setFocusable(!on);
            f.setFocusableInTouchMode(!on);
        }
    }

    private void askToEditPosition(final EditText field) {
        if (!gpsFilled) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.position_edit_title)
                .setMessage(R.string.position_edit_text)
                .setPositiveButton(R.string.position_edit_yes, (dialog, which) -> {
                    guardPosition(false);
                    field.requestFocus();
                    field.setSelection(field.length());
                    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                            .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
                })
                .setNegativeButton(R.string.position_edit_no, null)
                .show();
    }

    /** The field metadata on the screen; throws with a plain-language message when a value is not
     *  usable. */
    private FieldInfo fieldOnScreen() {
        FieldInfo f = new FieldInfo();
        f.setupBy = MainActivity.text(setupBy);
        f.site = MainActivity.text(site);
        f.transect = MainActivity.text(transect);
        f.plant = MainActivity.text(plant);
        f.habitat = MainActivity.text(habitat);
        f.sampleId = MainActivity.text(sampleId);
        f.sessionId = MainActivity.text(sessionId);
        f.cameraHeight = MainActivity.text(cameraHeight);
        f.distance = MainActivity.text(distance);
        f.distanceFromFocus = distanceFromFocus;
        f.custom = customFields.read();
        f.sitePhotosAbout = MainActivity.text(sitePhotosAbout);
        Integer goal = FieldInfo.gpsGoal(MainActivity.text(gpsGoal));
        if (goal == null) throw new IllegalArgumentException(getString(R.string.gps_goal_problem, FieldInfo.MAX_GPS_GOAL_M));
        f.gpsGoalM = goal;
        f.location = locationOnScreen();
        String p = f.problem();
        if (p != null) throw new IllegalArgumentException(p);
        return f;
    }

    /** The position in the fields: the GPS position while they still show it, else typed by hand;
     *  null when all are empty. Throws with a plain-language message when it is not usable. */
    private Location locationOnScreen() {
        String lat = MainActivity.text(latitude);
        String lon = MainActivity.text(longitude);
        String acc = MainActivity.text(accuracy);
        if (lat.isEmpty() && lon.isEmpty() && acc.isEmpty()) return null;
        if (lat.isEmpty() || lon.isEmpty()) throw new IllegalArgumentException(getString(R.string.location_both));
        double la = MainActivity.decimal(this, latitude, R.string.field_latitude);
        double lo = MainActivity.decimal(this, longitude, R.string.field_longitude);
        Float ac = acc.isEmpty() ? null : (float) MainActivity.decimal(this, accuracy, R.string.field_uncertainty);
        String p = FieldInfo.positionProblem(la, lo, ac);
        if (p != null) throw new IllegalArgumentException(p);
        if (location != null && lat.equals(FieldInfo.degrees(location.getLatitude()))
                && lon.equals(FieldInfo.degrees(location.getLongitude())) && acc.equals(accuracyText(location))) {
            return location;
        }
        return FieldInfo.typed(la, lo, ac);
    }

    /** Where the position in the fields comes from, or what is wrong with it; after {@code note}
     *  (null: none). */
    private void showLocation(String note) {
        String text;
        boolean ok = true;
        try {
            Location l = locationOnScreen();
            if (l == null) {
                text = getString(R.string.location_none);
            } else if (!FieldInfo.fromGps(l)) {
                text = getString(R.string.location_typed);
            } else {
                Integer n = FieldInfo.satellites(l);
                text = getString(R.string.location_saved,
                        new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(l.getTime())),
                        n == null ? "" : getResources().getQuantityString(R.plurals.location_saved_satellites, n, n));
                Integer goal = FieldInfo.gpsGoal(MainActivity.text(gpsGoal));
                if (goal != null && goal > 0 && l.getAccuracy() > goal) text += " " + getString(R.string.location_weak, goal);
            }
        } catch (IllegalArgumentException e) {
            text = e.getMessage();
            ok = false;
        }
        locationText.setText(note == null ? text : note + "\n" + text);
        // A problem shows at once, in the warning colour and bold (also without colour).
        locationText.setTextColor(ok ? bodyColor : ColorStateList.valueOf(getColor(R.color.warning)));
        locationText.setTypeface(null, ok ? Typeface.NORMAL : Typeface.BOLD);
    }

    /** The note under Stop at: a problem, or a warning that the search will likely run its full
     *  time; then the position line, whose note depends on the goal. */
    private void showGpsGoal() {
        Integer g = FieldInfo.gpsGoal(MainActivity.text(gpsGoal));
        long min = GpsFix.TIMEOUT_MS / 60000;
        String note = g == null ? getString(R.string.gps_goal_problem, FieldInfo.MAX_GPS_GOAL_M)
                : g == 0 ? getString(R.string.gps_goal_zero, min)
                : g < FieldInfo.LOW_GPS_GOAL_M ? getString(R.string.gps_goal_low, FieldInfo.LOW_GPS_GOAL_M, min)
                : null;
        gpsGoalNote.setText(note);
        gpsGoalNote.setVisibility(note == null ? View.GONE : View.VISIBLE);
        gpsGoalNote.setTypeface(null, g == null ? Typeface.BOLD : Typeface.NORMAL); // a problem: bold, as elsewhere
        if (gps == null) showLocation(null);
    }

    /** Get location: the GPS runs until the position reaches the uncertainty goal (GpsFix); a second
     *  tap stops it and keeps the best position so far. */
    private void getLocation() {
        if (gps != null) {
            gpsStopped = true;
            gps.stop();
            return;
        }
        final Integer goal = FieldInfo.gpsGoal(MainActivity.text(gpsGoal));
        if (goal == null) { // the note under the goal says why
            gpsGoal.requestFocus();
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Both: Android 12+ then offers "precise" or "approximate"; the GPS needs precise.
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
            return;
        }
        if (!GpsFix.gpsOn(this)) {
            showLocation(getString(R.string.location_off));
            MainActivity.openPage(this, new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        gps = new GpsFix(this, goal, new GpsFix.Listener() {
            @Override
            public void onSearching(Location best, long elapsedMs, int heard, int used) {
                String text = goal > 0
                        ? getString(R.string.location_searching, goal, elapsedMs / 1000, GpsFix.TIMEOUT_MS / 1000)
                        : getString(R.string.location_searching_full, elapsedMs / 1000, GpsFix.TIMEOUT_MS / 1000);
                // From the first second, so the wait has a reason; own lines, as the seconds before
                // them would move them at each tick.
                text += "\n" + (heard <= 0 ? getString(R.string.location_satellites_none)
                        : getString(R.string.location_satellites, heard, Math.max(0, used)));
                text += "\n" + (best == null ? getString(R.string.location_best_none)
                        : getString(R.string.location_best, Math.round(best.getAccuracy())));
                locationText.setText(text);
            }

            @Override
            public void onDone(Location best) {
                locationDone();
                if (best == null) {
                    showLocation(gpsStopped ? getString(R.string.location_cancelled)
                            : getString(R.string.location_failed, GpsFix.TIMEOUT_MS / 1000));
                    return;
                }
                showPosition(best);
                FieldInfo saved = FieldInfo.load(FieldActivity.this);
                saved.location = best;
                saved.gpsGoalM = goal;
                saved.save(FieldActivity.this);
                showLocationSwitch();
            }
        });
        gpsStopped = false;
        gps.start();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // a screen timeout would stop it
        locationButton.setText(R.string.location_stop);
    }

    private void locationDone() {
        gps = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        locationButton.setText(R.string.location_get);
    }

    /** The phone's Location switch beside Get location (also on card 4): it is needed for the GPS,
     *  and then best switched off again. */
    private void showLocationSwitch() {
        boolean on = PhoneCheck.locationOn(this);
        Location l = location;
        String text = getString(!on ? R.string.location_switch_off
                : l != null && FieldInfo.fromGps(l) && gps == null ? R.string.location_switch_on_done
                : R.string.location_switch_on);
        if (!text.contentEquals(locationSwitch.getText())) locationSwitch.setText(text);
    }
}
