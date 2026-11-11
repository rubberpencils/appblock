#!/bin/sh -e
# The end-to-end tests (app/src/androidTest) on a device that is already
# up, with the whole backend up for them: the real local server
# (server/scripts/with-backend.ts) with a scratch database. The device
# reaches it as its own localhost:8787, carried to this machine by
# `adb reverse`. So the tests in FullStackE2eTest drive the app against a
# real server; the rest don't need one. scripts/e2e.sh boots the emulator
# first and then runs this; CI runs this on its own emulator.
#
# Needs `pnpm install` done in server/. Extra arguments go to Gradle.
cd "$(dirname "$0")/.."
: "${ADB:=adb}"
export ADB

exec node server/scripts/with-backend.ts -- sh -ec '
  "$ADB" reverse tcp:8787 "tcp:$MOLY_BACKEND_PORT"
  trap "\"\$ADB\" reverse --remove tcp:8787 >/dev/null 2>&1 || true" EXIT
  ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.backend=http://localhost:8787 "$@"
' e2e-run "$@"
