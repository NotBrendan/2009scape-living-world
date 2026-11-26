package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * W6 Runecrafting altar loop (-Dartisans.altarLoop, the design notes). Plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestRcRun <addons-src>/content/custom
 * Run it twice: bare (flag off) and with -Dartisans.altarLoop=true. Checks the pure rules in RcRun, the route table against
 * the engine's own runecrafting enums (server.jar), and the wiring read from the source (Artisans, Gates, MarketBrain cannot
 * class-init outside a world), including that the flag-off path still holds train-61's code.
 */
public final class TestRcRun {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestRcRun] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static boolean order(String src, String a, String b) { int i = src.indexOf(a), j = src.indexOf(b); return i >= 0 && j > i; }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestRcRun]   missing: " + p); return false; } return true; }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String ar = read(dir, "Artisans.java"), ga = read(dir, "Gates.java"), mb = read(dir, "MarketBrain.java"), rc = read(dir, "RcRun.java");

        // F: flag follows its property, default off; altar list default earth
        check("F flag: ON=" + RcRun.ON + " follows -Dartisans.altarLoop, altars=" + RcRun.ALTARS,
                RcRun.ON == Boolean.getBoolean("artisans.altarLoop") && RcRun.ALTARS.equals(System.getProperty("artisans.altarLoop.altars", "earth")));

        // R: route table
        int[] ess = {7936}, rune = {1436}, other = {1511};
        check("R earth rune from pure essence -> earth (default list)", RcRun.route(557, ess, "earth") != null && "earth".equals(RcRun.route(557, ess, "earth").name));
        check("R earth rune from rune essence -> earth", RcRun.route(557, rune, "earth") != null);
        check("R air rune with list 'earth' -> null (workshop)", RcRun.route(556, ess, "earth") == null);
        check("R air/fire with list 'earth, AIR,fire' -> rows", RcRun.route(556, ess, "earth, AIR,fire") != null && RcRun.route(554, ess, "earth, AIR,fire") != null);
        check("R mind/body unlisted (W8 rows), nature (no row) -> null", RcRun.route(558, ess, "air,earth,fire") == null && RcRun.route(559, ess, "earth") == null && RcRun.route(561, ess, "air,earth,fire,mind,body,nature") == null); // W8: mind/body have rows now; unlisted they stay on the workshop path
        check("R non-essence input or none -> null", RcRun.route(557, other, "earth") == null && RcRun.route(557, new int[0], "earth") == null && RcRun.route(557, null, "earth") == null);
        check("R empty/null list -> null", RcRun.route(557, ess, "") == null && RcRun.route(557, ess, null) == null);

        // E: the table matches the engine (content.global.skill.runecrafting, server.jar)
        boolean eng = true;
        for (RcRun.Route r : RcRun.ROUTES) {
            String n = r.name.toUpperCase();
            content.global.skill.runecrafting.MysteriousRuin ruin = content.global.skill.runecrafting.MysteriousRuin.valueOf(n);
            content.global.skill.runecrafting.Altar alt = content.global.skill.runecrafting.Altar.valueOf(n);
            content.global.skill.runecrafting.Rune ru = content.global.skill.runecrafting.Rune.valueOf(n);
            boolean ok = ruin.getBase().getX() == r.ruinX && ruin.getBase().getY() == r.ruinY && ruin.getEnd().getX() == r.endX && ruin.getEnd().getY() == r.endY
                    && alt.getObject() == r.altarObj && alt.getPortal() == r.portalObj && ru.getRune().getId() == r.rune && ru.getExperience() == r.xp
                    && ruin.getTalisman().getTalisman().getId() == r.talisman && ruin.getTiara().getTiara().getId() == r.tiara;
            if (!ok) { eng = false; System.out.println("[TestRcRun]   engine mismatch: " + r.name); }
        }
        check("E route table = engine ruins (base, landing), altar/portal objects, talisman, tiara, rune id and xp for air/earth/fire", eng);

        // N: offline nav check recorded (p38-navport.py on nav-p0.bin): every route clean both ways; earth starts at the workshop bank
        boolean nav = true; for (RcRun.Route r : RcRun.ROUTES) { int lim = r.w6 ? 200 : 250; if (r.navOut <= 0 || r.navBack <= 0 || r.navOut > lim || r.navBack > lim) nav = false; } // W8 rows (mind 216/212) under 250
        RcRun.Route earth = RcRun.route(557, ess, "earth");
        check("N routes checked offline (bank<->ruins under 200 tiles each way); earth's bank is the runecrafter's workshop 3253,3420",
                nav && earth.bankX == 3253 && earth.bankY == 3420 && ar.contains("RUNECRAFTING(\"runecrafter\", 791, new Location[]{Location.create(3253, 3420, 0)}"));
        int[] room = earth.room();
        check("N altar room box holds the landing and is clear of the overworld (y 4800+)", room[0] <= earth.endX && earth.endX <= room[2] && room[1] <= earth.endY && earth.endY <= room[3] && room[1] >= 4800);

        // K: key rules (engine: tiara worn or talisman used/carried)
        check("K keyOk: tiara worn or talisman carried", RcRun.keyOk(true, false) && RcRun.keyOk(false, true) && !RcRun.keyOk(false, false));
        check("K keySource order: own > bank > stock talisman > stock tiara (head free) > none",
                "own".equals(RcRun.keySource(true, true, 5, 5, true, true)) && "bank".equals(RcRun.keySource(false, true, 5, 5, true, true))
                        && "stock-talisman".equals(RcRun.keySource(false, false, 1, 5, true, true)) && "stock-tiara".equals(RcRun.keySource(false, false, 0, 1, true, true))
                        && RcRun.keySource(false, false, 0, 1, false, true) == null && RcRun.keySource(false, false, 0, 0, true, true) == null
                        && RcRun.keySource(false, true, 3, 0, true, false) == null);

        // S: stall and park rules
        long t = 10_000_000L;
        check("S stalled: over the leg limit or 30 s still; not when moving inside the limit",
                RcRun.stalled(t + 241_000, t, t + 240_000, RcRun.WALK_LIMIT) && RcRun.stalled(t + 31_000, t, t, RcRun.WALK_LIMIT) && !RcRun.stalled(t + 100_000, t, t + 90_000, RcRun.WALK_LIMIT));
        long[] ring = new long[RcRun.PARK_STALLS];
        boolean p1 = RcRun.noteStall(ring, t), p2 = RcRun.noteStall(ring, t + 600_000);
        long[] ring2 = new long[RcRun.PARK_STALLS];
        boolean q1 = RcRun.noteStall(ring2, t), q2 = RcRun.noteStall(ring2, t + RcRun.PARK_WINDOW + 1);
        check("S park: 2 stalls inside an hour park the altar; 2 stalls an hour apart do not", !p1 && p2 && !q1 && !q2);
        RcRun.Route pr = RcRun.ROUTES[0]; pr.parkedUntil = t + 5; boolean pk = RcRun.parked(pr, t) && !RcRun.parked(pr, t + 5); pr.parkedUntil = 0;
        check("S parked until parkedUntil", pk);

        // X: xp as the engine per essence, multiplier 1; supply = the recipe's amount (no multiplier)
        check("X xp: 28 essence at earth = 182.0, air = 140.0, fire = 196.0", RcRun.xp(earth, 28) == 182.0 && RcRun.xp(RcRun.route(556, ess, "air"), 28) == 140.0 && RcRun.xp(RcRun.route(554, ess, "fire"), 28) == 196.0);
        check("X supply is the recipe amount x times (no engine multiplier), sold through Deals.settleOwed + Ledger.supply",
                all(rc, "int n = plan.r.amount * plan.times;", "Deals.settleOwed(bot, plan.r.product, n)", "Ledger.supply(bot, plan.r.product, forSale)") && !rc.contains("getMultiplier"));

        // C: economy: the key is bought from bot stock (pays sellers) or comes from the bank; a key that cannot be placed goes back on sale
        check("C key from Ledger.consume or the bank, unplaced key -> Ledger.supply; no Item minted into the pack outside those paths",
                all(rc, "Ledger.consume(p, id, 1, \"rc-key\")", "if (!placed) { Ledger.supply(p, id, 1); return false; }", "p.getBank().remove(t)"));

        // W: wiring, flag off = train-61
        check("W Artisans: rc field, tick hook (returns only when rc != null), start only under RcRun.ON && RUNECRAFTING, workshop count under RcRun.ON",
                all(ar, "RcRun rc;", "if (rc != null) { int rr = rc.tick(this, now);", "if (RcRun.ON && trade == Trade.RUNECRAFTING) rc = RcRun.start(this, plan, now);",
                        "if (RcRun.ON && trade == Trade.RUNECRAFTING) RcRun.workshopRunes += plan.r.amount * plan.times;"));
        check("W Artisans: the workshop path is unchanged (home pick, WALK, WORK put-on-GE line, settleForShutdown)",
                all(ar, "home = trade.spots[RNG.nextInt(trade.spots.length)];\r\n                    walkStart = now;\r\n                    step = Step.WALK;",
                        "System.out.println(\"[Artisans] \" + bot.getUsername() + \" put \" + plan.r.amount * plan.times + \" x \" + plan.r.name + \" on the GE\");",
                        "if (a.bot == null || a.plan == null || (a.step != Step.WALK && a.step != Step.WORK)) continue;"));
        check("W RcRun draws no Artisans.RNG / RNG at all (sims seeded: flag on keeps the artisans' draw order)", !rc.contains("RNG."));
        check("W Gates: altar room gates only under RcRun.ON, rule rcRuins", all(ga, "if (RcRun.ON) for (RcRun.Route r : RcRun.ROUTES)", "static Rule rcRuins(int talisman, int tiara, String name)"));
        check("W MarketBrain: [RC] hourly line on its own timer only under RcRun.ON", all(mb, "if (RcRun.ON) TIMER.schedule(new TimerTask() { @Override public void run() { try { System.out.println(RcRun.line()); }"));
        check("W log lines: crafted, stall/fallback, parked, hourly", all(rc, "\"[RC] crafted \" + n + \" at \" + r.name + \" altar", "; fallback to the workshop\"", "altar parked for ", "[RC] hourly on=[altarLoop]")
                && RcRun.line().startsWith("[RC] hourly on=[altarLoop] altars="));

        // t62 review fix 2 (results/t62-audit.md item 5): the walk back gives up
        boolean ba = RcRun.backAction(5_000, 0) == RcRun.BACK_WAIT && RcRun.backAction(10_001, 0) == RcRun.BACK_RETRY_PORTAL
                && RcRun.backAction(10_001, RcRun.BACK_TRIES - 1) == RcRun.BACK_RETRY_PORTAL && RcRun.backAction(10_001, RcRun.BACK_TRIES) == RcRun.BACK_GIVE_UP
                && RcRun.backAction(9_000, RcRun.BACK_TRIES) == RcRun.BACK_WAIT;
        check("t62 fix 2 rule: back leg waits 10 s, retries the portal " + RcRun.BACK_TRIES + " times, then gives up " + ba, ba);
        check("t62 fix 2 wiring: still in the altar room after the tries -> stall line, park rule, set at the bank, key banked, DONE; no retry every tick",
                all(rc, "int act = backAction(now - legStart, backTries);", "if (act == BACK_RETRY_PORTAL) { backTries++; legStart = now; lastMove = now; if (!Seed.RC_STICK) bot.getProperties().setTeleportLocation(r.ruin()); }",
                        "returnStalls++; backGiveUps++;", "portal tries (runes already sold); set at the bank", "try { Spectacle.teleport(bot, r.bank()); }")
                        && rc.indexOf("if (noteStall(r.stallAt, now)) { r.parkedUntil = now + PARK_FOR; parks++;", rc.indexOf("returnStalls++; backGiveUps++;")) > rc.indexOf("returnStalls++; backGiveUps++;")
                        && !rc.contains("if (now - legStart > 10_000) bot.getProperties().setTeleportLocation(r.ruin()); return RUNNING;")
                        && RcRun.line().contains(" backGiveUps="));

        System.out.println("[TestRcRun] " + pass + " passed, " + fail + " failed (altarLoop " + (RcRun.ON ? "ON" : "off") + ")");
        if (fail > 0) System.exit(1);
    }
}
