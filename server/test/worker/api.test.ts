// The Worker's own routes, run inside workerd (the runtime Cloudflare runs
// in production). `exports.default.fetch` calls src/index.ts directly, so
// these see only what reaches the Worker: files that exist are answered
// before it, and test/e2e covers those.
import { exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";

const site = "https://trymoly.com";

describe("GET /api/health", () => {
  it("says the server is up, in JSON", async () => {
    const response = await exports.default.fetch(`${site}/api/health`);
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("application/json");
    expect(await response.json()).toEqual({ ok: true });
  });

  it.each(["POST", "PUT", "DELETE"])("isn't there for %s", async (method) => {
    const response = await exports.default.fetch(`${site}/api/health`, {
      method,
    });
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: "not_found" });
  });
});

describe("an unknown path under /api/", () => {
  it.each(["/api/", "/api/nope", "/api/health/deeper"])(
    "%s is a JSON 404, not the site's page",
    async (path) => {
      const response = await exports.default.fetch(site + path);
      expect(response.status).toBe(404);
      expect(response.headers.get("content-type")).toContain(
        "application/json",
      );
      expect(await response.json()).toEqual({ error: "not_found" });
    },
  );
});

describe("a page that doesn't exist", () => {
  it.each(["/nope", "/privacy/nope", "/apix"])(
    "%s gets the site's 404 page",
    async (path) => {
      const response = await exports.default.fetch(site + path);
      expect(response.status).toBe(404);
      expect(response.headers.get("content-type")).toContain("text/html");
      expect(await response.text()).toContain("Page not found");
    },
  );
});
