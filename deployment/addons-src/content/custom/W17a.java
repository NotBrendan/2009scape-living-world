package content.custom;

import core.game.node.entity.combat.CombatStyle;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.GroundItem;
import core.game.node.item.GroundItemManager;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.interaction.IntType;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;
import core.game.world.map.path.Path;
import core.game.world.map.path.Pathfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/**
 * W17a (2026-11-21): the Warriors' Guild defenders loop, built on the W13 lane B draft (W13Defenders). Switch:
 * -Dbots.str.defenders (default OFF). Off: no hook body runs, no RNG draw is added or moved, no line, no coin, no item.
 * On: no RNG draw either (who goes is decided by levels, kit and a cooldown).
 *
 * What the engine has (wguild classes in server.jar, read by javap 11-21) and what the loop does with it:
 *  - Guild door 15653 (WarriorsGuild.canEnter): Attack + Strength 130+, or 99 in either. The bot clicks Open; the engine decides.
 *  - AnimationRoom: a full helm + platebody + platelegs of one metal used on an animator (15621) makes an AnimatedArmour
 *    (4278..4284) that attacks its owner; on death the three pieces and 5/10/15/20/25/30/40 tokens (8851) drop (1 in 180 a
 *    piece breaks). The bot uses a set it owns, fights, picks its drops back up, until TOKENS_GOAL.
 *  - CyclopesRoom (top floor, door 15641/15644 at 2847,3541): needs 100 tokens in the pack, takes 10 on entry and 10 a
 *    minute. The first click opens Kamfreena's "defender entry" talk (she names the defender to expect), the next click
 *    goes in. A cyclops (4291/4292) kill drops, 1 in 50, the defender after the best one worn in the shield slot or carried
 *    (bronze first, rune again once rune is held). There is no stored "shown to Kamfreena" state in this engine: the drop
 *    rule reads the pack and shield slot at each kill, so the bot carries or wears its best defender and stays in the room.
 *  - Not used: barrel / catapult / shot-put / dummy rooms (tokens only via the animators), dragon defender (not in 2009).
 * Player rules: nothing is granted. The kit (own armour set, own tokens, own best defender, own food) comes out of the bot's
 * bank at the trip end, as Hunter.kit does; nothing is bought.
 * Lines: "[Defenders] <bot> ..." (enters the guild, shows ... to Kamfreena, enters the cyclopes room, got <metal> defender,
 * wields <metal> defender, leaves the guild, banked).
 */
final class W17a {
    private W17a() { }

    static final boolean ON = Boolean.getBoolean("bots.str.defenders");
    static final int TOKEN = 8851, ENTRY_TOKENS = 100, ROOM_FEE = 10, TOKENS_GOAL = 200, ATT_STR_MIN = 130;
    static final int MIN_FOOD = 4, FOOD_TAKE = 10, MAX_BOTS = 3;
    static final long COOLDOWN_MS = 45 * 60_000L, RUN_MS = 40 * 60_000L, STALL_MS = 4 * 60_000L;
    /** Bronze .. rune defender (CyclopesRoom.DEFENDERS; no dragon defender in 2009). */
    static final int[] DEFENDERS = {8844, 8845, 8846, 8847, 8848, 8849, 8850};
    static final String[] METAL = {"bronze", "iron", "steel", "black", "mithril", "adamant", "rune"};
    /** Defence level to wield each defender. */
    static final int[] DEF_LEVEL = {1, 1, 5, 10, 20, 30, 40};
    /** AnimationRoom.ArmourSet pieces {full helm, platebody, platelegs}, bronze .. rune. */
    static final int[][] SETS = {{1155, 1117, 1075}, {1153, 1115, 1067}, {1157, 1119, 1069}, {1165, 1125, 1077}, {1159, 1121, 1071}, {1161, 1123, 1073}, {1163, 1127, 1079}};
    static final int[] ARMOUR_NPC = {4278, 4279, 4280, 4281, 4282, 4283, 4284}, CYCLOPS = {4291, 4292};
    static final int GUILD_DOOR = 15653, ANIMATOR = 15621, CYC_DOOR_A = 15641, CYC_DOOR_B = 15644;
    /** Guild footprint (CyclopesRoom's zone borders) and the cyclopes door line: the whole of plane 2 is the room except the stair lobby
     *  west of the door (x <= 2846, y <= CYC_LOBBY_Y; cache clip map and npc_spawns 4291/4292, review 11-21). */
    static final int GX0 = 2838, GY0 = 3534, GX1 = 2876, GY1 = 3556, CYC_IN_X = 2847, CYC_DOOR_Y = 3541, CYC_LOBBY_Y = 3542;

