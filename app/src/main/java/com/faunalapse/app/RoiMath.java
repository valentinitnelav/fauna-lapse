package com.faunalapse.app;

/**
 * Region of interest (ROI) maths: where the square is in a photo, how many pixels to save, how the
 * camera view on the screen relates to the photo, and how to turn saved squares upright. Pure Java,
 * tested on the PC.
 *
 * "Photo" means the full photo as the camera delivers it, in the sensor's own orientation (on most
 * phones the long side is the width, whichever way the phone is held). Shares run from 0 to 1
 * across its width and height.
 */
final class RoiMath {

    /** Saved sides are multiples of this (JPEG works in 8 or 16 pixel blocks; 32 keeps both). */
    static final int SIDE_STEP = 32;

    private RoiMath() {
    }

    /** Square covering {@code sideShare} of the short side of a width x height photo, with its
     *  centre at the shares (centreX, centreY) but kept inside the photo, as {left, top, right,
     *  bottom} with even values. */
    static int[] square(int width, int height, double sideShare, double centreX, double centreY) {
        int side = (int) Math.round(Math.min(width, height) * clamp(sideShare)) & ~1;
        int left = (int) Math.round(width * clamp(centreX) - side / 2.0);
        int top = (int) Math.round(height * clamp(centreY) - side / 2.0);
        left = Math.max(0, Math.min(width - side, left)) & ~1;
        top = Math.max(0, Math.min(height - side, top)) & ~1;
        return new int[] {left, top, left + side, top + side};
    }

    /** Side to save: the target, but never more than the real pixels inside the square (never
     *  enlarge), rounded down to a multiple of {@link #SIDE_STEP}. */
    static int savedSide(int realSide, int target) {
        int side = Math.min(realSide, target) / SIDE_STEP * SIDE_STEP;
        return Math.max(SIDE_STEP, side);
    }

    /** Largest power-of-2 shrink factor that still keeps at least {@code saved} pixels per side,
     *  so the phone decodes fewer pixels before the final resize. */
    static int sampleSize(int realSide, int saved) {
        int sample = 1;
        while (realSide / (sample * 2) >= saved) sample *= 2;
        return sample;
    }

    /** Where a photo point (shares) appears on a screen that shows the photo turned clockwise by
     *  {@code turn} degrees (0, 90, 180 or 270), as shares of the screen view. */
    static double[] photoToScreen(double x, double y, int turn) {
        switch (turn) {
            case 90:
                return new double[] {1 - y, x};
            case 180:
                return new double[] {1 - x, 1 - y};
            case 270:
                return new double[] {y, 1 - x};
            default:
                return new double[] {x, y};
        }
    }

    /** The reverse of {@link #photoToScreen}. */
    static double[] screenToPhoto(double u, double v, int turn) {
        return photoToScreen(u, v, (360 - turn) % 360);
    }

    /** A phone orientation from Android (degrees clockwise from upright: 90 = left side up; -1 =
     *  lying flat) rounded to 0, 90, 180 or 270. Flat counts as upright, so the top of the saved
     *  photo is the top edge of the phone, as on the screen. */
    static int quarterTurn(int degrees) {
        return degrees < 0 ? 0 : (degrees + 45) / 90 * 90 % 360;
    }

    /** Clockwise turn that makes a back-camera photo upright: the sensor orientation plus the
     *  phone's own turn (the formula in Android's Camera.Parameters.setRotation documentation). */
    static int photoTurn(int sensorOrientation, int phoneTurn) {
        return (sensorOrientation + phoneTurn) % 360;
    }

    /** The rectangle {@code r} (photo pixels) on the camera's pixel array, as {left, top, width,
     *  height}: a photo shows the largest centred part of the array with the photo's shape.
     *  Camera2 wants focus and exposure areas this way. */
    static int[] inArray(int arrayW, int arrayH, int photoW, int photoH, int[] r) {
        double w = Math.min(arrayW, (double) arrayH * photoW / photoH);
        double h = Math.min(arrayH, (double) arrayW * photoH / photoW);
        double scale = w / photoW;
        double x0 = (arrayW - w) / 2;
        double y0 = (arrayH - h) / 2;
        return new int[] {(int) Math.round(x0 + r[0] * scale), (int) Math.round(y0 + r[1] * scale),
            (int) Math.round((r[2] - r[0]) * scale), (int) Math.round((r[3] - r[1]) * scale)};
    }

    /** The rectangle {@code r} (photo pixels) as a Camera1 area: -1000 to 1000 across the view. */
    static int[] camera1Area(int photoW, int photoH, int[] r) {
        return new int[] {area(r[0], photoW), area(r[1], photoH), area(r[2], photoW), area(r[3], photoH)};
    }

    private static int area(int pixel, int size) {
        return Math.max(-1000, Math.min(1000, (int) Math.round(-1000 + 2000.0 * pixel / size)));
    }

    private static double clamp(double share) {
        return Math.max(0, Math.min(1, share));
    }
}
