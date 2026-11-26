package content.custom;

import content.global.skill.summoning.SummoningCreator;
import content.global.skill.summoning.SummoningPouch;
import content.global.skill.summoning.SummoningScroll;
import content.global.skill.summoning.familiar.Familiar;
import content.global.skill.summoning.familiar.FamiliarSpecial;
import core.game.dialogue.DialogueInterpreter;
import core.game.dialogue.DialoguePlugin;
import core.game.interaction.IntType;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.world.map.Location;

import java.util.function.Predicate;

import static content.custom.QuestScript.has;
import static content.custom.QuestScript.stageAtLeast;

/**
 * P59 -Dquest.wolfWhistle (default off: the script is not registered, so flag off = live code).
 * design: the design notes (not published) section 6. Server flow (upstream SummoningTrainingRoom.java, PikkupstixDialogue.java,
 * SpiritWolfNPC.java, SummonFamiliarPlugin.java):
 *   0 -> 10  talk to Pikkupstix (6971, 2926,3443,0), continue only (quest.start).
 *   10 -> 20 ladder 28572 "Climb-up" in his house (no familiar) starts the "fluffy cutscene" (a copy of region 11573,
 *            attribute in-cutscene); FluffyDialogue, continue only, sets 20 at its stage 8 and stops the cutscene.
 *   20 -> 30 talk, continue only.
 *   30       two wolf bones 2859 (tradeable): the bank or the Exchange stock first (Ledger.consume, paid, no mint), else the
 *            dead body 28586 "Search" at 2853,3473 (once per account, attribute searched-body, 2 free slots), guarded, only
 *            for a bot of combat 25+ (white wolves next to it).
 *   30 -> 40 talk with the bones: 2 gold charms 12158, 2 pouches 12155, 14 shards 12183, trapdoor key 12528 (add(item, player):
 *            a full pack drops them, hence makeRoom 6 first).
 *   40       trapdoor 28675 "Open" at 2927,3444 (eats the key, sets the unsaved has-key), 28676 "Climb-down" -> 2209,5348,0;
 *            infuse 2 spirit wolf pouches (12047) and Howl scrolls (12425, eats one pouch) next to the cellar obelisk;
 *            ladder 28714 "Climb" at 2209,5349 -> 2926,3444.
 *   40 -> 50 talk with pouch + scroll.
 *   50 -> 60 ladder again with pouch + scroll: continue until FluffyDialogue stage -5 (it opens the tab), one more continue
 *            (sets in-cutscene, closes; FluffyDialogue now waits at stage 6), summon the pouch (allowed while in-cutscene is
 *            set), the wolf's special on the wolpertinger 6990 (SpiritWolfNPC.specialMove calls wolf-dial.handle), then
 *            continue the last lines; FluffyDialogue stage 10 sets 60, the cutscene end dismisses the wolf.
 *   60 -> 100 Summoning points are below the level: "Renew-points" at the cellar obelisk 28716 (2209,5344; no obelisk with
 *            Renew-points within 30 tiles of the house), then talk ("Feeling refreshed?").
 * No combat. Not in this build: summoning outside the quest, beasts of burden, shop buying.
 */
final class QuestWolfWhistle {
    private QuestWolfWhistle() { }

    static final boolean ON = Boolean.getBoolean("quest.wolfWhistle");
    static final String SLUG = "wolfwhistle";
    static final content.data.Quests Q = content.data.Quests.WOLF_WHISTLE;
    static final int PIKK = 6971, WOLPERTINGER = 6990;
    static final int LADDER_UP = 28572, TRAPDOOR = 28675, TRAPDOOR_OPEN = 28676, CELLAR_LADDER = 28714, BODY = 28586;
    static final int[] OBELISKS = {28716, 28719, 28722, 28725, 28278, 28731, 28734};
    static final int BONES = 2859, POUCH = 12047, HOWL = 12425, KEY = 12528, GOLD = 12158, EMPTY = 12155, SHARDS = 12183;
    static final Location PIKK_AT = Location.create(2926, 3443, 0);
    static final Location TRAPDOOR_AT = Location.create(2927, 3444, 0);
    static final Location CELLAR = Location.create(2209, 5348, 0);
    static final Location CELLAR_LADDER_AT = Location.create(2209, 5349, 0);
    static final Location OUTSIDE = Location.create(2926, 3452, 0);
    /** F7 resolved by the trial scan (p59 trial-1, 341 regions): the only 28586 is at 2853,3473,0, at the foot of White Wolf
     *  Mountain west of Taverley, beside aggressive white wolves (96/97 at 2846,3477 / 2847,3474 / 2848,3487 / 2850,3484).
     *  2855,3473 is the nearest tile the planner reaches (p38-navport from 2926,3444: 132 tiles, no jumps). */
    static final Location BODY_AT = Location.create(2853, 3473, 0);
    static final Location BODY_SIDE = Location.create(2855, 3473, 0);
    static final int BODY_MIN_COMBAT = Integer.getInteger("quest.wolfWhistle.bodyCombat", 25);

