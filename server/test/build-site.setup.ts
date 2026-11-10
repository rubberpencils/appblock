// The Worker tests read 404.html from the built site.
import { buildSite, repoDirs } from "../scripts/build-site.ts";

export default function setup(): void {
  buildSite(repoDirs());
}
