package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;

import java.util.*;

/**
 * Trial only (-Dtest.b27hubs=true -Dsmartbots.count=0 -Dhubs.ticks=100 -Dquests=false): batch 27, hub census and bands (Hubs.java).
 *  F  the hubs file: data/eco/hubs.json exists after boot (copied from the shipped default) and loaded 12 hubs, the GE's breakRange 400.
 *  P  bad hub files are refused and leave the loaded hubs as they were (no half-loaded file).
 *  C  census: 3 test bots put at Catherby beach; Hubs.census() counts per hub equal a naive count over every online bot
 *     (bank users skipped, -Dhubs.census=work), Catherby counts the 3, and the "[Hubs]" line names every hub and says count=work.
 *  W  (fix 2) census=work: a test bot at the Draynor bank is not counted, is counted on a break, and is counted with census=all.
 *  J  jobs: a test brain picks 160 jobs with nothing flagged (Hubs.on() false) from each of 3 start points (Lumbridge, Draynor bank,
 *     Varrock); the target is the banded hub with a job spot within 60 tiles and the largest share between 0% and 70% (start and hub);
 *     160 more picks from that start with it marked deep under (count 0): its share goes up. JO: marked deep over, down (reported only).
 *  B  breaks: 40 breaks with nothing flagged, 40 with the nearest other hub marked under (all go there), 40 with
 *     Lumbridge marked over (none stay in Lumbridge).
 *  BK (fix 1) with Draynor over, Watchdog.hubsBank gives another reachable bank within +60 tiles of Draynor's distance.
 *  BR (fix 3) with the GE under, 20 breaks from Lumbridge (275 tiles) all go to the GE, each break is longer by the walk time, breaks every 1-2 trips.
 *  FS (fix 4) scaled factors: deep under BOOST^2, 4x over DAMP^4, 10x over floored at 0.3, unknown count the flat factor.
 *  H  (train-4 thermostat, the design notes) apply() fed Lumbridge counts hi+1, hi+1, mid+1, mid, lo, lo, lo+1, mid, hi, mid with the
 *     switch on (fixture: thermostatWindows 2) arms none (spawn), over, over (latched), none, none (1 of 2), under, under (latched),
 *     none, over, none; the " spawn" tag is on the first line only. F also checks the shipped file's thermostatWindows 1.
 *  KS each new switch false (hubs.bank, hubs.census=all, hubs.breaks, hubs.scaled, hubs.thermostat) gives the old answer in its unit.
 *  L  the census pulse itself printed a line within 3 minutes of the units (hubs.ticks=100 = 60 s).
 * Kill-switch run (-Dhubs=false): K = no hubs loaded, Hubs.on() false even after testMark, no census, no economy field, bankFor inert.
 * Ends with RESULT lines and DONE.
 */
public final class TestB27Hubs implements StartupListener {
    static void log(String s) { System.out.println("[TestB27Hubs] " + s); }
    static Location L(int x, int y) { return Location.create(x, y, 0); }

    static final int PICKS = 160, BREAKS = 40, PER_TICK = 20;
    /** The pre-rescope 10-band hubs file (b27-hubs-fixed), parsed after F for the mechanism units. */
    static final String FIXTURE = "{\"boost\":1.3,\"damp\":0.8,\"jobRange\":60,\"breakRange\":160,\"underWindows\":2,\"thermostatWindows\":2,\"hubs\":[{\"name\":\"Lumbridge\",\"x\":3222,\"y\":3219,\"radius\":20,\"dayMin\":8,\"dayMax\":12,\"eveMin\":12,\"eveMax\":18},{\"name\":\"GE\",\"x\":3165,\"y\":3487,\"radius\":20,\"breakRange\":400,\"dayMin\":8,\"dayMax\":12,\"eveMin\":10,\"eveMax\":16},{\"name\":\"Varrock\",\"x\":3212,\"y\":3429,\"radius\":20,\"dayMin\":4,\"dayMax\":8,\"eveMin\":6,\"eveMax\":10},{\"name\":\"Draynor\",\"x\":3088,\"y\":3248,\"radius\":20,\"dayMin\":4,\"dayMax\":8,\"eveMin\":6,\"eveMax\":10},{\"name\":\"Barbarian\",\"x\":3082,\"y\":3417,\"radius\":20,\"dayMin\":4,\"dayMax\":6,\"eveMin\":6,\"eveMax\":8},{\"name\":\"Falador\",\"x\":2976,\"y\":3376,\"radius\":22,\"dayMin\":3,\"dayMax\":6,\"eveMin\":5,\"eveMax\":8},{\"name\":\"AlKharid\",\"x\":3293,\"y\":3177,\"radius\":20,\"dayMin\":3,\"dayMax\":6,\"eveMin\":4,\"eveMax\":8},{\"name\":\"PortSarim\",\"x\":3032,\"y\":3222,\"radius\":20,\"dayMin\":2,\"dayMax\":4,\"eveMin\":3,\"eveMax\":5},{\"name\":\"Edgeville\",\"x\":3090,\"y\":3497,\"radius\":20,\"dayMin\":2,\"dayMax\":4,\"eveMin\":3,\"eveMax\":5},{\"name\":\"Catherby\",\"x\":2826,\"y\":3436,\"radius\":20,\"dayMin\":2,\"dayMax\":4,\"eveMin\":3,\"eveMax\":6},{\"name\":\"Seers\",\"x\":2725,\"y\":3488,\"radius\":20},{\"name\":\"Ardougne\",\"x\":2662,\"y\":3306,\"radius\":20}]}";
    static final Location LUMBRIDGE = L(3222, 3218), DRAYNOR_BANK = L(3092, 3245);
    static final Location[] STARTS = {LUMBRIDGE, L(3092, 3246), L(3212, 3429)}; // Draynor bank: one tile off the booth tile

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b27hubs")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            long t0; int phase, fails, n, c0; boolean done;
            final List<AIPlayer> extras = new ArrayList<>();
            SmartAdventurers.Brain br; AIPlayer me, banker;
            List<List<Location>> offBy = new ArrayList<>();
            List<Location> under = new ArrayList<>(), over = new ArrayList<>();
            Hubs.Hub target; String mode = "off"; int jStage, waited, chosen = -1;
            final List<String> ks = new ArrayList<>(); // kill-switch results, one per new switch

