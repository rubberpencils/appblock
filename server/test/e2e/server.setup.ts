// Starts the real local server (`wrangler dev`: the Worker in workerd, with
// the static site in front of it, as in production) on a free port for the
// end-to-end tests, and stops it afterwards. Its database is a scratch one,
// made for the run with the tables from migrations/: the tests never touch
// the one `pnpm dev` uses.
import { type ChildProcess, execFileSync, spawn } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import type { TestProject } from "vitest/node";

const server = resolve(fileURLToPath(import.meta.url), "..", "..", "..");
const READY_TIMEOUT_MS = 90_000;

function freePort(): Promise<number> {
  return new Promise((done, fail) => {
    const probe = createServer();
    probe.on("error", fail);
    probe.listen(0, "127.0.0.1", () => {
      const address = probe.address();
      probe.close(() =>
        typeof address === "object" && address
          ? done(address.port)
          : fail(new Error("no free port")),
      );
    });
  });
}

/** Resolves once the server answers; rejects if it exits or takes too long. */
async function ready(child: ChildProcess, baseUrl: string, log: () => string) {
  const deadline = Date.now() + READY_TIMEOUT_MS;
  while (Date.now() < deadline) {
    if (child.exitCode !== null || child.signalCode !== null) {
      const how = child.signalCode ?? child.exitCode;
      throw new Error(`wrangler dev exited (${how}):\n${log()}`);
    }
    try {
      const response = await fetch(`${baseUrl}/api/health`);
      await response.body?.cancel();
      if (response.ok) return;
    } catch {
      // Not listening yet.
    }
    await new Promise((wait) => setTimeout(wait, 250));
  }
  throw new Error(`wrangler dev wasn't ready in time:\n${log()}`);
}

export default async function setup(project: TestProject) {
  const port = await freePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  const wrangler = resolve(server, "node_modules", ".bin", "wrangler");
  const scratch = mkdtempSync(join(tmpdir(), "moly-e2e-"));
  const state = join(scratch, "state");
  // What the server prints, kept where a test can read it: locally a mail
  // isn't sent, it is printed.
  const logFile = join(scratch, "server.log");
  writeFileSync(logFile, "");
  execFileSync(
    wrangler,
    ["d1", "migrations", "apply", "moly", "--local", "--persist-to", state],
    { cwd: server, stdio: "pipe" },
  );

  let output = "";
  // Its own process group, so stopping it also stops the workerd it starts.
  const child = spawn(
    wrangler,
    [
      "dev",
      ...["--port", String(port), "--ip", "127.0.0.1"],
      ...["--persist-to", state],
      // A secret in production. Nothing is sent from here.
      ...["--var", "FEEDBACK_TO:maintainer@example.com"],
    ],
    { cwd: server, detached: true, stdio: ["ignore", "pipe", "pipe"] },
  );
  const keep = (chunk: unknown) => {
    output += chunk;
    writeFileSync(logFile, String(chunk), { flag: "a" });
  };
  child.stdout?.on("data", keep);
  child.stderr?.on("data", keep);
  const stop = () => {
    if (child.pid && child.exitCode === null && child.signalCode === null) {
      try {
        process.kill(-child.pid);
      } catch {
        // Already gone.
      }
    }
    rmSync(scratch, { recursive: true, force: true });
  };
  // Vitest doesn't run the teardown when the run is interrupted (Ctrl-C, or
  // a hook that is killed), and the server's own process group wouldn't get
  // the signal either: stop it on the way out, whatever the way out is.
  process.once("exit", stop);
  for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"] as const) {
    process.once(signal, () => {
      stop();
      process.exit(1);
    });
  }
  try {
    await ready(child, baseUrl, () => output);
  } catch (error) {
    stop();
    throw error;
  }
  project.provide("baseUrl", baseUrl);
  project.provide("serverState", state);
  project.provide("serverLog", logFile);
  return stop;
}

declare module "vitest" {
  export interface ProvidedContext {
    baseUrl: string;
    /** Where the server keeps its local database: `wrangler d1 execute --persist-to`. */
    serverState: string;
    /** A file holding everything the server has printed so far. */
    serverLog: string;
  }
}
