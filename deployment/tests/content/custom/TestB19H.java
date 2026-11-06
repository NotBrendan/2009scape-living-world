package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Trial only (-Dtest.b19h=true -Dfailures.stubRecord=true, with TestB18G's flags: -Dnav=true -Dscenario.echo=true -Dwatchdog.test=true
 * -Dwatchdog.min=2 -Dtraders.fast=true -Dsmartbots.count=8 ...): batch 19a builder B, hooks B2/B3/B4/B7 against the STUB Failures
 * (its call log Failures.CALLS, read by reflection; with Builder A's Failures the lines say "STUB-ONLY (skipped)": the record contents are A's tests).
 * On a rigged brain bot at Lumbridge (TestCR1.rig), each forced event must call Failures.failure exactly once, with the right src and sid,
 * and leave the batch 18 scenario count as before (+1 for the walk sources, +0 for replays):
 *   NV  a shop walk with its deadline passed, shopTick()             -> src never_reached, sid never_reached|shop|<here>|<Varrock>, scenario +1, fid in scenarios.jsonl
 *   AF  an AFK runner walk with no progress after 5 tries (no coins)  -> src failed_trip, sid failed_trip|afk|<from>|<dest>, scenario +1
 *   ID  Watchdog.idle()                                              -> src idle_giveup, scenario +1
 *   RP  a Replayer ending fail / gave_up / skipped / pass             -> replay_fail, replay_timeout, replay_skip, none; sid = the scenario id;
 *       its result line has "fid" (and "bug" + one replayResult for the scenario the stub calls an open bug)
 *   EX  an AFK runner whose tick throws (dest null), through Runner.pulse()'s catch -> one Failures.exception("AfkTasks.Runner.tickOnce"), print still there
 *   RS  -Dwatchdog.test puts a real adventurer in the unicorn pen; when rescued: exactly one src rescue call for it, sid rescue|...
 * With -Dfailures=false (B7): every count is 0 and scenarios still +1 (batch 18). Hot-path hooks: driver/log/tripStart counts printed.
 * At 1500 ticks (or when everything is in): DONE.
 */
public final class TestB19H implements StartupListener {
    static void log(String s) { System.out.println("[TestB19H] " + s); }
    static final Location LUM = Location.create(3222, 3218, 0), VARROCK = Location.create(3212, 3428, 0), FAR = Location.create(3100, 3500, 0);

