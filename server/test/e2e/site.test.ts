// The site as a visitor and the app get it: real HTTP requests to the local
// server (`wrangler dev`), where files are answered by Cloudflare's static
// site layer and everything else by the Worker.
import { readFileSync } from "node:fs";
import { request } from "node:http";
import { join } from "node:path";
import { describe, expect, inject, it } from "vitest";
import { repoDirs, rulesFormats } from "../../scripts/build-site.ts";

const baseUrl = inject("baseUrl");
const { web, rules } = repoDirs();

/** A request that reports a redirect instead of following it. */
function get(path: string, headers: Record<string, string> = {}) {
  return fetch(baseUrl + path, { headers, redirect: "manual" });
}

/**
 * The request a browser makes when someone opens an address. `fetch` can't
 * make it: it sets `Sec-Fetch-Mode` itself, to "cors".
 */
function open(path: string): Promise<{
  status: number;
  type: string;
  body: string;
}> {
  return new Promise((done, fail) => {
    request(
      baseUrl + path,
      { headers: { "Sec-Fetch-Mode": "navigate", Accept: "text/html" } },
      (response) => {
        let body = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => {
          body += chunk;
        });
        response.on("end", () =>
          done({
            status: response.statusCode ?? 0,
            type: response.headers["content-type"] ?? "",
            body,
          }),
        );
      },
    )
      .on("error", fail)
      .end();
  });
}

describe("the website", () => {
  it.each([
    ["/", "index.html"],
    ["/privacy/", "privacy/index.html"],
    ["/terms/", "terms/index.html"],
  ])("%s is the page in web/%s", async (path, file) => {
    const response = await get(path);
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("text/html");
    expect(await response.text()).toBe(readFileSync(join(web, file), "utf8"));
  });

  it.each([
    ["/style.css", "text/css"],
    ["/email-menu.js", "javascript"],
    ["/shot-viewer.js", "javascript"],
  ])("%s is served as %s", async (path, type) => {
    const response = await get(path);
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain(type);
    expect(await response.text()).toBe(readFileSync(join(web, path), "utf8"));
  });

  // The pages link to /privacy/ and /terms/; a link typed without the
  // slash still has to arrive.
  it.each(["/privacy", "/terms"])(
    "%s redirects to its folder",
    async (path) => {
      const response = await get(path);
      expect(response.status).toBeGreaterThanOrEqual(300);
      expect(response.status).toBeLessThan(400);
      expect(
        new URL(response.headers.get("location") ?? "", baseUrl).pathname,
      ).toBe(`${path}/`);
    },
  );

  it("answers a page that doesn't exist with the 404 page", async () => {
    const response = await get("/no-such-page");
    expect(response.status).toBe(404);
    expect(response.headers.get("content-type")).toContain("text/html");
    expect(await response.text()).toBe(
      readFileSync(join(web, "404.html"), "utf8"),
    );
  });
});

// What Feeds.kt relies on: the signed file at /<format>/rules.signed.json,
// an ETag it sends back as If-None-Match, and a 304 while nothing changed.
describe.each(rulesFormats(rules))("the %s rules", (format) => {
  const path = `/${format}/rules.signed.json`;
  const signed = readFileSync(join(rules, format, "rules.signed.json"));

  it("are the signed file, byte for byte", async () => {
    const response = await get(path, { "User-Agent": "AppBlock" });
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("application/json");
    expect(Buffer.from(await response.arrayBuffer()).equals(signed)).toBe(true);
  });

  it("come with an ETag, and a 304 for a phone that already has them", async () => {
    const etag = (await get(path)).headers.get("etag");
    expect(etag).toBeTruthy();
    const again = await get(path, { "If-None-Match": etag ?? "" });
    expect(again.status).toBe(304);
    expect(await again.text()).toBe("");
  });

  it("are sent in full to a phone holding older ones", async () => {
    const response = await get(path, { "If-None-Match": '"an-older-etag"' });
    expect(response.status).toBe(200);
    expect(Buffer.from(await response.arrayBuffer()).equals(signed)).toBe(true);
  });

  it("must be checked again on every fetch, never served stale", async () => {
    const cache = (await get(path)).headers.get("cache-control") ?? "";
    expect(cache).toContain("must-revalidate");
    expect(cache).toContain("max-age=0");
  });

  it("don't include the unsigned file", async () => {
    expect((await get(`/${format}/rules.json`)).status).toBe(404);
  });
});

describe("the API, through the same address", () => {
  it("answers /api/health from the Worker", async () => {
    const response = await get("/api/health");
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true });
  });

  it("answers an unknown API path in JSON, not with the 404 page", async () => {
    const response = await get("/api/nope");
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: "not_found" });
  });
});

// Cloudflare answers a browser's navigation to a path with no file by
// itself, with the 404 page. /api/ has to be exempt (`run_worker_first` in
// wrangler.jsonc), or a link to an API address would never reach the Worker.
describe("an address opened in a browser", () => {
  it("reaches the Worker under /api/", async () => {
    const response = await open("/api/health");
    expect(response.status).toBe(200);
    expect(response.type).toContain("application/json");
    expect(JSON.parse(response.body)).toEqual({ ok: true });
  });

  it("gets the API's JSON 404 for an unknown path under /api/", async () => {
    const response = await open("/api/nope");
    expect(response.status).toBe(404);
    expect(response.type).toContain("application/json");
    expect(JSON.parse(response.body)).toEqual({ error: "not_found" });
  });

  it("gets the 404 page for a page that doesn't exist", async () => {
    const response = await open("/no-such-page");
    expect(response.status).toBe(404);
    expect(response.type).toContain("text/html");
    expect(response.body).toBe(readFileSync(join(web, "404.html"), "utf8"));
  });

  it("gets a page that exists", async () => {
    const response = await open("/privacy/");
    expect(response.status).toBe(200);
    expect(response.body).toBe(
      readFileSync(join(web, "privacy/index.html"), "utf8"),
    );
  });
});
