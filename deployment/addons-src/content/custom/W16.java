package content.custom;

import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.world.map.Location;

/**
 * W16 (2026-11-21): three not-built items from the design notes. Each has its own switch, all
 * default OFF. Off: no hook body runs, no RNG draw is added or moved, no line, no coin, no item. None of the three draws RNG
 * when on either.
 *
 *  -Dbots.fight.karaRetreat  Karamja retreat route. A bot fleeing (AfkTasks State.RETREAT) on Karamja towards a mainland bank
 *                            was only sent to the customs dock when it stood within BOAT_NEAR (120) tiles of it; farther off
 *                            the walk had no route ("goTo RETREAT ... NO ROUTE"), so it paid a teleport or took the free
 *                            home teleport mid-fight. On: with the 30 gp fare it walks to the Musa Point dock from anywhere
 *                            on the island (offline reach check on nav-p0.bin from the dock 2953,3149: Karamja mine len 472,
 *                            Brimhaven 252, Tai Bwo Wannai 384, nature ruins side 505) and takes the boat like a player.
 *                            Line: "[KaraRetreat] <bot> walks to the Musa Point dock ...".
 *  -Dbots.kara.dockWalk      The same dock walk for the other legs off Karamja (bank trips, the walk to a mainland spot),
 *                            instead of the paid teleport / home teleport (not a shop walk's crossSea: its pulse ends
 *                            after 300 ticks, short of the longer walks, so it keeps the 120-tile rule). Line: "[KaraDock] <bot> walks to the Musa Point dock ...".
 *  -Dbots.craft.spinJob      Crafting a (the w13a TODO): a bot starting a flax job with Crafting 10+ and a ground-floor wheel
 *                            within reach chains it "then spin" (the players' ::afk flax then spin chain: pick a pack, walk
 *                            to the wheel, the engine's SpinningPulse, bank / sell the bowstrings). The only ground-floor
 *                            wheel near the Seers' flax field comes from -Dbots.craft.spin (w13a, 2732,3276, reach len 219),
 *                            so run the two together. Line: "[Spin] <bot> chains flax to bow strings ...".
 */
final class W16 {
    private W16() { }

    static final boolean KARA_RETREAT = Boolean.getBoolean("bots.fight.karaRetreat");
    static final boolean DOCK_WALK = Boolean.getBoolean("bots.kara.dockWalk");
    static final boolean SPIN_JOB = Boolean.getBoolean("bots.craft.spinJob");
    /** Either Karamja switch: the one test AfkTasks.goTo makes before calling in here. */
    static final boolean KARA_ANY = KARA_RETREAT || DOCK_WALK;

    static volatile int karaRetreats, karaDocks, spinStarts, spinSkips;

    // ------------------------------------------------------------------ Karamja dock walk (pure rule)

    /**
     * True when the boat's "near the docks" limit is lifted for this walk: a bot on Karamja bound for the mainland (never the
     * way out, where Port Sarim is always walkable or teleported to as before), able to pay and pocket the fare, and not
     * bound far past the landing. A flee needs karaRetreat, any other leg dockWalk.
     */
    static boolean dockWalk(boolean retreatOn, boolean walkOn, boolean retreat, boolean out, boolean onKaramja, long coins, int fare, boolean fareFits, boolean farOn) {
        if (out || !onKaramja || farOn || !fareFits || coins < fare) return false;
        return retreat ? retreatOn : walkOn;
    }

    /**
     * w20 (NOFIRE fix): may this walk take the boat from where the bot stands? The way out (Port Sarim) always; the way back from
     * the Musa Point box (QuestPiratesTreasure.onKaramja, x 2840..2985, y 3130..3185) as before; and, new, a lifted dock walk from
     * anywhere on the island. Every tile of that box is within BOAT_NEAR (120) of the dock, so with the box as the only test the
     * lifted walk (always farther than 120) could never pass: both switches were dead code and never printed their line.
     */
    static boolean boatHere(boolean out, boolean inMusaBox, boolean lifted) { return out || inMusaBox || lifted; }

    /**
     * w20 (NOFIRE fix, karaRetreat): should a flee on Karamja be bound for the bank by the Port Sarim landing? Only far from the
     * dock (nearer, the old boat rule works), with the fare, and when the flee's own bank is no use for the boat: none in reach,
     * or a mainland one far past the landing (from Brimhaven the nearest is Yanille, 435 tiles from the landing: goTo's farOn
     * refused the boat every time, so a flee was never sent to the dock). A bank on the island itself is left alone.
     */
    static boolean retreatToSarim(boolean on, boolean onKaramja, double dockDist, int near, long coins, int fare, boolean bankNone, boolean bankOnKaramja, boolean bankFarPastLanding) {
        if (!on || !onKaramja || dockDist <= near || coins < fare) return false;
        return bankNone || !bankOnKaramja && bankFarPastLanding;
    }

