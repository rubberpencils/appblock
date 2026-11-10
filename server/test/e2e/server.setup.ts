// Starts the real local server (`wrangler dev`: the Worker in workerd, with
// the static site in front of it, as in production) on a free port for the
// end-to-end tests, and stops it afterwards.
import { type ChildProcess, spawn } from "node:child_process";
import { createServer } from "node:net";
import { resolve } from "node:path";
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
    if (child.exitCode !== null) {
      throw new Error(`wrangler dev exited (${child.exitCode}):\n${log()}`);
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
  let output = "";
  // Its own process group, so stopping it also stops the workerd it starts.
  const child = spawn(
    resolve(server, "node_modules", ".bin", "wrangler"),
    ["dev", "--port", String(port), "--ip", "127.0.0.1"],
    { cwd: server, detached: true, stdio: ["ignore", "pipe", "pipe"] },
  );
  child.stdout?.on("data", (chunk) => {
    output += chunk;
  });
  child.stderr?.on("data", (chunk) => {
    output += chunk;
  });
  const stop = () => {
    if (child.pid && child.exitCode === null) process.kill(-child.pid);
  };
  try {
    await ready(child, baseUrl, () => output);
  } catch (error) {
    stop();
    throw error;
  }
  project.provide("baseUrl", baseUrl);
  return stop;
}

declare module "vitest" {
  export interface ProvidedContext {
    baseUrl: string;
  }
}
