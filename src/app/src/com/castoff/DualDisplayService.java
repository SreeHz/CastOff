package com.castoff;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.view.Display;
import java.io.FileWriter;
import java.io.IOException;

public class DualDisplayService extends Service implements DisplayManager.DisplayListener {

    private static final String CHANNEL_ID = "dual_display_channel";
    private static final int NOTIF_ID = 2001;
    public static final String ACTION_DUAL_DISPLAY_CONNECTED = "com.castoff.ACTION_DUAL_DISPLAY_CONNECTED";
    public static final String ACTION_DUAL_DISPLAY_DISCONNECTED = "com.castoff.ACTION_DUAL_DISPLAY_DISCONNECTED";

    private DisplayManager displayManager;
    private SC2PresentationBridge presentationBridge;
    private int secondaryDisplayId = -1;
    private boolean isActive = false;

    @Override
    public void onCreate() {
        super.onCreate();
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startDualMode();
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Dual Display Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        int titleId = getResources().getIdentifier("dual_display_notification_title", "string", getPackageName());
        int textId = getResources().getIdentifier("dual_display_notification_text", "string", getPackageName());
        int iconId = getResources().getIdentifier("ic_launcher", "drawable", getPackageName());

        return builder
                .setContentTitle(titleId != 0 ? getString(titleId) : "Dual Display Active")
                .setContentText(textId != 0 ? getString(textId) : "Second screen managed by CastOff")
                .setSmallIcon(iconId != 0 ? iconId : android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
    }

    public void startDualMode() {
        if (isActive) return;
        startForeground(NOTIF_ID, buildNotification());
        displayManager.registerDisplayListener(this, null);
        isActive = true;
        scanForSecondaryDisplay();
    }

    public void stopDualMode() {
        isActive = false;
        displayManager.unregisterDisplayListener(this);
        dismissPresentation();
        writeStateFile("INACTIVE");
        stopForeground(true);
        stopSelf();
    }

    public boolean isActive() {
        return isActive;
    }

    public int getSecondaryDisplayId() {
        return secondaryDisplayId;
    }

    private void scanForSecondaryDisplay() {
        Display[] displays = displayManager.getDisplays();
        for (Display display : displays) {
            if (isSecondaryDisplay(display)) {
                handleDisplayConnected(display);
                return;
            }
        }
    }

    private boolean isSecondaryDisplay(Display display) {
        if (display.getDisplayId() == Display.DEFAULT_DISPLAY) return false;
        
        // Check for Presentation or WiFi displays
        if (display.isValid()) {
            return true; // Simplify for now, any non-default display is considered secondary
        }
        return false;
    }

    private void handleDisplayConnected(Display display) {
        if (presentationBridge != null && secondaryDisplayId == display.getDisplayId()) {
            return; // Already handling this display
        }
        
        dismissPresentation();
        secondaryDisplayId = display.getDisplayId();
        
        presentationBridge = new SC2PresentationBridge(this, display);
        presentationBridge.setPresentationListener(new SC2PresentationBridge.PresentationListener() {
            @Override
            public void onDisplayRemoved() {
                handleDisplayDisconnected();
            }
        });
        
        try {
            presentationBridge.show();
            writeStateFile("ACTIVE:" + secondaryDisplayId);
            sendBroadcast(new Intent(ACTION_DUAL_DISPLAY_CONNECTED));
        } catch (Exception e) {
            e.printStackTrace();
            handleDisplayDisconnected();
        }
    }

    private void handleDisplayDisconnected() {
        dismissPresentation();
        secondaryDisplayId = -1;
        writeStateFile("INACTIVE");
        sendBroadcast(new Intent(ACTION_DUAL_DISPLAY_DISCONNECTED));
    }

    private void dismissPresentation() {
        if (presentationBridge != null) {
            try {
                presentationBridge.dismiss();
            } catch (Exception e) {
                // Ignore
            }
            presentationBridge = null;
        }
    }

    private void writeStateFile(String content) {
        try {
            FileWriter writer = new FileWriter("/data/local/tmp/castoff_dual.state");
            writer.write(content);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
        if (!isActive) return;
        Display display = displayManager.getDisplay(displayId);
        if (display != null && isSecondaryDisplay(display)) {
            handleDisplayConnected(display);
        }
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        if (displayId == secondaryDisplayId) {
            handleDisplayDisconnected();
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {
        // Handle changes if needed
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopDualMode();
        super.onDestroy();
    }
}