    static int stage(Player p) { return p.getQuestRepository().getStage(Q); }
    static boolean done(Player p) { return stage(p) >= 100; }
    static boolean inCellar(Player p) { return inCellar(p.getLocation()); }
    static boolean inCellar(Location l) { return l.getX() >= 2180 && l.getX() <= 2240 && l.getY() >= 5320 && l.getY() <= 5380; }
    static boolean inScene(Player p) { return p.getAttribute("in-cutscene", null) != null || QuestRunner.inCutscene(p); }
    static boolean home(Player p) { return !inScene(p) && p.getLocation().getZ() == 0 && p.getLocation().getDistance(PIKK_AT) <= 15; }
    static boolean hasFamiliar(Player p) { try { return p.getFamiliarManager().hasFamiliar(); } catch (Throwable t) { return false; } }
    static boolean pouchAndScroll(Player p) { return has(POUCH, 1).test(p) && has(HOWL, 1).test(p); }
    static boolean pointsFull(Player p) { return p.getSkills().getLevel(Skills.SUMMONING) >= p.getSkills().getStaticLevel(Skills.SUMMONING); }
    /**
     * The trapdoor is one varp object (wrapper 4039 at 2927,3444) with both forms as children; findScenery's fallback takes
     * "any of its forms", which would show the open one before the key was used (trial 1). Only the form this bot sees counts.
     */
    static boolean canClimbDown(Player p) {
        if (p.getLocation().getZ() != 0) return false;
        try {
            Scenery w = core.game.world.map.RegionManager.getObject(0, TRAPDOOR_AT.getX(), TRAPDOOR_AT.getY());
            if (w == null) return false;
            Scenery c = QuestSteps.childOf(w, p);
            return c != null && c.getId() == TRAPDOOR_OPEN;
        } catch (Throwable t) { return false; }
    }
    static boolean bones(Player p) { return has(BONES, 2).test(p) || stage(p) >= 40; }
    /** The cellar trip is over: pouch and scroll in hand, or the stage is past it. */
    static boolean infused(Player p) { return stage(p) >= 60 || stage(p) >= 40 && pouchAndScroll(p); } // P83: at 50 too (the ladder wants both in the pack; a stage-50 bot without them goes back to the cellar)

    static QuestSteps.Talk talk(QuestSteps.Talk t) { t.limitTicks = 300; return t; }

    /** Logged once per boot (the spec asks for the tiles G3 finds). */
    static volatile boolean bodyLogged, cellarObeliskLogged;

    static Scenery obelisk(Player p, Location near, int r) {
        Scenery s = QuestSteps.findScenery(p, near, r, OBELISKS, null, "Renew-points");
        if (s == null) s = QuestSteps.findScenery(p, near, r, null, "obelisk", "Renew-points");
        return s;
    }

    /** P63: a lock held this long while the bot stands idle outside any cutscene is left over (not a ladder/door animation). */
    static final int STALE_LOCK_TICKS = 10;

    /** Clicks the house ladder until the cutscene starts. Never the generic climb fallback: it would bypass the cutscene. */
    static QuestSteps.Step upLadder(String name, int stageAfter) {
        final int[] st = {0, 0, 0, 0, 0}; // wait, clicks, idle ticks under a lock, busy ticks, ticks since direct() started (P69)
        Predicate<Player> ok = p -> stage(p) >= stageAfter || inScene(p);
        return new QuestSteps.Custom(name, 200, r -> { st[0] = 0; st[1] = 0; st[2] = 0; st[3] = 0; st[4] = 0; }, r -> {
            Player p = r.bot;
            if (ok.test(p)) return QuestSteps.St.DONE;
            if (st[0] > 0) { st[0]--; return QuestSteps.St.RUNNING; }
            // P69 (flaw #1): direct() started the cutscene once; never start a second copy, wait for in-cutscene instead.
            if (st[4] > 0) { if (++st[4] > DIRECT_WAIT_TICKS) { r.log("  [WolfWhistle] direct cutscene start: no cutscene after " + DIRECT_WAIT_TICKS + " ticks (at " + p.getLocation() + ", " + state(p) + ")"); return QuestSteps.St.FAILED; } return QuestSteps.St.RUNNING; }
            if (p.getWalkingQueue().isMoving() || p.getPulseManager().hasPulseRunning()) {
                // P67 (live train-21: "time limit 200 ticks" next to the ladder): a pulse or walk that never ends holds the step. Clear it once.
                if (++st[3] == BUSY_TICKS) { r.log("  [WolfWhistle] busy " + BUSY_TICKS + " ticks next to the ladder (" + state(p) + "): pulses cleared"); unstick(p); }
                return QuestSteps.St.RUNNING;
            }
            st[3] = 0;
            if (hasFamiliar(p)) { try { p.getFamiliarManager().dismiss(); } catch (Throwable ignored) { } st[0] = 2; return QuestSteps.St.RUNNING; }
            if (clearStaleLock(r, p, st)) return QuestSteps.St.RUNNING;
            if (stage(p) == 50 && !pouchAndScroll(p)) { // P83: the ladder at 50 wants both in the pack (else a "bring my pouch" line and no cutscene)
                fromBank(p, POUCH, 1); fromBank(p, HOWL, 1);
                if (!pouchAndScroll(p)) { r.log("  [WolfWhistle] no spirit wolf pouch and Howl scroll in the pack or the bank at stage 50: the next run makes them in the cellar"); return QuestSteps.St.FAILED; }
                r.log("  [WolfWhistle] the pouch and scroll from the bank for the ladder");
            }
            Scenery s = QuestSteps.findScenery(p, PIKK_AT, 10, new int[]{LADDER_UP}, null, "Climb-up");
            if (s == null) { r.log("  [WolfWhistle] no ladder " + LADDER_UP + " near " + PIKK_AT + " (at " + p.getLocation() + ")"); return QuestSteps.St.FAILED; }
            if (st[1] >= 6) { r.log("  [WolfWhistle] ladder " + LADDER_UP + " clicked " + st[1] + " times, no cutscene (at " + p.getLocation() + ", " + state(p) + ")"); return QuestSteps.St.FAILED; }
            if (st[1] >= 2) { // P67: the clicks did nothing (live: 18 of 77 house-ladder runs, no lock held, so P63 never fired)
                r.log("  [WolfWhistle] ladder click " + st[1] + " did nothing (at " + p.getLocation() + ", " + state(p) + ")" + (st[1] == 2 ? ": pulses and scripts cleared" : ": starting the cutscene as the ladder does"));
                if (st[1] == 2) unstick(p);
                else if (direct(p, s)) { st[1]++; st[4] = 1; return QuestSteps.St.RUNNING; }
            }
            st[1]++;
            QuestSteps.clearPopups(p);
            if (!QuestSteps.click(p, s, "Climb-up", IntType.SCENERY)) return QuestSteps.St.FAILED;
            st[0] = 12;
            return QuestSteps.St.RUNNING;
        }).done(ok);
    }

