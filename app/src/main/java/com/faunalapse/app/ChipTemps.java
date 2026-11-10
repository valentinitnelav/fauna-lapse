package com.faunalapse.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Chip temperatures: the hottest CPU sensor and the hottest GPU sensor, from the kernel's thermal
 * zones (folders /sys/class/thermal/thermal_zone*, each with a `type` name and a `temp` reading).
 * They react within seconds, while the battery warms slowly and misses the chip's own slow-down.
 * Whether an app may read them is the phone maker's choice (Xiaomi phones yes, the Samsung M12
 * no): then both are null. Only recorded (see Thermal): the heat pause stays on the battery,
 * because safe chip limits differ per chip. Zone names as in FaunaPulse, so both apps' files read
 * alike. Pure Java, tested on the PC.
 */
final class ChipTemps {

    private static ChipTemps phone;

    private final List<Zone> cpu = new ArrayList<>();
    private final List<Zone> gpu = new ArrayList<>();

    /** A zone's `temp` file and the number that turns its reading into °C. */
    private static final class Zone {
        final File temp;
        final double divisor;

        Zone(File temp) {
            this.temp = temp;
            divisor = divisor(number(firstLine(temp)));
        }
    }

    /** Finds the zones in {@code thermalDir} whose name says CPU or GPU (Qualcomm: cpu-0-0-usr,
     *  gpuss-0-usr; MediaTek: mtktscpu; Exynos calls the GPU G3D). */
    ChipTemps(File thermalDir) {
        File[] zones = thermalDir.listFiles();
        if (zones == null) return; // the phone does not let apps read the folder
        for (File z : zones) {
            if (!z.getName().startsWith("thermal_zone")) continue;
            String type = firstLine(new File(z, "type"));
            if (type == null) continue;
            type = type.toLowerCase(Locale.US);
            if (type.contains("cpu")) cpu.add(new Zone(new File(z, "temp")));
            else if (type.contains("gpu") || type.contains("g3d")) gpu.add(new Zone(new File(z, "temp")));
        }
    }

    /** This phone's zones, found once (they do not change while the app runs). */
    static synchronized ChipTemps phone() {
        if (phone == null) phone = new ChipTemps(new File("/sys/class/thermal"));
        return phone;
    }

    Double cpuC() {
        return hottest(cpu);
    }

    Double gpuC() {
        return hottest(gpu);
    }

    /** The unit of a zone, decided once from its first reading. The kernel's rule is thousandths of
     *  a degree (37000 = 37 °C); a few old drivers give tenths or whole degrees. Deciding it per
     *  reading would read a chip below 1 °C (thousandths: 0 to 999) as 20 to 99.9 °C. */
    static double divisor(Double firstRaw) {
        if (firstRaw == null || firstRaw == 0) return 1000;
        double a = Math.abs(firstRaw);
        return a >= 1000 ? 1000 : a >= 200 ? 10 : 1;
    }

    /** A reading in °C (one decimal), or null when it is none: -40 °C or lower means that the
     *  sensor is absent, 150 °C or more that it is no temperature. */
    static Double celsius(Double raw, double divisor) {
        if (raw == null) return null;
        double c = raw / divisor;
        return c > -40 && c < 150 ? Math.round(c * 10) / 10.0 : null;
    }

    static Double number(String line) {
        if (line == null) return null;
        try {
            return Double.parseDouble(line.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double hottest(List<Zone> zones) {
        Double max = null;
        for (Zone z : zones) {
            Double c = celsius(number(firstLine(z.temp)), z.divisor);
            if (c != null && (max == null || c > max)) max = c;
        }
        return max;
    }

    private static String firstLine(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            return r.readLine();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
