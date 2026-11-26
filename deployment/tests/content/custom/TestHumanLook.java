package content.custom;

import core.api.StartupListener;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Trial only (-Dtest.humanlook=true; optional -Dhumanlook.min=25 sampling minutes): P31 human-look-1, fixes 1 (names without the
 * counter tail) and 3 (tile spread and idle emotes on breaks). It measures the same numbers as the P31 step 1/1b baseline
 * (the design notes), so one build runs as control (-Dbots.humanNames=false -Dbots.humanLook=false) and as
 * the build (defaults), and the two are compared:
 *   names: share of online bot names ending in a digit, share of the Aa999 shape (one capital, lowercase, 3 digits), duplicates;
 *   breaks: bots on a break within 8 tiles of their hangout, how many share a tile, the most on one tile;
 *   emotes: break emotes and face-turns per break-bot-minute;
 *   hubs: bots and real players within 20 tiles of the 10 roadmap section 3 hubs (the step 1b hub table), mean per sample.
 * Unit checks first (U1-U4, judged with bots.humanNames on): pickHumanName over 2000 stock names, a taken name, a "direct" profile
 * restored under exactly its name and saved with "direct":true. U6/U7 (train-3 audit finding 2, NameGuard; judged both ways, the
 * expected answer follows -Dbots.nameGuard): a "direct" profile whose name is a real player's save, and a live bot whose name the
 * server's index now resolves to a "real" player (a TEST_HUMANS stand-in put in Repository.playerNames). Live checks N1 (names) and T1/E1 (breaks) are judged only when
 * their flag is on; with a flag off they print INFO (the control numbers). Prints "[TestHumanLook] sample ...", RESULT ...,
 * summary, DONE. Suggested: -Dsmartbots.count=40 -Dtraders.fast=true (16 new bots on the 24-profile pristine book; 20 s breaks).
 * Add -Dtest.look.breakSoon=true (test only) so every trip ends in a 3-5 min break: with 20 s breaks T1/E1 got no break-bot samples.
 */
public final class TestHumanLook implements StartupListener {
    static void log(String s) { System.out.println("[TestHumanLook] " + s); }
    static int fails;
    static void result(String name, boolean judged, boolean pass, String what) {
        if (!judged) { log("INFO " + name + ": " + what); return; }
        if (!pass) fails++;
        log("RESULT " + name + " " + (pass ? "PASS" : "FAIL") + ": " + what);
    }

    /** Roadmap section 3 hubs, the tiles the step 1b table used (HANGOUTS, plus the Exchange). */
    static final Object[][] HUBS = {
            {"Lumbridge", 3222, 3219}, {"GE", 3165, 3487}, {"Varrock", 3212, 3429}, {"Draynor", 3082, 3249}, {"Barbarian", 3082, 3417},
            {"Falador", 2995, 3378}, {"AlKharid", 3293, 3175}, {"PortSarim", 3043, 3236}, {"Edgeville", 3085, 3500}, {"Catherby", 2843, 3432}};
    static final String DIRECT_TEST = "Quietfern";

