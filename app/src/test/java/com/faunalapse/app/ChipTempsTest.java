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
    public void unitFromTheFirstReading() {
        assertEquals(1000, ChipTemps.divisor(36400.0), 0); // the kernel's thousandths
        assertEquals(1000, ChipTemps.divisor(-40000.0), 0); // absent sensor, in thousandths
        assertEquals(10, ChipTemps.divisor(415.0), 0);
        assertEquals(1, ChipTemps.divisor(37.0), 0);
        assertEquals(1000, ChipTemps.divisor(0.0), 0); // nothing to go by: the kernel's rule
        assertEquals(1000, ChipTemps.divisor(null), 0);
    }

    @Test
    public void readingsInDegrees() {
        assertEquals(36.4, ChipTemps.celsius(36400.0, 1000), 1e-9);
        assertEquals(36.1, ChipTemps.celsius(36123.0, 1000), 1e-9); // one decimal
        assertEquals(0.5, ChipTemps.celsius(500.0, 1000), 1e-9); // cold chip: not 50 °C
        assertEquals(-5.0, ChipTemps.celsius(-5000.0, 1000), 1e-9); // frost is kept
        assertEquals(18.0, ChipTemps.celsius(180.0, 10), 1e-9);
        assertEquals(37.0, ChipTemps.celsius(37.0, 1), 1e-9);
    }

    @Test
    public void noTemperatureGivesNull() {
        assertNull(ChipTemps.celsius(-40000.0, 1000)); // sensor absent
        assertNull(ChipTemps.celsius(-40.0, 1));
        assertNull(ChipTemps.celsius(150000.0, 1000));
        assertNull(ChipTemps.celsius(null, 1000));
        assertNull(ChipTemps.number("n/a"));
        assertNull(ChipTemps.number(""));
        assertNull(ChipTemps.number(null));
    }

    @Test
    public void hottestCpuAndGpuZoneByName() throws IOException {
        File dir = tmp.newFolder("thermal");
        zone(dir, 0, "cpu-0-0-usr", "36400");
        zone(dir, 1, "cpu-1-2-usr", "52100");
        zone(dir, 2, "cpuss-0-usr", "-40000"); // absent sensor: left out
        zone(dir, 3, "gpuss-0-usr", "44000");
        zone(dir, 4, "G3D", "470"); // Exynos name of the GPU, here in tenths
        zone(dir, 5, "soc", "81"); // state of charge in percent, not a chip zone
        zone(dir, 6, "pm6150-tz", "60000");
        new File(dir, "cooling_device0").mkdir();
        ChipTemps t = new ChipTemps(dir);
        assertEquals(52.1, t.cpuC(), 1e-9);
        assertEquals(47.0, t.gpuC(), 1e-9);
    }

    @Test
    public void eachZoneKeepsItsUnitWhenItCools() throws IOException {
        File dir = tmp.newFolder("cooling");
        File cpu = zone(dir, 0, "mtktscpu", "21000");
        File gpu = zone(dir, 1, "gpu", "250"); // tenths
        ChipTemps t = new ChipTemps(dir);
        write(cpu, "800\n"); // 0.8 °C
        write(gpu, "150\n"); // 15.0 °C
        assertEquals(0.8, t.cpuC(), 1e-9);
        assertEquals(15.0, t.gpuC(), 1e-9);
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

    /** A zone folder; returns its temp file. */
    private static File zone(File dir, int n, String type, String temp) throws IOException {
        File z = new File(dir, "thermal_zone" + n);
        if (!z.mkdir()) throw new IOException("cannot make " + z);
        write(new File(z, "type"), type + "\n");
        File t = new File(z, "temp");
        write(t, temp + "\n");
        return t;
    }

    private static void write(File f, String text) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