    /**
     * P67 (live train-21: 6 x "nobody to talk to (ids [6971]) within 20 of [3222, 3218, 0]"): a bot resumed at stage 40 after a
     * relog stands in Lumbridge, and every step between it and the house was already done, so the talk ran from there. A walk
     * back to the house before each talk or ladder that a resumed run can reach first.
     */
    static QuestSteps.Step houseWalk(String name, Predicate<Player> skip) {
        return new QuestSteps.Walk(name, PIKK_AT, 3).done(p -> skip.test(p) || home(p) && p.getLocation().getDistance(PIKK_AT) <= 3);
    }

    /** P69: how long upLadder waits for in-cutscene after one direct() start before it fails (never a second start). */
    static final int DIRECT_WAIT_TICKS = 20;

    /** P67: a pulse or walk held this long next to the ladder (no cutscene yet) is stuck. */
    static final int BUSY_TICKS = 40;

    /** P67: what can drop a scenery click without a word (logged so the next live failure names it). */
    static String state(Player p) {
        StringBuilder b = new StringBuilder();
        try { b.append("locks m").append(p.getLocks().isMovementLocked() ? 1 : 0).append(" i").append(p.getLocks().isInteractionLocked() ? 1 : 0); } catch (Throwable ignored) { }
        try { Object pu = p.getPulseManager().getCurrent(); b.append(", pulse ").append(pu == null ? "-" : pu.getClass().getName() + (p.getPulseManager().hasPulseRunning() ? "+" : "")); } catch (Throwable ignored) { }
        try { b.append(", move ").append(p.currentMovement == null ? "-" : p.currentMovement.getClass().getName() + (p.currentMovement.isRunning() ? "+" : "")); } catch (Throwable ignored) { }
        try { Object sc = p.scripts.getActiveScript(); b.append(", script ").append(sc == null ? "-" : sc.getClass().getSimpleName()).append(" delay ").append(p.scripts.getDelay() - core.game.world.GameWorld.getTicks()); } catch (Throwable ignored) { }
        try { b.append(", modal ").append(p.hasModalOpen() ? 1 : 0).append(" dialogue ").append(p.getDialogueInterpreter().getDialogue() == null ? 0 : 1); } catch (Throwable ignored) { }
        try { b.append(", zones ").append(p.getZoneMonitor().getZones().size()); } catch (Throwable ignored) { }
        return b.toString();
    }

    static void unstick(Player p) {
        try { p.getPulseManager().clear(); } catch (Throwable ignored) { }
        try { if (p.currentMovement != null) { p.currentMovement.stop(); p.currentMovement = null; } } catch (Throwable ignored) { }
        try { p.scripts.reset(); } catch (Throwable ignored) { }
        try { p.getWalkingQueue().reset(); } catch (Throwable ignored) { }
        QuestSteps.clearPopups(p);
    }

    /**
     * P67: what SummoningTrainingRoom "climb-up" does at stage 10 and 50 (no familiar; at 50 the pouch and the scroll), for a bot
     * standing next to the ladder whose clicks were dropped twice: ActivityManager.start(player, "fluffy cutscene", false).
     */
    static boolean direct(Player p, Scenery s) {
        int st = stage(p);
        if (st != 10 && st != 50 || hasFamiliar(p) || p.getLocation().getZ() != 0 || p.getLocation().getDistance(s.getLocation()) > 2) return false;
        if (st == 50 && (!p.getInventory().contains(POUCH, 1) || !p.getInventory().contains(HOWL, 1))) return false;
        try { unstick(p); return core.game.activity.ActivityManager.start(p, "fluffy cutscene", false); } catch (Throwable t) { return false; }
    }

    /**
     * P63 (live train-19, 3 bots ABANDONED here): InteractPlugin drops a walk-option click without a word while the bot's
     * movement or interaction lock is set, so the ladder was "clicked 5 times" and the bot never moved. Walking ignores the
     * movement lock (WalkingQueue does not check it), which is how the bot still reached the house. A long lock left behind
     * by an earlier scene (CutscenePlugin, JulietCutscenePlugin and the tutorial lock movement for 100000+ ticks) is the
     * likely source. Outside a cutscene, idle, after STALE_LOCK_TICKS: clear it once and log it; true = wait this tick.
     */
    static boolean clearStaleLock(QuestRunner r, Player p, int[] st) {
        boolean mv, ia;
        try { mv = p.getLocks().isMovementLocked(); ia = p.getLocks().isInteractionLocked(); } catch (Throwable t) { return false; }
        if (!mv && !ia) { st[2] = 0; return false; }
        if (inScene(p) || ++st[2] < STALE_LOCK_TICKS) return true;
        r.log("  [WolfWhistle] stale lock (movement " + mv + ", interaction " + ia + ") for " + st[2] + " idle ticks at " + p.getLocation() + ": cleared");
        try { p.getLocks().unlockMovement(); p.getLocks().unlockInteraction(); } catch (Throwable ignored) { }
        st[2] = 0;
        return true;
    }

    /** Continue through the first cutscene until the stage lands (no options in it; clicks only while a chatbox shows). */
    static QuestSteps.Step firstScene() {
        final int[] st = {0};
        return new QuestSteps.Custom("watch the wolpertinger upstairs", 600, r -> st[0] = 0, r -> {
            Player p = r.bot;
            if (stage(p) >= 20) return QuestSteps.St.DONE;
            if (st[0] > 0) { st[0]--; return QuestSteps.St.RUNNING; }
            continueOnce(p, st);
            return QuestSteps.St.RUNNING;
        }).done(stageAtLeast(Q, 20));
    }

    static boolean continueOnce(Player p, int[] st) {
        DialogueInterpreter di = p.getDialogueInterpreter();
        DialoguePlugin d = di.getDialogue();
        int box = DialogueDriver.chatboxId(p);
        if (d == null || box <= 0 || DialogueDriver.isOptions(box) || !di.activeTopics.isEmpty()) return false;
        di.handle(box, DialogueDriver.CONTINUE_CHILD);
        st[0] = 1 + (int) (Math.random() * 3);
        return true;
    }

