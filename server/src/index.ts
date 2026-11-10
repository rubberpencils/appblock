// The Worker behind trymoly.com. Cloudflare serves the static site (the
// pages in web/ and the signed rules, built into site/) by itself, without
// running this code. A request only reaches here when no file matched it:
// the API under /api/, or a path that doesn't exist.
import { Hono } from "hono";

const app = new Hono<{ Bindings: Env }>();

app.get("/api/health", (c) => c.json({ ok: true }));

// An unknown API path answers in JSON, like the rest of the API.
app.all("/api/*", (c) => c.json({ error: "not_found" }, 404));

// Anything else is a page that doesn't exist: the static site answers with
// web/404.html and a 404 status (`not_found_handling` in wrangler.jsonc).
app.all("*", (c) => c.env.ASSETS.fetch(c.req.raw));

export default app;
