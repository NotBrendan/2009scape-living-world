package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.*;
/**
 * Trial only (-Dtest.b29=true -Dsmartbots.count=0; list deployment/tests/gate-p78.txt): P78 skilling chains (Batch29.java,
 * ported from batch 29). Checks the part ON or OFF by Batch29.on(CHAINS):
 *  ON: C0 an iron chain fits a Smithing-20 bot (-Dchains.legMax=200 for the test geometry) and a lobster chain fits a Cooking-45
 *     bot, C3 never at Smithing 1; C1 mine -> smelt -> bank: a Varrock south-west iron chain (26 ore in the pack) reaches
 *     SMELTING, then TO_BANK, bank iron bars rose, wallet unchanged; C2 fish -> cook -> bank: a Catherby lobster chain (27 raw in
 *     the pack) passes a range, bank cooked lobsters rose, wallet never rose (no coin made; a Watchdog paid rescue is a sink). OFF: C-off Chains.pick gives nothing, no chain
 *     started, no JOBS entry has a chain.
 * K: with the master off or the part off, every Batch29 counter is 0 and no chat line was said. Result lines
 * "[TestB29] B29 <case> PASS|FAIL ticks=<n> | why", then "RESULT UNITS ..." and DONE.
 */
public final class TestB29 implements StartupListener {
    static void log(String s) { System.out.println("[TestB29] " + s); }
    static Location L(int x, int y) { return Location.create(x, y, 0); }
    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }

    @Override public void startup() {
        if (!"true".equalsIgnoreCase(System.getProperty("test.b29", ""))) return;
        GameWorld.getPulser().submit(new Run());
    }

    static String counters() {
        return "chains " + Batch29.Chains.line() + "; lines " + Arrays.toString(Batch29.LINES) + ", stopped " + Batch29.stopped;
    }

    static final class Run extends Pulse {
        Run() { super(1); }
        long t0; int ticks, settle, fails, passes; boolean done, began;
        final List<AIPlayer> made = new ArrayList<>();
        final List<SmartAdventurers.Brain> registered = new ArrayList<>(), thinkers = new ArrayList<>();
        // C
        int cPhase; SmartAdventurers.Brain c1, c2; boolean c1Smelt, c1Bank, c2Range, c2Bank, c1Done, c2Done; int c1Bars0, c2Cooked0; long c1Coins, c2Coins; int cStart;
        void mark(String id, boolean pass, String why) { log("B29 " + id + " " + (pass ? "PASS" : "FAIL") + " ticks=" + (ticks - settle) + " | " + why); if (pass) passes++; else fails++; }

        AIPlayer bot(Location at) { AIPlayer b = TestCR1.bot(at, 30, 20, 20, 20); made.add(b); return b; }
        SmartAdventurers.Brain brain(AIPlayer b, Location loiter, long idleFor, boolean think) {
            SmartAdventurers.Brain br = new SmartAdventurers.Brain();
            long now = System.currentTimeMillis();
            br.bot = b; br.scriptAPI = new core.game.bots.ScriptAPI(b); br.nextThink = 0; br.loiterAt = loiter; br.loiterName = "the test spot";
            br.idleUntil = idleFor > 0 ? now + idleFor : 0; br.nextExtra = Long.MAX_VALUE; br.nextChat = Long.MAX_VALUE; br.home = 0; br.shiftEnd = now + 3 * 3_600_000L;
            SmartAdventurers.BRAINS.put(key(b), br); registered.add(br);
            if (think) thinkers.add(br);
            return br;
        }
        static void lv(Player p, int skill, int l) { p.getSkills().setStaticLevel(skill, l); p.getSkills().setLevel(skill, l); }
        static SmartAdventurers.Job raw(AfkTasks.Task t) { for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.hunt == null && j.task == t) return j; return null; }
        boolean killMode() { return !Batch29.MASTER || !Batch29.PARTS[0] || Batch29.stopped; }

        @Override public boolean pulse() {
            if (done) return true;
            long now = System.currentTimeMillis();
            if (t0 == 0) t0 = now;
            ticks++;
            try {
                if (now - t0 > 1_500_000) { log("RESULT TIMEOUT phase C" + cPhase + "; " + counters()); fails++; finish(); return true; }
                if (now - t0 < 30_000) return false; // the world settles
                if (!began) { began = true; settle = ticks; setup(now); return false; }
                if (ticks - settle >= 4) for (SmartAdventurers.Brain br : thinkers) {
                    br.idleUntil = Math.max(br.idleUntil, now + 10 * 60_000L);
                    try { br.think(); } catch (Throwable t) { log("think " + br.bot.getUsername() + ": " + t); br.nextThink = now + 10_000; }
                }
                cTick(now);
                if (cPhase >= 99) { finish(); return true; }
            } catch (Throwable e) { log("RESULT exception: " + e); e.printStackTrace(); fails++; finish(); return true; }
            return false;
        }

        void setup(long now) {
            log("setup: batch29 " + Batch29.MASTER + ", parts " + Arrays.toString(Batch29.PARTS) + ", stopped " + Batch29.stopped + ", legMax " + Batch29.Chains.LEG_MAX + (killMode() ? " -> kill-switch run" : ""));
            // C: two chain bots (their brains do not think: the Runner drives them)
            c1 = brain(bot(L(3180, 3371)), null, 0, false);
            lv(c1.bot, Skills.MINING, 30); lv(c1.bot, Skills.SMITHING, 20);
            c1.bot.getInventory().add(new Item(1265)); for (int i = 0; i < 26; i++) c1.bot.getInventory().add(new Item(440));
            Ledger.Wallet.give(c1.bot, 2_000);
            c2 = brain(bot(L(2837, 3432)), null, 0, false);
            lv(c2.bot, Skills.FISHING, 40); lv(c2.bot, Skills.COOKING, 45);
            c2.bot.getInventory().add(new Item(301)); for (int i = 0; i < 27; i++) c2.bot.getInventory().add(new Item(377));
            Ledger.Wallet.give(c2.bot, 2_000);
        }

        // ------------------------------------------------------------ C chains
        void cTick(long now) {
            if (cPhase >= 99) return;
            if (ticks - settle < 6) return;
            boolean on = Batch29.on(Batch29.CHAINS);
            SmartAdventurers.Job iron = raw(AfkTasks.Task.IRON), cage = raw(AfkTasks.Task.CAGE);
            if (cPhase == 0) {
                if (iron == null || cage == null) { mark("C", false, "no IRON / CAGE job in JOBS"); cPhase = 99; return; }
                if (!on) {
                    SmartAdventurers.Job p1 = Batch29.Chains.pick(c1, iron, 1.0), p2 = Batch29.Chains.pick(c2, cage, 1.0);
                    boolean none = true; for (SmartAdventurers.Job j : SmartAdventurers.JOBS) if (j.chain != 0) none = false;
                    mark("C-off", p1 == null && p2 == null && none && Batch29.Chains.started == 0 && Batch29.LINES[Batch29.CHAINS] == 0, "pick " + p1 + "/" + p2 + ", JOBS chain-free " + none + ", started " + Batch29.Chains.started);
                    cPhase = 99; return;
                }
                SmartAdventurers.Job ci = Batch29.Chains.variant(iron), cc = Batch29.Chains.variant(cage);
                boolean fit = Batch29.Chains.fits(c1, ci), fit2 = Batch29.Chains.fits(c2, cc);
                Location[] lg = Batch29.Chains.legs(c1.bot, ci);
                mark("C0", fit && fit2, "iron chain fits " + fit + " (legs " + (lg == null ? "-" : Arrays.toString(lg)) + "), lobster chain fits " + fit2 + ", demand iron " + Batch29.Chains.demand(ci) + " lobster " + Batch29.Chains.demand(cc));
                AIPlayer low = bot(L(3182, 3371)); lv(low, Skills.MINING, 30); lv(low, Skills.SMITHING, 1); Ledger.Wallet.give(low, 2_000);
                SmartAdventurers.Brain lb = brain(low, null, 0, false);
                int got = 0; for (int i = 0; i < 50; i++) if (Batch29.Chains.fits(lb, ci)) got++;
                mark("C3", got == 0, "Smithing 1: iron chain fits " + got + "/50");
                c1Bars0 = c1.bot.getBank().getAmount(2351); c2Cooked0 = c2.bot.getBank().getAmount(379);
                c1.skipShopOnce = true; c2.skipShopOnce = true; c1.armourChecked = true; c2.armourChecked = true; // train-29: no armour or shop walk first (the test checks the chain, not the kit)
                c1.start(ci); c2.start(cc);
                c1Coins = Ledger.Wallet.coins(c1.bot); c2Coins = Ledger.Wallet.coins(c2.bot);
                AfkTasks.Runner r1 = c1.runner, r2 = c2.runner;
                log("C: started " + (r1 == null ? "no runner" : "smelt " + r1.smeltHaul + " sell " + r1.sell) + " / " + (r2 == null ? "no runner" : "cook " + r2.cookHaul + " sell " + r2.sell));
                if (r1 == null || r2 == null || !r1.smeltHaul || r1.sell || !r2.cookHaul || r2.sell) { mark("C1", false, "runner flags wrong"); mark("C2", false, "runner flags wrong"); cPhase = 99; return; }
                cStart = ticks; cPhase = 1; return;
            }
            AfkTasks.Runner r1 = c1.runner, r2 = c2.runner;
            if (r1 != null && r1.state == AfkTasks.State.SMELTING) c1Smelt = true;
            if (c1Smelt && r1 != null && (r1.state == AfkTasks.State.TO_BANK || r1.state == AfkTasks.State.BANKING)) c1Bank = true;
            if (r2 != null && r2.state == AfkTasks.State.SMELTING) c2Range = true; // the station step (TO_FURNACE / SMELTING) is the range for a cook
            if (c2Range && r2 != null && (r2.state == AfkTasks.State.TO_BANK || r2.state == AfkTasks.State.BANKING)) c2Bank = true;
            int bars = c1.bot.getBank().getAmount(2351) - c1Bars0, cooked = c2.bot.getBank().getAmount(379) - c2Cooked0;
            boolean d1 = c1Smelt && c1Bank && bars > 0, d2 = c2Range && c2Bank && cooked > 0;
            if (!c1Done && d1) { c1Done = true; mark("C1", Ledger.Wallet.coins(c1.bot) <= c1Coins, "mine -> smelt -> bank: smelted, banked, +" + bars + " iron bars, wallet " + c1Coins + " -> " + Ledger.Wallet.coins(c1.bot) + ", state " + (r1 == null ? "-" : r1.state)); if (r1 != null) r1.halt(); }
            if (!c2Done && d2) { c2Done = true; mark("C2", Ledger.Wallet.coins(c2.bot) <= c2Coins, "fish -> cook -> bank: range, banked, +" + cooked + " cooked lobsters, wallet " + c2Coins + " -> " + Ledger.Wallet.coins(c2.bot) + ", state " + (r2 == null ? "-" : r2.state)); if (r2 != null) r2.halt(); }
            if (c1Done && c2Done) { log("C: " + Batch29.Chains.line()); cPhase = 99; return; }
            if (ticks - cStart > 1_800) {
                if (!c1Done) mark("C1", false, "timeout: smelt " + c1Smelt + " bank " + c1Bank + " bars +" + bars + ", state " + (r1 == null ? "-" : r1.state + " (" + r1.last + ")"));
                if (!c2Done) mark("C2", false, "timeout: range " + c2Range + " bank " + c2Bank + " cooked +" + cooked + ", state " + (r2 == null ? "-" : r2.state + " (" + r2.last + ")"));
                if (c1.runner != null) c1.runner.halt();
                if (c2.runner != null) c2.runner.halt();
                cPhase = 99;
            }
        }

        void finish() {
            done = true;
            if (killMode()) {
                boolean zero = Batch29.lines() == 0 && Batch29.Chains.started == 0 && Batch29.Chains.trips == 0 && Batch29.Chains.otherTrips == 0;
                mark("K", zero, "kill switch: " + counters());
            }
            log("counters: " + counters());
            for (SmartAdventurers.Brain b : registered) { SmartAdventurers.BRAINS.remove(key(b.bot)); try { if (b.runner != null) b.runner.halt(); } catch (Throwable ignored) { } }
            for (AIPlayer b : made) try { b.clear(); } catch (Throwable ignored) { }
            log("RESULT UNITS " + passes + " PASS, " + fails + " FAIL" + (fails == 0 ? " -> ALL PASS" : ""));
            log("DONE");
        }
    }
}
