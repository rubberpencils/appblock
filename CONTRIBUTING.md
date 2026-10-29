# Contributing

**Please open issues, not pull requests.**

I'm not accepting pull requests because of the flood of AI slop. AppBlock can see
which apps you use and draw over them, on my phone and yours, so every line of it
needs to be code I can stand behind.

What works instead: **open a good issue.** Bug reports, feature requests, UX
complaints, edge cases you hit — all welcome. If the issue is clear and worth doing,
I'll implement it and it ships.

A good issue has:

- **What happened / what you want** — concrete, not "make it better"
- **Steps to reproduce** (for bugs) — which app you blocked, what budget, what you did
- **Your device + Android version** — blockers are exactly the kind of app OEMs break
  in vendor-specific ways

Thanks for understanding.

---

*Maintainer note: the first Gradle build in a clone turns on the test-running
pre-commit hook (`core.hooksPath` = `.githooks`). CI runs the same tests on every
push, plus the end-to-end tests on an emulator (`scripts/e2e.sh` runs those
locally). See AGENTS.md for the full setup.*
