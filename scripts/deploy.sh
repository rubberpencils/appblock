#!/bin/sh -e
# Everyday build -> phone loop: run the unit tests, build the release APK,
# install it over USB. `-r` keeps app data and the permission grants, so no
# re-setup is needed on the phone (Android brings the accessibility service back
# after the update).
cd "$(dirname "$0")/.."
. scripts/env.sh

./gradlew testDebugUnitTest assembleRelease
cp app/build/outputs/apk/release/app-release.apk appblock.apk
"$ADB" install -r appblock.apk
