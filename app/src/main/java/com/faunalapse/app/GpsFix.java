package com.faunalapse.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * One GPS position for the session record. The GPS runs only until a position is good enough, the
 * user stops it, or the time is up; then it is switched off again to save energy. GPS only, no
 * network positions: in the field the phone is usually in airplane mode, and without network help
 * a first position can take a few minutes. Runs on the main thread.
 */
final class GpsFix implements LocationListener {

    /** Good enough: within about 10 m (owner); 68 % of positions lie within the stated accuracy. */
    static final float GOOD_M = 10;
    /** Give up after this long and keep the best position so far, if any. */
    static final long TIMEOUT_MS = 180000;

    interface Listener {
        /** Each second while searching; {@code best} is null until the first position. */
        void onSearching(Location best, long elapsedMs);

        /** The search is over; {@code best} is null when no position came. */
        void onDone(Location best);
    }

    private final LocationManager manager;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long start = SystemClock.elapsedRealtime();
    private Location best;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long elapsed = SystemClock.elapsedRealtime() - start;
            if (elapsed >= TIMEOUT_MS) {
                stop();
                return;
            }
            listener.onSearching(best, elapsed);
            handler.postDelayed(this, 1000);
        }
    };

    GpsFix(Context context, Listener listener) {
        this.manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
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
        return true;
    }

    @Override
    public void onLocationChanged(Location l) {
        if (!l.hasAccuracy()) return;
        if (best == null || l.getAccuracy() < best.getAccuracy()) best = l;
        if (l.getAccuracy() <= GOOD_M) stop();
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
