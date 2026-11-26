package content.custom;

import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Trial only, never shipped (P68 -Dscenes.rescueEar forced-rescue trial). -Dtest.rescueEarTrial=true, about 30 fleet bots.
 * Forces real Watchdog rescues (Watchdog.rescue with a loop reason, the path the live pair logged as "path loop"; no trap cell
 * is learned) next to frozen witness bots, so Scenes.RescueTalk.saw runs exactly as live calls it.
 * One stand-in (Companions.TEST_HUMANS) stands at site A. Sites B and C are 60+ tiles from it.
 *  F  flood: 6 rescues 15 s apart at site B, a fresh free witness each. Both sides: exactly 1 remark (2 min server cap).
 *  R1 A busy bot 2 tiles, free bot 5 tiles. ON: remark by the free bot, heard=1 most=1. OFF: a remark (old nearest pick).
 *  R2 A same. ON: remark by the free bot, heard=1 most=2.
 *  R3 A same. ON: held cap (the stand-in heard 2 in 30 min), no remark. OFF: a remark.
 *  R4 B busy 2, free 5, no human. ON: remark by the free bot, heard=0. OFF: a remark.
 *  R5 C only a busy bot. ON: no remark from it, held busy. OFF: the busy bot remarks.
 *  R6 A the stand-in is the subject. ON: held human, no remark. OFF: old path (reported only).
 * Rounds R1-R6 start 5 s after the 2 min server cap clears. Fleet marks: no witness speaks twice (10 min), no two remarks
 * within 2 min, no stand-in name in any rescue-talk line, held log lines at most one a minute (the read script counts them).
 * ON PASS = every round as above. OFF PASS = no held counter moved, HEARD empty, no heard= in any line, F gave 1 remark
 * and R1-R5 gave at least 4 (the old behaviour unchanged).
 */