    static volatile int runs, enters, shows, roomEnters, animated, got, wields, leaves, banks, skips, walkFail, tickFails, rescued, refills;

    // ------------------------------------------------------------------ pure rules (TestW17a)

    /** Guild door rule: Attack + Strength 130+, or 99 in either. */
    static boolean entry(int attack, int strength) { return attack + strength >= ATT_STR_MIN || attack >= 99 || strength >= 99; }

    /** Index of the best defender held (-1 none). */
    static int best(IntPredicate has) { int b = -1; for (int i = 0; i < DEFENDERS.length; i++) if (has.test(DEFENDERS[i])) b = i; return b; }

    /** The defender wanted next (-1 when the rune one is held): bronze, iron, steel, black, mithril, adamant, rune. */
    static int next(int best) { return best + 1 < DEFENDERS.length ? DEFENDERS[best + 1] : -1; }

    /** What the engine drops for a bot holding `best` (CyclopesRoom.death: the next one, rune again at the top). */
    static int dropIndex(int best) { return Math.min(best + 1, DEFENDERS.length - 1); }

    /** The best defender index up to `best` the bot may wield at this Defence level (-1 none). */
    static int wearable(int best, int defence) { for (int i = Math.min(best, DEFENDERS.length - 1); i >= 0; i--) if (DEF_LEVEL[i] <= defence) return i; return -1; }

    /** The best full armour set held (-1 none). */
    static int setTier(IntPredicate has) { int b = -1; for (int i = 0; i < SETS.length; i++) if (has.test(SETS[i][0]) && has.test(SETS[i][1]) && has.test(SETS[i][2])) b = i; return b; }

    static String name(int idx) { return idx < 0 || idx >= METAL.length ? "no defender" : METAL[idx] + " defender"; }

    static int indexOf(int id) { for (int i = 0; i < DEFENDERS.length; i++) if (DEFENDERS[i] == id) return i; return -1; }

    static final int GO = 0, OFF = 1, WEAK = 2, DONE = 3, COOL = 4, CROWDED = 5, NO_TOKENS = 6, NO_FOOD = 7, PACK = 8;
    static final String[] WHY = {"go", "off", "under the door rule", "has the rune defender", "cooldown", "guild full", "under 100 tokens and no full armour set", "under " + MIN_FOOD + " food", "no pack room for the kit"};

    /** Start a run? Order: flag, door rule, rune held, cooldown, bots there, tokens or a set, food, pack room. */
    static int decide(boolean on, int attack, int strength, int best, int setTier, int tokens, int food, boolean packFits, long sinceLast, int botsThere) {
        if (!on) return OFF;
        if (!entry(attack, strength)) return WEAK;
        if (best >= DEFENDERS.length - 1) return DONE;
        if (sinceLast < COOLDOWN_MS) return COOL;
        if (botsThere >= MAX_BOTS) return CROWDED;
        if (tokens < ENTRY_TOKENS && setTier < 0) return NO_TOKENS;
        if (food < MIN_FOOD) return NO_FOOD;
        return packFits ? GO : PACK;
    }

    /** Which room next, outside the cyclopes room: the animators while a set is held and the goal is not met, else the cyclopes with 100+, else out. */
    static String room(int tokens, int setTier) {
        if (setTier >= 0 && tokens < TOKENS_GOAL) return "animator";
        return tokens >= ENTRY_TOKENS ? "cyclopes" : "leave";
    }

    /** Inside the cyclopes room: why to walk out, or null to keep fighting. */
    static String leaveWhy(int tokens, int food, int hpPct, int best, long elapsed) {
        if (best >= DEFENDERS.length - 1) return "has the rune defender";
        if (tokens < ROOM_FEE) return "out of tokens";
        if (food <= 0 && hpPct < 50) return NO_FOOD_WHY;
        return elapsed > RUN_MS ? "long enough" : null;
    }

    /** w18 (q14: 8 of 14 runs ended out of food on a 10-12 food load): pack slots kept free of food for the defender drops. */
    static final int SPARE = 2;
    static final String NO_FOOD_WHY = "out of food", REFILL = "food run";
    /** w18: extra food to take from the bot's own bank: what it has there, up to the free slots less SPARE. */
    static int foodMore(int freeSlots, int bankFood) { return Math.max(0, Math.min(bankFood, freeSlots - SPARE)); }
    /** w18: out of food in the room: bank and come back, once a run, with the door's tokens left, MIN_FOOD food in the bank and the run length not yet up. */
    static boolean refill(boolean done, int tokens, int bankFood, long elapsed) { return !done && tokens >= ENTRY_TOKENS && bankFood >= MIN_FOOD && elapsed < RUN_MS; }

