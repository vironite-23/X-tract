package com.vironite.retrievex.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewParent;

import androidx.appcompat.widget.AppCompatImageView;

/** ImageView with pinch-to-zoom, drag-to-pan and double-tap zoom. Hands horizontal swipes to its pager at the edges. */
public class ZoomImageView extends AppCompatImageView {
    public interface TapListener {
        void onTap();
    }

    private static final float MAX_ZOOM = 5f;   // relative to the fitted size
    private static final float DOUBLE_TAP_ZOOM = 2.5f;

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final RectF rect = new RectF();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float baseScale = 1f;
    private float lastX;
    private float lastY;
    private int activePointer = MotionEvent.INVALID_POINTER_ID;
    private boolean dragging;
    private ValueAnimator zoomAnim;
    private TapListener tapListener;

    public ZoomImageView(Context c) {
        this(c, null);
    }

    public ZoomImageView(Context c, AttributeSet attrs) {
        super(c, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                cancelAnim();
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (tapListener != null) tapListener.onTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (getDrawable() == null) return true;
                float rel = currentScale() / baseScale;
                animateTo(rel > 1.05f ? 1f : DOUBLE_TAP_ZOOM, e.getX(), e.getY());
                return true;
            }
        });
    }

    public void setTapListener(TapListener l) {
        this.tapListener = l;
    }

    @Override
    public void setImageDrawable(Drawable d) {
        super.setImageDrawable(d);
        fit();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit();
    }

    /** Resets the matrix so the whole image is visible and centered. */
    private void fit() {
        cancelAnim();
        Drawable d = getDrawable();
        int vw = getWidth();
        int vh = getHeight();
        if (d == null || vw == 0 || vh == 0) return;
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        baseScale = Math.min((float) vw / dw, (float) vh / dh);
        matrix.reset();
        matrix.postScale(baseScale, baseScale);
        matrix.postTranslate((vw - dw * baseScale) / 2f, (vh - dh * baseScale) / 2f);
        setImageMatrix(matrix);
    }

    private float currentScale() {
        matrix.getValues(values);
        return values[Matrix.MSCALE_X];
    }

    private RectF imageRect() {
        Drawable d = getDrawable();
        if (d == null) {
            rect.setEmpty();
            return rect;
        }
        rect.set(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(rect);
        return rect;
    }

    private void zoomBy(float factor, float px, float py) {
        float cur = currentScale();
        float target = cur * factor;
        float min = baseScale;
        float max = baseScale * MAX_ZOOM;
        if (target < min) factor = min / cur;
        else if (target > max) factor = max / cur;
        matrix.postScale(factor, factor, px, py);
        constrain();
        setImageMatrix(matrix);
    }

    /** Keeps the image from being dragged away from the view; centers it when it is smaller than the view. */
    private void constrain() {
        RectF r = imageRect();
        float dx = 0;
        float dy = 0;
        int vw = getWidth();
        int vh = getHeight();
        if (r.width() <= vw) dx = (vw - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < vw) dx = vw - r.right;
        if (r.height() <= vh) dy = (vh - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < vh) dy = vh - r.bottom;
        matrix.postTranslate(dx, dy);
    }

    private void animateTo(float relTarget, final float px, final float py) {
        cancelAnim();
        final float from = currentScale();
        final float to = baseScale * relTarget;
        zoomAnim = ValueAnimator.ofFloat(from, to);
        zoomAnim.setDuration(220);
        zoomAnim.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            zoomBy(v / currentScale(), px, py);
        });
        zoomAnim.start();
    }

    private void cancelAnim() {
        if (zoomAnim != null) {
            zoomAnim.cancel();
            zoomAnim = null;
        }
    }

    private boolean isZoomed() {
        return currentScale() > baseScale * 1.01f;
    }

    private void claimTouches(boolean claim) {
        ViewParent p = getParent();
        if (p != null) p.requestDisallowInterceptTouchEvent(claim);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (getDrawable() == null) return super.onTouchEvent(ev);
        scaleDetector.onTouchEvent(ev);
        gestureDetector.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePointer = ev.getPointerId(0);
                lastX = ev.getX();
                lastY = ev.getY();
                dragging = false;
                claimTouches(isZoomed());
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                claimTouches(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (scaleDetector.isInProgress()) {
                    claimTouches(true);
                    break;
                }
                int idx = ev.findPointerIndex(activePointer);
                if (idx < 0) break;
                float x = ev.getX(idx);
                float y = ev.getY(idx);
                float dx = x - lastX;
                float dy = y - lastY;
                lastX = x;
                lastY = y;
                if (isZoomed()) {
                    RectF r = imageRect();
                    boolean atLeft = r.left >= -0.5f;
                    boolean atRight = r.right <= getWidth() + 0.5f;
                    boolean wantsPager = !dragging && ((dx > 0 && atLeft) || (dx < 0 && atRight))
                            && Math.abs(dx) > Math.abs(dy);
                    if (wantsPager) {
                        claimTouches(false);
                    } else {
                        claimTouches(true);
                        dragging = true;
                        matrix.postTranslate(dx, dy);
                        constrain();
                        setImageMatrix(matrix);
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                int upIndex = ev.getActionIndex();
                if (ev.getPointerId(upIndex) == activePointer) {
                    int newIndex = upIndex == 0 ? 1 : 0;
                    activePointer = ev.getPointerId(newIndex);
                    lastX = ev.getX(newIndex);
                    lastY = ev.getY(newIndex);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePointer = MotionEvent.INVALID_POINTER_ID;
                dragging = false;
                claimTouches(false);
                break;
            default:
                break;
        }
        return true;
    }
}