    /** F8: the second cutscene. Continue to FluffyDialogue stage 6, summon the wolf, its special on the wolpertinger, continue to 60. */
    static QuestSteps.Step fight() {
        final int[] st = {0, 0, 0}; // wait, summon tries, special tries
        return new QuestSteps.Custom("set the spirit wolf on the wolpertinger", 600, r -> { st[0] = 0; st[1] = 0; }, r -> {
            Player p = r.bot;
            if (stage(p) >= 60) return QuestSteps.St.DONE;
            if (st[0] > 0) { st[0]--; return QuestSteps.St.RUNNING; }
            DialoguePlugin d = p.getDialogueInterpreter().getDialogue();
            boolean fluffy = d != null && "FluffyDialogue".equals(d.getClass().getSimpleName());
            if (!fluffy) return QuestSteps.St.RUNNING; // the cutscene is still fading in
            if (d.stage == 6) {
                if (!hasFamiliar(p)) {
                    if (++st[1] > 3 || !has(POUCH, 1).test(p)) { r.log("  [WolfWhistle] could not summon the spirit wolf (tries " + st[1] + ", pouch " + has(POUCH, 1).test(p) + ", points " + p.getSkills().getLevel(Skills.SUMMONING) + ")"); return QuestSteps.St.FAILED; }
                    p.getFamiliarManager().summon(new Item(POUCH), false);
                    st[0] = 2;
                    return QuestSteps.St.RUNNING;
                }
                NPC wolp = QuestSteps.findNpc(p, null, 16, new int[]{WOLPERTINGER}, null);
                Familiar f = p.getFamiliarManager().getFamiliar();
                if (wolp == null || f == null) { if (++st[2] > 3) { r.log("  [WolfWhistle] no wolpertinger or familiar to use the special on"); return QuestSteps.St.FAILED; } st[0] = 3; return QuestSteps.St.RUNNING; }
                if (++st[2] > 3) { r.log("  [WolfWhistle] the wolf's special did not take (3 tries)"); return QuestSteps.St.FAILED; }
                f.executeSpecialMove(new FamiliarSpecial(wolp));
                st[0] = 6; // its pulse moves FluffyDialogue to stage 7 after 4 ticks
                return QuestSteps.St.RUNNING;
            }
            continueOnce(p, st);
            return QuestSteps.St.RUNNING;
        }).done(stageAtLeast(Q, 60));
    }

    static QuestSteps.Step backHome(int stageAfter) {
        Predicate<Player> ok = p -> stage(p) >= stageAfter && !inScene(p) && p.getLocation().getZ() == 0;
        return new QuestSteps.Custom("wait to be back in Pikkupstix's house", 250, null, r -> ok.test(r.bot) ? QuestSteps.St.DONE : QuestSteps.St.RUNNING).done(ok);
    }

    /** F7: the dead body is cache-placed (no config); found by a scan around the house, its tile logged once. */
    static QuestSteps.Step searchBody() {
        final int[] st = {0, 0}; // wait, clicks
        return new QuestSteps.Custom("search the dead body for wolf bones", 300, r -> { st[0] = 0; st[1] = 0; }, r -> {
            Player p = r.bot;
            if (bones(p)) return QuestSteps.St.DONE;
            if (st[0] > 0) { st[0]--; return QuestSteps.St.RUNNING; }
            if (p.getWalkingQueue().isMoving() || p.getPulseManager().hasPulseRunning()) return QuestSteps.St.RUNNING;
            if (QuestScript.attr(p, "searched-body") && st[1] == 0) { r.log("  [WolfWhistle] body already searched by this bot: bones from the bank or the Exchange"); return QuestSteps.St.FAILED; }
            if (++st[1] > 4) { r.log("  [WolfWhistle] searched the body 4 times, no bones"); return QuestSteps.St.FAILED; }
            Scenery s = QuestSteps.findScenery(p, BODY_AT, 3, new int[]{BODY}, null, "Search");
            if (s == null) { r.log("  [WolfWhistle] no dead body " + BODY + " at " + BODY_AT); return QuestSteps.St.FAILED; }
            if (!bodyLogged) { bodyLogged = true; System.out.println("[WolfWhistle] body at " + s.getLocation().getX() + "," + s.getLocation().getY() + "," + s.getLocation().getZ()); }
            QuestRunner.makeRoom(p, 2);
            QuestSteps.clearPopups(p);
            if (!QuestSteps.click(p, s, "Search", IntType.SCENERY)) return QuestSteps.St.FAILED;
            st[0] = 4;
            return QuestSteps.St.RUNNING;
        }).done(QuestWolfWhistle::bones);
    }

    /** The body trip only for a bot that can stand the white wolves; a weaker one fails here (the quest is parked as usual). */
    static QuestSteps.Step bodyGate() {
        return new QuestSteps.Custom("check the way to the dead body", 5, null, r -> {
            Player p = r.bot;
            if (bones(p)) return QuestSteps.St.DONE;
            int cb = p.getProperties().getCurrentCombatLevel();
            if (cb < BODY_MIN_COMBAT) { r.log("  [WolfWhistle] no wolf bones in the bank or stock, and combat " + cb + " < " + BODY_MIN_COMBAT + " for the white wolves by the body"); return QuestSteps.St.FAILED; }
            QuestRunner.makeRoom(p, 8);
            QuestRunner.packFood(p, 5);
            return QuestSteps.St.DONE;
        }).done(QuestWolfWhistle::bones);
    }

    static QuestSteps.Step guarded(QuestSteps.Step s) { return new QuestBlackKnightsFortress.Guarded(s); }