            @Override public boolean pulse() {
                if (done) return true;
                long now = System.currentTimeMillis();
                if (t0 == 0) t0 = now;
                try {
                    if (now - t0 > 900_000) { log("RESULT TIMEOUT in phase " + phase); endTest(); return true; }
                    switch (phase) {
                        case 0: // the world settles
                            if (now - t0 < 30_000) return false;
                            if (!Hubs.FLAG) { killSwitch(); phase = 90; t0 = now; return false; }
                            fileChecks();
                            for (int i = 0; i < 3; i++) extras.add(TestCR1.bot(L(2843 + i, 3432), 30, 20, 20, 20));
                            banker = TestCR1.bot(DRAYNOR_BANK.transform(0, 1, 0), 30, 20, 20, 20); extras.add(banker); // fix 2: a bank user inside Draynor's radius
                            me = TestCR1.bot(LUMBRIDGE, 30, 20, 20, 20);
                            me.getBank().add(new core.game.node.item.Item(995, 3000)); // not broke: under 400 coins the money-first branch picks the one best-paying job (dark wizards) every time and J measured nothing
                            br = new SmartAdventurers.Brain(); br.bot = me; br.scriptAPI = new core.game.bots.ScriptAPI(me); br.nextThink = Long.MAX_VALUE;
                            phase = 1; n = 0; return false;
                        case 1: // a few ticks for the teleports
                            if (++n < 8) return false;
                            Hubs.testPause = true; // the pulse's census would re-flag hubs under the marks
                            censusCheck(now);
                            censusWork(now);
                            Hubs.testMark(null, null); mode = "off"; jStage = 0; waited = -1; n = 0; phase = 2; return false;
                        case 2: // job picks, PER_TICK a tick: nothing flagged from each start, then under and over from the chosen one
                            if (!jobPicks()) return false;
                            phase = 3; return false;
                        case 3:
                            takeCoins(); // breaks: no paid teleport to the Exchange (the bot stays at Lumbridge for every break)
                            breakChecks();
                            bankChecks();
                            geBreaks();
                            factorChecks();
                            thermostatChecks();
                            log("RESULT KS new switches off give the old rule: " + String.join(", ", ks) + " -> " + (ks.stream().allMatch(s -> s.endsWith(" ok")) && ks.size() == 5 ? "PASS" : "FAIL"));
                            if (!(ks.stream().allMatch(s -> s.endsWith(" ok")) && ks.size() == 5)) fails++;
                            Hubs.testMark(null, null);
                            c0 = Hubs.censuses; t0 = now;
                            Hubs.testPause = false;
                            phase = 4; return false;
                        case 4: // the pulse's own census
                            if (Hubs.censuses <= c0 && now - t0 < 180_000) return false;
                            boolean l = Hubs.censuses > c0 && Hubs.lastLine != null;
                            log("RESULT L census pulse lines " + (Hubs.censuses - c0) + " after the units, last: " + Hubs.lastLine + " -> " + (l ? "PASS" : "FAIL"));
                            if (!l) fails++;
                            log("RESULT UNITS " + (fails == 0 ? "PASS" : fails + " FAIL"));
                            endTest(); return true;
                        case 90: // kill switch: wait past two census periods, then check nothing ran
                            if (now - t0 < 130_000) return false;
                            boolean k = Hubs.HUBS.length == 0 && !Hubs.on() && Hubs.censuses == 0 && Hubs.LAST == null && Hubs.lastLine == null;
                            Location bk = Hubs.bankFor(L(3200, 3280), DRAYNOR_BANK, b -> true);
                            k &= bk == DRAYNOR_BANK && !Hubs.pushBreaks() && Hubs.underBreakHub(LUMBRIDGE) == null;
                            log("RESULT K -Dhubs=false: hubs " + Hubs.HUBS.length + ", on " + Hubs.on() + ", censuses " + Hubs.censuses + ", economy field " + (Hubs.LAST != null) + ", bankFor " + bk + ", pushBreaks " + Hubs.pushBreaks() + " -> " + (k ? "PASS" : "FAIL"));
                            log("RESULT UNITS " + (k ? "PASS" : "1 FAIL"));
                            endTest(); return true;
                        default: return true;
                    }
                } catch (Throwable e) { log("RESULT exception in phase " + phase + ": " + e); e.printStackTrace(); endTest(); return true; }
            }

