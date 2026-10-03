package com.faunalapse.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Cuts the ROI square out of a full photo and saves it as a JPEG (the only processor work on photos). */
final class PhotoSaver {

    static final int JPEG_QUALITY = 90;

    private PhotoSaver() {
    }

    /**
     * Decodes only the square (shrunk by a power of 2 where possible, which is fast and safe on
     * phones with little memory), resizes it to the saved side and writes {@code file}.
     * Returns {real side in the photo, saved side}.
     */
    @SuppressWarnings("deprecation") // the newer BitmapRegionDecoder.newInstance needs Android 12
    static int[] saveSquare(byte[] jpeg, double sideShare, int targetSide, File file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        int[] r = RoiMath.centredSquare(bounds.outWidth, bounds.outHeight, sideShare);
        int real = r[2] - r[0];
        int saved = RoiMath.savedSide(real, targetSide);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = RoiMath.sampleSize(real, saved);
        BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.length, false);
        Bitmap square = decoder.decodeRegion(new Rect(r[0], r[1], r[2], r[3]), options);
        decoder.recycle();
        Bitmap out = square.getWidth() == saved ? square : Bitmap.createScaledBitmap(square, saved, saved, true);
        try (FileOutputStream f = new FileOutputStream(file)) {
            out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, f);
            f.getFD().sync(); // on disk even if the phone dies next
        } finally {
            if (out != square) out.recycle();
            square.recycle();
        }
        return new int[] {real, saved};
    }
}