    /** F6: has-key is not saved. The key from the bank, else Pikkupstix hands a new one (stage 40, PikkupstixDialogue L117). */
    static QuestSteps.Step keyFromBank() {
        return new QuestSteps.Custom("take the trapdoor key from the bank", 5, null, r -> {
            Player p = r.bot;
            if (P88.ON && p.getBank().contains(KEY, 1) && !has(KEY, 1).test(p)) QuestRunner.makeRoom(p, 1); // P88: a full pack sent the key back to the bank
            try { if (p.getBank().contains(KEY, 1) && p.getBank().remove(new Item(KEY, 1)) && !p.getInventory().add(new Item(KEY, 1))) p.getBank().add(new Item(KEY, 1)); } catch (Throwable ignored) { }
            return QuestSteps.St.DONE;
        }).done(p -> keyReady(p) || !p.getBank().contains(KEY, 1));
    }

    static boolean keyReady(Player p) { return infused(p) || inCellar(p) || stage(p) >= 50 || has(KEY, 1).test(p) || QuestScript.attr(p, "has-key") || canClimbDown(p); } // P83: from 50 the trapdoor opens without the key

    /** Opens the trapdoor (stage 40 eats the key; later stages just open it). Done once the open trapdoor shows for this bot. */
    static QuestSteps.Step openTrapdoor(String name, Predicate<Player> skip) {
        final int[] st = {0, 0}; // wait, clicks
        Predicate<Player> ok = p -> skip.test(p) || inCellar(p) || canClimbDown(p);
        return new QuestSteps.Custom(name, 200, r -> { st[0] = 0; st[1] = 0; }, r -> {
            Player p = r.bot;
            if (ok.test(p)) return QuestSteps.St.DONE;
            if (st[0] > 0) { st[0]--; return QuestSteps.St.RUNNING; }
            if (p.getWalkingQueue().isMoving() || p.getPulseManager().hasPulseRunning()) return QuestSteps.St.RUNNING;
            if (P88.ON && stage(p) == 40 && !has(KEY, 1).test(p) && !QuestScript.attr(p, "has-key") && p.getBank().contains(KEY, 1)) { // P88: a full pack left the key in the bank (Pikkupstix hands no new one then)
                QuestRunner.makeRoom(p, 1); fromBank(p, KEY, 1);
                r.log("  [WolfWhistle] (P88) the trapdoor key from the bank: " + (has(KEY, 1).test(p) ? "in the pack" : "no room"));
            }
            if (++st[1] > 5) { r.log("  [WolfWhistle] trapdoor did not open (5 clicks, key " + has(KEY, 1).test(p) + ", has-key " + QuestScript.attr(p, "has-key") + ", key banked " + p.getBank().contains(KEY, 1) + ", free " + p.getInventory().freeSlots() + ", varp1178 " + core.api.ContentAPIKt.getVarp(p, 1178) + ", at " + p.getLocation() + ")"); return QuestSteps.St.FAILED; }
            Scenery s = QuestSteps.findScenery(p, TRAPDOOR_AT, 2, new int[]{TRAPDOOR}, null, "Open");
            if (s == null) { r.log("  [WolfWhistle] no trapdoor " + TRAPDOOR + " at " + TRAPDOOR_AT); return QuestSteps.St.FAILED; }
            QuestSteps.clearPopups(p);
            if (!QuestSteps.click(p, s, "Open", IntType.SCENERY)) return QuestSteps.St.FAILED;
            st[0] = 4;
            return QuestSteps.St.RUNNING;
        }).done(ok);
    }

    /** P83: spirit wolf pouches still to make for one pouch and the scrolls (the Howl scroll eats one pouch). */
    static int pouchesToMake(Player p) {
        int pouches = p.getInventory().getAmount(POUCH);
        return Math.max(0, (has(HOWL, 1).test(p) ? 1 : 2) - pouches);
    }
    static boolean kitReady(Player p) {
        int need = pouchesToMake(p);
        return infused(p) || has(BONES, need).test(p) && has(GOLD, need).test(p) && has(EMPTY, need).test(p) && has(SHARDS, 7 * need).test(p);
    }

    /** One item from the bank into the pack, up to n in the pack. */
    static void fromBank(Player p, int id, int n) {
        int want = n - p.getInventory().getAmount(id), inBank = p.getBank().getAmount(id);
        if (want <= 0 || inBank <= 0) return;
        Item it = new Item(id, Math.min(want, inBank));
        try { if (p.getBank().remove(it) && !p.getInventory().add(it)) p.getBank().add(it); } catch (Throwable ignored) { }
    }