            void endTest() {
                done = true;
                try { Hubs.testMark(null, null); Hubs.testPause = false; } catch (Throwable ignored) { }
                Hubs.BANK = true; Hubs.CENSUS_WORK = true; Hubs.BREAKS = true; Hubs.SCALED = true; Hubs.THERMOSTAT = Hubs.opt("hubs.thermostat");
                for (AIPlayer b : extras) try { b.clear(); } catch (Throwable ignored) { }
                if (me != null) try { me.clear(); } catch (Throwable ignored) { }
                log("DONE");
            }

            void killSwitch() {
                Hubs.testMark("Lumbridge", "GE");
                boolean k = !Hubs.on() && Hubs.HUBS.length == 0;
                log("K testMark ignored when off: on " + Hubs.on() + " -> " + (k ? "ok" : "WRONG"));
            }

            void takeCoins() {
                try { int b = me.getBank().getAmount(995); if (b > 0) me.getBank().remove(new core.game.node.item.Item(995, b)); } catch (Throwable ignored) { }
                try { int i = me.getInventory().getAmount(995); if (i > 0) me.getInventory().remove(new core.game.node.item.Item(995, i)); } catch (Throwable ignored) { }
            }

            void fileChecks() {
                Hubs.Hub ge = Hubs.hub("GE");
                // Lumbridge-only rescope (the design notes section 4): the shipped file bands Lumbridge only, the GE has no breakRange,
                // and fixes 1 and 3 are off unless -Dhubs.bank=true / -Dhubs.breaks=true
                int banded = 0; for (Hubs.Hub h : Hubs.HUBS) if (h.banded) banded++;
                boolean sw = Hubs.BANK == "true".equalsIgnoreCase(System.getProperty("hubs.bank")) && Hubs.BREAKS == "true".equalsIgnoreCase(System.getProperty("hubs.breaks"))
                        && Hubs.THERMOSTAT == "true".equalsIgnoreCase(System.getProperty("hubs.thermostat")) && Hubs.UNDER_WINDOWS == 2 && Hubs.THERMO_WINDOWS == 1; // train-4 thermostat: off unless asked, the shipped file's windows
                boolean f = Hubs.dataFile().exists() && Hubs.HUBS.length == 12 && Hubs.hub("Catherby") != null && Hubs.hub("Seers") != null && !Hubs.hub("Seers").banded
                        && Hubs.hub("Lumbridge") != null && Hubs.hub("Lumbridge").banded && banded == 1 && ge != null && !ge.banded && ge.breakRange < 0 && Hubs.hub("Draynor").breakRange < 0 && sw;
                log("RESULT F " + Hubs.dataFile().getPath() + " exists " + Hubs.dataFile().exists() + ", hubs " + Hubs.HUBS.length + ", banded " + banded + " (Lumbridge only), GE breakRange " + (ge != null ? ge.breakRange : -1) + ", bank " + Hubs.BANK + ", breaks " + Hubs.BREAKS + ", thermostat " + Hubs.THERMOSTAT + " (underWindows " + Hubs.UNDER_WINDOWS + ", thermostatWindows " + Hubs.THERMO_WINDOWS + ") -> " + (f ? "PASS" : "FAIL"));
                if (!f) fails++;
                // the mechanism units below (P to FS) run on the 10-band fixture (the pre-rescope file) with fixes 1 and 3 on, as before the rescope
                String fx = Hubs.parse(FIXTURE);
                Hubs.BANK = true; Hubs.BREAKS = true;
                log("F units fixture: " + (fx == null ? "loaded " + Hubs.HUBS.length + " hubs, GE breakRange " + Hubs.hub("GE").breakRange : "REFUSED " + fx));
                if (fx != null) fails++;
                Hubs.Hub[] before = Hubs.HUBS;
                String[] bad = {"{", "[]", "{\"hubs\": []}", "{\"hubs\": [{\"name\": \"A\", \"x\": 3222, \"y\": 3219, \"dayMin\": 2}]}",
                        "{\"hubs\": [{\"name\": \"A\", \"x\": 3222, \"y\": 3219, \"dayMin\": 5, \"dayMax\": 4, \"eveMin\": 1, \"eveMax\": 2}]}",
                        "{\"boost\": 9, \"hubs\": [{\"name\": \"A\", \"x\": 3222, \"y\": 3219}]}",
                        "{\"hubs\": [{\"name\": \"A\", \"x\": 3222, \"y\": 3219}, {\"name\": \"a\", \"x\": 3100, \"y\": 3200}]}",
                        "{\"hubs\": [{\"name\": \"A\", \"x\": 3222, \"y\": 3219, \"breakRange\": 900}]}"};
                int refused = 0;
                for (String s : bad) { String why = Hubs.parse(s); if (why != null) refused++; else log("P accepted a bad file: " + s); }
                boolean p = refused == bad.length && Hubs.HUBS == before;
                log("RESULT P bad files refused " + refused + "/" + bad.length + ", hubs unchanged " + (Hubs.HUBS == before) + " -> " + (p ? "PASS" : "FAIL"));
                if (!p) fails++;
            }

