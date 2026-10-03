package com.faunalapse.app;

/**
 * Region of interest (ROI) maths: where the square is in a photo and how many pixels to save.
 * Pure Java, tested on the PC. For now the square is centred; the drag-the-square screen comes later.
 */
final class RoiMath {

    /** Saved sides are multiples of this (JPEG works in 8 or 16 pixel blocks; 32 keeps both). */
    static final int SIDE_STEP = 32;

    private RoiMath() {
    }

    /** Centred square covering {@code sideShare} (0 to 1) of the short side of a width x height
     *  picture, as {left, top, right, bottom} with even values. */
    static int[] centredSquare(int width, int height, double sideShare) {
        int side = (int) Math.round(Math.min(width, height) * Math.max(0, Math.min(1, sideShare))) & ~1;
        int left = ((width - side) / 2) & ~1;
        int top = ((height - side) / 2) & ~1;
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
}
