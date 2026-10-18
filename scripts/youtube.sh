#!/bin/sh -e
# The YouTube canary, locally: do the shipped Shorts rules (rules/rules.json)
# still catch the real YouTube's Shorts? Copies YouTube off the connected
# phone into .avd/youtube/ (the last copy is reused without a phone),
# installs it on the emulator over the Play image's older one, and runs
# YouTubeCanaryTest there. Needs the network. A failure means the rules need
# updating (then ./gradlew signRules).
cd "$(dirname "$0")/.."
. scripts/env.sh

case "$ANDROID_SERIAL" in
  ""|emulator-*) ;;
  *)  # env.sh found the phone
    rm -rf .avd/youtube && mkdir -p .avd/youtube
    for apk in $("$ADB" shell pm path com.google.android.youtube | sed 's/^package://' | tr -d '\r'); do
      "$ADB" pull "$apk" .avd/youtube/ > /dev/null
    done
    echo "Copied YouTube off the phone." ;;
esac
ls .avd/youtube/*.apk > /dev/null 2>&1 || { echo "No YouTube in .avd/youtube/: connect the phone and run again."; exit 1; }

export ANDROID_SERIAL=emulator-5554
scripts/emulator.sh
"$ADB" install-multiple -r .avd/youtube/*.apk
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=xyz.robinroy.appblock.YouTubeCanaryTest \
  -Pandroid.testInstrumentationRunnerArguments.canary=true
