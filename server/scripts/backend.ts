// The whole backend on this machine, for tests: the real local server
// (`wrangler dev`: the Worker in workerd, with the static site in front of
// it, as in production) on a free port, with a database of its own made
// for the run from migrations/. It never touches the one `pnpm dev` uses.
// The server's e2e tests start it (test/e2e/server.setup.ts), and so do the
// app's (scripts/with-backend.ts).
import { type ChildProcess, execFileSync, spawn } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const server = resolve(fileURLToPath(import.meta.url), "..", "..");
const wrangler = resolve(server, "node_modules", ".bin", "wrangler");
const READY_TIMEOUT_MS = 90_000;

export interface Backend {
  /** http://127.0.0.1:<port> */
  url: string;
  port: number;
  /** Where its database is: `wrangler d1 execute --local --persist-to`. */
  state: string;
  /** A file holding everything the server has printed so far (a mail isn't sent locally: it is printed). */
  logFile: string;
  /** A folder of its own, gone when it stops. */
  scratch: string;
  stop(): void;
}

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
async function ready(child: ChildProcess, url: string, log: () => string) {
  const deadline = Date.now() + READY_TIMEOUT_MS;
  while (Date.now() < deadline) {
    if (child.exitCode !== null || child.signalCode !== null) {
      const how = child.signalCode ?? child.exitCode;
      throw new Error(`wrangler dev exited (${how}):\n${log()}`);
    }
    try {
      const response = await fetch(`${url}/api/health`);
      await response.body?.cancel();
      if (response.ok) return;
    } catch {
      // Not listening yet.
    }
    await new Promise((wait) => setTimeout(wait, 250));
  }
  throw new Error(`wrangler dev wasn't ready in time:\n${log()}`);
}

/**
 * Starts it. `site`, given the scratch folder, may return a folder to serve
 * in place of site/ (the app's tests add files of their own to a copy).
 */
export async function startBackend(
  options: { site?: (scratch: string) => string } = {},
): Promise<Backend> {
  const port = await freePort();
  const url = `http://127.0.0.1:${port}`;
  const scratch = mkdtempSync(join(tmpdir(), "moly-backend-"));
  const state = join(scratch, "state");
  const logFile = join(scratch, "server.log");
  writeFileSync(logFile, "");
  execFileSync(
    wrangler,
    ["d1", "migrations", "apply", "moly", "--local", "--persist-to", state],
    { cwd: server, stdio: "pipe" },
  );
  const site = options.site?.(scratch);

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
      ...(site ? ["--assets", site] : []),
    ],
    { cwd: server, detached: true, stdio: ["ignore", "pipe", "pipe"] },
  );
  const keep = (chunk: unknown) => {
    output += chunk;
    writeFileSync(logFile, String(chunk), { flag: "a" });
  };
  child.stdout?.on("data", keep);
  child.stderr?.on("data", keep);
  let stopped = false;
  const stop = () => {
    if (stopped) return;
    stopped = true;
    if (child.pid && child.exitCode === null && child.signalCode === null) {
      try {
        process.kill(-child.pid);
      } catch {
        // Already gone.
      }
    }
    rmSync(scratch, { recursive: true, force: true });
  };
  // A run that is interrupted (Ctrl-C, a hook that is killed) skips the
  // caller's own teardown, and the server's process group wouldn't get the
  // signal either: stop it on the way out, whatever the way out is.
  process.once("exit", stop);
  for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"] as const) {
    process.once(signal, () => {
      stop();
      process.exit(1);
    });
  }
  try {
    await ready(child, url, () => output);
  } catch (error) {
    stop();
    throw error;
  }
  return { url, port, state, logFile, scratch, stop };
}
