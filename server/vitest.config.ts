import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

// Three kinds of test, from fastest to most real:
//   unit    plain logic (the site build), in Node
//   worker  the Worker's routes, inside workerd
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
          cloudflareTest({ wrangler: { configPath: "./wrangler.jsonc" } }),
        ],
        test: {
          name: "worker",
          include: ["test/worker/**/*.test.ts"],
          globalSetup: ["test/build-site.setup.ts"],
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
