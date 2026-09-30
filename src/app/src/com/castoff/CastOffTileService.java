package com.castoff;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class CastOffTileService extends TileService {
    private static final String STATE_FILE = "/data/local/tmp/castoff.state";

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    private boolean isScreenBlanked() {
        try {
            File f = new File(STATE_FILE);
            if (f.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(f));
                String line = br.readLine();
                br.close();
                return "OFF".equalsIgnoreCase(line != null ? line.trim() : "");
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean blanked = isScreenBlanked();
        tile.setState(blanked ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_label));
        tile.setSubtitle(getString(blanked ? R.string.tile_active : R.string.tile_inactive));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_cast_screen_off));
        tile.updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean blanked = isScreenBlanked();
        if (blanked) {
            // Screen is blanked, restore it
            executeRootCommand("castoff on");
            stopService(new Intent(this, TouchBlockerService.class));
        } else {
            // Screen is active, turn it off
            startService(new Intent(this, TouchBlockerService.class));
            executeRootCommand("castoff off");
        }
        updateTileState();
    }

    private void executeRootCommand(final String cmd) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Try direct command or fallback to /data/local/tmp/castoff
                    Process p = Runtime.getRuntime().exec(new String[]{
                        "su", "-c", "castoff " + (cmd.contains("off") ? "off" : "on") + " || /data/local/tmp/castoff " + (cmd.contains("off") ? "off" : "on")
                    });
                    p.waitFor();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }).start();
    }
}
