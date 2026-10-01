package com.castoff;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import java.io.BufferedReader;
import java.io.FileReader;

public class DisplayControllerOverlay extends Service {

    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams params;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        }

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 100;

        int layoutId = getResources().getIdentifier("overlay_controller", "layout", getPackageName());
        LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        if (layoutId == 0) return;
        
        overlayView = inflater.inflate(layoutId, null);
        
        View bubbleIcon = overlayView.findViewById(getResources().getIdentifier("bubble_icon", "id", getPackageName()));
        final View panelContainer = overlayView.findViewById(getResources().getIdentifier("panel_container", "id", getPackageName()));
        
        View btnSwitch = overlayView.findViewById(getResources().getIdentifier("btn_switch_sc2", "id", getPackageName()));
        View btnMoveApp = overlayView.findViewById(getResources().getIdentifier("btn_move_app", "id", getPackageName()));
        View btnBlank = overlayView.findViewById(getResources().getIdentifier("btn_blank_phone", "id", getPackageName()));
        View btnStop = overlayView.findViewById(getResources().getIdentifier("btn_stop_dual", "id", getPackageName()));

        bubbleIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (panelContainer.getVisibility() == View.VISIBLE) {
                    panelContainer.setVisibility(View.GONE);
                } else {
                    panelContainer.setVisibility(View.VISIBLE);
                }
            }
        });

        bubbleIcon.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(overlayView, params);
                        return true;
                }
                return false;
            }
        });

        btnSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Implement switch to SC2 view
                panelContainer.setVisibility(View.GONE);
            }
        });

        btnMoveApp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int displayId = getActiveDisplayId();
                if (displayId != -1) {
                    // Launch an app picker or move current on that display
                    // Simple am command example to bring up recent apps or picker
                    runAsRoot("am start -a android.intent.action.MAIN -c android.intent.category.HOME --display " + displayId);
                }
                panelContainer.setVisibility(View.GONE);
            }
        });

        btnBlank.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runAsRoot("castoff off");
                panelContainer.setVisibility(View.GONE);
            }
        });

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent stopIntent = new Intent(DisplayControllerOverlay.this, DualDisplayService.class);
                stopService(stopIntent);
                stopSelf();
            }
        });

        windowManager.addView(overlayView, params);
    }

    private int getActiveDisplayId() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/data/local/tmp/castoff_dual.state"));
            String line = reader.readLine();
            reader.close();
            if (line != null && line.startsWith("ACTIVE:")) {
                return Integer.parseInt(line.substring(7).trim());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    private void runAsRoot(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayView != null) {
            windowManager.removeView(overlayView);
        }
    }
}
