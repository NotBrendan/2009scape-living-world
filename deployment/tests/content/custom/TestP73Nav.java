package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.p73=true; needs TestCR1.java; run with -Dwatchdog.min=5 like live): P73 track B, the live stuck tiles of
 * 11-13..11-15 replayed. Each leg puts one smart bot on a live stuck tile and gives it the trip the live bots were on: a shop
 * walk (the brain's own shopTick, 8 min deadline) or a hunt (Brain.start(job) with the shop and armour stops skipped).
 * Logged every 100 ticks; RESULT per leg when the walk/trip ends or at the leg's tick cap: arrived / gave up / where it is,
 * closest distance, Watchdog rescues. -Dp73.legs=S1,S2,... picks legs (default all).
 */
public final class TestP73Nav implements StartupListener {
    static void log(String s) { System.out.println("[TestP73Nav] " + s); }
    static Object[] shop(String part) { for (Object[] s : BotChat.WorldData.shops) if (String.valueOf(s[0]).toLowerCase().contains(part)) return s; return null; }
    static SmartAdventurers.Job hunt(String name) { for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.hunt != null && j.hunt.name.equalsIgnoreCase(name)) return j; return null; }

    static final class Leg {
        final String name, what; final Location from; final long coins; final int lvl; final boolean isShop;
        AIPlayer bot; SmartAdventurers.Brain br; Location to; boolean done; int startTick, rescued0; double best = Double.MAX_VALUE; String track = "";
        Leg(String name, String what, int x, int y, int z, long coins, int lvl, boolean isShop) { this.name = name; this.what = what; this.from = Location.create(x, y, z); this.coins = coins; this.lvl = lvl; this.isShop = isShop; }
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p73")) return;
        final String pick = System.getProperty("p73.legs", "");
        final int cap = Integer.getInteger("p73.cap", 1000);
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; java.util.List<Leg> legs = new java.util.ArrayList<>();
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t == 200) {
                        Leg[] all = {
                            new Leg("S1", "gerrant", 3026, 3245, 0, 0, 30, true),
                            new Leg("S2", "gerrant", 3025, 3253, 0, 0, 30, true),
                            new Leg("S3", "gerrant", 3030, 3248, 0, 0, 30, true),
                            new Leg("H1", "Dark wizard", 3267, 3228, 0, 300, 40, false),
                            new Leg("H2", "Dark wizard", 3265, 3240, 0, 300, 40, false),
                            new Leg("H3", "Skeleton", 2938, 3355, 0, 300, 40, false),
                            new Leg("H4", "Black demon", 2884, 9797, 0, 300, 99, false),
                            new Leg("H5", "Moss giant", 2838, 3549, 0, 6000, 60, false),
                            new Leg("H6", "Pyrefiend", 2838, 3549, 0, 6000, 60, false),
                            new Leg("S4", "gerrant", 3293, 3170, 0, 50, 30, true),
                            new Leg("S5", "gerrant", 3031, 3244, 0, 0, 30, true),
                            new Leg("S6", "gerrant", 3037, 3251, 0, 0, 30, true),
                        };
                        StringBuilder tv = new StringBuilder();
                        for (HuntMap.Hunt h : HuntMap.HUNTS) if (h.spot != null && h.spot.getX() >= 2830 && h.spot.getX() <= 2960 && h.spot.getY() >= 9740 && h.spot.getY() <= 9860) tv.append(h.name).append(' ').append(h.spot.getX()).append(',').append(h.spot.getY()).append(" canEnter(H4 kit)=").append("?").append("; ");
                        log("Taverley dungeon hunts: " + tv);
                        for (Leg l : all) if (pick.isEmpty() || (("," + pick + ",").contains("," + l.name + ","))) legs.add(l);
                        for (Leg l : legs) {
                            l.bot = TestCR1.bot(l.from, l.lvl, l.lvl, l.lvl, l.lvl);
                            l.bot.getInventory().remove(new Item(995, l.bot.getInventory().getAmount(995))); l.bot.getBank().remove(new Item(995, l.bot.getBank().getAmount(995)));
                        }
                    }
                    if (t == 206) for (Leg l : legs) {
                        SmartAdventurers.enlist(l.bot, false, false, -1, 0, 0, "test bot (p73 leg " + l.name + ")");
                        l.br = SmartAdventurers.BRAINS.get(l.bot.getUsername().toLowerCase(java.util.Locale.ROOT));
                        l.br.nextThink = Long.MAX_VALUE;
                        l.bot.getInventory().remove(new Item(995, l.bot.getInventory().getAmount(995))); l.bot.getBank().remove(new Item(995, l.bot.getBank().getAmount(995)));
                        if (l.coins > 0) l.bot.getInventory().add(new Item(995, (int) l.coins));
                        for (int f = 0; f < 6; f++) l.bot.getInventory().add(new Item(379, 1)); // lobsters: no food stop
                        try { l.bot.getProperties().setTeleportLocation(l.from); } catch (Throwable ignored) { }
                    }
                    if (t == 212) for (Leg l : legs) {
                        SmartAdventurers.Brain br = l.br; l.startTick = t; l.rescued0 = Watchdog.rescued;
                        if (l.isShop) {
                            Object[] sh = shop(l.what); int[] stock = (int[]) sh[3];
                            br.shopAt = l.to = Location.create((Integer) sh[1], (Integer) sh[2], 0); br.shopName = (String) sh[0];
                            br.shopBuy.clear(); br.shopBuy.add(new int[]{stock.length > 0 ? stock[0] : 1925, 1});
                            br.pendingJob = null; br.shopDeadline = System.currentTimeMillis() + 480_000; br.shopBest = Double.MAX_VALUE; br.shopStalls = 0;
                            br.nextThink = 0;
                            log("leg " + l.name + ": at " + l.bot.getLocation() + " walking to " + br.shopName + " " + l.to + " (" + Math.round(l.bot.getLocation().getDistance(l.to)) + " tiles), " + Ledger.Wallet.coins(l.bot) + " gp");
                        } else {
                            SmartAdventurers.Job j = hunt(l.what);
                            if (j == null) { log("leg " + l.name + ": no hunt " + l.what); l.done = true; continue; }
                            l.to = j.hunt.spot;
                            br.skipShopOnce = true; br.armourChecked = true; br.exchangeChecked = true;
                            boolean skip = Resources.skipHunt(l.bot, j.hunt.spot);
                            log("leg " + l.name + ": at " + l.bot.getLocation() + " hunt " + j.label() + " spot " + l.to + " skipHunt " + skip + " canEnter " + Gates.canEnter(l.bot, l.to) + ", " + Ledger.Wallet.coins(l.bot) + " gp");
                            br.start(j);
                            br.nextThink = Long.MAX_VALUE;
                        }
                    }
                    if (t <= 212) return false;
                    boolean all = true;
                    for (Leg l : legs) {
                        if (l.done) continue;
                        Location at = l.bot.getLocation();
                        double dd = at.getZ() == l.to.getZ() ? at.getDistance(l.to) : 9999;
                        l.best = Math.min(l.best, dd);
                        boolean ended = l.isShop ? (l.br.shopAt == null || l.br.shopAt != l.to) : (l.br.runner == null || dd <= 8);
                        int age = t - l.startTick;
                        if (age % 25 == 0 && l.track.length() < 900) l.track += at.getX() + "," + at.getY() + (at.getZ() != 0 ? "," + at.getZ() : "") + " ";
                        if (ended || age >= cap) {
                            l.done = true;
                            String res = ended ? (dd <= 9 ? "ARRIVED" : "ENDED " + Math.round(dd) + " off") : "STILL OUT after " + age + " ticks";
                            log("RESULT leg " + l.name + " (" + l.what + " from " + l.from.getX() + "," + l.from.getY() + "): " + res + " at " + at + " in " + age + " ticks, closest " + Math.round(l.best) + ", rescues " + (Watchdog.rescued - l.rescued0) + ", runner " + (l.br.runner == null ? "-" : l.br.runner.last));
                            log("TRACK " + l.name + ": " + l.track.trim());
                            l.br.nextThink = Long.MAX_VALUE; try { l.bot.getPulseManager().clear(); l.bot.getWalkingQueue().reset(); } catch (Throwable ignored) { }
                        } else {
                            all = false;
                            if (age % 100 == 0) log("leg " + l.name + " tick " + age + ": at " + at + " (" + Math.round(dd) + " off), " + BotChat.activityOf(l.bot) + (l.br.runner != null ? " | " + l.br.runner.last : ""));
                            if ((age % 50 == 0 || l.isShop && age % 10 == 0 && age <= 400) && at.getZ() == 0 && at.getY() < 6400 && l.to.getY() < 6400) try {
                                NavPlanner.Route a = NavPlanner.plan(at, l.to, NavWalker.agility(l.bot), 0, true), b = NavPlanner.plan(at, l.to, NavWalker.agility(l.bot), 0, false);
                                log("PLAN " + l.name + " at " + at.getX() + "," + at.getY() + ": toll " + (a == null ? "none" : a.tiles + " " + a.hops.subList(0, Math.min(3, a.hops.size()))) + " | free " + (b == null ? "none" : b.tiles + " " + b.hops.subList(0, Math.min(3, b.hops.size()))) + " | doors " + (a == null ? "-" : a.doorTiles + "->" + a.doorAcross) + " | walker " + l.bot.getWalkingQueue().isMoving());
                            } catch (Throwable e) { log("PLAN error " + e); }
                        }
                    }
                    if (all) { log("DONE"); return true; }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