    /**
     * P83 (live train-33: 8 FAILED + 4 ABANDONED "custom step failed" at the infuse step, 6 + 3 at the stage-50 ladder; the
     * trials' detail line is "short of the pouch supplies (bones 0, charms 0, pouches 0, shards 0)"): a run that resumes after
     * a trip end finds the supplies (or the pouch and scroll) banked, and the ladder at 50 wants both in the pack. Before the
     * cellar: the pouch and scroll from the bank, else the supplies from the bank, wolf bones from bot stock, shards and empty
     * pouches bought from Pikkupstix's shop (his prices, Summon's bill). Gold charms only from the bank: never minted.
     */
    static QuestSteps.Step kit() {
        return new QuestSteps.Custom("get the pouch supplies together", 20, null, r -> {
            Player p = r.bot;
            if (kitReady(p)) return QuestSteps.St.DONE;
            QuestRunner.makeRoom(p, 6);
            fromBank(p, POUCH, 2); fromBank(p, HOWL, 1);
            if (kitReady(p)) { r.log("  [WolfWhistle] the pouch and scroll from the bank"); return QuestSteps.St.DONE; }
            int need = pouchesToMake(p);
            fromBank(p, BONES, need); fromBank(p, GOLD, need); fromBank(p, EMPTY, need); fromBank(p, SHARDS, 7 * need);
            int bones = need - p.getInventory().getAmount(BONES);
            if (bones > 0 && !Boolean.getBoolean("quests.noStock") && MarketBrain.stockOf(BONES) > 0) {
                int got = Ledger.consume(p, BONES, bones, "quest");
                if (got > 0) { p.getInventory().add(new Item(BONES, got)); r.log("  [WolfWhistle] bought " + got + " wolf bones from bot stock"); }
            }
            int shards = 7 * need - p.getInventory().getAmount(SHARDS), empty = need - p.getInventory().getAmount(EMPTY);
            if ((shards > 0 || empty > 0) && home(p)) {
                long bill = (long) Math.max(0, shards) * Summon.SHARD_PRICE + (long) Math.max(0, empty) * Summon.POUCH_PRICE;
                if (Ledger.Wallet.pay(p, bill)) {
                    if (shards > 0) p.getInventory().add(new Item(SHARDS, shards));
                    if (empty > 0) p.getInventory().add(new Item(EMPTY, empty));
                    r.log("  [WolfWhistle] paid Pikkupstix " + bill + " gp for " + Math.max(0, shards) + " shards and " + Math.max(0, empty) + " pouches");
                }
            }
            if (kitReady(p)) { r.log("  [WolfWhistle] pouch supplies together (bank, stock, shop)"); return QuestSteps.St.DONE; }
            if (P92.ON) { // P92: stage + items decide (pack and bank), no once-per-run marker
                int[] h = home(p) ? P92.handOut(stage(p), infused(p), need, p.getInventory().getAmount(GOLD) + p.getBank().getAmount(GOLD), p.getInventory().getAmount(EMPTY) + p.getBank().getAmount(EMPTY), p.getInventory().getAmount(SHARDS) + p.getBank().getAmount(SHARDS)) : null;
                int n = h == null ? 0 : P92.handed(p.getUsername().toLowerCase(java.util.Locale.ROOT), QuestBook.today());
                if (h != null && n >= P92.CAP) r.log("  [WolfWhistle] (P92) hand-out cap " + P92.CAP + " reached this run: no more supplies");
                else if (h != null) {
                    QuestRunner.makeRoom(p, 3);
                    boolean got = h[0] + h[1] + h[2] <= 0; if (h[0] > 0) got |= p.getInventory().add(new Item(GOLD, h[0]));
                    if (h[1] > 0) got |= p.getInventory().add(new Item(EMPTY, h[1]));
                    if (h[2] > 0) got |= p.getInventory().add(new Item(SHARDS, h[2]));
                    if (got) P92.hand(p.getUsername().toLowerCase(java.util.Locale.ROOT), QuestBook.today()); else r.log("  [WolfWhistle] (P92) no room for the supplies: nothing handed, not counted");
                    r.log("  [WolfWhistle] (P92) Pikkupstix hands the missing supplies (stage " + stage(p) + "): charms " + h[0] + ", pouches " + h[1] + ", shards " + h[2]);
                    if (kitReady(p)) return QuestSteps.St.DONE;
                }
            } else if (P88.ON && home(p) && p.getAttribute("taken-summoning-supplies", null) == null && P88.HANDED.add(p.getUsername().toLowerCase(java.util.Locale.ROOT))) { // P88: his own resupply (stage-50 branch of PikkupstixDialogue), once per login
                int g = need - p.getInventory().getAmount(GOLD), e = need - p.getInventory().getAmount(EMPTY), sh = 7 * need - p.getInventory().getAmount(SHARDS);
                p.setAttribute("taken-summoning-supplies", true);
                if (g > 0) p.getInventory().add(new Item(GOLD, g));
                if (e > 0) p.getInventory().add(new Item(EMPTY, e));
                if (sh > 0) p.getInventory().add(new Item(SHARDS, sh));
                r.log("  [WolfWhistle] (P88) Pikkupstix hands the missing supplies: charms " + Math.max(0, g) + ", pouches " + Math.max(0, e) + ", shards " + Math.max(0, sh));
                if (kitReady(p)) return QuestSteps.St.DONE;
            }
            r.log("  [WolfWhistle] pouch supplies short after the bank, stock and shop (need " + need + ": bones " + p.getInventory().getAmount(BONES) + ", charms " + p.getInventory().getAmount(GOLD) + ", pouches " + p.getInventory().getAmount(EMPTY) + ", shards " + p.getInventory().getAmount(SHARDS) + ", coins " + Ledger.Wallet.coins(p) + ")");
            return QuestSteps.St.FAILED;
        }).done(QuestWolfWhistle::kitReady);
    }

    /**
     * P83 (live train-33: 4 FAILED + 2 ABANDONED "dialogue never opened [seen []]" giving the bones, after the body trip):
     * a pulse or lock left by the guarded walk drops the Talk-to click without a word. Settle first: wait up to 10 ticks for
     * it to end by itself, then clear it (outside any cutscene) and log what it was.
     */
    static QuestSteps.Step calm(String name) {
        final int[] st = {0};
        return new QuestSteps.Custom(name, 40, r -> st[0] = 0, r -> {
            Player p = r.bot;
            if (inScene(p)) return QuestSteps.St.DONE;
            boolean locked; try { locked = p.getLocks().isMovementLocked() || p.getLocks().isInteractionLocked(); } catch (Throwable t) { locked = false; }
            boolean busy = p.getPulseManager().hasPulseRunning() && !p.getWalkingQueue().isMoving();
            if (!locked && !busy) return QuestSteps.St.DONE;
            if (++st[0] < 10) return QuestSteps.St.RUNNING;
            r.log("  [WolfWhistle] " + (locked ? "lock" : "pulse") + " held " + st[0] + " idle ticks before the talk (" + state(p) + "): cleared");
            unstick(p);
            try { p.getLocks().unlockMovement(); p.getLocks().unlockInteraction(); } catch (Throwable ignored) { }
            return QuestSteps.St.DONE;
        });
    }

