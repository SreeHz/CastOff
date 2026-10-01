package com.castoff;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class MainActivity extends Activity {
    private static final String STATE_FILE = "/data/local/tmp/castoff.state";
    private static final String DUAL_STATE_FILE = "/data/local/tmp/castoff_dual.state";
    private TextView tvStatus;
    private Button btnTest;
    private Button btnToggle;
    private TextView tvDualStatus;
    private Button btnStartDual;
    private Button btnStopDual;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateStatusUi();
            updateDualStatusUi();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        btnTest = findViewById(R.id.btn_test);
        btnToggle = findViewById(R.id.btn_toggle);
        tvDualStatus = findViewById(R.id.tv_dual_status);
        btnStartDual = findViewById(R.id.btn_start_dual);
        btnStopDual = findViewById(R.id.btn_stop_dual);

        // Check overlay permission if not granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Throwable ignored) {}
        }

        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Blanking screen for 5 seconds...\nDouble-press Vol-Up to wake", Toast.LENGTH_LONG).show();
                executeRootCommand("castoff test 5");
            }
        });

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                executeRootCommand("castoff toggle");
            }
        });

        btnStartDual.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(new Intent(MainActivity.this, DualDisplayService.class));
                } else {
                    startService(new Intent(MainActivity.this, DualDisplayService.class));
                }
                startService(new Intent(MainActivity.this, DisplayControllerOverlay.class));
            }
        });

        btnStopDual.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, DualDisplayService.class));
                stopService(new Intent(MainActivity.this, DisplayControllerOverlay.class));
            }
        });

        // Prompt to add Quick Settings tile if on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                android.app.StatusBarManager sbm = getSystemService(android.app.StatusBarManager.class);
                if (sbm != null) {
                    sbm.requestAddTileService(
                        new android.content.ComponentName(this, CastOffTileService.class),
                        getString(R.string.tile_label),
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_cast_screen_off),
                        getMainExecutor(),
                        new java.util.function.Consumer<Integer>() {
                            @Override
                            public void accept(Integer result) {}
                        }
                    );
                }
            } catch (Throwable ignored) {}
        }

        updateStatusUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusUi();
        updateDualStatusUi();
        IntentFilter filter = new IntentFilter();
        filter.addAction(CastOffReceiver.ACTION_SCREEN_ON);
        filter.addAction(CastOffReceiver.ACTION_SCREEN_OFF);
        filter.addAction("com.castoff.ACTION_DUAL_DISPLAY_CONNECTED");
        filter.addAction("com.castoff.ACTION_DUAL_DISPLAY_DISCONNECTED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(stateReceiver);
        } catch (Throwable ignored) {}
    }

    private void updateStatusUi() {
        boolean blanked = false;
        try {
            File f = new File(STATE_FILE);
            if (f.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(f));
                String line = br.readLine();
                br.close();
                blanked = "OFF".equalsIgnoreCase(line != null ? line.trim() : "");
            }
        } catch (Throwable ignored) {}

        if (blanked) {
            tvStatus.setText("Display Blanked (Active Casting)");
            tvStatus.setTextColor(Color.parseColor("#FF2196F3"));
            btnToggle.setText("Wake Screen Up");
        } else {
            tvStatus.setText("Normal (Display Active)");
            tvStatus.setTextColor(Color.parseColor("#FF4CAF50"));
            btnToggle.setText(getString(R.string.btn_toggle));
        }
    }

    private void updateDualStatusUi() {
        boolean active = false;
        String statusText = "OFF";
        try {
            File f = new File(DUAL_STATE_FILE);
            if (f.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(f));
                String line = br.readLine();
                br.close();
                if (line != null && line.contains("Display #")) {
                    active = true;
                    statusText = line.trim();
                }
            }
        } catch (Throwable ignored) {}

        if (active) {
            if (tvDualStatus != null) {
                tvDualStatus.setText(statusText);
                tvDualStatus.setTextColor(Color.parseColor("#FF4CAF50"));
            }
        } else {
            if (tvDualStatus != null) {
                tvDualStatus.setText(getString(R.string.dual_no_display));
                tvDualStatus.setTextColor(Color.parseColor("#FFFF9800"));
            }
        }
    }

    private void executeRootCommand(final String cmd) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{
                        "su", "-c", cmd + " || /data/local/tmp/" + cmd
                    });
                    p.waitFor();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateStatusUi();
                        }
                    });
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }).start();
    }
}
