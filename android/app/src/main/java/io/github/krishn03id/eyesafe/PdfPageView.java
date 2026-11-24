package io.github.krishn03id.eyesafe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

final class PdfPageView extends View {
    interface Listener {
        void turn(int delta);
        void tapped();
        void zoomed(float scale);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final GestureDetector gestures;
    private final ScaleGestureDetector pinch;
    private final Listener listener;
    private Bitmap bitmap;
    private float zoom = 1f, panX, panY;
    private float downX, downY;
    private boolean scaling;
    private final int padding;

    PdfPageView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        padding = Math.round(12 * getResources().getDisplayMetrics().density);
        setContentDescription("PDF page. Pinch to zoom, drag to pan, swipe to turn pages. Use Text mode to select passages.");
        setFocusable(true);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) { performClick(); listener.tapped(); return true; }
            @Override public boolean onDoubleTap(MotionEvent event) {
                zoom = zoom > 1.2f ? 1f : 2f;
                panX = panY = 0;
                invalidate();
                listener.zoomed(zoom);
                return true;
            }
            @Override public boolean onScroll(MotionEvent first, MotionEvent second, float dx, float dy) {
                if (scaling) return true;
                panX -= dx; panY -= dy; clamp(); invalidate(); return true;
            }
            @Override public boolean onFling(MotionEvent first, MotionEvent second, float vx, float vy) {
                if (first == null || scaling || zoom > 1.05f) return false;
                float dx = second.getX() - first.getX();
                float dy = second.getY() - first.getY();
                if (Math.abs(dx) > padding * 5 && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    listener.turn(dx < 0 ? 1 : -1); return true;
                }
                return false;
            }
        });
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) { scaling = true; return true; }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoom = Math.max(.85f, Math.min(3f, zoom * detector.getScaleFactor()));
                clamp(); invalidate(); return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector detector) {
                scaling = false; listener.zoomed(zoom);
            }
        });
    }

    void bitmap(Bitmap value, boolean reset) {
        Bitmap old = bitmap;
        bitmap = value;
        if (reset) { zoom = 1f; panX = panY = 0; }
        clamp();
        invalidate();
        if (old != null && old != value && !old.isRecycled()) old.recycle();
    }

    void clear() {
        Bitmap old = bitmap;
        bitmap = null;
        if (old != null && !old.isRecycled()) old.recycle();
    }

    float zoom() { return zoom; }

    void theme(String theme) {
        if ("dark".equals(theme) || "oled".equals(theme)) {
            float brightness = "oled".equals(theme) ? 205 : 225;
            paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                    -.88f,0,0,0,brightness, 0,-.88f,0,0,brightness, 0,0,-.88f,0,brightness, 0,0,0,1,0
            })));
        } else if ("sepia".equals(theme)) {
            paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                    .8f,.15f,.05f,0,0, .08f,.8f,.05f,0,0, .05f,.1f,.65f,0,0, 0,0,0,1,0
            })));
        } else paint.setColorFilter(null);
        invalidate();
    }

    private float fit() { return bitmap == null ? 1 : Math.max(1, getWidth() - padding * 2f) / bitmap.getWidth(); }

    private void clamp() {
        if (bitmap == null) return;
        float width = bitmap.getWidth() * fit() * zoom;
        float height = bitmap.getHeight() * fit() * zoom;
        float horizontal = Math.max(0, (width - getWidth() + padding * 2) / 2);
        panX = Math.max(-horizontal, Math.min(horizontal, panX));
        panY = Math.max(Math.min(0, getHeight() - height - padding * 2), Math.min(0, panY));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || bitmap.isRecycled()) return;
        float scale = fit() * zoom;
        float height = bitmap.getHeight() * scale;
        canvas.save();
        canvas.translate((getWidth() - bitmap.getWidth() * scale) / 2 + panX,
                (height < getHeight() ? (getHeight() - height) / 2 : padding) + panY);
        canvas.scale(scale, scale);
        canvas.drawBitmap(bitmap, 0, 0, paint);
        canvas.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        pinch.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = event.getX(); downY = event.getY(); }
        if (!pinch.isInProgress() && !scaling) gestures.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(event.getX() - downX) < 4 && Math.abs(event.getY() - downY) < 4) clamp();
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
