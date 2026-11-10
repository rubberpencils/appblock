// The pre-commit hook (.githooks/pre-commit) decides which checks a commit
// needs. Each test stages a change in a scratch repo and runs the real hook
// with stand-ins for ./gradlew and pnpm that only record being called.
import { spawnSync } from "node:child_process";
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

const hook = resolve(
  fileURLToPath(import.meta.url),
  "../../../../.githooks/pre-commit",
);

let root: string;
let repo: string;
let bin: string;
let calls: string;

function write(path: string, content = "x"): void {
  mkdirSync(dirname(join(repo, path)), { recursive: true });
  writeFileSync(join(repo, path), content);
}

/** An executable that records its name, then exits with `status`. */
function standIn(path: string, name: string, status = 0): void {
  writeFileSync(
    path,
    `#!/bin/sh\necho ${name} >> "${calls}"\nexit ${status}\n`,
  );
  chmodSync(path, 0o755);
}

function git(...args: string[]): void {
  const done = spawnSync(
    "git",
    ["-c", "user.name=test", "-c", "user.email=test@example.com", ...args],
    { cwd: repo, encoding: "utf8" },
  );
  if (done.status !== 0)
    throw new Error(`git ${args.join(" ")}: ${done.stderr}`);
}

/** Runs the hook on what is staged: which checks ran, and how it ended. */
function runHook(path = `${bin}:/usr/bin:/bin`) {
  const done = spawnSync("sh", [hook], {
    cwd: repo,
    encoding: "utf8",
    env: { PATH: path, HOME: root },
  });
  return {
    status: done.status,
    output: done.stdout + done.stderr,
    ran: existsSync(calls)
      ? readFileSync(calls, "utf8").trim().split("\n")
      : [],
  };
}

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), "moly-hook-"));
  repo = join(root, "repo");
  bin = join(root, "bin");
  calls = join(root, "calls");
  mkdirSync(repo);
  mkdirSync(bin);
  // git itself, wherever this machine keeps it.
  const where = spawnSync("sh", ["-c", "command -v git"], { encoding: "utf8" });
  writeFileSync(
    join(bin, "git"),
    `#!/bin/sh\nexec "${where.stdout.trim()}" "$@"\n`,
  );
  chmodSync(join(bin, "git"), 0o755);
  standIn(join(bin, "pnpm"), "server-checks");

  git("init", "--quiet");
  standIn(join(repo, "gradlew"), "app-unit-tests");
  mkdirSync(join(repo, "server", "node_modules"), { recursive: true });
  write("app/Main.kt");
  write("web/index.html");
  write("web/old page.html");
  write("rules/v1/rules.json");
  write("server/src/index.ts");
  write("README.md");
  write(".gitignore", "node_modules/\n");
  git("add", "--all");
  git("commit", "--quiet", "--no-verify", "--message", "start");
});

afterEach(() => rmSync(root, { recursive: true, force: true }));

describe("which checks a commit gets", () => {
  it("only the app's tests for a change to the app", () => {
    write("app/Main.kt", "changed");
    git("add", "--all");
    expect(runHook()).toMatchObject({ status: 0, ran: ["app-unit-tests"] });
  });

  it("only the app's tests for a change to the docs", () => {
    write("README.md", "changed");
    write("docs/new.md");
    git("add", "--all");
    expect(runHook().ran).toEqual(["app-unit-tests"]);
  });

  it.each([
    ["the server's code", "server/src/index.ts"],
    ["a page", "web/index.html"],
    ["the rules", "rules/v1/rules.json"],
  ])("the server's checks too for a change to %s", (_, path) => {
    write(path, "changed");
    git("add", "--all");
    expect(runHook()).toMatchObject({
      status: 0,
      ran: ["app-unit-tests", "server-checks"],
    });
  });

  it("the server's checks for a new page", () => {
    write("web/terms/index.html");
    git("add", "--all");
    expect(runHook().ran).toContain("server-checks");
  });

  it("the server's checks for a deleted page", () => {
    git("rm", "--quiet", "web/old page.html");
    expect(runHook().ran).toContain("server-checks");
  });

  it("the server's checks for a page moved out of web/", () => {
    mkdirSync(join(repo, "docs"));
    git("mv", "web/old page.html", "docs/old page.html");
    expect(runHook().ran).toContain("server-checks");
  });

  it("the server's checks for a page whose name git prints quoted", () => {
    write("web/café.html");
    git("add", "--all");
    expect(runHook().ran).toContain("server-checks");
  });

  it("not the server's checks for a folder that only starts like one", () => {
    write("webby/page.html");
    write("servers/a.ts");
    git("add", "--all");
    expect(runHook().ran).toEqual(["app-unit-tests"]);
  });

  it("only what is staged counts", () => {
    write("web/index.html", "changed, not staged");
    write("app/Main.kt", "changed");
    git("add", "app");
    expect(runHook().ran).toEqual(["app-unit-tests"]);
  });
});

describe("when a check fails or can't run", () => {
  it("blocks the commit when the app's tests fail, without going further", () => {
    standIn(join(repo, "gradlew"), "app-unit-tests", 1);
    write("web/index.html", "changed");
    git("add", "web");
    expect(runHook()).toMatchObject({ status: 1, ran: ["app-unit-tests"] });
  });

  it("blocks the commit when the server's checks fail", () => {
    standIn(join(bin, "pnpm"), "server-checks", 1);
    write("web/index.html", "changed");
    git("add", "--all");
    const result = runHook();
    expect(result.status).toBe(1);
    expect(result.output).toContain("the server's checks failed");
  });

  it("blocks a commit that needs pnpm when pnpm isn't installed", () => {
    rmSync(join(bin, "pnpm"));
    write("web/index.html", "changed");
    git("add", "--all");
    const result = runHook();
    expect(result.status).toBe(1);
    expect(result.output).toContain("pnpm, which isn't installed");
  });

  it("doesn't ask for pnpm when the commit doesn't need it", () => {
    rmSync(join(bin, "pnpm"));
    write("app/Main.kt", "changed");
    git("add", "--all");
    expect(runHook().status).toBe(0);
  });

  it("blocks a commit that needs the server's packages before they're installed", () => {
    rmSync(join(repo, "server", "node_modules"), { recursive: true });
    write("server/src/index.ts", "changed");
    git("add", "--all");
    const result = runHook();
    expect(result.status).toBe(1);
    expect(result.output).toContain("pnpm install");
    expect(result.ran).toEqual(["app-unit-tests"]);
  });
});
