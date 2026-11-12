// What a search engine, a link preview and a browser tab read off the pages
// in web/: each page's title and description, its one true address, its
// preview card and its icons, and the sitemap and robots.txt beside them.
// A page added to web/ without them fails here.
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { repoDirs, siteFiles } from "../../scripts/build-site.ts";

const dirs = repoDirs();
const { web } = dirs;
const SITE = "https://trymoly.com";

/** The site's pages but the 404: [file in web/, its address on the site]. */
const pages: [string, string][] = [...siteFiles(dirs).keys()]
  .filter((file) => file.endsWith(".html") && file !== "404.html")
  .sort()
  .map((file) => [file, `${SITE}/${file.replace(/index\.html$/, "")}`]);

function read(file: string): string {
  return readFileSync(join(web, file), "utf8");
}

/** The `content` of each <meta> named (or with the property) `name`. */
function metas(html: string, name: string): string[] {
  const tag = new RegExp(
    `<meta (?:name|property)="${name}" content="([^"]*)">`,
    "g",
  );
  return [...html.matchAll(tag)].map((match) => match[1] ?? "");
}

/** The `href` of each <link rel="`rel`">. */
function links(html: string, rel: string): string[] {
  const tag = new RegExp(`<link rel="${rel}" href="([^"]*)"`, "g");
  return [...html.matchAll(tag)].map((match) => match[1] ?? "");
}

function title(html: string): string {
  return /<title>([^<]*)<\/title>/.exec(html)?.[1] ?? "";
}

/** A PNG's width and height, from its header. */
function pngSize(file: string): string {
  const png = readFileSync(join(web, file));
  expect(png.subarray(1, 4).toString("latin1")).toBe("PNG");
  return `${png.readUInt32BE(16)}x${png.readUInt32BE(20)}`;
}

describe("the address the pages give as their own", () => {
  it("is the domain the Worker answers on", () => {
    const config = readFileSync(join(web, "../server/wrangler.jsonc"), "utf8");
    expect(config).toContain(`"pattern": "${new URL(SITE).host}"`);
  });
});

describe.each(pages)("web/%s", (file, address) => {
  const html = read(file);

  it("has a title a search result can show whole", () => {
    expect(title(html)).toContain("Moly");
    expect(title(html).length).toBeLessThanOrEqual(60);
  });

  it("has one description, of a search result's length", () => {
    const [description, ...more] = metas(html, "description");
    expect(more).toEqual([]);
    expect(description?.length).toBeGreaterThanOrEqual(50);
    expect(description?.length).toBeLessThanOrEqual(160);
  });

  it("names its own address as the canonical one", () => {
    expect(links(html, "canonical")).toEqual([address]);
  });

  it("has a preview card that says what the page does", () => {
    expect(metas(html, "og:title")).toEqual([title(html)]);
    expect(metas(html, "og:description")).toEqual(metas(html, "description"));
    expect(metas(html, "og:url")).toEqual([address]);
    expect(metas(html, "og:site_name")).toEqual(["Moly"]);
    expect(metas(html, "twitter:card")).toEqual(["summary_large_image"]);
  });

  it("has a preview image that is on the site, at the size it says", () => {
    const [image] = metas(html, "og:image");
    // A preview is fetched from elsewhere: the address has to be a full one.
    expect(image).toMatch(new RegExp(`^${SITE}/`));
    const file = (image ?? "").slice(SITE.length + 1);
    const jpeg = readFileSync(join(web, file));
    expect(jpeg.subarray(0, 3)).toEqual(Buffer.from([0xff, 0xd8, 0xff]));
    expect(metas(html, "og:image:width")).toEqual(["1200"]);
    expect(metas(html, "og:image:height")).toEqual(["630"]);
    expect(metas(html, "og:image:alt")[0]).toBeTruthy();
  });

  it("may be indexed", () => {
    expect(metas(html, "robots")).toEqual([]);
  });
});

