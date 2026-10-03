package com.faunalapse.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RoiMathTest {

    @Test
    public void centredSquare() {
        assertArrayEquals(new int[] {500, 0, 3500, 3000}, RoiMath.centredSquare(4000, 3000, 1.0));
        assertArrayEquals(new int[] {450, 270, 990, 810}, RoiMath.centredSquare(1440, 1080, 0.5));
        assertArrayEquals(new int[] {0, 500, 3000, 3500}, RoiMath.centredSquare(3000, 4000, 1.0)); // upright
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
}
