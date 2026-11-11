#!/bin/sh -e
# Ends strict mode on the connected device (the phone, or the emulator with
# ANDROID_SERIAL=emulator-5554) through adb's way out, EndStrictReceiver, and
# waits for Moly to stop being a device admin. Harmless when strict mode
# is already off.
cd "$(dirname "$0")/.."
. scripts/env.sh

if ! "$ADB" get-state > /dev/null 2>&1; then
  echo "No device connected (on wireless debugging? try: $ADB mdns services)." >&2
  exit 1
fi
end_strict
if "$ADB" shell dumpsys device_policy 2>/dev/null | grep -q "xyz.robinroy.appblock/.StrictAdmin"; then
  echo "Strict mode is still on: Moly is still a device admin." >&2
  exit 1
fi
echo "Strict mode is off."
