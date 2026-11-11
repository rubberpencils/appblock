// The Worker behind trymoly.com. Cloudflare serves the static site (the
// pages in web/ and the signed rules, built into site/) by itself, without
// running this code. What reaches here: everything under /api/, and a
// request for a path that doesn't exist, unless it is a browser opening a
// page (Cloudflare answers that with 404.html itself).
import { Hono } from "hono";
import {
  countFromSender,
  countUnmailed,
  markEmailed,
  type StoredFeedback,
  saveFeedback,
} from "./db.ts";
import { feedbackMail, sendEmail } from "./email.ts";
import { MAX_BODY_BYTES, parseFeedback } from "./feedback.ts";

const app = new Hono<{ Bindings: Env }>();

app.get("/api/health", (c) => c.json({ ok: true }));

/** The request's body as text, or null once it passes `max` bytes. */
async function bodyUpTo(request: Request, max: number): Promise<string | null> {
  if (Number(request.headers.get("content-length") ?? 0) > max) return null;
  if (request.body === null) return "";
  const chunks: Uint8Array[] = [];
  let size = 0;
  for await (const chunk of request.body) {
    size += chunk.byteLength;
    if (size > max) return null;
    chunks.push(chunk);
  }
  return new TextDecoder().decode(await new Blob(chunks).arrayBuffer());
}

// Feedback from the app's info sheet: saved, then mailed to the maintainer.
// The message is safe once it is saved, so a mail that fails doesn't fail
// the request: the row stays, with `emailed` still 0, and the next mail
// that does go out says how many didn't.
app.post("/api/feedback", async (c) => {
  if (
    !c.req.header("content-type")?.toLowerCase().startsWith("application/json")
  ) {
    return c.json({ error: "unsupported_media_type" }, 415);
  }
  const text = await bodyUpTo(c.req.raw, MAX_BODY_BYTES);
  if (text === null) return c.json({ error: "too_large" }, 413);
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    return c.json({ error: "invalid", field: "body" }, 400);
  }
  const now = Date.now();
  const parsed = parseFeedback(body, now);
  if (!parsed.ok) return c.json({ error: "invalid", field: parsed.field }, 400);

  const country = (c.req.raw.cf?.country as string | undefined) ?? null;
  const row: StoredFeedback = {
    ...parsed.feedback,
    id: parsed.feedback.id ?? crypto.randomUUID(),
    receivedAt: new Date(now).toISOString(),
    country: country && /^[A-Z]{2}$/.test(country) ? country : null,
  };
  // Already here: the app is trying again after an answer that was lost on
  // the way. It has been saved and mailed (or not) once; say so again.
  if (!(await saveFeedback(c.env.DB, row))) {
    return c.json({ id: row.id, receivedAt: row.receivedAt }, 201);
  }

  if (c.env.FEEDBACK_TO) {
    try {
      const mail = feedbackMail(row, c.env.FEEDBACK_TO, {
        fromSender:
          row.senderId === null
            ? 0
            : await countFromSender(c.env.DB, row.senderId),
        unmailed: await countUnmailed(c.env.DB, row.id),
      });
      try {
        await sendEmail(c.env.EMAIL, mail);
      } catch (error) {
        // The reply address is the one header a sender chooses, and the
        // likeliest thing to be refused. It is in the text too: without it
        // the maintainer still hears.
        if (!mail.replyTo) throw error;
        console.warn(
          JSON.stringify({
            event: "feedback_mail_retried_without_reply_to",
            id: row.id,
            error: String(error),
          }),
        );
        await sendEmail(c.env.EMAIL, { ...mail, replyTo: undefined });
      }
      await markEmailed(c.env.DB, row.id);
    } catch (error) {
      console.error(
        JSON.stringify({
          event: "feedback_mail_failed",
          id: row.id,
          error: String(error),
        }),
      );
    }
  } else {
    console.warn(
      JSON.stringify({
        event: "feedback_mail_skipped",
        id: row.id,
        reason: "FEEDBACK_TO is not set",
      }),
    );
  }

  return c.json({ id: row.id, receivedAt: row.receivedAt }, 201);
});

// An unknown API path answers in JSON, like the rest of the API.
app.all("/api/*", (c) => c.json({ error: "not_found" }, 404));

// Anything else is a page that doesn't exist: the static site answers with
// web/404.html and a 404 status (`not_found_handling` in wrangler.jsonc).
app.all("*", (c) => c.env.ASSETS.fetch(c.req.raw));

export default app;
