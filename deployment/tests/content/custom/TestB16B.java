package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.npc.NPC;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.List;
import java.util.Locale;

/**
 * Trial only (-Dtest.b16b=true): batch 16 item2. A quiet field (3150,3300): a stand-in human H (artificial=false) and
 * bots X, Y (rigged brains) 2 tiles away.
 * 1. Combat memory: after Watch.poll has run, H gets the engine's NPCKillEvent for a goblin X hit too (its uid in the
 *    goblin's impact log): Memory remembers "helped|X|goblin"; X's answers to "hello" x10 (a fight greeting at most once).
 *    Then a SelfDeathEvent (killed by the goblin): "died|X|goblin", and Y's greeting x10 (a second-hand line).
 * 2. Home-town banter: X from Lumbridge, Y from Varrock (SmartAdventurers.HOME/STORY), banter() x60 with cooldowns
 *    cleared: (a) the rivalry rate gap cleared each time: rivalry share of exchanges (about 1 in 5 of the non-gossip
 *    ones); (b) rate limit kept: 60 more calls -> at most one more. Same-town pair (both Lumbridge) x60 -> none.
 * 3. Trust: H in the Black Arm Gang asks X for a Heroes' Quest partner: Grip's key ring (untradeable) is dropped for
 *    them (Companions.partner): Trust before/after, and a second ask within the hour (no second rise).
 */
public final class TestB16B implements StartupListener {
    static void log(String s) { System.out.println("[TestB16B] " + s); }
    static Object fget(Object o, String n) { try { java.lang.reflect.Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o); } catch (Throwable t) { return "n/a"; } }
    static Object bcget(String n) { try { java.lang.reflect.Field f = BotChat.class.getDeclaredField(n); f.setAccessible(true); return f.get(null); } catch (Throwable t) { return "n/a"; } }
    static void bcset(String n, Object v) { try { java.lang.reflect.Field f = BotChat.class.getDeclaredField(n); f.setAccessible(true); f.set(null, v); } catch (Throwable ignored) { } }
    static String key(core.game.node.entity.player.Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b16b")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer x, y, h; NPC gob; Location at = Location.create(3150, 3300, 0);
            String greet(AIPlayer b, int n) {
                StringBuilder s = new StringBuilder(); int fights = 0;
                for (int i = 0; i < n; i++) {
                    List<String> out = BotChat.Brain.answer(b, BotChat.Persona.of(b), h, "hello", true);
                    String l = out == null || out.isEmpty() ? "-" : out.get(0);
                    String lo = l.toLowerCase(Locale.ROOT);
                    if (lo.contains("goblin")) { fights++; s.append(" [FIGHT] ").append(l); }
                }
                return fights + " fight greeting(s) in " + n + s;
            }
            String met() { BotChat.Memory.Met m = BotChat.Memory.get(h); return m == null ? "none" : "times " + m.times + ", fight " + fget(m, "fight"); }
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) {
                        x = TestCR1.bot(at, 40, 20, 20, 20); y = TestCR1.bot(at.transform(1, 0, 0), 40, 20, 20, 20);
                        TestCR1.rig(x, false); TestCR1.rig(y, false);
                        h = TestA1.human(at.transform(2, 0, 0), 900400);
                        gob = NPC.create(100, at.transform(0, 2, 0)); gob.init();
                        log("X " + x.getUsername() + ", Y " + y.getUsername() + ", H " + h.getUsername() + ", goblin " + gob.getName());
                    }
                    if (t == 200) { // Watch.poll (every 16 ticks) has seen H by now
                        BotChat.Memory.saw(h); BotChat.Memory.saw(h);
                        gob.getImpactHandler().getPlayerImpactLog().put(x.getDetails().getUid(), 4);
                        gob.getImpactHandler().getPlayerImpactLog().put(h.getDetails().getUid(), 6);
                        h.dispatch(new core.game.event.NPCKillEvent(gob));
                        log("RESULT 1a after a kill X helped with: memory " + met());
                        log("RESULT 1a X greets: " + greet(x, 10));
                        log("RESULT 1a memory after: " + met());
                        h.dispatch(new core.game.event.SelfDeathEvent(gob));
                        log("RESULT 1b after dying to the goblin near X and Y: memory " + met());
                        log("RESULT 1b Y greets: " + greet(y, 10));
                    }
                    if (t == 210) {
                        SmartAdventurers.HOME.put(key(x), BotLife.Town.LUMBRIDGE.ordinal()); SmartAdventurers.HOME.put(key(y), BotLife.Town.VARROCK.ordinal());
                        SmartAdventurers.STORY.put(key(x), new String[]{"Onwards.", "goblins"}); SmartAdventurers.STORY.put(key(y), new String[]{"Onwards.", "chickens"});
                        log("stories: X " + String.join("/", SmartAdventurers.story(x)) + ", Y " + String.join("/", SmartAdventurers.story(y)));
                        int[] r = new int[3];
                        for (int pass = 0; pass < 3; pass++) {
                            if (pass == 2) SmartAdventurers.HOME.put(key(y), BotLife.Town.LUMBRIDGE.ordinal());
                            Object r0 = bcget("rivalries");
                            for (int i = 0; i < 60; i++) {
                                BotChat.BOT_COOLDOWN.clear();
                                if (pass != 1) bcset("nextRivalry", 0L);
                                BotChat.banter();
                            }
                            Object r1 = bcget("rivalries");
                            r[pass] = r0 instanceof Integer && r1 instanceof Integer ? (Integer) r1 - (Integer) r0 : -1;
                        }
                        log("RESULT 2a Lumbridge vs Varrock, gap cleared: rivalry exchanges " + r[0] + " in 60 (n/a = -1)");
                        log("RESULT 2b Lumbridge vs Varrock, rate limit kept: " + r[1] + " in 60 more");
                        log("RESULT 2c Lumbridge vs Lumbridge: " + r[2] + " in 60");
                        BotChat.BOT_COOLDOWN.clear();
                    }
                    if (t == 220) {
                        h.setAttribute("/save:black-arm-gang", true);
                        int t0 = BotChat.Trust.of(h, x);
                        String a1 = Companions.partner(x, h, "i need a phoenix partner for heroes quest");
                        int t1 = BotChat.Trust.of(h, x);
                        for (core.game.node.item.GroundItem gi : core.game.node.item.GroundItemManager.getItems()) if (gi != null && gi.isActive() && gi.getId() == 1588 && gi.getLocation().getDistance(x.getLocation()) <= 2) { core.game.node.item.GroundItemManager.destroy(gi); break; }
                        String a2 = Companions.partner(x, h, "i need a phoenix partner for heroes quest");
                        int t2 = BotChat.Trust.of(h, x);
                        log("RESULT 3 Grip's key ring dropped for H: X says \"" + a1 + "\"; trust " + t0 + " -> " + t1 + "; asked again (key gone): \"" + a2 + "\" trust " + t2);
                    }
                    if (t == 225) { log("DONE"); return true; }
                } catch (Throwable ex) { log("error " + ex); ex.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
