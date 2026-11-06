# Client plugins

Source folders for the 2009scape client's plugin system. Each folder holds a `plugin.kt` (Kotlin,
compiled by the client at startup) and a `plugin.properties` with the author, description and
version. Copy a folder into your client's `plugins/` directory and restart the client.

Most of these are community plugins from the 2009scape plugin repository, adapted so they run
against this server (hiscores and prices come from a configurable API instead of 2009scape's).
The original authors are in each `plugin.properties`. Four were written for this project
(`AUTHOR='Private RuneScape'`).

| Plugin | What it does | Origin |
|---|---|---|
| BorderlessFullscreen | F11 toggles borderless fullscreen | this project |
| MinimapZoom | zoom buttons on the minimap | this project |
| NoIdleLogout | stops the 5-minute idle logout | this project |
| BasicInputQOL | scroll-wheel zoom, middle-click panning | Ceikry |
| BetterShiftDrop | shift-click drop and destroy | bushtail |
| ChatboxHelmets | account-type icons in the chatbox | downthecrop |
| Craftify | nameplates above players | bushtail |
| EscClose | Esc closes interfaces | Chisato |
| GroundItems | ground item overlay with prices | downthecrop |
| HolidayLoginMusic | holiday login music | Zerken |
| IdentifyClueScrolls | names clue scrolls in the inventory | bushtail |
| KondoKit | right-side panel: XP, loot, hiscores, plugin manager | downthecrop |
| LoginTimer | session timer | community |
| MiniMenuQOL | right-click menu tweaks | Ceikry |
| RememberMyLogin | remembers the last login name | Ceikry |
| SDChatForHD | SD-style chatbox in HD | Edith |
| SlayerTrackerPlugin | slayer task tracker | community |
| SmartRoofs | hides roofs | Edith |
| TabReply | Tab replies to the last private message | Ceikry |
| TakeScreenshot | Ctrl+PrintScreen screenshot | ipkpjersi |
| ToggleResizableSD | F12 resizable SD | ipkpjersi |
| ValUAdd | inventory value | bushtail |
| XPDropPlugin | XP drops | Ceikry |
| XPGlobesPlugin | XP globes | Pyrethus |

## Prices and hiscores

`ChatboxHelmets`, `GroundItems` and `KondoKit` fetch from a small HTTP API that served
`/api/compat/ge.json` (item id to price) and `/api/compat/playerSkills/<name>` (a player's skills).
That API is not part of this repo. The plugins read the base URL from the client JVM property
`livingworld.pricesUrl` (default `https://prices.example.com`) and fall back to bundled or empty
data when the host does not answer, so they are safe to install without one.
