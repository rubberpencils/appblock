// The mail the maintainer gets about a feedback message (src/email.ts).
import { describe, expect, it } from "vitest";
import type { StoredFeedback } from "../../src/db.ts";
import { feedbackMail, type Mailer, sendEmail } from "../../src/email.ts";

const row: StoredFeedback = {
  id: "d253906d-8ed8-441b-8cc8-855b535da257",
  receivedAt: "2026-11-11T12:00:00.000Z",
  message: "The wall didn't come up on Instagram today.\nIt did yesterday.",
  contact: "tester@example.com",
  senderId: "3f9a2b6c-1d4e-4f5a-8b7c-9d0e1f2a3b4c",
  appVersion: "2.7.0",
  appBuild: 20,
  androidVersion: "14",
  androidSdk: 34,
  device: "OnePlus CPH2401",
  installedAt: "2026-09-30T12:00:00.000Z",
  updatedAt: "2026-11-09T08:00:00.000Z",
  diagnostics: '{"accessibility":true,"usage_access":false,"limited_apps":4}',
  country: "IN",
};

describe("the mail for a message with everything", () => {
  const mail = feedbackMail(row, "maintainer@example.com", 3);

  it("goes to the maintainer, from the feedback address", () => {
    expect(mail.to).toBe("maintainer@example.com");
    expect(mail.from).toEqual({
      email: "feedback@trymoly.com",
      name: "Moly feedback",
    });
  });

  it("is answered to the sender", () => {
    expect(mail.replyTo).toBe("tester@example.com");
  });

  it("says at a glance what it runs on and how long they've had it", () => {
    expect(mail.subject).toBe(
      "Moly feedback · 2.7.0 · OnePlus CPH2401 · installed 42 days",
    );
  });

  it("opens with the message as written, then the details", () => {
    expect(mail.text).toBe(
      [
        "The wall didn't come up on Instagram today.",
        "It did yesterday.",
        "",
        "--",
        "From: tester@example.com",
        "Sender: 3f9a2b6c-1d4e-4f5a-8b7c-9d0e1f2a3b4c (message 3 from them)",
        "App: Moly 2.7.0 (20) · Android 14 (SDK 34) · OnePlus CPH2401",
        "Installed: 2026-09-30 (42 days ago)",
        "Updated: 2026-11-09",
        "Country: IN",
        "Setup: accessibility: yes, usage_access: no, limited_apps: 4",
        "Received: 2026-11-11T12:00:00.000Z",
        "ID: d253906d-8ed8-441b-8cc8-855b535da257",
        "",
      ].join("\n"),
    );
  });
});

describe("the mail for a message and nothing else", () => {
  const bare: StoredFeedback = {
    ...row,
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
    country: null,
  };
  const mail = feedbackMail(bare, "maintainer@example.com");

  it("has no one to answer", () => {
    expect(mail.replyTo).toBeUndefined();
    expect(mail.text).toContain("From: no address left");
  });

  it("leaves out what it doesn't know", () => {
    expect(mail.subject).toBe("Moly feedback");
    expect(mail.text).toBe(
      [
        row.message,
        "",
        "--",
        "From: no address left",
        "Received: 2026-11-11T12:00:00.000Z",
        "ID: d253906d-8ed8-441b-8cc8-855b535da257",
        "",
      ].join("\n"),
    );
  });
});

describe("small things", () => {
  it("one day is a day", () => {
    const yesterday = { ...row, installedAt: "2026-11-10T11:00:00.000Z" };
    expect(feedbackMail(yesterday, "m@example.com").subject).toContain(
      "installed 1 day",
    );
  });

  it("installed today is 0 days, and a clock ahead of ours isn't less", () => {
    const ahead = { ...row, installedAt: "2026-11-11T13:00:00.000Z" };
    expect(feedbackMail(ahead, "m@example.com").subject).toContain(
      "installed 0 days",
    );
  });

  it("a setup report that isn't an object is shown as it came", () => {
    const odd = { ...row, diagnostics: "not json" };
    expect(feedbackMail(odd, "m@example.com").text).toContain(
      "Setup: not json",
    );
  });

  it("a very long device name doesn't make a very long subject", () => {
    const long = { ...row, device: "x".repeat(500) };
    expect(
      feedbackMail(long, "m@example.com").subject.length,
    ).toBeLessThanOrEqual(200);
  });
});

describe("sendEmail", () => {
  it("hands the mailer the mail, with no reply address when there is none", async () => {
    const sent: unknown[] = [];
    const mailer: Mailer = {
      send: async (message) => {
        sent.push(message);
      },
    };
    await sendEmail(mailer, feedbackMail(row, "m@example.com", 1));
    await sendEmail(
      mailer,
      feedbackMail({ ...row, contact: null }, "m@example.com"),
    );
    expect(sent[0]).toMatchObject({
      to: "m@example.com",
      replyTo: row.contact,
    });
    expect(sent[1]).not.toHaveProperty("replyTo");
  });
});