    static boolean inGuild(int x, int y) { return x >= GX0 && x <= GX1 && y >= GY0 && y <= GY1; }
    static boolean inCyclopes(int x, int y, int z) { return z == 2 && inGuild(x, y) && (x >= CYC_IN_X || y > CYC_LOBBY_Y); }

    static String line(String bot, String what) { return "[Defenders] " + bot + " " + what; }

    // ------------------------------------------------------------------ the run (world only, every entry behind ON)

    static final int TO_DOOR = 0, DOOR = 1, TO_ANIM = 2, ANIM = 3, TO_CYC = 4, CYC_DOOR = 5, CYC = 6, OUT = 7, TO_BANK = 8;

    static final class Run {
        final SmartAdventurers.Brain br; final QuestSteps.Mover mv = new QuestSteps.Mover();
        int phase, clicks, wait, got, tokensSeen, tries; long start, progress; boolean shown, heldLine, refilled; DialogueDriver talk; String why = "";
        Run(SmartAdventurers.Brain br) { this.br = br; }
    }
    static final Map<String, Run> RUNS = new ConcurrentHashMap<>();
    static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    static final java.util.Set<String> FAILED = ConcurrentHashMap.newKeySet();

    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }
    static boolean on(SmartAdventurers.Brain br) { return br != null && br.bot != null && RUNS.containsKey(key(br.bot)); }
    /** Names can hold spaces: lines print them with underscores so a \S+ regex matches (as W17b.nm). */
    static String nm(Player p) { try { return p.getUsername().replace(' ', '_'); } catch (Throwable t) { return "?"; } }
    static void say(Player p, String what) { System.out.println(line(nm(p), what)); }

    static Location outside() { return Location.create(2879, 3546, 0); }
    static Location animAt() { return Location.create(2854, 3540, 0); }
    static Location cycOutside() { return Location.create(CYC_IN_X - 1, CYC_DOOR_Y, 2); }

    static int worn(Player p) { Item s = p.getEquipment().get(5); return s == null ? -1 : indexOf(s.getId()); }
    static int tokens(Player p) { return p.getInventory().getAmount(TOKEN); }
    static int food(Player p) { int n = 0; for (int f : AfkTasks.Runner.FOOD) n += p.getInventory().getAmount(f); return n; }
    static int bankFood(Player p) { int n = 0; for (int f : AfkTasks.Runner.FOOD) n += p.getBank().getAmount(f); return n; }
    /** w18: the fuller load, bank -> pack one by one (the bot's own food; nothing lost when one does not fit). */
    static void loadFood(Player p) {
        int more = foodMore(p.getInventory().freeSlots(), bankFood(p));
        for (int f : AfkTasks.Runner.FOOD) while (more > 0 && p.getBank().getAmount(f) > 0 && pull(p, f, 1)) more--;
    }
    /** Best defender the engine's drop rule sees: the shield slot or the pack. */
    static int held(Player p) { return Math.max(worn(p), best(id -> p.getInventory().getAmount(id) > 0)); }
    static int packSet(Player p) { return setTier(id -> p.getInventory().getAmount(id) > 0); }
    static boolean fighting(Player p) { try { return p.inCombat() || p.getProperties().getCombatPulse().isAttacking(); } catch (Throwable t) { return false; } }

    /** Trip end (Brain.tick, after the Hunter plan): start a run, or false. Draws no RNG. */
    static boolean plan(SmartAdventurers.Brain br, long now) {
        if (!ON || br == null) return false;
        Player p = br.bot;
        if (p == null || !p.isArtificial() || br.busy() || p.inCombat() || on(br)) return false;
        int att = p.getSkills().getStaticLevel(Skills.ATTACK), str = p.getSkills().getStaticLevel(Skills.STRENGTH);
        if (!entry(att, str)) return false;
        IntPredicate own = id -> p.getInventory().getAmount(id) > 0 || p.getBank().getAmount(id) > 0;
        int wornIdx = worn(p), best = Math.max(wornIdx, best(own)), set = setTier(own);
        int tok = tokens(p) + p.getBank().getAmount(TOKEN), fd = food(p);
        for (int f : AfkTasks.Runner.FOOD) fd += p.getBank().getAmount(f);
        // pack slots the kit needs: tokens, the best defender when it is not worn, the set pieces, the food still to take
        int need = (p.getInventory().getAmount(TOKEN) == 0 && tok > 0 ? 1 : 0) + (best > wornIdx && p.getInventory().getAmount(DEFENDERS[best]) == 0 ? 1 : 0);
        if (set >= 0) for (int id : SETS[set]) if (p.getInventory().getAmount(id) == 0) need++;
        need += Math.max(0, Math.min(FOOD_TAKE, fd) - food(p));
        Long last = LAST.get(key(p));
        int d = decide(ON, att, str, best, set, tok, fd, p.getInventory().freeSlots() >= need + 2, last == null ? COOLDOWN_MS : now - last, RUNS.size());
        if (d != GO) {
            if (d == NO_TOKENS || d == NO_FOOD || d == PACK) { skips++; LAST.put(key(p), now); say(p, "skip: " + WHY[d] + " (tokens " + tok + ", food " + fd + ", best " + name(best) + ")"); }
            return false;
        }
        if (!kit(p, best, wornIdx, set)) { skips++; LAST.put(key(p), now); say(p, "skip: the kit did not fit the pack"); return false; }
        Run r = new Run(br);
        r.start = r.progress = now; r.tokensSeen = tokens(p); r.mv.reset(outside(), 2);
        RUNS.put(key(p), r); LAST.put(key(p), now); runs++; br.nextThink = now + 600;
        say(p, "sets off for the Warriors' Guild (Attack " + att + ", Strength " + str + ", tokens " + tokens(p) + ", best " + name(best) + ", armour set " + (set < 0 ? "none" : METAL[set]) + ", food " + food(p) + ")");
        try { BotChat.activity(p, () -> "after a defender at the Warriors' Guild"); } catch (Throwable ignored) { }
        return true;
    }

    /** One item bank -> pack; false when it does not fit (nothing lost). */
    static boolean pull(Player p, int id, int n) {
        if (n <= 0) return true;
        if (!p.getBank().remove(new Item(id, n))) return false;
        if (p.getInventory().add(new Item(id, n))) return true;
        p.getBank().add(new Item(id, n)); return false;
    }

    /** The bot's own kit out of its bank: tokens, the best defender (unless worn), the armour set, food. */
    static boolean kit(Player p, int best, int wornIdx, int set) {
        int bt = p.getBank().getAmount(TOKEN);
        if (bt > 0 && !pull(p, TOKEN, bt)) return false;
        if (best > wornIdx && p.getInventory().getAmount(DEFENDERS[best]) == 0 && !pull(p, DEFENDERS[best], 1)) return false;
        if (set >= 0) for (int id : SETS[set]) if (p.getInventory().getAmount(id) == 0 && !pull(p, id, 1)) return false;
        for (int f : AfkTasks.Runner.FOOD) while (food(p) < FOOD_TAKE && p.getBank().getAmount(f) > 0) if (!pull(p, f, 1)) return food(p) >= MIN_FOOD;
        loadFood(p);
        return food(p) >= MIN_FOOD;
    }

    /** Brain.tick: drive a run. True while one is active (the Brain does nothing else that tick). Never throws. */
    static boolean tick(SmartAdventurers.Brain br, long now) {
        try { return tick0(br, now); } catch (Throwable t) { fail(br, t); return false; }
    }

    static void fail(SmartAdventurers.Brain br, Throwable t) {
        tickFails++;
        Player p = br == null ? null : br.bot;
        String k; try { k = p == null ? "?" : key(p); } catch (Throwable e) { k = "?"; }
        if (FAILED.add(k)) System.out.println("[Defenders] " + k.replace(' ', '_') + " run failed: " + t + " (run dropped; logged once per bot)");
        if (!"?".equals(k)) RUNS.remove(k);
        try { rescue(p, "run failed"); } catch (Throwable ignored) { }
    }

    static boolean tick0(SmartAdventurers.Brain br, long now) {
        if (!ON || br == null || br.bot == null) return false;
        Player p = br.bot;
        Run r = RUNS.get(key(p));
        if (r == null) return false;
        br.nextThink = now + 600;
        if (!p.isActive()) { RUNS.remove(key(p)); return false; }
        try { QuestShieldOfArrav.eatIfLow(p); } catch (Throwable ignored) { }
        if (r.talk != null) { talk(r, p); return true; }
        if (p.getLocks().isInteractionLocked()) return true;
        boolean fightPhase = r.phase == ANIM || r.phase == CYC;
        if (!fightPhase && p.getPulseManager().hasPulseRunning()) return true;
        Location here = p.getLocation();
        // review 11-21: a death (respawn elsewhere) or a paid stall teleport inside the guild phases ends the run where the bot stands
        if (r.phase >= TO_ANIM && r.phase <= OUT && !inGuild(here.getX(), here.getY()) && !p.getAttribute("state:death", false)) { end(r, "no longer in the guild (died or moved out)"); return true; }
        if (now - r.start > 2 * RUN_MS + STALL_MS) { end(r, "run time cap"); return true; }
        int tok = tokens(p);
        if (tok > r.tokensSeen) r.progress = now;
        r.tokensSeen = tok;
        switch (r.phase) {
            case TO_DOOR: {
                if (here.getZ() == 0 && inGuild(here.getX(), here.getY())) { entered(r, p, now); return true; }
                QuestSteps.St s = r.mv.tick(p);
                if (s == QuestSteps.St.DONE) { r.phase = DOOR; r.clicks = 0; r.wait = 0; }
                else if (s == QuestSteps.St.FAILED) { walkFail++; end(r, "couldn't walk to the guild (" + r.mv.last + ")"); }
                return true;
            }
            case DOOR: {
                if (inGuild(here.getX(), here.getY())) { entered(r, p, now); return true; }
                if (r.wait-- > 0) return true;
                if (r.clicks >= 4) { end(r, "the guild door stayed shut"); return true; }
                Scenery door = QuestSteps.findScenery(p, Location.create(2877, 3546, 0), 4, new int[]{GUILD_DOOR}, null, "Open");
                if (door == null) { end(r, "no guild door found"); return true; }
                QuestSteps.clearPopups(p);
                QuestSteps.click(p, door, "Open", IntType.SCENERY); r.clicks++; r.wait = 5;
                return true;
            }
            case TO_ANIM: {
                QuestSteps.St s = r.mv.tick(p);
                if (s == QuestSteps.St.DONE) { r.phase = ANIM; r.wait = 0; r.tries = 0; r.progress = now; }
                else if (s == QuestSteps.St.FAILED) { walkFail++; if (tok >= ENTRY_TOKENS) toCyclopes(r, p); else leave(r, p, "couldn't reach the animators (" + r.mv.last + ")"); }
                return true;
            }
            case ANIM: animators(r, p, now); return true;
            case TO_CYC: {
                QuestSteps.St s = r.mv.tick(p);
                if (s == QuestSteps.St.DONE) { r.phase = CYC_DOOR; r.clicks = 0; r.wait = 0; }
                else if (s == QuestSteps.St.FAILED) { walkFail++; leave(r, p, "couldn't reach the top floor (" + r.mv.last + ")"); }
                return true;
            }
            case CYC_DOOR: {
                if (inCyclopes(here.getX(), here.getY(), here.getZ())) {
                    r.phase = CYC; r.progress = now; roomEnters++;
                    say(p, "enters the cyclopes room (tokens " + tok + ", after the " + name(dropIndex(held(p))) + ")");
                    return true;
                }
                if (r.wait-- > 0) return true;
                if (DialogueDriver.open(p)) { // Kamfreena's "defender entry" talk: she names the defender to expect
                    if (!r.shown) { r.shown = true; shows++; say(p, "shows " + name(held(p)) + " to Kamfreena"); }
                    r.talk = new DialogueDriver(p);
                    return true;
                }
                if (r.clicks >= 6 || tok < ENTRY_TOKENS) { leave(r, p, tok < ENTRY_TOKENS ? "under 100 tokens at the cyclopes door" : "the cyclopes door stayed shut"); return true; }
                Scenery door = QuestSteps.findScenery(p, Location.create(CYC_IN_X, CYC_DOOR_Y, 2), 3, new int[]{CYC_DOOR_A, CYC_DOOR_B}, null, "Open");
                if (door == null) { leave(r, p, "no cyclopes door found"); return true; }
                QuestSteps.click(p, door, "Open", IntType.SCENERY); r.clicks++; r.wait = 3;
                return true;
            }
            case CYC: cyclopes(r, p, now); return true;
            case OUT: {
                if (!inCyclopes(here.getX(), here.getY(), here.getZ())) { leave(r, p, r.why); return true; }
                if (r.wait-- > 0) return true;
                if (r.clicks >= 5) { r.clicks = 0; r.mv.reset(cycOutside(), 1); r.phase = TO_BANK; r.tries = 1; return true; } // the walk out instead (TO_BANK re-aims at the bank)
                try { p.getProperties().getCombatPulse().stop(); } catch (Throwable ignored) { }
                Scenery door = QuestSteps.findScenery(p, Location.create(CYC_IN_X, CYC_DOOR_Y, 2), 3, new int[]{CYC_DOOR_A, CYC_DOOR_B}, null, "Open");
                if (door != null) QuestSteps.click(p, door, "Open", IntType.SCENERY);
                r.clicks++; r.wait = 4;
                return true;
            }
            case TO_BANK: {
                QuestSteps.St s = r.mv.tick(p);
                if (s == QuestSteps.St.RUNNING) return true;
                if (r.tries == 1) { r.tries = 0; leave(r, p, r.why); return true; } // out of the room by foot: now the bank walk
                if (s == QuestSteps.St.DONE) { if (REFILL.equals(r.why) && restock(r, p, now)) return true; bank(r, p); end(r, "done"); }
                else { walkFail++; end(r, "couldn't walk to the bank (" + r.mv.last + ")"); }
                return true;
            }
            default: end(r, "bad phase"); return false;
        }
    }

    static void talk(Run r, Player p) {
        DialogueDriver.Status s = r.talk.tick();
        if (s == DialogueDriver.Status.RUNNING) return;
        r.talk = null; r.wait = 1;
        QuestSteps.clearPopups(p);
    }

    static void entered(Run r, Player p, long now) {
        enters++; r.progress = now;
        say(p, "enters the guild (Attack " + p.getSkills().getStaticLevel(Skills.ATTACK) + ", Strength " + p.getSkills().getStaticLevel(Skills.STRENGTH) + ", tokens " + tokens(p) + ")");
        wieldBest(r, p);
        choose(r, p);
    }

    static void choose(Run r, Player p) {
        String rm = room(tokens(p), packSet(p));
        if (rm.equals("animator")) { r.phase = TO_ANIM; r.mv.reset(animAt(), 3); }
        else if (rm.equals("cyclopes")) toCyclopes(r, p);
        else leave(r, p, "under 100 tokens and no full armour set");
    }

    static void toCyclopes(Run r, Player p) { r.phase = TO_CYC; r.mv.reset(cycOutside(), 1); }

    /** Animation room: use the set on an animator, fight the armour, take the pieces and tokens back. */
    static void animators(Run r, Player p, long now) {
        int set = packSet(p), tok = tokens(p);
        int[] mine = new int[22]; int k = 0; mine[k++] = TOKEN; for (int[] s : SETS) for (int id : s) mine[k++] = id;
        int t = take(p, mine);
        if (t >= 0) { if (t > 0) r.progress = now; return; }
        if (fighting(p)) { r.progress = now; return; }
        NPC armour = null;
        for (NPC n : RegionManager.getLocalNpcs(p, 10)) {
            if (n == null || !n.isActive() || !contains(ARMOUR_NPC, n.getId())) continue;
            boolean ours = false; try { ours = n.isAttackable(p, CombatStyle.MELEE, false); } catch (Throwable ignored) { }
            if (ours) { armour = n; break; }
        }
        Object mineNow = p.getAttribute("animated_set"); // review 11-21: the engine's own handle on this bot's armour (it may have run out of the 10-tile scan)
        if (armour == null && mineNow instanceof NPC && ((NPC) mineNow).isActive()) armour = (NPC) mineNow;
        if (armour != null) { p.attack(armour); r.progress = now; return; }
        if (mineNow instanceof NPC) { // a set the engine still holds (the bot left the room with it up): it returns at logout, no new set can be animated
            if (tok >= ENTRY_TOKENS) toCyclopes(r, p); else leave(r, p, "its armour set is held by the animator until it logs out"); return;
        }
        if (now - r.progress > STALL_MS) { if (tok >= ENTRY_TOKENS) toCyclopes(r, p); else leave(r, p, "stalled at the animators"); return; }
        if (p.getAttribute("animated_set") != null) return; // the armour is still rising
        if (r.wait-- > 0) return;
        if (set < 0 || tok >= TOKENS_GOAL) { choose(r, p); return; }
        Scenery an = QuestSteps.findScenery(p, animAt(), 9, new int[]{ANIMATOR}, null, null);
        if (an == null) { if (tok >= ENTRY_TOKENS) toCyclopes(r, p); else leave(r, p, "no animator found"); return; }
        if (p.getLocation().getDistance(an.getLocation()) > 2.5) {
            if (++r.tries > 12) { if (tok >= ENTRY_TOKENS) toCyclopes(r, p); else leave(r, p, "couldn't reach an animator"); return; }
            if (!p.getWalkingQueue().isMoving()) try { Path path = Pathfinder.find(p, an); if (path != null) path.walk(p); } catch (Throwable ignored) { }
            return;
        }
        r.tries = 0;
        QuestSteps.clearPopups(p);
        QuestRunner.legacyUseWith(p, new Item(SETS[set][0]), an);
        animated++; r.wait = 12;
        say(p, "animates its " + METAL[set] + " armour (tokens " + tok + ")");
    }

    /** Cyclopes room: fight, take the defender drop, wield it, leave when the rule says so. */
    static void cyclopes(Run r, Player p, long now) {
        Location here = p.getLocation();
        if (!inCyclopes(here.getX(), here.getY(), here.getZ())) { // the engine walked it out (tokens short)
            String rm = room(tokens(p), packSet(p));
            if (rm.equals("leave") || now - r.start > RUN_MS) leave(r, p, tokens(p) < ENTRY_TOKENS ? "out of tokens" : "long enough"); else choose(r, p);
            return;
        }
        int before = held(p);
        int t = take(p, DEFENDERS);
        if (t > 0) {
            r.got++; got++; r.progress = now;
            say(p, "got " + name(indexOf(t)) + (indexOf(t) <= before ? " (a spare)" : ""));
            wieldBest(r, p);
            return;
        }
        if (t == 0) return;
        if (t == -2) { // a drop it has no room for: eat one food to make the slot, else walk out
            for (int f : AfkTasks.Runner.FOOD) if (p.getInventory().getAmount(f) > 0) { try { new core.game.bots.ScriptAPI(p).forceEat(f); } catch (Throwable ignored) { } return; }
            out(r, "pack full"); return;
        }
        int hp = p.getSkills().getLifepoints() * 100 / Math.max(1, p.getSkills().getMaximumLifepoints());
        String why = leaveWhy(tokens(p), food(p), hp, held(p), now - r.start);
        if (NO_FOOD_WHY.equals(why) && refill(r.refilled, tokens(p), bankFood(p), now - r.start)) { r.refilled = true; why = REFILL; } // w18: once a run
        if (why == null && now - r.progress > STALL_MS) why = "stalled in the cyclopes room";
        if (why != null) { out(r, why); return; }
        if (fighting(p)) { r.progress = now; return; }
        NPC pick = null; double bd = 99;
        for (NPC n : RegionManager.getLocalNpcs(p, 14)) {
            if (n == null || !n.isActive() || !contains(CYCLOPS, n.getId()) || n.inCombat()) continue;
            Location l = n.getLocation();
            if (!inCyclopes(l.getX(), l.getY(), l.getZ())) continue;
            double d = l.getDistance(here);
            if (d < bd) { bd = d; pick = n; }
        }
        if (pick != null) p.attack(pick);
    }

    static void out(Run r, String why) { r.phase = OUT; r.why = why; r.clicks = 0; r.wait = 0; }

    /**
     * The nearest of the bot's own ground drops with one of these ids. Returns the id picked up, 0 when walking to one,
     * -1 when there is none, -2 when the nearest does not fit the pack.
     */
    static int take(Player p, int[] ids) {
        Location me = p.getLocation();
        List<GroundItem> near = new ArrayList<>();
        for (int cx = (me.getX() - 9) >> 3; cx <= (me.getX() + 9) >> 3; cx++) for (int cy = (me.getY() - 9) >> 3; cy <= (me.getY() + 9) >> 3; cy++)
            try { near.addAll(RegionManager.getRegionChunk(Location.create(cx << 3, cy << 3, me.getZ())).getItems()); } catch (Throwable ignored) { }
        GroundItem best = null; double bd = 12;
        for (GroundItem g : near) {
            if (g == null || !g.isActive() || g.isRemoved() || !contains(ids, g.getId()) || !g.droppedBy(p) || g.getLocation().getZ() != me.getZ()) continue;
            double d = g.getLocation().getDistance(me);
            if (d < bd) { bd = d; best = g; }
        }
        if (best == null) return -1;
        Item it = new Item(best.getId(), best.getAmount(), best.getCharge());
        if (!p.getInventory().hasSpaceFor(it)) return -2;
        if (bd <= 1.5) { if (GroundItemManager.destroy(best) != null) { p.getInventory().add(it); return it.getId(); } return 0; }
        if (!p.getWalkingQueue().isMoving()) try { Path path = Pathfinder.find(p, best.getLocation()); if (path != null && path.isSuccessful()) path.walk(p); } catch (Throwable ignored) { }
        return 0;
    }

    static boolean contains(int[] a, int v) { for (int x : a) if (x == v) return true; return false; }

    /** Wield the best defender in the pack the bot may wear, when the shield slot is free or holds a lower defender. */
    static void wieldBest(Run r, Player p) {
        try {
            int inPack = best(id -> p.getInventory().getAmount(id) > 0), wornIdx = worn(p);
            int w = wearable(inPack, p.getSkills().getStaticLevel(Skills.DEFENCE));
            while (w >= 0 && p.getInventory().getAmount(DEFENDERS[w]) == 0) w--;
            if (w < 0 || w <= wornIdx) return;
            Item shield = p.getEquipment().get(5), weapon = p.getEquipment().get(3);
            boolean twoHanded = weapon != null && Boolean.TRUE.equals(weapon.getDefinition().getConfiguration("two_handed", false));
            if (twoHanded || shield != null && indexOf(shield.getId()) < 0) {
                if (!r.heldLine) { r.heldLine = true; say(p, "keeps " + name(w) + " in the pack (" + (twoHanded ? "two-handed weapon" : "shield worn") + ")"); }
                return;
            }
            if (!p.getInventory().remove(new Item(DEFENDERS[w]))) return;
            Item old = p.getEquipment().replace(new Item(DEFENDERS[w]), 5);
            if (old != null && !p.getInventory().add(old)) p.getBank().add(old);
            try { p.getEquipment().refresh(); } catch (Throwable ignored) { }
            wields++;
            say(p, "wields " + name(w));
        } catch (Throwable t) { say(p, "wield failed: " + t); }
    }

    /** Head for a bank: the guild's own booths when the map has them, else the nearest AfkTasks bank. */
    static void leave(Run r, Player p, String why) {
        boolean back = REFILL.equals(why); // w18: a food trip is not the end of the run
        r.why = why; r.phase = TO_BANK; r.tries = 0; if (!back) leaves++;
        Location bank = null;
        try { Scenery booth = QuestSteps.findScenery(p, Location.create(2843, 3542, 0), 7, null, "bank booth", null); if (booth != null) bank = booth.getLocation(); } catch (Throwable ignored) { }
        if (bank == null) { double bd = Double.MAX_VALUE; for (Location b : AfkTasks.BANKS) { double d = b.getDistance(p.getLocation()); if (d < bd) { bd = d; bank = b; } } }
        r.mv.reset(bank, 3);
        if (back) say(p, "goes to the bank for food (tokens " + tokens(p) + ", defenders this run " + r.got + ")");
        else say(p, "leaves the guild (" + why + "): defenders this run " + r.got + ", best " + name(held(p)) + ", tokens " + tokens(p) + ", food " + food(p));
    }

    /** At the bank: the armour set, the tokens and the defenders in the pack go in (the worn one stays on). */
    static void bank(Run r, Player p) {
        int n = 0;
        int[] ids = new int[29]; int k = 0; ids[k++] = TOKEN; for (int d : DEFENDERS) ids[k++] = d; for (int[] s : SETS) for (int id : s) ids[k++] = id;
        for (int id : ids) {
            int c = p.getInventory().getAmount(id);
            if (c <= 0 || !p.getInventory().remove(new Item(id, c))) continue;
            if (!p.getBank().add(new Item(id, c))) { p.getInventory().add(new Item(id, c)); continue; }
            n += c;
        }
        banks++;
        say(p, "banked " + n + " guild items (worn: " + name(worn(p)) + ")");
    }

    /** w18: at the bank on a food trip: the fuller load again, then back in (the guild's own booths: straight to the rooms; another bank: the door walk). False: not enough food or tokens after all, the run ends as before. */
    static boolean restock(Run r, Player p, long now) {
        r.why = NO_FOOD_WHY;
        loadFood(p);
        if (food(p) < MIN_FOOD || tokens(p) < ENTRY_TOKENS) return false;
        refills++; r.progress = now;
        say(p, "restocked to " + food(p) + " food, goes back in (tokens " + tokens(p) + ")");
        Location here = p.getLocation();
        if (here.getZ() == 0 && inGuild(here.getX(), here.getY())) choose(r, p);
        else { r.phase = TO_DOOR; r.clicks = 0; r.wait = 0; r.mv.reset(outside(), 2); }
        return true;
    }

    static void end(Run r, String why) {
        Player p = r.br.bot;
        RUNS.remove(key(p)); LAST.put(key(p), System.currentTimeMillis());
        if (!"done".equals(why)) say(p, "run ended: " + why);
        try { BotChat.clearActivity(p); } catch (Throwable ignored) { }
        try { rescue(p, why); } catch (Throwable ignored) { }
    }

    /**
     * A bot with no run standing on an upper floor of the guild (a failed run, a restart mid-run): back to the tile outside
     * the guild door, as Slayer.surface / W13Duradel.leave do for their places (the trip Runner only walks plane 0).
     * True = moved. Hooks: brain start (behind ON), end, fail.
     */
    static boolean rescue(Player p, String why) {
        if (!ON || p == null) return false;
        Location here = p.getLocation();
        if (here == null || here.getZ() == 0 || !inGuild(here.getX(), here.getY())) return false;
        Location to = outside();
        Bob.tele(p).getProperties().setTeleportLocation(to);
        try { NavWalker.forget(p); } catch (Throwable ignored) { }
        rescued++;
        say(p, "leaves the guild to " + to.getX() + "," + to.getY() + " from " + here.getX() + "," + here.getY() + "," + here.getZ() + " (" + why + ")");
        return true;
    }

    static String line() {
        return "[Defenders] hourly: runs " + runs + " enters " + enters + " shows " + shows + " room " + roomEnters + " animated " + animated + " got " + got + " wields " + wields
                + " leaves " + leaves + " banks " + banks + " skips " + skips + " walkFail " + walkFail + " tickFails " + tickFails + " rescued " + rescued + " active " + RUNS.size() + " refills " + refills;
    }
}
