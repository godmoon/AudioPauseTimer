#!/bin/bash
# 音频暂停定时器 Android APK 本机构建脚本（无需 Android Studio）

set -e

# SDK 和 JDK 路径（本机）
ANDROID_HOME="$HOME/android-sdk"
ANDROID_SDK_ROOT="$ANDROID_HOME"
JAVA_HOME="$ANDROID_HOME/jdk/jdk-17.0.9+9"
export ANDROID_HOME ANDROID_SDK_ROOT JAVA_HOME

export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/build-tools/34.0.0:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "================================"
echo "音频暂停定时器 Android APK 构建"
echo "================================"
echo "JAVA_HOME: $JAVA_HOME"
echo "ANDROID_HOME: $ANDROID_HOME"
echo ""

./gradlew assembleDebug --no-daemon

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
    cp "$APK" audioPauseTimer-debug.apk
    echo ""
    echo "================================"
    echo "✓ 构建成功!"
    echo "================================"
    echo "APK: $SCRIPT_DIR/audioPauseTimer-debug.apk"
    ls -lh audioPauseTimer-debug.apk
else
    echo "构建失败：未找到 $APK"
    exit 1
fi
