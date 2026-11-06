# Tests and the trial harness

Everything here runs a throwaway world on `127.0.0.1` and never touches a live server.

## Layout

* `content/custom/*.java`: trial-only tests. Each is a `StartupListener` that does nothing unless
  its own `-Dtest.<name>=true` flag is set, so they are compiled in only for a trial build and
  never shipped. The flags each test needs are in its class comment.
* `harness/`: `build.sh`, `run.sh`, `stop.sh` and `env.sh`. Git Bash on Windows or any Linux shell.
* `run-test.sh <TestName> [-D flags]`: builds `../addons-src` plus all tests, starts the trial
  world, prints the test's `[<TestName>]` lines and stops it. `SRC=<folder>` builds another source
  tree instead (for a before/after).
* `run-list.sh <list.txt>`: runs every test named in a file, one after the other.
* `soak.sh`: runs the world for a while at live bot counts and counts exceptions, deaths, rescues
  and long pulses.
* `count-stuck.sh`: counts stuck reports in a run's log.
* `survey-merge.py`: merges survey and bug files from several runs.
* `tools/bugs.py`: read-only reader for the failure records (`docs/failure-format.md`).

## Setting up a harness folder

`HARNESS` (default `$HOME/rs-harness`) must point at a folder holding:

```
rs-harness/
  qt/
    game/            a copy of a built 2009scape server: server.jar and its data/
    test.conf        the server config for trials, with use_auth=false
  eco-pristine/      copy of deployment/seed/data/eco/
  quests-pristine/   copy of deployment/seed/data/quests/
  smartbots-pristine.json   copy of deployment/seed/data/smartbots.json
```

`run.sh` resets `qt/game/data/eco`, `data/quests` and `smartbots.json` to the pristine copies
before every run (`KEEP=1` skips that), compiles a small listener shim that binds the game port to
`127.0.0.1` only, and refuses to start if a server is already listening. `stop.sh` ends the run.

## Recording and replaying a failure

A walk that went wrong can be written down as a scenario (`docs/scenario-format.md`) and replayed
with `-Dsurvey.replay=...` so a fix is proven on the exact trip. Non-path failures use the same
idea through `EconReplayer` (`docs/failure-format.md`).
