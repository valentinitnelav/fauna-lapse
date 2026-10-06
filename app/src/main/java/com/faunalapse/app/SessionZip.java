package com.faunalapse.app;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Packs finished photo sessions into zip files, so a computer copies one file instead of thousands
 * of photos (over USB "File transfer" each file costs time: the zip of 301 small photos copied 9
 * times faster than their folder, Samsung M12; docs/PHONES.md). Video sessions stay as they are:
 * their few large clips copy fast. The photos are only stored, not compressed (JPEG would shrink by 1 to 3 %):
 * fast and light on the battery. Each zip goes next to its session folder, holds the folder
 * itself (extracting gives the folder back), is written to the storage, read back and checked
 * (size and CRC of every file), and only then are its files deleted from the folder. A large
 * session gets more zips ("_part2" and on), each one complete. A run that stopped goes on where
 * it ended. A session whose record names files the app cannot see is left as it is. Pure Java,
 * tested on the PC; MainActivity runs it on a second thread.
 */
final class SessionZip {

    /** At most this many bytes per zip: below 4 GiB, so a zip fits on a FAT32 memory card and needs
     *  no Zip64 (some programs cannot read Zip64). */
    static final long PART_BYTES = 4_000_000_000L;
    /** At most this many files per zip: a zip without Zip64 holds at most 65535. */
    static final int PART_FILES = 65000;
    /** With less free space than one whole zip, smaller zips, but not below this (else many). */
    static final long MIN_PART_BYTES = 500L * 1024 * 1024;
    /** Zip headers per file, generously: two headers of 30 and 46 bytes, each with the name. */
    private static final int HEADER_BYTES = 256;
    private static final int BUFFER = 1 << 20;
    /** On Android 11 and newer each file open goes through the file index and costs some ms, but
     *  several opens at a time are faster: photos (up to 4 MB) are read whole on 4 threads, up to 8
     *  files ahead of the zip writer (3.9 times faster on the Samsung M12). */
    private static final int THREADS = 4;
    private static final int READ_AHEAD = 8;
    private static final int READ_WHOLE_BYTES = 4 << 20;
    private static final String RECORD = "session.jsonl";
    private static final Pattern FILE = Pattern.compile("\"file\":\"([^\"]+)\"");
    private static final Pattern SITE_PHOTOS = Pattern.compile("\"site_photos\":\\[([^\\]]*)\\]");

    /** The run going on, or the last one (null before the first). */
    static volatile SessionZip last;

    /** Deletes many files in one go; it may leave some, which are then deleted one by one. On
     *  Android 11 and newer one delete through the storage takes about 16 ms (Samsung M12, Redmi
     *  Note 9S); in one call to the file index 14 and 6 ms per file. */
    interface BulkDelete {
        void delete(List<File> files);
    }

    /** Set by MainActivity on Android 11 and newer; null: one by one. */
    BulkDelete bulkDelete;

    // Limits, changed only by the tests.
    long partBytes = PART_BYTES;
    long minPartBytes = MIN_PART_BYTES;
    long reserveBytes = Guards.RESERVE_BYTES; // free space left for the phone, as in a session

    // Progress, read by the screen.
    final List<File> folders;
    volatile int current;     // the folder being packed
    volatile long totalBytes; // of all files to pack; each byte counts twice: written, then checked
    volatile long doneBytes;
    volatile boolean stop;    // asked on the screen: the run ends after the file in hand
    volatile boolean finished;

    // Outcome, read after finished.
    int packed;          // sessions packed completely
    int notDeleted;      // files in a checked zip that could not be deleted
    boolean stopped;     // the run ended on the user's stop
    File failedFolder;   // the session where the run ended early (no space or an error), or null
    String failure;      // the error there, or null
    long neededBytes;    // no space: how much more free space that session needs
    final List<String> hidden = new ArrayList<>(); // sessions left as they are: files hidden (Hidden)
    int hiddenFiles;     // in those sessions
    long writeMs, checkMs, deleteMs; // time spent on each step, for the log
    /** Zips made, and files and folders deleted: Android 10 and older must update their file index. */
    final List<String> changed = new ArrayList<>();

    private final byte[] buf = new byte[BUFFER];
    private ExecutorService pool;

    SessionZip(List<File> folders) {
        this.folders = folders;
    }

    /** True while a run packs. */
    static boolean running() {
        SessionZip run = last;
        return run != null && !run.finished;
    }

