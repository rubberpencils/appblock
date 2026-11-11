// Mail from the Worker. Everything that sends goes through sendEmail, so
// what does the sending (Cloudflare's binding today) can change in one place.
import { COUNT_UP_TO, type StoredFeedback } from "./db.ts";

export interface Mail {
  from: { email: string; name: string };
  to: string;
  subject: string;
  text: string;
  /** Where a reply goes, when it isn't `from`. */
  replyTo?: string;
}

/** What sends: the EMAIL binding in the Worker, a stand-in in tests. */
export interface Mailer {
  send(message: {
    from: { email: string; name: string };
    to: string;
    subject: string;
    text: string;
    replyTo?: string;
  }): Promise<unknown>;
}

export async function sendEmail(mailer: Mailer, mail: Mail): Promise<void> {
  await mailer.send({
    from: mail.from,
    to: mail.to,
    subject: mail.subject,
    text: mail.text,
    ...(mail.replyTo ? { replyTo: mail.replyTo } : {}),
  });
}

const FROM = { email: "feedback@trymoly.com", name: "Moly" };
const DAY_MS = 24 * 60 * 60 * 1000;

function days(n: number): string {
  return n === 1 ? "1 day" : `${n} days`;
}

/** "2026-09-30", from ISO text. */
function date(iso: string): string {
  return iso.slice(0, 10);
}

/** The setup report as "accessibility: yes, limited_apps: 4"; as sent if it isn't an object. */
function setupLine(diagnostics: string): string {
  try {
    const parsed: unknown = JSON.parse(diagnostics);
    if (typeof parsed !== "object" || parsed === null) return diagnostics;
    return Object.entries(parsed)
      .map(([key, value]) => {
        const shown =
          value === true ? "yes" : value === false ? "no" : String(value);
        return `${key}: ${shown}`;
      })
      .join(", ");
  } catch {
    return diagnostics;
  }
}

/**
 * The mail telling the maintainer about a feedback message: the message
 * first, then who and what it came from. A reply goes to the sender when
 * they left an address.
 */
// The order of the lines below is the order they are read in: the message,
// who, what on, how long they have had it.
export function feedbackMail(
  row: StoredFeedback,
  to: string,
  counts: {
    /** How many messages this sender has sent, this one included; 0 when not known. */
    fromSender?: number;
    /** How many earlier messages were never mailed. */
    unmailed?: number;
  } = {},
): Mail {
  const fromSender = counts.fromSender ?? 0;
  const unmailed = counts.unmailed ?? 0;
  const upTo = (n: number) =>
    n >= COUNT_UP_TO ? `${COUNT_UP_TO}+` : String(n);
  const received = Date.parse(row.receivedAt);
  const installedDays =
    row.installedAt === null
      ? null
      : Math.max(
          0,
          Math.floor((received - Date.parse(row.installedAt)) / DAY_MS),
        );

  // The kind first, in brackets: a support request stands out in the inbox.
  const subject = [
    `[${row.kind === "support" ? "Support" : "Feedback"}] Moly`,
    row.appVersion,
    row.device,
    installedDays === null ? null : `installed ${days(installedDays)}`,
  ]
    .filter((part) => part !== null)
    .join(" · ")
    .slice(0, 200);

  const app = [
    row.appVersion === null
      ? null
      : `Moly ${row.appVersion}${row.appBuild === null ? "" : ` (${row.appBuild})`}`,
    row.androidVersion === null
      ? null
      : `Android ${row.androidVersion}${row.androidSdk === null ? "" : ` (SDK ${row.androidSdk})`}`,
    row.device,
  ].filter((part) => part !== null);

  const lines = [
    row.message,
    "",
    "--",
    `From: ${row.contact ?? "no address left"}`,
    row.senderId === null
      ? null
      : `Sender: ${row.senderId}${fromSender > 0 ? ` (message ${upTo(fromSender)} from them)` : ""}`,
    app.length > 0 ? `App: ${app.join(" · ")}` : null,
    row.installedAt === null
      ? null
      : `Installed: ${date(row.installedAt)} (${days(installedDays ?? 0)} ago)`,
    row.updatedAt === null ? null : `Updated: ${date(row.updatedAt)}`,
    row.country === null ? null : `Country: ${row.country}`,
    row.diagnostics === null ? null : `Setup: ${setupLine(row.diagnostics)}`,
    `Received: ${row.receivedAt}`,
    `ID: ${row.id}`,
    // A mail can fail while its message is saved: this is how that shows.
    unmailed === 0
      ? null
      : `\nNever mailed: ${upTo(unmailed)} earlier ${unmailed === 1 ? "message" : "messages"}. In the database: SELECT * FROM feedback WHERE emailed = 0.`,
  ].filter((line) => line !== null);

  return {
    from: FROM,
    to,
    subject,
    text: `${lines.join("\n")}\n`,
    ...(row.contact ? { replyTo: row.contact } : {}),
  };
}
