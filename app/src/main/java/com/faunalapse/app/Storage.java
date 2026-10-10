package com.faunalapse.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Where sessions are saved: the phone's own storage or a memory card (SD card, also a USB drive),
 * as the user chooses on the status card. Each place has a FaunaLapse folder: Documents/FaunaLapse,
 * so the files survive an uninstall and are easy to find over USB. Where Android does not allow
 * that, the app's own folder on the same storage is used, which Android deletes with the app:
 * on the phone with Android 10 and older without the storage permission, on a card with Android
 * 7 to 10 always (apps may write only their own folder there).
 */
final class Storage {

    /** One storage and its FaunaLapse folder. */
    static final class Place {
        final String key;        // PHONE, or the card's volume name (for example 70DE-1DEC)
        final String name;       // as Android names it: "SD card", "USB drive"; "Phone" for the phone
        final File root;         // the storage, as file manager apps show its top
        final File dir;          // the FaunaLapse folder on it
        final boolean appFolder; // the app's own folder: deleted when the app is uninstalled

        Place(String key, String name, File root, File dir, boolean appFolder) {
            this.key = key;
            this.name = name;
            this.root = root;
            this.dir = dir;
            this.appFolder = appFolder;
        }
    }

    static final String PHONE = "phone";
    private static final String PREFS = "storage";

    private Storage() {
    }

    /** The phone first, then each memory card that is in the phone now. */
    @SuppressWarnings("deprecation") // the shared folder's path: direct paths work again from Android 11
    static List<Place> places(Context context) {
        List<Place> places = new ArrayList<>();
        File phoneRoot = Environment.getExternalStorageDirectory();
        File shared = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "FaunaLapse");
        boolean useShared = canShare(context) && (shared.isDirectory() || shared.mkdirs());
        places.add(new Place(PHONE, context.getString(R.string.storage_phone), phoneRoot,
                useShared ? shared : appDir(context.getExternalFilesDir(null), context), !useShared));
        // The app's folder on each storage; [0] is the phone's, null when a storage is not ready.
        File[] own = context.getExternalFilesDirs(null);
        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        for (int i = 1; i < own.length; i++) {
            if (own[i] == null) continue;
            String path = own[i].getAbsolutePath();
            int cut = path.indexOf("/Android/data/");
            if (cut < 0) continue;
            File root = new File(path.substring(0, cut));
            StorageVolume volume = sm.getStorageVolume(own[i]);
            String name = volume == null ? root.getName() : volume.getDescription(context);
            File cardShared = new File(root, Environment.DIRECTORY_DOCUMENTS + "/FaunaLapse");
            // Android 11+ lets apps make their own files in Documents on a card too.
            boolean cardUseShared = Build.VERSION.SDK_INT >= 30 && (cardShared.isDirectory() || cardShared.mkdirs());
            places.add(new Place(root.getName(), name, root, cardUseShared ? cardShared : appDir(own[i], context),
                    !cardUseShared));
        }
        return places;
    }

    /** True when the app may write in the shared Documents folder. Android 8 to 10 grant only the
     *  permissions asked for, and give write access to shared folders only with read granted too. */
    static boolean canShare(Context context) {
        return Build.VERSION.SDK_INT >= 30
                || context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static File appDir(File own, Context context) {
        return new File(own == null ? context.getFilesDir() : own, "sessions");
    }

    /** The place chosen for new sessions: the saved choice, or the phone when that card is not in
     *  the phone now ({@link #missingChoice} tells). */
    static Place chosen(Context context, List<Place> places) {
        String key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("place", PHONE);
        for (Place p : places) {
            if (p.key.equals(key)) return p;
        }
        return places.get(0);
    }

    /** True when the chosen card is not in the phone now. */
    static boolean missingChoice(Context context, List<Place> places) {
        return !chosen(context, places).key.equals(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("place", PHONE));
    }

    static void choose(Context context, String key) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("place", key).apply();
    }

    /** The folder for new sessions. Lists the storages (it may make folders): not for every second. */
    static File sessionsDir(Context context) {
        return chosen(context, places(context)).dir;
    }

    /** The app's own sessions folder on the phone: no permission needed, deleted with the app. */
    static File appSessionsDir(Context context) {
        return appDir(context.getExternalFilesDir(null), context);
    }

    /** Free bytes where the folder is. Measured on the nearest folder that exists: Android answers 0
     *  for a folder not made yet (the app's own sessions folder before the first session). */
    @SuppressLint("UsableSpace")
    static long free(File dir) {
        File f = dir;
        while (f != null && !f.exists()) f = f.getParentFile();
        return f == null ? 0 : f.getUsableSpace();
    }

    /** A folder as file manager apps show it: "Internal storage/Documents/FaunaLapse",
     *  "SD card/Documents/FaunaLapse". */
    static String shownPath(Context context, List<Place> places, File dir) {
        String path = dir.getAbsolutePath();
        for (Place p : places) {
            String root = p.root.getAbsolutePath() + "/";
            if (path.startsWith(root)) {
                String top = p.key.equals(PHONE) ? context.getString(R.string.storage_phone_top) : p.name;
                return top + "/" + path.substring(root.length());
            }
        }
        return path;
    }

    /** The folder's ID for Android's file app ("primary:Documents/FaunaLapse",
     *  "70DE-1DEC:Documents/FaunaLapse"), or null when it is not on a storage. */
    static String documentId(List<Place> places, File dir) {
        String path = dir.getAbsolutePath();
        for (Place p : places) {
            String root = p.root.getAbsolutePath() + "/";
            if (path.startsWith(root)) {
                return (p.key.equals(PHONE) ? "primary" : p.key) + ":" + path.substring(root.length());
            }
        }
        return null;
    }
}