    /** The photo session folders in these FaunaLapse folders, in name order (phone ID, then time). */
    static List<File> photoSessions(List<File> dirs) {
        List<File> out = new ArrayList<>();
        for (File d : dirs) {
            File[] inside = d.listFiles(SessionZip::isPhotoSession);
            if (inside == null) continue;
            Arrays.sort(inside);
            out.addAll(Arrays.asList(inside));
        }
        return out;
    }

    static boolean isPhotoSession(File f) {
        return f.isDirectory() && f.getName().endsWith("_photo");
    }

    /** The session of a folder or zip in a FaunaLapse folder: X for X, X.zip and X_part2.zip. */
    static String sessionName(File f) {
        String name = f.getName();
        if (f.isDirectory() || !name.endsWith(".zip")) return name;
        return name.substring(0, name.length() - 4).replaceFirst("_part\\d+$", "");
    }

    /** Bytes of a file, or of all files in a folder. */
    static long bytes(File f) {
        File[] inside = f.listFiles();
        if (inside == null) return f.length();
        long sum = 0;
        for (File g : inside) sum += bytes(g);
        return sum;
    }

    /** Zip number n (from 1) of a session. */
    static File part(File parent, String session, int n) {
        return new File(parent, session + (n == 1 ? "" : "_part" + n) + ".zip");
    }

    int percent() {
        return (int) (doneBytes * 100 / Math.max(1, 2 * totalBytes));
    }

    /** Packs the folders in turn; ends at the first problem or on stop, the rest stays as it is. */
    void packAll() {
        pool = Executors.newFixedThreadPool(THREADS);
        try {
            long total = 0;
            for (File f : folders) total += bytes(f);
            totalBytes = total;
            for (int i = 0; i < folders.size(); i++) {
                current = i;
                try {
                    pack(folders.get(i));
                    packed++;
                } catch (Hidden e) {
                    hidden.add(folders.get(i).getName());
                    hiddenFiles += e.files;
                } catch (Stopped e) {
                    stopped = true;
                    return;
                } catch (IOException e) {
                    failedFolder = folders.get(i);
                    if (e instanceof NoSpace) neededBytes = ((NoSpace) e).needed;
                    else failure = e.getMessage();
                    return;
                }
            }
        } finally {
            pool.shutdownNow();
            finished = true;
        }
    }

