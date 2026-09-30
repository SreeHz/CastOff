package com.castoff;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.quicksettings.TileService;

public class CastOffReceiver extends BroadcastReceiver {
    public static final String ACTION_SCREEN_ON = "com.castoff.ACTION_SCREEN_ON";
    public static final String ACTION_SCREEN_OFF = "com.castoff.ACTION_SCREEN_OFF";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;

        if (ACTION_SCREEN_ON.equals(action)) {
            // Screen was woken up (e.g. by double-press Volume Up)
            context.stopService(new Intent(context, TouchBlockerService.class));
            TileService.requestListeningState(context, new ComponentName(context, CastOffTileService.class));
        } else if (ACTION_SCREEN_OFF.equals(action)) {
            // Screen was blanked
            context.startService(new Intent(context, TouchBlockerService.class));
            TileService.requestListeningState(context, new ComponentName(context, CastOffTileService.class));
        }
    }
}
