package com.faunalapse.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class ChipTempsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void readingsInThousandthsTenthsOrDegrees() {
        assertEquals(36.4, ChipTemps.celsius("36400\n"), 1e-9);
        assertEquals(36.1, ChipTemps.celsius("36123"), 1e-9); // one decimal
        assertEquals(41.5, ChipTemps.celsius("415"), 1e-9);
        assertEquals(37.0, ChipTemps.celsius("37"), 1e-9);
    }

    @Test
    public void noTemperatureGivesNull() {
        assertNull(ChipTemps.celsius("-40000")); // sensor absent
        assertNull(ChipTemps.celsius("-40"));
        assertNull(ChipTemps.celsius("0"));
        assertNull(ChipTemps.celsius("150000"));
        assertNull(ChipTemps.celsius("n/a"));
        assertNull(ChipTemps.celsius(""));
        assertNull(ChipTemps.celsius(null));
    }

    @Test
    public void hottestCpuAndGpuZoneByName() throws IOException {
        File dir = tmp.newFolder("thermal");
        zone(dir, 0, "cpu-0-0-usr", "36400");
        zone(dir, 1, "cpu-1-2-usr", "52100");
        zone(dir, 2, "cpuss-0-usr", "-40000"); // absent sensor: left out
        zone(dir, 3, "gpuss-0-usr", "44000");
        zone(dir, 4, "G3D", "47000"); // Exynos name of the GPU
        zone(dir, 5, "soc", "81"); // state of charge in percent, not a chip zone
        zone(dir, 6, "pm6150-tz", "60000");
        new File(dir, "cooling_device0").mkdir();
        ChipTemps t = new ChipTemps(dir);
        assertEquals(52.1, t.cpuC(), 1e-9);
        assertEquals(47.0, t.gpuC(), 1e-9);
    }

    @Test
    public void unreadableFolderOrNoChipZonesGiveNull() throws IOException {
        ChipTemps none = new ChipTemps(new File(tmp.getRoot(), "missing"));
        assertNull(none.cpuC());
        assertNull(none.gpuC());
        File dir = tmp.newFolder("battery_only");
        zone(dir, 0, "battery", "31000");
        ChipTemps battery = new ChipTemps(dir);
        assertNull(battery.cpuC());
        assertNull(battery.gpuC());
    }

    private static void zone(File dir, int n, String type, String temp) throws IOException {
        File z = new File(dir, "thermal_zone" + n);
        if (!z.mkdir()) throw new IOException("cannot make " + z);
        write(new File(z, "type"), type + "\n");
        write(new File(z, "temp"), temp + "\n");
    }

    private static void write(File f, String text) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