    /** Infuse 2 spirit wolf pouches, then Howl scrolls (eats one pouch), within 2 tiles of the cellar obelisk. */
    static QuestSteps.Step infuse() {
        final int[] st = {0, 0}; // wait, creates
        return new QuestSteps.Custom("infuse the spirit wolf pouch and Howl scrolls", 300, r -> { st[0] = 0; st[1] = 0; }, r -> {
            Player p = r.bot;
            if (infused(p)) return QuestSteps.St.DONE;
            if (st[0] > 0) { st[0]--; return QuestSteps.St.RUNNING; }
            if (p.getWalkingQueue().isMoving() || p.getPulseManager().hasPulseRunning()) return QuestSteps.St.RUNNING;
            if (!inCellar(p)) { r.log("  [WolfWhistle] not in the cellar at " + p.getLocation()); return QuestSteps.St.FAILED; }
            Scenery ob = obelisk(p, CELLAR, 12);
            if (ob == null) { r.log("  [WolfWhistle] no obelisk within 12 of " + CELLAR); return QuestSteps.St.FAILED; }
            if (!cellarObeliskLogged) { cellarObeliskLogged = true; System.out.println("[WolfWhistle] cellar obelisk " + ob.getId() + " at " + ob.getLocation()); }
            if (p.getLocation().getDistance(ob.getLocation()) > 2.5) {
                if (st[1] > 0 && ++st[1] > 12) { r.log("  [WolfWhistle] could not reach the cellar obelisk at " + ob.getLocation()); return QuestSteps.St.FAILED; }
                if (st[1] == 0) st[1] = 1;
                try { core.game.world.map.path.Pathfinder.find(p, ob).walk(p); } catch (Throwable ignored) { }
                st[0] = 3;
                return QuestSteps.St.RUNNING;
            }
            int pouches = p.getInventory().getAmount(POUCH);
            if (pouches >= 2 || pouches >= 1 && has(HOWL, 1).test(p)) {
                if (has(HOWL, 1).test(p)) return QuestSteps.St.DONE;
                if (++st[1] > 20) { r.log("  [WolfWhistle] Howl scroll not made"); return QuestSteps.St.FAILED; }
                SummoningCreator.create(p, 1, SummoningScroll.HOWL_SCROLL);
                st[0] = 3;
                return QuestSteps.St.RUNNING;
            }
            int need = 2 - pouches;
            boolean sets = has(BONES, need).test(p) && has(GOLD, need).test(p) && has(EMPTY, need).test(p) && has(SHARDS, 7 * need).test(p);
            if (!sets) { r.log("  [WolfWhistle] short of the pouch supplies (bones " + p.getInventory().getAmount(BONES) + ", charms " + p.getInventory().getAmount(GOLD) + ", pouches " + p.getInventory().getAmount(EMPTY) + ", shards " + p.getInventory().getAmount(SHARDS) + ")"); return QuestSteps.St.FAILED; }
            if (++st[1] > 20) { r.log("  [WolfWhistle] spirit wolf pouches not made"); return QuestSteps.St.FAILED; }
            SummoningCreator.create(p, need, SummoningPouch.SPIRIT_WOLF_POUCH);
            st[0] = 3;
            return QuestSteps.St.RUNNING;
        }).done(QuestWolfWhistle::infused);
    }

    /** Renew Summoning points at the obelisk near `near` (the cellar one: none with Renew-points within 30 of the house). */
    static QuestSteps.Step renew(String name, Location near, int radius) {
        final int[] st = {0, 0}; // wait, clicks
        Predicate<Player> ok = p -> pointsFull(p) || done(p);
        QuestSteps.Step s = new QuestSteps.Custom(name, 400, r -> { st[0] = 0; st[1] = 0; }, r -> {
            Player p = r.bot;
            if (ok.test(p)) return QuestSteps.St.DONE;
            if (st[0] > 0) { st[0]--; return QuestSteps.St.RUNNING; }
            if (p.getWalkingQueue().isMoving() || p.getPulseManager().hasPulseRunning()) return QuestSteps.St.RUNNING;
            if (++st[1] > 5) { r.log("  [WolfWhistle] obelisk near " + near + " did not renew the points (5 clicks)"); return QuestSteps.St.FAILED; }
            Scenery ob = obelisk(p, near, radius);
            if (ob == null) { r.log("  [WolfWhistle] no obelisk with Renew-points within " + radius + " of " + near); return QuestSteps.St.FAILED; }
            QuestSteps.clearPopups(p);
            if (!QuestSteps.click(p, ob, "Renew-points", IntType.SCENERY)) return QuestSteps.St.FAILED;
            st[0] = 6;
            return QuestSteps.St.RUNNING;
        }).done(ok);
        return s;
    }

    static QuestSteps.Step room(int slots) {
        return new QuestSteps.Custom("make room in the pack", 5, null, r -> { QuestRunner.makeRoom(r.bot, slots); return QuestSteps.St.DONE; });
    }

    static QuestScript build() {
        QuestScript s = QuestScript.of(Q, SLUG, "Wolf Whistle").startAt(2926, 3443, 0).minutes(30).xp(276 * 0.5).unlock(30).near(2);

        // F11: never a familiar during a quest; out of the cellar first if a run resumes down there
        s.step(new QuestSteps.Custom("no familiar out", 5, null, r -> { if (hasFamiliar(r.bot)) try { r.bot.getFamiliarManager().dismiss(); } catch (Throwable ignored) { } return QuestSteps.St.DONE; }).done(p -> !hasFamiliar(p)));
        s.step(new QuestSteps.Interact("climb out of Pikkupstix's cellar", new int[]{CELLAR_LADDER}, null, "Climb", CELLAR_LADDER_AT, 6, false).until(p -> !inCellar(p)).done(p -> !inCellar(p) || (stage(p) == 40 || stage(p) == 50) && !infused(p) && kitReady(p))); // P83: up for the bank/shop when short

        // 0 -> 10
        s.step(new QuestSteps.Walk("walk to Pikkupstix in Taverley", PIKK_AT, 3).done(p -> stage(p) >= 10 || p.getLocation().getZ() == 0 && p.getLocation().getDistance(PIKK_AT) <= 3));
        s.step(talk(new QuestSteps.Talk("ask Pikkupstix what the trouble is", new int[]{PIKK})).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));

