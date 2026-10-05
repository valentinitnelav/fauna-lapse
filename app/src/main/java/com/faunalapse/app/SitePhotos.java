package com.faunalapse.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
//noinspection ExifInterface: the AndroidX copy is a library (AGENTS.md); this one reads the turn
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Site photos: full-size photos of the surroundings, taken before Start with the phone's own camera
 * app (it has zoom, wide lens and flash; FaunaLapse's camera is made for the square). They wait in
 * the app's folder and move into the session folder, in its own folder site_photos, when a session
 * starts, so they never mix with the time-lapse photos. The camera app writes each photo through
 * {@link Provider}: since Android 7 apps share files only by such content links, not by path.
 */
final class SitePhotos {

    /** The folder inside a session folder. */
    static final String FOLDER = "site_photos";

    private SitePhotos() {
    }

    /** Where photos wait until Start: the app's own folder (no permission needed). */
    static File waitingDir(Context context) {
        File base = context.getExternalFilesDir(null);
        File dir = new File(base == null ? context.getFilesDir() : base, "site_photos_waiting");
        dir.mkdirs();
        return dir;
    }

    /** The waiting photos, oldest first (names are times); empty files (a cancelled photo) go. */
    static List<File> waiting(Context context) {
        File[] files = waitingDir(context).listFiles();
        List<File> list = new ArrayList<>();
        if (files == null) return list;
        Arrays.sort(files);
        for (File f : files) {
            if (f.length() == 0) f.delete();
            else if (f.getName().endsWith(".jpg")) list.add(f);
        }
        return list;
    }

    /** A new file for the next photo, named by the time like the time-lapse photos. */
    static File newFile(Context context) {
        return new File(waitingDir(context), SessionLog.fileStem(System.currentTimeMillis()) + ".jpg");
    }

    /** The content link the camera app writes to. */
    static Uri uri(Context context, File file) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".sitephotos")
                .appendPath(file.getName()).build();
    }

    /** Moves the waiting photos into {@code sessionDir}/site_photos; returns their names. A rename
     *  where it works, else a copy (another storage, such as a memory card). */
    static List<String> moveInto(Context context, File sessionDir) {
        List<String> names = new ArrayList<>();
        List<File> files = waiting(context);
        if (files.isEmpty()) return names;
        File dir = new File(sessionDir, FOLDER);
        dir.mkdirs();
        for (File f : files) {
            File to = new File(dir, f.getName());
            if (f.renameTo(to) || (copy(f, to) && f.delete())) names.add(f.getName());
        }
        return names;
    }

    private static boolean copy(File from, File to) {
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return true;
        } catch (IOException e) {
            to.delete();
            return false;
        }
    }

    /** The photo decoded to about {@code side} px on its short side, turned upright (camera apps
     *  often only note the turn in the photo's EXIF data); null when it cannot be read. */
    static Bitmap picture(File file, int side) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        int shortSide = Math.min(o.outWidth, o.outHeight);
        if (shortSide <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (shortSide / (o.inSampleSize * 2) >= side) o.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeFile(file.getPath(), o);
        if (b == null) return null;
        int degrees = 0;
        try {
            int turn = new ExifInterface(file.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            degrees = turn == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                    : turn == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : turn == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (IOException ignored) {
            // shown as stored
        }
        if (degrees == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    /**
     * Lets the camera app write one waiting photo (content://com.faunalapse.app.sitephotos/<name>).
     * Not exported: a camera app gets access only to the one link FaunaLapse hands it, for that
     * photo. Names are checked, so no other file can be reached.
     */
    public static final class Provider extends ContentProvider {

        @Override
        public boolean onCreate() {
            return true;
        }

        private File file(Uri uri) throws FileNotFoundException {
            String name = uri.getLastPathSegment();
            if (name == null || !name.matches("[0-9_]+\\.jpg")) throw new FileNotFoundException(String.valueOf(uri));
            return new File(waitingDir(getContext()), name);
        }

        @Override
        public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
        }

        /** Name and size: some camera apps ask before they write. */
        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            File f;
            try {
                f = file(uri);
            } catch (FileNotFoundException e) {
                return null;
            }
            MatrixCursor c = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[] {f.getName(), f.length()});
            return c;
        }

        @Override
        public String getType(Uri uri) {
            return "image/jpeg";
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            return 0;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return 0;
        }
    }
}
