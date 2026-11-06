package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Trial only (-Dtest.b23afk=true -Dsmartbots.count=0): batch 23 section 4.2 (the design notes), the
 * player ::afk walks. Run it twice: planner off (no -Dnav, no data/survey/nav.on) and planner on (-Dnav=true); the mode
 * is whatever the JVM started with and is printed in the totals line. Optional -Dtest.b23afk.only=A1,G2 runs a subset.
 *
 * Each case is its own non-bot Human (TestAfk pattern), placed with Location.create, given coins/tools/levels and started
 * with AfkTasks.handle. All cases run side by side from the same tick; each is checked every tick and logged every 50
 * ticks (r.state, r.last, location). One result line per case: "[TestB23Afk] B23 <case> PASS|FAIL|SKIP ticks=<n> last=<r.last> | why",
 * then "B23 H1 ..." (pulse cost) and "B23 TOTAL ...". Budgets: A/B/C/D/E/F 400 ticks, G 2000.
 *
 * Deviations from the design table (the command forms it gives don't exist in AfkTasks today):
 *  - B1/B2: "::afk gold ..." is the gold-making plan (handle's "gold" sub-command), never Task.GOLD, so B1/B2 use
 *    mithril (same spots: LSW, KARA_MINE) with Mining 55.
 *  - C1/D1/E1/F2: "at" only takes named spots, so these start a plain "logs" runner and then send it to the tile with
 *    Runner.goTo (r.spot set first for TO_SPOT), the same call handle makes for "at". Revisit when T5 widens "at".
 *  - F1 uses "tree at draynor" (TREE has the Draynor spot; TestAfk L52).
 * UNVERIFIED tiles (C1 gate pick, D1 desert tile, E1 Entrana tile) print SKIP, not FAIL, when the tile is not standable.
 * H1 reads Watchdog.pulseTopSrc/pulseTopNs every tick (the minute's heaviest add-on pulse) and keeps the max seen while the
 * top source was "AfkTasks.Runner": a lower bound (a heavier other pulse in the same minute hides the runner's), all
 * player runners together. Needs failures on (default). Exceptions: grep the run log for "Exception" / "[AfkTasks] .*: ".
 *
 * P16 item 5: -Dtest.b23combos=true (instead of -Dtest.b23afk) runs the T4 combos and T5 trials instead of A-G:
 * K1 iron smelt smith dagger, K2 cook from bank, K3 logs fletch, K4 logs burn, K5 wool spin, K6 flax spin, K7 bury from bank
 * (pass: a product in pack/bank, or the skill's XP rising), T1 coal from an unlisted cluster (UNVERIFIED start 3016,3450),
 * T2 cows (HuntMap), T3 "ore" without a pickaxe (refusal naming the nearest shop). Same result/TOTAL lines.
 */
public final class TestB23Afk implements StartupListener {
    static void log(String s) { System.out.println("[TestB23Afk] " + s); }

    static final class Human extends AIPlayer {
        List<String> msgs; // lazily: sendMessage may run from the super constructor
        Human(Location l) { super(l); }
        @Override public void sendMessage(String m) {
            String s = m.replaceAll("<col=[0-9a-f]+>", "");
            if (msgs == null) msgs = new ArrayList<>();
            msgs.add(s);
            System.out.println("[TestB23Afk]   " + getUsername() + " <- " + s);
        }
        String lastMsg() { return msgs == null || msgs.isEmpty() ? "" : msgs.get(msgs.size() - 1); }
        boolean said(String part) { if (msgs != null) for (String s : msgs) if (s.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT))) return true; return false; }
    }

    static final class Case {
        final String id; final int budget; final Location start;
        Location target; String skip;
        Human h; String key; AfkTasks.Runner r;
        int ticks, coins0, stopTick = -1, minX = Integer.MAX_VALUE;
        boolean stopped, tele, sawBank, done;
        double maxDist; Location prev;
        int legs; Location[] ends; // A4: the two ends of the shuttle and the legs walked
        int skill = -1; double xp0; int[] want; // P16 item 5 (K/T cases): the skill whose XP must rise, and the product ids that pass
        String verdict, why;
        String note; // P20 K4: the runner's last light refusal note, logged when it changes
        Case(String id, int budget, Location start) { this.id = id; this.budget = budget; this.start = start; }
    }

    static final int COINS = 995, AXE = 1351, PICK = 1265, NET = 303, WATERSKIN = 1823;
    static final int COPPER = 436, MITHRIL = 447, IRON_BAR = 2351, SHRIMP_COOKED = 315;
    static final int HAMMER = 2347, KNIFE = 946, TINDERBOX = 590, SHEARS = 1735, SHRIMP_RAW = 317, BONES = 526, COAL = 453; // P16 item 5
    static final int IRON_DAGGER = 1203, ARROW_SHAFT = 52, SHORTBOW_U = 50, BALL_OF_WOOL = 1759, BOW_STRING = 1777;
    static final Location AK_BANK = Location.create(3270, 3167, 0), DRAYNOR = Location.create(3087, 3236, 0);
    static final Map<String, Case> CASES = new LinkedHashMap<>();
    static boolean nav;
    static long runnerMaxNs;

    static boolean wanted(String id) {
        String only = System.getProperty("test.b23afk.only", "").trim();
        if (only.isEmpty()) return true;
        for (String s : only.split(",")) if (s.trim().equalsIgnoreCase(id)) return true;
        return false;
    }

    static Human human(Location l) throws Exception {
        Human h = null;
        for (int i = 0; i < 5; i++) { // AIPlayer names are generated: never two cases on one RUNNING key
            h = new Human(l);
            boolean dup = false;
            for (Case c : CASES.values()) if (c.h != null && c.key.equals(h.getUsername().toLowerCase(Locale.ROOT))) dup = true;
            if (!dup) break;
        }
        java.lang.reflect.Field f = Player.class.getDeclaredField("artificial"); f.setAccessible(true); f.setBoolean(h, false);
        AfkTasks.OWNERS.add(h.getUsername().toLowerCase(Locale.ROOT));
        h.getInventory().clear(); h.getBank().clear();
        for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS, Skills.WOODCUTTING, Skills.MINING, Skills.FISHING, Skills.SMITHING, Skills.COOKING})
            level(h, sk, 30);
        return h;
    }
    static void level(Player p, int sk, int lv) { p.getSkills().setStaticLevel(sk, lv); p.getSkills().setLevel(sk, lv); }
    static void give(Player p, int id, int n) { if (n > 0) p.getInventory().add(new Item(id, n)); }
    static int coins(Player p) { return p.getInventory().getAmount(COINS) + p.getBank().getAmount(COINS); }

    /** Standable: the planner grid when there is one, else the engine clipping flag (NavGrid.pack's blocking bits). */
    static final long CAP = Long.getLong("afk.spendCap", 100L); // P16 item 4: the same property AfkTasks reads

    static boolean stand(Location l) {
        if (l == null) return false;
        if (NavGrid.ready()) return NavGrid.standable(l.getX(), l.getY());
        return (OffGround.flag(l.getZ(), l.getX(), l.getY()) & (NavGrid.SOLID | NavGrid.UNREAD)) == 0;
    }

    static void cmd(Case c, String line) {
        log(c.id + " ::afk " + line + " (at " + c.h.getLocation() + ", coins " + coins(c.h) + ")");
        AfkTasks.handle(c.h, ("afk " + line).split(" "));
        c.r = AfkTasks.RUNNING.get(c.key);
    }

    /** Test injection (see the class comment): send the case's runner to a tile the "at" word can't name. */
    static void sendTo(Case c, AfkTasks.State s, Location to) {
        if (c.r == null) return;
        if (s == AfkTasks.State.TO_SPOT) c.r.spot = to;
        boolean ok = c.r.goTo(s, to);
        log(c.id + " sent to " + to + " as " + s + ": goTo " + ok);
    }

    /** C1: a multi-tile gate from the NAV2 trailer near Falador east (UNVERIFIED pick), a tile each side across its narrow axis. */
    static Location[] gatePair() {
        if (NavGrid.BIG_GATE.isEmpty()) return null;
        Map<Integer, int[]> boxes = new LinkedHashMap<>(); // origin index -> {minX, minY, maxX, maxY}
        for (Map.Entry<Integer, Integer> e : NavGrid.BIG_GATE.entrySet()) {
            int k = e.getKey(), x = NavGrid.X0 + k % NavGrid.W, y = NavGrid.Y0 + k / NavGrid.W;
            int[] b = boxes.computeIfAbsent(e.getValue(), o -> new int[]{x, y, x, y});
            b[0] = Math.min(b[0], x); b[1] = Math.min(b[1], y); b[2] = Math.max(b[2], x); b[3] = Math.max(b[3], y);
        }
        int[] best = null; double bd = 80;
        for (int[] b : boxes.values()) { double d = Math.hypot(b[0] - 3007, b[1] - 3360); if (d < bd) { bd = d; best = b; } }
        if (best == null) return null;
        int w = best[2] - best[0] + 1, ht = best[3] - best[1] + 1;
        log("C1 gate footprint " + best[0] + "," + best[1] + " " + w + "x" + ht + " (" + (int) bd + " tiles from 3007,3360)");
        return w <= ht
                ? new Location[]{Location.create(best[0] - 3, best[1], 0), Location.create(best[2] + 3, best[1], 0)}   // a N-S gate: cross it west -> east
                : new Location[]{Location.create(best[0], best[1] - 3, 0), Location.create(best[0], best[3] + 3, 0)};  // an E-W gate: cross it south -> north
    }

    static void setup(Case c) throws Exception {
        if ("C1".equals(c.id)) {
            if (!nav) { c.skip = "C1 runs only with -Dnav=true"; return; }
            Location[] g = gatePair();
            if (g == null) { c.skip = "no multi-tile gate near Falador east in the grid (NavGrid.BIG_GATE " + NavGrid.BIG_GATE.size() + " tiles)"; return; }
            if (!stand(g[0]) || !stand(g[1])) { c.skip = "UNVERIFIED gate sides not standable: " + g[0] + " " + stand(g[0]) + ", " + g[1] + " " + stand(g[1]); return; }
            c.target = g[1];
            c.h = human(g[0]);
        } else {
            if (c.target != null && !stand(c.target)) { c.skip = "UNVERIFIED tile " + c.target + " not standable"; return; }
            if (c.start != null && !stand(c.start)) { c.skip = "UNVERIFIED start tile " + c.start + " not standable"; return; } // P16 item 4: A3's 3293,3172 is suspect
            c.h = human(c.start);
        }
        c.key = c.h.getUsername().toLowerCase(Locale.ROOT);
        Human h = c.h;
        switch (c.id) {
            case "A1": give(h, COINS, 50); level(h, Skills.THIEVING, 25); cmd(c, "pickpocket warriors at al kharid"); break; // spare PC: "warriors" alone = Task.AL_KHARID, which has no SPOTS entry
            case "A2": level(h, Skills.THIEVING, 25); cmd(c, "pickpocket warriors at al kharid"); break; // broke
            case "A4": give(h, COINS, (int) Math.max(200, CAP + 100)); give(h, AXE, 1); c.ends = new Location[]{Location.create(3222, 3218, 0), Location.create(3276, 3227, 0)}; cmd(c, "logs"); c.legs = 1; sendTo(c, AfkTasks.State.TO_SPOT, c.ends[1]); break; // 200 gp, shuttle Lumbridge <-> Al Kharid: 10 gp a crossing
            case "A3": level(h, Skills.MINING, 40); give(h, PICK, 1); give(h, COAL, 22); cmd(c, "coal"); break; // round 3: the runner banks only what it GAINED, so it must mine the pack full (5 coal at the Al Kharid mine) and then bank it
            case "B1": level(h, Skills.MINING, 55); give(h, COINS, 100); give(h, PICK, 1); cmd(c, "mithril at karamja mine"); break;
            case "B2": level(h, Skills.MINING, 55); give(h, PICK, 1); give(h, MITHRIL, 27); cmd(c, "mithril"); break;
            case "C1": give(h, AXE, 1); cmd(c, "logs"); sendTo(c, AfkTasks.State.TO_SPOT, c.target); break;
            case "D1": give(h, COINS, 50); give(h, WATERSKIN, 3); give(h, AXE, 1); cmd(c, "logs"); sendTo(c, AfkTasks.State.TO_SPOT, c.target); break;
            case "E1": give(h, COINS, 100); give(h, AXE, 1); cmd(c, "logs"); sendTo(c, AfkTasks.State.TO_SPOT, c.target); break;
            case "F1": give(h, AXE, 1); cmd(c, "tree at draynor"); break;
            case "F2": give(h, COINS, 50); give(h, AXE, 1); cmd(c, "logs"); sendTo(c, AfkTasks.State.TO_BANK, AK_BANK); break;
            case "G1": level(h, Skills.MINING, 50); level(h, Skills.SMITHING, 15); give(h, PICK, 1); cmd(c, "iron smelt"); break;
            case "G2": give(h, NET, 1); cmd(c, "shrimp cook sell"); break;
            // P16 item 5: T4 combos not covered above (-Dtest.b23combos=true)
            case "K1": level(h, Skills.MINING, 30); level(h, Skills.SMITHING, 20); give(h, PICK, 1); give(h, HAMMER, 1); c.want = new int[]{IRON_DAGGER}; cmd(c, "iron then smelt then smith dagger bank"); break;
            case "K2": h.getBank().add(new Item(SHRIMP_RAW, 100)); c.want = new int[]{SHRIMP_COOKED}; cmd(c, "cook bank"); break;
            case "K3": level(h, Skills.FLETCHING, 35); give(h, AXE, 1); give(h, KNIFE, 1); c.want = new int[]{ARROW_SHAFT, SHORTBOW_U, 54, 60}; cmd(c, "logs then fletch bank"); break; // round 3: at Fletching 35 it fletches oak (54) / willow (60) shortbows (u), not only normal ones
            case "K4": h.setAttribute("tutorial:complete", true); /* P23: else Skills.addExperience clamps xp at 175 and P20 light() reads every fire as "didn't light" (as TestB25AfkMagic) */ level(h, Skills.FIREMAKING, 30); give(h, AXE, 1); give(h, TINDERBOX, 1); c.skill = Skills.FIREMAKING; cmd(c, "logs then burn"); break;
            case "K5": level(h, Skills.CRAFTING, 10); give(h, SHEARS, 1); c.want = new int[]{BALL_OF_WOOL}; cmd(c, "c_wool then spin bank"); break;
            case "K6": level(h, Skills.CRAFTING, 10); c.want = new int[]{BOW_STRING}; cmd(c, "c_flax then spin bank"); break;
            case "K7": h.getBank().add(new Item(BONES, 50)); c.skill = Skills.PRAYER; cmd(c, "bury"); break;
            // T5 trials
            case "T1": give(h, PICK, 1); c.want = new int[]{COAL}; cmd(c, "coal"); break; // unlisted cluster (design 148: Dwarven Mine entrance, UNVERIFIED)
            case "T2": c.skill = Skills.HITPOINTS; cmd(c, "cows"); break; // HuntMap cows
            case "T3": cmd(c, "ore"); break; // no pickaxe: refusal with the shop's name
            default: c.skip = "unknown case";
        }
        if (c.r == null && !"B1".equals(c.id)) log(c.id + " no runner after the start (last message: " + h.lastMsg() + ")");
        c.coins0 = coins(h); // after the start: what the walk pays (tolls, the pass) or sells is the difference
    }

    /** One tick of a running case: tracking, then a verdict or null (carry on). */
    static String judge(Case c) {
        Human h = c.h; Location l = h.getLocation();
        if (c.prev != null && l.getDistance(c.prev) > 8) { c.tele = true; log(c.id + " moved " + (int) l.getDistance(c.prev) + " tiles in one tick: " + c.prev + " -> " + l); }
        c.prev = l;
        c.minX = Math.min(c.minX, l.getX());
        c.maxDist = Math.max(c.maxDist, l.getDistance(c.start));
        if (c.r != null && c.r.state == AfkTasks.State.BANKING) c.sawBank = true;
        if (!c.stopped && (c.r == null || c.r.halted || AfkTasks.RUNNING.get(c.key) != c.r)) { c.stopped = true; c.stopTick = c.ticks; }
        String msg = h.lastMsg();
        int paid = c.coins0 - coins(h);
        if (h.said("Stopped after an error")) return no(c, "runner error: " + msg);
        switch (c.id) {
            case "A1":
                if (l.getX() >= 3270) return paid <= 10 ? yes(c, "east of the fence, paid " + paid) : no(c, "east of the fence but paid " + paid);
                if (c.stopped) return no(c, "stopped: " + msg);
                break;
            case "A2":
                if (c.stopped) {
                    if (!h.said("toll")) return no(c, "stopped without a toll message: " + msg);
                    return nav || c.stopTick <= 100 ? yes(c, "toll message at " + c.stopTick) : no(c, "toll message late (" + c.stopTick + " > 100)");
                }
                if (l.getX() >= 3270) return nav && paid == 0 ? yes(c, "round the fence, paid 0") : no(c, "crossed with 0 gp, paid " + paid);
                if (!nav && c.ticks > 100) return no(c, "no toll message within 100 ticks");
                break;
            case "A3":
                if (paid > 0) return no(c, "paid " + paid + " gp on the bank walk (toll)"); // round 3: the nearest bank by straight line may be Varrock east (round the fence's north end, free) or Al Kharid's; never through the toll
                if (h.getBank().getAmount(COAL) > 0) return yes(c, "mined the pack full and banked " + h.getBank().getAmount(COAL) + " coal (min x " + c.minX + ", paid 0)");
                if (c.stopped) return no(c, "stopped: " + msg);
                break;
            case "A4": // pre-review 2.1: the spend cap (-Dafk.spendCap, default 100 gp) stops the run on the toll past it; the pack keeps the rest
                if (c.stopped) {
                    long tolls = paid / 10, capTolls = CAP / 10; // P16 item 4: cap 50 = 5 tolls, cap 100 = 10
                    if (!h.said("limit")) return no(c, "stopped without the spend-cap message: " + msg);
                    if (paid != capTolls * 10) return no(c, "paid " + paid + " gp (" + tolls + " tolls), expected " + (capTolls * 10) + " (" + capTolls + " tolls, cap " + CAP + ")");
                    return yes(c, "stopped after " + tolls + " tolls, " + paid + " gp paid, " + h.getInventory().getAmount(COINS) + " gp left: " + msg);
                }
                if (paid > CAP) return no(c, "paid " + paid + " gp, past the cap " + CAP);
                if (c.r != null && l.getDistance(c.ends[c.legs % 2]) <= 3) { c.legs++; sendTo(c, AfkTasks.State.TO_SPOT, c.ends[c.legs % 2]); } // arrived: walk back
                break;
            case "B1":
                if (c.stopped) return h.said("sea") && c.stopTick <= 10 && c.maxDist <= 5 ? yes(c, "refused at " + c.stopTick + ": " + msg)
                        : no(c, "stopped at " + c.stopTick + ", moved " + (int) c.maxDist + ": " + msg);
                if (c.ticks > 10) return no(c, "no refusal within 10 ticks");
                break;
            case "B2":
                if (c.stopped) return h.said("No bank close enough") ? yes(c, msg) : no(c, "stopped: " + msg);
                break;
            case "C1": case "D1":
                if (l.getDistance(c.target) <= ("C1".equals(c.id) ? 2 : 16)) return yes(c, "arrived " + l + ", paid " + paid);
                if (c.stopped) return no(c, "stopped at " + l + ": " + msg);
                break;
            case "E1":
                if (c.stopped) {
                    if (nav) return c.stopTick <= 10 ? yes(c, "refused at " + c.stopTick + ": " + msg) : no(c, "stopped late (" + c.stopTick + "): " + msg);
                    return h.said("stuck") || h.said("walk there") ? yes(c, "refused: " + msg) : no(c, "stopped without 'stuck': " + msg); // spare PC: goTo refuses islands in both modes
                }
                if (nav && c.ticks > 10) return no(c, "no refusal within 10 ticks");
                break;
            case "F1":
                if (c.tele) return no(c, "teleported");
                if (l.getDistance(DRAYNOR) <= 16) return yes(c, "walked out to Draynor");
                if (c.stopped) return h.said("stuck") ? yes(c, msg) : no(c, "stopped: " + msg);
                break;
            case "F2":
                if (c.sawBank || l.getDistance(AK_BANK) <= 4) return yes(c, "reached the Al Kharid bank, paid " + paid);
                if (c.stopped) return h.said("stuck") || h.said("loop") ? yes(c, msg) : no(c, "stopped: " + msg);
                break;
            case "G1":
                if (h.getBank().getAmount(IRON_BAR) > 0) return yes(c, "iron bars in bank " + h.getBank().getAmount(IRON_BAR));
                if (c.stopped) return no(c, "stopped: " + msg);
                break;
            case "G2":
                long sold = c.r == null ? 0 : c.r.soldCoins;
                if (sold > 0 || coins(h) > c.coins0 || h.getBank().getAmount(SHRIMP_COOKED) > 0)
                    return yes(c, "sold " + sold + ", coins " + c.coins0 + " -> " + coins(h) + ", cooked shrimp in bank " + h.getBank().getAmount(SHRIMP_COOKED));
                if (c.stopped) return no(c, "stopped: " + msg);
                break;
        }
        if (c.id.startsWith("K") || c.id.startsWith("T")) { String v = judgeCombo(c, msg); if (v != null) return v; }
        if (c.ticks >= c.budget) return no(c, "budget " + c.budget + " ticks used, at " + l + ", state " + (c.r == null ? "-" : c.r.state));
        return null;
    }
    /** P16 item 5: K1-K7 pass on a product in pack or bank (or the skill's XP rising); T1-T3 as named in setup. */
    static String judgeCombo(Case c, String msg) {
        Human h = c.h;
        if (c.ticks == 1 && c.skill >= 0) c.xp0 = h.getSkills().getExperience(c.skill);
        if ("T3".equals(c.id)) {
            if (h.said("nearest shop")) return yes(c, "refused with the shop's name: " + msg);
            if (h.said("you need")) return no(c, "refused without a shop name: " + msg);
            if (c.ticks > 5) return no(c, "no refusal within 5 ticks: " + msg);
            return null;
        }
        if (c.want != null) for (int id : c.want) {
            int n = h.getInventory().getAmount(id) + h.getBank().getAmount(id);
            if (n <= 0) continue;
            if ("T1".equals(c.id)) { // the spot must be a surveyed cluster, not one of the two static SPOTS rows
                Location sp = c.r == null ? null : c.r.spot;
                for (AfkTasks.Spot s : AfkTasks.SPOTS.get(AfkTasks.Task.COAL)) if (sp != null && sp.getDistance(s.at) <= 16) return no(c, "mined " + n + " coal at the listed spot " + s.name);
                return yes(c, "mined " + n + " coal at " + sp + " (not a listed spot)");
            }
            return yes(c, n + " of item " + id + " in pack/bank");
        }
        if ("K4".equals(c.id) && c.r != null && c.r.lightNote != null && !c.r.lightNote.equals(c.note)) { // P20: show silent light refusals
            c.note = c.r.lightNote; log("K4 light: " + c.note + " (tick " + c.ticks + ", player at " + h.getLocation() + ")");
        }
        if (c.skill >= 0 && c.ticks > 1 && h.getSkills().getExperience(c.skill) > c.xp0) {
            String lit = "K4".equals(c.id) && c.r != null ? ", lit at " + (c.r.litAt != null ? c.r.litAt : c.r.lightAt) : ""; // P20: the tile the fire took on
            return yes(c, Skills.SKILL_NAME[c.skill] + " xp " + (int) c.xp0 + " -> " + (int) h.getSkills().getExperience(c.skill) + (c.r == null ? "" : ", spot " + c.r.spot) + lit);
        }
        if (c.stopped) return no(c, "stopped: " + msg);
        return null;
    }

    static String yes(Case c, String why) { c.why = why; return "PASS"; }
    static String no(Case c, String why) { c.why = why; return "FAIL"; }

    static String last(Case c) { return c.r == null ? "-" : c.r.last; }
    static void result(Case c, String v) {
        c.verdict = v; c.done = true;
        log("B23 " + c.id + " " + v + " ticks=" + c.ticks + " last=" + last(c) + " | " + c.why);
        if (c.r != null && !c.r.halted && AfkTasks.RUNNING.get(c.key) == c.r) AfkTasks.handle(c.h, new String[]{"afk", "stop"}); // free the world for the others
    }

    static int failureFiles() {
        try { return count(Failures.failDir()); } catch (Throwable t) { return -1; }
    }
    static int count(File d) {
        File[] fs = d == null ? null : d.listFiles(); int n = 0;
        if (fs != null) for (File f : fs) n += f.isDirectory() ? count(f) : 1;
        return n;
    }

    @Override public void startup() {
        boolean combos = Boolean.getBoolean("test.b23combos"); // P16 item 5: the T4 combos and T5 trials, instead of A-G
        if (!Boolean.getBoolean("test.b23afk") && !combos) return;
        if (combos) {
            Location lum = Location.create(3222, 3218, 0), draynorBank = Location.create(3092, 3243, 0);
            CASES.put("K1", new Case("K1", 3000, Location.create(3287, 3366, 0)));  // Varrock SE mine (G1's start)
            CASES.put("K2", new Case("K2", 1500, draynorBank));
            CASES.put("K3", new Case("K3", 3000, lum)); // round 3: 1500 ran out while still chopping/fletching (time, not logic)
            CASES.put("K4", new Case("K4", 4000, lum)); // round 3: 2500 ran out on the first burn (chopping a full pack of willow takes ~2,300 ticks)
            CASES.put("K5", new Case("K5", 2500, AfkTasks.SPOTS.get(AfkTasks.Task.WOOL)[0].at));
            CASES.put("K6", new Case("K6", 2500, AfkTasks.SPOTS.get(AfkTasks.Task.FLAX)[0].at));
            CASES.put("K7", new Case("K7", 1000, draynorBank));
            CASES.put("T1", new Case("T1", 1500, Location.create(3300, 3300, 0))); // UNVERIFIED (SKIP if not standable)
            CASES.put("T2", new Case("T2", 1500, lum));
            CASES.put("T3", new Case("T3", 50, lum));
        } else {
        CASES.put("A1", new Case("A1", 400, Location.create(3222, 3218, 0)));
        CASES.put("A2", new Case("A2", 400, Location.create(3222, 3218, 0)));
        CASES.put("A3", new Case("A3", 1200, Location.create(3300, 3300, 0))); // round 3: the Al Kharid mine (T1 shows the tile is standable and coal is surveyed there)
        CASES.put("A4", new Case("A4", 1500, Location.create(3222, 3218, 0)));      // spend cap: 200 gp, shuttle, stops after the 10th toll (-Dafk.spendCap default 100)
        CASES.put("B1", new Case("B1", 400, Location.create(3027, 3218, 0)));
        CASES.put("B2", new Case("B2", 400, Location.create(2846, 3034, 0)));
        CASES.put("C1", new Case("C1", 400, null));                                // start/target picked from the grid's gates (UNVERIFIED)
        Case d1 = new Case("D1", 400, Location.create(3304, 3122, 0)); d1.target = Location.create(3200, 3000, 0); CASES.put("D1", d1); // UNVERIFIED desert tile
        Case e1 = new Case("E1", 400, Location.create(3027, 3218, 0)); e1.target = Location.create(2834, 3335, 0); CASES.put("E1", e1); // UNVERIFIED Entrana tile
        CASES.put("F1", new Case("F1", 600, Location.create(3162, 3488, 0)));      // the Exchange counter ring (NavWalker L65 trap cell)
        CASES.put("F2", new Case("F2", 400, Location.create(3263, 3230, 0)));      // the batch 21 Lumbridge east loop start
        CASES.put("G1", new Case("G1", 2000, Location.create(3287, 3366, 0)));
        CASES.put("G2", new Case("G2", 2000, Location.create(3087, 3229, 0)));
        }
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, go = -1, fail0; boolean requested;
            @Override public boolean pulse() {
                t++;
                try {
                    if (go < 0) {
                        if (t < 150 || NavGrid.building) { if (t > 3000) { log("setup: a nav build still running at tick 3000"); return finish(); } return false; }
                        nav = NavWalker.ON;
                        if (nav && !NavPlanner.built && t < 2400) { // planner on wants a grid: TestB22NavFile's request file, once
                            if (!requested && !NavGrid.ready()) { requested = true; try { File f = NavWalker.survey("navbuild.request"); f.getParentFile().mkdirs(); java.nio.file.Files.write(f.toPath(), "b23afk\n".getBytes()); log("no nav grid: navbuild.request written at tick " + t); } catch (Throwable e) { log("could not write navbuild.request: " + e); } }
                            return false;
                        }
                        go = t; fail0 = failureFiles();
                        log("start at tick " + t + ": mode " + (nav ? "planner-on" : "planner-off") + ", planner built " + NavPlanner.built + ", grid " + NavGrid.ready() + ", failure files " + fail0);
                        for (Case c : CASES.values()) {
                            if (!wanted(c.id)) { c.done = true; c.verdict = null; continue; }
                            try { setup(c); } catch (Throwable e) { c.skip = null; c.why = "setup error " + e; e.printStackTrace(System.out); c.ticks = 0; result(c, "FAIL"); continue; }
                            if (c.skip != null) { c.why = c.skip; result(c, "SKIP"); }
                        }
                        return false;
                    }
                    if (Watchdog.FAIL_ON && "AfkTasks.Runner".equals(Watchdog.pulseTopSrc)) runnerMaxNs = Math.max(runnerMaxNs, Watchdog.pulseTopNs);
                    boolean open = false;
                    for (Case c : CASES.values()) {
                        if (c.done) continue;
                        c.ticks++;
                        String v = judge(c);
                        if (v != null) { result(c, v); continue; }
                        open = true;
                        if (c.ticks % 50 == 0) log(c.id + " t=" + c.ticks + " state=" + (c.r == null ? "-" : c.r.state) + " last=" + last(c) + " at=" + c.h.getLocation().getX() + "," + c.h.getLocation().getY() + "," + c.h.getLocation().getZ() + " coins=" + coins(c.h));
                    }
                    if (open) return false;
                    int pass = 0, fail = 0, skip = 0;
                    for (Case c : CASES.values()) { if ("PASS".equals(c.verdict)) pass++; else if ("FAIL".equals(c.verdict)) fail++; else if ("SKIP".equals(c.verdict)) skip++; }
                    double ms = runnerMaxNs / 1e6;
                    if (!Watchdog.FAIL_ON) { log("B23 H1 SKIP ticks=" + (t - go) + " last=- | failures off: no pulse costs"); skip++; }
                    else if (ms < 200) { log("B23 H1 PASS ticks=" + (t - go) + " last=- | runner pulse max " + String.format(Locale.ROOT, "%.1f", ms) + " ms (< 200)"); pass++; }
                    else { log("B23 H1 FAIL ticks=" + (t - go) + " last=- | runner pulse max " + String.format(Locale.ROOT, "%.1f", ms) + " ms (>= 200)"); fail++; }
                    int fail1 = failureFiles();
                    log("B23 TOTAL mode=" + (nav ? "planner-on" : "planner-off") + " pass=" + pass + " fail=" + fail + " skip=" + skip
                            + " failureFiles=" + fail0 + "->" + fail1 + (fail1 != fail0 ? " (CHANGED)" : "") + " plannerBuilt=" + NavPlanner.built);
                    return finish();
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); return finish(); }
            }
            boolean finish() { log("DONE"); return true; }
        });
    }
}
