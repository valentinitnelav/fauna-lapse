package com.faunalapse.app;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.util.List;

/**
 * Packs the photo sessions (SessionZip) in a foreground service: with its notification, Android
 * keeps FaunaLapse running when the user leaves it. Without one, the Redmi Note 9S closed the app
 * 20 s after Home to free memory, as writing GBs fills the memory cache (docs/PHONES.md). A wake
 * lock keeps it going with the screen off. Android's type for such local file work is "data sync".
 */
public final class PackService extends Service {

    private static final int NOTIFICATION_ID = 2;

    private SessionZip started; // the run a thread packs, or packed

    /** Packs the photo sessions in these FaunaLapse folders; SessionZip.last shows the progress. */
    static void start(Context context, List<File> dirs) {
        SessionZip run = new SessionZip(SessionZip.photoSessions(dirs));
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 30) run.bulkDelete = files -> deleteFromIndex(app, files);
        SessionZip.last = run;
        Intent intent = new Intent(context, PackService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = CaptureService.notification(this,
                getString(R.string.notification_pack_title), getString(R.string.notification_pack_text));
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException e) {
            // Android refused: packing still runs, but only safely while FaunaLapse stays open.
            Log.w(CaptureService.TAG, "pack: no foreground service: " + e);
        }
        final SessionZip run = SessionZip.last;
        if (run == null || run.finished || run == started) {
            if (run == null || run.finished) stopSelf(startId); // else packing already
            return START_NOT_STICKY;
        }
        started = run;
        new Thread(() -> {
            PowerManager.WakeLock lock = ((PowerManager) getSystemService(POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FaunaLapse:pack");
            lock.acquire(4 * 3600000L); // a safety net: released at the end
            long start = SystemClock.elapsedRealtime();
            run.packAll();
            Log.i(CaptureService.TAG, "pack: " + run.packed + " of " + run.folders.size() + " sessions, "
                    + run.totalBytes / 1000000 + " MB in " + (SystemClock.elapsedRealtime() - start) + " ms (write "
                    + run.writeMs + ", check " + run.checkMs + ", delete " + run.deleteMs + ")"
                    + (run.hidden.isEmpty() ? "" : ", hidden files: " + run.hidden + " " + run.hiddenFiles)
                    + (run.stopped ? ", stopped" : "") + (run.failedFolder == null ? ""
                    : ", failed at " + run.failedFolder.getName() + ": " + run.failure + " " + run.neededBytes)
                    + (run.notDeleted > 0 ? ", not deleted: " + run.notDeleted : ""));
            if (Build.VERSION.SDK_INT <= 29 && !run.changed.isEmpty()) {
                // Android 10 and older update their file index (which a computer sees over USB) only
                // when asked, as in SessionRunner.
                MediaScannerConnection.scanFile(this, run.changed.toArray(new String[0]), null, null);
            }
            if (lock.isHeld()) lock.release();
            // Only when no newer start came meanwhile (a new run after this one).
            new Handler(Looper.getMainLooper()).post(() -> stopSelf(startId));
        }, "pack").start();
        return START_NOT_STICKY; // after Android ended the app, the user taps Pack again
    }

    /** Android 15 and newer end a data sync service after 6 h in a day: packing stops after the
     *  file in hand; the rest stays as it was, and Pack goes on later. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        SessionZip run = SessionZip.last;
        if (run != null) run.stop = true;
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Deletes the files through Android's file index, which deletes them from the storage too: one
     *  call per 500 files (SessionZip.BulkDelete). Only the app's own files match. DATA: the path,
     *  deprecated for reading files, still the column to find them by. */
    @SuppressWarnings("deprecation")
    private static void deleteFromIndex(Context app, List<File> files) {
        for (int i = 0; i < files.size(); i += 500) {
            List<File> batch = files.subList(i, Math.min(files.size(), i + 500));
            String[] paths = new String[batch.size()];
            StringBuilder where = new StringBuilder(MediaStore.MediaColumns.DATA + " IN (");
            for (int k = 0; k < paths.length; k++) {
                paths[k] = batch.get(k).getPath();
                where.append(k == 0 ? "?" : ",?");
            }
            app.getContentResolver().delete(MediaStore.Files.getContentUri("external"), where + ")", paths);
        }
    }
}
