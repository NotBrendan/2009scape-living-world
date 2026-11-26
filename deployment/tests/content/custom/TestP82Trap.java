package content.custom;

import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.p82trap=true, with -Dbots.summoning=true -Dsmartbots.testSummon=true -Dtraders.fast=true): P82 fix 4,
 * the Taverley trapdoor give-up of the summoning run (p79: 1 run in 4 ended "trapdoor did not let it down" at 2926,3444).
 * The live cause could not be reproduced in a trial (TestP62Trial 4/4), so this test makes the trapdoor refuse: while the bot
 * is on the surface near the trapdoor, its varp 1178 is put back to the shut trapdoor (32989, WolfWhistle.getConfig stage 100)
 * every tick, so each Open is undone before the bot can climb down. One smart bot from the Falador west bank runs the
 * errand (dreadfowl, summoning 4). Expect: flag off = gave up at the trapdoor, 0 pouches; -Dnav.p82Fix=true = it climbs
 * down after 4 clicks, makes the pouches and banks them. RESULT PASS/FAIL against -Dp82.expectFix (default = the flag).
 */
public final class TestP82Trap implements StartupListener {
    static final String T = "[TestP82Trap] ";
    static int total(Player p, int id) { return p.getBank().getAmount(id) + p.getInventory().getAmount(id); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p82trap")) return;
        final boolean fix = Boolean.getBoolean("nav.p82Fix"), expectFix = Boolean.parseBoolean(System.getProperty("p82.expectFix", String.valueOf(fix)));
        final boolean block = !"false".equals(System.getProperty("p82.block"));
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, start; SmartAdventurers.Brain b; Player p; long now0; int pouch0; boolean sawDown, wasErrand; int surfaceResets;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (t < 200) return false;
                    if (t == 200) {
                        for (SmartAdventurers.Brain x : SmartAdventurers.BRAINS.values()) if (x.bot != null && x.bot.isActive() && x.companion == null && !QuestRunner.active(x.bot)) { b = x; break; }
                        if (b == null) { System.out.println(T + "RESULT FAIL no brain"); System.out.println(T + "DONE"); return true; }
                        p = b.bot; b.nextThink = Long.MAX_VALUE;
                        AfkTasks.stop(p, null); b.runner = null; b.shopAt = null; b.pendingJob = null; b.idleUntil = 0; b.crew = null;
                        try { p.getPulseManager().clear(); p.getWalkingQueue().reset(); } catch (Throwable ignored) { }
                        p.getProperties().setTeleportLocation(Location.create(2945, 3370, 0));
                        p.getQuestRepository().setStage(p.getQuestRepository().getQuest(content.data.Quests.WOLF_WHISTLE), 100);
                        p.getSkills().setStaticLevel(Skills.SUMMONING, 4); p.getSkills().setLevel(Skills.SUMMONING, 4);
                        for (int id : new int[]{Summon.GOLD, 2138, 12043, Summon.SHARDS, Summon.EMPTY}) { p.getBank().remove(new Item(id, p.getBank().getAmount(id))); p.getInventory().remove(new Item(id, p.getInventory().getAmount(id))); }
                        p.getBank().add(new Item(Summon.GOLD, 20)); p.getBank().add(new Item(2138, 20));
                        if (Ledger.Wallet.coins(p) < 60_000) p.getBank().add(new Item(995, (int) (60_000 - Ledger.Wallet.coins(p))));
                        System.out.println(T + "bot " + p.getUsername() + " p82Fix=" + fix + " expectFix=" + expectFix + " block=" + block);
                        return false;
                    }
                    if (t == 205) {
                        pouch0 = total(p, 12043); now0 = System.currentTimeMillis();
                        boolean r = b.planSummonRun(now0);
                        System.out.println(T + "planSummonRun=" + r + " summon=" + b.summon + " n=" + b.summonN + " at " + p.getLocation());
                        if (!r) { System.out.println(T + "RESULT FAIL no errand (" + Summon.tallyLine() + ")"); System.out.println(T + "DONE"); return true; }
                        start = t; wasErrand = true; return false;
                    }
                    if (t < 205) return false;
                    Location at = p.getLocation();
                    if (Summon.inCellar(at)) sawDown = true;
                    if (block && b.summon != 0 && at.getZ() == 0 && at.getY() < 6400 && at.getDistance(Summon.TRAPDOOR_AT) <= 4) {
                        try { core.api.ContentAPIKt.setVarp(p, 1178, 32989, false); surfaceResets++; } catch (Throwable e) { System.out.println(T + "setVarp error " + e); }
                    }
                    if ((t - start) % 50 == 0) System.out.println(T + "t" + (t - start) + " summon=" + b.summon + " at " + at + " resets " + surfaceResets);
                    if (b.summon == 0 || t - start > 1500) {
                        int made = total(p, 12043) - pouch0;
                        boolean through = sawDown && made > 0;
                        boolean pass = expectFix ? through : !through;
                        System.out.println(T + "errand ended after " + (t - start) + " ticks: made " + made + ", went down " + sawDown + ", summon " + b.summon + ", at " + at + ", varp resets " + surfaceResets + ", P82 trapdoor climbs " + P82.TRAP.get());
                        System.out.println(T + "RESULT " + (pass ? "PASS" : "FAIL") + " p82Fix=" + fix + ": " + (through ? "through the trapdoor, pouches made" : "stopped at the trapdoor"));
                        b.nextThink = System.currentTimeMillis();
                        System.out.println(T + "DONE");
                        return true;
                    }
                } catch (Throwable e) { System.out.println(T + "RESULT FAIL error " + e); e.printStackTrace(System.out); System.out.println(T + "DONE"); return true; }
                return false;
            }
        });
    }
}
