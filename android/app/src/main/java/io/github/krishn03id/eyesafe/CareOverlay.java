package io.github.krishn03id.eyesafe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

final class CareOverlay extends View {
    private final AppSettings settings;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean ruler;
    private float rulerCenter = .4f;
    private boolean dragging;

    CareOverlay(Context context, AppSettings settings, boolean ruler) {
        super(context);
        this.settings = settings;
        this.ruler = ruler;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (ruler) {
            if (!settings.flag("ruler", false)) return;
            float center = getHeight() * rulerCenter;
            float band = 100 * getResources().getDisplayMetrics().density;
            paint.setColor(0x80000000);
            canvas.drawRect(0, 0, getWidth(), center - band / 2, paint);
            canvas.drawRect(0, center + band / 2, getWidth(), getHeight(), paint);
            paint.setColor(0xBBD99B52);
            canvas.drawRect(0, center - band / 2, getWidth(), center - band / 2 + 2, paint);
            canvas.drawRect(0, center + band / 2 - 2, getWidth(), center + band / 2, paint);
            canvas.drawCircle(getWidth() - 20 * getResources().getDisplayMetrics().density, center, 12 * getResources().getDisplayMetrics().density, paint);
        } else {
            int warmth = Math.max(0, Math.min(60, settings.number("warmth", 20)));
            int dimmer = Math.max(0, Math.min(65, settings.number("dimmer", 0)));
            canvas.drawColor(Color.argb(warmth * 2, 255, 156, 51));
            canvas.drawColor(Color.argb((int)(dimmer * 2.55f), 0, 0, 0));
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!ruler || !settings.flag("ruler", false)) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            dragging = event.getX() > getWidth() - 48 * getResources().getDisplayMetrics().density
                    && Math.abs(event.getY() - getHeight() * rulerCenter) < 36 * getResources().getDisplayMetrics().density;
            return dragging;
        }
        if (!dragging) return false;
        rulerCenter = Math.max(.15f, Math.min(.85f, event.getY() / Math.max(1, getHeight())));
        invalidate();
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) { dragging = false; performClick(); }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
