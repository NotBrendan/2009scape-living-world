package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.ge.GrandExchange;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trial only (-Dtest.b15c4=true): batch 15 items 3 and 4.
 * A: bots at the Grand Exchange (a fighter and a ranger) set out (Brain.start) on a level-64 poisonous hunt and a
 *    level-55 hunt; which potions did they buy from bot stock and pack? (Thin piles are topped up by the test first.)
 * B: a runner in a fight carrying attack(3) and strength(3) potions: does it drink when the boost is gone?
 * C: the herbalist's planner (Artisans.choose) on a stock of herblore inputs (herbs, unfinished potions, secondaries) and no finished potions, before and after a fighter
 *    holding a strength potion found no attack potion on the Exchange: how often does it pick the attack potion?
 */
public final class TestB15C4 implements StartupListener {
    static void log(String s) { System.out.println("[TestB15C4] " + s); }
    static final int[] IDS = {175, 181, 121, 115, 145, 157, 9741, 169};
    static final Location GE = Location.create(3165, 3487, 0);
    static final int[][] POTS = {{2434, 139, 141, 143}, {2446, 175, 177, 179}, {2448, 181, 183, 185}, {2428, 121, 123, 125}, {113, 115, 117, 119},
            {2436, 145, 147, 149}, {2440, 157, 159, 161}, {9739, 9741, 9743, 9745}, {2444, 169, 171, 173}}; // a copy: the before build has no table
    static boolean pot(int id) { for (int[] f : POTS) for (int d : f) if (d == id) return true; return false; }

    static String pots(AIPlayer b) {
        StringBuilder s = new StringBuilder();
        for (Item it : b.getInventory().toArray()) if (it != null && pot(it.getId())) s.append(s.length() > 0 ? ", " : "").append(it.getName());
        String bank = ""; for (int[] f : POTS) for (int id : f) if (b.getBank().getAmount(id) > 0) bank += " " + b.getBank().getAmount(id) + "x" + id;
        return "pack [" + (s.length() == 0 ? "none" : s) + "] bank [" + bank.trim() + "]";
    }
    static SmartAdventurers.Job hunt(String name, int lvl) {
        HuntMap.Hunt h = new HuntMap.Hunt(); h.name = name; h.npcLevel = lvl; h.names = new String[]{name}; h.spot = GE.transform(3, 3, 0); h.products = new int[0]; h.chance = new double[0];
        SmartAdventurers.Job j = new SmartAdventurers.Job(AfkTasks.Task.AUTO, -1, 70); j.hunt = h; return j;
    }
    static String top;
    static int choosePct(int... ids) {
        int hits = 0; Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < 300; i++) { Artisans.Plan p = Artisans.choose(Artisans.Trade.HERBLORE, 99); if (p != null) { seen.merge(p.r.name, 1, Integer::sum); for (int id : ids) if (p.r.product == id) hits++; } }
        top = seen.toString();
        return hits;
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b15c4")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; List<AIPlayer> melee = new ArrayList<>(), ranged = new ArrayList<>(); AfkTasks.Runner rb; AIPlayer bb; StringBuilder seeded = new StringBuilder();
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 210) {
                        for (int i = 0; i < 30 && (melee.size() < 2 || ranged.isEmpty()); i++) {
                            AIPlayer b = TestCR1.bot(GE, 70, 70, 70, 70);
                            b.getSkills().setStaticLevel(Skills.RANGE, 70); b.getSkills().setLevel(Skills.RANGE, 70);
                            SmartAdventurers.FAV.put(b.getUsername().toLowerCase(java.util.Locale.ROOT), -1); // a fighter (styleFor gives non-fighters melee)
                            int st = SmartAdventurers.styleOf(b);
                            if (st == SmartAdventurers.MELEE && melee.size() < 2) melee.add(b); else if (st == SmartAdventurers.RANGED && ranged.isEmpty()) ranged.add(b);
                            b.getBank().add(new Item(995, 500_000));
                        }
                        for (int id : IDS) { int have = GrandExchange.Companion.getBotstockForId(id); if (have < 3) { Ledger.supply(null, id, 4); seeded.append(' ').append(id).append(':').append(have).append("+4"); } }
                        log("bots: " + melee.size() + " fighters, " + ranged.size() + " rangers; test topped up stock" + (seeded.length() == 0 ? " nothing" : seeded));
                    }
                    if (t == 250) {
                        Map<Integer, Integer> m = new HashMap<>(MarketBrain.stock);
                        for (int id : IDS) m.put(id, GrandExchange.Companion.getBotstockForId(id));
                        MarketBrain.stock = m;
                        StringBuilder s = new StringBuilder(); for (int id : IDS) s.append(' ').append(id).append('=').append(MarketBrain.stockOf(id)); log("stock" + s);
                        if (!melee.isEmpty()) {
                            AIPlayer b = melee.get(0); SmartAdventurers.Brain br = TestCR1.rig(b, true);
                            br.start(hunt("Poison spider", 64));
                            log("RESULT A fighter, poison spider (64, poisonous): " + pots(b));
                            if (br.runner != null) br.runner.halt();
                        }
                        if (!ranged.isEmpty()) {
                            AIPlayer b = ranged.get(0); SmartAdventurers.Brain br = TestCR1.rig(b, true);
                            br.start(hunt("Ogre", 55));
                            log("RESULT A ranger, ogre (55): " + pots(b));
                            if (br.runner != null) br.runner.halt();
                        }
                    }
                    if (t == 270) {
                        bb = TestCR1.bot(Location.create(3242, 3238, 0), 70, 70, 70, 70);
                        bb.getInventory().add(new Item(121, 1)); bb.getInventory().add(new Item(115, 1));
                        rb = new AfkTasks.Runner(bb, AfkTasks.Task.COW, true, 0, false, false);
                    }
                    if (t == 272 || t == 276 || t == 280) {
                        if (t == 280) { bb.getSkills().setLevel(Skills.ATTACK, 70); }
                        bb.setAttribute("combat-time", System.currentTimeMillis() + 10000);
                        rb.fight();
                        log("RESULT B fight call at " + (t - 270) + ": attack " + bb.getSkills().getLevel(Skills.ATTACK) + "/70, strength " + bb.getSkills().getLevel(Skills.STRENGTH) + "/70, " + pots(bb));
                    }
                    if (t == 282) {
                        rb.halt();
                        Map<Integer, Integer> saved = MarketBrain.stock, m = new HashMap<>();
                        for (Artisans.Recipe r : Artisans.RECIPES) if ("HERBLORE".equals(r.trade)) m.put(r.product, 0);
                        for (Artisans.Recipe r : Artisans.RECIPES) if ("HERBLORE".equals(r.trade) && r.inIds != null) { for (int in : r.inIds) m.put(in, 1000); }
                        MarketBrain.stock = m;
                        int before = choosePct(121); String topBefore = top;
                        String asked = "no fighter";
                        if (melee.size() > 1) { melee.get(1).getInventory().add(new Item(115, 1)); SmartAdventurers.Brain br = TestCR1.rig(melee.get(1), true); /* it has a strength potion: only attack is missing */ asked = "exchangeHasWhatINeed=" + br.exchangeHasWhatINeed(hunt("Ogre", 55)); }
                        int after = choosePct(121);
                        MarketBrain.stock = saved;
                        log("RESULT C herbalist picks attack potion in 300 plans: " + before + " before the fighter looked, " + after + " after (" + asked + "); plans before " + topBefore + ", after " + top);
                        log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
