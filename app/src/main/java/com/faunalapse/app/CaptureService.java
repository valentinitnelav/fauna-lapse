package com.faunalapse.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Runs one phase 0 test, also with the screen off: one photo (Camera1Backend) or the speed test
 * (SpeedTest).
 *
 * This is a foreground service: background work that Android keeps alive because it shows a
 * permanent notification. Since Android 11 an app may use the camera with the screen off only
 * from a foreground service of type "camera" that was started while the app was on screen.
 * The service also holds a partial wake lock, which keeps the processor running with the
 * screen off.
 */
public final class CaptureService extends Service {

    static final String TAG = "FaunaLapse";
    /** Time from the tap to the camera start: enough to switch the screen off by hand. */
    static final long START_DELAY_MS = 10000;
    /** Intent extra: run the speed test (SpeedTest) instead of taking one photo. */
    static final String EXTRA_SPEED_TEST = "speed_test";
    /** True from the start until the camera is closed again; read by the screen. */
    static volatile boolean running;

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "capture";

    private HandlerThread cameraThread;
    private PowerManager.WakeLock wakeLock;

    /** Folder for photos and the log: the app's own folder, so no storage permission is needed.
     *  Android removes it when the app is uninstalled. */
    static File outputDir(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir(); // no shared storage mounted
        File dir = new File(base, "phase0");
        dir.mkdirs();
        return dir;
    }

    /** One JSON record per line, one line per try. */
    static File logFile(Context context) {
        return new File(outputDir(context), "log.jsonl");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_NOT_STICKY;
        running = true;
        try {
            startInForeground();
        } catch (RuntimeException e) {
            // For example: the camera permission is missing, or Android refused the start.
            JSONObject record = new JSONObject();
            put(record, "result", "error");
            put(record, "error", "startForeground: " + e);
            report(record);
            stopSelf();
            return START_NOT_STICKY;
        }

        final boolean speedTest = intent != null && intent.getBooleanExtra(EXTRA_SPEED_TEST, false);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FaunaLapse:capture");
        // Released earlier; the timeout is a safety net.
        wakeLock.acquire(START_DELAY_MS + (speedTest ? 15 : 1) * 60000L);

        cameraThread = new HandlerThread("camera");
        cameraThread.start();
        final Handler handler = new Handler(cameraThread.getLooper());
        handler.postDelayed(() -> {
            final boolean screenOnAtStart = isScreenOn();
            Camera1Backend.Listener done = record -> {
                put(record, "screen_on_at_camera_start", screenOnAtStart);
                put(record, "screen_on_at_camera_close", isScreenOn());
                report(record);
                new Handler(Looper.getMainLooper()).post(this::stopSelf);
            };
            if (speedTest) {
                File dir = new File(outputDir(this), "speed_"
                        + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()));
                dir.mkdirs();
                new Thread(new SpeedTest(this, dir, done), "speed-test").start();
            } else {
                new Camera1Backend(handler, outputDir(this), done).start();
            }
        }, START_DELAY_MS);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (cameraThread != null) cameraThread.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        running = false;
        super.onDestroy();
    }

    @SuppressWarnings("deprecation") // Notification.Builder(Context) is the only choice before Android 8
    private void startInForeground() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    /** Adds phone facts to the record, appends it to the log file and to the Android log. */
    private void report(JSONObject record) {
        put(record, "phone", Build.MANUFACTURER + " " + Build.MODEL);
        put(record, "android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        put(record, "battery_temp_c", batteryTempC(this));
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            put(record, "charging", battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
        }
        Log.i(TAG, record.toString());
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(logFile(this), true), StandardCharsets.UTF_8)) {
            w.write(record.toString());
            w.write('\n');
        } catch (IOException e) {
            Log.e(TAG, "Cannot write the log file", e);
        }
    }

    /** Battery temperature in °C (the only temperature an app can read), or null if unknown. */
    static Double batteryTempC(Context context) {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        // Android reports tenths of a degree Celsius.
        return battery == null ? null : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0;
    }

    @SuppressWarnings("deprecation") // isScreenOn() is the only choice on Android 4.4
    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return Build.VERSION.SDK_INT >= 20 ? pm.isInteractive() : pm.isScreenOn();
    }

    private static void put(JSONObject record, String key, Object value) {
        try {
            record.put(key, value);
        } catch (JSONException ignored) {
            // Only thrown for NaN or infinite numbers, which we never write.
        }
    }
}
