# Security

If you find a security issue, please **don't open a public issue** — email
**support@robinroy.xyz** instead.

Worth knowing when assessing impact: the app runs an **accessibility service** (it
can see which apps are on screen, split screen and pop-ups included, and draw the
block wall over them; it reads which app each window belongs to, never what's inside
it, except YouTube's Shorts player, found by view id, when the user blocks Shorts) and
may hold the optional **Usage access** (how long apps are used, for the screen-time
numbers). Its one use of the network is downloading the Shorts-detection rules
(`rules/v1/rules.signed.json` from this repo, served at `appblock.robinroy.xyz` over HTTPS): a plain GET that sends nothing
about the device or user. The app only uses a rules file signed with the maintainer's
key (ECDSA P-256; the public half is `Storage.RULES_PUBLIC_KEY`), and a rule can only
name view ids to look for in an app whose feed the user blocked. All data (your rules and usage log) lives in local app
storage, which Android's own Auto Backup may copy to your Google account like any
app's.