    @SuppressWarnings("unchecked")
    static List<String> calls() { try { Field f = Failures.class.getDeclaredField("CALLS"); f.setAccessible(true); return (List<String>) f.get(null); } catch (Throwable t) { return null; } }
    static Object sf(String n) { try { Field f = Failures.class.getDeclaredField(n); f.setAccessible(true); return f.get(null); } catch (Throwable t) { return null; } }
    /** The full failure call lines for a bot (and src). */
    static List<String> lines(String kind, String bot, String src) {
        List<String> out = new ArrayList<>(); List<String> c = calls(); if (c == null) return null;
        for (String s : c) { String[] a = s.split("\\|", -1); if (!a[0].equals(kind)) continue; if (bot != null && !(a.length > 1 && (a[1].equals(bot) || a.length > 2 && a[2].equals(bot)))) continue; if (src != null && !(a.length > 3 && a[3].equals(src))) continue; out.add(s); }
        return out;
    }
    static String scen(String src) { java.util.concurrent.atomic.AtomicInteger v = Watchdog.SCEN.get(src); return v == null ? "0" : v.toString(); }
    static String n(List<?> l) { return l == null ? "STUB-ONLY (skipped: not the stub)" : String.valueOf(l.size()); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19h")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, nvFid = 0; AIPlayer bot; SmartAdventurers.Brain br; boolean forced, rescueSaid; String nvFidS;
            @Override public boolean pulse() {
                try {
                    t++;
                    if (bot == null && t >= 60) { bot = TestCR1.bot(LUM, 40, 20, 20, 20); br = TestCR1.rig(bot, false); log("rig bot " + bot.getUsername() + " at Lumbridge; failures " + (Watchdog.FAIL_ON ? "on" : "off") + ", stub log " + (calls() != null ? "yes" : "no")); return false; }
                    if (bot != null && !forced && t >= 120) { forced = true; force(); }
                    String tb = Watchdog.testBot;
                    if (!rescueSaid && Watchdog.rescued > 0 && tb != null) {
                        List<String> r = lines("failure", null, "rescue"); List<String> mine = new ArrayList<>();
                        if (r != null) for (String s : r) if (s.split("\\|")[1].toLowerCase(java.util.Locale.ROOT).equals(tb)) mine.add(s);
                        rescueSaid = true;
                        log("RESULT RS rescue of " + tb + ": Watchdog.rescued " + Watchdog.rescued + ", rescue scenarios " + scen("rescue") + ", Failures.failure(rescue) calls for it " + (r == null ? "STUB-ONLY (skipped)" : mine.size()) + " (expect 1) at tick " + t + (mine.isEmpty() ? "" : ": " + mine.get(0)));
                    }
                    if (t >= 1500 || rescueSaid && forced && t >= 400) {
                        if (!rescueSaid) log("RESULT RS rescue: none in 15 min (Watchdog.rescued " + Watchdog.rescued + ")");
                        int[] drv = new int[7]; Object dn = sf("DRV_N"); if (dn != null) for (int i = 0; i < 7; i++) drv[i] = ((java.util.concurrent.atomic.AtomicInteger[]) dn)[i].get();
                        log("RESULT hot hooks: driver calls none/planner/routemap/old/climber/watchdog/flee " + java.util.Arrays.toString(drv) + ", log calls " + sf("LOG_N") + ", tripStart calls " + sf("TRIP_N") + ", last log " + sf("lastLog"));
                        log("RESULT totals: failure calls " + n(lines("failure", null, null)) + ", exception calls " + n(lines("exception", null, null)) + ", replayResult calls " + n(lines("replayResult", null, null)) + "; scenarios rescue " + scen("rescue") + ", idle_giveup " + scen("idle_giveup") + ", never_reached " + scen("never_reached") + ", failed_trip " + scen("failed_trip") + ", quest_fail " + scen("quest_fail"));
                        List<String> all = lines("failure", null, null); if (all != null) for (String s : all) log("  call " + s);
                        List<String> ex = lines("exception", null, null); if (ex != null) for (String s : ex) log("  call " + s);
                        log("DONE"); return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }

            void force() throws Exception {
                String me = bot.getUsername();
                Location here = bot.getLocation();
                log("forcing at " + here + " (nav ON " + NavWalker.ON + ")");
                // NV
                String v0 = scen("never_reached"); int c0 = size(lines("failure", me, "never_reached"));
                br.shopAt = VARROCK; br.shopName = "Test shop"; br.shopDeadline = System.currentTimeMillis() - 1;
                br.shopTick(System.currentTimeMillis());
                List<String> nv = lines("failure", me, "never_reached");
                String want = Scenarios.id("never_reached", "shop", here, VARROCK);
                String got = nv == null || nv.size() <= c0 ? null : nv.get(nv.size() - 1);
                nvFidS = got == null ? null : got.split("\\|", -1)[got.split("\\|", -1).length - 2];
                log("RESULT NV never reached: scenarios " + v0 + " -> " + scen("never_reached") + ", failure calls " + (nv == null ? "STUB-ONLY (skipped)" : (nv.size() - c0)) + " (expect 1), domain/src/sid " + field(got, 2) + "/" + field(got, 3) + "/" + sidOf(got) + " (expect path/never_reached/" + want + "), sid ok " + want.equals(sidOf(got)) + ", fid " + nvFidS);
                // AF
                String a0 = scen("failed_trip"); int f0 = size(lines("failure", me, "failed_trip"));
                long coins = Ledger.Wallet.coins(bot);
                AfkTasks.Runner r = new AfkTasks.Runner(bot, AfkTasks.Task.NET, false, 0, false, false);
                r.state = AfkTasks.State.TO_SPOT; r.dest = FAR; r.walkFrom = bot.getLocation(); r.best = 0; r.sinceProgress = 40; r.retries = 4;
                RouteMap.PLANS.remove(me.toLowerCase(java.util.Locale.ROOT));
                r.walk(AfkTasks.State.WORK);
                List<String> af = lines("failure", me, "failed_trip"); String ag = af == null || af.size() <= f0 ? null : af.get(af.size() - 1);
                String wantA = Scenarios.id("failed_trip", "afk", here, FAR);
                log("RESULT AF afk no path (coins " + coins + "): scenarios " + a0 + " -> " + scen("failed_trip") + ", failure calls " + (af == null ? "STUB-ONLY (skipped)" : (af.size() - f0)) + " (expect 1), src/sid " + field(ag, 3) + "/" + sidOf(ag) + " (expect failed_trip/" + wantA + "), sid ok " + wantA.equals(sidOf(ag)) + ", runner last '" + r.last + "', halted " + r.halted);
                // ID
                String i0 = scen("idle_giveup"); int d0 = size(lines("failure", me, "idle_giveup"));
                Watchdog.idle(bot, bot.getLocation(), 20);
                List<String> id = lines("failure", me, "idle_giveup");
                log("RESULT ID idle give-up: scenarios " + i0 + " -> " + scen("idle_giveup") + ", failure calls " + (id == null ? "STUB-ONLY (skipped)" : (id.size() - d0)) + " (expect 1), src/sid " + (id == null || id.size() <= d0 ? "-" : field(id.get(id.size() - 1), 3) + "/" + sidOf(id.get(id.size() - 1))));
                // RP
                String[][] cases = {{"fail", "replay_fail"}, {"gave_up", "replay_timeout"}, {"skipped", "replay_skip"}, {"pass", null}};
                for (String[] c : cases) {
                    Scenarios.Sc sc = new Scenarios.Sc(); sc.id = "test19h|" + c[0] + "|404,402,0|400,400,0"; sc.source = "test19h"; sc.walker = "other"; sc.t = System.currentTimeMillis() - 5000; // id in 4 parts like a real one
                    sc.from = new int[]{3232, 3216, 0}; sc.to = new int[]{3200, 3200, 0};
                    if ("fail".equals(c[0])) setStub("TEST_BUG_SID", sc.id);
                    Scenarios.Replayer rp = new Scenarios.Replayer(); rp.bot = bot; rp.sc = sc; rp.from = Scenarios.loc(sc.from); rp.to = Scenarios.loc(sc.to);
                    int all0 = size(lines("failure", me, null)), rr0 = size(lines("replayResult", null, null)); int q0 = Scenarios.OUT_R.size();
                    rp.end(c[0], "fail".equals(c[0]) ? bot.getLocation() : null, "test " + c[0]);
                    List<String> fl = lines("failure", me, null); String last = fl == null || fl.size() <= all0 ? null : fl.get(fl.size() - 1);
                    String res = null; for (String s : Scenarios.OUT_R) if (s.contains(sc.id)) res = s;
                    log("RESULT RP " + c[0] + ": failure calls " + (fl == null ? "STUB-ONLY (skipped)" : (fl.size() - all0)) + " (expect " + (c[1] == null ? 0 : 1) + "), src/sid " + field(last, 3) + "/" + sidOf(last) + " (expect " + c[1] + "/" + sc.id + "), replayResult calls " + (size(lines("replayResult", null, null)) - rr0) + " (expect " + ("fail".equals(c[0]) ? 1 : 0) + "), result line fid " + has(res, "\"fid\"") + " bug " + has(res, "\"bug\"") + ", lines queued +" + (Scenarios.OUT_R.size() - q0));
                    if ("fail".equals(c[0])) setStub("TEST_BUG_SID", null);
                }
                // EX: a runner whose tick throws, through Runner.pulse()'s catch
                int e0 = size(lines("exception", null, null));
                AfkTasks.Runner bad = new AfkTasks.Runner(bot, AfkTasks.Task.NET, false, 0, false, false);
                bad.state = AfkTasks.State.TO_SPOT; bad.dest = null;
                AfkTasks.RUNNING.remove(me.toLowerCase(java.util.Locale.ROOT));
                boolean done = bad.pulse();
                List<String> ex = lines("exception", null, null);
                log("RESULT EX exception through AfkTasks.Runner.pulse catch: exception calls " + (ex == null ? "STUB-ONLY (skipped)" : (ex.size() - e0)) + " (expect 1)" + (ex != null && ex.size() > e0 ? ": " + ex.get(ex.size() - 1) : "") + " (expect where AfkTasks.Runner.tickOnce, bot " + me + ", NullPointerException); pulse returned " + done + ", last '" + bad.last + "'");
                // fid into scenarios.jsonl (the Scenarios timer flushes every 5 s; read from a thread)
                final String fid = nvFidS;
                new Thread(() -> {
                    try {
                        Thread.sleep(7_000);
                        File f = new File(new File(ServerConstants.DATA_PATH, "survey"), "scenarios.jsonl");
                        String line = null; if (f.exists()) for (String l : Files.readAllLines(f.toPath())) if (fid != null && l.contains("\"" + fid + "\"")) line = l;
                        log("RESULT NV fid in scenarios.jsonl: " + (fid == null ? "no fid (failures off or not the stub)" : line != null ? "yes: " + line : "NOT FOUND (deduped today, or not flushed)"));
                    } catch (Throwable e) { log("fid check: " + e); }
                }, "TestB19H").start();
            }
        });
    }

    static void setStub(String n, Object v) { try { Field f = Failures.class.getDeclaredField(n); f.setAccessible(true); f.set(null, v); } catch (Throwable ignored) { } }
    static int size(List<?> l) { return l == null ? 0 : l.size(); }
    static String field(String s, int i) { if (s == null) return "-"; String[] a = s.split("\\|", -1); return i < a.length ? a[i] : "-"; }
    /** sid is the 5th..8th fields joined (the sid itself has 3 "|"). failure|bot|domain|src|s1|s2|s3|s4|walker|stall|fid|why */
    static String sidOf(String s) { if (s == null) return "-"; String[] a = s.split("\\|", -1); if (a.length < 12) return a.length > 4 ? a[4] : "-"; return "null".equals(a[4]) ? "null" : a[4] + "|" + a[5] + "|" + a[6] + "|" + a[7]; }
    static boolean has(String s, String k) { return s != null && s.contains(k); }
}