            /** The census by hand: every bot inside a hub's radius; with census=work not one within 8 tiles of a bank unless on a break. */
            int[] naive(long now, boolean work) {
                Hubs.Hub[] hubs = Hubs.HUBS;
                int[] out = new int[hubs.length];
                for (Player p : Repository.getPlayers()) {
                    if (p == null || !p.isActive() || !p.isArtificial()) continue;
                    Location l = p.getLocation();
                    if (work) {
                        SmartAdventurers.Brain b = SmartAdventurers.BRAINS.get(p.getUsername().toLowerCase(Locale.ROOT));
                        boolean onBreak = b != null && b.idleUntil > now, atBank = false;
                        for (Location bk : AfkTasks.BANKS) if (bk.getZ() == l.getZ() && bk.getDistance(l) <= 8) atBank = true;
                        if (atBank && !onBreak) continue;
                    }
                    for (int i = 0; i < hubs.length; i++) if (l.getZ() == 0 && l.getDistance(hubs[i].centre) <= hubs[i].radius) { out[i]++; break; }
                }
                return out;
            }

            int idx(String name) { Hubs.Hub[] hubs = Hubs.HUBS; for (int i = 0; i < hubs.length; i++) if (hubs[i].name.equals(name)) return i; return -1; }

            void censusCheck(long now) {
                Hubs.Hub[] hubs = Hubs.HUBS;
                int[] nv = naive(now, true);
                int[] got = Hubs.census(now);
                int cat = idx("Catherby");
                boolean same = Arrays.equals(nv, got);
                boolean named = Hubs.lastLine != null && Hubs.lastLine.contains(" count=work"); for (Hubs.Hub h : hubs) named &= Hubs.lastLine != null && Hubs.lastLine.contains(" " + h.name + "=");
                boolean c = same && cat >= 0 && got[cat] >= 3 && named && Hubs.LAST != null;
                log("C extras at " + extras.get(0).getLocation() + "; census " + Arrays.toString(got) + ", naive " + Arrays.toString(nv));
                log("RESULT C census = naive " + same + ", Catherby " + (cat >= 0 ? got[cat] : -1) + " (expect 3+), line names every hub and count=work " + named + " -> " + (c ? "PASS" : "FAIL"));
                if (!c) fails++;
            }

            /** Fix 2: the banker at Draynor is skipped by census=work, counted on a break, counted by census=all (the KS half). */
            void censusWork(long now) {
                int dr = idx("Draynor");
                Location bl = banker.getLocation();
                boolean skip = !Hubs.counted(bl, null, now);
                SmartAdventurers.Brain onBreak = new SmartAdventurers.Brain(); onBreak.idleUntil = now + 60_000;
                boolean brk = Hubs.counted(bl, onBreak, now);
                boolean worker = Hubs.counted(LUMBRIDGE, null, now);
                int work = Hubs.census(now)[dr];
                Hubs.CENSUS_WORK = false;
                int[] all = Hubs.census(now);
                int[] nvAll = naive(now, false);
                boolean allLine = Hubs.lastLine != null && Hubs.lastLine.contains(" count=all");
                Hubs.CENSUS_WORK = true;
                boolean w = skip && brk && worker && dr >= 0 && Hubs.hub("Draynor").near(bl, 20);
                boolean kOld = Arrays.equals(all, nvAll) && all[dr] >= work + 1 && allLine;
                log("RESULT W census=work: banker at " + bl + " skipped " + skip + ", counted on a break " + brk + ", a worker counted " + worker + "; Draynor work " + work + " vs all " + all[dr] + " -> " + (w ? "PASS" : "FAIL"));
                if (!w) fails++;
                ks.add("census=all " + (kOld ? "ok" : "WRONG (all " + Arrays.toString(all) + " naive " + Arrays.toString(nvAll) + ")"));
            }

