# Shared setup, sourced by the other scripts. Not executable on its own.
#
# Gradle needs JDK 17 (the default java on this machine may be older); adb
# lives inside the SDK named by local.properties and isn't on PATH.

[ -d /opt/homebrew/opt/openjdk@17 ] && export JAVA_HOME=/opt/homebrew/opt/openjdk@17

SDK="$(sed -n 's/^sdk.dir=//p' local.properties)"
ADB="$SDK/platform-tools/adb"

# With the emulator running too, adb needs telling which device. Unless
# ANDROID_SERIAL says otherwise, scripts target the phone: the connected
# device that isn't an emulator.
if [ -z "$ANDROID_SERIAL" ]; then
  PHONE=$("$ADB" devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ { print $1; exit }')
  if [ -n "$PHONE" ]; then export ANDROID_SERIAL="$PHONE"; fi
fi

# Strict mode makes AppBlock a device admin, and Android then refuses to
# uninstall it (adb included). Before an uninstall: end strict mode (adb's
# way out, EndStrictReceiver), and wait for the admin to let go. Harmless
# when it's off or AppBlock isn't installed.
end_strict() {
  "$ADB" shell am broadcast -a xyz.robinroy.appblock.END_STRICT \
    -n xyz.robinroy.appblock/.EndStrictReceiver > /dev/null 2>&1 || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    "$ADB" shell dumpsys device_policy 2>/dev/null | grep -q "xyz.robinroy.appblock/.StrictAdmin" || return 0
    sleep 1
  done
}
