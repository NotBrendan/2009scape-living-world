package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.combat.graves.Grave;
import core.game.node.entity.combat.graves.GraveController;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only (-Dtest.b15b3=true): batch 15 item3, gravestone blessing.
 * Bot-to-bot: a crew bot "dies" (a gravestone made as the player-rules death makes one, the bot back at Lumbridge, the
 * brain's death latch set) and its brain's own checkDeath runs (the idle-death path). A crewmate nearby should walk over
 * and use the stone's option:
 *  S1 crewmate Prayer 70, 6 tiles off -> "bless" (the stone's "blessed" flag, more time, prayer points spent).
 *  S2 crewmate Prayer 30 -> "repair".
 *  S3 the stone in the Wilderness -> nobody goes.
 *  S4 killed by a cow, the stone next to the cows -> nobody goes.
 * Player request (TradeTalk.blessGrave): Q 15 tiles from P's stone; the tile Q is on when the stone gets its 15 minutes.
 */
public final class TestB15B3 implements StartupListener {
    static void log(String s) { System.out.println("[TestB15B3] " + s); }

    static Grave grave(AIPlayer owner, Location at) {
        if (owner.getDetails().getUid() == 0) owner.getDetails().accountInfo.setUid(0x40000000 + owner.getUid());
        Grave g = GraveController.produceGrave(GraveController.getGraveType(owner));
        g.initialize(owner, at, new Item[]{new Item(1323), new Item(995, 500)});
        return g;
    }
    static AIPlayer mate(Location at, int prayer) {
        AIPlayer b = TestCR1.bot(at, 40, 40, 40, 40);
        b.getSkills().setStaticLevel(Skills.PRAYER, prayer); b.getSkills().setLevel(Skills.PRAYER, prayer); b.getSkills().setPrayerPoints(prayer);
        try { b.getSkills().updateCombatLevel(); } catch (Throwable ignored) { }
        return b;
    }
    /** A dead crew bot: a brain of its own (nothing ticks it but this test), a stone at {@code at}, the death latch set. */
    static Object[] scenario(String tag, Location at, String killer, int prayer, Location mateAt) {
        AIPlayer dead = TestCR1.bot(at, 30, 20, 20, 20);
        SmartAdventurers.Brain db = new SmartAdventurers.Brain(); db.bot = dead; db.scriptAPI = new core.game.bots.ScriptAPI(dead); db.nextThink = Long.MAX_VALUE;
        SmartAdventurers.BRAINS.put(dead.getUsername().toLowerCase(java.util.Locale.ROOT), db);
        AIPlayer m = mate(mateAt, prayer);
        SmartAdventurers.Brain mb = TestCR1.rig(m, false);
        SmartAdventurers.Crew c = new SmartAdventurers.Crew(1); c.members.add(db); c.members.add(mb); c.leader = mb; db.crew = c; mb.crew = c;
        return new Object[]{null, m, 0, 0.0, dead, db, at, killer, tag};
    }
    /** Ten ticks on (everyone where the teleports put them): the stone, the bot back at Lumbridge, its brain's death check. */
    static void die(Object[] s) {
        AIPlayer dead = (AIPlayer) s[4]; SmartAdventurers.Brain db = (SmartAdventurers.Brain) s[5]; Location at = (Location) s[6]; AIPlayer m = (AIPlayer) s[1];
        Grave g = grave(dead, at);
        dead.getProperties().setTeleportLocation(TestCR1.LUMBRIDGE);
        db.diedAt = at; db.diedKiller = (String) s[7];
        log(s[8] + ": " + dead.getUsername() + "'s stone at " + at + " (" + g.getTicksRemaining() + " ticks), crewmate " + m.getUsername() + " Prayer " + m.getSkills().getStaticLevel(Skills.PRAYER) + " at " + m.getLocation() + ", killer " + s[7]);
        db.checkDeath();
        s[0] = g; s[2] = g.getTicksRemaining(); s[3] = m.getSkills().getPrayerPoints();
    }
    static String outcome(String tag, Object[] s) {
        Grave g = (Grave) s[0]; AIPlayer m = (AIPlayer) s[1];
        return tag + ": blessed " + g.getAttribute("blessed", false) + ", repaired " + g.getAttribute("repaired", false) + ", ticks " + s[2] + " -> " + g.getTicksRemaining()
                + ", prayer points " + s[3] + " -> " + m.getSkills().getPrayerPoints() + ", crewmate " + (int) m.getLocation().getDistance(g.getLocation()) + " tiles from the stone";
    }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b15b3")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; Object[] s1, s2, s3, s4; Grave pg; AIPlayer p, q; int qDist = -1, pTicks0;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) s1 = scenario("S1", Location.create(3236, 3210, 0), "no monster (heat, poison or a fall)", 70, Location.create(3236, 3216, 0));
                    if (t == 152) s2 = scenario("S2", Location.create(3236, 3195, 0), "no monster (heat, poison or a fall)", 30, Location.create(3240, 3195, 0));
                    if (t == 156) s4 = scenario("S4", Location.create(3258, 3272, 0), "Cow (level 2)", 70, Location.create(3254, 3266, 0));
                    if (t == 158) { p = TestCR1.bot(Location.create(3236, 3180, 0), 30, 20, 20, 20); q = TestCR1.bot(Location.create(3236, 3165, 0), 30, 20, 20, 20); }
                    if (t == 166) { die(s1); die(s2); die(s4); }
                    if (t == 168) {
                        pg = grave(p, Location.create(3236, 3180, 0)); pTicks0 = pg.getTicksRemaining();
                        p.getProperties().setTeleportLocation(TestCR1.LUMBRIDGE);
                        log("S5: " + p.getUsername() + "'s stone at " + pg.getLocation() + " (" + pTicks0 + " ticks); " + q.getUsername() + " at " + q.getLocation() + " asked to bless it: " + TradeTalk.blessGrave(q, p));
                    }
                    if (pg != null && qDist < 0 && pg.getTicksRemaining() >= 1400) { qDist = (int) q.getLocation().getDistance(pg.getLocation()); log("S5 stone blessed at tick " + t + " with " + q.getUsername() + " at " + q.getLocation() + ", " + qDist + " tiles from it"); }
                    if (t == 260) {
                        log("RESULT " + outcome("S1 Prayer 70", s1));
                        log("RESULT " + outcome("S2 Prayer 30", s2));
                        String wild; // S3: the engine refuses a stone in the Wilderness (allowGenerate), so the danger rule itself is asked
                        try { java.lang.reflect.Method dm = SmartAdventurers.class.getDeclaredMethod("graveDanger", Location.class, String.class, core.game.node.entity.player.Player.class); dm.setAccessible(true); wild = String.valueOf(dm.invoke(null, Location.create(3090, 3560, 0), "no monster (heat, poison or a fall)", q)); } catch (NoSuchMethodException e) { wild = "no danger rule in this build"; }
                        log("RESULT S3 Wilderness stone at [3090, 3560, 0]: graveDanger -> " + wild);
                        log("RESULT " + outcome("S4 killer cow still there", s4));
                        log("RESULT S5 player request: " + (qDist < 0 ? "never blessed (ticks " + pTicks0 + " -> " + pg.getTicksRemaining() + ", " + q.getUsername() + " " + (int) q.getLocation().getDistance(pg.getLocation()) + " tiles off)" : "blessed with the bot " + qDist + " tiles from the stone"));
                        log("DONE"); return true;
                    }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
