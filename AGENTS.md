# AGENTS.md

Moly is a small Android app-blocker: an accessibility service watches
which apps are on screen (the window list, split screen and pop-ups included)
and draws a block wall over a limited app's window once it exceeds its time
budget, and over YouTube or Instagram while Shorts or Reels is on screen if
the user blocks them. Strict mode, if the user turns it on, keeps Moly
from being uninstalled or switched off until their chosen way out.
"Usage access" is optional, for screen-time stats only.

The app is plain Kotlin + Gradle (`app/`), no Android Studio required. The
repo also holds its website (`web/`), the Shorts and Reels detection rules
(`rules/`), and the server behind `trymoly.com` that serves both
(`server/`, a Cloudflare Worker in TypeScript).

Moly was called AppBlock until 2.5.0, and the old name stays in what a
phone identifies the app by: the app ID and Kotlin package
(`xyz.robinroy.appblock`), the prefs file (`appblock`) and the broadcast
actions (`xyz.robinroy.appblock.END_STRICT`). Don't rename those: to
Android a new app ID is a different app, and a renamed component or prefs
file loses the user's accessibility grant, device admin or rules on update.
Everything a person reads says Moly.

## Tests come with every change

A blocker that silently stops blocking is worse than none, and the tests are
how we know it still works. A change without tests isn't finished.

- **New or changed decision logic:** a pure function in `Storage.kt` with
  JUnit tests in `app/src/test/`, edge cases included.
- **New or changed screen or flow** (onboarding, sheets, the picker, the
  wall, shortcuts): an end-to-end test in `app/src/androidTest/` that drives
  it the way a user would.
