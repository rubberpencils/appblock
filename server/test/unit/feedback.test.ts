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
  it("is taken as sent, times as ISO text and the sender id in lower case", () => {
    expect(parsed(full)).toEqual({
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

describe("the rest, each refused by name when wrong", () => {
  it.each([
    ["contact", "not an address"],
    ["contact", "two@@example.com"],
    ["contact", `${"a".repeat(250)}@example.com`],
    ["contact", 5],
    ["senderId", "not-a-uuid"],
    ["senderId", "3f9a2b6c1d4e4f5a8b7c9d0e1f2a3b4c"],
    ["appVersion", "v".repeat(41)],
    ["appBuild", 0],
    ["appBuild", 2.5],
    ["appBuild", "20"],
    ["androidVersion", 14],
    ["androidSdk", -1],
    ["device", "d".repeat(121)],
    ["installedAt", "yesterday"],
    ["installedAt", Date.UTC(2019, 11, 31)],
    ["updatedAt", now + 2 * day],
    ["diagnostics", "accessibility"],
    ["diagnostics", [1, 2]],
    ["diagnostics", { blob: "x".repeat(4000) }],
  ])("%s: %j", (field, value) => {
    expect(refusedField({ ...full, [field]: value })).toBe(field);
  });

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
