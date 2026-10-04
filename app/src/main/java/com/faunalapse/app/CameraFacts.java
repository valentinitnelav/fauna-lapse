package com.faunalapse.app;

import android.content.Context;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/** What one camera offers: sizes, frame rates, focus and exposure options, video profiles.
 *  Phase 0 collects this on every test phone. */
@SuppressWarnings("deprecation") // Camera1 and CamcorderProfile.get() still work on every version
final class CameraFacts {

    private CameraFacts() {
    }

    static JSONObject collect(Context context, int cameraId, Camera.Parameters p) throws JSONException {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        JSONObject f = new JSONObject();
        f.put("phone", Build.MANUFACTURER + " " + Build.MODEL);
        f.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        f.put("camera1_id", cameraId);
        f.put("camera1_count", Camera.getNumberOfCameras());
        f.put("sensor_orientation", info.orientation);
        f.put("can_disable_shutter_sound", info.canDisableShutterSound);
        f.put("picture_sizes", sizes(p.getSupportedPictureSizes()));
        f.put("preview_sizes", sizes(p.getSupportedPreviewSizes()));
        List<Camera.Size> video = p.getSupportedVideoSizes();
        f.put("video_sizes", video == null ? "same as preview sizes" : sizes(video));
        JSONArray fps = new JSONArray();
        for (int[] r : p.getSupportedPreviewFpsRange()) {
            fps.put(r[Camera.Parameters.PREVIEW_FPS_MIN_INDEX] / 1000.0 + "-"
                    + r[Camera.Parameters.PREVIEW_FPS_MAX_INDEX] / 1000.0);
        }
        f.put("preview_fps_ranges", fps);
        f.put("focus_modes", new JSONArray(p.getSupportedFocusModes()));
        f.put("max_focus_areas", p.getMaxNumFocusAreas());
        f.put("max_metering_areas", p.getMaxNumMeteringAreas());
        f.put("exposure_lock", p.isAutoExposureLockSupported());
        f.put("white_balance_lock", p.isAutoWhiteBalanceLockSupported());
        float step = p.getExposureCompensationStep();
        f.put("exposure_compensation_ev", String.format(Locale.US, "%.2f to %.2f",
                p.getMinExposureCompensation() * step, p.getMaxExposureCompensation() * step));
        List<Integer> zoom = p.isZoomSupported() ? p.getZoomRatios() : null;
        f.put("max_zoom", zoom == null ? 1.0 : zoom.get(zoom.size() - 1) / 100.0);
        f.put("video_stabilization", p.isVideoStabilizationSupported());
        f.put("focal_length_mm", p.getFocalLength());
        f.put("view_angle_deg", p.getHorizontalViewAngle() + " x " + p.getVerticalViewAngle());
        f.put("video_profiles", videoProfiles(cameraId));
        // Every Camera1 setting, also the phone maker's own: some old phones hide a manual focus there.
        f.put("all_camera1_parameters", p.flatten());
        f.put("camera2", Camera2Facts.collect(context));
        return f;
    }

    private static JSONArray sizes(List<Camera.Size> sizes) {
        JSONArray a = new JSONArray();
        for (Camera.Size s : sizes) a.put(s.width + "x" + s.height);
        return a;
    }

    /** The phone maker's recording presets (size, frame rate, bit rate) for this camera. */
    private static JSONObject videoProfiles(int cameraId) throws JSONException {
        int[] qualities = {
            CamcorderProfile.QUALITY_2160P, CamcorderProfile.QUALITY_1080P,
            CamcorderProfile.QUALITY_720P, CamcorderProfile.QUALITY_480P,
            CamcorderProfile.QUALITY_TIME_LAPSE_1080P, CamcorderProfile.QUALITY_TIME_LAPSE_720P,
        };
        String[] names = {"2160p", "1080p", "720p", "480p", "time-lapse 1080p", "time-lapse 720p"};
        JSONObject profiles = new JSONObject();
        for (int i = 0; i < qualities.length; i++) {
            if (!CamcorderProfile.hasProfile(cameraId, qualities[i])) continue;
            CamcorderProfile c = CamcorderProfile.get(cameraId, qualities[i]);
            profiles.put(names[i], String.format(Locale.US, "%dx%d, %d fps, %.1f Mbit/s",
                    c.videoFrameWidth, c.videoFrameHeight, c.videoFrameRate, c.videoBitRate / 1e6));
        }
        return profiles;
    }
}
