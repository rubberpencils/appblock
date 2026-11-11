// Every query the Worker runs, in one place: the rest of the code doesn't
// write SQL. The tables are migrations/*.sql.
import type { Feedback } from "./feedback.ts";

/** A feedback message as it is stored: what the app sent, and what the server adds. */
export interface StoredFeedback extends Feedback {
  id: string;
  /** UTC, ISO 8601. */
  receivedAt: string;
  /** Two letters, from Cloudflare; null when it doesn't know. */
  country: string | null;
}

/**
 * Saves a message. False when one with its id is already there: the app
 * trying again after an answer that never reached it. Nothing is changed
 * then.
 */
export async function saveFeedback(
  db: D1Database,
  row: StoredFeedback,
): Promise<boolean> {
  const done = await db
    .prepare(
      `INSERT INTO feedback (id, received_at, kind, message, contact, sender_id,
         app_version, app_build, android_version, android_sdk, device,
         installed_at, updated_at, diagnostics, country)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT (id) DO NOTHING`,
    )
    .bind(
      row.id,
      row.receivedAt,
      row.kind,
      row.message,
      row.contact,
      row.senderId,
      row.appVersion,
      row.appBuild,
      row.androidVersion,
      row.androidSdk,
      row.device,
      row.installedAt,
      row.updatedAt,
      row.diagnostics,
      row.country,
    )
    .run();
  return done.meta.changes > 0;
}

/** The maintainer was mailed about this one. */
export async function markEmailed(db: D1Database, id: string): Promise<void> {
  await db
    .prepare("UPDATE feedback SET emailed = 1 WHERE id = ?")
    .bind(id)
    .run();
}

/** Counting stops here: past it the mail says "100+", and a count never reads more rows than this. */
export const COUNT_UP_TO = 100;

/** How many messages this sender has sent, this one included once it is saved, up to COUNT_UP_TO. */
export async function countFromSender(
  db: D1Database,
  senderId: string,
): Promise<number> {
  const row = await db
    .prepare(
      `SELECT COUNT(*) AS n FROM
         (SELECT 1 FROM feedback WHERE sender_id = ? LIMIT ${COUNT_UP_TO})`,
    )
    .bind(senderId)
    .first<{ n: number }>();
  return row?.n ?? 0;
}

/** How many messages besides `id` were never mailed to the maintainer, up to COUNT_UP_TO. */
export async function countUnmailed(
  db: D1Database,
  id: string,
): Promise<number> {
  const row = await db
    .prepare(
      `SELECT COUNT(*) AS n FROM
         (SELECT 1 FROM feedback WHERE emailed = 0 AND id != ? LIMIT ${COUNT_UP_TO})`,
    )
    .bind(id)
    .first<{ n: number }>();
  return row?.n ?? 0;
}
