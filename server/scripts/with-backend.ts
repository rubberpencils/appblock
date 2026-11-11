// `node server/scripts/with-backend.ts -- <command...>`: runs a command
// with the whole backend up for it (scripts/backend.ts), and stops the
// backend when the command ends, with the command's exit code. The app's
// end-to-end tests run under it (scripts/e2e-run.sh in the repo root), so
// the app on the emulator talks to a real server.
//
// The command finds the server at $MOLY_BACKEND_URL (port
// $MOLY_BACKEND_PORT). Besides the real site, it serves a few files for
// the tests under /e2e/: detection rules as the server would publish them,
// signed with a key made for this run (the real key never leaves the
// maintainer's machine), and that key's public half.
import { spawn } from "node:child_process";
import { generateKeyPairSync, type KeyObject, sign } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { startBackend } from "./backend.ts";
import { buildSite, repoDirs } from "./build-site.ts";

/** The e2e tests' stand-in for YouTube (fixture/), and its player's view id. */
const FIXTURE = "xyz.robinroy.appblock.fixture";
const PLAYER = "reel_watch_player";

/** A rules file as `./gradlew signRules` writes it: the rules as a string, and a signature over them. */
function signed(rules: string, key: KeyObject): string {
  const sig = sign("sha256", Buffer.from(rules, "utf8"), key).toString(
    "base64",
  );
  return `${JSON.stringify({ rules, sig }, null, 2)}\n`;
}

function rules(updated: Date, ids: string[]): string {
  // To the second, as the app insists: "2026-11-11T12:00:00Z".
  const stamp = `${updated.toISOString().slice(0, 19)}Z`;
  return JSON.stringify(
    { updated: stamp, feeds: { [FIXTURE]: [{ ids, minCover: 0.7 }] } },
    null,
    2,
  );
}

/**
 * The files under /e2e/, by path. Three rule sets, each at the path the app
 * fetches a format from (<somewhere>/v1/rules.signed.json):
 *   old       names a view the player no longer has (the app redesigned)
 *   new       the fix: names the player's view
 *   tampered  the fix's signature on rules someone changed
 *   stranger  newer than the fix, and properly signed, by someone else's key
 * All are newer than anything the app ships with.
 */
export function fixtures(now: Date = new Date()): {
  files: Record<string, string>;
  publicKey: string;
} {
  const { privateKey, publicKey } = generateKeyPairSync("ec", {
    namedCurve: "prime256v1",
  });
  const hour = 60 * 60 * 1000;
  const old = rules(new Date(now.getTime() - 2 * hour), [
    "player_before_the_redesign",
  ]);
  const fix = rules(new Date(now.getTime() - hour), [PLAYER]);
  const changed = rules(now, ["something_else_entirely"]);
  const strangers = rules(new Date(now.getTime() + hour), ["a_strangers_rule"]);
  const stranger = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  // Signed once: a second signature over the same rules would differ.
  const fixFile = signed(fix, privateKey);
  const fixSignature = JSON.parse(fixFile).sig as string;
  const key = publicKey
    .export({ type: "spki", format: "der" })
    .toString("base64");
  return {
    publicKey: key,
    files: {
      "e2e/public-key.txt": `${key}\n`,
      "e2e/rules/old/v1/rules.signed.json": signed(old, privateKey),
      "e2e/rules/new/v1/rules.signed.json": fixFile,
      "e2e/rules/tampered/v1/rules.signed.json": `${JSON.stringify({ rules: changed, sig: fixSignature }, null, 2)}\n`,
      "e2e/rules/stranger/v1/rules.signed.json": signed(
        strangers,
        stranger.privateKey,
      ),
    },
  };
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const dashes = process.argv.indexOf("--");
  const command = dashes < 0 ? [] : process.argv.slice(dashes + 1);
  if (command.length === 0) {
    console.error("usage: node server/scripts/with-backend.ts -- <command...>");
    process.exit(2);
  }
  const backend = await startBackend({
    // The site as it is published, and the tests' files beside it.
    site: (scratch) => {
      const site = join(scratch, "site");
      buildSite({ ...repoDirs(), out: site });
      for (const [path, content] of Object.entries(fixtures().files)) {
        mkdirSync(dirname(join(site, path)), { recursive: true });
        writeFileSync(join(site, path), content);
      }
      return site;
    },
  });
  console.log(
    `The backend is up at ${backend.url} (its log: ${backend.logFile}).`,
  );
  // Not spawnSync: this process has to keep running while the command
  // does, to go on writing down what the server prints.
  const child = spawn(command[0], command.slice(1), {
    stdio: "inherit",
    env: {
      ...process.env,
      MOLY_BACKEND_URL: backend.url,
      MOLY_BACKEND_PORT: String(backend.port),
    },
  });
  const status = await new Promise<number>((done) => {
    child.on("error", (error) => {
      console.error(String(error));
      done(127);
    });
    child.on("exit", (code) => done(code ?? 1));
  });
  // What the server printed is worth seeing when the command failed.
  if (status !== 0) {
    console.error(`\n--- the backend's log (the command exited ${status}) ---`);
    console.error(
      readFileSync(backend.logFile, "utf8").split("\n").slice(-60).join("\n"),
    );
  }
  backend.stop();
  process.exit(status);
}