    /** Packs one session folder into zips next to it, and deletes the folder. */
    private void pack(File folder) throws IOException {
        File parent = folder.getParentFile();
        String name = folder.getName();
        // Zips made before: a run that ended between a check and the deletes left their files.
        Map<String, Long> inZips = new HashMap<>();
        int n = 1;
        for (; part(parent, name, n).isFile(); n++) {
            try (ZipFile zip = new ZipFile(part(parent, name, n))) {
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    ZipEntry entry = e.nextElement();
                    inZips.put(entry.getName(), entry.getSize());
                }
            }
        }
        List<File> files = new ArrayList<>();
        list(folder, files);
        if (files.isEmpty() && folder.delete()) { // Android deletes only an empty folder: nothing hidden
            changed.add(folder.getPath());
            return;
        }
        checkRecord(folder, files, inZips.keySet());
        List<File> left = new ArrayList<>();
        List<File> packedBefore = new ArrayList<>();
        for (File f : files) {
            Long size = inZips.get(entryName(folder, f));
            if (size != null && size == f.length()) {
                doneBytes += 2 * f.length();
                packedBefore.add(f);
            } else {
                left.add(f);
            }
        }
        deleteAll(packedBefore);
        long leftBytes = 0;
        for (File f : left) leftBytes += f.length() + HEADER_BYTES;
        for (int i = 0; i < left.size(); n++) {
            // As many files as fit in one zip and in the free space (usable space leaves out caches
            // Android could clear: a cautious number, as in a session).
            //noinspection UsableSpace
            long room = parent.getUsableSpace() - reserveBytes;
            long smallest = Math.min(leftBytes, minPartBytes);
            if (room < smallest) throw new NoSpace(smallest - room);
            long limit = Math.min(partBytes, room);
            int end = i;
            long bytes = 0;
            while (end < left.size() && end - i < PART_FILES) {
                long next = left.get(end).length() + HEADER_BYTES;
                if (end > i && bytes + next > limit) break;
                bytes += next;
                end++;
            }
            List<File> chunk = left.subList(i, end);
            File zip = part(parent, name, n);
            File tmp = new File(parent, zip.getName() + ".tmp");
            try {
                long t0 = System.nanoTime();
                long[] crcs = write(tmp, folder, chunk);
                long t1 = System.nanoTime();
                check(tmp, folder, chunk, crcs);
                writeMs += (t1 - t0) / 1000000;
                checkMs += (System.nanoTime() - t1) / 1000000;
                if (!tmp.renameTo(zip)) throw new IOException("cannot rename " + tmp.getName());
            } finally {
                tmp.delete(); // after a stop or an error; gone already after the rename
            }
            changed.add(zip.getPath());
            long t2 = System.nanoTime();
            deleteAll(chunk);
            deleteMs += (System.nanoTime() - t2) / 1000000;
            leftBytes -= bytes;
            i = end;
        }
        deleteEmptyFolders(folder);
    }

    /** Android 11 and newer show an app only the files that their file index lists as the app's
     *  own; when the index lost some (the Redmi Note 9S lost 120 photos of a session), the folder
     *  looks complete but is not. The record names every saved photo and site photo: throws Hidden
     *  unless all of them are in the folder or in a zip made before. */
    private static void checkRecord(File folder, List<File> files, Set<String> inZips) throws IOException {
        String prefix = folder.getName() + "/";
        List<String> named = null;
        File record = new File(folder, RECORD);
        if (record.isFile()) {
            try (InputStream in = new FileInputStream(record)) {
                named = recordedFiles(in);
            }
        }
        // Or in a zip made before.
        for (int n = 1; named == null && part(folder.getParentFile(), folder.getName(), n).isFile(); n++) {
            try (ZipFile zip = new ZipFile(part(folder.getParentFile(), folder.getName(), n))) {
                ZipEntry entry = zip.getEntry(prefix + RECORD);
                if (entry == null) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    named = recordedFiles(in);
                }
            }
        }
        if (named == null) throw new Hidden(1); // every session has its record
        Set<String> seen = new HashSet<>(inZips);
        for (File f : files) seen.add(entryName(folder, f));
        int missing = 0;
        for (String f : named) if (!seen.contains(prefix + f)) missing++;
        if (missing > 0) throw new Hidden(missing);
    }

    /** The files a record names: each saved photo or clip (not those with a save error), and the
     *  site photos (in their folder). */
    static List<String> recordedFiles(InputStream record) throws IOException {
        List<String> out = new ArrayList<>();
        BufferedReader lines = new BufferedReader(new InputStreamReader(record, StandardCharsets.UTF_8));
        for (String line; (line = lines.readLine()) != null; ) {
            Matcher m = SITE_PHOTOS.matcher(line);
            if (m.find()) {
                for (String f : m.group(1).split(",")) {
                    f = f.trim().replace("\"", "");
                    if (!f.isEmpty()) out.add(SitePhotos.FOLDER + "/" + f);
                }
            }
            if ((line.contains("\"event\":\"photo\"") || line.contains("\"event\":\"clip\""))
                    && !line.contains("\"error\":")) {
                m = FILE.matcher(line);
                if (m.find()) out.add(m.group(1));
            }
        }
        return out;
    }

    /** Writes the files into a new zip, stored (not compressed), and onto the storage; returns
     *  their CRCs. A stored file needs its CRC before its data: photos are read whole on the pool,
     *  ahead of the writer; a larger file is read twice here (CRC, then copy). */
    private long[] write(File tmp, File folder, List<File> files) throws IOException {
        long[] crcs = new long[files.size()];
        Deque<Future<byte[]>> ahead = new ArrayDeque<>();
        int next = 0;
        FileOutputStream out = new FileOutputStream(tmp);
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out, BUFFER))) {
            zip.setMethod(ZipOutputStream.STORED);
            for (int k = 0; k < files.size(); k++) {
                if (stop) throw new Stopped();
                for (; next < files.size() && next <= k + READ_AHEAD; next++) {
                    File f = files.get(next);
                    ahead.add(pool.submit(() -> readWhole(f)));
                }
                byte[] data = result(ahead.remove());
                File f = files.get(k);
                long size = data == null ? f.length() : data.length;
                CRC32 crc = new CRC32();
                if (data == null) {
                    try (InputStream in = new FileInputStream(f)) {
                        for (int r; (r = in.read(buf)) > 0; ) crc.update(buf, 0, r);
                    }
                } else {
                    crc.update(data, 0, data.length);
                }
                crcs[k] = crc.getValue();
                ZipEntry entry = new ZipEntry(entryName(folder, f));
                entry.setTime(f.lastModified());
                entry.setSize(size);
                entry.setCompressedSize(size);
                entry.setCrc(crcs[k]);
                zip.putNextEntry(entry);
                if (data == null) {
                    try (InputStream in = new FileInputStream(f)) {
                        for (int r; (r = in.read(buf)) > 0; ) zip.write(buf, 0, r);
                    }
                } else {
                    zip.write(data);
                }
                doneBytes += size;
                zip.closeEntry(); // fails if the size or CRC differ from what was copied
            }
            zip.finish();
            zip.flush();
            out.getFD().sync(); // on the storage itself before any original is deleted
        }
        return crcs;
    }

    /** Reads the zip back: it holds exactly these files, each with its size and CRC. */
    private void check(File tmp, File folder, List<File> files, long[] crcs) throws IOException {
        try (ZipFile zip = new ZipFile(tmp)) {
            if (zip.size() != files.size()) throw new IOException("check: " + zip.size() + " files in the zip");
            for (int k = 0; k < files.size(); k++) {
                if (stop) throw new Stopped();
                String name = entryName(folder, files.get(k));
                ZipEntry entry = zip.getEntry(name);
                if (entry == null) throw new IOException("check: " + name + " missing");
                CRC32 crc = new CRC32();
                long size = 0;
                try (InputStream in = zip.getInputStream(entry)) {
                    for (int r; (r = in.read(buf)) > 0; ) {
                        crc.update(buf, 0, r);
                        size += r;
                        doneBytes += r;
                    }
                }
                if (size != files.get(k).length() || crc.getValue() != crcs[k]) {
                    throw new IOException("check: " + name + " differs");
                }
            }
        }
    }

    /** The whole file, or null when it is larger than READ_WHOLE_BYTES. */
    private static byte[] readWhole(File f) throws IOException {
        long size = f.length();
        if (size > READ_WHOLE_BYTES) return null;
        byte[] data = new byte[(int) size];
        try (InputStream in = new FileInputStream(f)) {
            int done = 0;
            for (int r; done < data.length && (r = in.read(data, done, data.length - done)) > 0; ) done += r;
            if (done < data.length) throw new IOException(f.getName() + " is shorter than listed");
        }
        return data;
    }

    private static <T> T result(Future<T> work) throws IOException {
        try {
            return work.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException ? (IOException) e.getCause() : new IOException(e.getCause());
        }
    }

    /** "folder/file" and "folder/site_photos/file": extracting gives the session folder. */
    private static String entryName(File folder, File f) {
        return folder.getName() + "/"
                + f.getPath().substring(folder.getPath().length() + 1).replace(File.separatorChar, '/');
    }

    /** The files in a folder and its subfolders, in name order. */
    private static void list(File dir, List<File> out) {
        File[] inside = dir.listFiles();
        if (inside == null) return;
        Arrays.sort(inside);
        for (File f : inside) {
            if (f.isDirectory()) list(f, out);
            else out.add(f);
        }
    }

    /** Deletes the files: all at once where it can (bulkDelete), then what is left one by one. */
    private void deleteAll(List<File> files) {
        if (files.isEmpty()) return;
        Set<String> left = null; // null: all may be left
        if (bulkDelete != null) {
            try {
                bulkDelete.delete(files);
            } catch (RuntimeException e) {
                // one by one below
            }
            left = new HashSet<>();
            Set<File> dirs = new HashSet<>();
            for (File f : files) dirs.add(f.getParentFile());
            for (File dir : dirs) {
                String[] names = dir.list();
                if (names != null) for (String n : names) left.add(new File(dir, n).getPath());
            }
        }
        for (File f : files) {
            if ((left != null && !left.contains(f.getPath())) || f.delete()) changed.add(f.getPath());
            else notDeleted++;
        }
    }

    private void deleteEmptyFolders(File dir) {
        File[] inside = dir.listFiles();
        if (inside != null) {
            for (File f : inside) if (f.isDirectory()) deleteEmptyFolders(f);
        }
        if (dir.delete()) changed.add(dir.getPath()); // only when empty
    }

    /** The record names files that are not in the folder: Android hides them, or they are lost. */
    private static final class Hidden extends IOException {
        final int files;

        Hidden(int files) {
            this.files = files;
        }
    }

    /** The user stopped the run. */
    private static final class Stopped extends IOException {
    }

    /** Too little free space for the smallest zip allowed. */
    private static final class NoSpace extends IOException {
        final long needed;

        NoSpace(long needed) {
            this.needed = needed;
        }
    }
}
