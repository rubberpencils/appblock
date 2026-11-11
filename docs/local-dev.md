# Running everything on your machine

The app, its server and its database all run locally, with no Cloudflare
account and nothing on the network. This page is how to start each part,
join them up, look inside, and test the whole of it.

## The parts

| Part | What it is locally | Started by |
|---|---|---|
| Server | The real Worker in workerd (`wrangler dev`), with the site and the rules in front of it, at `http://localhost:8787` | `pnpm dev` in `server/` |
| Database | A SQLite file in `server/.wrangler/state`, with the tables from `server/migrations` | `pnpm dev` (it applies migrations first) |
| Mail | Nothing is sent: each mail is recorded and printed | `pnpm dev` |
| App | The emulator (`scripts/emulator.sh`) or a phone over adb | `scripts/local.sh` |

Once: `pnpm install` in `server/`, and the [Setup in AGENTS.md](../AGENTS.md#setup).

## The server

```sh
cd server
pnpm dev          # site + API + database + recorded mail, at http://localhost:8787
pnpm db:feedback  # the feedback in the database, newest first
pnpm dev:mail     # as `pnpm dev`, but mail is really sent
```

- **Look inside:** `http://localhost:8787/cdn-cgi/explorer` is Wrangler's
  own page for a running local server. *D1* shows the database's tables
  and runs queries; *Email sending* lists every mail the Worker "sent".
  (*Email routing* is for mail coming in to the Worker; ours takes none.)
- **Start the database again from nothing:** stop the server and delete
  `server/.wrangler/state`.
- **Real mail** (`pnpm dev:mail`) needs `pnpm exec wrangler login` once,
  and in `server/.dev.vars` a `FEEDBACK_TO` address that is verified on
  the Cloudflare account. Everything but the mail stays local.
- `server/.dev.vars` holds local secrets (copy `.dev.vars.example`). It is
  gitignored.

## The app against your server

```sh
scripts/local.sh                                # on the phone
ANDROID_SERIAL=emulator-5554 scripts/local.sh   # on the emulator
```

It builds the "local" build (the release build, except that it talks to
`http://localhost:8787`; its version reads `-local`), installs it over the
app that is there, keeping its data, and links the device to your machine.
It is signed like a release, so it needs the release keystore
(`keystore.properties`), which only the maintainer's machine has:

```sh
adb reverse tcp:8787 tcp:8787
```

That makes the device's own port 8787 lead, through the adb connection
(USB or wireless debugging), to port 8787 here. The server listens on
localhost only, so nothing is opened on the network, and no address needs
changing when your machine's does. It lasts while adb stays connected: after
a reconnect, run `scripts/local.sh` again.

- "Couldn't send it" in the app: the server isn't running, or the link is
  gone (`adb reverse --list`).
- Back to the real server: `scripts/deploy.sh`.

## Tests

| What | Command | Needs |
|---|---|---|
| The server's (unit, in workerd, e2e over HTTP) | `pnpm check` in `server/` | nothing running |
| The app's unit tests | `./gradlew test` | nothing running |
| The app's e2e tests, the full-stack ones included | `scripts/e2e.sh` | the emulator's SDK image; `pnpm install` in `server/` |

`scripts/e2e.sh` boots the emulator, then starts **its own** server for
the run (`server/scripts/with-backend.ts`): a free port, a scratch
database, gone when the run ends. It never touches the one `pnpm dev`
uses, and both can run at once. The emulator is linked to it with
`adb reverse`, as above.

Most e2e tests don't use the server: the test plays it, so it can stall
or fail on cue (`FeedbackE2eTest`, `FeedE2eTest`). `FullStackE2eTest` is
the rest: the app against the real server, nothing played.

- **Feedback:** sent from the form, then read back from the server's
  database and its recorded mail (`E2e.Backend.rows` and `.mails`, which
  ask the server's explorer through the same link).
- **Detection rules:** the server serves rule files made for the run,
  signed with a key made for the run (the real key is never in a test):
  rules that miss the player, the fix, and a tampered file. The app
  downloads them as it does in the field, and the wall follows.

One class or one test: `scripts/e2e.sh -Pandroid.testInstrumentationRunnerArguments.class=xyz.robinroy.appblock.FullStackE2eTest`.
Pictures of the screens as the tests see them: add
`-Pandroid.testInstrumentationRunnerArguments.shots=1`, then
`adb pull /sdcard/Download/moly-shots` (`E2e.shot`).

A full-stack test run with Gradle alone fails, saying to use the script:
it has no server.
