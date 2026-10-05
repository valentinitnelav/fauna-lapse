package com.faunalapse.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * One GPS position for the session record. The GPS runs only until a position reaches the user's
 * uncertainty goal (FieldInfo.gpsGoalM), the user stops it, or the time is up; then it is switched
 * off again to save energy. GPS only, no network positions: in the field the phone is usually in
 * airplane mode, and without network help a first position can take a few minutes. While it
 * searches, Android's satellite status tells how many satellites (GPS, Galileo and others) send a
 * signal and how many the position uses. Runs on the main thread.
 */
final class GpsFix implements LocationListener {

    /** Give up after this long and keep the best position so far, if any. */
    static final long TIMEOUT_MS = 180000;

    interface Listener {
        /** Each second while searching; {@code best} is null until the first position; {@code heard}
         *  and {@code used}: satellites with a signal and used for the position, -1 until Android
         *  reports them. */
        void onSearching(Location best, long elapsedMs, int heard, int used);

        /** The search is over; {@code best} is null when no position came. */
        void onDone(Location best);
    }

    private final LocationManager manager;
    private final Listener listener;
    private final int goalM;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long start = SystemClock.elapsedRealtime();
    private Location best;
    private boolean running;
    private int heard = -1;
    private int used = -1;

    private final GnssStatus.Callback satellites = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            int h = 0;
            int u = 0;
            for (int i = 0; i < status.getSatelliteCount(); i++) {
                if (status.getCn0DbHz(i) > 0) h++; // a signal; the list also holds satellites expected only
                if (status.usedInFix(i)) u++;
            }
            heard = h;
            used = u;
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long elapsed = SystemClock.elapsedRealtime() - start;
            if (elapsed >= TIMEOUT_MS) {
                stop();
                return;
            }
            listener.onSearching(best, elapsed, heard, used);
            handler.postDelayed(this, 1000);
        }
    };

    /** {@code goalM}: stop at this uncertainty or less (68 % radius, in m); 0 searches the full time. */
    GpsFix(Context context, int goalM, Listener listener) {
        this.manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.goalM = goalM;
        this.listener = listener;
    }

    /** False when the phone's Location setting is off: the GPS cannot be used then. */
    static boolean gpsOn(Context context) {
        return ((LocationManager) context.getSystemService(Context.LOCATION_SERVICE))
                .isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    /** MissingPermission: the caller asks for the location permission first. */
    @SuppressLint("MissingPermission")
    void start() {
        running = true;
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
        manager.registerGnssStatusCallback(satellites, handler);
        handler.post(tick);
    }

    /** Switches the GPS off and reports the best position so far. */
    void stop() {
        if (!cancel()) return;
        listener.onDone(best);
    }

    /** Switches the GPS off without a report; false when it was already off. */
    boolean cancel() {
        if (!running) return false;
        running = false;
        handler.removeCallbacks(tick);
        manager.removeUpdates(this);
        manager.unregisterGnssStatusCallback(satellites);
        return true;
    }

    @Override
    public void onLocationChanged(Location l) {
        if (!l.hasAccuracy()) return;
        if (best == null || l.getAccuracy() < best.getAccuracy()) {
            best = l;
            // Android's GPS usually adds the satellites it used; else the count from the status.
            Bundle b = l.getExtras() == null ? new Bundle() : new Bundle(l.getExtras());
            if (FieldInfo.satellites(l) == null && used >= 0) b.putInt(FieldInfo.SATELLITES, used);
            b.putInt(FieldInfo.GOAL, goalM); // for the record
            l.setExtras(b);
        }
        if (goalM > 0 && l.getAccuracy() <= goalM) stop();
    }

    // Android 7 to 10 call these too (they have no default there).
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
        stop(); // Location was switched off
    }
}
