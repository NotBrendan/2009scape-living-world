package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;

/**
 * Batch 24 money guards for {@link Bargain}: no new coin source, the daily discount budget, the floor price, the
 * exit rule, and no haggling for the AFK runner. Plain java, no server needed:
 *   javac -d out deployment/addons-src/content/custom/Bargain.java deployment/tests/content/custom/TestBargain.java
 *   java -cp out content.custom.TestBargain deployment/addons-src/content/custom/Bargain.java
 * Prints one PASS/FAIL line per check and "[TestBargain] DONE n pass, m fail"; exit code 1 on any fail.
 */
public final class TestBargain {
    static int pass, fail;
    static final long T0 = 1_793_318_400_000L; // 2026-10-30 00:00 UTC

    static void check(String what, boolean ok) { if (ok) pass++; else fail++; System.out.println("[TestBargain] " + (ok ? "PASS " : "FAIL ") + what); }

    static void reset() {
        Bargain.SESSIONS.clear(); Bargain.WALKS.clear(); Bargain.DAY.clear(); Bargain.MOOD.clear();
        Bargain.tripped = false; Bargain.tripReason = ""; Bargain.dataDir = null; Bargain.offCheckAt = 0;
    }

    public static void main(String[] args) throws Exception {
        String src = args.length > 0 ? args[0] : "deployment/addons-src/content/custom/Bargain.java";
        noCoinSource(src);
        flagDefaultsOff();
        floorAndList();
        dailyBudget();
        exitRule();
        buying();
        afkRunner();
        walkAway();
        coinAlarmWiring(src); // batch 24b item 1
        memoryAndRecovery();  // batch 24b item 2
        stageTwoDeals(src);   // batch 24b item 3
        dealsLine(src);       // batch 24b item 4
        runtimeOff();         // P22: data/bargain-off works without a restart
        System.out.println("[TestBargain] DONE " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    /** Guard 1: the engine names nothing that can create or move coins or items. */
    static void noCoinSource(String path) throws Exception {
        String code = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        String[] banned = {"Ledger", "Wallet", "getInventory", "getBank", "new Item", "995", ".give(", ".pay(", "GrandExchange", "addItem", "TradeModule"};
        StringBuilder hit = new StringBuilder();
        for (String b : banned) if (code.contains(b)) hit.append(b).append(' ');
        check("no coin source: Bargain.java names no coin/item API " + (hit.length() > 0 ? "(found " + hit + ")" : ""), hit.length() == 0);
        check("no coin source: only java.* imports", !code.matches("(?s).*import (?!java\\.).*"));
    }

    static void flagDefaultsOff() {
        reset(); System.clearProperty("bargain.on");
        check("flag: off by default", !Bargain.on());
        System.setProperty("bargain.on", "true");
        check("flag: -Dbargain.on=true turns it on", Bargain.on());
    }

    /** 500 random bids per tier: every total the engine names lies in [floor, list]; nothing over the quote. */
    static void floorAndList() {
        reset(); Random r = new Random(24);
        int bad = 0, n = 0;
        for (int i = 0; i < 500; i++) {
            long now = T0 + i * 1_000L;
            long list = 100 + r.nextInt(200_000), floor = (long) (list * (0.5 + r.nextDouble() * 0.6)); // floor may exceed list: must clamp
            Bargain.DAY.clear(); Bargain.DAY.put("t" + i % 7, new long[]{Bargain.day(now), 10_000_000, 0}); // plenty of budget: the floor must hold alone
            Bargain.Session s = Bargain.sell("t" + i % 7, "p" + i, list, floor, r.nextInt(9), false);
            long effFloor = Math.min(floor, list);
            for (int k = 0; k < 4; k++) {
                long bid = r.nextInt(4) == 0 ? r.nextInt(100) : (long) (list * r.nextDouble() * 1.2);
                Bargain.Result res = Bargain.decide(s, bid, r.nextInt(30) == 0, now);
                n++;
                if (res.total < effFloor || res.total > list || res.total > s.quote() && res.verdict != Bargain.Verdict.WALK) { bad++; System.out.println("[TestBargain]   bad " + res + " list " + list + " floor " + effFloor); }
                if (res.verdict == Bargain.Verdict.WALK || res.verdict == Bargain.Verdict.ACCEPT) break;
            }
            Bargain.WALKS.clear();
        }
        check("floor price: " + n + " verdicts all in [floor, list]", bad == 0);
        reset();
        Bargain.Session s = Bargain.sell("t", "p", 10_000, 9_500, 9, false);
        Bargain.DAY.put("t", new long[]{Bargain.day(T0), 10_000_000, 0});
        check("floor price: clamp(0) = floor", Bargain.clamp(s, 0, T0) == 9_500);
        check("floor price: clamp(huge) = list", Bargain.clamp(s, 1_000_000, T0) == 10_000);
        Bargain.Result res = Bargain.decide(s, 9_000, false, T0);
        check("floor price: a mate's 90% bid under the floor is not accepted below floor (" + res + ")", res.total >= 9_500);
    }

    /** Amendment 8: per trader per UTC day, discount <= min(3% of list sales, 2,000); then list price. */
    static void dailyBudget() {
        reset();
        long now = T0 + 3_600_000L, given = 0, sales = 0; int held = 0;
        for (int i = 0; i < 60; i++) {
            Bargain.Session s = Bargain.sell("miner", "p" + i, 5_000, 0, 9, false);
            Bargain.Result res = null;
            for (int k = 0; k < 4; k++) { res = Bargain.decide(s, 4_000, false, now); if (res.verdict != Bargain.Verdict.COUNTER) break; }
            long paid = res.verdict == Bargain.Verdict.ACCEPT || res.verdict == Bargain.Verdict.FINAL ? res.total : s.quote();
            if (paid == 5_000) held++;
            Bargain.sold(s, paid, now);
            given += 5_000 - paid; sales += 5_000;
            now += 60_000;
        }
        long cap = Math.min(2_000, sales * 3 / 100);
        check("daily budget: discount " + given + " <= cap " + cap, given <= cap);
        check("daily budget: traders then hold list price (" + held + " of 60 at list)", held > 0);
        check("daily budget: never tripped by honest use", !Bargain.tripped);
        Bargain.Session s = Bargain.sell("miner", "late", 5_000, 0, 9, false);
        Bargain.Result res = Bargain.decide(s, 4_500, false, now);
        check("daily budget: spent budget answers FINAL at list (" + res + ")", res.total == 5_000 && res.verdict != Bargain.Verdict.COUNTER);
        long tomorrow = T0 + 86_400_000L + 60_000;
        check("daily budget: resets on the next UTC day", Bargain.budgetLeft("miner", 5_000, tomorrow) == 150);
        reset();
        check("daily budget: the first sale of the day gets at most 3% of itself", Bargain.budgetLeft("x", 10_000, T0) == 300);
        check("daily budget: capped at 2,000", Bargain.budgetLeft("x", 10_000_000, T0) == 2_000);
    }

    /** The exit rule: discounts past budget, a final outside [floor, list], or a coin alarm turn the engine off. */
    static void exitRule() {
        reset(); System.setProperty("bargain.on", "true");
        Bargain.Session s = Bargain.sell("fish", "p", 10_000, 0, 0, false);
        Bargain.sold(s, 9_000, T0); // 1,000 off: budget was 300
        check("exit rule: discount past the daily budget turns it off", Bargain.tripped && !Bargain.on());
        reset();
        s = Bargain.sell("fish", "p", 10_000, 9_200, 0, false);
        Bargain.DAY.put("fish", new long[]{Bargain.day(T0), 10_000_000, 0});
        Bargain.sold(s, 9_100, T0);
        check("exit rule: a final under floor turns it off", Bargain.tripped);
        reset();
        Bargain.coinAlarm("test");
        check("exit rule: a coin alarm turns it off", !Bargain.on() && Bargain.tripReason.contains("coin alarm"));
        reset();
        s = Bargain.sell("fish", "p", 10_000, 0, 0, false);
        Bargain.sold(s, 10_000, T0);
        check("exit rule: a list-price sale leaves it on", Bargain.on());
    }

    /** P22: touching data/bargain-off on a running server turns the engine off within the check interval; deleting it does not re-arm. */
    static void runtimeOff() throws Exception {
        java.io.File dir = Files.createTempDirectory("bargainoff").toFile(), marker = new java.io.File(dir, Bargain.MARKER);
        java.io.PrintStream real = System.out;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        System.setProperty("bargain.offCheckSec", "1");
        try {
            reset(); Bargain.dataDir = dir; System.clearProperty("bargain.on");
            marker.createNewFile();
            System.setOut(new java.io.PrintStream(buf, true));
            boolean offFlag = Bargain.on();
            System.setOut(real);
            check("runtime off: flag off + marker = off, no trip, no OFF line", !offFlag && !Bargain.tripped && buf.size() == 0);
            marker.delete();

            reset(); Bargain.dataDir = dir; System.setProperty("bargain.on", "true");
            check("runtime off: flag on, no marker = on", Bargain.on());
            marker.createNewFile(); // touched mid-run
            check("runtime off: inside the check interval it is still on (cached look)", Bargain.on());
            Thread.sleep(1_200);
            System.setOut(new java.io.PrintStream(buf, true));
            boolean after = Bargain.on(), again = Bargain.on();
            Thread.sleep(1_200);
            boolean later = Bargain.on();
            System.setOut(real);
            String out = buf.toString("UTF-8");
            String line = "[Bargain] OFF: data/bargain-off found at runtime (traders hold list price; remove bargain-off and restart to re-arm)";
            int n = 0; for (int i = out.indexOf(line); i >= 0; i = out.indexOf(line, i + 1)) n++;
            check("runtime off: past the interval on() is false and tripped", !after && !again && !later && Bargain.tripped);
            check("runtime off: the OFF line is printed once (" + n + ")", n == 1 && out.trim().equals(line));
            // the trader's gate (TravellingTraders: if (Bargain.on()) bargain(...) else hold the price): a low bid now pays list
            Bargain.Session s = Bargain.sell("fish", "p", 10_000, 9_000, 0, false);
            long price = Bargain.on() ? Bargain.decide(s, 9_000, false, T0).total : s.list;
            check("runtime off: a sale after it is priced at list, no discount (" + price + ")", price == 10_000);
            check("runtime off: the marker is left as it was", marker.exists());

            marker.delete();
            Thread.sleep(1_200);
            check("runtime off: deleting the marker does not re-arm", !Bargain.on() && Bargain.tripped);
        } finally {
            System.setOut(real);
            System.clearProperty("bargain.offCheckSec"); System.clearProperty("bargain.on");
            marker.delete(); dir.delete(); reset();
        }
    }

    /** Buying (PlayerTrades stage): never over guide. */
    /** Batch 24b item 1: CoinWatch's alarm calls Bargain.coinAlarm; off-flag alarms write no marker; on-flag ones trip and write it. */
    static void coinAlarmWiring(String bargainSrc) throws Exception {
        java.nio.file.Path cw = Paths.get(bargainSrc).resolveSibling("CoinWatch.java");
        String code = new String(Files.readAllBytes(cw), StandardCharsets.UTF_8).replaceAll("//[^\\n]*", "");
        int at = code.indexOf("ALARM coin-drain"), call = code.indexOf("Bargain.coinAlarm(");
        check("coin alarm wiring: CoinWatch.tick calls Bargain.coinAlarm after its ALARM line", at > 0 && call > at);
        java.io.File dir = Files.createTempDirectory("bargain").toFile();
        reset(); Bargain.dataDir = dir; System.clearProperty("bargain.on");
        Bargain.coinAlarm("CoinWatch paid actions over 12");
        check("coin alarm wiring: flag off = no trip, no marker", !Bargain.tripped && !new java.io.File(dir, Bargain.MARKER).exists());
        System.setProperty("bargain.on", "true");
        Bargain.coinAlarm("CoinWatch bot coins fell -20.0%");
        check("coin alarm wiring: flag on = trip off, marker written, reason kept", !Bargain.on() && new java.io.File(dir, Bargain.MARKER).exists() && Bargain.tripReason.contains("bot coins fell"));
        reset(); Bargain.init(dir);
        check("coin alarm wiring: the marker keeps it off after a restart", !Bargain.on());
        new java.io.File(dir, Bargain.MARKER).delete(); dir.delete(); reset();
    }

    /** Batch 24b item 2: Memory.Met deals (encode/decode, last 5, 7-day lookup), lowball flag, trust +1/24 h recovery. */
    static void memoryAndRecovery() {
        reset(); System.setProperty("bargain.on", "true");
        Bargain.Deal d = new Bargain.Deal("Miner|Bob", "coal", 10, 1_500, T0);
        Bargain.Deal back = Bargain.Deal.decode(d.encode());
        check("memory: a deal round-trips (" + d.encode() + ")", back != null && back.bot.equals("Miner/Bob") && back.item.equals("coal") && back.qty == 10 && back.total == 1_500 && back.when == T0);
        check("memory: malformed saves decode to null", Bargain.Deal.decode("a|b|x|1|2") == null && Bargain.Deal.decode("a|b") == null && Bargain.Deal.decode(null) == null);
        java.util.List<Bargain.Deal> list = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) Bargain.remember(list, new Bargain.Deal("miner", "ore" + i, 1, i, T0 + i));
        Bargain.remember(list, null);
        check("memory: keeps the last 5, newest last", list.size() == 5 && list.get(0).item.equals("ore3") && list.get(4).item.equals("ore7"));
        check("memory: lastDeal finds a deal inside 7 days", Bargain.lastDeal(list, "MINER", "ore5", T0 + 6 * 86_400_000L) != null);
        check("memory: lastDeal ignores one older than 7 days", Bargain.lastDeal(list, "miner", "ore5", T0 + 8 * 86_400_000L) == null);
        Bargain.Session s = Bargain.sell("fish", "p", 1_000, 920, 0, false);
        Bargain.Result low = Bargain.decide(s, 100, false, T0), counter;
        s = Bargain.sell("fish", "q", 1_000, 920, 0, false);
        counter = Bargain.decide(s, 900, false, T0);
        Bargain.Result walk = Bargain.decide(Bargain.sell("fish", "p", 1_000, 920, 0, false), 0, true, T0 + 1);
        check("memory: lowball flag on lowball verdicts, not on a fair bid (" + low + ", " + walk + ", " + counter + ")", low.lowball && walk.lowball && !counter.lowball);
        long[] owed = Bargain.struck(null, T0);
        check("recovery: nothing back inside 24 h", Bargain.recoverDue(owed, T0 + 23 * 3_600_000L) == 0 && owed[0] == 1);
        check("recovery: +1 after 24 h, then nothing owed", Bargain.recoverDue(owed, T0 + 24 * 3_600_000L) == 1 && owed[0] == 0 && Bargain.recoverDue(owed, T0 + 999 * 3_600_000L) == 0);
        owed = Bargain.struck(Bargain.struck(null, T0), T0 + 1_000);
        check("recovery: two strikes, a new strike restarts the clock", owed[0] == 2 && Bargain.recoverDue(owed, T0 + 86_400_000L) == 0);
        check("recovery: never more than owed (2 after 5 days)", Bargain.recoverDue(owed, T0 + 1_000 + 5 * 86_400_000L) == 2 && owed[0] == 0);
        owed = Bargain.struck(null, T0);
        int got = Bargain.recoverDue(owed, T0 + 25 * 3_600_000L);
        check("recovery: the clock keeps the leftover hours (+1 at 25 h, next due 48 h from the strike)", got == 0 + 1 && owed[1] == T0 + 86_400_000L);
        reset();
    }

    /**
     * Batch 24b item 3, stage 2: Deals offers through the engine. Source: the engine path is behind Bargain.on() and moves no
     * coins; the old path is still there for flag off. Engine: Deals' own numbers (unit at 80-110% of guide, floor 92%/91% of
     * guide, part-lots) never give a total outside [min(floor, ask), ask], and nothing over the daily budget is booked.
     */
    static void stageTwoDeals(String bargainSrc) throws Exception {
        String code = new String(Files.readAllBytes(Paths.get(bargainSrc).resolveSibling("Deals.java")), StandardCharsets.UTF_8);
        int hook = code.indexOf("if (Bargain.on()) return bargain(o, h, s, n, ask, floor, bid, b.free, now);");
        int oldPath = code.indexOf("if (bid == 0 || b.free)");
        check("stage 2 source: Deals.haggle hands offers to the engine only behind Bargain.on(), old path follows for flag off", hook > 0 && oldPath > hook);
        int a = code.indexOf("static boolean bargain(Offer o"), z = code.indexOf("static boolean haggle(Offer o");
        String body = a > 0 && z > a ? code.substring(a, z) : "";
        boolean clean = !body.isEmpty();
        for (String bad : new String[]{"Wallet", "Ledger", ".give(", ".pay(", "getInventory", "getBank", "new Item"}) if (body.contains(bad)) clean = false;
        check("stage 2 source: Deals.bargain/bargainSold move no coins or items", clean);
        check("stage 2 source: both window-sale branches book through bargainSold", code.split("bargainSold\\(o, ", -1).length - 1 == 2);
        reset(); System.setProperty("bargain.on", "true");
        Random r = new Random(24);
        int bad = 0, n = 0; long given = 0, booked = 0;
        for (int i = 0; i < 400; i++) {
            String bot = "adv" + (i % 7), pl = "p" + i;
            int guide = 50 + r.nextInt(3_000), unit = Math.max(1, guide * (80 + r.nextInt(31)) / 100), qty = 1 + r.nextInt(500);
            boolean mate = r.nextBoolean();
            long ask = (long) unit * qty, floor = (long) guide * qty * (mate ? 91 : 92) / 100;
            Bargain.Session s = Bargain.sell(bot, pl, ask, floor, mate ? 6 : r.nextInt(6), false); s.units = qty;
            long last = ask;
            for (int k = 0; k < 4; k++) {
                long bid = (long) (ask * (0.3 + r.nextDouble() * 0.8));
                Bargain.Result res = Bargain.decide(s, bid, false, T0 + i * 1_000L + k);
                n++;
                if (res.total < Math.min(floor, ask) || res.total > ask) bad++;
                if (res.verdict == Bargain.Verdict.WALK) break;
                last = res.total;
                if (res.verdict == Bargain.Verdict.ACCEPT) break;
            }
            if (Bargain.session(bot, pl) == s && r.nextInt(3) > 0) { Bargain.sold(s, last, T0 + i * 1_000L + 900); booked += ask; given += ask - last; }
        }
        check("stage 2 engine: " + n + " Deals-style verdicts all in [min(floor, ask), ask]", bad == 0);
        check("stage 2 engine: honest Deals haggling never trips the exit rule (gave " + given + " on " + booked + " listed)", !Bargain.tripped);
        long cap = 0; for (long[] b : Bargain.DAY.values()) cap += Math.min(Bargain.BUDGET_CAP, (long) Math.floor(Bargain.BUDGET_PCT * b[1]));
        check("stage 2 engine: discounts within the summed daily budgets (" + given + " <= " + cap + ")", given <= cap);
        reset();
    }

    /**
     * Batch 24b item 4: soak.sh's DEALS line (harness/deals-line.sh) reads the log Bargain itself writes: a real SALE and WALK
     * pass, an appended under-floor SALE fails with exit 5, lines before the soak start are ignored. Needs bash (skipped, not
     * counted, without it); the source check that soak.sh calls it always runs.
     */
    static void dealsLine(String bargainSrc) throws Exception {
        java.nio.file.Path tests = Paths.get(bargainSrc).toAbsolutePath().getParent().getParent().getParent().resolveSibling("tests");
        java.nio.file.Path script = tests.resolve("harness/deals-line.sh"), soak = tests.resolve("soak.sh");
        String soakCode = Files.exists(soak) ? new String(Files.readAllBytes(soak), StandardCharsets.UTF_8) : "";
        check("deals line: soak.sh runs harness/deals-line.sh and exits 5 on a bad deal", soakCode.contains("harness/deals-line.sh") && soakCode.contains("DEALS_RC=5"));
        java.io.File dir = Files.createTempDirectory("bargain").toFile();
        reset(); Bargain.dataDir = dir; System.setProperty("bargain.on", "true");
        java.io.File log = new java.io.File(dir, Bargain.LOG);
        Files.write(log.toPath(), "2026-10-01T00:00:00Z SALE bot=old player=p list=10 floor=9 final=1 disc=9 tries=0 strikes=0 tier=0\n".getBytes(StandardCharsets.UTF_8));
        Bargain.Session s = Bargain.sell("Fish Monger", "Some Player", 560, 515, 0, false);
        Bargain.Result c = Bargain.decide(s, 448, false, T0);
        Bargain.sold(s, c.total, T0 + 1_000);
        Bargain.Session w = Bargain.sell("Fish Monger", "p2", 560, 515, 0, false);
        Bargain.decide(w, 10, false, T0 + 2_000); Bargain.decide(w, 10, false, T0 + 3_000);
        String[] r1 = runDeals(script, log);
        if (r1 == null) { System.out.println("[TestBargain] SKIP deals line: no bash here (the soak runs it)"); }
        else {
            check("deals line: Bargain's own SALE and WALK pass, the old line is ignored (" + r1[1] + ")", "0".equals(r1[0]) && r1[1].startsWith("DEALS PASS: sales 1, walk-aways 1,"));
            Files.write(log.toPath(), "2026-10-30T01:00:00Z SALE bot=a b player=c list=560 floor=515 final=500 disc=60 tries=1 strikes=0 tier=0\n".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
            String[] r2 = runDeals(script, log);
            check("deals line: a final under floor = DEALS FAIL, exit 5 (" + r2[1] + ")", "5".equals(r2[0]) && r2[1].startsWith("DEALS FAIL:") && r2[1].contains("bad finals (under floor or over list) 1"));
        }
        for (java.io.File f : dir.listFiles()) f.delete();
        dir.delete(); reset();
    }
    /** {exit code, first output line}, or null when bash cannot be run. */
    static String[] runDeals(java.nio.file.Path script, java.io.File log) {
        try {
            Process p = new ProcessBuilder("bash", script.toString(), "2026-10-29T00:00:00", log.getPath()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return new String[]{String.valueOf(p.waitFor()), out.split("\n")[0]};
        } catch (Exception e) { return null; }
    }

    static void buying() {
        reset();
        Bargain.Session s = Bargain.buy("bob", "p", 1_000, 9, false);
        check("buying: clamp never over guide", Bargain.clamp(s, 5_000, T0) == 1_000 && Bargain.clamp(s, -5, T0) == 0);
        Bargain.sold(s, 1_001, T0);
        check("buying: paying over guide trips the engine", Bargain.tripped);
    }

    static void afkRunner() {
        reset();
        Bargain.Session s = Bargain.sell("miner", "player1", 5_000, 0, 9, true);
        boolean ok = true;
        for (long bid : new long[]{0, 100, 2_500, 4_900, 4_999}) { Bargain.Result r = Bargain.decide(s, bid, false, T0); ok &= r.total == 5_000 && r.verdict == Bargain.Verdict.FINAL && !r.trustStrike; }
        Bargain.Result r = Bargain.decide(s, 5_000, false, T0);
        check("afk runner: no haggling, list price only", ok && r.verdict == Bargain.Verdict.ACCEPT && r.total == 5_000);
        check("afk runner: no discount booked", Bargain.budgetLeft("miner", 0, T0) == 0 && Bargain.WALKS.isEmpty());
    }

    /** Lowballs: stranger walks on the second, 15 min first hold, 60 min repeat, 6 h abuse; one trust strike per 10 min. */
    static void walkAway() {
        reset();
        Bargain.Session s = Bargain.sell("t", "p", 5_000, 0, 0, false);
        Bargain.Result a = Bargain.decide(s, 100, false, T0), b = Bargain.decide(s, 100, false, T0);
        check("walk: stranger lowball 1 = REFUSE, 2 = WALK", a.verdict == Bargain.Verdict.REFUSE && b.verdict == Bargain.Verdict.WALK && b.trustStrike);
        check("walk: first hold 15 min", b.holdUntil == T0 + 15 * 60_000L);
        s = Bargain.sell("t", "p", 5_000, 0, 0, false);
        Bargain.decide(s, 1, false, T0 + 1_000); Bargain.Result c = Bargain.decide(s, 1, false, T0 + 1_000);
        check("walk: lowball inside a hold = 6 h abuse hold, no second strike in 10 min", c.verdict == Bargain.Verdict.WALK && c.holdUntil == T0 + 1_000 + 6 * 3_600_000L && !c.trustStrike);
        reset();
        s = Bargain.sell("t", "m", 5_000, 0, 9, false);
        Bargain.Result m1 = Bargain.decide(s, 0, true, T0), m2 = Bargain.decide(s, 0, true, T0), m3 = Bargain.decide(s, 0, true, T0);
        check("walk: a mate gets REFUSE, FINAL, then WALK", m1.verdict == Bargain.Verdict.REFUSE && m2.verdict == Bargain.Verdict.FINAL && m3.verdict == Bargain.Verdict.WALK);
    }
}