        // 10 -> 20: the wolpertinger upstairs
        s.step(houseWalk("walk to the ladder in Pikkupstix's house", p -> stage(p) >= 20 || inScene(p)));
        s.step(upLadder("climb the ladder to see the monster", 20));
        s.step(firstScene());
        s.step(backHome(20));

        // 20 -> 30
        s.step(new QuestSteps.Walk("back to Pikkupstix", PIKK_AT, 3).done(p -> stage(p) >= 30 || home(p) && p.getLocation().getDistance(PIKK_AT) <= 3));
        s.step(talk(new QuestSteps.Talk("tell Pikkupstix about the teeth", new int[]{PIKK})).until(stageAtLeast(Q, 30)).done(stageAtLeast(Q, 30)));

        // 30: two wolf bones (the dead body, else the bank or the Exchange stock)
        s.step(new QuestSteps.Collect("look for wolf bones in the bank and the Exchange", BONES, 2).stock().done(QuestWolfWhistle::bones).optional());
        s.step(bodyGate());
        s.step(guarded(new QuestSteps.Walk("walk to the dead body below White Wolf Mountain", BODY_SIDE, 2)).done(p -> bones(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(BODY_SIDE) <= 2));
        s.step(guarded(searchBody()).done(QuestWolfWhistle::bones));
        s.step(new QuestSteps.Walk("back to Pikkupstix with the bones", PIKK_AT, 3).done(p -> stage(p) >= 40 || p.getLocation().getZ() == 0 && p.getLocation().getDistance(PIKK_AT) <= 3));

        // 30 -> 40: the supplies and the key (F5: room for them first)
        s.step(room(6).done(p -> stage(p) >= 40));
        s.step(calm("settle before giving the bones").done(stageAtLeast(Q, 40)));
        s.step(talk(new QuestSteps.Talk("give Pikkupstix the wolf bones", new int[]{PIKK})).until(stageAtLeast(Q, 40)).done(stageAtLeast(Q, 40)));

        // 40: the cellar and its obelisk
        s.step(keyFromBank());
        s.step(houseWalk("walk to Pikkupstix for the trapdoor", p -> infused(p) || inCellar(p)));
        s.step(kit());
        s.step(talk(new QuestSteps.Talk("ask Pikkupstix for the trapdoor key", new int[]{PIKK})).until(QuestWolfWhistle::keyReady).done(QuestWolfWhistle::keyReady));
        if (P92.ON) s.step(calm("(P92) settle before the trapdoor").done(p -> infused(p) || inCellar(p) || canClimbDown(p)));
        s.step(openTrapdoor("open the trapdoor", QuestWolfWhistle::infused));
        if (P92.ON) s.step(calm("(P92) settle before climbing down").done(p -> inCellar(p) || infused(p)));
        s.step(new QuestSteps.Interact("climb down to the obelisk cellar", new int[]{TRAPDOOR_OPEN}, null, "Climb-down", TRAPDOOR_AT, 3, false).until(QuestWolfWhistle::inCellar).done(p -> inCellar(p) || infused(p)));
        s.step(infuse());
        s.step(new QuestSteps.Interact("climb out of the cellar", new int[]{CELLAR_LADDER}, null, "Climb", CELLAR_LADDER_AT, 6, false).until(p -> !inCellar(p)).done(p -> !inCellar(p)));

        // 40 -> 50
        s.step(houseWalk("walk to Pikkupstix with the pouch and scroll", p -> stage(p) >= 50 || inCellar(p)));
        s.step(talk(new QuestSteps.Talk("show Pikkupstix the pouch and scroll", new int[]{PIKK})).until(stageAtLeast(Q, 50)).done(stageAtLeast(Q, 50)));

        // 50 -> 60: the spirit wolf against the wolpertinger
        s.step(houseWalk("walk to the ladder with the pouch", p -> stage(p) >= 60 || inScene(p)));
        s.step(upLadder("climb the ladder with the spirit wolf pouch", 60));
        s.step(fight());
        s.step(backHome(60));

        // 60 -> 100: renew the points (the obelisk by the village, else the cellar one), then report
        Predicate<Player> full = p -> pointsFull(p) || done(p);
        // no obelisk with Renew-points within 30 of the house (trial 1 scan): the cellar obelisk 28716 at 2209,5344
        if (P92.ON) s.step(calm("(P92) settle before the cellar trapdoor").done(p -> full.test(p) || inCellar(p) || canClimbDown(p)));
        s.step(openTrapdoor("open the trapdoor for the cellar obelisk", full));
        if (P92.ON) s.step(calm("(P92) settle before climbing down to the obelisk").done(p -> inCellar(p) || full.test(p)));
        s.step(new QuestSteps.Interact("climb down to the cellar obelisk", new int[]{TRAPDOOR_OPEN}, null, "Climb-down", TRAPDOOR_AT, 3, false).until(QuestWolfWhistle::inCellar).done(p -> inCellar(p) || full.test(p)));
        s.step(renew("renew Summoning points at the cellar obelisk", CELLAR, 12));
        s.step(new QuestSteps.Interact("climb back out of the cellar", new int[]{CELLAR_LADDER}, null, "Climb", CELLAR_LADDER_AT, 6, false).until(p -> !inCellar(p)).done(p -> !inCellar(p)));
        s.step(new QuestSteps.Walk("back to Pikkupstix to report", PIKK_AT, 3).done(p -> done(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(PIKK_AT) <= 3));
        s.step(room(4).done(QuestWolfWhistle::done));
        s.step(talk(new QuestSteps.Talk("tell Pikkupstix the wolpertinger is gone", new int[]{PIKK})).until(QuestWolfWhistle::done).done(QuestWolfWhistle::done));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));
        s.exit(new QuestSteps.Walk("walk out of Pikkupstix's house", OUTSIDE, 4));
        return s;
    }
}
