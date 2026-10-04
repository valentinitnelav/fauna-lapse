package com.faunalapse.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RoiMathTest {

    @Test
    public void centredSquare() {
        assertArrayEquals(new int[] {500, 0, 3500, 3000}, RoiMath.square(4000, 3000, 1.0, 0.5, 0.5));
        assertArrayEquals(new int[] {450, 270, 990, 810}, RoiMath.square(1440, 1080, 0.5, 0.5, 0.5));
        assertArrayEquals(new int[] {0, 500, 3000, 3500}, RoiMath.square(3000, 4000, 1.0, 0.5, 0.5)); // upright
    }

    @Test
    public void movedSquareStaysInsideThePhoto() {
        assertArrayEquals(new int[] {2200, 400, 3700, 1900}, RoiMath.square(4000, 3000, 0.5, 0.7375, 0.3833));
        // Pushed past the top right corner: it stops at the edges.
        assertArrayEquals(new int[] {2500, 0, 4000, 1500}, RoiMath.square(4000, 3000, 0.5, 0.9, 0.1));
        assertArrayEquals(new int[] {0, 0, 3000, 3000}, RoiMath.square(4000, 3000, 1.0, 0.0, 0.5));
    }

    @Test
    public void savedSideIsNeverEnlargedAndAMultipleOf32() {
        assertEquals(1024, RoiMath.savedSide(3000, 1024));
        assertEquals(1024, RoiMath.savedSide(1080, 1024));
        assertEquals(992, RoiMath.savedSide(1000, 1024)); // fewer real pixels than the target
        assertEquals(512, RoiMath.savedSide(540, 1024));
        assertEquals(1280, RoiMath.savedSide(3000, 1280));
    }

    @Test
    public void videoCropZoomsOntoTheSquare() {
        // Samsung M12: 4000 x 3000 array and photo, 4:3 stream, up to 8 times zoom, crop anywhere.
        double a = 4.0 / 3;
        assertArrayEquals(new int[] {0, 0, 4000, 3000}, RoiMath.videoCrop(4000, 3000, new int[] {500, 0, 3000, 3000}, a, 8, true));
        // A 50 % square at the top right: the crop is as high as the square, kept inside the array.
        assertArrayEquals(new int[] {2000, 0, 2000, 1500}, RoiMath.videoCrop(4000, 3000, new int[] {2500, 0, 1500, 1500}, a, 8, true));
        // A 10 % square: at most 8 times zoom, so the crop is larger than the square.
        assertArrayEquals(new int[] {1750, 1313, 500, 375}, RoiMath.videoCrop(4000, 3000, new int[] {1850, 1350, 300, 300}, a, 8, true));
        // Centred crops only: the top right square needs the whole array, a middle one less.
        assertArrayEquals(new int[] {0, 0, 4000, 3000}, RoiMath.videoCrop(4000, 3000, new int[] {2500, 0, 1500, 1500}, a, 8, false));
        assertArrayEquals(new int[] {1400, 1050, 1200, 900}, RoiMath.videoCrop(4000, 3000, new int[] {1550, 1050, 900, 900}, a, 8, false));
    }

    @Test
    public void videoSquareInTheStream() {
        // Camera2 on the Samsung, stream 2576 x 1932: a 50 % square at the top right.
        RoiMath.VideoSquare v = RoiMath.videoSquare(4000, 3000, 4000, 3000, 2576, 1932, 8, "anywhere", 0.5, 0.9, 0.1);
        assertArrayEquals(new int[] {2000, 0, 2000, 1500}, v.crop);
        assertArrayEquals(new double[] {0.25, 0, 0.75, 1}, v.inStream, 1e-9);
        assertEquals(1500, v.realPx); // the photo's pixels: the camera enlarges 1500 to 1932
        // The whole view: the stream's 1932 rows are fewer than the photo's 3000.
        assertEquals(1932, RoiMath.videoSquare(4000, 3000, 4000, 3000, 2576, 1932, 8, "anywhere", 1, 0.5, 0.5).realPx);
        // A 10 % square: 300 real pixels, although the stream has 1545 across it.
        assertEquals(300, RoiMath.videoSquare(4000, 3000, 4000, 3000, 2576, 1932, 8, "anywhere", 0.1, 0.5, 0.5).realPx);
        // Camera1, stream 1440 x 1080 of the whole view: the square has half its height.
        v = RoiMath.videoSquare(4000, 3000, 4000, 3000, 1440, 1080, 1, "none", 0.5, 0.9, 0.1);
        assertEquals(null, v.crop);
        assertArrayEquals(new double[] {0.625, 0, 0.375, 0.5}, v.inStream, 1e-9);
        assertEquals(540, v.realPx);
    }

    @Test
    public void squareCornersTurnClockwise() {
        double[] sq = {0.25, 0, 0.5, 0.75}; // left, top, width, height
        // Not turned: the output's bottom left shows the square's bottom left (v runs down).
        assertArrayEquals(new float[] {-1, -1, 0.25f, 0.75f, 1, -1, 0.75f, 0.75f, -1, 1, 0.25f, 0, 1, 1, 0.75f, 0},
                RoiMath.squareCorners(sq, 0), 1e-6f);
        // Turned 90 degrees clockwise: the output's top left shows the square's bottom left.
        assertArrayEquals(new float[] {-1, -1, 0.75f, 0.75f, 1, -1, 0.75f, 0, -1, 1, 0.25f, 0.75f, 1, 1, 0.25f, 0},
                RoiMath.squareCorners(sq, 90), 1e-6f);
    }

    @Test
    public void textureCornersIgnoreTheTransformTurn() {
        float[] corners = RoiMath.squareCorners(new double[] {0.25, 0, 0.5, 0.75}, 0);
        float[] flip = {1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1}; // Camera1: y flipped only
        float[] turn = {0, -1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1}; // Camera2: also turned 90
        assertArrayEquals(corners, RoiMath.inTexture(corners, flip), 1e-6f);
        assertArrayEquals(corners, RoiMath.inTexture(corners, turn), 1e-6f);
        // A texture with a cropped edge: only its valid part is used.
        float[] cropped = {0.5f, 0, 0, 0, 0, -0.98f, 0, 0, 0, 0, 1, 0, 0, 0.99f, 0, 1};
        float[] c = RoiMath.inTexture(corners, cropped);
        assertEquals(0.125f, c[2], 1e-6f);        // u 0.25 of a texture half as wide
        assertEquals(0.01f + 0.735f, c[3], 1e-6f); // v 0.75 between 0.01 and 0.99
    }

    @Test
    public void sampleSizeKeepsEnoughPixels() {
        assertEquals(2, RoiMath.sampleSize(3000, 1024)); // decode 1500 px, then resize to 1024
        assertEquals(1, RoiMath.sampleSize(1080, 1024));
        assertEquals(4, RoiMath.sampleSize(3000, 512));
    }

    @Test
    public void screenAndPhotoPoints() {
        // Turned 90 degrees clockwise: the photo's top left corner is at the screen's top right.
        assertArrayEquals(new double[] {1, 0}, RoiMath.photoToScreen(0, 0, 90), 1e-9);
        assertArrayEquals(new double[] {0, 1}, RoiMath.photoToScreen(0, 0, 270), 1e-9);
        assertArrayEquals(new double[] {0.8, 0.9}, RoiMath.photoToScreen(0.2, 0.1, 180), 1e-9);
        for (int turn = 0; turn < 360; turn += 90) {
            double[] screen = RoiMath.photoToScreen(0.2, 0.7, turn);
            assertArrayEquals(new double[] {0.2, 0.7}, RoiMath.screenToPhoto(screen[0], screen[1], turn), 1e-9);
        }
    }

    @Test
    public void turns() {
        assertEquals(0, RoiMath.quarterTurn(-1)); // lying flat
        assertEquals(0, RoiMath.quarterTurn(44));
        assertEquals(90, RoiMath.quarterTurn(46));
        assertEquals(180, RoiMath.quarterTurn(200));
        assertEquals(270, RoiMath.quarterTurn(300));
        assertEquals(0, RoiMath.quarterTurn(350));
        assertEquals(90, RoiMath.photoTurn(90, 0)); // Samsung M12 held upright
        assertEquals(0, RoiMath.photoTurn(90, 270)); // right side up: the photo is already upright
        assertEquals(180, RoiMath.photoTurn(270, 270));
    }

    @Test
    public void focusAreas() {
        int[] r = {500, 0, 3500, 3000};
        assertArrayEquals(new int[] {500, 0, 3000, 3000}, RoiMath.inArray(4000, 3000, 4000, 3000, r));
        // A 16:9 photo shows a band of a 4:3 pixel array.
        assertArrayEquals(new int[] {0, 375, 4000, 2250}, RoiMath.inArray(4000, 3000, 1920, 1080,
                new int[] {0, 0, 1920, 1080}));
        assertArrayEquals(new int[] {-750, -1000, 750, 1000}, RoiMath.camera1Area(4000, 3000, r));
    }
}
