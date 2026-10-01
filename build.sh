#!/usr/bin/env bash
# CastOff Build Script
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

if [ -z "$SDK_ROOT" ]; then
    if [ -d "$HOME/android_sdk" ]; then
        SDK_ROOT="$HOME/android_sdk"
    elif [ -d "$HOME/Android/Sdk" ]; then
        SDK_ROOT="$HOME/Android/Sdk"
    fi
fi

PLATFORM_JAR="$SDK_ROOT/platforms/android-34/android.jar"
if [ ! -f "$PLATFORM_JAR" ]; then
    PLATFORM_JAR="$(find "$SDK_ROOT/platforms" -name "android.jar" 2>/dev/null | sort -V | tail -n 1)"
fi

BUILD_TOOLS_DIR="$(find "$SDK_ROOT/build-tools" -maxdepth 1 -mindepth 1 2>/dev/null | sort -V | tail -n 1)"
AAPT2="$BUILD_TOOLS_DIR/aapt2"
D8="$BUILD_TOOLS_DIR/d8"
ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"

echo "=== CastOff Build System ==="
echo "SDK: $SDK_ROOT"
echo "Platform: $PLATFORM_JAR"
echo "Build Tools: $BUILD_TOOLS_DIR"

# Clean build directory
BUILD_DIR="$PROJECT_DIR/build"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/cli/com/castoff" "$BUILD_DIR/app/classes" "$BUILD_DIR/app/gen"

# 1. Compile CLI (castoff.jar & launcher script)
echo "--> Building CLI..."
javac -cp "$PLATFORM_JAR" -d "$BUILD_DIR/cli" "$PROJECT_DIR/src/cli/com/castoff/CastOffCli.java"
"$D8" --output "$BUILD_DIR/cli" "$BUILD_DIR/cli/com/castoff/"*.class
(cd "$BUILD_DIR/cli" && zip -j castoff.jar classes.dex)

cat << 'EOF' > "$BUILD_DIR/cli/castoff"
#!/system/bin/sh
# CastOff CLI Launcher
BASEDIR="/system/framework"
if [ ! -f "$BASEDIR/castoff.jar" ]; then
    BASEDIR="/data/local/tmp"
fi
export CLASSPATH="$BASEDIR/castoff.jar"
exec app_process /system/bin com.castoff.CastOffCli "$@"
EOF
chmod 755 "$BUILD_DIR/cli/castoff"

# 2. Compile Companion App (CastOff.apk)
echo "--> Building Companion App APK..."
"$AAPT2" compile --dir "$PROJECT_DIR/src/app/res" -o "$BUILD_DIR/app/compiled.zip"
"$AAPT2" link -I "$PLATFORM_JAR" \
    --manifest "$PROJECT_DIR/src/app/AndroidManifest.xml" \
    --java "$BUILD_DIR/app/gen" \
    -o "$BUILD_DIR/app/unaligned.apk" \
    "$BUILD_DIR/app/compiled.zip"

javac -cp "$PLATFORM_JAR" -d "$BUILD_DIR/app/classes" \
    "$BUILD_DIR/app/gen/com/castoff/R.java" \
    "$PROJECT_DIR/src/app/src/com/castoff/"*.java

"$D8" --output "$BUILD_DIR/app" "$BUILD_DIR/app/classes/com/castoff/"*.class

(cd "$BUILD_DIR/app" && zip -u unaligned.apk classes.dex)
"$ZIPALIGN" -f 4 "$BUILD_DIR/app/unaligned.apk" "$BUILD_DIR/app/CastOff.apk"

# Sign with debug key or generate one if needed
KEYSTORE="$BUILD_DIR/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -v -keystore "$KEYSTORE" -alias androiddebugkey \
        -keypass android -storepass android -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android "$BUILD_DIR/app/CastOff.apk"

# 3. Update Magisk Module Payload
echo "--> Updating Magisk module files..."
rm -rf "$PROJECT_DIR/magisk_module/system/app"
mkdir -p "$PROJECT_DIR/magisk_module/system/framework" \
         "$PROJECT_DIR/magisk_module/system/bin"

cp -f "$BUILD_DIR/cli/castoff.jar" "$PROJECT_DIR/magisk_module/system/framework/castoff.jar"
cp -f "$BUILD_DIR/cli/castoff" "$PROJECT_DIR/magisk_module/system/bin/castoff"
cp -f "$BUILD_DIR/app/CastOff.apk" "$PROJECT_DIR/magisk_module/CastOff.apk"
chmod 755 "$PROJECT_DIR/magisk_module/system/bin/castoff"
chmod 644 "$PROJECT_DIR/magisk_module/system/framework/castoff.jar"
chmod 644 "$PROJECT_DIR/magisk_module/CastOff.apk"

# 4. Package Magisk ZIP
echo "--> Packaging cast_off_magisk.zip..."
(cd "$PROJECT_DIR/magisk_module" && zip -r -FS "$PROJECT_DIR/cast_off_magisk.zip" .)

echo "=== Build Complete! ==="
echo "Module ZIP: $PROJECT_DIR/cast_off_magisk.zip"
