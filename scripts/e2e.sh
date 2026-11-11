#!/bin/sh -e
# End-to-end tests (app/src/androidTest) on the emulator: boots it if needed,
# then drives the real app with UI Automator, with the backend running on
# this machine for the tests that need it (scripts/e2e-run.sh). Report on
# failure: app/build/reports/androidTests/connected/debug/index.html
cd "$(dirname "$0")/.."
. scripts/env.sh
export ANDROID_SERIAL=emulator-5554
export ADB

scripts/emulator.sh
scripts/e2e-run.sh "$@"
