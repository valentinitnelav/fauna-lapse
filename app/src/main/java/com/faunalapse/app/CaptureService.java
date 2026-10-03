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
 * Runs a session on its schedule (SessionRunner), or one phase 0 test (one photo with
 * Camera1Backend, or SpeedTest), also with the screen off.
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
    /** Intent extra: run a session with the saved settings (SessionSettings). */
    static final String EXTRA_SESSION = "session";
    /** Intent extra: how the phone is held at Start (RoiMath.quarterTurn), for upright squares. */
    static final String EXTRA_PHONE_TURN = "phone_turn";
    /** Intent extra: stop the running session. */
    static final String EXTRA_STOP = "stop";
    /** True from the start until the camera is closed again; read by the screen. */
    static volatile boolean running;
    /** True while a session (not a test) runs. */
    static volatile boolean sessionRunning;

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "capture";

    private HandlerThread cameraThread;
    private Thread sessionThread;
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

    /** Sessions: one folder per session (named by its start time) with the photos or videos and
     *  session.jsonl. The app's own folder for now; DCIM comes later (AGENT_OVERVIEW.md). */
    static File sessionsDir(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        return new File(base, "sessions");
    }

    /** One JSON record per line, one line per test or session (the screen shows the last one). */
    static File logFile(Context context) {
        return new File(outputDir(context), "log.jsonl");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(EXTRA_STOP, false)) {
            if (sessionThread != null) sessionThread.interrupt(); // the session closes the camera, then stops us
            else if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        running = true;
        final boolean session = intent != null && intent.getBooleanExtra(EXTRA_SESSION, false);
        try {
            startInForeground(session);
        } catch (RuntimeException e) {
            // For example: the camera permission is missing, or Android refused the start.
            JSONObject record = new JSONObject();
            put(record, "result", "error");
            put(record, "error", "startForeground: " + e);
            report(record);
            stopSelf();
            return START_NOT_STICKY;
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FaunaLapse:capture");
        if (session) {
            startSession(intent.getIntExtra(EXTRA_PHONE_TURN, 0));
            return START_NOT_STICKY;
        }
        final boolean speedTest = intent != null && intent.getBooleanExtra(EXTRA_SPEED_TEST, false);
        // Released earlier; the timeout is a safety net.
        wakeLock.acquire(START_DELAY_MS + (speedTest ? 15 : 1) * 60000L);

        cameraThread = new HandlerThread("camera");
        cameraThread.start();
        final Handler handler = new Handler(cameraThread.getLooper());
        handler.postDelayed(() -> {
            final boolean screenOnAtStart = isScreenOn(this);
            Camera1Backend.Listener done = record -> {
                put(record, "screen_on_at_camera_start", screenOnAtStart);
                put(record, "screen_on_at_camera_close", isScreenOn(this));
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

    /** Starts the session thread with the settings the screen saved just before. */
    private void startSession(int phoneTurn) {
        SessionSettings settings = SessionSettings.load(this);
        long now = System.currentTimeMillis();
        CapturePlan plan;
        try {
            if (settings.problem() != null) throw new IllegalArgumentException(settings.problem());
            // The grid starts one wake lead after the tap, so the first burst also gets its warm-up.
            plan = settings.plan(now + SessionRunner.WAKE_LEAD_MS);
        } catch (IllegalArgumentException e) {
            JSONObject record = new JSONObject();
            put(record, "test", "session");
            put(record, "result", "error");
            put(record, "error", e.getMessage());
            report(record);
            stopSelf();
            return;
        }
        // Held for the whole session (the phone stays awake between bursts); released when it ends.
        wakeLock.acquire(plan.end() - now + 10 * 60000L);
        File dir = new File(sessionsDir(this), new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(now)));
        dir.mkdirs();
        sessionThread = new Thread(new SessionRunner(this, settings, plan, dir, phoneTurn, summary -> {
            report(summary);
            new Handler(Looper.getMainLooper()).post(this::stopSelf);
        }), "session");
        sessionRunning = true;
        sessionThread.start();
    }

    @Override
    public void onDestroy() {
        if (sessionThread != null) sessionThread.interrupt();
        if (cameraThread != null) cameraThread.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        running = false;
        sessionRunning = false;
        super.onDestroy();
    }

    @SuppressWarnings("deprecation") // Notification.Builder(Context) is the only choice before Android 8
    private void startInForeground(boolean session) {
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
                .setContentTitle(getString(session ? R.string.notification_session_title : R.string.notification_title))
                .setContentText(getString(session ? R.string.notification_session_text : R.string.notification_text))
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
        Battery battery = Battery.read(this);
        put(record, "battery_temp_c", battery.tempC);
        put(record, "charging", battery.charging);
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
        return Battery.read(context).tempC;
    }

    /** The phone's last battery report. Android keeps it as a "sticky" broadcast, so reading it
     *  costs almost nothing. */
    static final class Battery {
        final Double tempC;    // null if unknown
        final int percent;     // -1 if unknown
        final boolean charging;

        private Battery(Double tempC, int percent, boolean charging) {
            this.tempC = tempC;
            this.percent = percent;
            this.charging = charging;
        }

        static Battery read(Context context) {
            Intent b = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return new Battery(null, -1, false);
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            return new Battery(b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0, // tenths of a °C
                    level >= 0 && scale > 0 ? level * 100 / scale : -1,
                    b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
        }
    }

    static boolean isScreenOn(Context context) {
        return ((PowerManager) context.getSystemService(POWER_SERVICE)).isInteractive();
    }

    private static void put(JSONObject record, String key, Object value) {
        try {
            record.put(key, value);
        } catch (JSONException ignored) {
            // Only thrown for NaN or infinite numbers, which we never write.
        }
    }
}