- **New or changed server code:** tests in `server/test/`, in the layer
  that fits (see [Server](#server)).
- **Bug fix:** a test that fails without the fix and passes with it.
- **Changed something a test looks for** (text, a button, a dialog): update
  that test in the same commit, still checking the same behaviour.
- **Never make a test pass by weakening it:** no deleting tests, loosening
  assertions, `@Ignore`, longer timeouts or sleeps to hide a problem. If a
  test seems wrong, say why and ask.
- **Never get around the checks:** no `git commit --no-verify`, no editing
  `.githooks/` or `.github/workflows/` to turn things green.

Done means all of these:

1. The pre-commit hook passes: the app's unit tests (`./gradlew test`),
   and `pnpm check` in `server/` when the commit touches `server/`, `web/`
   or `rules/`.
2. `scripts/e2e.sh` passes on the emulator, for a change to the app or its
   tests. It's the suite CI runs.
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
- **Anything in the Cloudflare account** (see [Server](#server)).

## Setup

Requirements:

- **JDK 17.** The Android Gradle plugin refuses Java 11 and older. If the
  default `java` is wrong, point `JAVA_HOME` at a JDK 17 before building, or
  set `org.gradle.java.home` in `gradle.properties`.
- **Android SDK.** Create `local.properties` (gitignored) with its path:

  ```properties
  sdk.dir=/path/to/android-sdk
  ```

  `adb` lives at `<sdk.dir>/platform-tools/adb` and may not be on `PATH`.
- **Node and pnpm**, for the server: the Node version in
  `server/.node-version`, then `pnpm install` in `server/`. Also needed to
  commit a change to `web/` or `rules/` (the hook below).

First time in a clone, before the first commit (agents: do this yourself,
don't wait to be asked; check with `git config core.hooksPath`):

1. **Enable the pre-commit hook.** It runs the app's unit tests, and the
   server's checks when the commit touches `server/`, `web/` or `rules/`,
   and blocks a commit that breaks them. Git doesn't turn on in-repo hooks
   by itself; any Gradle build does it for you (`settings.gradle.kts`), or
   by hand:

   ```sh
   git config core.hooksPath .githooks
   ```

2. **Install the emulator** for the end-to-end tests (a one-time SDK
   download; `scripts/emulator.sh` creates the virtual device itself):

   ```sh
   sdkmanager "emulator" "system-images;android-34;google_apis_playstore;arm64-v8a"
   ```

   That's the Apple-silicon image; on an Intel machine use `x86_64` here
   and in `scripts/emulator.sh`.

3. **Run `scripts/e2e.sh` before pushing** any change to the app or its
   tests. It's the suite CI's `e2e` jobs run, so a red suite here means a
   red CI.

CI (`.github/workflows/ci.yml`) runs on pushes to `main` and on every pull
request (a branch gets CI once its PR is open; open a draft PR early). All
four jobs must pass before a PR merges:

- `app-unit-tests`: the app's unit tests, and a compile.
- `e2e-android-14`, `e2e-android-16`: the suite above on fresh emulators,
  on the Google Play image like `scripts/emulator.sh`'s, and on Android 16,
  the version the app targets, with the server running beside them for the
  full-stack tests. It installs `fixture/`, a stand-in app with
  YouTube's and Instagram's player view ids, for the Shorts and Reels tests.
- `server-checks`: `pnpm check` in `server/`.

Check a push with `gh run list`. A failing e2e run uploads an
`e2e-report-api-<level>` artifact: the HTML report, plus a screenshot and
the device log for each failed test.

Build:

```sh
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JUnit tests (also run by the pre-commit hook)
scripts/e2e.sh                   # end-to-end tests on the emulator, with the server up (also in CI)
```

## Build → phone loop (phone connected over USB)

```sh
scripts/deploy.sh          # tests + release APK + adb install -r (keeps data/grants)
scripts/fresh-install.sh   # same build, but uninstall first: wipes all app state
                           # to test the first-run flow
scripts/local.sh           # the release build, talking to `pnpm dev` on this machine
                           # instead of trymoly.com (start that first)
```

Both handle JDK 17 and the adb path (via `scripts/env.sh`) themselves. Use
`deploy.sh` after every change; `fresh-install.sh` only when the first-run
experience itself is being tested: it deletes the user's rules and the
permission grants. A bare uninstall + install is not enough: Android's Auto
Backup restores the old rules on install, so the script turns restore off
for its install (`bmgr autorestore`). The phone also refuses
`adb shell pm clear` (OxygenOS), so reinstalling is the only way to wipe.

Install via `adb`, not by copying the APK to the phone: adb installs skip
Google Play Protect's block. One-time phone setup: enable Developer options
and USB debugging, then accept the USB-debugging prompt.

To verify a change, open Moly on the phone. The permission grants and
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
exemption, usage access and notifications via adb, so the home list is live
straight away. Prefer it for checking UI changes; use the phone for what
the emulator can't tell you (haptics, OxygenOS behaviour). With both
connected, the scripts target the phone; set `ANDROID_SERIAL=emulator-5554`
to point one at the emulator.

## Shorts and Reels detection rules

How the blocker spots YouTube's Shorts and Instagram's Reels (the players'
view ids) is data, in `rules/v1/rules.json`, so a redesign is fixed without
an app update. Which apps can have their feed blocked is code
(`Storage.FEEDS`): a rule for any other app does nothing. In Instagram's
code a "reel" is a Story (`reels_tray_container` is the Stories row) and
Reels are "clips" (`clips_viewer_view_pager`, `clips_video_container`), so
an Instagram rule names only `clips_*` ids; a unit test holds it to that.
The Reels tab, a reel from Explore and a reel link all open the same
player. There's no Instagram canary: the real Instagram needs an account,
so check a Reels rule change on a phone.

The app bundles the rules and fetches newer ones. `v1` is the rules format:
a build fetches only the one it reads, so a new format goes in `rules/v2/`
for the builds that read it, and `v1` keeps being served to older ones.

- **Publishing.** Once `rules/<format>/rules.signed.json` is on `main`,
  the `Publish site` workflow serves it at
  `https://trymoly.com/<format>/rules.signed.json` (the [server](#server)).
  A rule only names view ids to look for in an app whose feed the user
  blocked; a bad one can at worst wall YouTube wrongly, and re-signing the
  old content (a newer `updated`) rolls it back.
- **Builds up to 2.5.0 fetch from the old address,**
  `https://appblock.robinroy.xyz/v1/rules.signed.json`: a GitHub Pages
  site that nothing publishes to any more. It stays switched on, so those
  builds keep the rules it has, and get no newer ones. Builds from 2.6.0
  fetch from `trymoly.com` (`Storage.RULES_URL`; a unit test holds it to
  the domain in `server/wrangler.jsonc`).
- **Signing.** After editing `rules/v1/rules.json`, run
  `./gradlew signRules`. It stamps the file's `updated` with the time now
  (UTC; the newer file wins on the phone) and writes
  `rules/v1/rules.signed.json`, refusing if the clock isn't past the last
  signing. The unit tests fail if the two files disagree. Signing needs
  `rules-signing-key.pem` in the repo root (gitignored; only on the
  maintainer's and trusted developers' machines, never in the repo or CI;
  keep a backup: without it, rules can only change with an app update that
  swaps the key in `Storage.RULES_PUBLIC_KEY`). Agents: ask before signing.
- **New developers: getting the signing key.** You only need it to change
  the rules; everything else (building, tests, the e2e suite) works without
  it. Ask the maintainer, who shares it through a password manager, never
  over chat or email. Save it as `rules-signing-key.pem` in the repo root
  (`.gitignore` already covers it), and never commit it, paste it anywhere
  or put it in CI. Whoever holds it can publish rules to every phone, so
  if it may have leaked, tell the maintainer at once: the fix is an app
  update with a new key.
- **The YouTube canary.** `scripts/youtube.sh` checks the rules against the
  real, current YouTube: it copies YouTube off the phone onto the emulator
  and runs `YouTubeCanaryTest`. It keeps that copy in `.avd/youtube/`
  (gitignored) and reuses it when no phone is connected, so the phone is
  only needed for a fresh copy. On a new clone there's no copy yet: the
  first run needs an Android phone with YouTube connected over adb, and the
  script says so and stops without one. The copy is per machine and only as
  new as the last run with the phone connected, so connect it before
  checking a rules change. A developer without such a phone can't run the
  canary: ask the maintainer to run it. CI's `YouTube canary` workflow does
  the same daily and on rules changes; it's not a required check (it needs
  the network, and YouTube changes under us), and it reads YouTube's APK
  from a private repo release (secrets `YOUTUBE_APKS_REPO`,
  `YOUTUBE_APKS_TOKEN`).

## Strict mode

While it's on, Moly is a device admin (`StrictAdmin`, no policies in
`res/xml/strict_admin.xml`), so Android refuses to uninstall it, and the
blocker walls any screen of the "guarded" system apps (Settings, the package
installer, the permission controller: `Strict.guardedPackages`) that shows
Moly's name, until that app leaves the screen. Rules only get stricter
(`Storage.loosens`). It ends through the user's way out (plugged in, or a
cooldown), the admin found off (safe mode, where Moly doesn't run), or
adb. The decisions are in `Storage.kt` (`StrictTest`), the moving parts in
`Strict.kt`, the screens in `StrictActivity`/`StrictSetupActivity`, the
end-to-end tests in `StrictE2eTest`.

- Its state is its own prefs file, `strict` (`Storage.STRICT_PREFS`), left
  out of Auto Backup by `backup_rules.xml` and `data_extraction_rules.xml`
  (`BackupRulesTest`). Rename it and those files must follow.
- While the admin is active nothing can uninstall Moly, adb and Gradle
  included, and `adb shell dpm remove-active-admin` refuses (the app isn't
  test-only). End strict mode first:

  ```sh
  adb shell am broadcast -a xyz.robinroy.appblock.END_STRICT -n xyz.robinroy.appblock/.EndStrictReceiver
  ```

  `scripts/fresh-install.sh` and `scripts/emulator.sh` do this before they
  uninstall. The receiver needs the DUMP permission, which only the shell
  has. `E2e.freshState()` and `StrictE2eTest`'s cleanup end it from inside
  the app, so no test leaves the emulator locked.
- `E2e.strictOn()` turns it on with `dpm set-active-admin` (allowed for any
  admin); one test drives the system's real Activate page.

## Website

The pages in `web/` are served at the root of `trymoly.com`:
`web/index.html` is `/`, `web/privacy/index.html` is `/privacy/` (the Play
Store listing links to it), `web/terms/index.html` is `/terms/`. Plain HTML
and one `style.css`, no generator.

- The site is `web/` as is, with the signed rules on top. The build
  (`server/scripts/build-site.ts`) refuses a `web/v1/` (or any folder named
  like a rules format: that path is the rules'), a `web/api/` (the
  server's) and a missing privacy page.
- Preview with `pnpm dev` in `server/`: the site exactly as it is served.
- When a change touches what the app collects, sends or asks permission for
  (the manifest, usage access, the rules fetch, backups), update the
  privacy policy in the same commit.

## Server

`server/` is the Cloudflare Worker behind `trymoly.com` (TypeScript, with
Hono for the routes). Cloudflare answers a request for a file (a page, the
rules) by itself, without running the Worker. The Worker (`src/index.ts`)
gets the rest: everything under `/api/`, and paths that don't exist, which
it hands back for the 404 page. From `server/`:

```sh
pnpm install     # once
pnpm dev         # the site and the API at http://localhost:8787, with a local database
pnpm dev:mail    # the same, but its mail is really sent (to FEEDBACK_TO in .dev.vars)
pnpm db:feedback # the feedback in the local database
pnpm check       # lint + typecheck + every test: CI's `server-checks` job
pnpm format      # apply the formatter
```

`pnpm dev` is `wrangler dev`: the Worker in workerd, the runtime Cloudflare
runs in production, with the static site in front of it as there. None of
this needs a Cloudflare account. Running the app against it, looking
inside the local database and mail, and the full-stack tests are in
[docs/local-dev.md](docs/local-dev.md).

- **Tests** are in `server/test/` (Vitest), in three layers: `unit/` for
  plain logic, in Node; `worker/` for the Worker's routes, inside workerd;
  `e2e/` for HTTP requests to a `wrangler dev` the tests start themselves.
  Anything a client relies on goes in `e2e/`: the app's side of the rules
  fetch (the signed file, its `ETag`, the `304`) is held to it there.
  `pnpm test --project unit` runs one layer. The pre-commit hook's own
  tests are in `unit/` too.
- **The database** (D1, SQLite) is made of `migrations/*.sql`, and every
  query is in `src/db.ts`. Until the first deploy a table is changed in
  its file and the local copy reset (delete `.wrangler/state`); after it,
  ask the maintainer before writing a migration. `pnpm dev` only prints
  mail (`src/email.ts`).
- **Deploys** happen in CI only, never from a laptop: on a push to `main`
  that changes `server/`, `web/` or `rules/`, `Publish site` runs the
  app's unit tests and `pnpm check`, then `wrangler deploy`. It publishes
  `main` and nothing else. There is no staging copy: `main` is what is live.
- **Configuration** is `server/wrangler.jsonc`: what the Worker is and
  where it answers. What has no config file (DNS, email, the `www`
  redirect, CI's token, who has access) is recorded in
  [docs/cloudflare.md](docs/cloudflare.md).

## Conventions

- Decision logic goes in `Storage.kt` as pure functions with JUnit tests in
  `app/src/test/`, every feature. UI stays in the Activities/Service.
- User-visible flows get an end-to-end test in `app/src/androidTest/`:
  UI Automator driving the real app (and the apps it blocks) on the
  emulator. `E2e.freshState()` resets the app before each test, and
  `seedUsage()` starts a test partway into a budget instead of waiting.
  Cleanup that leaves the screen goes in an `E2e.Cleanup` rule ordered
  before `E2e.ScreenshotOnFailure`, not an `@After`, so a failure's
  screenshot shows the screen as it failed.
- No build artifacts are committed: `*.apk`, `build/`, and `local.properties`
  are gitignored. Keep it that way.
