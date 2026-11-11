// Feedback as the app sends it: a real HTTP request to the local server,
// then the two things that must follow, looked for where they really are.
// The row, in the server's database file; the mail, in what the server
// printed (locally a mail isn't sent, it is printed with its text in a file).
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, inject, it } from "vitest";

const baseUrl = inject("baseUrl");
const state = inject("serverState");
const log = inject("serverLog");
const server = resolve(fileURLToPath(import.meta.url), "..", "..", "..");

function send(body: unknown, headers: Record<string, string> = {}) {
  return fetch(`${baseUrl}/api/feedback`, {
    method: "POST",
    // What Feedback.kt sends.
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "User-Agent": "Moly",
      ...headers,
    },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

/** Rows of the server's database, read from its file as `pnpm db:feedback` does. */
function query(sql: string): Record<string, unknown>[] {
  const out = execFileSync(
    resolve(server, "node_modules", ".bin", "wrangler"),
    [
      "d1",
      "execute",
      "moly",
      "--local",
      "--persist-to",
      state,
      "--json",
      "--command",
      sql,
    ],
    { cwd: server, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
  );
  return JSON.parse(out)[0].results;
}

async function eventually<T>(look: () => T | undefined, ms = 5000): Promise<T> {
  const until = Date.now() + ms;
  for (;;) {
    const found = look();
    if (found !== undefined) return found;
    if (Date.now() > until) throw new Error("didn't happen in time");
    await new Promise((wait) => setTimeout(wait, 100));
  }
}

const message = `The wall didn't come up on Instagram today. (${crypto.randomUUID()})`;
const full = {
  message,
  contact: "tester@example.com",
  senderId: crypto.randomUUID(),
  appVersion: "2.7.0",
  appBuild: 20,
  androidVersion: "14",
  androidSdk: 34,
  device: "OnePlus CPH2401",
  installedAt: Date.now() - 42 * 24 * 60 * 60 * 1000,
  updatedAt: Date.now() - 60 * 60 * 1000,
  diagnostics: { accessibility: true, limited_apps: 4 },
};

describe("feedback sent to the local server", () => {
  it("is answered, saved in the database and mailed to the maintainer", async () => {
    const response = await send(full);
    expect(response.status).toBe(201);
    expect(response.headers.get("content-type")).toContain("application/json");
    const { id, receivedAt } = (await response.json()) as {
      id: string;
      receivedAt: string;
    };

    const saved = query(`SELECT * FROM feedback WHERE id = '${id}'`);
    expect(saved).toHaveLength(1);
    expect(saved[0]).toMatchObject({
      received_at: receivedAt,
      message,
      contact: "tester@example.com",
      sender_id: full.senderId,
      app_version: "2.7.0",
      device: "OnePlus CPH2401",
      emailed: 1,
    });

    // The mail: who to, what about, and the message itself in its text.
    const textFile = await eventually(() => {
      const printed = readFileSync(log, "utf8");
      const at = printed.indexOf("send_email binding called");
      if (at < 0) return undefined;
      const mail = printed.slice(at);
      expect(mail).toContain("To: maintainer@example.com");
      expect(mail).toContain(
        "Subject: Moly feedback · 2.7.0 · OnePlus CPH2401 · installed 42 days",
      );
      return /Text: (\S+\.txt)/.exec(mail)?.[1];
    });
    const text = readFileSync(textFile, "utf8");
    expect(text).toContain(message);
    expect(text).toContain("From: tester@example.com");
    expect(text).toContain(`ID: ${id}`);
  });

  it("refuses what it can't take, in JSON, saving nothing", async () => {
    const marker = crypto.randomUUID();
    const empty = await send({ message: " ", device: marker });
    expect(empty.status).toBe(400);
    expect(await empty.json()).toEqual({ error: "invalid", field: "message" });

    const notJson = await send("message=hi", { "Content-Type": "text/plain" });
    expect(notJson.status).toBe(415);

    expect(
      query(`SELECT id FROM feedback WHERE device = '${marker}'`),
    ).toHaveLength(0);
  });

  it("is only there to be posted to: opening it in a browser finds nothing", async () => {
    const response = await fetch(`${baseUrl}/api/feedback`, {
      headers: { "Sec-Fetch-Mode": "navigate", Accept: "text/html" },
    });
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: "not_found" });
  });
});
