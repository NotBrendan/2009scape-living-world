package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b17a3=true; needs TestCR1.java; run with -Dwatchdog.min=3 so a stalled walk is rescued inside the
 * test): batch 17 item3, the sea crossing on a smart bot's shop walk. Three bots enlisted at Draynor (3092,3245), each
 * given a shop walk (the brain's own shopTick, an 8 minute deadline): A with 150 gp to the Shrimp and Parrot (Brimhaven),
 * B broke (0 gp) to the same shop, C (control) with 150 gp to Wydin's (Port Sarim, mainland). Logged every 100 ticks;
 * each RESULT when its shop walk ends (shopAt cleared: arrived if within 9 tiles, else given up) or at 900 ticks.
 * Before: A stalls by the Port Sarim shore and is rescued or gives up; after: A takes the boat and arrives; B gives up
 * at once ("never reached ... buying elsewhere"); C as before.
 */
public final class TestB17A3 implements StartupListener {
    static void log(String s) { System.out.println("[TestB17A3] " + s); }
    static final Location FROM = Location.create(3092, 3245, 0);
    static Object[] shop(String part) { for (Object[] s : BotChat.WorldData.shops) if (String.valueOf(s[0]).toLowerCase().contains(part)) return s; return null; }

    static String sea(SmartAdventurers.Brain br) { // the batch 17 field, read by name so the same test builds before and after
        try { Object r = SmartAdventurers.Brain.class.getDeclaredField("sea").get(br); if (r == null) return ""; AfkTasks.Runner rr = (AfkTasks.Runner) r; return ", crossing " + AfkTasks.Runner.class.getDeclaredField("crossing").get(rr) + " '" + rr.last + "'"; } catch (NoSuchFieldException e) { return ""; } catch (Throwable t) { return " " + t; }
    }
    static final class Leg {
        final String name; final Object[] shop; final long coins; AIPlayer bot; SmartAdventurers.Brain br; Location to; boolean done, karamjaSeen; int startTick, jumps; double best = Double.MAX_VALUE; Location prev; String jumpLog = "";
        Leg(String name, Object[] shop, long coins) { this.name = name; this.shop = shop; this.coins = coins; }
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b17a3")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; Leg[] legs; int rescued0;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 150) {
                        Object[] sp = shop("shrimp and parrot"), wy = shop("wydin");
                        log("shops: " + (sp == null ? "no Shrimp and Parrot" : sp[0] + " at " + sp[1] + "," + sp[2]) + "; " + (wy == null ? "no Wydin's" : wy[0] + " at " + wy[1] + "," + wy[2]) + "; teleport price " + Math.max(100, MarketBrain.value(563) + 3L * MarketBrain.value(556)) + " gp (stall teleport needs 3x)");
                        legs = new Leg[]{new Leg("A", sp, 150), new Leg("B", sp, 0), new Leg("C", wy, 150)};
                        for (Leg l : legs) {
                            l.bot = TestCR1.bot(FROM, 40, 20, 20, 20);
                            l.bot.getInventory().remove(new Item(995, l.bot.getInventory().getAmount(995))); l.bot.getBank().remove(new Item(995, l.bot.getBank().getAmount(995)));
                            if (l.coins > 0) l.bot.getInventory().add(new Item(995, (int) l.coins));
                        }
                        rescued0 = Watchdog.rescued;
                    }
                    if (t == 156) for (Leg l : legs) {
                        SmartAdventurers.enlist(l.bot, false, false, -1, 0, 0, "test bot (item3 leg " + l.name + ")");
                        l.br = SmartAdventurers.BRAINS.get(l.bot.getUsername().toLowerCase(java.util.Locale.ROOT));
                        l.br.nextThink = Long.MAX_VALUE;
                        l.bot.getInventory().remove(new Item(995, l.bot.getInventory().getAmount(995))); l.bot.getBank().remove(new Item(995, l.bot.getBank().getAmount(995)));
                        if (l.coins > 0) l.bot.getInventory().add(new Item(995, (int) l.coins)); // after enlist (it starts the bot with an empty pack)
                    }
                    if (t == 160) for (Leg l : legs) {
                        SmartAdventurers.Brain br = l.br; int[] stock = (int[]) l.shop[3];
                        br.shopAt = l.to = Location.create((Integer) l.shop[1], (Integer) l.shop[2], 0); br.shopName = (String) l.shop[0];
                        br.shopBuy.clear(); br.shopBuy.add(new int[]{stock.length > 0 ? stock[0] : 1925, 1});
                        br.pendingJob = null; br.shopDeadline = System.currentTimeMillis() + 480_000; br.shopBest = Double.MAX_VALUE; br.shopStalls = 0;
                        br.nextThink = 0; l.startTick = t;
                        log("leg " + l.name + ": " + l.bot.getUsername() + " at " + l.bot.getLocation() + " with " + Ledger.Wallet.coins(l.bot) + " gp walking to " + br.shopName + " " + l.to + " (" + Math.round(l.bot.getLocation().getDistance(l.to)) + " tiles)");
                    }
                    if (t <= 160) return false;
                    boolean all = true;
                    for (Leg l : legs) {
                        if (l.done) continue;
                        Location at = l.bot.getLocation();
                        if (l.prev != null && at.getDistance(l.prev) > 20) { l.jumps++; if (l.jumpLog.length() < 300) l.jumpLog += l.prev + "->" + at + " at tick " + (t - l.startTick) + "; "; }
                        l.prev = at; l.best = Math.min(l.best, at.getDistance(l.to));
                        if (AfkTasks.Runner.karamja(at)) l.karamjaSeen = true;
                        boolean ended = l.br.shopAt == null || l.br.shopAt != l.to;
                        if (ended || t - l.startTick >= 900) {
                            l.done = true;
                            log("RESULT leg " + l.name + " (" + l.coins + " gp, " + l.br.shopName + "): " + (ended ? (at.getDistance(l.to) <= 9 ? "ARRIVED" : "GAVE UP (shop walk ended " + Math.round(at.getDistance(l.to)) + " tiles off)") : "STILL WALKING after 900 ticks") + " at tick " + (t - l.startTick)
                                + ", now at " + at + ", closest " + Math.round(l.best) + ", was on Karamja " + l.karamjaSeen + ", jumps " + l.jumps + (l.jumps > 0 ? " (" + l.jumpLog.trim() + ")" : "") + ", coins now " + Ledger.Wallet.coins(l.bot));
                            l.br.nextThink = Long.MAX_VALUE; try { l.bot.getPulseManager().clear(); l.bot.getWalkingQueue().reset(); } catch (Throwable ignored) { }
                        } else {
                            all = false;
                            if ((t - l.startTick) % 100 == 0) log("leg " + l.name + " tick " + (t - l.startTick) + ": at " + at + " (" + Math.round(at.getDistance(l.to)) + " tiles off), doing " + BotChat.activityOf(l.bot) + sea(l.br));
                        }
                    }
                    if (all) { log("RESULT Watchdog rescues during the test (any bot): " + (Watchdog.rescued - rescued0)); log("DONE"); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
