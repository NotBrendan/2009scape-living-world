package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;

/**
 * Trial only: batch 14 item 3. -Dtest.b143=spend: spend from every daily cap (bounty 12,345; bot buyer 54,321 for uid 777;
 * alch 1,000) and save the ledger. -Dtest.b143=check (the next boot, KEEP=1): what the caps say they have used today.
 * Reflection for the bot buyer's private fields, so it compiles against batch 13 and 14.
 */
public final class TestB143 implements StartupListener {
    static void log(String s) { System.out.println("[TestB143] " + s); }
    static java.lang.reflect.Field f(String n) throws Exception { java.lang.reflect.Field x = PrivateWorldAddons.class.getDeclaredField(n); x.setAccessible(true); return x; }
    static String state() {
        try { return "bounty paidToday " + Bounty.paidToday + "; buyer spentAll " + f("spentAll").get(null) + ", spentToday " + f("spentToday").get(null) + ", spentDay " + f("spentDay").get(null) + "; alch alchedToday " + Ledger.alchedToday + " (" + Ledger.alchDay + "); clerk " + Ledger.clerkPaidToday + " (" + Ledger.clerkDay + ")"; }
        catch (Throwable t) { return "state error " + t; }
    }
    @Override public void startup() {
        final String mode = System.getProperty("test.b143");
        if (mode == null) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                if (t != 60) return false;
                try {
                    if ("spend".equals(mode)) {
                        log("before spending: " + state());
                        Bounty.paidToday += 12_345;
                        f("spentDay").set(null, java.time.LocalDate.now(java.time.ZoneId.of("America/New_York")));
                        f("spentAll").set(null, 54_321L);
                        @SuppressWarnings("unchecked") java.util.Map<Integer, Integer> m = (java.util.Map<Integer, Integer>) f("spentToday").get(null); m.put(777, 54_321);
                        Ledger.alchRoom(1_000);
                        Ledger.save();
                        log("RESULT spent and saved: " + state());
                    } else log("RESULT after restart: " + state());
                } catch (Throwable e) { log("error " + e); e.printStackTrace(); }
                log("DONE");
                return true;
            }
        });
    }
}