            /** The spot a pick goes to, as Hubs.jobFactor sees it: the hunt's spot or the task spot nearest the bot. */
            Location spotOf(SmartAdventurers.Job j) {
                if (j.hunt != null) return j.hunt.spot;
                AfkTasks.Spot[] sp = AfkTasks.SPOTS.get(j.task);
                Location best = null; double bd = Double.MAX_VALUE;
                if (sp != null) for (AfkTasks.Spot s : sp) { double d = s.at.getDistance(me.getLocation()); if (d < bd) { bd = d; best = s.at; } }
                return best;
            }

            final java.util.Map<String, Integer> labels = new java.util.HashMap<>();
            double share(List<Location> picks, Hubs.Hub h) {
                if (picks.isEmpty()) return 0;
                int k = 0; for (Location l : picks) if (h.near(l, Hubs.JOB_RANGE)) k++;
                return k / (double) picks.size();
            }

            /** Teleports me to at; true once it stands within 3 tiles (at least 3 ticks after asking). */
            boolean at(Location to) {
                if (waited < 0) { me.getProperties().setTeleportLocation(to); waited = 0; return false; }
                if (++waited < 3 || me.getLocation().getDistance(to) > 3) { if (waited > 40) { log("J teleport to " + to + " did not land (at " + me.getLocation() + ")"); waited = -1; } return false; }
                return true;
            }

            /** Stages: 0-2 nothing flagged from STARTS[stage], 3 deep under from the chosen start, 4 deep over, 5 back to Lumbridge. True when done. */
            boolean jobPicks() {
                if (jStage == 5) { if (!at(LUMBRIDGE)) return false; waited = -1; return true; }
                Location start = jStage < 3 ? STARTS[jStage] : STARTS[chosen];
                if (!at(start)) return false;
                if (jStage < 3 && offBy.size() <= jStage) offBy.add(new ArrayList<>());
                List<Location> into = jStage < 3 ? offBy.get(jStage) : jStage == 3 ? under : over;
                String tag = jStage < 3 ? "off@" + jStage : mode;
                for (int i = 0; i < PER_TICK && into.size() < PICKS; i++) {
                    br.lastJob = null; br.questPick = null;
                    SmartAdventurers.Job j = br.chooseJob();
                    if (j == null) { n++; if (n > 2000) { into.add(L(0, 0)); } continue; } // a quest won the pick; L(0,0) stops a stuck loop
                    Location l = spotOf(j); if (l != null) into.add(l);
                    labels.merge(tag + ":" + j.label() + "@" + (l == null ? "-" : l.getX() + "," + l.getY()), 1, Integer::sum);
                }
                if (into.size() < PICKS) return false;
                { StringBuilder sb = new StringBuilder(); labels.entrySet().stream().filter(e -> e.getKey().startsWith(tag + ":")).sorted((a, b) -> b.getValue() - a.getValue()).limit(6).forEach(e -> sb.append(e.getKey()).append('x').append(e.getValue()).append(' '));
                  log("J top picks " + tag + " at " + start + " (null picks " + n + ", coins " + Ledger.Wallet.coins(me) + "): " + sb.toString().trim()); }
                waited = -1;
                if (jStage < 3) {
                    if (Hubs.on()) log("J WARNING Hubs.on() true with nothing marked");
                    log("J nothing flagged from " + start + ": " + summary(into));
                    if (jStage < 2) { jStage++; return false; }
                    // the target: a banded hub some job can serve, with the largest share strictly between 0% and 70% (room to grow), over the 3 starts
                    double best = -1;
                    for (int s = 0; s < 3; s++) for (Hubs.Hub h : Hubs.HUBS) {
                        if (!h.banded || !Hubs.hasJob(h)) continue;
                        double sh = share(offBy.get(s), h);
                        if (sh > 0 && sh < 0.7 && sh > best) { best = sh; target = h; chosen = s; }
                    }
                    if (target == null) { log("RESULT J no banded hub with a job and a share between 0% and 70% from any start -> FAIL"); fails++; jStage = 5; return false; }
                    log("J target " + target.name + " from " + STARTS[chosen] + " share " + pct(best));
                    Hubs.testMark(target.name, null, 0, -1); mode = "under"; jStage = 3; return false;
                }
                if (jStage == 3) { Hubs.testMark(null, target.name, -1, Math.max(1, target.hi) * 4); mode = "over"; jStage = 4; return false; }
                Hubs.testMark(null, null);
                double so = share(offBy.get(chosen), target), su = share(under, target), sv = share(over, target);
                boolean j = su > so;
                log("RESULT J " + target.name + " from " + STARTS[chosen] + " marked deep under: share of picks within " + (int) Hubs.JOB_RANGE + " tiles " + pct(so) + " -> " + pct(su) + " -> " + (j ? "PASS" : "FAIL"));
                log("RESULT JO " + target.name + " marked deep over: " + pct(so) + " -> " + pct(sv) + " (expect lower) -> " + (sv < so || so == 0 ? "PASS" : "FAIL (reported only)"));
                if (!j) fails++;
                jStage = 5; return false;
            }

