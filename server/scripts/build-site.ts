// Builds the folder Cloudflare serves (server/site/, gitignored): the pages
// in web/ as they are, then each rules format's signed file on top
// (rules/v1/rules.signed.json -> site/v1/rules.signed.json), the path the
// app fetches. Only signed files are published: signing happens on a key
// holder's machine (./gradlew signRules), so a build can't make rules the
// app accepts, only publish ones already signed.
//
// Wrangler runs this before `wrangler dev` and `wrangler deploy` (the
// `build` entry in wrangler.jsonc), and the tests run it too, so every one
// of them serves the same files.
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  rmdirSync,
  rmSync,
  statSync,
} from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

// Paths under the site root that belong to the Worker's code, not to pages.
export const WORKER_PATHS = ["api"];

export type SiteDirs = { web: string; rules: string; out: string };
type Sources = Omit<SiteDirs, "out">;

/** Every file under `dir`, as paths relative to it with "/" separators. */
function filesUnder(dir: string, prefix = ""): string[] {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = prefix + entry.name;
    return entry.isDirectory()
      ? filesUnder(join(dir, entry.name), `${path}/`)
      : [path];
  });
}

/** The rules formats in the repo: the folder names under rules/ ("v1"). */
export function rulesFormats(rules: string): string[] {
  return readdirSync(rules)
    .filter((name) => statSync(join(rules, name)).isDirectory())
    .sort();
}

/**
 * Why this web/ folder can't be published, or [] when it can. A page at a
 * path the rules or the API own would be served in their place, or be
 * hidden by them.
 */
export function siteProblems({ web, rules }: Sources): string[] {
  const problems: string[] = [];
  for (const format of rulesFormats(rules)) {
    if (existsSync(join(web, format))) {
      problems.push(`web/${format} clashes with the rules at /${format}/`);
    }
  }
  for (const path of WORKER_PATHS) {
    if (existsSync(join(web, path))) {
      problems.push(`web/${path} clashes with the server's /${path}/`);
    }
  }
  // The Play Store listing links to the privacy policy.
  if (!existsSync(join(web, "privacy", "index.html"))) {
    problems.push("web/privacy/index.html is missing");
  }
  return problems;
}

/** What the site holds: each path under the site root, and its source file. */
export function siteFiles({ web, rules }: Sources): Map<string, string> {
  const files = new Map<string, string>();
  for (const path of filesUnder(web)) {
    // macOS drops a .DS_Store into any folder Finder has opened.
    if (path.split("/").pop() === ".DS_Store") continue;
    files.set(path, join(web, path));
  }
  for (const format of rulesFormats(rules)) {
    const signed = join(rules, format, "rules.signed.json");
    if (existsSync(signed)) files.set(`${format}/rules.signed.json`, signed);
  }
  return files;
}

/**
 * Makes `out` hold exactly the site's files. It only writes what differs,
 * so building an up-to-date site touches nothing: a build is safe while
 * `wrangler dev` is serving the folder. Throws, having written nothing, if
 * web/ can't be published.
 */
export function buildSite({ web, rules, out }: SiteDirs): void {
  const problems = siteProblems({ web, rules });
  if (problems.length > 0) throw new Error(problems.join("\n"));

  const files = siteFiles({ web, rules });
  for (const [path, source] of files) {
    const target = join(out, path);
    // A page that was a folder in the last build, or a folder that was a
    // page: clear what is in the way.
    for (let dir = dirname(target); dir !== out; dir = dirname(dir)) {
      if (existsSync(dir) && !statSync(dir).isDirectory()) rmSync(dir);
    }
    if (existsSync(target)) {
      if (statSync(target).isDirectory()) {
        rmSync(target, { recursive: true });
      } else if (readFileSync(target).equals(readFileSync(source))) {
        continue;
      }
    }
    mkdirSync(dirname(target), { recursive: true });
    copyFileSync(source, target);
  }
  for (const path of filesUnder(out)) {
    if (files.has(path)) continue;
    rmSync(join(out, path));
    // And the folders it leaves empty, up to the site root.
    for (
      let dir = dirname(join(out, path));
      dir !== out && readdirSync(dir).length === 0;
      dir = dirname(dir)
    ) {
      rmdirSync(dir);
    }
  }
}

/** The repo's own folders: web/ and rules/ in the root, site/ in server/. */
export function repoDirs(): SiteDirs {
  const server = resolve(fileURLToPath(import.meta.url), "..", "..");
  return {
    web: resolve(server, "..", "web"),
    rules: resolve(server, "..", "rules"),
    out: join(server, "site"),
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    buildSite(repoDirs());
  } catch (error) {
    console.error(error instanceof Error ? error.message : error);
    process.exit(1);
  }
}
