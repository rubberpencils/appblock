#!/bin/sh -e
# From-scratch install for testing the first-run experience: uninstall
# (wiping rules, onboarding state, and every permission grant), then
# install the freshly built APK.
#
# Android's Auto Backup (on by default for every app) copies Moly's data
# to the phone's Google account and restores it when the app is installed
# again, which would bring the old rules straight back. So restore-on-install
# is switched off for this one install, and always switched back on after.
cd "$(dirname "$0")/.."
. scripts/env.sh

./gradlew testDebugUnitTest assembleRelease
cp app/build/outputs/apk/release/app-release.apk moly.apk
end_strict   # strict mode's device admin would refuse the uninstall
"$ADB" uninstall xyz.robinroy.appblock || true   # fine if it wasn't installed
"$ADB" shell bmgr autorestore false
trap '"$ADB" shell bmgr autorestore true' EXIT
"$ADB" install moly.apk
sleep 5   # the restore would start a few seconds after the install returns
