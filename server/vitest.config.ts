import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

const here = dirname(fileURLToPath(import.meta.url));

// Three kinds of test, from fastest to most real:
//   unit    plain logic (the site build, what feedback may hold), in Node
//   worker  the Worker's routes, inside workerd, with a database of its own
//   e2e     HTTP requests to a running `wrangler dev`
// `pnpm test` runs them all; `pnpm test --project unit` runs one kind.
export default defineConfig({
  test: {
    projects: [
      {
        test: {
          name: "unit",
          include: ["test/unit/**/*.test.ts"],
        },
      },
      {
        plugins: [
          cloudflareTest(async () => ({
            wrangler: { configPath: "./wrangler.jsonc" },
            miniflare: {
              bindings: {
                // The tables, for test/worker/apply-migrations.ts to create.
                TEST_MIGRATIONS: await readD1Migrations(
                  join(here, "migrations"),
                ),
                // A secret in production; nothing is sent from a test.
                FEEDBACK_TO: "maintainer@example.com",
              },
            },
          })),
        ],
        test: {
          name: "worker",
          include: ["test/worker/**/*.test.ts"],
          globalSetup: ["test/build-site.setup.ts"],
          setupFiles: ["test/worker/apply-migrations.ts"],
        },
      },
      {
        test: {
          name: "e2e",
          include: ["test/e2e/**/*.test.ts"],
          globalSetup: ["test/e2e/server.setup.ts"],
        },
      },
    ],
  },
});
