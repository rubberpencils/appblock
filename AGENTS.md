# AGENTS.md

AppBlock is a small Android app-blocker: an accessibility service watches
which apps are on screen (the window list, split screen and pop-ups included)
and draws a block wall over a limited app's window once it exceeds its time
budget, and over YouTube while Shorts is on screen if the user blocks Shorts.
"Usage access" is optional, for screen-time stats only.
Plain Kotlin + Gradle, no Android Studio required.

## Tests come with every change

A blocker that silently stops blocking is worse than none, and the tests are
how we know it still works. A change without tests isn't finished.

- **New or changed decision logic:** a pure function in `Storage.kt` with
  JUnit tests in `app/src/test/`, edge cases included.
- **New or changed screen or flow** (onboarding, sheets, the picker, the
  wall, shortcuts): an end-to-end test in `app/src/androidTest/` that drives
  it the way a user would.
- **Bug fix:** a test that fails without the fix and passes with it.
- **Changed something a test looks for** (text, a button, a dialog): update
  that test in the same commit, still checking the same behaviour.
- **Never make a test pass by weakening it:** no deleting tests, loosening
  assertions, `@Ignore`, longer timeouts or sleeps to hide a problem. If a
  test seems wrong, say why and ask.
- **Never get around the checks:** no `git commit --no-verify`, no editing
  `.githooks/` or `.github/workflows/` to turn things green.

Done means all of these:

1. `./gradlew testDebugUnitTest` passes (the pre-commit hook runs it).
2. `scripts/e2e.sh` passes on the emulator. It's the suite CI runs.
3. The new behaviour has its tests, as above.
4. A user-visible change has been tried on the emulator or the phone.
5. After pushing, CI is green (`gh run list`). Red CI on `main` comes before
   anything else, and a release is only ever made from a commit with green CI.

Ask the maintainer first, and say so in the commit message, before changing:

- **The stored data format** (the rules and usage JSON in `Storage.kt`).
  Users' rules live in it, and Android's Auto Backup restores it onto new
  installs, so a changed format needs a migration with tests.
- **`BlockerService.kt`'s detection and blocking**, `AndroidManifest.xml`
  permissions, and the CI workflow, hooks, or `scripts/`.
- **Release signing** (`keystore.properties` and the signing config).

## Setup

Requirements:

- **JDK 17** — the Android Gradle plugin refuses Java 11 and older. If the
  default `java` on the machine is wrong, point `JAVA_HOME` at a JDK 17 before
  building, or set `org.gradle.java.home` in `gradle.properties`.
- **Android SDK** — create `local.properties` (gitignored) with the SDK path:

  ```properties
  sdk.dir=/path/to/android-sdk
  ```

  `adb` lives at `<sdk.dir>/platform-tools/adb` and may not be on `PATH`.