public final class TestScenesRescueEarTrial implements StartupListener {
    static final String T = "[TestScenesRescueEarTrial] ";
    static void log(String s) { System.out.println(T + s); }
    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }

    static final Location STAND = Location.create(3175, 3285, 0), A = Location.create(3178, 3285, 0),
            B = Location.create(3236, 3340, 0), C = Location.create(3120, 3335, 0), PARK = Location.create(3200, 3372, 0);
    static final int NEED = 34;

    @Override public void startup() {
        if (!Boolean.getBoolean("test.rescueEarTrial")) return;
        log("armed rescueEar=" + Scenes.RescueTalk.EAR + " rescueTalk=" + Scenes.on(Scenes.RESCUE));
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 200 || t < 1500 && pool().size() < NEED) return false;
                try { go(); } catch (Throwable x) { log("RESULT FAIL setup " + x); x.printStackTrace(System.out); log("DONE"); }
                return true;
            }
        });
    }

    static List<SmartAdventurers.Brain> pool() {
        List<SmartAdventurers.Brain> out = new ArrayList<>();
        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values())
            if (b.bot != null && b.bot.isActive() && b.companion == null && !QuestRunner.active(b.bot) && !Companions.human(b.bot)) out.add(b);
        return out;
    }

    static final long FAR = 3_600_000L;
    static void freeze(SmartAdventurers.Brain b) {
        b.nextThink = System.currentTimeMillis() + FAR;
        try { AfkTasks.stop(b.bot, null); } catch (Throwable ignored) { }
        b.runner = null; b.shopAt = null; b.pendingJob = null; b.idleUntil = 0; b.crew = null;
        try { b.bot.getPulseManager().clear(); b.bot.getWalkingQueue().reset(); } catch (Throwable ignored) { }
    }
    static void put(Player p, Location l) { p.getProperties().setTeleportLocation(l); }

    static void go() {
        List<SmartAdventurers.Brain> all = pool();
        if (all.size() < NEED) { log("RESULT FAIL only " + all.size() + " free bots (need " + NEED + ")"); log("DONE"); return; }
        final List<SmartAdventurers.Brain> used = new ArrayList<>(all.subList(0, NEED));
        for (int i = 0; i < used.size(); i++) { freeze(used.get(i)); put(used.get(i).bot, PARK.transform(i % 10, i / 10, 0)); }
        final Player si = TestCR1.bot(STAND, 10, 1, 1, 1);
        Companions.TEST_HUMANS.add(key(si));
        final java.util.Iterator<SmartAdventurers.Brain> it = used.iterator();
        log("setup: " + NEED + " bots frozen and parked at " + PARK + ", stand-in " + si.getUsername() + " at " + STAND + " (names below are test bots)");

        // round plan: {site, busy?, free?, subjectIsStandIn?}; F rounds first
        final String[] NAME = {"F1", "F2", "F3", "F4", "F5", "F6", "R1", "R2", "R3", "R4", "R5", "R6"};
        final Location[] SITE = {B, B, B, B, B, B, A, A, A, B, C, A};
        final boolean[] BUSY = {false, false, false, false, false, false, true, true, true, true, true, false};
        final boolean[] FREE = {true, true, true, true, true, true, true, true, true, true, false, true};
        GameWorld.getPulser().submit(new Pulse(1) {
            int r = 0, k = -1; long nextAt = 0; final long t0 = System.currentTimeMillis();
            Player subj, busy, free; int talks0, hb0, hc0, hh0;
            final String[] res = new String[NAME.length]; final List<Long> talkTimes = new ArrayList<>(); final Map<String, Integer> spoke = new HashMap<>();
            int fRemarks, rRemarks, pass, fails; boolean nameLeak, heardSeenOff;
            Player next() { return it.hasNext() ? it.next().bot : null; }
            Player nextFree(long now) { while (it.hasNext()) { Player p = it.next().bot; if (p.isActive() && !Scenes.RescueTalk.busy(p, now)) return p; } return null; } // a frozen bot can still hold an errand mark
            @Override public boolean pulse() {
                try {
                    long now = System.currentTimeMillis();
                    if (k == -1) { // wait for the server cap before F1, and between R rounds
                        if (r >= NAME.length) return finish(now);
                        long gate = Math.max(nextAt, r == 0 || r >= 6 ? Scenes.RescueTalk.GLOBAL_NEXT + 5_000 : 0);
                        if (now < gate || now - t0 < 20_000) return false;
                        Location s = SITE[r];
                        subj = r == 11 ? si : next(); busy = BUSY[r] ? next() : null; free = FREE[r] ? nextFree(now) : null;
                        if (subj == null || BUSY[r] && busy == null || FREE[r] && free == null) { log("RESULT FAIL ran out of bots at " + NAME[r]); return done(); }
                        put(subj, s);
                        if (busy != null) { put(busy, s.transform(2, 0, 0)); SmartAdventurers.brainOf(busy).starUntil = now + FAR; } // busy(): starUntil > now (brain frozen, nothing ticks it)
                        if (free != null) put(free, s.transform(0, 5, 0));
                        k = 0; return false;
                    }
                    if (++k < 4) return false; // teleports land
                    int others = 0;
                    for (Player p : Repository.getPlayers()) if (p != null && p.isActive() && p != subj && p != busy && p != free && Scenes.dist(p.getLocation(), subj.getLocation()) <= 12) others++;
                    talks0 = Scenes.rescueTalks; hb0 = Scenes.RescueTalk.heldBusy; hc0 = Scenes.RescueTalk.heldCap; hh0 = Scenes.RescueTalk.heldHuman;
                    Location at = subj.getLocation();
                    Watchdog.rescue(subj, at, 5, null, false, false, "loop: forced by the rescueEar trial"); // the live rescue path, saw() inside
                    int dt = Scenes.rescueTalks - talks0;
                    String lt = dt > 0 ? Scenes.RescueTalk.lastTalk : null, who = null;
                    if (lt != null) {
                        int a = lt.indexOf("rescue-talk "), b = lt.indexOf(" about ");
                        who = a >= 0 && b > a ? lt.substring(a + 12, b) : "?"; // names can hold spaces
                        talkTimes.add(now); spoke.merge(who.toLowerCase(Locale.ROOT), 1, Integer::sum);
                        if (lt.toLowerCase(Locale.ROOT).contains(key(si))) nameLeak = true;
                        if (!Scenes.RescueTalk.EAR && lt.contains("heard=")) heardSeenOff = true;
                    }
                    int db = Scenes.RescueTalk.heldBusy - hb0, dc = Scenes.RescueTalk.heldCap - hc0, dh = Scenes.RescueTalk.heldHuman - hh0;
                    boolean byBusy = who != null && busy != null && who.equalsIgnoreCase(busy.getUsername()), byFree = who != null && free != null && who.equalsIgnoreCase(free.getUsername());
                    boolean speakerBusy = false;
                    if (who != null) for (Player p : Repository.getPlayers()) if (p != null && p.getUsername().equalsIgnoreCase(who)) speakerBusy = Scenes.RescueTalk.busy(p, now);
                    if (r < 6) fRemarks += dt; else if (r < 11) rRemarks += dt;
                    String ok = verdict(r, dt, lt, byBusy, byFree, speakerBusy, db, dc, dh, si);
                    if (ok.startsWith("PASS")) pass++; else fails++;
                    res[r] = NAME[r] + " " + ok + ": talks +" + dt + (lt != null ? " [" + lt + "]" : "") + " held busy +" + db + " cap +" + dc + " human +" + dh
                            + " (other players within 12: " + others + ", stand-in heard " + Scenes.RescueTalk.count(key(si), now) + ")";
                    log(res[r]);
                    // tidy: witnesses out of the way, the busy mark off, the stand-in back on its spot
                    if (busy != null) { SmartAdventurers.brainOf(busy).starUntil = 0; put(busy, PARK.transform(12, r, 0)); }
                    if (free != null) put(free, PARK.transform(14, r, 0));
                    if (r == 11) put(si, STAND);
                    r++; k = -1; nextAt = now + (r < 6 ? 15_000 : 3_000);
                    return false;
                } catch (Throwable e) { log("RESULT FAIL error " + e); e.printStackTrace(System.out); return done(); }
            }

            String verdict(int r, int dt, String lt, boolean byBusy, boolean byFree, boolean speakerBusy, int db, int dc, int dh, Player si) {
                boolean ear = Scenes.RescueTalk.EAR;
                if (r < 6) return "PASS (counted in the flood mark)";
                if (!ear) {
                    if (db + dc + dh != 0) return "FAIL held moved with the flag off";
                    return "PASS (off: " + (dt > 0 ? "remark" + (byBusy ? " by the busy bot" : byFree ? " by the free bot" : " by another bot") : "no remark") + ")";
                }
                switch (r) {
                    case 6: return dt == 1 && !speakerBusy && !byBusy && lt.contains("heard=1 most=1") ? "PASS" : "FAIL want a remark by a free bot, heard=1 most=1";
                    case 7: return dt == 1 && !speakerBusy && !byBusy && lt.contains("heard=1 most=2") ? "PASS" : "FAIL want a remark by a free bot, heard=1 most=2";
                    case 8: return dt == 0 && dc >= 1 ? "PASS" : "FAIL want held cap, no remark";
                    case 9: return dt == 1 && !speakerBusy && !byBusy && lt.contains("heard=0") ? "PASS" : "FAIL want a remark by a free bot, heard=0";
                    case 10: return !byBusy && !speakerBusy && (dt == 0 && db >= 1 || dt == 1) ? "PASS" + (dt == 1 ? " (another free bot spoke)" : "") : "FAIL want no remark from the busy bot";
                    case 11: return dt == 0 && dh >= 1 ? "PASS" : "FAIL want held human, no remark";
                    default: return "FAIL";
                }
            }

            boolean finish(long now) {
                boolean ear = Scenes.RescueTalk.EAR;
                int maxPerBot = 0; for (int v : spoke.values()) maxPerBot = Math.max(maxPerBot, v);
                long minGap = Long.MAX_VALUE; for (int i = 1; i < talkTimes.size(); i++) minGap = Math.min(minGap, talkTimes.get(i) - talkTimes.get(i - 1));
                boolean fleet = fRemarks == 1 && maxPerBot <= 1 && (talkTimes.size() < 2 || minGap >= 119_000) && (!ear || !nameLeak); // off: the old path may name a stand-in subject (R6, live today), reported only
                boolean side = ear ? fails == 0 : fails == 0 && Scenes.RescueTalk.HEARD.isEmpty() && !heardSeenOff && Scenes.RescueTalk.heldBusy + Scenes.RescueTalk.heldCap + Scenes.RescueTalk.heldHuman == 0 && rRemarks >= 4;
                log("SUMMARY ear=" + ear + " flood remarks " + fRemarks + " (want 1), R1-R5 remarks " + rRemarks + ", remarks " + talkTimes.size() + " in " + (now - t0) / 1000 + " s, min gap "
                        + (minGap == Long.MAX_VALUE ? "-" : minGap / 1000 + " s") + ", most by one bot " + maxPerBot + ", stand-in name in a line " + nameLeak
                        + ", held busy/cap/human " + Scenes.RescueTalk.heldBusy + "/" + Scenes.RescueTalk.heldCap + "/" + Scenes.RescueTalk.heldHuman + ", HEARD keys " + Scenes.RescueTalk.HEARD.size()
                        + ", rounds pass " + pass + "/" + (pass + fails));
                log("RESULT " + (fleet && side ? "PASS" : "FAIL") + " (fleet marks " + fleet + ", " + (ear ? "on" : "off") + " side " + side + ")");
                return done();
            }

            boolean done() {
                for (SmartAdventurers.Brain b : used) { b.starUntil = 0; b.nextThink = System.currentTimeMillis() + 5_000; }
                log("DONE");
                return true;
            }
        });
    }
}
