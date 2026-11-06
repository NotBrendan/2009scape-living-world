package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.world.map.Location;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trial only, batch 19c builder B, B4 = replays of non-path bugs (design 9). Two runs of the same test:
 *   live: -Dtest.b19c11=true -Dsurvey.replay=1 -Dsurvey.replay.debug=true -Dsmartbots.count=2 -Dtraders.fast=true
 *     L1 a seeded shop-loop bug (Lumbridge general store, wants a pot, which it stocks) -> the rescue replayer walks there as a shop_reach
 *        scenario, buys nothing (the replayer's own coins/items guard), and the bug gets a live "pass" with the stock read
 *     L2 the same shop wanting a rune scimitar (not stocked)                              -> a live "fail" listing the stock
 *     L3 farm kinds seeded too (quest, job, ge, danger)                                   -> nothing runs (not a farm world): no farm replays
 *   farm: -Dtest.b19c11=true -Dfailures.host=farm-w03 -Dsurvey.replay=0 -Dsurvey.replay.econ=2 -Dsurvey.replay.debug=true -Dsmartbots.count=2 -Dtraders.fast=true
 *     F1 the shop bug under the farm flag -> the econ replayer's throwaway brain with the spec's coins walks shopTick and buys: "pass"
 *     F2 a seeded step-stalled bug (cooks step 1, stage 0) -> a throwaway test bot runs that step from the spec's stage: "pass" (or the result)
 *     F3 job (TREE near Lumbridge, 5 min), ge (1 log taken and supplied back), danger (a spot list) -> one replay each, world farm-w03
 * The bugs are written into bugs.json before the world loads them (Failures.Bugs.load() again after the write). DONE when all are in, or 14 min.
 */
public final class TestB19C11 implements StartupListener {
    static void log(String s) { System.out.println("[TestB19C11] " + s); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b19c11")) return;
        new Thread(TestB19C11::run, "TestB19C11").start();
    }

    static final String POT = "b-shop-loop-t19c11-pot", SCIM = "b-shop-loop-t19c11-scim", QST = "b-step-stalled-cooks-1", JOB = "b-no-gain-tree-t19c11", GE = "b-ge-take-stalled-t19c11", DNG = "b-repeat-death-t19c11";

    static String bug(String id, String domain, String reason, String replay, long now) {
        return "\"" + id + "\":{\"id\":\"" + id + "\",\"domain\":\"" + domain + "\",\"reason\":\"" + reason + "\",\"rsub\":\"test\",\"cell\":null,\"place\":\"test\",\"first\":" + now + ",\"last\":" + (now - 4 * 86_400_000L)
                + ",\"count\":1,\"days\":{},\"sources\":{\"test\":1},\"examples\":[],\"fids\":[],\"bots\":[],\"snap\":null,\"status\":\"open\",\"statusT\":" + now + ",\"statusBy\":\"live\",\"note\":\"\",\"replays\":[],\"replay\":" + replay + "}";
    }

    static void run() {
        try {
            for (int i = 0; i < 600 && BotChat.WorldData.shops.isEmpty(); i++) Thread.sleep(200);
            Object[] store = null;
            for (Object[] sh : new ArrayList<>(BotChat.WorldData.shops)) if (String.valueOf(sh[0]).toLowerCase().contains("lumbridge general")) { store = sh; break; }
            if (store == null) { log("RESULT no Lumbridge general store in WorldData.shops (" + BotChat.WorldData.shops.size() + " shops)"); return; }
            int[] stock = (int[]) store[3]; boolean pots = false; for (int id : stock) if (id == 1931) pots = true;
            String slug = Failures.slug(String.valueOf(store[0]));
            log("mode " + (Failures.FARM ? "farm" : "live") + " (host " + Failures.HOST + "); shop '" + store[0] + "' slug " + slug + " at " + store[1] + "," + store[2] + ", stocks pots " + pots + ", stock " + java.util.Arrays.toString(stock));
            long now = System.currentTimeMillis();
            String from = "[3222,3218,0]";
            StringBuilder j = new StringBuilder("{\"v\":1,\"updated\":" + now + ",\"host\":\"test\",\"bugs\":{");
            j.append(bug(POT, "economy", "shop-loop", "{\"kind\":\"shop\",\"shop\":\"" + slug + "\",\"buy\":[[1931,1]],\"from\":" + from + ",\"coins\":300}", now)).append(',');
            j.append(bug(SCIM, "economy", "shop-loop", "{\"kind\":\"shop\",\"shop\":\"" + slug + "\",\"buy\":[[1333,1]],\"from\":" + from + ",\"coins\":30000}", now)).append(',');
            j.append(bug(QST, "quest", "step-stalled", "{\"kind\":\"quest\",\"slug\":\"cooks\",\"step\":1,\"stage\":0}", now)).append(',');
            j.append(bug(JOB, "skilling", "no-gain", "{\"kind\":\"job\",\"task\":\"TREE\",\"spot\":[3193,3240,0],\"level\":1,\"tools\":[1351]}", now)).append(',');
            j.append(bug(GE, "economy", "ge-take-stalled", "{\"kind\":\"ge\",\"item\":1511,\"n\":1}", now)).append(',');
            j.append(bug(DNG, "combat", "repeat-death", "{\"kind\":\"danger\",\"spot\":[3104,3245,0],\"cb\":20,\"approach\":[3092,3243,0]}", now));
            j.append("}}");
            File f = new File(Scenarios.rDir(), "bugs.json"); f.getParentFile().mkdirs();
            File keep = new File(f.getPath() + ".t19c11-was"); if (f.exists() && !keep.exists()) Files.copy(f.toPath(), keep.toPath());
            Files.write(f.toPath(), j.toString().getBytes("UTF-8"));
            TestB19C8.onGame(Failures.Bugs::load);
            log("seeded 6 bugs with replay specs into " + f.getPath());
            String[] ids = Failures.FARM ? new String[]{POT, SCIM, QST, JOB, GE, DNG} : new String[]{POT, SCIM};
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 14 * 60_000L) {
                Thread.sleep(10_000);
                int have = 0; for (String id : ids) if (!replays(id).isEmpty()) have++;
                if (have == ids.length) break;
            }
            Thread.sleep(3_000);
            for (String id : new String[]{POT, SCIM, QST, JOB, GE, DNG}) log("RESULT " + id + ": " + describe(replays(id)));
            if (!Failures.FARM) {
                log("RESULT L1/L2 live shop-reach: " + POT + " expect one pass (stock read, world " + Failures.HOST + "), " + SCIM + " expect one fail listing the stock; farm kinds expect none (not a farm world)");
                log("RESULT L1 coin/item neutral: live shop-reach replays started " + EconReplayer.liveStarted + ", farm started " + EconReplayer.farmStarted + " (expect 0); the replayer's coins/items guard ends a walk 'skipped' on any change, so a pass/fail means unchanged");
            } else log("RESULT F farm: started " + EconReplayer.farmStarted + ", live shop-reach " + EconReplayer.liveStarted + " (survey.replay=0: expect 0)");
            Failures.flushNow(8_000);
            String fileTxt = new String(Files.readAllBytes(f.toPath()), "UTF-8");
            log("RESULT bugs.json on disk keeps domain and replay: " + (fileTxt.contains("\"domain\":\"economy\"") && fileTxt.contains("\"replay\":{")) + "; " + POT + " replays in the file " + (fileTxt.indexOf("\"world\":\"" + Failures.HOST + "\"") >= 0));
        } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); }
        finally { log("DONE"); }
    }

    static List<Map<String, Object>> replays(String id) {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (Failures.Bugs.LOCK) { Failures.Bug b = Failures.Bugs.VIEW.get(id); if (b != null) for (Map<String, Object> r : b.replays) out.add(new LinkedHashMap<>(r)); }
        return out;
    }
    static String describe(List<Map<String, Object>> l) {
        if (l.isEmpty()) return "no replays";
        StringBuilder b = new StringBuilder(l.size() + " replay(s):");
        for (Map<String, Object> r : l) b.append(" [").append(r.get("world")).append(' ').append(r.get("bot")).append(' ').append(r.get("result")).append(" '").append(r.get("why")).append("']");
        return b.toString();
    }
}
