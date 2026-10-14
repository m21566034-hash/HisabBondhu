#!/usr/bin/env bash
# Builds the debug APK with a supported Java (17-21). Usage: ./build-debug.sh [release]
set -e
cd "$(dirname "$0")"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
V=$("$JAVA_BIN" -version 2>&1 | sed -n 's/.*version "\([0-9]*\)[."].*/\1/p' | head -1)
if [ -z "$V" ] || [ "$V" -lt 17 ] || [ "$V" -gt 21 ]; then
  echo "Java $V পাওয়া গেছে। JDK 17 (বা 21) দরকার।"
  echo "JAVA_HOME ঠিক করুন, যেমন:  export JAVA_HOME=/path/to/jdk-17"
  exit 1
fi
echo "Java $V ব্যবহার হচ্ছে ($JAVA_BIN)"
if [ "$1" = "release" ]; then ./gradlew assembleRelease; echo "APK: app/build/outputs/apk/release/"
else ./gradlew testDebugUnitTest assembleDebug; echo "APK: app/build/outputs/apk/debug/app-debug.apk"; fi