First time in a clone, before the first commit (agents: do this yourself,
don't wait to be asked; check with `git config core.hooksPath`):

1. **Enable the pre-commit hook** — it runs the unit tests and blocks a
   commit that breaks them. Git doesn't turn on in-repo hooks by itself;
   any Gradle build does it for you (`settings.gradle.kts`), or by hand:

   ```sh
   git config core.hooksPath .githooks
   ```

2. **Install the emulator** for the end-to-end tests (one-time SDK
   download; `scripts/emulator.sh` creates the virtual device itself):

   ```sh
   sdkmanager "emulator" "system-images;android-34;google_apis_playstore;arm64-v8a"
   ```

   That's the Apple-silicon image; on an Intel machine use `x86_64` here
   and in `scripts/emulator.sh`.

3. **Run `scripts/e2e.sh` before pushing** any change to the app or its
   tests. It's the same suite CI's `e2e` job runs on every push, so a red
   suite here means a red CI. When a change renames or moves something the
   e2e tests look for (button text, a dialog), update the tests in
   `app/src/androidTest/` in the same commit.

CI (`.github/workflows/ci.yml`) runs on pushes to `main` and on every pull
request (a branch gets CI once its PR is open; open a draft PR early): `test` (unit
tests + compile), and the suite above on fresh emulators: `e2e-android-14`
(the Google Play image, like `scripts/emulator.sh`'s) and `e2e-android-16`
on Android 16, the version the app targets. The suite installs `fixture/`,
a stand-in app with YouTube's Shorts player view id, for the Shorts tests. Check a
push with `gh run list`; a failing e2e run uploads an `e2e-report-api-<level>`
artifact with the HTML report, plus a screenshot and the device log for
each failed test.

Build:

```sh
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JUnit tests (also run by the pre-commit hook)
scripts/e2e.sh                   # end-to-end tests on the emulator (also in CI)
```

## Build → phone loop (phone connected over USB)

```sh
scripts/deploy.sh          # tests + release APK + adb install -r (keeps data/grants)
scripts/fresh-install.sh   # same build, but uninstall first: wipes all app state
                           # to test the first-run flow
```

Both handle JDK 17 and the adb path (via `scripts/env.sh`) themselves. Use
`deploy.sh` after every change; `fresh-install.sh` only when the fresh-install
experience itself is being tested — it deletes the user's rules and the
permission grants. A bare uninstall + install is not enough: Android's Auto
Backup restores the old rules on install, so the script turns restore off
for its install (`bmgr autorestore`). The phone also refuses
`adb shell pm clear` (OxygenOS), so reinstalling is the only way to wipe.

Install via `adb`, not by copying the APK to the phone — adb installs skip
Google Play Protect's block. One-time phone setup: enable Developer options and
USB debugging, then accept the USB-debugging prompt.

To verify a change, open AppBlock on the phone; the permission grants and
blocked-app rules survive an `-r` reinstall, and Android brings the blocker's
accessibility service back after the update, so no re-setup is needed.

## Emulator loop (no phone needed)

```sh
scripts/emulator.sh            # boot (creating it on first run), install, grant settings
scripts/emulator.sh --window   # same, with the emulator window visible
```

An Android 14 emulator (a Pixel 7, like the test phone's screen) whose
virtual device lives in `.avd/` (gitignored). It installs the current debug
build and switches on the blocker's accessibility service, the battery
exemption, usage access and notifications via adb, so the home list is live straight away. Prefer it for
checking UI changes; use the phone for what the emulator can't tell you
(haptics, OxygenOS behaviour). With both connected, the scripts target the
phone; set `ANDROID_SERIAL=emulator-5554` to point one at the emulator.

## Shorts detection rules

How the blocker spots YouTube's Shorts (the player's view ids) is data, in
`rules/v1/rules.json`, so a YouTube redesign is fixed without an app update:
the app bundles the rules and fetches newer ones from
`https://appblock.robinroy.xyz/v1/rules.signed.json`. That's GitHub Pages
for this repo (a CNAME at Namecheap); the `Publish rules` workflow deploys
`rules/<format>/rules.signed.json` there on every push to `main` that
changes `rules/`. `v1` is the rules format: a build fetches only the one it
reads, so a new format goes in `rules/v2/` for the builds that read it,
and `v1` keeps being served to older ones.

- After editing `rules/v1/rules.json`, run `./gradlew signRules`. It stamps
  the file's `updated` with the time now (UTC; the newer file wins on the
  phone) and writes `rules/v1/rules.signed.json`, refusing if the clock
  isn't past the last signing. The unit tests fail if the two files
  disagree. Signing needs `rules-signing-key.pem` in the repo root
  (gitignored; only on the maintainer's and trusted developers' machines,
  never in the repo or CI; keep a backup: without it, rules can only change
  with an app update that swaps the key in `Storage.RULES_PUBLIC_KEY`).
  Agents: ask before signing.
- The rules go live for every user once `rules/v1/rules.signed.json` is
  on `main` and `Publish rules` has run. A rule only names view ids to look
  for in an app whose feed the user blocked; a bad one can at worst wall
  YouTube wrongly, and re-signing the old content (a newer `updated`)
  rolls it back.
- `scripts/youtube.sh` checks the rules against the real, current YouTube:
  it copies YouTube off the phone onto the emulator and runs
  `YouTubeCanaryTest`. CI's `YouTube canary` workflow does the same daily and
  on rules changes; it's not a required check (it needs the network, and
  YouTube changes under us), and it reads YouTube's APK from a private repo
  release (secrets `YOUTUBE_APKS_REPO`, `YOUTUBE_APKS_TOKEN`).

## Conventions

- Decision logic goes in `Storage.kt` as pure functions with JUnit tests in
  `app/src/test/` — every feature. UI stays in the Activities/Service.
- User-visible flows get an end-to-end test in `app/src/androidTest/`:
  UI Automator driving the real app (and the apps it blocks) on the
  emulator. `E2e.freshState()` resets the app before each test, and
  `seedUsage()` starts a test partway into a budget instead of waiting.
  Cleanup that leaves the screen goes in an `E2e.Cleanup` rule ordered
  before `E2e.ScreenshotOnFailure`, not an `@After`, so a failure's
  screenshot shows the screen as it failed.
- No build artifacts are committed: `*.apk`, `build/`, and `local.properties`
  are gitignored. Keep it that way.
