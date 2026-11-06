# overlay-src: patched server classes (optional)

Two networking classes from the 2009scape server (commit in `sources.json`), patched and compiled
into the same overlay so they replace the jar's own. They are not needed for the bots; install them
only if you hit the problem they fix.

| Class | What changed |
|---|---|
| `core/net/event/HSReadEvent.java` | TCP can deliver one handshake or registration frame across several reads. The stock class drops the opcode when that happens and the client sits on "connecting". The patch keeps the opcode with the partial payload until the whole frame arrives. Seen with clients on slow or distant links. |
| `core/net/lobby/WorldList.java` | The world-list reply the client expects from this server build. |

Compile them with the rest of the overlay:

```bash
javac --release 11 -nowarn -encoding UTF-8 -cp /path/to/server.jar -d ../../overlay core/net/event/HSReadEvent.java core/net/lobby/WorldList.java
```

These files are 2009scape code (AGPL v3) with small changes.
