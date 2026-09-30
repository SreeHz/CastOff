package com.castoff;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

public class TouchBlockerService extends Service {
    private WindowManager windowManager;
    private View overlayView;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showOverlay();
        return START_STICKY;
    }

    private void showOverlay() {
        if (overlayView != null) return;

        try {
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) return;

            overlayView = new View(this) {
                @Override
                public boolean onTouchEvent(MotionEvent event) {
                    // Consume all touch events completely
                    return true;
                }
            };
            overlayView.setBackgroundColor(Color.TRANSPARENT);

            int layoutType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutType = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.LEFT;

            windowManager.addView(overlayView, params);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void hideOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Throwable ignored) {}
            overlayView = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        hideOverlay();
    }
}
