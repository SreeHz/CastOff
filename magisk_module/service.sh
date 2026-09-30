#!/system/bin/sh
MODDIR=${0%/*}

# Wait for Android boot completion
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

# Ensure permissions
chmod 755 $MODDIR/system/bin/castoff 2>/dev/null
chmod 644 $MODDIR/system/framework/castoff.jar 2>/dev/null

# Sync backup copies to /data/local/tmp
cp -f $MODDIR/system/bin/castoff /data/local/tmp/castoff 2>/dev/null
cp -f $MODDIR/system/framework/castoff.jar /data/local/tmp/castoff.jar 2>/dev/null
chmod 755 /data/local/tmp/castoff 2>/dev/null
chmod 644 /data/local/tmp/castoff.jar 2>/dev/null

# Automatically approve root access in Magisk for CastOff
APP_UID=$(dumpsys package com.castoff 2>/dev/null | grep -iE 'appId|userId' | head -n 1 | grep -o '[0-9]\+')
if [ -n "$APP_UID" ]; then
    magisk --sqlite "INSERT OR REPLACE INTO policies (uid, policy, until, logging, notification) VALUES ($APP_UID, 2, 0, 1, 0);" 2>/dev/null
    appops set com.castoff SYSTEM_ALERT_WINDOW allow 2>/dev/null
fi