            String summary(List<Location> picks) {
                StringBuilder sb = new StringBuilder();
                for (Hubs.Hub h : Hubs.HUBS) { double s = share(picks, h); if (s > 0) sb.append(h.name).append(Hubs.hasJob(h) ? "" : "(no job)").append(' ').append(pct(s)).append(' '); }
                return sb.toString().trim();
            }

            void breakChecks() {
                Hubs.Hub lum = Hubs.hub("Lumbridge");
                Hubs.Hub other = null; double od = Double.MAX_VALUE;
                for (Hubs.Hub h : Hubs.HUBS) {
                    if (!h.banded || h == lum || h.anchor == null) continue;
                    double d = h.anchor.getDistance(me.getLocation());
                    if (d <= Hubs.BREAK_RANGE && d < od) { od = d; other = h; }
                }
                long now = System.currentTimeMillis();
                Hubs.testMark(null, null);
                int offAt = 0, offLum = 0;
                for (int i = 0; i < BREAKS; i++) { br.loiter(now); if (other != null && br.loiterAt.equals(other.anchor)) offAt++; if (lum.near(br.loiterAt, lum.radius)) offLum++; }
                if (other == null) { log("RESULT B no other hub within " + (int) Hubs.BREAK_RANGE + " of " + me.getLocation() + " -> FAIL"); fails++; return; }
                Hubs.testMark(other.name, null);
                int onAt = 0; String name = null;
                for (int i = 0; i < BREAKS; i++) { br.loiter(now); if (br.loiterAt.equals(other.anchor)) { onAt++; name = br.loiterName; } }
                Hubs.testMark(null, "Lumbridge");
                int overLum = 0;
                for (int i = 0; i < BREAKS; i++) { br.loiter(now); if (lum.near(br.loiterAt, lum.radius)) overLum++; }
                Hubs.testMark(null, null);
                br.dressBack();
                boolean b = onAt >= BREAKS * 9 / 10 && onAt > offAt && overLum == 0 && offLum > 0;
                log("RESULT B breaks at " + other.name + " (" + name + ") from " + me.getLocation() + ": nothing flagged " + offAt + "/" + BREAKS + ", marked under " + onAt + "/" + BREAKS
                        + "; in Lumbridge: nothing flagged " + offLum + "/" + BREAKS + ", Lumbridge marked over " + overLum + "/" + BREAKS + " -> " + (b ? "PASS" : "FAIL"));
                if (!b) fails++;
            }

            /** Fix 1: Draynor over: the rescue's bank from south of Varrock is another reachable one within +60 tiles. */
            void bankChecks() {
                Location from = L(3200, 3280);
                Hubs.Hub dr = Hubs.hub("Draynor");
                Location first = Watchdog.nearestBank(from, me);
                Hubs.testMark(null, null);
                Location none = Watchdog.hubsBank(from, first, me);
                Hubs.testMark(null, "Draynor");
                int sw0 = Hubs.bankSwaps;
                Location alt = Watchdog.hubsBank(from, first, me);
                Hubs.BANK = false;
                Location old = Watchdog.hubsBank(from, first, me);
                Hubs.BANK = true;
                Hubs.testMark(null, null);
                boolean isDr = first != null && dr.near(first, dr.radius);
                boolean ok = isDr && none == first && alt != null && alt != first && !dr.near(alt, dr.radius) && alt.getDistance(from) <= first.getDistance(from) + Hubs.BANK_SLACK && Hubs.bankSwaps == sw0 + 1;
                log("RESULT BK from " + from + ": nearest bank " + first + " (Draynor " + isDr + "), nothing over " + none + ", Draynor over " + alt
                        + " (" + (alt != null ? (int) alt.getDistance(from) : -1) + " vs " + (first != null ? (int) first.getDistance(from) : -1) + " tiles) -> " + (ok ? "PASS" : "FAIL"));
                if (!ok) fails++;
                ks.add("hubs.bank " + (old == first ? "ok" : "WRONG (" + old + ")"));
            }

