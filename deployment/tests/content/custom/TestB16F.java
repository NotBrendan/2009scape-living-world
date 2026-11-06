package content.custom;

import core.api.StartupListener;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b16f=true, with real smart bots): batch 16 item3 with the real bots and the clan chat. At t=300 a
 * stand-in human appears at the Exchange and the party is made due (nextParty = 0); every 30 s the counters are logged
 * until t=1500 (about 12 minutes in). The "[Spectacle] party at ..." line says who was invited and who announced it.
 */
public final class TestB16F implements StartupListener {
    static void log(String s) { System.out.println("[TestB16F] " + s); }
    static Object sget(String n) { try { java.lang.reflect.Field f = Spectacle.class.getDeclaredField(n); f.setAccessible(true); return f.get(null); } catch (Throwable t) { return "n/a"; } }
    static void sset(String n, Object v) { try { java.lang.reflect.Field f = Spectacle.class.getDeclaredField(n); f.setAccessible(true); f.set(null, v); } catch (Throwable ignored) { } }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b16f")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 300) { TestA1.human(Location.create(3165, 3490, 0), 900700); sset("nextParty", 0L); sset("nextPartyCheck", 0L); log("a stand-in human at the Exchange; party due; clan bots " + Companions.CLAN_BOTS.size()); }
                    if (t > 300 && t % 50 == 0) {
                        int loit = 0; long now = System.currentTimeMillis();
                        if (Integer.valueOf(0).equals(sget("parties"))) sset("nextParty", 0L); // a skip (under 2 guests) waits 20 min: retry at once here
                        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) if (b.bot != null && now < b.idleUntil) loit++;
                        log("t " + t + ": parties " + sget("parties") + ", invited " + sget("partyInvited") + ", arrived " + sget("partyArrived") + ", dances " + sget("partyDances") + ", skipped " + sget("partySkipped") + ", loitering now " + loit);
                    }
                    if (t == 1500) { log("RESULT parties " + sget("parties") + ", invited " + sget("partyInvited") + ", arrived " + sget("partyArrived") + ", dances " + sget("partyDances") + ", skipped " + sget("partySkipped")); log("DONE"); return true; }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
