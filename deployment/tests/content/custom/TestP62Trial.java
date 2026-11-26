package content.custom;

import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.GroundItem;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Trial only, never shipped (P62 Summoning training). -Dtest.p62=true -Dtest.p62.bots=4 with -Dbots.summoning=true
 * -Dsmartbots.testSummon=true -Dtraders.fast=true.
 * Takes N fleet bots off their jobs, sets Wolf Whistle complete, gives each charms + a secondary in the bank and 60,000 coins,
 * puts it at Falador west bank and calls planSummonRun. Bot i: 0 spirit wolf (level 1), 1 dreadfowl (level 4), 2 granite
 * crab (level 16), 3 spirit wolf with a combat flag set half way to Taverley (must give up within 10 s, items banked).
 * PASS per bot = errand ends by itself, pouches made and banked (+xp), shop paid, no charm/shard/pouch lost, not in the
 * cellar, no familiar, back on a job within 4 min. Fleet PASS also needs 0 ground items within 16 tiles of Pikkupstix and
 * of the cellar obelisk, and 0 exceptions from Summon/SummoningCreator in the log (the read script greps that).
 * With -Dbots.summoning off: PASS = planSummonRun false, summon 0 for every bot.
 */
public final class TestP62Trial implements StartupListener {
    static final String T = "[TestP62Trial] ";

