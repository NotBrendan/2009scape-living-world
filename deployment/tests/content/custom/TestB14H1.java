package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.List;

/**
 * Trial only (-Dtest.b14h1=true): rec-review 10-23 batch14 item1, armour upgrades in armourNeeds. Four melee bots near
 * the Lumbridge shops, each given one simulated armour-shop visit (armourNeeds -> armourShop -> finishShop, as planShopTrip
 * and shopTick do):
 *  A bronze worn, Att/Def 20, 20k coins -> one tier step (before: nothing). Then the cheap old pieces: sold at the general
 *    store (the sell walk is skipped by a teleport; finishShop there), not banked.
 *  C iron worn with a steel scimitar, Att/Def 20 -> steel body/legs at Horvik's; the old iron pieces (100 gp or more) go on the Exchange (Ledger lots).
 *  D rune worn (high tier, Att/Def 45, 200k) -> untouched.
 *  E bronze worn, Att/Def 1 -> iron (iron needs level 1 in ARMOUR_TIERS, as in 2009).
 * Also: a second armourNeeds in the same trip (memo: nothing), and the next trip (the next step).
 */
public final class TestB14H1 implements StartupListener {
    static void log(String s) { System.out.println("[TestB14H1] " + s); }
    static final int[] BRONZE = {1155, 1117, 1075, 1189, 1321}, IRON = {1153, 1115, 1067, 1191, 1323}, RUNE = {1163, 1127, 1079, 1201, 1333};
    static final int[] SLOTS = {0, 4, 7, 5, 3};

