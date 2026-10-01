package com.castoff;

import android.os.Build;
import android.os.IBinder;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

public class CastOffCli {
    private static final String STATE_FILE = "/data/local/tmp/castoff.state";
    private static final String PID_FILE = "/data/local/tmp/castoff.pid";
    private static final String WAKELOCK_TAG = "CastOffWakeLock";

    private static Class<?> displayControlClass;
    private static IBinder displayToken;
    private static Method setDisplayPowerModeMethod;

    public static void main(String[] args) {
        String action = args.length > 0 ? args[0].toLowerCase() : "toggle";

        try {
            switch (action) {
                case "off":
                    turnScreenOff();
                    break;
                case "on":
                    turnScreenOn();
                    break;
                case "toggle":
                    toggleScreen();
                    break;
                case "status":
                case "state":
                    printStatus();
                    break;
                case "test":
                    int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 5;
                    testBlank(seconds);
                    break;
                case "daemon":
                    runDaemon();
                    break;
                case "dual-status":
                    printDualStatus();
                    break;
                case "launch-on-sc2":
                    if (args.length > 1) {
                        launchOnSc2(args[1]);
                    } else {
                        System.out.println("Usage: castoff launch-on-sc2 <package/activity>");
                    }
                    break;
                case "move-to-sc2":
                    if (args.length > 1) {
                        moveToSc2(args[1]);
                    } else {
                        System.out.println("Usage: castoff move-to-sc2 <package>");
                    }
                    break;
                default:
                    System.out.println("Usage: castoff [off|on|toggle|status|test [seconds]|daemon|dual-status|launch-on-sc2|move-to-sc2]");
                    break;
            }
        } catch (Throwable t) {
            System.err.println("CastOff error: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static synchronized void initDisplay() throws Exception {
        if (displayToken != null && setDisplayPowerModeMethod != null) {
            return;
        }

        // 1. Bypass hidden API restrictions
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntimeMethod = vmRuntimeClass.getDeclaredMethod("getRuntime");
            Method setHiddenApiExemptionsMethod = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            Object vmRuntime = getRuntimeMethod.invoke(null);
            setHiddenApiExemptionsMethod.invoke(vmRuntime, (Object) new String[]{"L"});
        } catch (Throwable ignored) {}

        Class<?> surfaceControlClass = Class.forName("android.view.SurfaceControl");

        // 2. Android 14+ (API >= 34): DisplayControl via services.jar
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                dalvik.system.PathClassLoader classLoader = new dalvik.system.PathClassLoader(
                    "/system/framework/services.jar",
                    ClassLoader.getSystemClassLoader()
                );
                displayControlClass = classLoader.loadClass("com.android.server.display.DisplayControl");

                Method loadLibraryMethod = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
                loadLibraryMethod.setAccessible(true);
                loadLibraryMethod.invoke(Runtime.getRuntime(), displayControlClass, "android_servers");

                Method getPhysicalDisplayIdsMethod = displayControlClass.getMethod("getPhysicalDisplayIds");
                long[] ids = (long[]) getPhysicalDisplayIdsMethod.invoke(null);
                if (ids != null && ids.length > 0) {
                    Method getPhysicalDisplayTokenMethod = displayControlClass.getMethod("getPhysicalDisplayToken", long.class);
                    displayToken = (IBinder) getPhysicalDisplayTokenMethod.invoke(null, ids[0]);
                }
            } catch (Throwable ignored) {}
        }

        // 3. Android 10 - 13 (API 29 - 33): SurfaceControl.getInternalDisplayToken()
        if (displayToken == null) {
            try {
                Method m = surfaceControlClass.getMethod("getInternalDisplayToken");
                displayToken = (IBinder) m.invoke(null);
            } catch (Throwable ignored) {}
        }

        // 4. Android 10 - 13 alternative: SurfaceControl.getPhysicalDisplayToken(id)
        if (displayToken == null) {
            try {
                Method getIds = surfaceControlClass.getMethod("getPhysicalDisplayIds");
                long[] ids = (long[]) getIds.invoke(null);
                if (ids != null && ids.length > 0) {
                    Method getToken = surfaceControlClass.getMethod("getPhysicalDisplayToken", long.class);
                    displayToken = (IBinder) getToken.invoke(null, ids[0]);
                }
            } catch (Throwable ignored) {}
        }

