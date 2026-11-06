package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

/** Trial only (-Dtest.prt4=true): pathing review 10-23 T-4, a trader's paid teleport to a far shop whose listed tile is solid. Where does it land? */
public final class TestPRT4 implements StartupListener {
    static void log(String s) { System.out.println("[TestPRT4] " + s); }
    static final Object[][] SHOPS = {{"Rasolo's Goods", 2536, 3426}, {"Leprechaun Larry's", 2826, 3685}, {"Sir Tiffy Cashien", 2997, 3373}, {"Gnomic Supplies", 3168, 3334}};
    static boolean standable(Location l) { int f = RegionManager.getClippingFlag(l.getZ(), l.getX(), l.getY()); return f != -1 && (f & (0x1000000 | 0x200000 | 0x80000 | 0x40000 | 0x100)) == 0; }
    @Override public void startup() {
        if (!Boolean.getBoolean("test.prt4")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, i = -1, w, good; TravellingTraders.Trader tr; AIPlayer bot;
            @Override public boolean pulse() {
                try {
                    if (++t < 30) return false;
                    if (tr == null) {
                        tr = new TravellingTraders.Trader(TravellingTraders.Kind.TREASURE, Location.create(3222, 3218, 0)); // not scheduled: only its shop walk is driven here
                        bot = new AIPlayer(Location.create(3293, 3180, 0)); tr.bot = bot;
                        Ledger.Wallet.give(bot, 200_000);
                        i = 0; w = 0; return false;
                    }
                    if (i >= SHOPS.length) { log("RESULT " + good + " of " + SHOPS.length + " teleports landed on a standable tile"); log("DONE"); return true; }
                    Object[] s = SHOPS[i];
                    Location shop = Location.create((Integer) s[1], (Integer) s[2], 0);
                    w++;
                    if (w == 1) { Location start = shop.getDistance(Location.create(3293, 3180, 0)) > 340 ? Location.create(3293, 3180, 0) : Location.create(2600, 3100, 0); bot.getPulseManager().clear(); bot.getWalkingQueue().reset(); bot.getProperties().setTeleportLocation(start); }
                    if (w == 4) {
                        tr.shopAt = shop; tr.shopName = (String) s[0]; tr.shopItem = 952; tr.shopAmt = 1; tr.shopStalls = 0; tr.shopBest = Double.MAX_VALUE; tr.shopUntil = System.currentTimeMillis() + 180_000; tr.shopLastPos = null;
                        tr.shopTick();
                    }
                    if (w == 8) {
                        Location at = bot.getLocation(); boolean ok = standable(at); if (ok) good++;
                        log("RESULT " + s[0] + " (listed tile " + shop + ", standable " + standable(shop) + "): landed at " + at + ", standable " + ok);
                        tr.shopAt = null; i++; w = 0;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); log("DONE"); return true; }
                return false;
            }
        });
    }
}
