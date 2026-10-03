package com.faunalapse.app;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Range;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * What the newer Camera2 interface (Android 5+) says about each camera. Phase 4 (manual focus) and
 * the hardware ROI crop depend on these facts. Kept in its own class so Android 4.4 never loads it.
 */
@TargetApi(21)
final class Camera2Facts {

    private Camera2Facts() {
    }

    static JSONArray collect(Context context) {
        JSONArray all = new JSONArray();
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            for (String id : manager.getCameraIdList()) {
                all.put(describe(id, manager.getCameraCharacteristics(id)));
            }
        } catch (Exception e) {
            all.put("error: " + e);
        }
        return all;
    }

    private static JSONObject describe(String id, CameraCharacteristics c) throws Exception {
        JSONObject f = new JSONObject();
        f.put("id", id);
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        f.put("facing", facing == null ? "?" : facing == CameraMetadata.LENS_FACING_BACK ? "back"
                : facing == CameraMetadata.LENS_FACING_FRONT ? "front" : "external");
        // LEGACY means Camera2 is only a wrapper around Camera1: no extra control.
        Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        String[] levels = {"limited", "full", "legacy", "level 3", "external"};
        f.put("hardware_level", level == null || level >= levels.length ? "?" : levels[level]);
        if (Build.VERSION.SDK_INT >= 23) {
            // Freeform: the camera chip can cut out any rectangle (a hardware ROI crop).
            Integer crop = c.get(CameraCharacteristics.SCALER_CROPPING_TYPE);
            f.put("cropping", crop != null && crop == CameraMetadata.SCALER_CROPPING_TYPE_FREEFORM
                    ? "freeform" : "center only");
        }
        // Manual focus: the app can switch autofocus off and set the lens distance itself. Minimum
        // focus distance is in diopters (1 / metres); 0 or missing means fixed focus.
        Float minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        boolean afOff = false;
        int[] afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (afModes != null) for (int m : afModes) afOff |= m == CameraMetadata.CONTROL_AF_MODE_OFF;
        boolean manualFocus = afOff && minFocus != null && minFocus > 0;
        f.put("manual_focus", manualFocus);
        if (manualFocus) f.put("closest_focus_cm", Math.round(100 / minFocus));
        JSONArray caps = new JSONArray();
        int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities != null) {
            for (int cap : capabilities) {
                if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) caps.put("manual sensor");
                else if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) caps.put("raw");
                else if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE) caps.put("burst");
                else caps.put(cap);
            }
        }
        f.put("capabilities", caps);
        JSONArray fps = new JSONArray();
        Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) for (Range<Integer> r : ranges) fps.put(r.getLower() + "-" + r.getUpper());
        f.put("fps_ranges", fps);
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            f.put("jpeg_largest", largestWithRate(map, ImageFormat.JPEG));
            f.put("yuv_largest", largestWithRate(map, ImageFormat.YUV_420_888));
        }
        return f;
    }

    /** Largest output size and the fastest rate the camera claims for it. */
    private static String largestWithRate(StreamConfigurationMap map, int format) {
        Size[] sizes = map.getOutputSizes(format);
        if (sizes == null || sizes.length == 0) return "none";
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        long frameNs = map.getOutputMinFrameDuration(format, best);
        return String.format(Locale.US, "%dx%d, up to %.1f per s", best.getWidth(), best.getHeight(),
                frameNs > 0 ? 1e9 / frameNs : 0);
    }
}
