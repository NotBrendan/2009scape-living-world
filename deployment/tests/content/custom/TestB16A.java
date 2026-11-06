package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b16a=true): batch 16 item1, surplus logs burned on a break. Two bots on a break with a stand-in
 * human (artificial=false) 3 tiles away: A at the Grand Exchange, B at the Lumbridge castle courtyard (not near the
 * Exchange). Neither has a log in its bank; both have a tinderbox and 20k coins, Firemaking 35. The test marks every
 * fire log that has real bot stock (>= 10) as surplus (MarketBrain.surplus, the flag the hourly round sets at 3x target),
 * then drives think() every 7 ticks for 200 s, re-arming the extras whenever one ends. Expect: A buys 2-4 logs through
 * Ledger.consume (coins down by the guide value, stock down), lights them one after another (Firemaking XP), and banks
 * any it didn't light; B never gets one (not at the Exchange). Then the server-wide hour cap: surplusBurned = 40 ->
 * surplusLog() returns 0.
 */
public final class TestB16A implements StartupListener {
    static void log(String s) { System.out.println("[TestB16A] " + s); }
    static Object sget(String n) { try { java.lang.reflect.Field f = SmartAdventurers.Brain.class.getDeclaredField(n); f.setAccessible(true); return f.get(null); } catch (Throwable t) { return "n/a"; } }
    static void sset(String n, Object v) { try { java.lang.reflect.Field f = SmartAdventurers.Brain.class.getDeclaredField(n); f.setAccessible(true); f.set(null, v); } catch (Throwable ignored) { } }
    static Object get(Object o, String n) { try { java.lang.reflect.Field f = SmartAdventurers.Brain.class.getDeclaredField(n); f.setAccessible(true); return f.get(o); } catch (Throwable t) { return "n/a"; } }
    static void set(Object o, String n, Object v) { try { java.lang.reflect.Field f = SmartAdventurers.Brain.class.getDeclaredField(n); f.setAccessible(true); f.set(o, v); } catch (Throwable ignored) { } }
    static Object call(Object o, String n, long now) { try { java.lang.reflect.Method m = SmartAdventurers.Brain.class.getDeclaredMethod(n, long.class); m.setAccessible(true); return m.invoke(o, now); } catch (NoSuchMethodException e) { return "n/a"; } catch (Throwable t) { return "error " + t; } }
    static final int[] LOGS = {1515, 1517, 1519, 1521, 1511};

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b16a")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer[] bot = new AIPlayer[2]; SmartAdventurers.Brain[] br = new SmartAdventurers.Brain[2]; Location[] spot = {Location.create(3165, 3480, 0), Location.create(3222, 3219, 0)};
            java.util.Set<Integer> mark = new java.util.HashSet<>(); double[] xp0 = new double[2]; long[] coins0 = new long[2]; int[] stock0 = new int[LOGS.length];
            String logs(AIPlayer p, boolean bank) { StringBuilder s = new StringBuilder(); for (int id : LOGS) { int n = bank ? p.getBank().getAmount(id) : p.getInventory().getAmount(id); if (n > 0) s.append(MarketBrain.name(id)).append(' ').append(n).append(' '); } return s.length() == 0 ? "none" : s.toString().trim(); }
            String state(int i) { return bot[i].getUsername() + " at " + bot[i].getLocation() + ": FM xp +" + (int) (bot[i].getSkills().getExperience(Skills.FIREMAKING) - xp0[i]) + ", coins " + coins0[i] + " -> " + Ledger.Wallet.coins(bot[i]) + ", bank logs " + logs(bot[i], true) + ", pack logs " + logs(bot[i], false) + ", extraKind " + get(br[i], "extraKind"); }
            String stock() { StringBuilder s = new StringBuilder(); for (int k = 0; k < LOGS.length; k++) s.append(MarketBrain.name(LOGS[k])).append(' ').append(stock0[k]).append("->").append(MarketBrain.stockOf(LOGS[k])).append(" (live ").append(live(LOGS[k])).append(") "); return s.toString().trim(); }
            int live(int id) { try { return core.game.ge.GrandExchange.Companion.getBotstockForId(id); } catch (Throwable e) { return -1; } }
            @Override public boolean pulse() {
                t++;
                try {
                    long now = System.currentTimeMillis();
                    if (t == 150) {
                        java.util.Set<Integer> sur = new java.util.HashSet<>(MarketBrain.surplus);
                        java.util.Map<Integer, Integer> st = new java.util.HashMap<>(MarketBrain.stock);
                        for (int k = 0; k < LOGS.length; k++) {
                            int id = LOGS[k], live = live(id);
                            log("log " + MarketBrain.name(id) + ": stock " + MarketBrain.stockOf(id) + " (live " + live + "), target " + MarketBrain.target(id) + ", surplus " + MarketBrain.isSurplus(id) + ", value " + MarketBrain.value(id));
                            if (live >= 10) { sur.add(id); st.put(id, live); mark.add(id); }
                        }
                        MarketBrain.surplus = sur; MarketBrain.stock = st;
                        for (int k = 0; k < LOGS.length; k++) stock0[k] = MarketBrain.stockOf(LOGS[k]);
                        StringBuilder ss = new StringBuilder(); for (int id : LOGS) if (MarketBrain.isSurplus(id)) ss.append(MarketBrain.name(id)).append(' ');
                        log("marked surplus (test): " + ss.toString().trim());
                        for (int i = 0; i < 2; i++) {
                            bot[i] = TestCR1.bot(spot[i], 40, 20, 20, 20);
                            br[i] = TestCR1.rig(bot[i], false);
                            bot[i].getSkills().setStaticLevel(Skills.FIREMAKING, 35); bot[i].getSkills().setLevel(Skills.FIREMAKING, 35);
                            bot[i].getInventory().clear(); for (Item it : bot[i].getBank().toArray()) if (it != null) bot[i].getBank().remove(it);
                            bot[i].getBank().add(new Item(590, 1)); bot[i].getBank().add(new Item(995, 20_000));
                            xp0[i] = bot[i].getSkills().getExperience(Skills.FIREMAKING); coins0[i] = Ledger.Wallet.coins(bot[i]);
                            TestA1.human(spot[i].transform(3, 0, 0), 900300 + i);
                            log("bot " + i + " " + bot[i].getUsername() + " -> " + spot[i]);
                        }
                    }
                    if (t == 156) for (int i = 0; i < 2; i++) { br[i].idleUntil = now + 400_000; br[i].loiterAt = spot[i]; br[i].loiterName = i == 0 ? "the Grand Exchange" : "the Lumbridge castle courtyard"; br[i].nextThink = 0; log("watcher of " + bot[i].getUsername() + ": " + Spectacle.watcher(bot[i])); }
                    if (t > 156 && t % 20 == 0 && !MarketBrain.surplus.containsAll(mark)) { java.util.Set<Integer> sur = new java.util.HashSet<>(MarketBrain.surplus); sur.addAll(mark); MarketBrain.surplus = sur; log("re-marked surplus after a MarketBrain refresh"); } // the 5-minute refresh recomputes it
                    if (t > 156 && t < 490 && t % 7 == 0) for (int i = 0; i < 2; i++) {
                        if (Integer.valueOf(0).equals(get(br[i], "extraKind"))) { set(br[i], "nextExtra", 0L); set(br[i], "extrasThisBreak", 0); set(br[i], "extraRollBreak", br[i].idleUntil); set(br[i], "extraRollOk", true); } // item4's one roll a break: forced on here
                        br[i].nextThink = 0; br[i].think();
                    }
                    if (t == 490) {
                        log("RESULT 1 surplus logs bought for burning: " + sget("surplusBought") + ", this hour " + sget("surplusBurned") + ", break extras started " + sget("loiterExtras"));
                        for (int i = 0; i < 2; i++) log("RESULT 1 " + (i == 0 ? "A (GE) " : "B (Lumbridge) ") + state(i));
                        log("RESULT 1 stock " + stock());
                        Object hour = sget("surplusHour");
                        sset("surplusBurned", 40);
                        Object capped = call(br[0], "surplusLog", now);
                        sset("surplusBurned", 0);
                        Object open = call(br[0], "surplusLog", now);
                        log("RESULT 2 hour cap: with 40 burned this hour surplusLog -> " + capped + "; with 0 -> " + open + " (hour started " + hour + ")");
                        log("DONE");
                        return true;
                    }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
