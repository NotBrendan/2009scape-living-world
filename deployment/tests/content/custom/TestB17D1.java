package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

/**
 * Trial only (-Dtest.b17d1=true -Dtest.b16c=true -Dspectacle.test=true -Dsmartbots.count=0; nav off, no data/survey/nav.on):
 * batch 17 item1, Falador party polish. Needs TestCR1 and TestA1 compiled in (bot/rig/human helpers).
 * A (extras): six rigged bots in Falador park on a break ending in 30 s that already had their one extra this break
 * (extrasThisBreak 1, extraBreak = extraRollBreak = idleUntil, roll ok), logs banked and a tinderbox in the pack. A stand-in
 * human is online; Spectacle.partyCheck invites them (idleUntil moves to after the party). Then each leaves the party
 * (partyUntil 0) with nextExtra 0 and loiterExtra runs once. Before: the counter is back to 0 and some start a second extra
 * in the same break; after: counter 1, no extra.
 * B (door, fallback walk): a guest inside a building near the party room with its door shut (-Dd1.x/-Dd1.y/-Dd1.dx/-Dd1.dy,
 * default inside the building south of the room, door 11707 at 3046,3360); partyTick every 7 ticks until it arrives or gives
 * up (partyStart + 45 s). Before: stuck behind the door; after: opens it and arrives.
 */
