package content.custom;

import content.global.skill.slayer.Master;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * slayer4 forced errand (-Dtest.slayerDoor=true): the Vannaka door path no farm bot was eligible to walk. One real bot.
 *   C flag sanity: Slayer.DOOR is on, door() resolves (printed).
 *   A near + poor: Edgeville bank, 2,000 gp. Slayer.errand (the trip-end entry) starts a Vannaka errand, the brain's own think
 *     drives Slayer.tick: walk to the door, free climb down (no [Paid]), a task or a clean end, free climb back up to the door.
 *   B far + rich: Ardougne, 50,000 gp. One paid teleport ("[Paid] ... Slayer.go") onto Vannaka's tile, then the climb up.
 * If errand() refuses or picks Turael (dice, open hunts, quest state) that check FAILS and the errand is then forced to Vannaka
 * (logged "forced") so the door walk still runs. Worst case about 8 min (3 min bot wait + 3 min A + 2 min B); usually 2-3 min.
 * Prints "[TestSlayerDoor] RESULT PASS|FAIL pass=N fail=M" then "[TestSlayerDoor] DONE" on every path.
 */
public final class TestSlayerDoor implements StartupListener {
    static final String T = "[TestSlayerDoor] ";
    static int pass, fail;
    static boolean done;
    static void log(String s) { System.out.println(T + s); }
    static void check(String what, boolean ok) { if (ok) pass++; else fail++; log((ok ? "PASS " : "FAIL ") + what); }
    static boolean finish() {
        if (done) return true;
        done = true;
        log("RESULT " + (fail == 0 ? "PASS" : "FAIL") + " pass=" + pass + " fail=" + fail);
        log("DONE");
        return true;
    }

    static final Location EDGE_BANK = Location.create(3094, 3491, 0), ARDOUGNE = Location.create(2655, 3283, 0);
    static final int WAIT_TICKS = 300, A_TICKS = 300, B_TICKS = 200; // 0.6 s ticks: 3 + 3 + 2 min

    @Override public void startup() {
        if (!Boolean.getBoolean("test.slayerDoor")) return;
        log("armed slayer=" + Slayer.ON + " vannaka=" + Slayer.VANNAKA + " door=" + Slayer.DOOR + " settleFirst=" + Slayer.SETTLE_FIRST + " park=" + Slayer.PARK
                + " openCheck=" + Slayer.OPEN_CHECK + " poorOk=" + Slayer.POOR_OK + " foodGate=" + P87Fight.FOOD + " walkOnly=" + Slayer.WALK_ONLY);
        try {
            GameWorld.getPulser().submit(new Pulse(1) {
                int t;
                @Override public boolean pulse() {
                    try {
                        if (++t < 100 || t < WAIT_TICKS && pickBot() == null) return false; // bots log in spread out
                        go();
                    } catch (Throwable x) { fail++; log("FAIL setup " + x); x.printStackTrace(System.out); finish(); }
                    return true;
                }
            });
        } catch (Throwable x) { fail++; log("FAIL submit " + x); finish(); }
    }