describe("the pages together", () => {
  it("each have a title and a description of their own", () => {
    const titles = pages.map(([file]) => title(read(file)));
    const descriptions = pages.map(([file]) =>
      metas(read(file), "description"),
    );
    expect(new Set(titles).size).toBe(pages.length);
    expect(new Set(descriptions.flat()).size).toBe(pages.length);
  });

  it("are the sitemap, every one and nothing else", () => {
    const listed = [...read("sitemap.xml").matchAll(/<loc>([^<]*)<\/loc>/g)];
    expect(listed.map((match) => match[1]).sort()).toEqual(
      pages.map(([, address]) => address).sort(),
    );
  });
});

describe("the icons", () => {
  // The 404 page is opened in a tab too.
  const withIcons = [...pages.map(([file]) => file), "404.html"];

  it.each(withIcons)("of web/%s are files on the site", (file) => {
    const html = read(file);
    expect(links(html, "icon")).toEqual(["/favicon.ico", "/favicon.svg"]);
    expect(links(html, "apple-touch-icon")).toEqual(["/apple-touch-icon.png"]);
    expect(links(html, "manifest")).toEqual(["/site.webmanifest"]);
    for (const rel of ["icon", "apple-touch-icon", "manifest"]) {
      for (const href of links(html, rel)) {
        expect(existsSync(join(web, href)), href).toBe(true);
      }
    }
  });

  it("include /favicon.ico, which a browser asks for unprompted", () => {
    const ico = readFileSync(join(web, "favicon.ico"));
    // An .ico's header: reserved 0, type 1 (icon), then how many images.
    expect(ico.readUInt16LE(0)).toBe(0);
    expect(ico.readUInt16LE(2)).toBe(1);
    expect(ico.readUInt16LE(4)).toBeGreaterThanOrEqual(1);
  });

  it("include an SVG, and the size iOS asks for", () => {
    expect(read("favicon.svg")).toMatch(
      /^<svg xmlns="http:\/\/www.w3.org\/2000\/svg"/,
    );
    expect(pngSize("apple-touch-icon.png")).toBe("180x180");
  });

  it("are in the manifest at the sizes it says", () => {
    const manifest = JSON.parse(read("site.webmanifest")) as {
      name: string;
      icons: { src: string; sizes: string; type: string }[];
    };
    expect(manifest.name).toBe("Moly");
    expect(manifest.icons.map((icon) => icon.sizes)).toContain("192x192");
    expect(manifest.icons.map((icon) => icon.sizes)).toContain("512x512");
    for (const icon of manifest.icons) {
      expect(icon.type).toBe("image/png");
      expect(pngSize(icon.src), icon.src).toBe(icon.sizes);
    }
  });
});

describe("the 404 page", () => {
  it("asks not to be indexed, and claims no address", () => {
    const html = read("404.html");
    expect(metas(html, "robots")).toEqual(["noindex"]);
    expect(links(html, "canonical")).toEqual([]);
  });
});

describe("robots.txt", () => {
  const lines = read("robots.txt").split("\n");

  it("lets every crawler in, and points it at the sitemap", () => {
    expect(lines).toContain("User-agent: *");
    expect(lines).toContain(`Sitemap: ${SITE}/sitemap.xml`);
    // "Disallow: /" alone would take the whole site out of every search.
    expect(lines).not.toContain("Disallow: /");
  });

  it("keeps crawlers off the API", () => {
    expect(lines).toContain("Disallow: /api/");
  });
});

describe("the home page's structured data", () => {
  const json = /<script type="application\/ld\+json">([\s\S]*?)<\/script>/.exec(
    read("index.html"),
  )?.[1];
  const graph = (
    JSON.parse(json ?? "{}") as { "@graph": Record<string, unknown>[] }
  )["@graph"];

  it("names the site, for the name a search result shows", () => {
    expect(graph).toContainEqual({
      "@type": "WebSite",
      name: "Moly",
      url: `${SITE}/`,
    });
  });

  it("describes the app as the page does: free, for Android", () => {
    const app = graph.find((item) => item["@type"] === "SoftwareApplication");
    expect(app).toMatchObject({
      name: "Moly",
      url: `${SITE}/`,
      operatingSystem: "Android",
      offers: { "@type": "Offer", price: "0" },
    });
    expect(app?.description).toBe(metas(read("index.html"), "description")[0]);
    const image = String(app?.image).slice(SITE.length + 1);
    expect(existsSync(join(web, image)), image).toBe(true);
  });
});
