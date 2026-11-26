package content.custom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * W8 wave 4 lane B (the design notes section 4), plain java (no world):
 *   java -cp "<build>;server.jar" content.custom.TestW8Wave4b <addons-src>/content/custom
 * Run it twice: bare (every flag off) and with every W8 flag set. Pure rules are checked directly; the wiring is read from
 * the source (each hook sits behind its own flag). The in-world checks are in the design notes.
 */
public final class TestW8Wave4b {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW8Wave4b] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestW8Wave4b]   missing: " + p); return false; } return true; }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        String at = read(dir, "AfkTasks.java"), b29 = read(dir, "Batch29.java"), mb = read(dir, "MarketBrain.java");

        // ---------------------------------------------------------------- 1 Smithing: bots.smith.own
        check("S0 flag: SmithOwn.ON follows -Dbots.smith.own (" + SmithOwn.ON + ")", SmithOwn.ON == Boolean.getBoolean("bots.smith.own"));
        check("S1 demand: under half the target and not surplus -> yes; at half, surplus or no target -> no",
                SmithOwn.demandOk(10, 40, false, 0.5) && !SmithOwn.demandOk(20, 40, false, 0.5) && !SmithOwn.demandOk(0, 40, true, 0.5) && !SmithOwn.demandOk(0, 0, false, 0.5));
        // rows {bar, level, product, bars}: iron dagger 15, iron platebody 33 (5 bars), iron 2h 29 (3), bronze platebody 18
        int[][] rows = {{2351, 15, 1203, 1}, {2351, 33, 1115, 5}, {2351, 29, 1309, 3}, {2349, 18, 1117, 5}};
        check("S2 pick: Smithing 40 iron -> platebody (highest level)", SmithOwn.pick(rows, 2351, 40, id -> true) == 1);
        check("S3 pick: Smithing 30 iron -> 2h (platebody over level)", SmithOwn.pick(rows, 2351, 30, id -> true) == 2);
        check("S4 pick: demand refuses the platebody -> the next best (2h)", SmithOwn.pick(rows, 2351, 40, id -> id != 1115) == 2);
        check("S5 pick: nothing wanted -> -1; wrong bar ignored", SmithOwn.pick(rows, 2351, 40, id -> false) == -1 && SmithOwn.pick(rows, 2349, 17, id -> true) == -1);
        check("S6 quest items (dart tips, claws) never picked", SmithOwn.questItem("Iron dart tip") && SmithOwn.questItem("Mithril claws") && !SmithOwn.questItem("Iron platebody"));
        boolean c1 = SmithOwn.capOk(1_000L, SmithOwn.PER_HOUR), c2 = !SmithOwn.capOk(1_000L, SmithOwn.PER_HOUR + 1);
        SmithOwn.capUse(SmithOwn.PER_HOUR); boolean c3 = !SmithOwn.capOk(2_000L, 1), c4 = SmithOwn.capOk(3_700_000L, 1);
        check("S7 hourly cap: " + SmithOwn.PER_HOUR + " items an hour, the window resets", c1 && c2 && c3 && c4);
        check("S8 wiring: Batch29 apply/done and the Runner anvil step behind SmithOwn.ON; makeOne still the default",
                all(b29, "if (SmithOwn.ON && job.chain == SMELT) SmithOwn.apply(r, r.p, job.task, spotAt);", "if (SmithOwn.ON) SmithOwn.done(r);")
                && all(at, "if (SmithOwn.ON && pr == Process.SMITH && SmithOwn.pulse(this, r)) { wait = 2; return; }", "case SMITH: case FLETCH: makeOne(r); return;"));
        check("S9 counter line only with a W8 flag on", all(mb, "if (W8Lines.anyOn()) W8Lines.schedule(TIMER, FAST);") && SmithOwn.line().startsWith("[SmithOwn] on=")
                && W8Lines.anyOn() == (SmithOwn.ON || MineGuild.ON || Hunter.ON || SlayerOnly.ONLY1));

        // ---------------------------------------------------------------- 2 Mining: bots.mine.guild
        String sa = read(dir, "SmartAdventurers.java");
        check("M0 flag: MineGuild.ON follows -Dbots.mine.guild (" + MineGuild.ON + ")", MineGuild.ON == Boolean.getBoolean("bots.mine.guild"));
        check("M1 guild box = Gates' mining-guild box; ladder foot and spot inside, ladder top on the surface",
                MineGuild.inGuild(MineGuild.FOOT[0], MineGuild.FOOT[1]) && MineGuild.inGuild(MineGuild.SPOT[0], MineGuild.SPOT[1]) && !MineGuild.inGuild(MineGuild.TOP[0], MineGuild.TOP[1])
                && all(read(dir, "Gates.java"), "g(\"mining-guild\", \"the Mining Guild\", \"skill\", 3016, 9733, 3055, 9760, skill(Skills.MINING, 60, \"Mining\"));"));
        check("M2 wants: 60+, coal/mithril, ladder nearer, under the cap -> yes",
                MineGuild.wants(true, 60, "COAL", 50, 200, 0, 6) && MineGuild.wants(true, 75, "MITHRIL", 10, 11, 5, 6));
        check("M3 wants: off, 59, iron, spot nearer, or full -> no",
                !MineGuild.wants(false, 99, "COAL", 1, 200, 0, 6) && !MineGuild.wants(true, 59, "COAL", 1, 200, 0, 6) && !MineGuild.wants(true, 99, "IRON", 1, 200, 0, 6)
                && !MineGuild.wants(true, 99, "COAL", 201, 200, 0, 6) && !MineGuild.wants(true, 99, "COAL", 1, 200, 6, 6));
        check("M4 every guild coordinate within 22 of the spot is a guild tile (the Runner's 22-tile spot rule never mines outside)",
                Math.abs(MineGuild.SPOT[0] - MineGuild.X0) <= 22 && Math.abs(MineGuild.X1 - MineGuild.SPOT[0]) <= 22);
        check("M5 wiring: Runner.entrance and Brain.start hooks behind MineGuild.ON; the old entrance table still follows",
                all(at, "if (MineGuild.ON) { Location[] g = MineGuild.entrance(under); if (g != null) return g; }", "if (x >= 2800 && x <= 2970 && y >= 9660 && y <= 9900)")
                && all(sa, "if (MineGuild.ON) { Location g = MineGuild.spotFor(p, job.task, spotAt); if (g != null) { spotAt = g; spotName = \"the Mining Guild\"; } }")
                && MineGuild.line().startsWith("[MineGuild] on=") && W8Lines.anyOn() == (SmithOwn.ON || MineGuild.ON || Hunter.ON || SlayerOnly.ONLY1));

        // ---------------------------------------------------------------- 3 Hunter: bots.hunter (+ .chins)
        check("H0 flags: Hunter.ON follows -Dbots.hunter, CHINS needs both (" + Hunter.ON + "/" + Hunter.CHINS + ")",
                Hunter.ON == Boolean.getBoolean("bots.hunter") && Hunter.CHINS == (Hunter.ON && Boolean.getBoolean("bots.hunter.chins")));
        check("H1 quarry: swifts below 63 or without .chins; chinchompas at 63+ with it",
                Hunter.kind(62, true) == Hunter.KIND_SWIFT && Hunter.kind(63, true) == Hunter.KIND_CHIN && Hunter.kind(99, false) == Hunter.KIND_SWIFT);
        check("H2 traps per bot: engine steps (1, 2 at 20, 3 at 40), never over 3",
                Hunter.trapsFor(1) == 1 && Hunter.trapsFor(19) == 1 && Hunter.trapsFor(20) == 2 && Hunter.trapsFor(40) == 3 && Hunter.trapsFor(99) == 3);
        check("H3 area cap: 9 traps and 3 bots", Hunter.areaOk(6, 2, 3, 9, 3) && !Hunter.areaOk(7, 2, 3, 9, 3) && !Hunter.areaOk(0, 3, 1, 9, 3));
        check("H4 fit: Hunter 99, combat under " + Hunter.MIN_COMBAT + " (w13d: the Feldip ogres), a full pack or the cooldown -> no",
                Hunter.fit(1, Hunter.MIN_COMBAT, 10, 10_000, 0, 1000) && !Hunter.fit(99, 50, 20, 10_000, 0, 1000) && !Hunter.fit(5, Hunter.MIN_COMBAT - 1, 20, 10_000, 0, 1000)
                && !Hunter.fit(5, 50, 9, 10_000, 0, 1000) && !Hunter.fit(5, 50, 20, 10_000, 9_500, 1000) && Hunter.fit(5, 50, 20, 10_000, 9_000, 1000));
        check("H5 engine ids: bird snare 10006, box trap 10008, crimson swift 5073, carnivorous chinchompa 5080 at 63 (javap Traps 11-19)",
                Hunter.SNARE == 10006 && Hunter.BOX == 10008 && Hunter.SWIFT == 5073 && Hunter.RED_CHIN == 5080 && Hunter.CHIN_LEVEL == 63);
        check("H6 wiring: Brain tick, trip end and errandNoStar behind Hunter.ON; farm lines unchanged",
                all(sa, "if (Hunter.ON && Hunter.tick(this, now)) return;", "if (Hunter.ON && Hunter.plan(this, now)) return;", "boolean errandNoStar() { return Hunter.ON && Hunter.on(this) || shopAt != null",
                        "if (Farming.ON && farm != 0) { farmTick(now); return; }", "if (Farming.ON && planFarmRun(now)) return;")
                && Hunter.line().startsWith("[Hunter] on=") && W8Lines.anyOn() == (SmithOwn.ON || MineGuild.ON || Hunter.ON || SlayerOnly.ONLY1));
        String hs = read(dir, "Hunter.java");
        check("H7 the trap actions are the engine's (Traps.create / dismantle), the RNG is Hunter's own",
                all(hs, "trap(r.kind).create(p, it);", "w.getType().dismantle(p, w.getObject());", "static final Random RNG = new Random(") && !hs.contains("SmartAdventurers.RNG"));
        boolean hmApi; try { hmApi = content.global.skill.hunter.HunterManager.class.getConstructor() != null && content.global.skill.hunter.HunterManager.class.getMethod("login", core.game.node.entity.player.Player.class) != null
                && core.api.LoginListener.class.isAssignableFrom(content.global.skill.hunter.HunterManager.class); } catch (Throwable t) { hmApi = false; }
        int pl = hs.indexOf("static boolean plan("), rng = hs.indexOf("if (RNG.nextInt(hunter < 20 ? 4 : 8) != 0) return false;", pl), ens = hs.indexOf("if (ensure(p) == null) { noManager++;", pl);
        check("H8 w11 fix 1: a bot gets its HunterManager attached once (the engine's LoginListener never runs for an AIPlayer); plan asks ensure() after the RNG draw",
                hmApi && all(hs, "static HunterManager ensure(Player p) {", "if (p.getAttribute(\"hunter-manager\", null) == null) new HunterManager().login(p);", "return HunterManager.getInstance(p);")
                        && pl > 0 && rng > pl && ens > rng && !hs.substring(pl).contains("if (HunterManager.getInstance(p) == null) { noManager++;"));

        String so3 = read(dir, "SmithOwn.java");
        check("t63 fix 3: Hunter kit and SmithOwn hammer give the unplaced part's coins back (W8Polish.unplaced); no bare return after the paid add",
                all(hs, "got = need; paid = cost;", "{ W8Polish.unplaced(p, id, got - i, W8Polish.refundCoins(paid, i, got), \"hunter kit\"); return false; }")
                        && !hs.contains("if (!p.getInventory().add(new Item(id)) && !p.getBank().add(new Item(id))) return false;")
                        && all(so3, "{ W8Polish.unplaced(p, HAMMER, 1, W8Polish.refundCoins(cost, 0, 1), \"smith-own hammer\"); return false; }")
                        && W8Polish.refundCoins(40, 2, 4) == 20);

        int hf0 = Hunter.tickFails; boolean hNoThrow = true; try { Hunter.fail(null, new RuntimeException("test")); Hunter.tick(null, 0L); } catch (Throwable ex) { hNoThrow = false; }
        check("t63 fix 4: Hunter.tick wraps tick0 (catch Throwable -> fail: counted, logged once per bot, run dropped)",
                hNoThrow && Hunter.tickFails == hf0 + 1 && all(hs, "try { return tick0(br, now); } catch (Throwable t) { fail(br, t); return false; }", "if (FAILED.add(k)) System.out.println(\"[Hunter] \" + k + \" run failed: \"")
                        && !hs.contains("RUNS.remove(key(p));\r\n            return false;"));

        check("t63 fix 6: bots.smith.own.share parsed defensively (bad/null/NaN -> 0.5), so a typo never breaks SmithOwn's class init",
                SmithOwn.parse("abc", 0.5) == 0.5 && SmithOwn.parse(null, 0.5) == 0.5 && SmithOwn.parse(" 0.3 ", 0.5) == 0.3 && SmithOwn.parse("NaN", 0.5) == 0.5 && SmithOwn.parse("", 0.5) == 0.5
                        && all(read(dir, "SmithOwn.java"), "static final double SHARE = parse(System.getProperty(\"bots.smith.own.share\"), 0.5);")
                        && SmithOwn.SHARE == SmithOwn.parse(System.getProperty("bots.smith.own.share"), 0.5));

        // ---------------------------------------------------------------- 4 Slayer: bots.slayer.only1
        String sl = read(dir, "Slayer.java");
        check("L0 flag: SlayerOnly.ONLY1 follows -Dbots.slayer.only1 (" + SlayerOnly.ONLY1 + ")", SlayerOnly.ONLY1 == Boolean.getBoolean("bots.slayer.only1"));
        check("L1 crawling hands: Slayer 5, combat 25, hp 25, 5k, hunt open -> yes; each one short -> no",
                SlayerOnly.qualifies(0, 5, 5, 25, 25, 5_000, true, false) && !SlayerOnly.qualifies(0, 4, 5, 25, 25, 5_000, true, false)
                && !SlayerOnly.qualifies(0, 5, 5, 24, 25, 5_000, true, false) && !SlayerOnly.qualifies(0, 5, 5, 25, 24, 5_000, true, false)
                && !SlayerOnly.qualifies(0, 5, 5, 25, 25, 4_999, true, false) && !SlayerOnly.qualifies(0, 5, 5, 25, 25, 5_000, false, false));
        check("L2 banshees: Slayer 15, combat 40 and earmuffs (held or affordable)",
                SlayerOnly.qualifies(1, 15, 15, 40, 30, 9_000, true, true) && !SlayerOnly.qualifies(1, 15, 15, 40, 30, 9_000, true, false)
                && !SlayerOnly.qualifies(1, 14, 15, 60, 30, 9_000, true, true) && !SlayerOnly.qualifies(1, 15, 15, 39, 30, 9_000, true, true));
        check("L3 earmuffs only for banshees", SlayerOnly.needsEarmuffs("Banshee") && !SlayerOnly.needsEarmuffs("Crawling Hand") && SlayerOnly.EARMUFFS == 4166);
        check("L4 wiring: Turael allow set and the banshee kit behind ONLY1; served() unchanged",
                all(sl, "Set<Tasks> allow = served(to);", "if (SlayerOnly.ONLY1 && to == Master.TURAEL) allow = SlayerOnly.add(b, allow);", "static final Set<Tasks> ALLOW = Collections.unmodifiableSet(EnumSet.of(Tasks.BATS,")
                && all(sa, "if (SlayerOnly.ONLY1 && job.hunt != null) SlayerOnly.kit(p, job.hunt);")
                && SlayerOnly.line().startsWith("[SlayerOnly] on=") && W8Lines.anyOn() == (SmithOwn.ON || MineGuild.ON || Hunter.ON || SlayerOnly.ONLY1));
        check("L5 chaeldar is not in SlayerOnly; w9 lane D built it in W9Chaeldar, Slayer reads the flag only into CHAELDAR", !read(dir, "SlayerOnly.java").contains("getBoolean(\"bots.slayer.chaeldar\")")
                && sl.contains("static final boolean CHAELDAR = Boolean.getBoolean(\"bots.slayer.chaeldar\") && VANNAKA;"));

        System.out.println("[TestW8Wave4b] " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