            /** Fix 3: the GE deep under: breaks from Lumbridge (275 tiles, past the global 160) all go there and last the walk longer. */
            void geBreaks() {
                Hubs.Hub ge = Hubs.hub("GE");
                long now = System.currentTimeMillis();
                long walk = Hubs.walkMs(me.getLocation(), SmartAdventurers.GRAND_EXCHANGE);
                Hubs.testMark("GE", null, 0, -1);
                int atGe = 0; long minExtra = Long.MAX_VALUE;
                for (int i = 0; i < 20; i++) { br.loiter(now); if (br.loiterAt == SmartAdventurers.GRAND_EXCHANGE) atGe++; minExtra = Math.min(minExtra, br.idleUntil - now); }
                boolean push = Hubs.pushBreaks();
                Hubs.BREAKS = false;
                int oldGe = 0; long maxOld = 0;
                for (int i = 0; i < 20; i++) { br.loiter(now); if (br.loiterAt == SmartAdventurers.GRAND_EXCHANGE) oldGe++; maxOld = Math.max(maxOld, br.idleUntil - now); }
                boolean oldPush = Hubs.pushBreaks(); double oldRange = ge.breakRange();
                Hubs.BREAKS = true;
                Hubs.testMark(null, null);
                br.dressBack();
                double dist = me.getLocation().getDistance(SmartAdventurers.GRAND_EXCHANGE);
                boolean ok = dist > Hubs.BREAK_RANGE && ge.breakRange() == 400 && atGe == 20 && walk >= 150_000 && walk <= Hubs.WALK_CAP_MS && minExtra >= 60_000 + walk && push;
                log("RESULT BR GE under, from " + me.getLocation() + " (" + (int) dist + " tiles): breaks at the GE " + atGe + "/20, walk " + walk / 1000 + " s, shortest break " + minExtra / 1000 + " s (min 60 + walk), every 1-2 trips " + push + " -> " + (ok ? "PASS" : "FAIL"));
                if (!ok) fails++;
                boolean kOld = oldGe < 20 && maxOld <= 180_000 && !oldPush && oldRange == Hubs.BREAK_RANGE;
                ks.add("hubs.breaks " + (kOld ? "ok" : "WRONG (GE " + oldGe + "/20, longest " + maxOld / 1000 + " s, push " + oldPush + ", range " + oldRange + ")"));
            }

            /** One apply() with every banded hub at its own band middle and Lumbridge at c; Lumbridge's armed state after it. */
            String armed(Hubs.Hub lum, int li, int c, long now) {
                boolean eve = Hubs.evening(now);
                int[] n = new int[Hubs.HUBS.length];
                for (int k = 0; k < n.length; k++) n[k] = Hubs.HUBS[k].banded ? (Hubs.HUBS[k].min(eve) + Hubs.HUBS[k].max(eve)) / 2 : 0;
                n[li] = c;
                Hubs.apply(Hubs.HUBS, n, now);
                boolean u = Arrays.asList(Hubs.UNDER).contains(lum), o = Arrays.asList(Hubs.OVER).contains(lum);
                return u && o ? "both" : u ? "under" : o ? "over" : "none";
            }

