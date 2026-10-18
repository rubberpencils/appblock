# Security

If you find a security issue, please **don't open a public issue** — email
**support@robinroy.xyz** instead.

Worth knowing when assessing impact: the app runs an **accessibility service** (it
can see which apps are on screen, split screen and pop-ups included, and draw the
block wall over them; it reads which app each window belongs to, never what's inside
it) and may hold the optional **Usage access** (how long apps are used, for the
screen-time numbers), but it has **no network permission** — nothing ever leaves the
device. All data (your rules and usage log) lives in local app
storage, which Android's own Auto Backup may copy to your Google account like any
app's.
