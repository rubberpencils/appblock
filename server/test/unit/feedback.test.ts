// What a feedback message from the app may hold (src/feedback.ts).
import { describe, expect, it } from "vitest";
import {
  type Feedback,
  MAX_MESSAGE_CHARS,
  parseFeedback,
} from "../../src/feedback.ts";

const now = Date.UTC(2026, 10, 11, 12);
const day = 24 * 60 * 60 * 1000;

/** Everything the app sends, as it sends it. */
const full = {
  id: "D253906D-8ED8-441B-8CC8-855B535DA257",
  message: "The wall didn't come up on Instagram today.",
  contact: "tester@example.com",
  senderId: "3F9A2B6C-1D4E-4F5A-8B7C-9D0E1F2A3B4C",
  appVersion: "2.7.0",
  appBuild: 20,
  androidVersion: "14",
  androidSdk: 34,
  device: "OnePlus CPH2401",
  installedAt: now - 42 * day,
  updatedAt: now - 2 * day,
  diagnostics: { accessibility: true, limited_apps: 4 },
};

function parsed(body: unknown): Feedback {
  const result = parseFeedback(body, now);
  if (!result.ok) throw new Error(`refused: ${result.field}`);
  return result.feedback;
}

function refusedField(body: unknown): string | null {
  const result = parseFeedback(body, now);
  return result.ok ? null : result.field;
}

describe("a message with everything the app sends", () => {
  it("is taken as sent, times as ISO text and the ids in lower case", () => {
    expect(parsed(full)).toEqual({
      id: "d253906d-8ed8-441b-8cc8-855b535da257",
      kind: "feedback",
      message: "The wall didn't come up on Instagram today.",
      contact: "tester@example.com",
      senderId: "3f9a2b6c-1d4e-4f5a-8b7c-9d0e1f2a3b4c",
      appVersion: "2.7.0",
      appBuild: 20,
      androidVersion: "14",
      androidSdk: 34,
      device: "OnePlus CPH2401",
      installedAt: new Date(now - 42 * day).toISOString(),
      updatedAt: new Date(now - 2 * day).toISOString(),
      diagnostics: '{"accessibility":true,"limited_apps":4}',
    });
  });

  it("ignores what it doesn't know", () => {
    expect(parsed({ ...full, somethingNew: [1, 2, 3] })).toEqual(parsed(full));
  });
});

describe("the message", () => {
  it("is all that's needed: the rest is empty", () => {
    expect(parsed({ message: "hi" })).toEqual({
      id: null,
      kind: "feedback",
      message: "hi",
      contact: null,
      senderId: null,
      appVersion: null,
      appBuild: null,
      androidVersion: null,
      androidSdk: null,
      device: null,
      installedAt: null,
      updatedAt: null,
      diagnostics: null,
    });
  });

  it("keeps its line breaks and loses the space around it", () => {
    expect(parsed({ message: "  one\r\ntwo\rthree\n\n " }).message).toBe(
      "one\ntwo\nthree",
    );
  });

  it("can be as long as anyone would write, in any script", () => {
    expect(
      parsed({ message: "a".repeat(MAX_MESSAGE_CHARS) }).message,
    ).toHaveLength(MAX_MESSAGE_CHARS);
    expect(parsed({ message: "മലയാളം 日本語 🙂" }).message).toBe(
      "മലയാളം 日本語 🙂",
    );
  });

  it.each([
    ["missing", {}],
    ["empty", { message: "" }],
    ["only space", { message: " \n\t " }],
    ["not text", { message: 42 }],
    ["past the limit", { message: "a".repeat(MAX_MESSAGE_CHARS + 1) }],
  ])("is refused when %s", (_, body) => {
    expect(refusedField(body)).toBe("message");
  });
});

describe("the kind of message", () => {
  it("is feedback or support, and feedback when it isn't said", () => {
    expect(parsed({ ...full, kind: "feedback" }).kind).toBe("feedback");
    expect(parsed({ ...full, kind: "support" }).kind).toBe("support");
    expect(parsed(full).kind).toBe("feedback");
  });

  it.each(["Support", "bug", "", 1, true])("%j is not a kind", (kind) => {
    expect(refusedField({ ...full, kind })).toBe("kind");
  });

  it("support needs an address to answer; feedback doesn't", () => {
    for (const contact of [undefined, null, "", "  "]) {
      expect(refusedField({ ...full, kind: "support", contact })).toBe(
        "contact",
      );
      expect(parsed({ ...full, kind: "feedback", contact }).contact).toBeNull();
    }
  });
});

describe("the reply address", () => {
  it.each([
    "tester@example.com",
    "a.b+c@mail.example.co.in",
    "josé@exämple.com",
  ])("%s could be one", (contact) => {
    expect(parsed({ ...full, contact }).contact).toBe(contact);
  });

  it.each([
    "not an address",
    "two@@example.com",
    "no-dot@example",
    `${"a".repeat(250)}@example.com`,
    // What means something else in a mail header.
    '"x"<y@z.de',
    "a,b@c.de",
    "a;b@c.de",
    "a b@c.de",
    "a\u00a0b@c.de",
    5,
  ])("%j isn't, and the message is refused for it", (contact) => {
    expect(refusedField({ ...full, contact })).toBe("contact");
  });
});

describe("what the app says about itself", () => {
  // A phone's clock years off, an app version nobody expected: the message
  // still matters. What can't be read is left out, and the rest kept.
  it.each([
    ["id", "not-a-uuid"],
    ["id", 7],
    ["senderId", "not-a-uuid"],
    ["senderId", "3f9a2b6c1d4e4f5a8b7c9d0e1f2a3b4c"],
    ["appVersion", "v".repeat(41)],
    ["appBuild", 0],
    ["appBuild", 2.5],
    ["appBuild", "20"],
    ["androidVersion", 14],
    ["androidSdk", -1],
    ["androidSdk", 10_000],
    ["device", "d".repeat(121)],
    ["installedAt", "yesterday"],
    ["installedAt", 0],
    ["installedAt", Date.UTC(2015, 5, 1)],
    ["updatedAt", now + 3 * day],
    ["diagnostics", "accessibility"],
    ["diagnostics", [1, 2]],
    ["diagnostics", { blob: "x".repeat(4000) }],
  ])(
    "%s that makes no sense (%j) is left out, and the message taken",
    (field, value) => {
      const taken = parsed({ ...full, [field]: value });
      expect(taken[field as keyof Feedback]).toBeNull();
      expect(taken.message).toBe(full.message);
      expect({ ...taken, [field]: undefined }).toEqual({
        ...parsed(full),
        [field]: undefined,
      });
    },
  );

  it("an empty or absent one is simply empty", () => {
    const body = { ...full, contact: "  ", device: null, diagnostics: null };
    expect(parsed(body)).toMatchObject({
      contact: null,
      device: null,
      diagnostics: null,
    });
  });

  it("a line break inside one becomes a space: none can add a line to the mail", () => {
    expect(
      parsed({ ...full, device: "Pixel\r\nBcc: someone@example.com" }).device,
    ).toBe("Pixel Bcc: someone@example.com");
  });
});

describe("what isn't an object at all", () => {
  it.each([null, "text", 5, [], [full]])("%j is refused", (body) => {
    expect(refusedField(body)).toBe("body");
  });
});
