package com.faunalapse.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * The ROI square over the camera view on the aim screen; a finger moves it. The square is kept as
 * in SessionSettings (shares of the photo, see RoiMath), and the view shows the photo turned
 * clockwise by {@code viewTurn}. A triangle marks the edge that becomes the top of the saved photos.
 */
public final class SquareView extends View {

    /** Called when the finger leaves the screen after moving the square. */
    interface Listener {
        void onMoved();
    }

    double sideShare = 1;
    double centreX = 0.5;
    double centreY = 0.5;
    private int photoW;
    private int photoH;
    private int viewTurn;
    private int phoneTurn;
    private Listener listener;
    private final float dp;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String topWord;
    private final Path triangle = new Path();
    private float downX;
    private float downY;
    private double[] downCentre; // square centre on the screen (shares) when the finger came down

    public SquareView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        line.setColor(Color.WHITE);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * dp);
        shade.setColor(0x80000000); // half-transparent black outside the square
        mark.setColor(Color.WHITE);
        label.setColor(Color.WHITE);
        label.setTextSize(12 * dp);
        label.setTextAlign(Paint.Align.CENTER);
        label.setShadowLayer(2 * dp, 0, 0, Color.BLACK); // readable on a bright flower
        topWord = context.getString(R.string.aim_top);
    }

    /** Called once the camera is open and the photo size is known. */
    void setUp(int photoW, int photoH, int viewTurn, Listener listener) {
        this.photoW = photoW;
        this.photoH = photoH;
        this.viewTurn = viewTurn;
        this.listener = listener;
        keepInside();
    }

    /** How the phone is held now (RoiMath.quarterTurn): moves the triangle. */
    void setPhoneTurn(int turn) {
        if (turn == phoneTurn) return;
        phoneTurn = turn;
        invalidate();
    }

    /** Moves the centre so the whole square stays inside the photo (also after a size change). */
    void keepInside() {
        if (photoW == 0) return;
        int[] r = RoiMath.square(photoW, photoH, sideShare, centreX, centreY);
        centreX = (r[0] + r[2]) / 2.0 / photoW;
        centreY = (r[1] + r[3]) / 2.0 / photoH;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (photoW == 0) return;
        // The same pixels that PhotoSaver cuts, shown on the screen.
        int[] r = RoiMath.square(photoW, photoH, sideShare, centreX, centreY);
        double[] a = RoiMath.photoToScreen((double) r[0] / photoW, (double) r[1] / photoH, viewTurn);
        double[] b = RoiMath.photoToScreen((double) r[2] / photoW, (double) r[3] / photoH, viewTurn);
        int w = getWidth();
        int h = getHeight();
        float left = (float) (Math.min(a[0], b[0]) * w);
        float top = (float) (Math.min(a[1], b[1]) * h);
        float right = (float) (Math.max(a[0], b[0]) * w);
        float bottom = (float) (Math.max(a[1], b[1]) * h);
        canvas.drawRect(0, 0, w, top, shade);
        canvas.drawRect(0, bottom, w, h, shade);
        canvas.drawRect(0, top, left, bottom, shade);
        canvas.drawRect(right, top, w, bottom, shade);
        canvas.drawRect(left, top, right, bottom, line);

        // Triangle inside the top edge, pointing up, with the word "top" below it; turned with the
        // phone (90 = left side up, so the top of the saved photo is the left edge on the screen).
        float cx = (left + right) / 2;
        float cy = (top + bottom) / 2;
        float size = Math.min(14 * dp, (right - left) / 4);
        canvas.save();
        canvas.rotate(-phoneTurn, cx, cy);
        triangle.reset();
        triangle.moveTo(cx, top + 2 * dp);
        triangle.lineTo(cx + size, top + 2 * dp + size);
        triangle.lineTo(cx - size, top + 2 * dp + size);
        triangle.close();
        canvas.drawPath(triangle, mark);
        if (right - left > 8 * size) canvas.drawText(topWord, cx, top + 4 * dp + size - label.ascent(), label);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (photoW == 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downCentre = RoiMath.photoToScreen(centreX, centreY, viewTurn);
                return true;
            case MotionEvent.ACTION_MOVE:
                moveTo(e);
                return true;
            case MotionEvent.ACTION_UP:
                moveTo(e);
                performClick();
                if (listener != null) listener.onMoved();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (listener != null) listener.onMoved();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    /** The square follows the finger from where it was when the finger came down. */
    private void moveTo(MotionEvent e) {
        double[] p = RoiMath.screenToPhoto(downCentre[0] + (e.getX() - downX) / getWidth(),
                downCentre[1] + (e.getY() - downY) / getHeight(), viewTurn);
        centreX = Math.max(0, Math.min(1, p[0]));
        centreY = Math.max(0, Math.min(1, p[1]));
        keepInside();
    }

    @Override
    public boolean performClick() {
        return super.performClick(); // lets accessibility tools see the touch
    }
}
