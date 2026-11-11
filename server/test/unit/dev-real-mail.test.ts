// `pnpm dev:mail`'s copy of the config (scripts/dev-real-mail.ts).
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { withoutComments, withRealMail } from "../../scripts/dev-real-mail.ts";

const config = readFileSync(
  resolve(fileURLToPath(import.meta.url), "..", "..", "..", "wrangler.jsonc"),
  "utf8",
);

describe("comments", () => {
  it("go, and what looks like one inside a string stays", () => {
    const jsonc = `{
      // a line
      "url": "https://trymoly.com/a//b", /* a block
      over lines */ "quote": "say \\"// hi\\"", "n": 1 // trailing
    }`;
    expect(JSON.parse(withoutComments(jsonc))).toEqual({
      url: "https://trymoly.com/a//b",
      quote: 'say "// hi"',
      n: 1,
    });
  });
});

describe("the real-mail copy of wrangler.jsonc", () => {
  const copy = withRealMail(config);

  it("has the mail binding as the real one", () => {
    expect(copy.send_email).toEqual([{ name: "EMAIL", remote: true }]);
  });

  it("is the same Worker otherwise: the database stays local", () => {
    const original = JSON.parse(withoutComments(config));
    expect({ ...copy, send_email: original.send_email }).toEqual(original);
    expect(JSON.stringify(copy.d1_databases)).not.toContain("remote");
  });

  it("is refused for a config with no mail binding", () => {
    expect(() => withRealMail(`{ "name": "moly" }`)).toThrow(/send_email/);
  });
});