    static SmartAdventurers.Brain pickBot() {
        SmartAdventurers.Brain second = null;
        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) {
            if (b.bot == null || !b.bot.isActive() || b.companion != null || QuestRunner.active(b.bot) || !(b.bot instanceof AIPlayer)) continue;
            boolean mid = false; try { mid = QuestBook.midQuest(b.bot); } catch (Throwable ignored) { }
            if (!mid) return b;
            if (second == null) second = b;
        }
        return second;
    }

    /** Parks the brain (no think) and drops whatever it was doing; the bot stays where it is. */
    static void idle(SmartAdventurers.Brain b, long far) {
        Player p = b.bot;
        b.nextThink = far;
        AfkTasks.stop(p, null); b.runner = null; b.shopAt = null; b.pendingJob = null; b.idleUntil = 0; b.crew = null;
        try { p.getPulseManager().clear(); p.getWalkingQueue().reset(); } catch (Throwable ignored) { }
    }

    static void place(Player p, Location at) {
        Location w = null; try { w = Surveyors.Surveyor.walkable(at); } catch (Throwable ignored) { }
        p.getProperties().setTeleportLocation(w != null ? w : at);
    }

    static void setCoins(Player p, long want) {
        long have = Ledger.Wallet.coins(p);
        if (have < want) p.getBank().add(new Item(995, (int) (want - have)));
        else if (have > want) {
            long cut = have - want; int fromBank = (int) Math.min(cut, p.getBank().getAmount(995));
            if (fromBank > 0) p.getBank().remove(new Item(995, fromBank));
            if (cut > fromBank) p.getInventory().remove(new Item(995, (int) (cut - fromBank)));
        }
    }

    /** No task, no cooldowns, 2 tasks done: what the ladder and errandDue read. */
    static void slayerReset(SmartAdventurers.Brain b) {
        Slayer.St s = b.slaySt;
        try { Slayer.ensure(b.bot).clear(); } catch (Throwable x) { log("clear task: " + x); }
        s.had = null; s.names = java.util.Collections.emptySet(); s.pending = null; s.parkedUntil = 0; s.parks = 0;
        s.nextErrand = 0; s.skipUntil = 0; s.revisitUntil = 0; s.errandTo = null;
        if (s.done < Slayer.VANNAKA_DONE) s.done = Slayer.VANNAKA_DONE;
        b.slay = 0;
    }

    /** The trip-end entry. True = errand() itself started a Vannaka errand; else the errand is forced to Vannaka so the walk still runs. */
    static boolean startErrand(SmartAdventurers.Brain b, String tag, long now) {
        Slayer.St s = b.slaySt; Player p = b.bot;
        boolean has = Slayer.hasTask(p), r = false;
        try { r = Slayer.errand(b, now, has); } catch (Throwable x) { log(tag + " errand threw " + x); }
        boolean honest = r && b.slay == 1 && s.errandTo == Master.VANNAKA;
        if (!honest) {
            log(tag + " errand() returned " + r + " slay=" + b.slay + " to=" + s.errandTo + " hasTask=" + has + " nav=" + NavWalker.ON + " lastJob=" + (b.lastJob != null ? b.lastJob.label() : "null")
                    + " midQuest=" + QuestBook.midQuest(p) + " busy[mill=" + b.mill + " bone=" + b.bone + " summon=" + b.summon + " deliver=" + b.delivering + " agi=" + b.agiStep + "] -> forced to Vannaka");
            b.slay = 1; s.start = now; s.errandTo = Master.VANNAKA; s.nextErrand = now + Slayer.ERRAND_COOL_MS;
        }
        b.nextThink = now; // the brain's own think drives Slayer.tick from here
        return honest;
    }

    static void go() {
        // C: flag sanity
        check("C Slayer.ON", Slayer.ON);
        check("C Slayer.VANNAKA (needs -Dbots.slayer.vannaka + park + openCheck + fight.foodGate)", Slayer.VANNAKA);
        check("C Slayer.DOOR true in this run (-Dbots.slayer.vannakaDoor)", Slayer.DOOR);
        final Location door = Slayer.door();
        log("C resolved door tile " + door + " (hint " + Slayer.VANNAKA_DOOR_HINT + "), Vannaka at " + Slayer.VANNAKA_AT + ", TELEPORT_MIN " + AfkTasks.Runner.TELEPORT_MIN + ", nav " + NavWalker.ON);
        check("C door() resolves on the surface within 16 of the hint", door != null && door.getZ() == 0 && door.getY() < 6400 && door.getDistance(Slayer.VANNAKA_DOOR_HINT) <= 16);
        if (!Slayer.DOOR || door == null) { finish(); return; }

        final SmartAdventurers.Brain b = pickBot();
        if (b == null) { check("a free bot (BRAINS " + SmartAdventurers.BRAINS.size() + ")", false); finish(); return; }
        final Player p = b.bot; final Slayer.St s = b.slaySt;
        final long far = System.currentTimeMillis() + 6 * 3_600_000L;
        idle(b, far);
        b.shiftEnd = far;
        // the ladder's inputs, set honestly on the bot: combat 45-99 with Defence for effectiveCombat, food for a level-60 task, a combat last job
        int c0 = p.getProperties().getCurrentCombatLevel();
        if (c0 < Slayer.LADDER_TURAEL_TOP || c0 > Slayer.VANNAKA_MAX || b.effectiveCombat() < Slayer.VANNAKA_MIN) {
            for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { p.getSkills().setStaticLevel(sk, 50); p.getSkills().setLevel(sk, 50); }
            try { p.getSkills().updateCombatLevel(); } catch (Throwable ignored) { }
        }
        if (p.getBank().getAmount(379) < 20) p.getBank().add(new Item(379, 20));
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.skill < 0) { b.lastJob = j; break; }
        slayerReset(b);
        setCoins(p, 2_000);
        place(p, EDGE_BANK);
        log("bot = " + p.getUsername() + " combat " + c0 + " -> " + p.getProperties().getCurrentCombatLevel() + "/" + b.effectiveCombat() + " done " + s.done + " lastJob " + (b.lastJob != null ? b.lastJob.label() : "null"));

        GameWorld.getPulser().submit(new Pulse(1) {
            int phase, ticks, settle, paid0, down0, up0, assigned0, gaveup0;
            long coins0;
            boolean sawDoor, sawUnder, sawTile, timedOut;
            String tag = "A";

            void begin(long now) {
                coins0 = Ledger.Wallet.coins(p); paid0 = Slayer.DOOR_PAID; down0 = Slayer.DOOR_DOWN; up0 = Slayer.DOOR_UP; assigned0 = Slayer.ASSIGNED; gaveup0 = Slayer.GAVEUP;
                sawDoor = sawUnder = sawTile = timedOut = false; settle = 0;
            }
            /** One errand tick of watching. True = the errand is over (or timed out) and the bot has had 4 ticks to finish the climb. */
            boolean watch(int limit) {
                Location l = p.getLocation();
                if (l.getY() < 6400 && l.getDistance(door) <= 2) sawDoor = true;
                if (l.getY() > 6400 && l.getDistance(Slayer.VANNAKA_AT) <= 5) sawUnder = true;
                if (l.getDistance(Slayer.VANNAKA_AT) <= 1) sawTile = true;
                if (ticks % 25 == 0) log(tag + " t" + ticks + " slay=" + b.slay + " at " + l + " door=" + sawDoor + " under=" + sawUnder + " paid=" + (Slayer.DOOR_PAID - paid0) + " coins " + Ledger.Wallet.coins(p));
                if (b.slay != 0 && ticks < limit) return false;
                if (b.slay != 0 && !timedOut) { // stop it by hand: clear message, no hang
                    timedOut = true;
                    log(tag + " TIMEOUT after " + ticks + " ticks at " + l + " slay=" + b.slay);
                    try { Slayer.end(b); } catch (Throwable x) { b.slay = 0; s.errandTo = null; }
                }
                b.nextThink = far; // the errand is over: the brain stays parked
                return ++settle >= 4;
            }

            @Override public boolean pulse() {
                ticks++;
                long now = System.currentTimeMillis();
                try {
                    switch (phase) {
                        case 0: { // A start
                            if (ticks < 5) return false;
                            begin(now);
                            double md = Slayer.masterDist(p, Master.VANNAKA);
                            check("A bot at Edgeville bank " + p.getLocation() + " with " + coins0 + " gp (under MIN_COINS " + Slayer.MIN_COINS + ")", p.getLocation().getDistance(EDGE_BANK) <= 8 && coins0 == 2_000);
                            check("A near: masterDist " + (int) md + " <= TELEPORT_MIN", md <= AfkTasks.Runner.TELEPORT_MIN);
                            check("A errand() starts a Vannaka errand (not refused as far/poor)", startErrand(b, "A", now));
                            phase = 1; ticks = 0; return false;
                        }
                        case 1: { // A run
                            if (!watch(A_TICKS)) return false;
                            Location l = p.getLocation(); long coins1 = Ledger.Wallet.coins(p);
                            check("A errand ended inside " + A_TICKS + " ticks (" + ticks + ")", !timedOut);
                            check("A reached the door tile on the surface", sawDoor);
                            check("A underground within 5 tiles of Vannaka (y > 6400)", sawUnder);
                            check("A one free climb down (DOOR_DOWN +" + (Slayer.DOOR_DOWN - down0) + ")", Slayer.DOOR_DOWN - down0 == 1);
                            check("A no paid teleport (DOOR_PAID +" + (Slayer.DOOR_PAID - paid0) + ")", Slayer.DOOR_PAID == paid0);
                            check("A travel cost 0: wallet " + coins0 + " -> " + coins1, coins1 == coins0);
                            boolean task = Slayer.hasTask(p);
                            check("A task assigned (" + (task ? s.had : "none") + ", ASSIGNED +" + (Slayer.ASSIGNED - assigned0) + ") or a clean end (no walk give-up, GAVEUP +" + (Slayer.GAVEUP - gaveup0) + ")",
                                    !timedOut && sawUnder && Slayer.GAVEUP == gaveup0 && b.slay == 0 && (task ? Slayer.ASSIGNED - assigned0 == 1 : true));
                            check("A one free climb up (DOOR_UP +" + (Slayer.DOOR_UP - up0) + ")", Slayer.DOOR_UP - up0 == 1);
                            check("A back on the surface at the door: " + l, l.getY() < 6400 && l.getDistance(door) <= 2);
                            // B setup
                            idle(b, far);
                            slayerReset(b);
                            setCoins(p, 50_000);
                            place(p, ARDOUGNE);
                            tag = "B"; phase = 2; ticks = 0; return false;
                        }
                        case 2: { // B start
                            if (ticks < 5) return false;
                            begin(now);
                            double md = Slayer.masterDist(p, Master.VANNAKA);
                            check("B bot at Ardougne " + p.getLocation() + " with " + coins0 + " gp", p.getLocation().getDistance(ARDOUGNE) <= 16 && coins0 == 50_000);
                            check("B far: masterDist " + (int) md + " > TELEPORT_MIN", md > AfkTasks.Runner.TELEPORT_MIN);
                            check("B errand() starts a Vannaka errand", startErrand(b, "B", now));
                            phase = 3; ticks = 0; return false;
                        }
                        case 3: { // B run
                            if (!watch(B_TICKS)) return false;
                            Location l = p.getLocation(); long coins1 = Ledger.Wallet.coins(p);
                            check("B errand ended inside " + B_TICKS + " ticks (" + ticks + ")", !timedOut);
                            check("B exactly one paid teleport (DOOR_PAID +" + (Slayer.DOOR_PAID - paid0) + ")", Slayer.DOOR_PAID - paid0 == 1);
                            check("B the teleport cost coins: wallet " + coins0 + " -> " + coins1 + " (drop " + (coins0 - coins1) + ")", coins1 < coins0 && coins0 - coins1 <= 5_000);
                            check("B landed on Vannaka's tile " + Slayer.VANNAKA_AT, sawTile);
                            check("B no walk to the door, no climb down (DOOR_DOWN +" + (Slayer.DOOR_DOWN - down0) + ")", Slayer.DOOR_DOWN == down0);
                            boolean task = Slayer.hasTask(p);
                            check("B task assigned (" + (task ? s.had : "none") + ") or a clean end (GAVEUP +" + (Slayer.GAVEUP - gaveup0) + ")", !timedOut && sawTile && Slayer.GAVEUP == gaveup0 && b.slay == 0);
                            check("B one free climb up (DOOR_UP +" + (Slayer.DOOR_UP - up0) + ")", Slayer.DOOR_UP - up0 == 1);
                            check("B back on the surface at the door: " + l, l.getY() < 6400 && l.getDistance(door) <= 2);
                            log("door counters down=" + Slayer.DOOR_DOWN + " up=" + Slayer.DOOR_UP + " paid=" + Slayer.DOOR_PAID);
                            idle(b, far);
                            return finish();
                        }
                        default: return finish();
                    }
                } catch (Throwable x) {
                    fail++; log("FAIL " + tag + " phase " + phase + " threw " + x); x.printStackTrace(System.out);
                    try { b.slay = 0; s.errandTo = null; b.nextThink = far; } catch (Throwable ignored) { }
                    return finish();
                }
            }
        });
    }
}