        // 5. Android 5 - 9 (API 21 - 28): SurfaceControl.getBuiltInDisplay(0)
        if (displayToken == null) {
            try {
                Method m = surfaceControlClass.getMethod("getBuiltInDisplay", int.class);
                displayToken = (IBinder) m.invoke(null, 0);
            } catch (Throwable ignored) {}
        }

        if (displayToken == null) {
            throw new IllegalStateException("Failed to obtain physical display token for SDK " + Build.VERSION.SDK_INT);
        }

        // 6. Resolve SurfaceControl.setDisplayPowerMode
        setDisplayPowerModeMethod = surfaceControlClass.getMethod("setDisplayPowerMode", IBinder.class, int.class);
    }

    private static void setDisplayPowerMode(int mode) throws Exception {
        initDisplay();
        setDisplayPowerModeMethod.invoke(null, displayToken, mode);
    }

    private static void collapseStatusBar() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"cmd", "statusbar", "collapse"});
            p.waitFor();
            Thread.sleep(450); // Allow shade collapse animation to cleanly finish
        } catch (Throwable ignored) {}
    }

    private static final String BRIGHTNESS_BACKUP = "/data/local/tmp/castoff_brightness.bak";

    private static void setBacklightOff() {
        try {
            File blDir = new File("/sys/class/backlight");
            if (!blDir.exists() || !blDir.isDirectory()) return;
            File[] drivers = blDir.listFiles();
            if (drivers == null) return;

            int savedBrightness = -1;
            for (File driver : drivers) {
                File brFile = new File(driver, "brightness");
                if (brFile.exists() && brFile.canRead()) {
                    if (savedBrightness < 0) {
                        try {
                            BufferedReader br = new BufferedReader(new FileReader(brFile));
                            String line = br.readLine();
                            br.close();
                            if (line != null && !line.trim().isEmpty()) {
                                savedBrightness = Integer.parseInt(line.trim());
                            }
                        } catch (Throwable ignored) {}
                    }
                    try {
                        FileWriter fw = new FileWriter(brFile);
                        fw.write("0\n");
                        fw.close();
                    } catch (Throwable ignored) {}
                }
            }
            if (savedBrightness > 0) {
                try {
                    FileWriter fw = new FileWriter(BRIGHTNESS_BACKUP);
                    fw.write(savedBrightness + "\n");
                    fw.close();
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void restoreBacklight() {
        try {
            int restoreValue = 871; // Default fallback for Moto G57
            File bak = new File(BRIGHTNESS_BACKUP);
            if (bak.exists()) {
                try {
                    BufferedReader br = new BufferedReader(new FileReader(bak));
                    String line = br.readLine();
                    br.close();
                    if (line != null && !line.trim().isEmpty()) {
                        restoreValue = Integer.parseInt(line.trim());
                    }
                } catch (Throwable ignored) {}
                bak.delete();
            }

            File blDir = new File("/sys/class/backlight");
            if (blDir.exists() && blDir.isDirectory()) {
                File[] drivers = blDir.listFiles();
                if (drivers != null) {
                    for (File driver : drivers) {
                        File brFile = new File(driver, "brightness");
                        if (brFile.exists()) {
                            try {
                                FileWriter fw = new FileWriter(brFile);
                                fw.write(restoreValue + "\n");
                                fw.close();
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void acquireWakeLock() {
        try {
            FileOutputStream fos = new FileOutputStream("/sys/power/wake_lock");
            fos.write(WAKELOCK_TAG.getBytes());
            fos.close();
        } catch (Throwable ignored) {}
    }

    private static void releaseWakeLock() {
        try {
            FileOutputStream fos = new FileOutputStream("/sys/power/wake_unlock");
            fos.write(WAKELOCK_TAG.getBytes());
            fos.close();
        } catch (Throwable ignored) {}
    }

    private static void broadcastAction(String action) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "am", "broadcast", "-a", action, "-p", "com.castoff"
            });
            p.waitFor();
        } catch (Throwable ignored) {}
    }

    private static String findVolumeUpEventDevice() {
        String bestDev = "/dev/input/event0";
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getevent", "-lp"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            String currentDev = null;
            String currentName = "";
            boolean hasVolumeUp = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("add device") && line.contains("/dev/input/")) {
                    int idx = line.indexOf("/dev/input/");
                    currentDev = line.substring(idx).trim();
                    currentName = "";
                    hasVolumeUp = false;
                } else if (line.startsWith("name:")) {
                    currentName = line.toLowerCase();
                } else if (line.contains("KEY_VOLUMEUP")) {
                    hasVolumeUp = true;
                    if (currentName.contains("gpio") || currentName.contains("key")) {
                        bestDev = currentDev;
                        break;
                    } else if (bestDev.equals("/dev/input/event0")) {
                        bestDev = currentDev;
                    }
                }
            }
            reader.close();
            p.destroy();
        } catch (Throwable ignored) {}
        return bestDev;
    }

    private static void writeState(String state) {
        try {
            FileWriter fw = new FileWriter(STATE_FILE);
            fw.write(state + "\n");
            fw.close();
        } catch (Throwable ignored) {}
    }

    private static String readState() {
        try {
            File f = new File(STATE_FILE);
            if (!f.exists()) return "ON";
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line = br.readLine();
            br.close();
            return line != null ? line.trim() : "ON";
        } catch (Throwable ignored) {
            return "ON";
        }
    }

    private static void killExistingDaemon() {
        try {
            File f = new File(PID_FILE);
            if (f.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(f));
                String pid = br.readLine();
                br.close();
                if (pid != null && !pid.trim().isEmpty()) {
                    Runtime.getRuntime().exec(new String[]{"kill", "-9", pid.trim()}).waitFor();
                }
                f.delete();
            }
        } catch (Throwable ignored) {}
    }

    private static void turnScreenOff() throws Exception {
        killExistingDaemon();

        // 1. Collapse shade & wait for animation
        collapseStatusBar();

        // 2. Acquire wake lock
        acquireWakeLock();

        // 3. Set display power mode to OFF (0) & zero backlight
        setDisplayPowerMode(0);
        setBacklightOff();
        writeState("OFF");
        broadcastAction("com.castoff.ACTION_SCREEN_OFF");
        System.out.println("CastOff: Physical display blanked (composition remains active).");

        // 4. Fork/start background listener for double Volume-Up
        String cp = System.getenv("CLASSPATH");
        if (cp == null || cp.isEmpty()) {
            cp = System.getProperty("java.class.path");
        }
        Process p = Runtime.getRuntime().exec(new String[]{
            "/system/bin/sh", "-c", "CLASSPATH=\"" + cp + "\" exec app_process /system/bin com.castoff.CastOffCli daemon >/data/local/tmp/castoff_daemon.log 2>&1 &"
        });
        p.waitFor();
    }

    private static void turnScreenOn() throws Exception {
        killExistingDaemon();

        // 1. Restore display power mode to NORMAL (2) & restore backlight
        setDisplayPowerMode(2);
        restoreBacklight();
        releaseWakeLock();
        writeState("ON");
        broadcastAction("com.castoff.ACTION_SCREEN_ON");
        System.out.println("CastOff: Physical display turned ON.");
    }

    private static void toggleScreen() throws Exception {
        String state = readState();
        if ("OFF".equalsIgnoreCase(state)) {
            turnScreenOn();
        } else {
            turnScreenOff();
        }
    }

    private static void printStatus() {
        String state = readState();
        System.out.println("CastOff Status: " + state);
    }

    private static final String DUAL_STATE_FILE = "/data/local/tmp/castoff_dual.state";

    private static void printDualStatus() {
        try {
            File f = new File(DUAL_STATE_FILE);
            if (!f.exists()) {
                System.out.println("Dual Display: OFF");
                return;
            }
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line = br.readLine();
            br.close();
            System.out.println("Dual Display: " + (line != null ? line.trim() : "OFF"));
        } catch (Throwable ignored) {
            System.out.println("Dual Display: OFF");
        }
    }

    private static String getSc2DisplayId() {
        try {
            File f = new File(DUAL_STATE_FILE);
            if (!f.exists()) return null;
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line = br.readLine();
            br.close();
            if (line != null && line.contains("Display #")) {
                return line.split("Display #")[1].trim();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void launchOnSc2(String component) {
        String displayId = getSc2DisplayId();
        if (displayId == null) {
            System.out.println("Error: No secondary display active.");
            return;
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "am", "start", "--display", displayId, "-n", component
            });
            p.waitFor();
            System.out.println("Launched " + component + " on display " + displayId);
        } catch (Throwable t) {
            System.out.println("Error launching: " + t.getMessage());
        }
    }

    private static void moveToSc2(String packageName) {
        String displayId = getSc2DisplayId();
        if (displayId == null) {
            System.out.println("Error: No secondary display active.");
            return;
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "cmd", "package", "resolve-activity", "--brief", packageName
            });
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            String component = null;
            while ((line = reader.readLine()) != null) {
                if (line.contains("/")) {
                    component = line.trim();
                    break;
                }
            }
            reader.close();
            p.waitFor();

            if (component != null) {
                launchOnSc2(component);
            } else {
                System.out.println("Error: Could not resolve activity for " + packageName);
            }
        } catch (Throwable t) {
            System.out.println("Error moving: " + t.getMessage());
        }
    }

    private static void testBlank(int seconds) throws Exception {
        System.out.println("CastOff Test: Blanking screen for " + seconds + " seconds...");
        System.out.println("Double-press Volume Up at any time to exit early.");

        collapseStatusBar();
        acquireWakeLock();
        setDisplayPowerMode(0);
        setBacklightOff();
        writeState("OFF");
        broadcastAction("com.castoff.ACTION_SCREEN_OFF");

        final AtomicBoolean finished = new AtomicBoolean(false);
        final String eventDev = findVolumeUpEventDevice();
        Thread listenerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(eventDev);
                    byte[] buf = new byte[24];
                    ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                    long lastPress = 0;

                    while (!finished.get() && fis.read(buf) == 24) {
                        bb.rewind();
                        bb.getLong(); // sec
                        bb.getLong(); // usec
                        short type = bb.getShort();
                        short code = bb.getShort();
                        int value = bb.getInt();

                        if (type == 1 && code == 115 && value == 1) { // EV_KEY KEY_VOLUMEUP DOWN
                            long now = System.currentTimeMillis();
                            long diff = now - lastPress;
                            if (diff > 50 && diff < 900) {
                                System.out.println("Double Volume-Up detected! Restoring screen early...");
                                finished.set(true);
                                break;
                            }
                            lastPress = now;
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (fis != null) {
                        try { fis.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        });
        listenerThread.start();

        long startTime = System.currentTimeMillis();
        long endTime = startTime + (seconds * 1000L);
        while (System.currentTimeMillis() < endTime && !finished.get()) {
            Thread.sleep(100);
        }
        finished.set(true);
        listenerThread.interrupt();

        setDisplayPowerMode(2);
        restoreBacklight();
        releaseWakeLock();
        writeState("ON");
        broadcastAction("com.castoff.ACTION_SCREEN_ON");
        System.out.println("CastOff Test completed. Screen restored to NORMAL.");
    }

    private static void runDaemon() {
        try {
            // Write our PID
            int myPid = android.os.Process.myPid();
            FileWriter fw = new FileWriter(PID_FILE);
            fw.write(myPid + "\n");
            fw.close();

            String eventDev = findVolumeUpEventDevice();
            System.out.println("Daemon started with PID " + myPid + ", listening on " + eventDev);
            FileInputStream fis = new FileInputStream(eventDev);
            byte[] buf = new byte[24];
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            long lastPress = 0;

            while (fis.read(buf) == 24) {
                bb.rewind();
                bb.getLong(); // sec
                bb.getLong(); // usec
                short type = bb.getShort();
                short code = bb.getShort();
                int value = bb.getInt();

                if (type == 1 && code == 115 && value == 1) { // EV_KEY KEY_VOLUMEUP DOWN
                    long now = System.currentTimeMillis();
                    long diff = now - lastPress;
                    System.out.println("Volume Up detected, diff=" + diff + "ms");
                    if (diff > 50 && diff < 900) {
                        System.out.println("Double press detected! Restoring display...");
                        setDisplayPowerMode(2);
                        restoreBacklight();
                        releaseWakeLock();
                        writeState("ON");
                        broadcastAction("com.castoff.ACTION_SCREEN_ON");
                        System.out.println("Display restored, daemon exiting.");
                        break;
                    }
                    lastPress = now;
                }
            }
            fis.close();
        } catch (Throwable t) {
            System.err.println("Daemon caught exception:");
            t.printStackTrace();
            // Restore screen on abnormal crash
            try {
                setDisplayPowerMode(2);
                restoreBacklight();
                releaseWakeLock();
                writeState("ON");
                broadcastAction("com.castoff.ACTION_SCREEN_ON");
            } catch (Throwable ignored) {}
        } finally {
            new File(PID_FILE).delete();
        }
    }
}
