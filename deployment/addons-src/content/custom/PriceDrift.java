package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.ge.GEDB;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prices that react (plan C20). Once an hour, every item the bots trade drifts: short of stock (a bounty is out) it
 * creeps up, glutted it creeps down, by DAILY_PERCENT / 24 per hour, never outside 70%–150% of the guide price the
 * item had when drift first saw it (data/eco/price-base.json). Writes straight to the price_index table (the server
 * reads it on every lookup, nothing is cached). Real trades keep moving the index as before.
 * Flags: -Dprices.drift=false turns it off; -Dprices.driftDaily (default 2) is the daily percentage.
 */
public final class PriceDrift implements StartupListener {
    static final Timer TIMER = new Timer("PriceDrift", true);
    static final boolean FAST = Boolean.getBoolean("traders.fast");
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("prices.drift", "true"));
    static final double DAILY_PERCENT = Double.parseDouble(System.getProperty("prices.driftDaily", "2"));
    static final double FLOOR = 0.70, CEILING = 1.50;
    static final Map<Integer, Integer> BASE = new ConcurrentHashMap<>();

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots() || !ON) { System.out.println("[Prices] drift off"); return; }
        load();
        TIMER.schedule(new TimerTask() {
            @Override public void run() { try { drift(); } catch (Throwable t) { System.out.println("[Prices] drift failed: " + t); } }
        }, FAST ? 90_000 : 20 * 60_000, FAST ? 120_000 : 3_600_000);
        System.out.println("[Prices] drift on: " + DAILY_PERCENT + "% a day, within " + (int) (FLOOR * 100) + "–" + (int) (CEILING * 100) + "% of the base guide");
    }

    static File file() { return new File(ServerConstants.DATA_PATH, "eco/price-base.json"); }

    static void load() {
        File f = file();
        if (!f.exists()) { LOADED = true; return; }
        try (FileReader r = new FileReader(f)) {
            JSONObject j = (JSONObject) new JSONParser().parse(r);
            for (Object k : j.keySet()) BASE.put(Integer.parseInt(String.valueOf(k)), ((Number) j.get(k)).intValue());
            System.out.println("[Prices] " + BASE.size() + " base prices remembered");
            LOADED = true;
        } catch (Throwable t) { System.out.println("[Prices] could not read base prices: " + t); Ledger.quarantine(f, "Prices", t, false, true); } // audit 10-23 X-11: kept, and never rewritten this boot
    }

    @SuppressWarnings("unchecked")
    static void save() {
        try {
            JSONObject j = new JSONObject();
            for (Map.Entry<Integer, Integer> e : BASE.entrySet()) j.put(String.valueOf(e.getKey()), e.getValue());
            file().getParentFile().mkdirs();
            Ledger.writeAtomic(file(), j.toJSONString()); // audit E-10: a truncated base file re-centred every band
        } catch (Throwable t) { System.out.println("[Prices] could not save base prices: " + t); try { Failures.Life.saveFailed("base-prices", t); } catch (Throwable ignored) { } } // batch 19c §10 save-failed
    }

    static volatile boolean LOADED; // the base file has been read (drift on): only then may others add bases and save
    static final java.util.concurrent.atomic.AtomicBoolean SAVE_PENDING = new java.util.concurrent.atomic.AtomicBoolean();

    /** A base was recorded outside drift (MarketBrain.botRef, audit E-4): one save a few seconds later, off the game thread. */
    static void saveSoon() {
        if (!SAVE_PENDING.compareAndSet(false, true)) return;
        TIMER.schedule(new TimerTask() { @Override public void run() { SAVE_PENDING.set(false); save(); } }, 5_000);
    }

    /** batch 19c §4.6 price-outlier (logging only): value <= 0, value outside 50%-200% of BASE, or botRef under half the value. Never adds a base. */
    static void outliers(String src) {
        if (!Failures.ON) return;
        for (int id : MarketBrain.CATALOGUE) {
            try {
                if (!MarketBrain.tradeable(id)) continue;
                int v = MarketBrain.value(id); Integer base = BASE.get(id);
                int ref = base != null && LOADED ? MarketBrain.botRef(id) : v; // botRef only adds a base when there is none: read it only when there is one
                if (v <= 0 || base != null && (v * 2L < base || v > 2L * base) || ref * 2L < v) Failures.Econ.priceOutlier(id, v, base == null ? -1 : base, ref, src);
            } catch (Throwable ignored) { }
        }
    }

    static synchronized void drift() {
        if (!LOADED) { System.out.println("[Prices] drift off this boot: price-base.json could not be read (audit 10-23 X-11)"); return; } // it rewrote the file from today's guides and re-centred every band
        try { outliers("drift"); } catch (Throwable ignored) { } // batch 19c §4.6
        double step = DAILY_PERCENT / 24.0 / 100.0;
        List<int[]> changes = new ArrayList<>(); // id, from, to
        boolean newBase = false;
        // Only the genuinely short items rise: the 40 most wanted, and only while under a quarter of their target.
        // (The first trial let every item with any bounty rise: 627 up, 16 down, which is inflation by another name.)
        java.util.Set<Integer> wanted = new java.util.HashSet<>();
        for (MarketBrain.Entry e : MarketBrain.topBounties(40)) wanted.add(e.id);
        for (int id : MarketBrain.CATALOGUE) {
            if (!MarketBrain.tradeable(id)) continue;
            boolean scarce = wanted.contains(id) && MarketBrain.stockOf(id) * 4 < MarketBrain.target(id) && SmartAdventurers.capable(id); // audit E5: Onyx drifted up for ever
            boolean glut = MarketBrain.isSurplus(id);
            if (scarce == glut) continue; // neither, or contradictory: leave it
            int cur = MarketBrain.value(id);
            if (cur <= 0) continue;
            Integer base = BASE.get(id);
            if (base == null) { BASE.put(id, cur); base = cur; newBase = true; }
            int target = scarce ? (int) Math.ceil(cur * (1 + step)) : (int) Math.floor(cur * (1 - step));
            if (scarce && target == cur) target = cur + 1;
            if (glut && target == cur) target = cur - 1;
            int lo = (int) Math.max(1, Math.round(base * FLOOR)), hi = (int) Math.max(lo, Math.round(base * CEILING));
            target = Math.max(lo, Math.min(hi, target));
            if (target != cur) changes.add(new int[]{id, cur, target});
        }
        if (newBase) save();
        if (changes.isEmpty()) { System.out.println("[Prices] drift: nothing to move"); return; }
        final List<int[]> apply = changes;
        final long now = System.currentTimeMillis();
        try {
            GEDB.run(conn -> {
                try {
                    java.sql.PreparedStatement st = conn.prepareStatement("UPDATE price_index SET value = ?, last_update = ? WHERE item_id = ?");
                    for (int[] c : apply) { st.setInt(1, c[2]); st.setLong(2, now); st.setInt(3, c[0]); st.addBatch(); }
                    st.executeBatch();
                    st.close();
                } catch (Throwable t) { System.out.println("[Prices] drift update failed: " + t); }
                return kotlin.Unit.INSTANCE;
            });
        } catch (Throwable t) { System.out.println("[Prices] drift db: " + t); return; }
        int up = 0, down = 0; int[] biggest = null;
        for (int[] c : apply) { if (c[2] > c[1]) up++; else down++; if (biggest == null || Math.abs(c[2] - c[1]) > Math.abs(biggest[2] - biggest[1])) biggest = c; }
        System.out.println("[Prices] drift: " + up + " up, " + down + " down" + (biggest != null ? " (e.g. " + MarketBrain.name(biggest[0]) + " " + biggest[1] + " -> " + biggest[2] + " gp)" : ""));
        MarketBrain.priceCacheClear();
    }
}
