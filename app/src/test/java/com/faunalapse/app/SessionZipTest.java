package com.faunalapse.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class SessionZipTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String NAME = "1DFFC454_20261005_101010_photo";

    /** The record of a session with three photos, one that failed, and a site photo. */
    private static final String RECORD = "{\"event\":\"session_start\",\"field\":{\"site\":\"Meadow\","
            + "\"site_photos\":[\"site_1.jpg\"],\"custom\":{}}}\n"
            + "{\"event\":\"photo\",\"time\":\"2026-10-05T10:10:10.000+0200\",\"file\":\"20261005_101010_000.jpg\",\"kb\":2}\n"
            + "{\"event\":\"photo\",\"time\":\"2026-10-05T10:10:12.000+0200\",\"file\":\"20261005_101012_000.jpg\",\"kb\":2}\n"
            + "{\"event\":\"photo\",\"time\":\"2026-10-05T10:10:14.000+0200\",\"file\":\"20261005_101014_000.jpg\",\"kb\":2}\n"
            + "{\"event\":\"photo\",\"time\":\"2026-10-05T10:10:16.000+0200\",\"file\":\"20261005_101016_000.jpg\","
            + "\"error\":\"java.io.IOException\"}\n"
            + "{\"event\":\"session_end\",\"photos\":3}\n";

    /** A session folder with its record, three photos and a site photo. */
    private File session(File parent, String name) throws IOException {
        File dir = new File(parent, name);
        assertTrue(new File(dir, "site_photos").mkdirs());
        Random random = new Random(name.hashCode());
        for (String f : new String[] {"20261005_101010_000.jpg", "20261005_101012_000.jpg",
                "20261005_101014_000.jpg", "site_photos/site_1.jpg"}) {
            byte[] data = new byte[1000 + random.nextInt(3000)];
            random.nextBytes(data);
            Files.write(new File(dir, f).toPath(), data);
        }
        Files.write(new File(dir, "session.jsonl").toPath(), RECORD.getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    private static SessionZip zipper(File... folders) {
        SessionZip z = new SessionZip(Arrays.asList(folders));
        z.reserveBytes = 0;
        return z;
    }

    /** Entry name and bytes of every file in the zips, sorted. */
    private static List<String> zipContents(File... zips) throws IOException {
        List<String> out = new ArrayList<>();
        for (File z : zips) {
            try (ZipFile zip = new ZipFile(z)) {
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    ZipEntry entry = e.nextElement();
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (InputStream in = zip.getInputStream(entry)) {
                        byte[] buf = new byte[4096];
                        for (int r; (r = in.read(buf)) > 0; ) bytes.write(buf, 0, r);
                    }
                    out.add(entry.getName() + " " + Arrays.hashCode(bytes.toByteArray()));
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    /** The same list for the files of a folder, as they would be named in its zip. */
    private static List<String> folderContents(File folder) throws IOException {
        List<String> out = new ArrayList<>();
        addFiles(folder, folder.getName(), out);
        Collections.sort(out);
        return out;
    }

    private static void addFiles(File dir, String name, List<String> out) throws IOException {
        for (File f : dir.listFiles()) {
            if (f.isDirectory()) addFiles(f, name + "/" + f.getName(), out);
            else out.add(name + "/" + f.getName() + " " + Arrays.hashCode(Files.readAllBytes(f.toPath())));
        }
    }

    @Test
    public void packsTheFolderIntoOneZipAndDeletesIt() throws IOException {
        File dir = session(tmp.getRoot(), NAME);
        List<String> before = folderContents(dir);
        SessionZip z = zipper(dir);
        z.packAll();
        assertTrue(z.finished);
        assertEquals(1, z.packed);
        assertNull(z.failedFolder);
        assertFalse(dir.exists());
        File zip = new File(tmp.getRoot(), NAME + ".zip");
        assertEquals(before, zipContents(zip));
        try (ZipFile z2 = new ZipFile(zip)) {
            assertEquals(ZipEntry.STORED, z2.entries().nextElement().getMethod()); // not compressed
        }
        assertArrayEquals(new String[] {NAME + ".zip"}, tmp.getRoot().list());
        assertEquals(100, z.percent());
        assertTrue(z.changed.contains(zip.getPath()));
        assertTrue(z.changed.contains(dir.getPath()));
    }

    @Test
    public void filesLeftByTheBulkDeleteAreDeletedOneByOne() throws IOException {
        File dir = session(tmp.getRoot(), NAME);
        List<String> before = folderContents(dir);
        SessionZip z = zipper(dir);
        List<File> asked = new ArrayList<>();
        z.bulkDelete = files -> { // deletes every second file, as an index that misses some
            asked.addAll(files);
            for (int i = 0; i < files.size(); i += 2) assertTrue(files.get(i).delete());
        };
        z.packAll();
        assertEquals(5, asked.size());
        assertFalse(dir.exists());
        assertEquals(0, z.notDeleted);
        for (File f : asked) assertTrue(z.changed.contains(f.getPath()));
        assertEquals(before, zipContents(new File(tmp.getRoot(), NAME + ".zip")));

        File next = session(tmp.getRoot(), "1DFFC454_20261006_101010_photo");
        z = zipper(next);
        z.bulkDelete = files -> {
            throw new IllegalArgumentException("no index");
        };
        z.packAll();
        assertEquals(1, z.packed);
        assertFalse(next.exists());
    }

    @Test
    public void largeSessionsGetMoreZips() throws IOException {
        File dir = session(tmp.getRoot(), NAME);
        List<String> before = folderContents(dir);
        SessionZip z = zipper(dir);
        z.partBytes = 5000; // about one photo per zip
        z.minPartBytes = 0;
        z.packAll();
        assertEquals(1, z.packed);
        assertFalse(dir.exists());
        List<File> zips = new ArrayList<>();
        for (int n = 1; SessionZip.part(tmp.getRoot(), NAME, n).isFile(); n++) zips.add(SessionZip.part(tmp.getRoot(), NAME, n));
        assertTrue(zips.size() >= 3);
        assertEquals(new File(tmp.getRoot(), NAME + "_part2.zip"), zips.get(1));
        assertEquals(before, zipContents(zips.toArray(new File[0])));
    }

    @Test
    public void goesOnAfterARunThatEndedBeforeTheDeletes() throws IOException {
        File dir = session(tmp.getRoot(), NAME);
        List<String> before = folderContents(dir);
        // A first zip with two of the files, which are still in the folder.
        File first = SessionZip.part(tmp.getRoot(), NAME, 1);
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(first))) {
            for (String f : new String[] {"20261005_101010_000.jpg", "session.jsonl"}) {
                out.putNextEntry(new ZipEntry(NAME + "/" + f));
                out.write(Files.readAllBytes(new File(dir, f).toPath()));
                out.closeEntry();
            }
        }
        SessionZip z = zipper(dir);
        z.packAll();
        assertEquals(1, z.packed);
        assertFalse(dir.exists());
        File second = SessionZip.part(tmp.getRoot(), NAME, 2);
        assertEquals(3, zipContents(second).size()); // only the files not in the first zip
        assertEquals(before, zipContents(first, second));
    }

    @Test
    public void aStopKeepsTheFolderAsItWas() throws IOException {
        File dir = session(tmp.getRoot(), NAME);
        List<String> before = folderContents(dir);
        SessionZip z = zipper(dir);
        z.stop = true;
        z.packAll();
        assertTrue(z.stopped);
        assertEquals(0, z.packed);
        assertEquals(before, folderContents(dir));
        assertArrayEquals(new String[] {NAME}, tmp.getRoot().list());
    }

    @Test
    public void tooLittleSpaceKeepsTheFolderAndSaysHowMuchMore() throws IOException {
        File dir = session(tmp.getRoot(), NAME);
        List<String> before = folderContents(dir);
        SessionZip z = zipper(dir);
        z.reserveBytes = tmp.getRoot().getUsableSpace() + (1L << 30); // nothing left for the zip
        z.packAll();
        assertEquals(dir, z.failedFolder);
        assertTrue(z.neededBytes > 0);
        assertEquals(before, folderContents(dir));
        assertArrayEquals(new String[] {NAME}, tmp.getRoot().list());
    }

    @Test
    public void aSessionWithHiddenFilesStaysAsItIsAndTheNextIsPacked() throws IOException {
        File hidden = session(tmp.getRoot(), NAME);
        // Android hid a photo from the app: the folder looks complete, but the record names it.
        assertTrue(new File(hidden, "20261005_101012_000.jpg").delete());
        List<String> before = folderContents(hidden);
        File next = session(tmp.getRoot(), "1DFFC454_20261006_101010_photo");
        SessionZip z = zipper(hidden, next);
        z.packAll();
        assertEquals(Collections.singletonList(NAME), z.hidden);
        assertEquals(1, z.hiddenFiles);
        assertEquals(1, z.packed);
        assertEquals(before, folderContents(hidden));
        assertFalse(new File(tmp.getRoot(), NAME + ".zip").exists());
        assertFalse(next.exists());
        assertTrue(new File(tmp.getRoot(), next.getName() + ".zip").isFile());
    }

    @Test
    public void aFolderWithoutItsRecordStaysAsItIs() throws IOException {
        File dir = session(tmp.getRoot(), NAME);
        assertTrue(new File(dir, "session.jsonl").delete());
        SessionZip z = zipper(dir);
        z.packAll();
        assertEquals(Collections.singletonList(NAME), z.hidden);
        assertTrue(dir.isDirectory());
        File empty = tmp.newFolder("1DFFC454_20261007_101010_photo"); // a session that never started
        z = zipper(empty);
        z.packAll();
        assertTrue(z.hidden.isEmpty());
        assertFalse(empty.exists());
    }

    @Test
    public void theRecordNamesSavedPhotosAndSitePhotos() throws IOException {
        assertEquals(Arrays.asList("site_photos/site_1.jpg", "20261005_101010_000.jpg", "20261005_101012_000.jpg",
                "20261005_101014_000.jpg"), // not the photo that failed
                SessionZip.recordedFiles(new ByteArrayInputStream(RECORD.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void findsOnlyPhotoSessionsAndNamesTheirZips() throws IOException {
        File a = tmp.newFolder("a");
        File b = tmp.newFolder("b");
        File photo2 = session(a, "1DFFC454_20261006_080000_photo");
        File photo1 = session(a, "1DFFC454_20261005_080000_photo");
        session(a, "1DFFC454_20261005_120000_video");
        assertTrue(new File(a, "1DFFC454_20261004_080000_photo.zip").createNewFile());
        File photo3 = session(b, "1DFFC454_20261007_080000_photo");
        assertEquals(Arrays.asList(photo1, photo2, photo3), SessionZip.photoSessions(Arrays.asList(a, b)));

        assertEquals("X_photo", SessionZip.sessionName(new File(a, "X_photo.zip")));
        assertEquals("X_photo", SessionZip.sessionName(new File(a, "X_photo_part12.zip")));
        assertEquals(photo1.getName(), SessionZip.sessionName(photo1));
    }
}
