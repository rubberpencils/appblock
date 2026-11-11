// Starts the whole backend (scripts/backend.ts: the real local server, with
// a scratch database) for the end-to-end tests, and stops it afterwards.
import type { TestProject } from "vitest/node";
import { startBackend } from "../../scripts/backend.ts";

export default async function setup(project: TestProject) {
  const backend = await startBackend();
  project.provide("baseUrl", backend.url);
  project.provide("serverState", backend.state);
  project.provide("serverLog", backend.logFile);
  return backend.stop;
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
