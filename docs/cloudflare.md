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

**DNS records** (DNS → Records). Nothing is added by hand except `www`:

| Record | Made by |
|---|---|
| `trymoly.com` → the Worker | The deploy (`routes` in `wrangler.jsonc`) |
| `www` A `192.0.2.1`, proxied | By hand, for the redirect below. The address is a reserved placeholder: the redirect answers before anything is sent to it. |
| MX ×3, SPF and DKIM TXT | Email Routing. Locked: it manages them. |

A hand-made record for `trymoly.com` itself would block the deploy: the
Worker can't claim a name that already has one.

**Email** (Email → Email Routing): on, with the catch-all rule forwarding
every `@trymoly.com` address to the maintainer's inbox. Receiving only.

**`www` redirect** (Rules → Redirect Rules): "Redirect from WWW to root",
`https://www.*` → `https://${1}`, 301, query string preserved.

**HTTPS** (SSL/TLS → Edge Certificates): Always Use HTTPS on. Without it,
plain `http://www.trymoly.com` misses the redirect rule and hangs.

**Leave off** (Security): Bot Fight Mode and Under Attack Mode. Both
challenge visitors that aren't browsers, and the app's rules download isn't
one: phones would get a "prove you're human" page instead of the rules.

## CI's access

The `Publish site` workflow deploys with two repository secrets:

- `CLOUDFLARE_API_TOKEN`: made from the "Edit Cloudflare Workers" template
  (profile → API Tokens), plus Account → D1 → Edit, limited to this account
  and `trymoly.com`. It belongs to no person, so removing a member doesn't
  break deploys. If it leaks, roll it in the dashboard and set the secret
  again (`gh secret set CLOUDFLARE_API_TOKEN`).
- `CLOUDFLARE_ACCOUNT_ID`: on the domain's Overview page. Not a secret, but
  the deploy needs it.

## Access for people

Building, testing and running the server locally needs no Cloudflare
account. Access is only for looking at or changing the live one (logs, DNS,
later the database). Ask the maintainer for an invite (Manage account →
Members); the "Workers Platform Admin" role covers a developer's needs.

With access, two CLIs, each signed in with your own login:

```sh
npm install -g cf && cf auth login     # the account: DNS, the domain's settings, email
cd server && pnpm exec wrangler login  # the Worker: logs (`wrangler tail`), deploys
```

Ask the maintainer before changing anything in the account.

## Limits worth knowing (free plan)

- Requests the Worker's code answers: 100,000 a day. Past that they fail
  until midnight UTC; there is no bill. Only `/api/` and paths that don't
  exist count.
- Requests for files (the website, the rules): free and unlimited. They are
  answered without running the Worker.

## Rebuilding from nothing

1. Add `trymoly.com` to a Cloudflare account (free plan), delete every DNS
   record the import finds, and set the nameservers it gives at Namecheap.
2. Once the domain is active: Email Routing, the `www` redirect (accept its
   offer to create the `www` record) and Always Use HTTPS, as above.
3. Create the API token and set both secrets.
4. Run the `Publish site` workflow. The first deploy creates the Worker, the
   DNS record for `trymoly.com` and its certificate.
