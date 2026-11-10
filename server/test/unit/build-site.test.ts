import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  utimesSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  buildSite,
  repoDirs,
  rulesFormats,
  type SiteDirs,
  siteFiles,
  siteProblems,
} from "../../scripts/build-site.ts";

let root: string;
let dirs: SiteDirs;

function write(path: string, content = path): void {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, content);
}

/** A minimal repo that can be published: two pages and one signed format. */
beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), "moly-site-"));
  dirs = {
    web: join(root, "web"),
    rules: join(root, "rules"),
    out: join(root, "site"),
  };
  write(join(dirs.web, "index.html"), "home");
  write(join(dirs.web, "privacy", "index.html"), "privacy");
  write(join(dirs.rules, "v1", "rules.json"), "unsigned");
  write(join(dirs.rules, "v1", "rules.signed.json"), "signed v1");
});

afterEach(() => rmSync(root, { recursive: true, force: true }));

describe("siteFiles", () => {
  it("is the pages of web/ and each format's signed rules", () => {
    expect([...siteFiles(dirs).keys()].sort()).toEqual([
      "index.html",
      "privacy/index.html",
      "v1/rules.signed.json",
    ]);
  });

  it("never publishes the unsigned rules", () => {
    expect([...siteFiles(dirs).keys()]).not.toContain("v1/rules.json");
  });

  it("publishes every format that has a signed file, and only those", () => {
    write(join(dirs.rules, "v2", "rules.signed.json"), "signed v2");
    write(join(dirs.rules, "v3", "rules.json"), "not signed yet");
    const paths = [...siteFiles(dirs).keys()];
    expect(paths).toContain("v1/rules.signed.json");
    expect(paths).toContain("v2/rules.signed.json");
    expect(paths.filter((path) => path.startsWith("v3/"))).toEqual([]);
  });

  it("leaves out .DS_Store at any depth", () => {
    write(join(dirs.web, ".DS_Store"));
    write(join(dirs.web, "img", ".DS_Store"));
    write(join(dirs.web, "img", "shot.webp"));
    const paths = [...siteFiles(dirs).keys()];
    expect(paths).toContain("img/shot.webp");
    expect(paths.filter((path) => path.endsWith(".DS_Store"))).toEqual([]);
  });
});

describe("rulesFormats", () => {
  it("is the folders under rules/, not the files", () => {
    write(join(dirs.rules, "v2", "rules.json"));
    write(join(dirs.rules, "README.md"));
    expect(rulesFormats(dirs.rules)).toEqual(["v1", "v2"]);
  });
});

describe("siteProblems", () => {
  it("finds none in a site that can be published", () => {
    expect(siteProblems(dirs)).toEqual([]);
  });

  it("refuses a page where a rules format lives", () => {
    write(join(dirs.web, "v1", "index.html"));
    expect(siteProblems(dirs)).toEqual([
      "web/v1 clashes with the rules at /v1/",
    ]);
  });

  it("refuses a page where a format without signed rules will live", () => {
    write(join(dirs.rules, "v2", "rules.json"));
    write(join(dirs.web, "v2", "index.html"));
    expect(siteProblems(dirs)).toEqual([
      "web/v2 clashes with the rules at /v2/",
    ]);
  });

  it("refuses a page under the server's /api/", () => {
    write(join(dirs.web, "api", "index.html"));
    expect(siteProblems(dirs)).toEqual([
      "web/api clashes with the server's /api/",
    ]);
  });

  it("refuses a site without the privacy policy", () => {
    rmSync(join(dirs.web, "privacy"), { recursive: true });
    expect(siteProblems(dirs)).toEqual(["web/privacy/index.html is missing"]);
  });

  it("reports every problem at once", () => {
    write(join(dirs.web, "v1", "index.html"));
    write(join(dirs.web, "api", "index.html"));
    rmSync(join(dirs.web, "privacy"), { recursive: true });
    expect(siteProblems(dirs)).toHaveLength(3);
  });
});

describe("buildSite", () => {
  it("copies each file to its place, byte for byte", () => {
    buildSite(dirs);
    expect(readFileSync(join(dirs.out, "index.html"), "utf8")).toBe("home");
    expect(readFileSync(join(dirs.out, "privacy/index.html"), "utf8")).toBe(
      "privacy",
    );
    expect(readFileSync(join(dirs.out, "v1/rules.signed.json"), "utf8")).toBe(
      "signed v1",
    );
    expect(existsSync(join(dirs.out, "v1/rules.json"))).toBe(false);
  });

  it("writes nothing when web/ can't be published", () => {
    write(join(dirs.web, "v1", "index.html"));
    expect(() => buildSite(dirs)).toThrow(
      "web/v1 clashes with the rules at /v1/",
    );
    expect(existsSync(dirs.out)).toBe(false);
  });

  it("leaves the last good build alone when web/ can't be published", () => {
    buildSite(dirs);
    write(join(dirs.web, "index.html"), "changed");
    write(join(dirs.web, "api", "index.html"));
    expect(() => buildSite(dirs)).toThrow();
    expect(readFileSync(join(dirs.out, "index.html"), "utf8")).toBe("home");
  });

  it("picks up a changed page and changed rules", () => {
    buildSite(dirs);
    write(join(dirs.web, "index.html"), "new home");
    write(join(dirs.rules, "v1", "rules.signed.json"), "newer rules");
    buildSite(dirs);
    expect(readFileSync(join(dirs.out, "index.html"), "utf8")).toBe("new home");
    expect(readFileSync(join(dirs.out, "v1/rules.signed.json"), "utf8")).toBe(
      "newer rules",
    );
  });

  it("removes what was deleted from web/, and the folder it emptied", () => {
    write(join(dirs.web, "old", "deep", "page.html"));
    write(join(dirs.web, "terms", "index.html"));
    buildSite(dirs);
    rmSync(join(dirs.web, "old"), { recursive: true });
    buildSite(dirs);
    expect(existsSync(join(dirs.out, "old"))).toBe(false);
    expect(readdirSync(dirs.out).sort()).toEqual([
      "index.html",
      "privacy",
      "terms",
      "v1",
    ]);
  });

  it("stops publishing rules whose signed file is gone", () => {
    buildSite(dirs);
    rmSync(join(dirs.rules, "v1", "rules.signed.json"));
    buildSite(dirs);
    expect(existsSync(join(dirs.out, "v1"))).toBe(false);
  });

  it("touches nothing when the site is already up to date", () => {
    buildSite(dirs);
    const long_ago = new Date("2020-01-01T00:00:00Z");
    for (const path of siteFiles(dirs).keys()) {
      utimesSync(join(dirs.out, path), long_ago, long_ago);
    }
    buildSite(dirs);
    for (const path of siteFiles(dirs).keys()) {
      expect(statSync(join(dirs.out, path)).mtime).toEqual(long_ago);
    }
  });
});

describe("the repo's own site", () => {
  it("can be published", () => {
    expect(siteProblems(repoDirs())).toEqual([]);
  });

  it("publishes the signed rules of every format in rules/", () => {
    const { rules } = repoDirs();
    const paths = [...siteFiles(repoDirs()).keys()];
    for (const format of rulesFormats(rules)) {
      expect(paths).toContain(`${format}/rules.signed.json`);
    }
  });
});
