package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b21e=true; flags: -Dsmartbots.count=0, and -Dnav=false (live: the planner off) or -Dnav=true; needs
 * TestCR1.java): batch 21e, the coin drain from repeated paid sea crossings on a shop walk to The Shrimp and Parrot (live
 * 2026-10-28: 490 crossings by 30 bots in 6 h). Two bots with 2,000 gp at Lumbridge (3220,3218), each given a shop walk to
 * The Shrimp and Parrot by the brain's own shopTick (8 minute deadline): TRAP with the shop's cell marked a Watchdog trap
 * (two rescues, as live: the crossing teleport's landing became the Ardougne south bank, on the mainland), CLEAN without.
 * The legs run one at a time: TRAP, then REPICK (the TRAP bot put back at Lumbridge and given the same shop walk again:
 * the per-bot cooldown), then CLEAN.
 * PASS per leg: at most one paid sea crossing (Brain.seaPaidAll) and the walk ends on Karamja (arrived or still walking
 * there at 900 ticks) or is given up; REPICK with -Dsea.cap on: no paid crossing and given up. The trap entry is put back
 * as it was at the end. Ends with RESULT lines, a summary and DONE.
 */
public final class TestB21E implements StartupListener {
    static void log(String s) { System.out.println("[TestB21E] " + s); }
    static final Location FROM = Location.create(3220, 3218, 0);
    static final long COINS = 2_000;

    static final class Leg {
        final String name; final boolean trap; AIPlayer bot; SmartAdventurers.Brain br; Location to; boolean done, karamjaSeen; int startTick, paid0, jumps; long coins0; String jumpLog = ""; Location prev;
        Leg(String name, boolean trap) { this.name = name; this.trap = trap; }
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b21e")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, phase, fails, cell, idx = -1, next; Integer trapWas; Object[] sp; Leg[] legs; Leg cur, repick; int firstPaid; java.util.List<Leg> order;

            @Override public boolean pulse() {
                try {
                    t++;
                    if (phase == 0) {
                        if (NavWalker.ON && !NavPlanner.built) { if (t >= 1200) { log("RESULT planner never came up"); log("RESULT summary: FAIL (no planner)"); log("DONE"); return true; } return false; }
                        if (t < 150) return false;
                        phase = 1; setUp(); next = t + 6; return false;
                    }
                    if (phase == 1 && t == next - 4) for (Leg l : legs) enlist(l);
                    if (phase == 1 && t == next) phase = 2;
                    if (phase == 2) { // one leg at a time: TRAP, REPICK (the TRAP bot again, inside its cooldown), CLEAN
                        if (cur == null) {
                            if (++idx >= order.size()) { finish(); return true; }
                            cur = order.get(idx);
                            if (cur.trap) Watchdog.TRAPS.put(cell, 2); else Watchdog.TRAPS.remove(cell);
                            if (idx == 1) { firstPaid = repick.br.seaPaidAll - repick.paid0; repick.done = false; repick.jumps = 0; repick.jumpLog = ""; repick.prev = null; repick.karamjaSeen = false; teleportBack(repick); next = t + 5; }
                            else next = t;
                        }
                        if (t == next) walk(cur);
                        if (t > next && watch(cur)) cur = null;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); restoreTrap(); log("RESULT summary: FAIL (error)"); log("DONE"); return true; }
                return false;
            }

            void setUp() {
                for (Object[] s : BotChat.WorldData.shops) if (String.valueOf(s[0]).toLowerCase().contains("shrimp and parrot")) sp = s;
                if (sp == null) throw new IllegalStateException("no Shrimp and Parrot in the shop table");
                Location shop = Location.create((Integer) sp[1], (Integer) sp[2], 0);
                Watchdog.trap(shop); // loads the saved traps first
                cell = RouteMap.cell(shop.getX(), shop.getY()); trapWas = Watchdog.TRAPS.get(cell);
                Watchdog.TRAPS.remove(cell); // CLEAN first: its landing is the shop itself
                log("flags: nav " + NavWalker.ON + " (planner built " + NavPlanner.built + "), sea.cap " + SmartAdventurers.SEA_CAP + " (cooldown " + SmartAdventurers.SEA_COOL_MS / 1000 + " s), sea.land " + Watchdog.SEA_LAND
                        + "; shop " + sp[0] + " at " + shop + " (Karamja " + AfkTasks.Runner.karamja(shop) + "), saved trap count there " + trapWas + "; teleport price " + Math.max(100, MarketBrain.value(563) + 3L * MarketBrain.value(556)) + " gp; landing clean " + Watchdog.landing(shop));
                Watchdog.TRAPS.put(cell, 2);
                log("landing with the trap " + Watchdog.landing(shop) + " (Karamja " + AfkTasks.Runner.karamja(Watchdog.landing(shop)) + ")");
                legs = new Leg[]{new Leg("TRAP", true), new Leg("CLEAN", false)};
                for (Leg l : legs) { l.bot = TestCR1.bot(FROM, 40, 20, 20, 20); setCoins(l.bot, COINS); }
                repick = legs[0]; order = java.util.Arrays.asList(legs[0], repick, legs[1]);
            }

            void setCoins(AIPlayer b, long c) {
                b.getInventory().remove(new Item(995, b.getInventory().getAmount(995))); b.getBank().remove(new Item(995, b.getBank().getAmount(995)));
                if (c > 0) b.getInventory().add(new Item(995, (int) c));
            }

            void enlist(Leg l) {
                SmartAdventurers.enlist(l.bot, false, false, -1, 0, 0, "test bot (b21e leg " + l.name + ")");
                l.br = SmartAdventurers.BRAINS.get(l.bot.getUsername().toLowerCase(java.util.Locale.ROOT));
                l.br.nextThink = Long.MAX_VALUE;
                setCoins(l.bot, COINS); // after enlist (it starts the bot with an empty pack)
            }

            void teleportBack(Leg l) {
                try { l.bot.getPulseManager().clear(); l.bot.getWalkingQueue().reset(); } catch (Throwable ignored) { }
                l.bot.getProperties().setTeleportLocation(FROM);
                log("REPICK: " + l.bot.getUsername() + " put back at " + FROM);
            }

            void walk(Leg l) {
                SmartAdventurers.Brain br = l.br; int[] stock = (int[]) sp[3];
                br.shopAt = l.to = Location.create((Integer) sp[1], (Integer) sp[2], 0); br.shopName = (String) sp[0];
                br.shopBuy.clear(); br.shopBuy.add(new int[]{stock.length > 0 ? stock[0] : 1925, 1});
                br.pendingJob = null; br.shopDeadline = System.currentTimeMillis() + 480_000; br.shopBest = Double.MAX_VALUE; br.shopStalls = 0;
                br.nextThink = 0; l.startTick = t; l.paid0 = br.seaPaidAll; l.coins0 = Ledger.Wallet.coins(l.bot);
                log("leg " + l.name + ": " + l.bot.getUsername() + " at " + l.bot.getLocation() + " with " + l.coins0 + " gp walking to " + br.shopName + " " + l.to + " (" + Math.round(l.bot.getLocation().getDistance(l.to)) + " tiles), trap " + l.trap);
            }

            /** True once the leg's walk has ended (or 900 ticks). */
            boolean watch(Leg l) {
                if (l.done) return true;
                Location at = l.bot.getLocation();
                if (l.prev != null && at.getDistance(l.prev) > 20) { l.jumps++; if (l.jumpLog.length() < 300) l.jumpLog += l.prev + "->" + at + " at tick " + (t - l.startTick) + "; "; }
                l.prev = at;
                if (AfkTasks.Runner.karamja(at)) l.karamjaSeen = true;
                boolean ended = l.br.shopAt == null || l.br.shopAt != l.to;
                int paid = l.br.seaPaidAll - l.paid0;
                if (paid > 1 && !ended) ended = true; // the bug: stop it here, it fails
                if (!ended && t - l.startTick < 900) {
                    if ((t - l.startTick) % 100 == 0) log("leg " + l.name + " tick " + (t - l.startTick) + ": at " + at + " (" + Math.round(at.getDistance(l.to)) + " tiles off), paid crossings " + paid + ", doing " + BotChat.activityOf(l.bot));
                    return false;
                }
                l.done = true;
                boolean onK = AfkTasks.Runner.karamja(at), arrived = ended && at.getDistance(l.to) <= 9, gaveUp = ended && !arrived;
                boolean isRepick = idx == 1, expectNone = isRepick && SmartAdventurers.SEA_CAP && firstPaid > 0; // the cooldown holds only after a paid crossing
                boolean ok = expectNone ? paid == 0 && gaveUp : paid <= 1 && (onK || gaveUp);
                if (!ok) fails++;
                log("RESULT leg " + (isRepick ? "REPICK" : l.name) + ": " + (arrived ? "ARRIVED" : gaveUp ? "GAVE UP (" + Math.round(at.getDistance(l.to)) + " tiles off)" : "STILL WALKING after 900 ticks") + " at tick " + (t - l.startTick)
                        + ", now at " + at + " (Karamja " + onK + ", was on Karamja " + l.karamjaSeen + "), paid crossings " + paid + (expectNone ? " (expect 0, given up: cooldown)" : " (expect <= 1, ends on Karamja or given up)")
                        + ", coins " + l.coins0 + " -> " + Ledger.Wallet.coins(l.bot) + ", jumps " + l.jumps + (l.jumps > 0 ? " (" + l.jumpLog.trim() + ")" : "") + " -> " + (ok ? "PASS" : "FAIL"));
                l.br.nextThink = Long.MAX_VALUE; l.br.shopAt = null; try { l.bot.getPulseManager().clear(); l.bot.getWalkingQueue().reset(); } catch (Throwable ignored) { }
                return true;
            }

            void restoreTrap() { if (cell != 0) { if (trapWas == null) Watchdog.TRAPS.remove(cell); else Watchdog.TRAPS.put(cell, trapWas); } }

            void finish() {
                restoreTrap();
                log("RESULT summary: " + (fails == 0 ? "PASS" : "FAIL (" + fails + ")") + " (nav " + NavWalker.ON + ", sea.cap " + SmartAdventurers.SEA_CAP + ", sea.land " + Watchdog.SEA_LAND + ")");
                log("DONE");
            }
        });
    }
}