    /** The mainland bank nearest the Port Sarim landing the bot may enter (Draynor's, 68 tiles), or null: the flee stays as it was. Draws nothing. */
    static Location retreatBank(AfkTasks.Runner r, Location nearest) {
        try {
            Player p = r.p;
            if (p == null || !p.isArtificial() || r.crossOnly) return null;
            Location here = p.getLocation(), land = AfkTasks.Runner.BOAT_LAND_SARIM;
            if (!retreatToSarim(KARA_RETREAT, AfkTasks.Runner.karamja(here), QuestPiratesTreasure.CUSTOMS_AT.getDistance(here), AfkTasks.Runner.BOAT_NEAR, Ledger.Wallet.coins(p), AfkTasks.Runner.BOAT_FARE,
                    nearest == null, nearest != null && AfkTasks.Runner.karamja(nearest), nearest != null && nearest.getDistance(land) > AfkTasks.Runner.TELEPORT_MIN)) return null;
            Location best = null; double d = AfkTasks.Runner.TELEPORT_MIN;
            for (Location b : AfkTasks.BANKS) {
                if (b.getX() == 3270 || AfkTasks.Runner.karamja(b) || !Gates.canEnter(p, b)) continue; // not Al Kharid's (toll gate), not the island's
                double dist = b.getDistance(land);
                if (dist < d) { d = dist; best = b; }
            }
            return best;
        } catch (Throwable t) { return null; }
    }

    /** The lane line (one per lifted walk; draws nothing). */
    static void dockLine(Player p, boolean retreat, int tiles, Location bound) {
        if (retreat) karaRetreats++; else karaDocks++;
        System.out.println((retreat ? "[KaraRetreat] " : "[KaraDock] ") + p.getUsername() + " walks to the Musa Point dock (" + tiles + " tiles) for the boat, bound for " + bound);
    }

    // ------------------------------------------------------------------ flax -> bow strings (bots.craft.spinJob)

    static final int SPIN_LEVEL = 10;
    static final int SPIN_GO = 0, SPIN_OFF = 1, SPIN_LOW = 2, SPIN_NO_WHEEL = 3, SPIN_PROBLEM = 4;
    static final String[] SPIN_WHY = {"go", "off", "Crafting under 10", "no ground-floor wheel within reach", "chain refused"};

    /** Pure rule: chain a flax job to the wheel? */
    static int spinDecision(boolean on, boolean flaxJob, int crafting, boolean wheel, boolean chainOk) {
        if (!on || !flaxJob) return SPIN_OFF;
        if (crafting < SPIN_LEVEL) return SPIN_LOW;
        if (!wheel) return SPIN_NO_WHEEL;
        return chainOk ? SPIN_GO : SPIN_PROBLEM;
    }

    /** Brain.start hook (behind SPIN_JOB): sets the Runner's chain to SPIN, or says why not. Never throws. */
    static void spinJob(Player p, AfkTasks.Runner r, Location spotAt) {
        try {
            int lvl = p.getSkills().getStaticLevel(Skills.CRAFTING);
            Location wheel = lvl < SPIN_LEVEL ? null : AfkTasks.stationNear(AfkTasks.Station.WHEEL, spotAt);
            String prob = wheel == null ? null : AfkTasks.chainProblem(p, AfkTasks.Task.FLAX, AfkTasks.Process.SPIN, "", true, false, spotAt);
            int d = spinDecision(SPIN_JOB, true, lvl, wheel != null, prob == null);
            if (d != SPIN_GO) {
                spinSkips++;
                System.out.println("[Spin] " + p.getUsername() + " skip: " + SPIN_WHY[d] + (prob != null ? " (" + prob + ")" : "") + " (Crafting " + lvl + ")");
                return;
            }
            r.chain(AfkTasks.Process.SPIN, "");
            spinStarts++;
            System.out.println("[Spin] " + p.getUsername() + " chains flax to bow strings at the wheel " + wheel + " (Crafting " + lvl + ", " + (int) wheel.getDistance(spotAt) + " tiles from the field)");
        } catch (Throwable t) {
            System.out.println("[Spin] " + p.getUsername() + " failed: " + t);
        }
    }
}
