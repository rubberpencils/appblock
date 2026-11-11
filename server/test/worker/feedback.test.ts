// POST /api/feedback inside workerd, with a database of its own (the tables
// from migrations/, test/worker/apply-migrations.ts) and the mail binding
// as `wrangler dev` has it: it takes the message and sends nothing.
import { env, exports } from "cloudflare:workers";
import { beforeEach, describe, expect, it } from "vitest";
import type { Mailer } from "../../src/email.ts";
import { MAX_BODY_BYTES, MAX_MESSAGE_CHARS } from "../../src/feedback.ts";
import app from "../../src/index.ts";

const url = "https://trymoly.com/api/feedback";

const full = {
  message: "The wall didn't come up on Instagram today.",
  contact: "tester@example.com",
  senderId: "3f9a2b6c-1d4e-4f5a-8b7c-9d0e1f2a3b4c",
  appVersion: "2.7.0",
  appBuild: 20,
  androidVersion: "14",
  androidSdk: 34,
  device: "OnePlus CPH2401",
  installedAt: Date.now() - 42 * 24 * 60 * 60 * 1000,
  updatedAt: Date.now() - 60 * 60 * 1000,
  diagnostics: { accessibility: true, limited_apps: 4 },
};

function post(body: unknown, headers: Record<string, string> = {}) {
  return new Request(url, {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

interface Row {
  id: string;
  received_at: string;
  kind: string;
  message: string;
  contact: string | null;
  sender_id: string | null;
  app_version: string | null;
  app_build: number | null;
  android_version: string | null;
  android_sdk: number | null;
  device: string | null;
  installed_at: string | null;
  updated_at: string | null;
  diagnostics: string | null;
  country: string | null;
  emailed: number;
}

async function rows(): Promise<Row[]> {
  const all = await env.DB.prepare(
    "SELECT * FROM feedback ORDER BY received_at, rowid",
  ).all<Row>();
  return all.results;
}

/** The Worker with a mailer of the test's own in place of the binding. */
function withMailer(request: Request, mailer: Mailer, vars: Partial<Env> = {}) {
  return app.fetch(request, {
    ...env,
    EMAIL: mailer,
    ...vars,
  } as unknown as Env);
}

beforeEach(async () => {
  await env.DB.exec("DELETE FROM feedback");
});

describe("a message from the app", () => {
  it("is saved whole, and answered with its id and when it arrived", async () => {
    const before = Date.now();
    const response = await exports.default.fetch(post(full));
    expect(response.status).toBe(201);
    const answer = await response.json<{ id: string; receivedAt: string }>();
    expect(answer.id).toMatch(/^[0-9a-f-]{36}$/);

    const saved = await rows();
    expect(saved).toHaveLength(1);
    expect(saved[0]).toMatchObject({
      id: answer.id,
      received_at: answer.receivedAt,
      kind: "feedback",
      message: full.message,
      contact: full.contact,
      sender_id: full.senderId,
      app_version: "2.7.0",
      app_build: 20,
      android_version: "14",
      android_sdk: 34,
      device: "OnePlus CPH2401",
      installed_at: new Date(full.installedAt).toISOString(),
      updated_at: new Date(full.updatedAt).toISOString(),
      diagnostics: '{"accessibility":true,"limited_apps":4}',
    });
    expect(Date.parse(saved[0].received_at)).toBeGreaterThanOrEqual(before);
    expect(Date.parse(saved[0].received_at)).toBeLessThanOrEqual(Date.now());
  });

  it("is marked as mailed once the mail went", async () => {
    await exports.default.fetch(post(full));
    expect((await rows())[0].emailed).toBe(1);
  });

  it("is kept as support when it says so, and its mail is marked", async () => {
    const sent: Parameters<Mailer["send"]>[0][] = [];
    const mailer: Mailer = {
      send: async (message) => {
        sent.push(message);
      },
    };
    const response = await withMailer(
      post({ ...full, kind: "support" }),
      mailer,
    );
    expect(response.status).toBe(201);
    expect((await rows())[0].kind).toBe("support");
    expect(sent[0].subject.startsWith("[Support] Moly")).toBe(true);
  });

  it("needs only the message", async () => {
    const response = await exports.default.fetch(post({ message: "hi" }));
    expect(response.status).toBe(201);
    expect((await rows())[0]).toMatchObject({
      kind: "feedback",
      message: "hi",
      contact: null,
      sender_id: null,
      device: null,
      diagnostics: null,
    });
  });

  it("can be as long as the app lets it be, in any script", async () => {
    const long = "ദ".repeat(MAX_MESSAGE_CHARS);
    const response = await exports.default.fetch(post({ message: long }));
    expect(response.status).toBe(201);
    expect((await rows())[0].message).toBe(long);
  });

  it("keeps where it came from when Cloudflare says, and nothing about the address", async () => {
    const request = post(full, { "cf-connecting-ip": "203.0.113.7" });
    const response = await app.fetch(
      new Request(request, { cf: { country: "IN" } } as RequestInit),
      env,
    );
    expect(response.status).toBe(201);
    const saved = (await rows())[0];
    expect(saved.country).toBe("IN");
    expect(JSON.stringify(saved)).not.toContain("203.0.113.7");
  });

  it("gets a different id each time, the same message twice included", async () => {
    await exports.default.fetch(post(full));
    await exports.default.fetch(post(full));
    const saved = await rows();
    expect(saved).toHaveLength(2);
    expect(saved[0].id).not.toBe(saved[1].id);
  });
});

describe("the mail about it", () => {
  it("goes to the address in FEEDBACK_TO, answerable to the sender", async () => {
    const sent: Parameters<Mailer["send"]>[0][] = [];
    const mailer: Mailer = {
      send: async (message) => {
        sent.push(message);
      },
    };
    const response = await withMailer(post(full), mailer);
    expect(response.status).toBe(201);
    expect(sent).toHaveLength(1);
    expect(sent[0].to).toBe(env.FEEDBACK_TO);
    expect(sent[0].replyTo).toBe(full.contact);
    expect(sent[0].text).toContain(full.message);
    expect(sent[0].text).toContain((await rows())[0].id);
  });

  it("counts the sender's messages: the second says so", async () => {
    const sent: Parameters<Mailer["send"]>[0][] = [];
    const mailer: Mailer = {
      send: async (message) => {
        sent.push(message);
      },
    };
    await withMailer(post(full), mailer);
    await withMailer(post({ ...full, message: "And again." }), mailer);
    await withMailer(post({ message: "Someone else, no id." }), mailer);
    expect(sent[0].text).toContain("(message 1 from them)");
    expect(sent[1].text).toContain("(message 2 from them)");
    expect(sent[2].text).not.toContain("Sender:");
  });

  it("failing doesn't lose the message: it is saved, not marked as mailed, and the app is told it arrived", async () => {
    const down: Mailer = {
      send: async () => {
        throw new Error("mail is down");
      },
    };
    const response = await withMailer(post(full), down);
    expect(response.status).toBe(201);
    const saved = await rows();
    expect(saved).toHaveLength(1);
    expect(saved[0].message).toBe(full.message);
    expect(saved[0].emailed).toBe(0);
  });

  it("isn't tried with nowhere to send it: saved, not marked as mailed", async () => {
    let tried = false;
    const mailer: Mailer = {
      send: async () => {
        tried = true;
      },
    };
    const response = await withMailer(post(full), mailer, { FEEDBACK_TO: "" });
    expect(response.status).toBe(201);
    expect(tried).toBe(false);
    expect((await rows())[0].emailed).toBe(0);
  });
});

describe("what is refused, and saves nothing", () => {
  it.each([
    ["no message", { ...full, message: "" }, "message"],
    [
      "a message past the limit",
      { message: "a".repeat(MAX_MESSAGE_CHARS + 1) },
      "message",
    ],
    [
      "a contact that isn't an address",
      { ...full, contact: "nope" },
      "contact",
    ],
    ["a sender id that isn't one", { ...full, senderId: "abc" }, "senderId"],
    ["a kind that isn't one", { ...full, kind: "urgent" }, "kind"],
    [
      "support with no address to answer",
      { message: "Help", kind: "support" },
      "contact",
    ],
    ["a list", [full], "body"],
  ])("%s: 400, naming the field", async (_, body, field) => {
    const response = await exports.default.fetch(post(body));
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: "invalid", field });
    expect(await rows()).toHaveLength(0);
  });

  it("text that isn't JSON: 400", async () => {
    const response = await exports.default.fetch(post("message=hi"));
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: "invalid", field: "body" });
  });

  it("anything but JSON: 415", async () => {
    const response = await exports.default.fetch(
      post(JSON.stringify(full), { "content-type": "text/plain" }),
    );
    expect(response.status).toBe(415);
    expect(await response.json()).toEqual({ error: "unsupported_media_type" });
    expect(await rows()).toHaveLength(0);
  });

  it("a body past the size limit: 413, whether or not it says how big it is", async () => {
    const huge = JSON.stringify({
      message: "a",
      filler: "x".repeat(MAX_BODY_BYTES),
    });
    const declared = await exports.default.fetch(post(huge));
    expect(declared.status).toBe(413);
    expect(await declared.json()).toEqual({ error: "too_large" });

    const streamed = await exports.default.fetch(
      new Request(url, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: new Blob([huge]).stream(),
        duplex: "half",
      } as RequestInit),
    );
    expect(streamed.status).toBe(413);
    expect(await rows()).toHaveLength(0);
  });

  it.each(["GET", "PUT", "DELETE"])(
    "%s: there is no such thing",
    async (method) => {
      const response = await exports.default.fetch(url, { method });
      expect(response.status).toBe(404);
      expect(await response.json()).toEqual({ error: "not_found" });
    },
  );
});