    static AIPlayer make(int[] set, int lvl, long coins, boolean high) {
        AIPlayer b = TestCR1.bot(Location.create(3212 + (int) (Math.random() * 6), 3245 + (int) (Math.random() * 4), 0), 40, lvl, lvl, lvl);
        b.getInventory().clear(); for (Item it : b.getBank().toArray()) if (it != null) b.getBank().remove(it);
        for (int s = 0; s < 14; s++) { Item w = b.getEquipment().get(s); if (w != null) b.getEquipment().remove(w); }
        for (int id : set) { b.getInventory().add(new Item(id)); SmartAdventurers.wear(b, id); }
        Ledger.Wallet.give(b, coins);
        return b;
    }
    static String worn(AIPlayer b) { StringBuilder s = new StringBuilder(); for (int sl : SLOTS) { Item w = b.getEquipment().get(sl); s.append(w == null ? "-" : w.getName()).append(sl == 3 ? "" : ", "); } return s.toString(); }
    static String ids(List<int[]> l) { StringBuilder s = new StringBuilder(); for (int[] x : l) s.append(MarketBrain.name(x[0])).append(" (").append(MarketBrain.value(x[0])).append(" gp), "); return l.isEmpty() ? "nothing" : s.substring(0, s.length() - 2); }
    static String held(AIPlayer b, int[] set) { StringBuilder s = new StringBuilder(); for (int id : set) { int p = b.getInventory().getAmount(id), k = b.getBank().getAmount(id), x = Ledger.waiting(b.getUsername(), id); if (p + k + x > 0) s.append(MarketBrain.name(id)).append(" pack ").append(p).append(" bank ").append(k).append(" exchange ").append(x).append("; "); } return s.length() == 0 ? "none" : s.toString(); }
    static Object field(Object o, String n) { try { java.lang.reflect.Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o); } catch (Throwable t) { return "(no field " + n + ")"; } }

    /** One armour-shop visit as planShopTrip + finishShop make it. */
    static void visit(String tag, SmartAdventurers.Brain br) {
        AIPlayer b = (AIPlayer) br.bot;
        long before = Ledger.Wallet.coins(b);
        List<int[]> need = br.armourNeeds();
        log(tag + " " + b.getUsername() + " Att/Def " + b.getSkills().getStaticLevel(Skills.ATTACK) + "/" + b.getSkills().getStaticLevel(Skills.DEFENCE) + ", coins " + before + ", worn [" + worn(b) + "] -> armourNeeds: " + ids(need));
        if (need.isEmpty()) { log("RESULT " + tag + " no shop visit (nothing to buy); worn [" + worn(b) + "]"); return; }
        Object[] shop = br.armourShop(need);
        if (shop == null) { log("RESULT " + tag + " no shop sells these"); return; }
        java.util.Set<Integer> sells = new java.util.HashSet<>(); for (int sid : (int[]) shop[3]) sells.add(sid);
        need.removeIf(pc -> !sells.contains(pc[0]));
        br.shopAt = b.getLocation(); br.shopName = (String) shop[0]; br.shopBuy.clear(); br.shopBuy.addAll(need); br.pendingJob = null; br.armourTrip = true;
        br.finishShop();
        log("RESULT " + tag + " visit to " + shop[0] + ": spent " + (before - Ledger.Wallet.coins(b)) + " gp (cap 0.4 x " + (before - 300) + " = " + (long) ((before - 300) * 0.4) + "), worn now [" + worn(b) + "]");
        log("RESULT " + tag + " old pieces: bronze " + held(b, BRONZE) + " | iron " + held(b, IRON) + " | oldToSell " + listOf(field(br, "oldToSell")) + " | next stop " + (br.shopAt == null ? "none" : br.shopName + " at " + br.shopAt));
        log("RESULT " + tag + " same trip, armourNeeds again: " + ids(br.armourNeeds()));
    }
    static String listOf(Object o) { if (!(o instanceof List)) return String.valueOf(o); StringBuilder s = new StringBuilder("["); for (Object x : (List<?>) o) s.append(MarketBrain.name((Integer) x)).append(", "); return s.length() > 1 ? s.substring(0, s.length() - 2) + "]" : "[]"; }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b14h1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer a, c, d, e; SmartAdventurers.Brain ba, bc, bd, be; long coinsAtStore;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) {
                        a = make(BRONZE, 20, 20_000, false); ba = TestCR1.rig(a, false);
                        c = make(new int[]{1153, 1115, 1067, 1191, 1325}, 20, 20_000, false); bc = TestCR1.rig(c, false); // iron, steel scimitar
                        d = make(RUNE, 45, 200_000, true); bd = TestCR1.rig(d, true);
                        e = make(BRONZE, 1, 20_000, false); be = TestCR1.rig(e, false);
                        StringBuilder v = new StringBuilder(); for (int[] set : new int[][]{BRONZE, IRON}) for (int id : set) v.append(MarketBrain.name(id)).append(' ').append(MarketBrain.value(id)).append(" tradeable ").append(MarketBrain.tradeable(id)).append("; ");
                        log("values: " + v);
                    }
                    if (t == 160) { visit("A", ba); visit("C", bc); visit("D", bd); visit("E", be); }
                    if (t == 165) {
                        if (ba.shopAt != null && SmartAdventurers.Brain.class.getDeclaredMethods().length > 0 && ba.shopName != null && ba.shopName.toLowerCase().contains("general store")) {
                            a.getProperties().setTeleportLocation(ba.shopAt); log("A walks (teleported for the trial) to " + ba.shopName);
                        } else log("A has no general store stop (bronze " + held(a, BRONZE) + ")");
                    }
                    if (t == 172) {
                        if (ba.shopAt != null) { coinsAtStore = Ledger.Wallet.coins(a); ba.finishShop(); log("RESULT B at " + a.getLocation() + ": coins +" + (Ledger.Wallet.coins(a) - coinsAtStore) + ", bronze left " + held(a, BRONZE) + ", oldToSell " + listOf(field(ba, "oldToSell")) + ", next stop " + (ba.shopAt == null ? "none" : ba.shopName)); }
                        else log("RESULT B no sale: bronze " + held(a, BRONZE));
                    }
                    if (t == 175) { ba.trips++; bc.trips++; bd.trips++; log("next trip:"); visit("A2", ba); visit("C2", bc); visit("D2", bd); }
                    if (t == 180) { log("DONE"); return true; }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
