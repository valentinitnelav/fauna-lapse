package com.faunalapse.app;

/**
 * Region of interest (ROI) maths: where the square is in a photo, how many pixels to save, how the
 * camera view on the screen relates to the photo, how to turn saved squares upright, and where a
 * video square comes from. Pure Java, tested on the PC.
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

    /** True when a w1 x h1 photo holds a larger square than a w2 x h2 one (a longer short side), or
     *  the same square with more pixels. The size with the most pixels may be only the 4:3 photo
     *  with top and bottom cut off and enlarged (Blackview A60: 4864x2736 against 4160x3120). */
    static boolean largerSquare(int w1, int h1, int w2, int h2) {
        int s1 = Math.min(w1, h1), s2 = Math.min(w2, h2);
        return s1 != s2 ? s1 > s2 : (long) w1 * h1 > (long) w2 * h2;
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

    /** Where a video square comes from: the hardware crop on the pixel array ({left, top, width,
     *  height}; null when the stream shows the whole view), the square in the stream (shares
     *  {left, top, width, height}) and its real pixels across in the stream. */
    static final class VideoSquare {
        final int[] crop;
        final double[] inStream;
        final int realPx;

        VideoSquare(int[] crop, double[] inStream, int realPx) {
            this.crop = crop;
            this.inStream = inStream;
            this.realPx = realPx;
        }
    }

    /**
     * The video square for a camera stream of the photo's shape. {@code zoom}: "anywhere" or
     * "centred" (Camera2: the camera hardware crops the pixel array before scaling it to the stream,
     * so a small square keeps its real detail), or "none" (Camera1: the stream shows the whole view,
     * and the square has only its share of the stream's pixels). The real pixels are never more than
     * the square has in the full photo.
     */
    static VideoSquare videoSquare(int photoW, int photoH, int arrayW, int arrayH, int streamW, int streamH,
                                   double maxZoom, String zoom, double sideShare, double centreX, double centreY) {
        int[] r = square(photoW, photoH, sideShare, centreX, centreY);
        if ("none".equals(zoom)) {
            int[] sq = {r[0], r[1], r[2] - r[0], r[3] - r[1]};
            int[] view = {0, 0, photoW, photoH};
            return new VideoSquare(null, inView(view, sq), (int) ((long) sq[3] * streamH / photoH));
        }
        int[] a = inArray(arrayW, arrayH, photoW, photoH, r);
        int[] crop = videoCrop(arrayW, arrayH, a, (double) streamW / streamH, maxZoom, "anywhere".equals(zoom));
        // A crop smaller than the stream is enlarged by the camera: the photo's pixels are the limit.
        int real = (int) Math.min(r[2] - r[0], (long) a[3] * streamH / crop[3]);
        return new VideoSquare(crop, inView(crop, a), real);
    }

    /**
     * Camera2 video: the part of the pixel array the camera zooms onto, as {left, top, width,
     * height}. It has the stream's shape ({@code aspect} = width / height), holds the square
     * {@code sq} ({left, top, width, height} on the array), is at most {@code maxZoom} times smaller
     * than the array, and is centred on the square where the camera can crop anywhere, else on the
     * middle of the array (then larger, so the square still fits).
     */
    static int[] videoCrop(int arrayW, int arrayH, int[] sq, double aspect, double maxZoom, boolean anywhere) {
        double cx = anywhere ? sq[0] + sq[2] / 2.0 : arrayW / 2.0;
        double cy = anywhere ? sq[1] + sq[3] / 2.0 : arrayH / 2.0;
        double half = Math.max(Math.max(cy - sq[1], sq[1] + sq[3] - cy),
                Math.max(cx - sq[0], sq[0] + sq[2] - cx) / aspect); // half height that holds the square
        double h = Math.max(2 * half, Math.max(arrayH, arrayW / aspect) / maxZoom);
        h = Math.min(h, Math.min(arrayH, arrayW / aspect));
        double w = h * aspect;
        double left = Math.max(0, Math.min(arrayW - w, cx - w / 2));
        double top = Math.max(0, Math.min(arrayH - h, cy - h / 2));
        return new int[] {(int) Math.round(left), (int) Math.round(top), (int) Math.round(w), (int) Math.round(h)};
    }

    /** The rectangle {@code sq} as shares {left, top, width, height} of {@code view} (both {left,
     *  top, width, height} in the same pixels). */
    static double[] inView(int[] view, int[] sq) {
        return new double[] {(sq[0] - view[0]) / (double) view[2], (sq[1] - view[1]) / (double) view[3],
            sq[2] / (double) view[2], sq[3] / (double) view[3]};
    }

    /**
     * The four corners of a quad that draws the square {@code sq} (shares {left, top, width,
     * height} of a camera frame) turned {@code turn} degrees clockwise onto a whole output frame:
     * {x, y, u, v} per corner, in the order bottom left, bottom right, top left, top right (a
     * triangle strip). x and y run from -1 to 1 upwards (OpenGL); u and v are shares of the camera
     * frame as stored (sensor orientation, v down from the top row), see {@link #inTexture}.
     */
    static float[] squareCorners(double[] sq, int turn) {
        double l = sq[0], t = sq[1], r = sq[0] + sq[2], b = sq[1] + sq[3];
        double[][] frame = {{l, t}, {r, t}, {r, b}, {l, b}}; // clockwise from top left
        float[][] out = {{-1, 1}, {1, 1}, {1, -1}, {-1, -1}};  // the same, on the output
        int[] strip = {3, 2, 0, 1};
        float[] v = new float[16];
        for (int i = 0; i < 4; i++) {
            int k = strip[i];
            double[] f = frame[(k - turn / 90 + 4) % 4]; // turned clockwise: corner k shows corner k - turn
            v[4 * i] = out[k][0];
            v[4 * i + 1] = out[k][1];
            v[4 * i + 2] = (float) f[0];
            v[4 * i + 3] = (float) f[1];
        }
        return v;
    }

    /**
     * Corners from {@link #squareCorners} with u and v turned into texture coordinates of a camera
     * frame. {@code m}: the frame's texture transform (SurfaceTexture, 4 x 4, column by column). Only
     * the part of the texture it covers is used, not its turn: Camera2 adds the sensor orientation
     * there and Camera1 does not, but a texture always holds the frame as stored, top row first.
     */
    static float[] inTexture(float[] corners, float[] m) {
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (int c = 0; c < 4; c++) {
            float s = c & 1;
            float t = c >> 1;
            float x = m[0] * s + m[4] * t + m[12];
            float y = m[1] * s + m[5] * t + m[13];
            x0 = Math.min(x0, x);
            x1 = Math.max(x1, x);
            y0 = Math.min(y0, y);
            y1 = Math.max(y1, y);
        }
        float[] out = corners.clone();
        for (int i = 0; i < 4; i++) {
            out[4 * i + 2] = x0 + corners[4 * i + 2] * (x1 - x0);
            out[4 * i + 3] = y0 + corners[4 * i + 3] * (y1 - y0);
        }
        return out;
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
