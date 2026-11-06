Trial-only copy of `patches/src/core/net/NioReactor.java` (AGPL-3.0, upstream 2009scape). It binds the game port to
127.0.0.1 (`-D2009scape.bindAddress`) and, for farm worlds, takes the port from `-Dfarm.gamePort` (layout.json:
gamePortBase + NN*portStride). build.sh compiles it into `$HARNESS/shim`; run.sh puts that ahead of server.jar.
It is never part of a deployed build.
