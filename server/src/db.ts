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

export async function saveFeedback(
  db: D1Database,
  row: StoredFeedback,
): Promise<void> {
  await db
    .prepare(
      `INSERT INTO feedback (id, received_at, message, contact, sender_id, app_version,
         app_build, android_version, android_sdk, device, installed_at,
         updated_at, diagnostics, country)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    )
    .bind(
      row.id,
      row.receivedAt,
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
}

/** The maintainer was mailed about this one. */
export async function markEmailed(db: D1Database, id: string): Promise<void> {
  await db
    .prepare("UPDATE feedback SET emailed = 1 WHERE id = ?")
    .bind(id)
    .run();
}

/** How many messages this sender has sent, this one included once it is saved. */
export async function countFromSender(
  db: D1Database,
  senderId: string,
): Promise<number> {
  const row = await db
    .prepare("SELECT COUNT(*) AS n FROM feedback WHERE sender_id = ?")
    .bind(senderId)
    .first<{ n: number }>();
  return row?.n ?? 0;
}
