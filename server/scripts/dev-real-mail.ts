// `pnpm dev:mail`: the local server, but mail really goes out, through
// Cloudflare, to the address in .dev.vars (FEEDBACK_TO). `pnpm dev` only
// prints each mail. Needs `pnpm exec wrangler login`, and an address that
// is verified on the account (Email → Email Routing → Destination addresses).
//
// Wrangler decides per binding whether it is the real one ("remote"), in
// its config file. So this writes a copy of wrangler.jsonc with the mail
// binding switched over (wrangler.real-mail.jsonc, gitignored) and runs
// `wrangler dev` on it. Everything else stays local: the database too.
import { spawnSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const server = resolve(fileURLToPath(import.meta.url), "..", "..");

/** JSON with comments, without them: wrangler.jsonc as plain JSON text. */
export function withoutComments(jsonc: string): string {
  let out = "";
  let inString = false;
  for (let i = 0; i < jsonc.length; i++) {
    const c = jsonc[i];
    if (inString) {
      out += c;
      if (c === "\\") out += jsonc[++i];
      else if (c === '"') inString = false;
    } else if (c === '"') {
      inString = true;
      out += c;
    } else if (c === "/" && jsonc[i + 1] === "/") {
      while (i < jsonc.length && jsonc[i] !== "\n") i++;
      out += "\n";
    } else if (c === "/" && jsonc[i + 1] === "*") {
      i = jsonc.indexOf("*/", i + 2) + 1;
    } else {
      out += c;
    }
  }
  return out;
}

/** The config with every mail binding made the real one. */
export function withRealMail(jsonc: string): Record<string, unknown> {
  const config = JSON.parse(withoutComments(jsonc)) as Record<string, unknown>;
  const mail = config.send_email;
  if (!Array.isArray(mail) || mail.length === 0) {
    throw new Error("wrangler.jsonc has no send_email binding");
  }
  config.send_email = mail.map((binding) => ({ ...binding, remote: true }));
  return config;
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const copy = resolve(server, "wrangler.real-mail.jsonc");
  const config = withRealMail(
    readFileSync(resolve(server, "wrangler.jsonc"), "utf8"),
  );
  writeFileSync(
    copy,
    `// Written by scripts/dev-real-mail.ts from wrangler.jsonc. Don't edit.\n${JSON.stringify(config, null, 2)}\n`,
  );
  console.log(
    "Mail from this server is REAL: it goes to FEEDBACK_TO in .dev.vars.\n",
  );
  const run = (args: string[]) =>
    spawnSync(resolve(server, "node_modules", ".bin", "wrangler"), args, {
      cwd: server,
      stdio: "inherit",
    });
  run(["d1", "migrations", "apply", "moly", "--local"]);
  process.exit(
    run(["dev", "--config", copy, ...process.argv.slice(2)]).status ?? 1,
  );
}
