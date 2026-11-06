# Contributing

I will not be able to maintain this for a while, so expect slow replies. Pull requests are welcome
and I will merge the ones I can test.

* **Keep it an overlay.** Nothing here modifies `server.jar`; add-ons hook in through
  `StartupListener`, `Pulse` and the command mapping. If a change needs the jar patched, put the
  patched source under `deployment/overlay-src/` with a note on what it fixes.
* **Three rules every bot follows** (see `docs/bot-behaviour.md` section 0): coins only come from
  selling something, bots avoid deaths they can see coming, and if a player can watch it, it should
  look like a person.
* **Wrap every tick** in `try/catch (Throwable)` and log `[Component] ...: exception`. One bot's
  failure must never stop the others.
* **Bank and inventory changes only on the game thread.** Worker threads queue a `Pulse(1)`.
* **Test on a throwaway world.** `deployment/tests/README.md` explains the harness; it binds to
  `127.0.0.1` only. A failing trip can be recorded as a scenario (`docs/scenario-format.md`) so the
  fix is proven by replaying the exact walk.
* **Flags for anything behavioural.** New behaviour ships behind a `-D` flag with the default that
  matches what you tested, and gets a row in `docs/bot-behaviour.md` section 14.

Licensed under the AGPL v3, like 2009scape itself.