    @Override public void startup() {
        if (!Boolean.getBoolean("test.humanlook")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 60) return false; // SmartAdventurers.startup has loaded the book by now
                new Thread(TestHumanLook::run, "TestHumanLook").start();
                return true;
            }
        });
    }

    static void run() {
        try { units(); live(); } catch (Throwable e) { fails++; log("error " + e); e.printStackTrace(System.out); }
        log("summary: " + (fails == 0 ? "PASS" : fails + " FAIL") + " (humanNames " + SmartAdventurers.HUMAN_NAMES + ", humanLook " + SmartAdventurers.Brain.HUMAN_LOOK + ", breakSoon " + SmartAdventurers.Brain.BREAK_SOON + ")");
        log("DONE");
    }

    static boolean endsInDigit(String n) { return n != null && !n.isEmpty() && Character.isDigit(n.charAt(n.length() - 1)); }
    static boolean aa999(String n) { return n != null && n.matches("[A-Z][a-z]+\\d{3}"); }
    static String pct(int a, int b) { return b == 0 ? "-" : String.format(Locale.ROOT, "%d/%d (%.0f%%)", a, b, 100.0 * a / b); }

    /** U5 (P38 L11 casual chat): flag off = no casual lines and the old 1-2 tick wait; flag on = casual shapes appear, every wait >= 2 s;
     *  a Deals price line is never changed, on or off. Pure helpers, so no bot or flag state is needed. */
    static void casualUnit() {
        Random r = new Random(38);
        String[] plain = {"Hello there, Bob. Welcome to Lumbridge.", "Aye. Hello.", "Hi Bob! How's the adventuring?", "The bank is just north of here."};
        String deal = "Selling 40 oak logs at 112 each, under guide. Trade me.";
        String hag = "Fine. 1,250 gp or nothing.";
        int offChanged = 0, onChanged = 0, shortN = 0, lowerN = 0, dealChanged = 0, minOn = 99, offMin = 99, offMax = 0, n = 2000;
        for (int i = 0; i < n; i++) {
            String line = plain[i % plain.length];
            for (BotChat.Persona.Style st : BotChat.Persona.Style.values()) {
                if (!line.equals(BotChat.casual(line, st, false, r))) offChanged++;
                String o = BotChat.casual(line, st, true, r);
                if (!line.equals(o)) { onChanged++; String sh = BotChat.shape(line, o); if (sh.equals("short")) shortN++; else if (sh.equals("lower")) lowerN++; }
                if (!deal.equals(BotChat.casual(deal, st, true, r)) || !deal.equals(BotChat.casual(deal, st, false, r))
                        || !hag.equals(BotChat.casual(hag, st, true, r))) dealChanged++;
            }
            int off = BotChat.replyTicks(false, line.length(), r); offMin = Math.min(offMin, off); offMax = Math.max(offMax, off);
            minOn = Math.min(minOn, BotChat.replyTicks(true, i % 3 == 0 ? 0 : line.length() * 3, r));
        }
        int total = n * BotChat.Persona.Style.values().length;
        result("U5", true, offChanged == 0 && offMin >= 1 && offMax <= 2 && onChanged * 100 >= total * 40 && shortN > 0 && lowerN > 0
                && minOn * 600 >= 2000 && dealChanged == 0 && !BotChat.CASUAL == !Boolean.getBoolean("bots.casualChat"),
                "flag off changed " + offChanged + ", wait " + offMin + "-" + offMax + " ticks; flag on changed " + pct(onChanged, total) + " (lower " + lowerN
                        + ", short " + shortN + "), shortest wait " + minOn * 600 + " ms; Deals/Haggle price lines changed " + dealChanged);
    }

    // ------------------------------------------------------------------ unit checks
    static void units() {
        boolean on = SmartAdventurers.HUMAN_NAMES;
        // U1: 2000 stock names through pickHumanName (nothing taken): digit share near the stock file's 16%, no counter shape
        List<String> stock = new ArrayList<>();
        try { for (String l : java.nio.file.Files.readAllLines(new java.io.File(core.ServerConstants.BOT_DATA_PATH, "botnames.txt").toPath())) if (!l.trim().isEmpty()) stock.add(l.trim()); } catch (Throwable t) { log("botnames.txt: " + t); }
        Random r = new Random(31);
        int n = 0, kept = 0, digits = 0, shape = 0, bad = 0;
        for (int i = 0; i < 2000 && !stock.isEmpty(); i++) {
            String got = SmartAdventurers.pickHumanName(stock.get(r.nextInt(stock.size())), x -> false, r);
            if (got == null) { kept++; continue; }
            n++; if (endsInDigit(got)) digits++; if (aa999(got)) shape++; if (got.length() > 12 || got.contains("_")) bad++;
        }
        double dPct = n == 0 ? 0 : 100.0 * digits / n;
        result("U1", on, n >= 1500 && dPct >= 8 && dPct <= 25 && bad == 0 && shape * 100 <= n * 3,
                "stock " + stock.size() + ", named " + n + " of 2000 (would keep the old name " + kept + "), ending in a digit " + String.format(Locale.ROOT, "%.1f", dPct) + "% (stock 16%, want 8-25), Aa999 " + shape + ", over 12 chars or '_' " + bad);
        // U2: a taken bare name gets digits and is not the taken one
        String t2 = SmartAdventurers.pickHumanName("Kabal", x -> x.equals("kabal"), new Random(5));
        result("U2", on, t2 != null && !t2.equalsIgnoreCase("Kabal") && endsInDigit(t2) && t2.length() <= 12, "Kabal taken -> " + t2);
        casualUnit(); // U5 (P38 L11), judged whatever the flags are: the pure helpers take the flag as an argument
        nameGuardUnits(); // U6/U7 (train-3 audit finding 2), judged both ways
        // U3/U4: a "direct" profile is restored under exactly its name and saved with "direct":true
        if (!on) { log("INFO U3/U4: skipped (bots.humanNames=false)"); return; }
        SmartAdventurers.Profile p = new SmartAdventurers.Profile();
        p.name = DIRECT_TEST; p.direct = true; p.coins = 500; p.home = 0; p.fav = -1;
        for (int i = 0; i < 24; i++) p.levels[i] = 1;
        p.levels[0] = p.levels[1] = p.levels[2] = p.levels[3] = 20;
        p.gear.add(1277); // a bronze sword: worn, nothing bought, so the coins stay exactly as saved
        int bots0 = SmartAdventurers.BOTS.size();
        Boolean ok = TestB19CUtil.onGame(() -> SmartAdventurers.restoreSafe(p, SmartAdventurers.LUMBRIDGE, "test", 0));
        Player up = null; for (Player b : SmartAdventurers.BOTS) if (b != null && b.isActive() && DIRECT_TEST.equals(b.getUsername())) up = b;
        final Player upF = up;
        Player found = TestB19CUtil.onGame(() -> Ledger.Wallet.findOnline(DIRECT_TEST.toLowerCase(Locale.ROOT)));
        long coins = up == null ? -1 : TestB19CUtil.coins(up);
        result("U3", on, Boolean.TRUE.equals(ok) && up != null && found == upF && SmartAdventurers.BOTS.size() >= bots0 + 1 && coins == 500, // >=: fresh spawns keep coming meanwhile
                "restoreSafe " + ok + ", up as " + (up == null ? "-" : up.getUsername()) + ", findOnline same bot " + (found != null && found == upF) + ", BOTS " + bots0 + " -> " + SmartAdventurers.BOTS.size() + ", coins " + coins + " (expect 500)");
        JSONArray arr = TestB19CUtil.onGame(SmartAdventurers::buildProfiles);
        Object direct = null; if (arr != null) for (Object o : arr) if (o instanceof JSONObject && DIRECT_TEST.equals(((JSONObject) o).get("name"))) direct = ((JSONObject) o).get("direct");
        JSONObject off = SmartAdventurers.savedJson(p);
        result("U4", on, Boolean.TRUE.equals(direct) && Boolean.TRUE.equals(off.get("direct")), "buildProfiles direct=" + direct + ", savedJson (logged-out form) direct=" + off.get("direct"));
    }

    // ------------------------------------------------------------------ train-3 audit finding 2 (NameGuard)
    static final String GUARD_SAVE = "Quietmoss", GUARD_CLASH = "Quietreed";
    static final int GUARD_MARK = 9; // fletching: a level no test bot has, to find the profile again

    /** A "direct" profile like U3's (coins 500, a bronze sword, combat 20), fletching 77 as the marker. */
    static SmartAdventurers.Profile directProfile(String name) {
        SmartAdventurers.Profile p = new SmartAdventurers.Profile();
        p.name = name; p.direct = true; p.coins = 500; p.home = 0; p.fav = -1;
        for (int i = 0; i < 24; i++) p.levels[i] = 1;
        p.levels[0] = p.levels[1] = p.levels[2] = p.levels[3] = 20; p.levels[GUARD_MARK] = 77;
        p.gear.add(1277);
        return p;
    }

    /** U6: a direct profile whose name is a real player's save. U7: a live bot whose name the server's index resolves to a stand-in human. Expected answers follow the flag. */
    static void nameGuardUnits() {
        boolean g = NameGuard.ON;
        // U6: on = restored under another name (blockedRestores +1, nobody online as GUARD_SAVE); off = under that name, as before
        java.io.File dir = new java.io.File(core.ServerConstants.PLAYER_SAVE_PATH), save = new java.io.File(dir, GUARD_SAVE.toLowerCase(Locale.ROOT) + ".json");
        boolean made = false;
        try { dir.mkdirs(); made = save.createNewFile(); } catch (Throwable t) { log("U6 could not make " + save + ": " + t); }
        boolean isSave = Ledger.isPlayerSave(GUARD_SAVE.toLowerCase(Locale.ROOT));
        int blocked0 = NameGuard.blockedRestores;
        SmartAdventurers.Profile p6 = directProfile(GUARD_SAVE);
        Boolean ok6 = TestB19CUtil.onGame(() -> SmartAdventurers.restoreSafe(p6, SmartAdventurers.LUMBRIDGE, "test", 0));
        Player as6 = TestB19CUtil.onGame(() -> Ledger.Wallet.findOnline(GUARD_SAVE.toLowerCase(Locale.ROOT)));
        Player marked6 = null; for (Player b : SmartAdventurers.BOTS) if (b != null && b.isActive() && b.getSkills().getStaticLevel(GUARD_MARK) == 77) marked6 = b;
        if (made) save.delete();
        int blocked = NameGuard.blockedRestores - blocked0;
        boolean pass6 = Boolean.TRUE.equals(ok6) && isSave && marked6 != null && (g ? as6 == null && blocked == 1 && !GUARD_SAVE.equalsIgnoreCase(marked6.getUsername()) : as6 != null && as6 == marked6 && blocked == 0);
        result("U6", true, pass6, "guard " + g + ": save file " + isSave + ", restoreSafe " + ok6 + ", online as " + GUARD_SAVE + " " + (as6 != null) + " (expect " + !g + "), the profile's bot is " + (marked6 == null ? "missing" : marked6.getUsername()) + ", blocked restores " + blocked + " (expect " + (g ? 1 : 0) + ")");
        // U7: on = within 12 s the bot is logged out (no Brain under the name), its OFFLINE profile carries a new name, direct, coins 500, the 77 gp owed under the old name moved to the new one; off = it stays, nothing moves
        String key7 = GUARD_CLASH.toLowerCase(Locale.ROOT);
        SmartAdventurers.Profile p7 = directProfile(GUARD_CLASH);
        Boolean ok7 = TestB19CUtil.onGame(() -> SmartAdventurers.restoreSafe(p7, SmartAdventurers.LUMBRIDGE, "test", 0));
        Player bot7 = TestB19CUtil.onGame(() -> Ledger.Wallet.findOnline(key7));
        core.game.bots.AIPlayer standIn = TestB19CUtil.onGame(() -> TestCR1.bot(SmartAdventurers.LUMBRIDGE.transform(3, 3, 0), 30, 20, 20, 20));
        int clashes0 = NameGuard.clashes;
        TestB19CUtil.game(() -> { Companions.TEST_HUMANS.add(standIn.getUsername().toLowerCase(Locale.ROOT)); Repository.getPlayerNames().put(key7, standIn); Ledger.Wallet.OFFLINE.put(key7, 77L); });
        try { Thread.sleep(12_000); } catch (InterruptedException ignored) { }
        Boolean brain7 = TestB19CUtil.onGame(() -> SmartAdventurers.BRAINS.containsKey(key7));
        SmartAdventurers.Profile off7 = null; for (SmartAdventurers.Profile q : SmartAdventurers.OFFLINE) if (q != null && q.levels[GUARD_MARK] == 77) off7 = q;
        Long owedOld = Ledger.Wallet.OFFLINE.get(key7), owedNew = off7 == null || off7.name == null ? null : Ledger.Wallet.OFFLINE.get(off7.name.toLowerCase(Locale.ROOT));
        int clashes = NameGuard.clashes - clashes0;
        final SmartAdventurers.Profile off7F = off7;
        TestB19CUtil.game(() -> { Repository.getPlayerNames().remove(key7); Companions.TEST_HUMANS.remove(standIn.getUsername().toLowerCase(Locale.ROOT)); Ledger.Wallet.OFFLINE.remove(key7); if (off7F != null && off7F.name != null) Ledger.Wallet.OFFLINE.remove(off7F.name.toLowerCase(Locale.ROOT)); try { standIn.clear(); } catch (Throwable ignored) { } });
        boolean pass7 = Boolean.TRUE.equals(ok7) && bot7 != null && (g
                ? Boolean.FALSE.equals(brain7) && off7 != null && off7.direct && off7.coins == 500 && off7.name != null && !off7.name.equalsIgnoreCase(GUARD_CLASH) && clashes == 1 && owedOld == null && owedNew != null && owedNew == 77L
                : Boolean.TRUE.equals(brain7) && off7 == null && clashes == 0 && owedOld != null && owedOld == 77L);
        result("U7", true, pass7, "guard " + g + ": restoreSafe " + ok7 + ", up " + (bot7 != null) + "; after the stand-in took the name: Brain still there " + brain7 + " (expect " + !g + "), offline profile " + (off7 == null ? "none" : off7.name + " direct " + off7.direct + " coins " + off7.coins)
                + ", clashes " + clashes + " (expect " + (g ? 1 : 0) + "), owed under the old name " + owedOld + ", under the new " + owedNew);
    }

    // ------------------------------------------------------------------ live sampling (one sample a minute, game thread)
    static final class Sample { int bots, breakBots, shared, maxTile; int[] hubBots = new int[HUBS.length], hubPlayers = new int[HUBS.length]; }

    static Sample sample() {
        Sample s = new Sample();
        long now = System.currentTimeMillis();
        List<Location> breakAt = new ArrayList<>();
        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) {
            if (b.bot == null || !b.bot.isActive()) continue;
            s.bots++;
            Location l = b.bot.getLocation();
            if (now < b.idleUntil && b.loiterAt != null && l.getZ() == b.loiterAt.getZ() && l.getDistance(b.loiterAt) <= 8) { s.breakBots++; breakAt.add(l); }
        }
        Map<String, Integer> tiles = new HashMap<>();
        for (Player p : Repository.getPlayers()) { // anyone on a break bot's tile counts, bot or not
            if (p == null || !p.isActive()) continue;
            Location l = p.getLocation();
            tiles.merge(l.getX() + "," + l.getY() + "," + l.getZ(), 1, Integer::sum);
            if (l.getZ() != 0) continue;
            for (int h = 0; h < HUBS.length; h++) {
                if (Math.max(Math.abs(l.getX() - (Integer) HUBS[h][1]), Math.abs(l.getY() - (Integer) HUBS[h][2])) > 20) continue;
                if (p.isArtificial()) s.hubBots[h]++; else s.hubPlayers[h]++;
            }
        }
        for (Location l : breakAt) { int c = tiles.getOrDefault(l.getX() + "," + l.getY() + "," + l.getZ(), 1); if (c > 1) s.shared++; s.maxTile = Math.max(s.maxTile, c); }
        return s;
    }

    static void live() {
        int minutes = Integer.getInteger("humanlook.min", 25);
        int e0 = SmartAdventurers.Brain.breakEmotes, f0 = SmartAdventurers.Brain.breakFaces, u0 = SmartAdventurers.Brain.unstacks;
        long breakBotSamples = 0, sharedSum = 0; int maxTile = 0, samples = 0;
        long[] hubSum = new long[HUBS.length], hubPl = new long[HUBS.length];
        for (int m = 1; m <= minutes; m++) {
            TestB19CUtil.sleep(60_000);
            Sample s = TestB19CUtil.onGame(TestHumanLook::sample);
            if (s == null) continue;
            samples++; breakBotSamples += s.breakBots; sharedSum += s.shared; maxTile = Math.max(maxTile, s.maxTile);
            StringBuilder hubs = new StringBuilder();
            for (int h = 0; h < HUBS.length; h++) { hubSum[h] += s.hubBots[h]; hubPl[h] += s.hubPlayers[h]; hubs.append(' ').append(HUBS[h][0]).append(':').append(s.hubBots[h]); if (s.hubPlayers[h] > 0) hubs.append('+').append(s.hubPlayers[h]).append('p'); }
            log("sample t=" + m + "m bots=" + s.bots + " breaks=" + s.breakBots + " sharedTile=" + s.shared + " maxPerTile=" + s.maxTile + " hubs" + hubs);
        }
        boolean namesOn = SmartAdventurers.HUMAN_NAMES, lookOn = SmartAdventurers.Brain.HUMAN_LOOK;
        // names: every smart adventurer online now (the U3 test bot left out)
        List<String> names = new ArrayList<>(); Set<String> seen = new HashSet<>(); int dup = 0;
        for (Player b : SmartAdventurers.BOTS) if (b != null && b.isActive() && !DIRECT_TEST.equals(b.getUsername())) { names.add(b.getUsername()); if (!seen.add(b.getUsername().toLowerCase(Locale.ROOT))) dup++; }
        int dig = 0, shape = 0, newN = 0, newDig = 0;
        for (String n : names) {
            if (endsInDigit(n)) dig++; if (aa999(n)) shape++;
            if (SmartAdventurers.DIRECT_NAMES.contains(n.toLowerCase(Locale.ROOT))) { newN++; if (endsInDigit(n)) newDig++; }
        }
        result("N1", namesOn, dup == 0 && newN > 0 && newDig * 100 <= newN * 25,
                "online bots " + names.size() + ": ending in a digit " + pct(dig, names.size()) + ", Aa999 " + pct(shape, names.size()) + ", duplicates " + dup
                        + "; new names this boot " + SmartAdventurers.humanNamed + " (with digits " + SmartAdventurers.humanNamedDigits + ", old name kept " + SmartAdventurers.humanNameKept + "), direct names online " + newN + ", ending in a digit " + pct(newDig, newN)
                        + " (live baseline: 102 of 102 end in digits, 93 Aa999; saved bots keep their names)");
        double sharedPct = breakBotSamples == 0 ? 0 : 100.0 * sharedSum / breakBotSamples;
        result("T1", lookOn, breakBotSamples >= 10 && maxTile <= 2 && sharedPct <= 10,
                "break-bot samples " + breakBotSamples + " over " + samples + " samples: on a shared tile " + String.format(Locale.ROOT, "%.1f", sharedPct) + "%, most on one tile " + maxTile + ", unstack steps " + (SmartAdventurers.Brain.unstacks - u0) + " (want at most 2 per tile, shared at most 10%)");
        int em = SmartAdventurers.Brain.breakEmotes - e0, fa = SmartAdventurers.Brain.breakFaces - f0;
        double perMin = breakBotSamples == 0 ? 0 : (double) em / breakBotSamples; // one sample a minute: break-bot samples = break-bot-minutes
        result("E1", lookOn, breakBotSamples >= 10 && em > 0 && perMin <= 1.5,
                "break emotes " + em + ", face-turns " + fa + " over about " + breakBotSamples + " break-bot-minutes = " + String.format(Locale.ROOT, "%.2f", perMin) + " per break-minute (want some, at most 1.5)");
        StringBuilder hubs = new StringBuilder("hub means (bots within 20 tiles per sample; +p = real players):");
        for (int h = 0; h < HUBS.length; h++) hubs.append(' ').append(HUBS[h][0]).append('=').append(samples == 0 ? "-" : String.format(Locale.ROOT, "%.1f", (double) hubSum[h] / samples)).append(hubPl[h] > 0 ? String.format(Locale.ROOT, "+%.1fp", (double) hubPl[h] / samples) : "");
        log(hubs.toString());
    }
}
