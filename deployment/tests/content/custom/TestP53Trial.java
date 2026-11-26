package content.custom;

import core.api.StartupListener;
import core.game.node.entity.combat.ImpactHandler;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Trial only, never shipped (P53 prayer bone-bury runs). -Dtest.p53=true with -Dsmartbots.count=4 -Dquests=false -Dtraders.fast=true.
 * Takes three fleet bots off their jobs, puts each at the Exchange with 20 big bones in the bank and calls planBoneRun:
 *   run:    left alone. PASS = run ends by itself, >= 15 buried, prayer xp +15 each, bones bank+pack fall by the buried count,
 *           activity "burying bones" seen, then the bot is back on a job (runner) within 4 min.          (F3: no half-keep, lock wait)
 *   death:  killed after 3 bones. PASS = bone 0 after the death and back on a job within 6 min.             (F1/F2)
 *   combat: "combat-time" set after 2 bones (Entity.inCombat). PASS = run stops within 10 s.                (F4)
 * With -Dbots.prayerRun off (or -Dsmartbots.testPrayer off) every scenario expects NO run: PASS = planBoneRun false, bone 0.
 */
public final class TestP53Trial implements StartupListener {
    static final String T = "[TestP53Trial] ";

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p53")) return;
        System.out.println(T + "armed prayerRun=" + PrayerRun.ON + " testPrayer=" + PrayerRun.TEST);
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 150) return false;
                try { go(); } catch (Throwable x) { System.out.println(T + "RESULT FAIL setup " + x); x.printStackTrace(System.out); System.out.println(T + "DONE"); }
                return true;
            }
        });
    }

    static boolean onJob(SmartAdventurers.Brain b) {
        return b.runner != null || AfkTasks.RUNNING.containsKey(b.bot.getUsername().toLowerCase(Locale.ROOT));
    }

    static final String[] NAMES = {"run", "death", "combat"};

    static void go() {
        List<SmartAdventurers.Brain> brains = new ArrayList<>();
        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) if (b.bot != null && b.bot.isActive() && b.companion == null && brains.size() < 3) brains.add(b);
        if (brains.size() < 3) { System.out.println(T + "RESULT FAIL only " + brains.size() + " brains"); System.out.println(T + "DONE"); return; }
        final long far = System.currentTimeMillis() + 3_600_000L;
        for (int i = 0; i < 3; i++) {
            SmartAdventurers.Brain b = brains.get(i); Player p = b.bot;
            b.nextThink = far; // the brain waits while the trial sets it up
            AfkTasks.stop(p, null); b.runner = null; b.shopAt = null; b.pendingJob = null; b.idleUntil = 0;
            try { p.getPulseManager().clear(); p.getWalkingQueue().reset(); } catch (Throwable ignored) { }
            p.getProperties().setTeleportLocation(Location.create(3164 + i, 3485, 0));
            p.getBank().remove(new Item(532, p.getBank().getAmount(532))); p.getBank().remove(new Item(526, p.getBank().getAmount(526)));
            p.getInventory().remove(new Item(532, p.getInventory().getAmount(532))); p.getInventory().remove(new Item(526, p.getInventory().getAmount(526)));
            p.getBank().add(new Item(532, 20));
            if (p.getSkills().getStaticLevel(Skills.PRAYER) >= 70) p.getSkills().setStaticLevel(Skills.PRAYER, 40);
            System.out.println(T + NAMES[i] + " = " + p.getUsername() + " prayer " + p.getSkills().getStaticLevel(Skills.PRAYER) + " free " + p.getInventory().freeSlots());
        }
        final boolean expectRun = PrayerRun.ON && PrayerRun.TEST;
        GameWorld.getPulser().submit(new Pulse(1) {
            int ticks; boolean started; final double[] xp0 = new double[3]; final int[] state = new int[3]; // 0 waiting, 1 done
            final long[] mark = new long[3]; final String[] res = new String[3]; boolean sawActivity; int killedAt = -1, attackedAt = -1;
            @Override public boolean pulse() {
                ticks++;
                if (ticks == 5) {
                    long now = System.currentTimeMillis();
                    for (int i = 0; i < 3; i++) {
                        SmartAdventurers.Brain b = brains.get(i);
                        xp0[i] = b.bot.getSkills().getExperience(Skills.PRAYER);
                        boolean r = b.planBoneRun(now);
                        System.out.println(T + NAMES[i] + " planBoneRun=" + r + " bone=" + b.bone + " at " + b.bot.getLocation() + " pack=" + b.bot.getInventory().getAmount(532));
                        if (!expectRun) res[i] = (!r && b.bone == 0) ? "PASS off: no run" : "FAIL off: a run started";
                        else if (!r) res[i] = "FAIL no run started";
                        if (!r) b.nextThink = now;
                    }
                    if (!expectRun || res[0] != null && res[1] != null && res[2] != null) return finish();
                    return false;
                }
                if (ticks < 5) return false;
                long now = System.currentTimeMillis();
                SmartAdventurers.Brain run = brains.get(0), death = brains.get(1), fight = brains.get(2);
                if (run.bone != 0) { java.util.function.Supplier<String> a = BotChat.ACTIVITY.get(run.bot.getUsername().toLowerCase(Locale.ROOT)); if (a != null && "burying bones".equals(a.get())) sawActivity = true; }
                // run: ends alone, then a job
                if (res[0] == null) {
                    if (state[0] == 0 && run.bone == 0) {
                        state[0] = 1; mark[0] = now;
                        int buried = 20 - run.bot.getBank().getAmount(532) - run.bot.getInventory().getAmount(532);
                        double gain = run.bot.getSkills().getExperience(Skills.PRAYER) - xp0[0];
                        boolean ok = buried >= 15 && gain >= buried * 15.0 - 0.01 && run.bot.getInventory().getAmount(532) == 0 && sawActivity;
                        System.out.println(T + "run ended: buried " + buried + " xp +" + gain + " pack " + run.bot.getInventory().getAmount(532) + " bank " + run.bot.getBank().getAmount(532) + " activity " + sawActivity);
                        if (!ok) res[0] = "FAIL run: buried " + buried + " xp +" + gain + " activity " + sawActivity;
                    } else if (state[0] == 1 && onJob(run)) res[0] = "PASS run: buried, xp gained, back on a job in " + (now - mark[0]) / 1000 + " s";
                    else if (state[0] == 1 && now - mark[0] > 240_000) res[0] = "FAIL run: no job 4 min after the run";
                }
                // death: kill after 3 bones
                if (res[1] == null) {
                    int left = death.bot.getInventory().getAmount(532);
                    if (killedAt < 0 && death.bone != 0 && left <= 17) {
                        killedAt = ticks; mark[1] = now;
                        System.out.println(T + "death: killing " + death.bot.getUsername() + " with " + left + " bones left");
                        death.bot.getImpactHandler().manualHit(death.bot, death.bot.getSkills().getLifepoints() + 10, ImpactHandler.HitsplatType.NORMAL);
                    } else if (killedAt >= 0 && death.bone == 0 && onJob(death)) res[1] = "PASS death: run dropped (bone 0), back on a job " + (now - mark[1]) / 1000 + " s after the hit";
                    else if (killedAt >= 0 && now - mark[1] > 360_000) res[1] = "FAIL death: bone=" + death.bone + " onJob=" + onJob(death) + " 6 min after the hit";
                    else if (killedAt < 0 && death.bone == 0 && ticks > 20) res[1] = "FAIL death: run ended before the kill";
                }
                // combat: attacked after 2 bones
                if (res[2] == null) {
                    int left = fight.bot.getInventory().getAmount(532);
                    if (attackedAt < 0 && fight.bone != 0 && left <= 18) {
                        attackedAt = ticks; mark[2] = now;
                        fight.bot.setAttribute("combat-time", now + 10_000L);
                        System.out.println(T + "combat: " + fight.bot.getUsername() + " flagged in combat with " + left + " bones left");
                    } else if (attackedAt >= 0 && fight.bone == 0) res[2] = ((now - mark[2]) <= 10_000 ? "PASS" : "FAIL") + " combat: run stopped " + (now - mark[2]) / 1000.0 + " s after the attack, " + fight.bot.getInventory().getAmount(532) + " bones left in pack, bank " + fight.bot.getBank().getAmount(532);
                    else if (attackedAt >= 0 && now - mark[2] > 20_000) res[2] = "FAIL combat: still burying 20 s after the attack";
                    else if (attackedAt < 0 && fight.bone == 0 && ticks > 20) res[2] = "FAIL combat: run ended before the attack";
                }
                if (res[0] != null && res[1] != null && res[2] != null) return finish();
                if (ticks > 1000) { for (int i = 0; i < 3; i++) if (res[i] == null) res[i] = "FAIL timeout"; return finish(); }
                return false;
            }
            boolean finish() {
                int pass = 0;
                for (int i = 0; i < 3; i++) { System.out.println(T + "RESULT " + NAMES[i] + " " + res[i]); if (res[i] != null && res[i].startsWith("PASS")) pass++; }
                System.out.println(T + "tally " + PrayerRun.tallyLine() + " runs=" + PrayerRun.RUNS + " buried=" + PrayerRun.BURIED);
                System.out.println(T + "SUMMARY " + pass + "/3 PASS (prayerRun=" + PrayerRun.ON + ", testPrayer=" + PrayerRun.TEST + ")");
                System.out.println(T + "DONE");
                return true;
            }
        });
    }
}