public final class TestB17D1 implements StartupListener {
    static void log(String s) { System.out.println("[TestB17D1] " + s); }
    static Object sget(String n) { try { java.lang.reflect.Field f = Spectacle.class.getDeclaredField(n); f.setAccessible(true); return f.get(null); } catch (Throwable t) { return "n/a"; } }
    static void sset(String n, Object v) { try { java.lang.reflect.Field f = Spectacle.class.getDeclaredField(n); f.setAccessible(true); f.set(null, v); } catch (Throwable ignored) { } }
    static final Location DOOR = Location.create(Integer.getInteger("d1.dx", 3046), Integer.getInteger("d1.dy", 3360), 0);
    static final Location INSIDE = Location.create(Integer.getInteger("d1.x", 3046), Integer.getInteger("d1.y", 3357), 0);

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b17d1")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, bStart = -1; final int N = 6; AIPlayer[] bot = new AIPlayer[N]; SmartAdventurers.Brain[] br = new SmartAdventurers.Brain[N];
            AIPlayer db; SmartAdventurers.Brain dbr; double closest = 999;
            Location DOOR_AT = DOOR;
            String door() { Scenery s = RegionManager.getObject(0, DOOR_AT.getX(), DOOR_AT.getY()); return s == null ? "none" : s.getName() + " " + s.getId() + (QuestSteps.hasOption(s, "Open") ? " shut" : " open"); }
            @Override public boolean pulse() {
                t++;
                try {
                    long now = System.currentTimeMillis();
                    if (t == 150) {
                        for (int i = 0; i < N; i++) {
                            bot[i] = TestCR1.bot(Location.create(2993 + i, 3378 + (i % 2) * 2, 0), 40, 20, 20, 20); br[i] = TestCR1.rig(bot[i], false);
                            bot[i].getBank().add(new Item(1511, 20)); bot[i].getInventory().add(new Item(590, 1));
                        }
                        TestA1.human(Location.create(2995, 3374, 0), 917100);
                    }
                    if (t == 158) {
                        long[] old = new long[N];
                        for (int i = 0; i < N; i++) { SmartAdventurers.Brain b = br[i]; b.loiterAt = bot[i].getLocation(); b.loiterName = "a test spot"; b.idleUntil = now + 30_000; old[i] = b.idleUntil;
                            b.extraBreak = b.idleUntil; b.extrasThisBreak = 1; b.extraRollBreak = b.idleUntil; b.extraRollOk = true; b.nextExtra = 0; }
                        sset("nextParty", 0L); sset("nextPartyCheck", 0L);
                        Spectacle.partyCheck(now);
                        int invited = 0, reset = 0, extras = 0;
                        for (int i = 0; i < N; i++) {
                            SmartAdventurers.Brain b = br[i];
                            if (b.partyUntil > now) invited++;
                            String s1 = bot[i].getUsername() + ": idleUntil +" + (old[i] - now) / 1000 + " s -> +" + (b.idleUntil - now) / 1000 + " s, extraBreak follows " + (b.extraBreak == b.idleUntil) + ", rollBreak follows " + (b.extraRollBreak == b.idleUntil);
                            b.partyUntil = 0; b.nextExtra = 0; // left the party (or never went), the extra cooldown out of the way
                            boolean started = b.loiterExtra(now);
                            if (b.extrasThisBreak == 0) reset++;
                            if (b.extraKind != 0) extras++;
                            log("A " + s1 + "; loiterExtra -> " + started + ", extrasThisBreak " + b.extrasThisBreak + ", extraKind " + b.extraKind);
                            if (b.extraKind != 0) b.endExtra();
                        }
                        log("RESULT A invited " + invited + "/" + N + ", counters reset to 0: " + reset + ", second extras started in the same break: " + extras + " (after: 0 and 0)");
                        for (int i = 0; i < N; i++) { br[i].idleUntil = 0; bot[i].getProperties().setTeleportLocation(Location.create(3210, 3420, 0)); }
                    }
                    if (t == 170) { // a tile next to a shut door from which the server's pathfinder cannot reach the floor (boxed in by that door)
                        Location aim = Location.create(3045, 3375, 0), pick = null; Location c = aim;
                        for (int dx = -30; dx <= 30 && pick == null; dx++) for (int dy = -30; dy <= 30 && pick == null; dy++) {
                            Scenery s = RegionManager.getObject(0, c.getX() + dx, c.getY() + dy);
                            if (s == null || s.getName() == null || !s.getName().toLowerCase().contains("door") || s.getType() > 3 || !QuestSteps.hasOption(s, "Open")) continue;
                            for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0, 0}}) {
                                Location n = s.getLocation().transform(o[0], o[1], 0); int f = RegionManager.getClippingFlag(0, n.getX(), n.getY());
                                if (f == -1 || (f & 0x200000) != 0 || (f & 0x100) != 0 || n.getDistance(aim) > 30) continue;
                                core.game.world.map.path.Path p = core.game.world.map.path.Pathfinder.find(n, aim);
                                Location end = p == null || !p.isSuccessful() || p.getPoints().isEmpty() ? n : Location.create(p.getPoints().peekLast().getX(), p.getPoints().peekLast().getY(), 0);
                                log("B candidate " + n + " beside door " + s.getLocation() + ": the pathfinder gets to " + end + " (" + (int) end.getDistance(aim) + " off)");
                                if (end.getDistance(aim) > 6) { pick = n; DOOR_AT = s.getLocation(); break; }
                            }
                        }
                        db = TestCR1.bot(pick != null ? pick : INSIDE, 40, 20, 20, 20); dbr = TestCR1.rig(db, false);
                    }
                    if (t == 176) {
                        dbr.partyUntil = now + 600_000; dbr.partyArrived = false; dbr.partyAt = Location.create(3045, 3375, 0);
                        sset("partyStart", now + 60_000L); sset("partyEnd", now + 600_000L);
                        bStart = t; log("B guest " + db.getUsername() + " at " + db.getLocation() + " (" + (int) db.getLocation().getDistance(dbr.partyAt) + " tiles), door " + door() + ", NavWalker.ON " + NavWalker.ON);
                    }
                    if (bStart > 0 && t > bStart && t % 7 == 0) {
                        if (dbr.partyUntil > now && !dbr.partyArrived) Spectacle.partyTick(dbr, now);
                        closest = Math.min(closest, db.getLocation().getDistance(dbr.partyAt));
                        if ((t - bStart) % 49 == 0) log("B tick " + (t - bStart) + ": at " + db.getLocation() + ", door " + door() + ", partyDoors " + sget("partyDoors"));
                        if (dbr.partyArrived || dbr.partyUntil <= now || t - bStart > 400) {
                            log("RESULT B " + (dbr.partyArrived ? "ARRIVED at tick " + (t - bStart) : dbr.partyUntil <= now ? "gave up (did not make the party)" : "still walking") + ", at " + db.getLocation() + " (" + (int) db.getLocation().getDistance(dbr.partyAt) + " tiles off, closest " + (int) closest + "), door " + door() + ", partyDoors " + sget("partyDoors"));
                            log("DONE"); return true;
                        }
                    }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
