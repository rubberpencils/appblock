# Cloudflare setup

`trymoly.com` runs on Cloudflare's free plan: one Worker, `moly`, serves the
website, the signed rules and the API. This page is the record of how the
account is set up, for the day something breaks or has to be rebuilt.
Day-to-day work doesn't need any of it: the server runs and is tested
locally (`server/`, see [AGENTS.md](../AGENTS.md#server)), and CI deploys it.

## What lives where

| What | Where it's set | Changes how |
|---|---|---|
| The Worker: its code, the static site, the domain it answers on | `server/wrangler.jsonc` | A push to `main` (`Publish site` workflow) |
| DNS, email, redirects, security settings | The Cloudflare dashboard (below) | By hand, by the maintainer |
| The domain's registration and nameservers | Namecheap | By hand, by the maintainer |

Keep it that way round: anything `wrangler.jsonc` can hold goes there, so it
is reviewed and in git history. The dashboard is for what has no config file.

## Dashboard settings

These exist only in the account. If one is changed, change it here too.

**Nameservers** (set at Namecheap: Domain List → Manage → Nameservers →
Custom DNS): `cartman.ns.cloudflare.com`, `lindsey.ns.cloudflare.com`.
Setting them back to "Namecheap BasicDNS" takes the domain off Cloudflare.

**DNS records** (DNS → Records). Two are added by hand, `www` and `_dmarc`:

| Record | Made by |
|---|---|
| `trymoly.com` → the Worker | The deploy (`routes` in `wrangler.jsonc`) |
| `www` A `192.0.2.1`, proxied | By hand, for the redirect below. The address is a reserved placeholder: the redirect answers before anything is sent to it. |
| MX ×3, SPF and DKIM TXT | Email Routing. Locked: it manages them. |
| `_dmarc` TXT `v=DMARC1; p=reject; sp=reject; rua=…` | By hand; the `rua` part by DMARC Management. See Email, below. |

Don't add a record for `trymoly.com` itself by hand: the deploy owns that
name, and in CI it replaces whatever record is there.

**Email** (Email → Email Routing): on, forwarding `support@trymoly.com` to
the maintainer's inbox. Receiving only.

The `_dmarc` record tells mail providers to reject any message that claims
to come from `trymoly.com` (or a subdomain) without proof. The domain sends
no mail, so every such message is a forgery, such as a fake "install this
update" sent to testers. Forwarding isn't affected: forwarded mail keeps its
own sender. It also means nothing may send as `@trymoly.com` unsigned, the
maintainer's own inbox included: a reply to support mail goes out from the
maintainer's address, not from `support@trymoly.com`. When the server starts
sending mail, publish that service's DKIM record for the domain first, check
one message arrives with `dmarc=pass`, and leave the policy at `reject`.

DMARC Management (Email → DMARC Management) is on: it added the `rua`
address to the record, so mail providers send their daily reports to
Cloudflare, and that page shows who is sending mail as `trymoly.com` and
whether it passed. Anything listed there before the server sends mail is
someone forging the domain.

**`www` redirect** (Rules → Redirect Rules): "Redirect from WWW to root",
`https://www.*` → `https://${1}`, 301, query string preserved.

**HTTPS** (SSL/TLS → Edge Certificates): Always Use HTTPS on. Without it,
plain `http://www.trymoly.com` misses the redirect rule and hangs.

**Web Analytics** (Observability → Analytics → Web analytics): on for
`trymoly.com`. Cloudflare adds its own script to pages opened in a browser
and counts visits and load times, without cookies. It sees nothing of the
app's requests or the API; those are in the Worker's logs (Workers & Pages →
`moly` → Observability).

**Leave off** (Security): Bot Fight Mode and Under Attack Mode. Both
challenge visitors that aren't browsers, and the app's rules download isn't
one: phones would get a "prove you're human" page instead of the rules.

## CI's access

The `Publish site` workflow deploys with two secrets. They are kept in the
`production` environment (repo Settings → Environments), not as repository
secrets, so only the deploy job can read them:

- `CLOUDFLARE_API_TOKEN`: the token named "GitHub Actions: deploy
  trymoly.com" (profile → API Tokens). It can do only what a deploy needs:

  | Scope | Permission |
  |---|---|
  | This account | Workers Scripts: Edit, D1: Edit, Account Settings: Read |
  | The `trymoly.com` domain | Workers Routes: Edit |

  It can't read or edit DNS records, email or anything else; its one reach
  into DNS is the record a deploy makes for the Worker's own domain. It
  belongs to the maintainer's login: if that login leaves the account, make
  a new one with the same permissions. If it leaks, roll it in the
  dashboard and set the secret again
  (`gh secret set CLOUDFLARE_API_TOKEN --env production`).
- `CLOUDFLARE_ACCOUNT_ID`: on the domain's Overview page. Not a secret, but
  the deploy needs it.

The `production` environment only allows `main`, so a workflow on any other
branch can't get the secrets, whatever it says. The deploy job itself also
refuses any other branch: "Run workflow" on a feature branch publishes
nothing.

## The old address

`appblock.robinroy.xyz` is this repo's GitHub Pages site (Settings → Pages,
and a CNAME at Namecheap). Nothing publishes to it any more, and it is left
switched on: it goes on serving the last site published before the move, so
builds up to 2.5.0, which fetch their rules there, keep getting them. Don't
switch Pages off or remove the CNAME.

## Access for people

Building, testing and running the server locally needs no Cloudflare
account. Access is only for looking at or changing the live one (logs, DNS,
later the database). Ask the maintainer for an invite (Manage account →
Members); the "Workers Platform Admin" role covers a developer's needs.

With access, two CLIs, each signed in with your own login:

```sh
npm install -g cf && cf auth login     # the account: DNS, the domain's settings, email
cd server && pnpm exec wrangler login  # the Worker: its logs (`wrangler tail`)
```

Ask the maintainer before changing anything in the account, and never
deploy from a laptop: deploys go through CI, which runs the checks first.

## Limits worth knowing (free plan)

- Requests the Worker's code answers: 100,000 a day. Past that they fail
  until midnight UTC; there is no bill. What counts: everything under
  `/api/`, and requests for paths that don't exist unless a browser is
  opening them as a page.
- Requests for files (the website, the rules): free and unlimited. They are
  answered without running the Worker.

## Rebuilding from nothing

1. Add `trymoly.com` to a Cloudflare account (free plan), delete every DNS
   record the import finds, and set the nameservers it gives at Namecheap.
2. Once the domain is active: Email Routing, the `_dmarc` record and DMARC
   Management, Web Analytics, the `www` redirect (accept its offer to
   create the `www` record) and Always Use HTTPS, as above.
3. Create the API token (the permissions above), create the `production`
   environment, limited to `main`, and set both secrets in it.
4. Run the `Publish site` workflow. The first deploy creates the Worker, the
   DNS record for `trymoly.com` and its certificate.
