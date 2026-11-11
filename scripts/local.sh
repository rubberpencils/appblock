#!/bin/sh -e
# The "local" build on the phone (or, with ANDROID_SERIAL=emulator-5554, the
# emulator): Moly as released, except that feedback goes to the server on
# this machine instead of trymoly.com. For trying a server change with the
# real app before it is live.
#
# Start the server first, in another terminal: `pnpm dev` in server/.
#
# The device reaches it as its own localhost:8787, which adb carries to
# this machine (`adb reverse`), over USB or wireless debugging: nothing is
# opened on the network. It lasts while adb stays connected; run this
# script again after a reconnect.
#
# The build is signed like a release, so it installs over the real app and
# keeps its data, and its version reads "-local" on the info sheet. To go
# back to the real server: scripts/deploy.sh.
cd "$(dirname "$0")/.."
. scripts/env.sh

# Signed like a release, or it couldn't install over the real app.
if [ ! -f keystore.properties ]; then
  echo "scripts/local.sh needs the release keystore (keystore.properties): ask the maintainer." >&2
  echo "Without it, try a change on the emulator: scripts/e2e.sh, or docs/local-dev.md." >&2
  exit 1
fi

./gradlew testDebugUnitTest assembleLocal
cp app/build/outputs/apk/local/app-local.apk moly-local.apk
"$ADB" reverse tcp:8787 tcp:8787
"$ADB" install -r moly-local.apk
echo "Installed the local build on ${ANDROID_SERIAL:-the device}. Its feedback goes to http://localhost:8787 on this machine."
