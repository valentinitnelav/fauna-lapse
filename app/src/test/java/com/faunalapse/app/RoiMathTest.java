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
