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
export interface Feedback {
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

/** A time the app reports (ms since 1970) as ISO text; not before 2020, not after `now`. */
function pastTime(value: unknown, now: number): string | null | undefined {
  if (value === undefined || value === null) return null;
  if (typeof value !== "number" || !Number.isFinite(value)) return undefined;
  if (value < Date.UTC(2020, 0, 1) || value > now + DAY_MS) return undefined;
  return new Date(value).toISOString();
}

/**
 * Checks what the app sent. Anything it doesn't know is ignored; anything
 * it knows and finds wrong is refused, naming the field.
 */
export function parseFeedback(body: unknown, now: number): Parsed {
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    return { ok: false, field: "body" };
  }
  const input = body as Record<string, unknown>;
  const bad = (field: string): Parsed => ({ ok: false, field });

  if (typeof input.message !== "string") return bad("message");
  const message = input.message.replace(/\r\n?/g, "\n").trim();
  if (message === "" || message.length > MAX_MESSAGE_CHARS) {
    return bad("message");
  }

  const contact = shortText(input.contact, MAX_CONTACT_CHARS);
  // One @ with something either side and a dot after it: enough to reply to.
  if (
    contact === undefined ||
    (contact !== null && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(contact))
  ) {
    return bad("contact");
  }

  const senderId = shortText(input.senderId, 36);
  if (
    senderId === undefined ||
    (senderId !== null &&
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
        senderId,
      ))
  ) {
    return bad("senderId");
  }

  const appVersion = shortText(input.appVersion, 40);
  if (appVersion === undefined) return bad("appVersion");
  const appBuild = wholeNumber(input.appBuild, 1, 1_000_000_000);
  if (appBuild === undefined) return bad("appBuild");
  const androidVersion = shortText(input.androidVersion, 20);
  if (androidVersion === undefined) return bad("androidVersion");
  const androidSdk = wholeNumber(input.androidSdk, 1, 1000);
  if (androidSdk === undefined) return bad("androidSdk");
  const device = shortText(input.device, 120);
  if (device === undefined) return bad("device");
  const installedAt = pastTime(input.installedAt, now);
  if (installedAt === undefined) return bad("installedAt");
  const updatedAt = pastTime(input.updatedAt, now);
  if (updatedAt === undefined) return bad("updatedAt");

  let diagnostics: string | null = null;
  if (input.diagnostics !== undefined && input.diagnostics !== null) {
    const d = input.diagnostics;
    if (typeof d !== "object" || Array.isArray(d)) return bad("diagnostics");
    diagnostics = JSON.stringify(d);
    if (diagnostics.length > MAX_DIAGNOSTICS_CHARS) return bad("diagnostics");
  }

  return {
    ok: true,
    feedback: {
      message,
      contact,
      senderId: senderId === null ? null : senderId.toLowerCase(),
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