            /** Train-4 thermostat (-Dhubs.thermostat): arm at the band's edge, latch to the middle, spawn warm-up; the old rule with the switch off (the KS half). */
            void thermostatChecks() {
                Hubs.Hub lum = Hubs.hub("Lumbridge");
                int li = idx("Lumbridge");
                long now = System.currentTimeMillis();
                boolean eve = Hubs.evening(now);
                int lo = lum.min(eve), hi = lum.max(eve), mid = (lo + hi) / 2;
                Hubs.testMark(null, null);
                int c0 = Hubs.censuses; String l0 = Hubs.lastLine; boolean oldT = Hubs.THERMOSTAT;
                // on: the gonogo's sequence (fixture thermostatWindows 2)
                int[] seq = {hi + 1, hi + 1, mid + 1, mid, lo, lo, lo + 1, mid, hi, mid};
                String[] want = {"none", "over", "over", "none", "none", "under", "under", "none", "over", "none"};
                Hubs.THERMOSTAT = true; Hubs.censuses = 0;
                for (Hubs.Hub h : Hubs.HUBS) { h.underRun = 0; h.overRun = 0; }
                StringBuilder got = new StringBuilder(); int wrong = 0; boolean spawnFirst = false, spawnLater = false;
                for (int i = 0; i < seq.length; i++) {
                    String state = armed(lum, li, seq[i], now + i * 600_000L);
                    boolean tag = Hubs.lastLine != null && Hubs.lastLine.contains(" spawn");
                    if (i == 0) spawnFirst = tag; else spawnLater |= tag;
                    if (!state.equals(want[i])) wrong++;
                    got.append(i > 0 ? ", " : "").append(seq[i]).append(':').append(state);
                }
                boolean h = wrong == 0 && spawnFirst && !spawnLater && Hubs.THERMO_WINDOWS == 2;
                log("RESULT H thermostat on, Lumbridge band " + lo + "-" + hi + " (mid " + mid + "), thermostatWindows " + Hubs.THERMO_WINDOWS + " (fixture, expect 2): " + got
                        + " (want none(spawn), over, over, none, none, under, under, none, over, none), spawn tag first line only " + (spawnFirst && !spawnLater) + " -> " + (h ? "PASS" : "FAIL"));
                if (!h) fails++;
                // off: the old rule: the first census arms the damp at once (no warm-up), a count at the floor resets the under run, two strictly under arm the boost
                Hubs.THERMOSTAT = false; Hubs.censuses = 0;
                for (Hubs.Hub hh : Hubs.HUBS) { hh.underRun = 0; hh.overRun = 0; }
                int[] seqOld = {hi + 1, lo, lo - 1, lo - 1};
                String[] wantOld = {"over", "none", "none", "under"};
                StringBuilder gotOld = new StringBuilder(); int wrongOld = 0; boolean tagOld = false;
                for (int i = 0; i < seqOld.length; i++) {
                    String state = armed(lum, li, seqOld[i], now + i * 600_000L);
                    tagOld |= Hubs.lastLine != null && Hubs.lastLine.contains(" spawn");
                    if (!state.equals(wantOld[i])) wrongOld++;
                    gotOld.append(i > 0 ? ", " : "").append(seqOld[i]).append(':').append(state);
                }
                ks.add("hubs.thermostat " + (wrongOld == 0 && !tagOld ? "ok" : "WRONG (" + gotOld + (tagOld ? ", spawn tag" : "") + ")"));
                Hubs.THERMOSTAT = oldT; Hubs.censuses = c0; Hubs.lastLine = l0;
                for (Hubs.Hub hh : Hubs.HUBS) { hh.underRun = 0; hh.overRun = 0; }
                Hubs.testMark(null, null);
            }

            /** Fix 4: band-distance-scaled factors at a hub's centre (inside JOB_RANGE). */
            void factorChecks() {
                Hubs.Hub h = Hubs.hub("Draynor");
                Location at = h.centre;
                Hubs.testMark("Draynor", null, 0, -1);
                double deepUnder = Hubs.factorAt(at, Hubs.UNDER, Hubs.OVER), eu = Math.min(2.5, Math.pow(Hubs.BOOST, 2));
                Hubs.SCALED = false;
                double flatOld = Hubs.factorAt(at, Hubs.UNDER, Hubs.OVER);
                Hubs.SCALED = true;
                Hubs.testMark(null, "Draynor", -1, h.hi * 4);
                double over4 = Hubs.factorAt(at, Hubs.UNDER, Hubs.OVER), eo = Math.max(0.3, Math.pow(Hubs.DAMP, 4));
                Hubs.testMark(null, "Draynor", -1, h.hi * 10);
                double over10 = Hubs.factorAt(at, Hubs.UNDER, Hubs.OVER);
                Hubs.testMark("Draynor", null);
                double flat = Hubs.factorAt(at, Hubs.UNDER, Hubs.OVER);
                Hubs.testMark(null, null);
                boolean ok = Math.abs(deepUnder - eu) < 1e-9 && Math.abs(over4 - eo) < 1e-9 && Math.abs(over10 - 0.3) < 1e-9 && Math.abs(flat - Hubs.BOOST) < 1e-9 && deepUnder > Hubs.BOOST && over4 < Hubs.DAMP;
                log(String.format(Locale.ROOT, "RESULT FS Draynor band %d-%d: deep under %.3f (expect %.3f), 4x over %.3f (expect %.3f), 10x over %.3f (floor 0.3), count unknown %.3f (flat %.2f) -> %s",
                        h.lo, h.hi, deepUnder, eu, over4, eo, over10, flat, Hubs.BOOST, ok ? "PASS" : "FAIL"));
                if (!ok) fails++;
                ks.add("hubs.scaled " + (Math.abs(flatOld - Hubs.BOOST) < 1e-9 ? "ok" : "WRONG (" + flatOld + ")"));
            }
        });
    }

    static String pct(double d) { return String.format(Locale.ROOT, "%.0f%%", d * 100); }
}
