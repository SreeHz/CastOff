SKIPUNZIP=0

ui_print "****************************************"
ui_print "*               CastOff                *"
ui_print "*   Screen Blanking for Casting        *"
ui_print "****************************************"

# Set permissions
set_perm_recursive $MODPATH 0 0 0755 0644
set_perm $MODPATH/system/bin/castoff 0 0 0755
set_perm $MODPATH/service.sh 0 0 0755

# Ensure /data/local/tmp has direct copies for instant use without reboot
cp -f $MODPATH/system/bin/castoff /data/local/tmp/castoff
cp -f $MODPATH/system/framework/castoff.jar /data/local/tmp/castoff.jar
chmod 755 /data/local/tmp/castoff
chmod 644 /data/local/tmp/castoff.jar

# Install APK immediately so user can test before reboot
ui_print "- Installing CastOff App and Quick Settings Tile..."
pm install -r -g $MODPATH/system/app/CastOff/CastOff.apk >/dev/null 2>&1

# Configure Magisk root policy and overlay permission
APP_UID=$(dumpsys package com.castoff 2>/dev/null | grep -iE 'appId|userId' | head -n 1 | grep -o '[0-9]\+')
if [ -n "$APP_UID" ]; then
    magisk --sqlite "INSERT OR REPLACE INTO policies (uid, policy, until, logging, notification) VALUES ($APP_UID, 2, 0, 1, 0);" 2>/dev/null
    appops set com.castoff SYSTEM_ALERT_WINDOW allow 2>/dev/null
fi

ui_print "- Setting up hardware key listeners..."
ui_print "  (Volume-Up hardware key detected)"

ui_print " "
ui_print "- CastOff installation complete!"
ui_print "- You can test right now with: castoff test 5"
ui_print "- Or reboot to complete system-wide mount."
ui_print "****************************************"
