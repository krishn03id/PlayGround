package io.github.krishn03id.eyesafe;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ScrollView;

final class ComfortScrollView extends ScrollView {
    interface FontScale { void changed(float scale); }
    private final ScaleGestureDetector scale;
    private boolean pinching;

    ComfortScrollView(Context context, FontScale listener) {
        super(context);
        setFillViewport(true);
        scale = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) { pinching = true; return true; }
            @Override public boolean onScale(ScaleGestureDetector detector) { listener.changed(detector.getScaleFactor()); return true; }
        });
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getPointerCount() > 1) { pinching = true; return true; }
        return pinching || super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scale.onTouchEvent(event);
        if (pinching) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) pinching = false;
            return true;
        }
        return super.onTouchEvent(event);
    }
}
