package com.faunalapse.app;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Chip temperatures for the records: the hottest CPU sensor and the hottest GPU sensor, from the
 * kernel's thermal zones (folders /sys/class/thermal/thermal_zone*, each with a `type` name and a
 * `temp` reading). They react within seconds, while the battery warms slowly and misses the chip's
 * own slow-down. Whether an app may read them is the phone maker's choice (Xiaomi phones yes, the
 * Samsung M12 no): then both are null. Only recorded: the heat pause stays on the battery, because
 * safe chip limits differ per chip. Keys and rule as in FaunaPulse, so both apps' files read alike.
 */
final class ChipTemps {

    private static ChipTemps phone;

    /** The `temp` files of the CPU and the GPU zones; empty where the folder cannot be read. */
    private final List<File> cpu = new ArrayList<>();
    private final List<File> gpu = new ArrayList<>();

    /** Finds the zones in {@code thermalDir} whose name says CPU or GPU (Qualcomm: cpu-0-0-usr,
     *  gpuss-0-usr; MediaTek: mtktscpu; Exynos calls the GPU G3D). */
    ChipTemps(File thermalDir) {
        File[] zones = thermalDir.listFiles();
        if (zones == null) return;
        for (File z : zones) {
            if (!z.getName().startsWith("thermal_zone")) continue;
            String type = firstLine(new File(z, "type"));
            if (type == null) continue;
            type = type.toLowerCase(Locale.US);
            if (type.contains("cpu")) cpu.add(new File(z, "temp"));
            else if (type.contains("gpu") || type.contains("g3d")) gpu.add(new File(z, "temp"));
        }
    }

    /** This phone's zones, found once (they do not change while the app runs). */
    static synchronized ChipTemps phone() {
        if (phone == null) phone = new ChipTemps(new File("/sys/class/thermal"));
        return phone;
    }

    /** Adds cpu_temp_c and gpu_temp_c to a record, null where the phone allows no reading. */
    static void put(JSONObject r) {
        ChipTemps t = phone();
        Double c = t.cpuC();
        Double g = t.gpuC();
        SessionLog.put(r, "cpu_temp_c", c == null ? JSONObject.NULL : c);
        SessionLog.put(r, "gpu_temp_c", g == null ? JSONObject.NULL : g);
    }

    Double cpuC() {
        return hottest(cpu);
    }

    Double gpuC() {
        return hottest(gpu);
    }

    /** A `temp` reading in °C (one decimal), or null when it is no temperature. Most kernels give
     *  thousandths of a degree, a few tenths or whole degrees. Readings outside 0 to 150 °C are
     *  dropped: -40 means that the sensor is absent. */
    static Double celsius(String raw) {
        if (raw == null) return null;
        double v;
        try {
            v = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        double c = Math.abs(v) >= 1000 ? v / 1000 : Math.abs(v) >= 200 ? v / 10 : v;
        return c > 0 && c < 150 ? Math.round(c * 10) / 10.0 : null;
    }

    private static Double hottest(List<File> temps) {
        Double max = null;
        for (File f : temps) {
            Double c = celsius(firstLine(f));
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
