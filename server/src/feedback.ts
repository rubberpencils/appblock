// What a feedback message from the app may hold, and the checks on it.
// Plain logic: no database, no network.

const DAY_MS = 24 * 60 * 60 * 1000;

// The message is long enough for anything a person would write; the app
// stops at the same length without saying so. The whole request has room
// for that message in any script (up to 4 bytes a character) and the rest.
export const MAX_MESSAGE_CHARS = 20_000;
export const MAX_BODY_BYTES = 128 * 1024;
const MAX_CONTACT_CHARS = 254;
const MAX_DIAGNOSTICS_CHARS = 4000;

/** A message as it is stored, before the server adds its own fields. */
export type Kind = "feedback" | "support";

export interface Feedback {
  /**
   * The message's own id, made up by the app before its first try, so that
   * a second try of the same message isn't a second message. Null from an
   * app that sends none.
   */
  id: string | null;
  /** Support is someone stuck, who needs an answer; feedback is everything else. */
  kind: Kind;
  message: string;
  contact: string | null;
  /** A random id the app made up once and keeps: the same person's messages share it. */
  senderId: string | null;
  appVersion: string | null;
  appBuild: number | null;
  androidVersion: string | null;
  androidSdk: number | null;
  device: string | null;
  /** UTC, ISO 8601. */
  installedAt: string | null;
  updatedAt: string | null;
  /** The app's report on its own setup, as JSON text. */
  diagnostics: string | null;
}

export type Parsed =
  | { ok: true; feedback: Feedback }
  | { ok: false; field: string };

/** Text with no control characters (line breaks included), trimmed. */
function oneLine(value: string): string {
  // biome-ignore lint/suspicious/noControlCharactersInRegex: removing them is the point
  return value.replace(/[\u0000-\u001f\u007f]+/g, " ").trim();
}

function shortText(value: unknown, max: number): string | null | undefined {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string") return undefined;
  const text = oneLine(value);
  if (text.length > max) return undefined;
  return text === "" ? null : text;
}

function wholeNumber(
  value: unknown,
  min: number,
  max: number,
): number | null | undefined {
  if (value === undefined || value === null) return null;
  if (typeof value !== "number" || !Number.isInteger(value)) return undefined;
  return value >= min && value <= max ? value : undefined;
}

// An address worth replying to: something@something.something, with none of
// the characters that mean something else in a mail header. (The app
// checks the same: Storage.feedbackContact.)
const ADDRESS =
  /^[^\s@<>()[\]",;:\\]+@[^\s@<>()[\]",;:\\]+\.[^\s@<>()[\]",;:\\]+$/;

/** A UUID in lower case, or null for anything else. */
function uuid(value: unknown): string | null {
  return typeof value === "string" &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      value,
    )
    ? value.toLowerCase()
    : null;
}

/** A time the app reports (ms since 1970) as ISO text; not before 2020, not after `now`. */
function pastTime(value: unknown, now: number): string | null | undefined {
  if (value === undefined || value === null) return null;
  if (typeof value !== "number" || !Number.isFinite(value)) return undefined;
  if (value < Date.UTC(2020, 0, 1) || value > now + DAY_MS) return undefined;
  return new Date(value).toISOString();
}

/**
 * Checks what the app sent. What can't be worked with is refused, naming
 * the field: no message, a kind or a reply address that isn't one. Anything
 * else it doesn't know or can't read is left out.
 */
export function parseFeedback(body: unknown, now: number): Parsed {
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    return { ok: false, field: "body" };
  }
  const input = body as Record<string, unknown>;
  const bad = (field: string): Parsed => ({ ok: false, field });

  // An app from before there were kinds sends none: feedback.
  const kind = input.kind ?? "feedback";
  if (kind !== "feedback" && kind !== "support") return bad("kind");

  if (typeof input.message !== "string") return bad("message");
  const message = input.message.replace(/\r\n?/g, "\n").trim();
  if (message === "" || message.length > MAX_MESSAGE_CHARS) {
    return bad("message");
  }

  const contact = shortText(input.contact, MAX_CONTACT_CHARS);
  if (contact === undefined || (contact !== null && !ADDRESS.test(contact))) {
    return bad("contact");
  }
  // Support can only be answered by mail.
  if (kind === "support" && contact === null) return bad("contact");

  // The rest is what the app says about itself. The message matters more
  // than any of it: something there that makes no sense (a phone whose
  // clock was years off when Moly was installed) is left out, not refused.
  const or = <T>(value: T | null | undefined): T | null => value ?? null;
  const id = uuid(input.id);
  const senderId = uuid(input.senderId);
  const appVersion = or(shortText(input.appVersion, 40));
  const appBuild = or(wholeNumber(input.appBuild, 1, 1_000_000_000));
  const androidVersion = or(shortText(input.androidVersion, 20));
  const androidSdk = or(wholeNumber(input.androidSdk, 1, 1000));
  const device = or(shortText(input.device, 120));
  const installedAt = or(pastTime(input.installedAt, now));
  const updatedAt = or(pastTime(input.updatedAt, now));

  let diagnostics: string | null = null;
  const d = input.diagnostics;
  if (typeof d === "object" && d !== null && !Array.isArray(d)) {
    const text = JSON.stringify(d);
    if (text.length <= MAX_DIAGNOSTICS_CHARS) diagnostics = text;
  }

  return {
    ok: true,
    feedback: {
      id,
      kind,
      message,
      contact,
      senderId,
      appVersion,
      appBuild,
      androidVersion,
      androidSdk,
      device,
      installedAt,
      updatedAt,
      diagnostics,
    },
  };
}
