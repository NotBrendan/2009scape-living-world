package content.custom;

import core.game.node.entity.player.Player;
import core.game.world.repository.Repository;

import java.util.Locale;
import java.util.Set;

/**
 * Train-3 audit finding 2 (the design notes): a bot's bare display name (P31 fix 1, no counter tail) can collide with
 * a real player's account registered later. The server's name index (Repository.playerNames) holds real players only, so that
 * login goes through and two active players share one display name; the addons key by that name and Ledger.Wallet.findOnline
 * prefers the real player, so the bot's clerk credits and owed coins would land in the player's bank. Off by default:
 * -Dbots.nameGuard=true turns it on. On:
 *  (1) SmartAdventurers.Brain.think, every think (4 s): a bot whose display name the index now resolves to another active real
 *      player (or a Companions.TEST_HUMANS stand-in in the trials) goes on a break by the usual path and its profile is renamed
 *      (SmartAdventurers.nameClash: pickHumanName with the old name taken; lots and owed coins follow by Ledger.rename, the quest
 *      book by QuestBook.rename), so it comes back under a name nobody holds;
 *  (2) SmartAdventurers.directBot: a "direct" profile whose name is a real player's save (Ledger.isPlayerSave) is not restored
 *      under that name; the restore goes on as a fresh bot under a new name with a name-lost event, as when a counter name
 *      cannot be rebuilt.
 * The pure helpers here are TestNameGuard (plain java); the behaviour is TestHumanLook U6/U7 (trial world).
 */
final class NameGuard {
    static final boolean ON = Boolean.getBoolean("bots.nameGuard");
    static volatile int clashes, blockedRestores; // trials
    /** Bots that found no free name (12-char names with nothing left to add): one line each per boot, tried again every think. */
    static final Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Pure: a direct restore under a name must not happen when the guard is on and a real player's save exists under it. */
    static boolean blocksRestore(boolean on, boolean playerSave) { return on && playerSave; }

    /**
     * Pure: what the name index returns for a bot's display name is a clash when the guard is on, something was returned, it is
     * not the bot itself, it is active and it is a real player (or a trial stand-in). On live the index never returns a bot
     * (AIPlayer skips it), so {@code sameObject} only matters in the trials.
     */
    static boolean clash(boolean on, boolean indexedPresent, boolean sameObject, boolean indexedActive, boolean human) {
        return on && indexedPresent && !sameObject && indexedActive && human;
    }

    /** The active real player (or TEST_HUMANS stand-in) that holds this bot's display name, or null (always null with the guard off). Game thread. */
    static Player clashWith(Player bot) {
        if (!ON || bot == null) return null;
        Player p = null;
        try { p = Repository.getPlayerByName(bot.getUsername()); } catch (Throwable ignored) { }
        if (p == null) return null;
        boolean human = !p.isArtificial() || Companions.TEST_HUMANS.contains(p.getUsername().toLowerCase(Locale.ROOT));
        return clash(true, true, p == bot, p.isActive(), human) ? p : null;
    }
}
