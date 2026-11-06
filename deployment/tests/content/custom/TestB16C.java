package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b16c=true; best with -Dsmartbots.count=0): batch 16 item3, the Falador party room. Five rigged
 * bots on a break (idleUntil, loiterAt = where they stand): three in Falador park, one at the Rising Sun Inn, one at the
 * Al Kharid palace gardens (over 200 tiles off: never invited). think() is driven every 7 ticks.
 * Phase 1 (no real player online, 40 s, party due): no party. Phase 2: a stand-in human (artificial=false) at the
 * Exchange: a party is announced; guests walk to the party room floor, dance, and after partyEnd are ordinary again
 * (partyUntil past, Spectacle.idle). A second check right after: nothing for 4 hours.
 */
public final class TestB16C implements StartupListener {
    static void log(String s) { System.out.println("[TestB16C] " + s); }
    static Object sget(String n) { try { java.lang.reflect.Field f = Spectacle.class.getDeclaredField(n); f.setAccessible(true); return f.get(null); } catch (Throwable t) { return "n/a"; } }
    static void sset(String n, Object v) { try { java.lang.reflect.Field f = Spectacle.class.getDeclaredField(n); f.setAccessible(true); f.set(null, v); } catch (Throwable ignored) { } }
    static Object get(Object o, String n) { try { java.lang.reflect.Field f = SmartAdventurers.Brain.class.getDeclaredField(n); f.setAccessible(true); return f.get(o); } catch (Throwable t) { return "n/a"; } }
    static final Location ROOM = Location.create(3045, 3378, 0);

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b16c")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, endAt = -1; final int N = 5; AIPlayer[] bot = new AIPlayer[N]; SmartAdventurers.Brain[] br = new SmartAdventurers.Brain[N];
            Location[] spot = {Location.create(2995, 3378, 0), Location.create(2993, 3376, 0), Location.create(2997, 3380, 0), Location.create(2956, 3370, 0), Location.create(3293, 3175, 0)};
            int minRoom[] = new int[N];
            String counts() { return "parties " + sget("parties") + ", invited " + sget("partyInvited") + ", arrived " + sget("partyArrived") + ", dances " + sget("partyDances") + ", skipped " + sget("partySkipped"); }
            String state(int i) { Object pu = get(br[i], "partyUntil"); return bot[i].getUsername() + " at " + bot[i].getLocation() + " (" + (int) bot[i].getLocation().getDistance(ROOM) + " from the room, closest " + minRoom[i] + "), partyUntil " + (pu instanceof Long ? ((Long) pu == 0 ? "0" : ((Long) pu - System.currentTimeMillis()) / 1000 + " s") : pu) + ", arrived " + get(br[i], "partyArrived") + ", Spectacle.idle " + Spectacle.idle(br[i]); }
            @Override public boolean pulse() {
                t++;
                try {
                    long now = System.currentTimeMillis();
                    if (t == 150) {
                        for (int i = 0; i < N; i++) { bot[i] = TestCR1.bot(spot[i], 40, 20, 20, 20); br[i] = TestCR1.rig(bot[i], false); minRoom[i] = 9999; }
                        log("bots " + bot[0].getUsername() + ", " + bot[1].getUsername() + ", " + bot[2].getUsername() + " (Falador park), " + bot[3].getUsername() + " (Rising Sun), " + bot[4].getUsername() + " (Al Kharid, " + (int) spot[4].getDistance(ROOM) + " tiles)");
                        log("routes to the room: park " + NavPlanner.reachable(spot[0], ROOM) + ", Al Kharid " + NavPlanner.reachable(spot[4], ROOM));
                    }
                    if (t == 156) { for (int i = 0; i < N; i++) { br[i].idleUntil = now + 900_000; br[i].loiterAt = spot[i]; br[i].loiterName = "a test spot"; } sset("nextParty", 0L); sset("nextPartyCheck", 0L); log("phase 1: no real player online; party due now"); }
                    if (t > 156 && t % 7 == 0) for (int i = 0; i < N; i++) { br[i].nextThink = 0; br[i].think(); minRoom[i] = Math.min(minRoom[i], (int) bot[i].getLocation().getDistance(ROOM)); }
                    if (t == 225) {
                        log("RESULT 1 no real player, 40 s: " + counts());
                        TestA1.human(Location.create(3165, 3490, 0), 900500);
                        sset("nextPartyCheck", 0L);
                        log("phase 2: a stand-in human at the Exchange");
                    }
                    if (t == 245) {
                        log("RESULT 2a party: " + counts() + ", start in " + ((sget("partyStart") instanceof Long ? ((Long) sget("partyStart") - now) / 1000 : -1)) + " s, ends in " + ((sget("partyEnd") instanceof Long ? ((Long) sget("partyEnd") - now) / 1000 : -1)) + " s");
                        for (int i = 0; i < N; i++) log("RESULT 2a " + state(i));
                        Object pe = sget("partyEnd"); endAt = pe instanceof Long && (Long) pe > now ? t + (int) (((Long) pe - now) / 600) + 20 : t + 300;
                    }
                    if (endAt > 0 && t == endAt - 60) { log("RESULT 2b during the party: " + counts()); for (int i = 0; i < N; i++) log("RESULT 2b " + state(i)); }
                    if (endAt > 0 && t == endAt) {
                        log("RESULT 3 after the party: " + counts());
                        for (int i = 0; i < N; i++) log("RESULT 3 " + state(i));
                        int p0 = sget("parties") instanceof Integer ? (Integer) sget("parties") : -1;
                        sset("nextPartyCheck", 0L);
                        for (int i = 0; i < N; i++) { br[i].idleUntil = now + 900_000; br[i].nextThink = 0; br[i].think(); }
                        int p1 = sget("parties") instanceof Integer ? (Integer) sget("parties") : -1;
                        log("RESULT 4 another check straight after: parties " + p0 + " -> " + p1 + ", next party in " + (sget("nextParty") instanceof Long ? ((Long) sget("nextParty") - now) / 60_000 : -1) + " min");
                        log("DONE");
                        return true;
                    }
                    if (t == 1400) { log("RESULT timeout: " + counts()); log("DONE"); return true; }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
