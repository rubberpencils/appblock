// The rule files the app's full-stack tests are served (scripts/with-backend.ts).
import { createPublicKey, verify } from "node:crypto";
import { describe, expect, it } from "vitest";
import { fixtures } from "../../scripts/with-backend.ts";

const now = new Date("2026-11-11T12:00:30.500Z");
const made = fixtures(now);
const key = createPublicKey({
  key: Buffer.from(made.publicKey, "base64"),
  format: "der",
  type: "spki",
});

function open(path: string): { rules: string; sig: string } {
  return JSON.parse(made.files[`e2e/rules/${path}/v1/rules.signed.json`]);
}

/** As the app checks a rules file: ECDSA P-256 over the rules' bytes. */
function signatureHolds(file: { rules: string; sig: string }): boolean {
  return verify(
    "sha256",
    Buffer.from(file.rules, "utf8"),
    key,
    Buffer.from(file.sig, "base64"),
  );
}

describe("the rules served to the app's full-stack tests", () => {
  it("are signed with the run's own key, whose public half is served too", () => {
    expect(signatureHolds(open("old"))).toBe(true);
    expect(signatureHolds(open("new"))).toBe(true);
    expect(made.files["e2e/public-key.txt"].trim()).toBe(made.publicKey);
  });

  it("the tampered file carries a real signature over other rules: it doesn't hold", () => {
    expect(open("tampered").sig).toBe(open("new").sig);
    expect(open("tampered").rules).not.toBe(open("new").rules);
    expect(signatureHolds(open("tampered"))).toBe(false);
  });

  it("the stranger's file is newer than the fix and truly signed, but not with the run's key", () => {
    const strangers = open("stranger");
    expect(JSON.parse(strangers.rules).updated).toBe("2026-11-11T13:00:30Z");
    expect(signatureHolds(strangers)).toBe(false);
    expect(strangers.sig).not.toBe(open("new").sig);
  });

  it("the fix names the player's view; the old rules name one it no longer has", () => {
    const fixture = "xyz.robinroy.appblock.fixture";
    expect(JSON.parse(open("new").rules).feeds[fixture][0].ids).toEqual([
      "reel_watch_player",
    ]);
    expect(JSON.parse(open("old").rules).feeds[fixture][0].ids).not.toContain(
      "reel_watch_player",
    );
  });

  it("are dated to the second, the fix after the old rules and the tampered file after both", () => {
    const updated = (path: string) =>
      JSON.parse(open(path).rules).updated as string;
    expect(updated("old")).toBe("2026-11-11T10:00:30Z");
    expect(updated("new")).toBe("2026-11-11T11:00:30Z");
    expect(updated("tampered")).toBe("2026-11-11T12:00:30Z");
  });

  it("a new key every run: nothing signed here is worth keeping", () => {
    expect(fixtures(now).publicKey).not.toBe(made.publicKey);
  });
});