    @Override public void startup() {
        final int seedN = Integer.getInteger("test.p62seed", 0);
        if (seedN > 0) { // soak stand-in for "bots that finished Wolf Whistle" (live since train-18): same on both sides of the pair
            GameWorld.getPulser().submit(new Pulse(1) {
                int t; final java.util.Set<String> done = new java.util.HashSet<>();
                @Override public boolean pulse() {
                    if (++t < 200 || t % 100 != 0) return false; // bots log in spread out (-Dbots.bootSpread): seed as they arrive
                    int n = done.size();
                    try {
                        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) {
                            if (n >= seedN) break;
                            if (b.bot == null || !b.bot.isActive() || QuestRunner.active(b.bot) || !done.add(b.bot.getUsername())) continue;
                            Player p = b.bot;
                            if (!p.getQuestRepository().isComplete(content.data.Quests.WOLF_WHISTLE)) {
                                p.getQuestRepository().setStage(p.getQuestRepository().getQuest(content.data.Quests.WOLF_WHISTLE), 100);
                                if (p.getSkills().getStaticLevel(Skills.SUMMONING) < 4) { p.getSkills().setStaticLevel(Skills.SUMMONING, 4); p.getSkills().setLevel(Skills.SUMMONING, 4); }
                                p.getBank().add(new Item(Summon.GOLD, 275)); // the quest's reward
                            }
                            n++;
                        }
                    } catch (Throwable x) { System.out.println(T + "seed error " + x); }
                    if (n >= seedN || t >= 6000) { System.out.println(T + "seeded " + n + " bots with Wolf Whistle complete (summoning=" + Summon.ON + ", tick " + t + ")"); return true; }
                    return false;
                }
            });
        }
        if (!Boolean.getBoolean("test.p62")) return;
        System.out.println(T + "armed summoning=" + Summon.ON + " testSummon=" + Summon.TEST);
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 150 || t < 900 && SmartAdventurers.BRAINS.size() < Integer.getInteger("test.p62.bots", 4) + 2) return false; // bots log in spread out
                try { go(); } catch (Throwable x) { System.out.println(T + "RESULT FAIL setup " + x); x.printStackTrace(System.out); System.out.println(T + "DONE"); }
                return true;
            }
        });
    }

    static boolean onJob(SmartAdventurers.Brain b) { return b.runner != null || b.agiStep >= 0 || b.starUntil > 0 || b.shopAt != null || AfkTasks.RUNNING.containsKey(b.bot.getUsername().toLowerCase(Locale.ROOT)); } // a job, an agility course, a star, a shop trip: back to its day

    static final int[][] PLAN = { // {level, secondary, expected pouch}
            {1, 2859, 12047}, {4, 2138, 12043}, {16, 440, 12009}, {1, 2859, 12047}};
    static final int CHARMS = 20, SEC = 20, COINS = 60_000;

    static int groundNear(Location c, int r) {
        int n = 0;
        for (int cx = (c.getX() - r) >> 3; cx <= (c.getX() + r) >> 3; cx++) for (int cy = (c.getY() - r) >> 3; cy <= (c.getY() + r) >> 3; cy++)
            try { for (GroundItem g : RegionManager.getRegionChunk(Location.create(cx << 3, cy << 3, c.getZ())).getItems()) if (g != null && g.isActive() && !g.isAutoSpawn() && g.getLocation().getDistance(c) <= r) { n++; System.out.println(T + "  ground item " + g.getId() + " x" + g.getAmount() + " at " + g.getLocation()); } } catch (Throwable ignored) { }
        return n;
    }

    static int total(Player p, int id) { return p.getBank().getAmount(id) + p.getInventory().getAmount(id); }

    static void go() {
        final int N = Math.max(1, Math.min(4, Integer.getInteger("test.p62.bots", 4)));
        if (!Boolean.getBoolean("nav")) System.out.println(T + "SETUP NOTE: no -Dnav=true (live runs it): the walk uses the learned route, which needs the P74 east-gate leg (p69 1/4 was this)");
        List<SmartAdventurers.Brain> brains = new ArrayList<>();
        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) if (b.bot != null && b.bot.isActive() && b.companion == null && !QuestRunner.active(b.bot) && brains.size() < N) brains.add(b);
        if (brains.size() < N) { System.out.println(T + "RESULT FAIL only " + brains.size() + " brains"); System.out.println(T + "DONE"); return; }
        final long far = System.currentTimeMillis() + 3_600_000L;
        final int[] gold0 = new int[N], sec0 = new int[N]; final double[] xp0 = new double[N]; final long[] coins0 = new long[N];
        for (int i = 0; i < N; i++) {
            SmartAdventurers.Brain b = brains.get(i); Player p = b.bot;
            b.nextThink = far;
            AfkTasks.stop(p, null); b.runner = null; b.shopAt = null; b.pendingJob = null; b.idleUntil = 0; b.crew = null;
            try { p.getPulseManager().clear(); p.getWalkingQueue().reset(); } catch (Throwable ignored) { }
            p.getProperties().setTeleportLocation(Location.create(2945 + i, 3370, 0)); // Falador west bank
            p.getQuestRepository().setStage(p.getQuestRepository().getQuest(content.data.Quests.WOLF_WHISTLE), 100);
            int lvl = PLAN[i][0];
            p.getSkills().setStaticLevel(Skills.SUMMONING, lvl); p.getSkills().setLevel(Skills.SUMMONING, lvl);
            for (int id : new int[]{Summon.GOLD, PLAN[i][1], PLAN[i][2], Summon.SHARDS, Summon.EMPTY}) { p.getBank().remove(new Item(id, p.getBank().getAmount(id))); p.getInventory().remove(new Item(id, p.getInventory().getAmount(id))); }
            p.getBank().add(new Item(Summon.GOLD, CHARMS)); p.getBank().add(new Item(PLAN[i][1], SEC));
            long have = Ledger.Wallet.coins(p); if (have < COINS) p.getBank().add(new Item(995, (int) (COINS - have)));
            System.out.println(T + "bot" + i + " = " + p.getUsername() + " summoning " + lvl + " free " + p.getInventory().freeSlots() + " coins " + Ledger.Wallet.coins(p));
        }
        final boolean expect = Summon.ON && Summon.TEST;
        GameWorld.getPulser().submit(new Pulse(1) {
            int ticks; final String[] res = new String[N]; final int[] state = new int[N]; final long[] mark = new long[N]; int attackedAt = -1;
            @Override public boolean pulse() {
                ticks++;
                long now = System.currentTimeMillis();
                if (ticks == 5) {
                    for (int i = 0; i < N; i++) {
                        SmartAdventurers.Brain b = brains.get(i); Player p = b.bot;
                        gold0[i] = total(p, Summon.GOLD); sec0[i] = total(p, PLAN[i][1]); xp0[i] = p.getSkills().getExperience(Skills.SUMMONING); coins0[i] = Ledger.Wallet.coins(p);
                        boolean r = b.planSummonRun(now);
                        System.out.println(T + "bot" + i + " planSummonRun=" + r + " summon=" + b.summon + " n=" + b.summonN + " at " + p.getLocation() + " tally " + Summon.tallyLine());
                        if (!expect) res[i] = (!r && b.summon == 0) ? "PASS off: no errand" : "FAIL off: an errand started";
                        else if (!r) res[i] = "FAIL no errand started";
                        if (!r) b.nextThink = now;
                    }
                    return !expect ? finish() : false;
                }
                if (ticks < 5) return false;
                for (int i = 0; i < N; i++) {
                    if (res[i] != null) continue;
                    SmartAdventurers.Brain b = brains.get(i); Player p = b.bot;
                    if (i == 3 && attackedAt < 0 && b.summon != 0 && ticks > 8) {
                        attackedAt = ticks; mark[i] = now; p.setAttribute("combat-time", now + 10_000L);
                        System.out.println(T + "bot3 flagged in combat at " + p.getLocation());
                    }
                    if (b.summon != 0 && ticks % 25 == 0) System.out.println(T + "bot" + i + " walk t" + ticks + " summon=" + b.summon + " at " + p.getLocation() + " last=" + b.summonMove.last + " moving=" + p.getWalkingQueue().isMoving()); // P74: walk trace
                    if (state[i] == 0 && b.summon == 0) {
                        state[i] = 1;
                        int made = total(p, PLAN[i][2]);
                        int gold = total(p, Summon.GOLD), sec = total(p, PLAN[i][1]);
                        double gain = p.getSkills().getExperience(Skills.SUMMONING) - xp0[i];
                        boolean cellar = Summon.inCellar(p.getLocation());
                        boolean fam = QuestWolfWhistle.hasFamiliar(p);
                        String line = "made " + made + " xp +" + String.format(Locale.ROOT, "%.1f", gain) + " gold " + gold0[i] + "->" + gold + " sec " + sec0[i] + "->" + sec + " coins " + coins0[i] + "->" + Ledger.Wallet.coins(p)
                                + " pack pouches " + p.getInventory().getAmount(PLAN[i][2]) + " shards " + total(p, Summon.SHARDS) + " empty " + total(p, Summon.EMPTY) + " at " + p.getLocation() + " familiar " + fam;
                        System.out.println(T + "bot" + i + " errand ended: " + line);
                        boolean conserved = gold0[i] - gold == made && sec0[i] - sec >= made; // each pouch ate one charm and one secondary; nothing else lost
                        if (i == 3) {
                            boolean quick = attackedAt >= 0 && now - mark[i] <= 10_000;
                            res[i] = (quick && gold == gold0[i] && made == 0 && !fam ? "PASS" : "FAIL") + " combat: gave up " + (attackedAt >= 0 ? (now - mark[i]) / 1000.0 + " s after the flag" : "before the flag") + ", " + line;
                            mark[i] = now; continue;
                        }
                        if (!(made >= 3 && gain > 0 && conserved && !cellar && !fam && p.getInventory().getAmount(PLAN[i][2]) == 0 && Ledger.Wallet.coins(p) < coins0[i])) res[i] = "FAIL errand: " + line;
                        mark[i] = now;
                    } else if (state[i] == 1 && i != 3 && onJob(b)) res[i] = "PASS errand: pouches made and banked, xp gained, back on a job in " + (now - mark[i]) / 1000 + " s";
                    else if (state[i] == 1 && i != 3 && now - mark[i] > 240_000) res[i] = "FAIL no job 4 min after the errand";
                }
                boolean all = true; for (String s : res) all &= s != null;
                if (all) return finish();
                if (ticks > 1500) { for (int i = 0; i < N; i++) if (res[i] == null) res[i] = "FAIL timeout summon=" + brains.get(i).summon + " at " + brains.get(i).bot.getLocation(); return finish(); }
                return false;
            }
            boolean finish() {
                int pass = 0;
                for (int i = 0; i < N; i++) { System.out.println(T + "RESULT bot" + i + " " + res[i]); if (res[i] != null && res[i].startsWith("PASS")) pass++; }
                int ground = groundNear(Summon.PIKK_AT, 16) + groundNear(Summon.CELLAR, 16);
                System.out.println(T + "ground items near Pikkupstix + cellar: " + ground + ", active errands " + Summon.ACTIVE.size());
                System.out.println(T + "tally " + Summon.tallyLine() + " " + Summon.totals());
                boolean fleet = ground == 0 && Summon.ACTIVE.isEmpty();
                System.out.println(T + "SUMMARY " + pass + "/" + N + " PASS fleet " + (fleet ? "PASS" : "FAIL") + " (summoning=" + Summon.ON + ", testSummon=" + Summon.TEST + ")");
                System.out.println(T + "DONE");
                return true;
            }
        });
    }
}
