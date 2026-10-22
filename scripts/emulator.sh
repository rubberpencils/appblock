#!/bin/sh -e
# Test on an emulator instead of the phone: boots an Android 14 emulator
# (creating it on first run), installs the current debug build on it (the
# build the e2e tests use), and switches on AppBlock's settings so the home
# list is live straight away.
#
#   scripts/emulator.sh            headless; drive it with adb
#   scripts/emulator.sh --window   with the emulator window on screen
#
# The virtual device lives in .avd/ (gitignored). It's the Google Play image:
# its YouTube is Google's own, so a current one installs over it
# (scripts/youtube.sh). One-time SDK download:
#   sdkmanager "emulator" "system-images;android-34;google_apis_playstore;arm64-v8a"
cd "$(dirname "$0")/.."
. scripts/env.sh

export ANDROID_AVD_HOME="$PWD/.avd"
export ANDROID_SERIAL=emulator-5554   # the first emulator's serial
WINDOW=-no-window
[ "$1" = "--window" ] && WINDOW=

IMAGE="system-images;android-34;google_apis_playstore;arm64-v8a"
# A device made from the older image (no Play) is made again.
if [ -d .avd/appblock.avd ] && ! grep -q google_apis_playstore .avd/appblock.avd/config.ini; then
  "$ADB" -s "$ANDROID_SERIAL" emu kill > /dev/null 2>&1 || true
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" delete avd -n appblock
fi
if [ ! -d .avd/appblock.avd ]; then
  mkdir -p .avd
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n appblock -k "$IMAGE" -d pixel_7
fi

if ! "$ADB" devices | grep -q "^$ANDROID_SERIAL"; then
  nohup "$SDK/emulator/emulator" -avd appblock $WINDOW -no-audio -no-boot-anim \
    > .avd/emulator.log 2>&1 &
  echo "Booting the emulator (log: .avd/emulator.log)..."
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
fi

./gradlew assembleDebug
APK=app/build/outputs/apk/debug/app-debug.apk
# A release build there (signed differently) refuses the update: replace it.
"$ADB" install -r "$APK" || { "$ADB" uninstall xyz.robinroy.appblock; "$ADB" install "$APK"; }

# The settings, granted directly instead of through their pages: the
# blocker's accessibility service, the battery exemption, and the optional
# usage access and notifications.
"$ADB" shell settings put secure enabled_accessibility_services \
  xyz.robinroy.appblock/xyz.robinroy.appblock.BlockerService
"$ADB" shell settings put secure accessibility_enabled 1
"$ADB" shell appops set xyz.robinroy.appblock GET_USAGE_STATS allow
"$ADB" shell dumpsys deviceidle whitelist +xyz.robinroy.appblock > /dev/null
"$ADB" shell pm grant xyz.robinroy.appblock android.permission.POST_NOTIFICATIONS
echo "Ready: AppBlock is installed on $ANDROID_SERIAL."
