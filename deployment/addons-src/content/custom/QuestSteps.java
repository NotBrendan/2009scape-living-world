package content.custom;

import core.game.bots.AIPlayer;
import core.game.interaction.IntType;
import core.game.interaction.InteractionListeners;
import core.game.interaction.Option;
import core.game.node.Node;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The building blocks of a quest script (QuestScript): walk somewhere, talk to someone, use a ladder, collect items,
 * buy, kill, wait for a stage. Each step has a time limit and an optional checkpoint: a step whose checkpoint already
 * holds is skipped, so a bot that comes back after a restart (or already had the flour) carries on where it is.
 * A step ticks once a game tick from its QuestRunner and answers RUNNING, DONE or FAILED.
 */
final class QuestSteps {
    private QuestSteps() { }

    enum St { RUNNING, DONE, FAILED }

    /** NPC id -> the bot talking to it for a quest. */
    static final java.util.Map<Integer, String> TALKING = new java.util.concurrent.ConcurrentHashMap<>();

    static final int WALK_LIMIT = 800, TALK_LIMIT = 150, COLLECT_LIMIT = 3000, KILL_LIMIT = 2000; // ticks: 8 min, 90 s, 30 min, 20 min

    abstract static class Step {
        final String name;
        int limitTicks;
        Predicate<Player> done;
        boolean boss;        // a quest boss: one bot at a time, never with a player nearby (QuestRunner)
        boolean recheck;     // the checkpoint may come true mid-step (collecting, waiting): finish as soon as it does
        boolean xpStep;      // talk, use and interact steps: quest XP at the 2009 amount (QuestRunner sets the multiplier)
        boolean optional;    // a failure just moves on (a quick look in the bank for a bead before the hunt)
        String failure;

        Step(String name, int limitTicks) { this.name = name; this.limitTicks = limitTicks; }

        Step done(Predicate<Player> p) { this.done = p; return this; }
        Step boss() { this.boss = true; return this; }
        Step optional() { this.optional = true; return this; }

        boolean checkpoint(Player p) {
            if (done == null) return false;
            try { return done.test(p); } catch (Throwable t) { return false; }
        }

        void begin(QuestRunner r) { failure = null; }
        abstract St tick(QuestRunner r);
        void end(QuestRunner r) { }
        St fail(String why) { failure = why; return St.FAILED; }
        String doing() { return name; }
        /** Extra detail for the failure log (a dialogue's seen() list). */
        String trace() { return null; }
    }

    // ------------------------------------------------------------------ walking

    /** Walks a bot towards a tile on its own plane: the bots' planner, the 40-tick stall rule, a paid teleport after 4 retries. */
    static final class Mover {
        Location dest; int radius; double best; int stall, retries; String last = "";
        Location lastAt; // pathing review 10-23 Q-1: where the bot was last tick
        QuestFloors.Climber climber; // made when a walk first needs another floor (wave 2 multi-floor walking)
        int doorWait; // quest fix 10-23: ticks left for a door click to walk up and open it
        int teles; // batch 22 B21-8: paid teleports on this walk (stall and long-walk), capped at TELES_MAX: a trap dest lands at a bank it can't walk back from
        static final int TELES_MAX = Math.max(1, Integer.getInteger("quest.teles", 3)); // -Dquest.teles=<n>; 3 = one long-walk teleport and two stall ones

        void reset(Location d, int r) { dest = d; radius = r; best = Double.MAX_VALUE; stall = 0; retries = 0; last = ""; lastAt = null; doorWait = 0; teles = 0; if (climber != null) climber.reset(); }

        St tick(Player bot) {
            Location here = bot.getLocation();
            if (bot.isArtificial() && AfkTasks.Runner.essenceMine(here) && !AfkTasks.Runner.essenceMine(dest)) { // quest fix 10-23: in the rune essence mine when the walk starts (live: "stuck at [2935, 4815, 0] with no teleport fare"): out by the portal, free, to Aubury's shop, as AfkTasks.goTo leaves it
                if (bot.getProperties().getTeleportLocation() == null) { bot.getPulseManager().clear(); bot.getProperties().setTeleportLocation(Location.create(3253, 3398, 0)); }
                best = Double.MAX_VALUE; stall = 0; last = "out through the essence mine portal";
                return St.RUNNING;
            }
            if (!QuestFloors.samePlane(here, dest)) { // another floor, or underground: climb first (it used to fail "on floor X, need Y")
                if (climber == null) climber = new QuestFloors.Climber();
                St c = climber.tick(bot, dest);
                last = climber.last;
                if (c == St.FAILED) return St.FAILED;
                if (QuestFloors.samePlane(bot.getLocation(), dest)) { best = Double.MAX_VALUE; stall = 0; }
                return St.RUNNING;
            }
            double d = here.getDistance(dest);
            if (d <= radius) return St.DONE;
            if (RouteMap.usingShortcut(bot)) { stall = 0; return St.RUNNING; }
            if (d < best - 0.5) { best = d; stall = 0; } // (pathing review 10-23 Q-1 "moving on a route is progress" held back: in the batch 12 soak it let bots shuffle on a route until the 800-tick limit)
            else if ((stall == 10 || stall == 25 || d <= 8 && stall > 0 && stall % 8 == 0) && !bot.getWalkingQueue().isMoving() && openDoorToward(bot, dest, stall == 10 ? 3 : 6)) { stall++; doorWait = 8; last = "opened a door"; return St.RUNNING; } // close to the target and not getting closer: a door in the way, almost always
            else if ((stall == 18 || stall == 33) && !bot.getWalkingQueue().isMoving() && openDoorToward(bot, here, 6)) { stall++; doorWait = 8; last = "opened a door out"; return St.RUNNING; } // shut in (inside Fred's house after the hop): any door
            else if (++stall > 40) {
                try { RouteMap.stuck(bot, dest); } catch (Throwable ignored) { }
                stall = 0; best = d;
                if (++retries > 4) {
                    long c0 = Ledger.Wallet.coins(bot);
                    if (teles < TELES_MAX && Ledger.Wallet.payTeleport(bot)) {
                        teles++;
                        bot.getPulseManager().clear();
                        Location land = Watchdog.landing(dest);
                        Spectacle.teleport(bot, land); // batch 15 item1
                        System.out.println("[QuestSteps] " + bot.getUsername() + " stuck at " + here + ", paid teleport " + teles + "/" + TELES_MAX + " (" + (c0 - Ledger.Wallet.coins(bot)) + " gp) to " + land + " for " + dest); // batch 22 B21-8: the fare in the log
                        retries = 0; best = Double.MAX_VALUE; last = "teleported";
                        return St.RUNNING;
                    }
                    last = teles >= TELES_MAX ? "stuck at " + here + " after " + teles + " paid teleports" : "stuck at " + here + " with no teleport fare";
                    return St.FAILED;
                }
                // shake loose: a walk 18 tiles off to one side (as shopTick does), so a straight line into a river or a wall gets round it;
                // a few random steps if no side tile is walkable (a bot at 3089,3216 bound for Rimmington stood still five retries)
                Location side = null;
                try {
                    double ang = Math.atan2(dest.getY() - here.getY(), dest.getX() - here.getX()) + ((retries & 1) == 0 ? 1 : -1) * (Math.PI / 2 + Math.random() * 0.6);
                    if (here.getZ() == 0) side = Surveyors.Surveyor.walkable(Location.create(here.getX() + (int) Math.round(Math.cos(ang) * 18), here.getY() + (int) Math.round(Math.sin(ang) * 18), 0));
                } catch (Throwable ignored) { }
                try { if (bot instanceof AIPlayer) { if (side != null) ((AIPlayer) bot).walkToPosSmart(side); else ((AIPlayer) bot).randomWalk(4, 4); } } catch (Throwable ignored) { }
                last = side != null ? "sidestep" : "stalled";
                return St.RUNNING;
            }
            if (bot.getWalkingQueue().isMoving()) return St.RUNNING;
            if (doorWait > 0) { doorWait--; if (bot.getPulseManager().hasPulseRunning()) return St.RUNNING; } // quest fix 10-23: the door click's walk-up pulse starts before the bot moves; a new walk here cancelled it, so the door never opened (live: "stuck at [3244, 3382, 0]" in the Phoenix ladder room, door 24381 at 3241,3382 a step away)
            if (d > AfkTasks.Runner.TELEPORT_MIN && teles < TELES_MAX && Ledger.Wallet.coins(bot) >= 5_000 && Ledger.Wallet.payTeleport(bot)) { // batch 22 B21-8: counted: a trap dest's landing (its nearest bank) can be TELEPORT_MIN+ away, and this paid again every tick down to 5,000 gp
                teles++; Spectacle.teleport(bot, Watchdog.landing(dest)); last = "teleported"; return St.RUNNING; // batch 15 item1
            }
            int toll = AfkTasks.Runner.tollHop(bot, dest); // the Al Kharid gate
            if (toll < 0 && NavPlanner.TOLL_ON && AfkTasks.Runner.tollStuck(bot, dest)) { last = "can't pay the Al Kharid toll"; return St.FAILED; } // batch 21 A: not parked at the gate (-Dnav.toll=false: the batch 20 way); review fix 2: only with no toll-free route
            if (toll > 0 || toll < 0 && !NavPlanner.TOLL_ON) return St.RUNNING; // paid and through (or, with -Dnav.toll=false, can't pay: waits at the gate as in batch 20); review fix 2: broke with a route round the fence: walks on as a non-payer (NavWalker.step plans without the toll under 10 gp)
            Location target = dest;
            Location via = retries >= 2 ? null : AfkTasks.Runner.bridgeVia(bot, here, dest); // the castle-exit and river rules can loop (inside Lumbridge castle bound for the general store): after two retries, without them
            if (via != null && here.getDistance(via) > 3) { target = via; last = "via"; try { RouteMap.PLANS.remove(bot.getUsername().toLowerCase(Locale.ROOT)); NavWalker.forget(bot); } catch (Throwable ignored) { } } // batch 17 item9: plan again from where the detour ends (the old plan sent it back east, Draynor jail ping-pong)
            else {
                Location hop = null;
                if (NavWalker.ON) { try { hop = NavWalker.step(bot, dest, true); } catch (Throwable ignored) { } } // batch 21 A: pays the toll (tollHop above)
                if (hop == null) try { hop = RouteMap.nextHop(bot, dest); } catch (Throwable ignored) { }
                if (RouteMap.usingShortcut(bot)) { last = "route"; return St.RUNNING; } // pathing review 10-23 R-2: the route's shortcut is being used: walking to "here" reset the walk its click had started
                if (hop != null) { target = hop; last = "route"; } else last = "direct";
            }
            double td = here.getDistance(target);
            if (td > 25) { // a leg at a time
                Location part = Location.create(here.getX() + (int) Math.round((target.getX() - here.getX()) * 25 / td), here.getY() + (int) Math.round((target.getY() - here.getY()) * 25 / td), here.getZ());
                Location w = here.getZ() == 0 ? Surveyors.Surveyor.walkable(part) : null;
                target = w != null ? w : part;
            }
            try { if (bot instanceof AIPlayer) ((AIPlayer) bot).walkToPosSmart(target); } catch (Throwable ignored) { }
            return St.RUNNING;
        }
    }

    /** Walk to a tile, on any floor: the Mover climbs stairs and ladders when the tile is on another one (QuestFloors). */
    static final class Walk extends Step {
        final Location to; final int radius; final Mover m = new Mover();
        Walk(String name, Location to, int radius) { super(name, WALK_LIMIT); this.to = to; this.radius = radius; this.done = p -> p.getLocation().getZ() == to.getZ() && p.getLocation().getDistance(to) <= radius; }
        @Override void begin(QuestRunner r) { super.begin(r); m.reset(to, radius); }
        @Override St tick(QuestRunner r) {
            St s = m.tick(r.bot);
            if (s == St.FAILED && r.bot.getLocation().getZ() == to.getZ() && r.bot.getLocation().getDistance(to) <= 6) { r.log("  " + name + ": stuck " + (int) r.bot.getLocation().getDistance(to) + " tiles short at " + r.bot.getLocation() + ", close enough"); return St.DONE; } // a wall or a door the walker can't open: the next step's own click paths the last few tiles
            if (s == St.RUNNING && m.retries >= 2 && r.bot.getLocation().getZ() == to.getZ() && r.bot.getLocation().getDistance(to) <= 6) return St.DONE; // same, without waiting for the teleport rule
            return s == St.FAILED ? fail("walk to " + to + ": " + m.last) : s;
        }
        @Override String trace() { return "last " + m.last; }
    }

    // ------------------------------------------------------------------ finding things

    static NPC findNpc(Player bot, Location near, int radius, int[] ids, String nameLower) {
        NPC best = null; double bd = Double.MAX_VALUE;
        List<NPC> list = near != null ? RegionManager.getLocalNpcs(near, radius) : RegionManager.getLocalNpcs(bot, radius);
        for (NPC n : list) {
            if (n == null || !n.isActive() || n.getLocation().getZ() != bot.getLocation().getZ()) continue;
            boolean ok = ids != null ? contains(ids, n.getId()) : nameLower != null && n.getName() != null && n.getName().toLowerCase(Locale.ROOT).equals(nameLower);
            if (!ok) continue;
            double d = n.getLocation().getDistance(bot.getLocation());
            if (d < bd) { bd = d; best = n; }
        }
        return best;
    }

    static Scenery findScenery(Player bot, Location near, int radius, int[] ids, String nameLower, String option) {
        Scenery best = null; double bd = Double.MAX_VALUE;
        Location c = near != null ? near : bot.getLocation();
        for (int dx = -radius; dx <= radius; dx++) for (int dy = -radius; dy <= radius; dy++) {
            Scenery s = null;
            try {
                if (ids != null) { for (int id : ids) { s = RegionManager.getObject(c.getZ(), c.getX() + dx, c.getY() + dy, id); if (s != null) break; } } // by id: also objects sharing a tile with another (the mill's flour bin)
                else s = RegionManager.getObject(c.getZ(), c.getX() + dx, c.getY() + dy);
            } catch (Throwable t) { continue; }
            if (s == null || !s.isActive()) continue;
            s = childOf(s, bot);
            boolean ok = ids != null ? contains(ids, s.getId()) : nameLower != null && s.getName() != null && s.getName().toLowerCase(Locale.ROOT).equals(nameLower);
            if (!ok) continue;
            if (option != null && !hasOption(s, option)) continue;
            double d = s.getLocation().getDistance(bot.getLocation());
            if (d < bd) { bd = d; best = s; }
        }
        if (best == null && ids != null) { // objects the map lookup hides (not renderable until a varbit shows them, e.g. the Mill Lane flour bin)
            try {
                for (Scenery s : RegionManager.getRegionPlane(c).getObjectList()) {
                    if (s == null) continue;
                    Scenery w = s;
                    s = childOf(w, bot);
                    if (!contains(ids, s.getId())) { try { if (w.getChilds() != null) for (Scenery ch : w.getChilds()) if (ch != null && contains(ids, ch.getId())) { s = placed(ch, w); break; } } catch (Throwable ignored) { } } // varp not set for this bot yet (restored after a restart): any of its forms will do
                    if (!contains(ids, s.getId()) || s.getLocation().getZ() != c.getZ() || s.getLocation().getDistance(c) > radius) continue;
                    double d = s.getLocation().getDistance(bot.getLocation());
                    if (d < bd) { bd = d; best = s; }
                }
            } catch (Throwable ignored) { }
        }
        return best;
    }

    /** A varbit object (a null-named wrapper, e.g. the Mill Lane flour bin 36880) is what this bot sees of it: its child, as a client click resolves it. */
    static Scenery childOf(Scenery s, Player bot) {
        try { if (s.getChilds() != null && s.getChilds().length > 0) { Scenery ch = s.getChild(bot); if (ch != null) return placed(ch, s); } } catch (Throwable ignored) { }
        return s;
    }

    /** A child form standing where its wrapper stands (children can come without a location of their own). */
    static Scenery placed(Scenery ch, Scenery w) {
        if (ch.getLocation() != null) return ch;
        return new Scenery(ch.getId(), w.getLocation(), w.getType(), w.getRotation());
    }

    /** A shut door or gate within 3 tiles that stands between the bot and where it is going: open it, like a player. */
    static boolean openDoorToward(Player bot, Location to) { return openDoorToward(bot, to, 3); }

    static boolean openDoorToward(Player bot, Location to, int r) {
        Location here = bot.getLocation();
        double d0 = here.getDistance(to);
        Scenery best = null; double bd = 99;
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) {
            Scenery s;
            try { s = RegionManager.getObject(here.getZ(), here.getX() + dx, here.getY() + dy); } catch (Throwable t) { continue; }
            if (s == null || s.getName() == null || !hasOption(s, "Open")) continue;
            String n = s.getName().toLowerCase(Locale.ROOT);
            if (!n.contains("door") && !n.contains("gate")) continue;
            if (to != here && s.getLocation().getDistance(to) >= d0 + 3) continue; // well behind us (a gate a little off the line, like Fred's yard gate, still counts); to == here: any door (shut in a house)
            String k = bot.getUsername() + "@" + s.getLocation();
            Long when = OPENED.get(k);
            if (when != null && System.currentTimeMillis() - when < 20_000) continue; // tried that one just now: try the next
            double d = s.getLocation().getDistance(here);
            if (d < bd) { bd = d; best = s; }
        }
        if (best == null) return false;
        OPENED.put(bot.getUsername() + "@" + best.getLocation(), System.currentTimeMillis());
        if (OPENED.size() > 500) OPENED.clear();
        return click(bot, best, "Open", IntType.SCENERY);
    }

    /** bot@door -> when this bot last opened it (so a stuck bot works through the doors and gates around it in turn). */
    static final java.util.Map<String, Long> OPENED = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean contains(int[] a, int v) { for (int x : a) if (x == v) return true; return false; }

    static boolean hasOption(Node node, String option) {
        try { for (Option o : node.getInteraction().getOptions()) if (o != null && o.getName().equalsIgnoreCase(option)) return true; } catch (Throwable ignored) { }
        return false;
    }

    /** The same click as AfkTasks.Runner.interact: the content listener first, the old handler as the fallback. */
    static boolean click(Player p, Node node, String option, IntType type) {
        Option opt = null;
        for (Option o : node.getInteraction().getOptions()) if (o != null && o.getName().equalsIgnoreCase(option)) opt = o;
        if (opt == null) return false;
        if (!InteractionListeners.run(node.getId(), type, opt.getName(), p, node)) node.getInteraction().handle(p, opt);
        return true;
    }

    static void clearPopups(Player p) {
        try { if (p.getDialogueInterpreter().getDialogue() != null) p.getDialogueInterpreter().close(); p.getInterfaceManager().closeChatbox(); if (p.hasModalOpen()) p.getInterfaceManager().close(); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ talking

    /** Talk to an NPC within 16 tiles and click through its dialogue with the given picks. */
    static final class Talk extends Step {
        final int[] ids; final Object[] picks; Predicate<Player> until; Function<Player, Object[]> pickFn;
        DialogueDriver driver; int tries, settle, approach; final Mover m = new Mover(); Location seenAt, approachFrom;
        Talk(String name, int[] ids, Object... picks) { super(name, TALK_LIMIT); this.ids = ids; this.picks = picks; this.xpStep = true; }
        Talk until(Predicate<Player> u) { this.until = u; return this; }
        /** Picks that depend on the bot's state (a different opening line after something happened). */
        Talk picks(Function<Player, Object[]> f) { this.pickFn = f; return this; }
        @Override void begin(QuestRunner r) { super.begin(r); driver = null; tries = 0; settle = 0; approach = 0; seenAt = null; m.dest = null; }
        @Override void end(QuestRunner r) { TALKING.remove(ids[0], QuestRunner.key(r.bot)); }
        @Override St tick(QuestRunner r) {
            Player bot = r.bot;
            if (driver == null) {
                NPC n = findNpc(bot, null, 20, ids, null);
                if (n == null && seenAt != null) { // wandered off on the walk up: back to where they were
                    if (m.dest == null || !m.dest.equals(seenAt)) m.reset(seenAt, 2);
                    St w = m.tick(bot);
                    if (w == St.FAILED) return fail("lost sight of " + Arrays.toString(ids) + ": " + m.last);
                    return St.RUNNING;
                }
                if (n == null) return fail("nobody to talk to (ids " + Arrays.toString(ids) + ") within 20 of " + bot.getLocation());
                seenAt = n.getLocation();
                if (approach > 0) { // the last click couldn't path to them: walk right up with the quest walker (it opens doors), then click again
                    if (approach == 120) approachFrom = bot.getLocation();
                    approach--;
                    if (m.dest == null || m.dest.getDistance(n.getLocation()) > 1) m.reset(n.getLocation(), 1);
                    if (bot.getLocation().getDistance(n.getLocation()) > 1.5 && m.tick(bot) == St.RUNNING) {
                        if (approach > 0) return St.RUNNING;
                        // 120 ticks and not one tile further: a dead-end tile (seen south of Fred's house at 3188,3269). Hop next to
                        // them, as the Watchdog's free rescue would, rather than give the quest up.
                        if (approachFrom != null && bot.getLocation().equals(approachFrom) && bot.getLocation().getDistance(n.getLocation()) <= 10) {
                            r.log("  stuck at " + bot.getLocation() + " within " + (int) bot.getLocation().getDistance(n.getLocation()) + " of " + n.getName() + ": hopping next to them");
                            bot.getPulseManager().clear();
                            Spectacle.teleport(bot, n.getLocation()); // batch 15 item1
                            return St.RUNNING;
                        }
                    }
                    approach = 0;
                }
                String holder = TALKING.get(ids[0]); // one bot at a time with a quest giver: two at once lost a quest start at Doric's
                if (holder != null && !holder.equals(QuestRunner.key(bot)) && QuestRunner.ACTIVE.containsKey(holder)) { limitTicks = Math.max(limitTicks, TALK_LIMIT * 3); return St.RUNNING; }
                TALKING.put(ids[0], QuestRunner.key(bot));
                clearPopups(bot);
                if (!click(bot, n, "Talk-to", IntType.NPC)) return fail(n.getName() + " has no Talk-to");
                driver = new DialogueDriver(bot, pickFn != null ? pickFn.apply(bot) : picks);
                tries++;
                return St.RUNNING;
            }
            DialogueDriver.Status s = driver.tick();
            if (s == DialogueDriver.Status.RUNNING) return St.RUNNING;
            if (s == DialogueDriver.Status.FAILED) {
                if ("dialogue never opened".equals(driver.failure()) && tries < 3) {
                    NPC n = findNpc(bot, null, 16, ids, null);
                    boolean door = n != null && openDoorToward(bot, n.getLocation());
                    r.log("  talk didn't open, walking up and trying again" + (door ? " (opened a door first)" : ""));
                    TALKING.remove(ids[0], QuestRunner.key(bot)); // let the next bot have a go while this one walks up
                    driver = null; approach = 120; m.dest = null; limitTicks = Math.max(limitTicks, TALK_LIMIT * 3); return St.RUNNING;
                }
                return fail("dialogue: " + driver.failure());
            }
            if (until == null) return St.DONE;
            if (until.test(bot)) return St.DONE;
            if (++settle < 10) return St.RUNNING; // stages can land a few ticks after the last screen
            return fail("dialogue ended but its condition didn't hold");
        }
        @Override String trace() { return driver == null ? null : "seen " + driver.seen(); }
        @Override String doing() { return name; }
    }

    // ------------------------------------------------------------------ scenery and NPC options (ladders, stairs, hoppers)

    static final class Interact extends Step {
        final int[] ids; final String nameLower, option; final Location near; final int radius; final boolean npc;
        Predicate<Player> until; Object[] picks; int clicks, waitTicks; DialogueDriver driver; Location clickedFrom;
        Interact(String name, int[] ids, String targetName, String option, Location near, int radius, boolean npc) {
            super(name, 300);
            this.ids = ids; this.nameLower = targetName == null ? null : targetName.toLowerCase(Locale.ROOT); this.option = option; this.near = near; this.radius = radius; this.npc = npc;
            this.xpStep = true;
        }
        Interact until(Predicate<Player> u) { this.until = u; return this; }
        /** Picks for a dialogue the option opens (e.g. "Climb up or down?"). */
        Interact picks(Object... p) { this.picks = p; return this; }
        @Override void begin(QuestRunner r) { super.begin(r); clicks = 0; waitTicks = 0; driver = null; }
        @Override St tick(QuestRunner r) {
            Player bot = r.bot; lastBot = bot;
            if (until != null && until.test(bot) && driver == null) return St.DONE;
            if (driver != null) {
                DialogueDriver.Status s = driver.tick();
                if (s == DialogueDriver.Status.RUNNING) return St.RUNNING;
                if (s == DialogueDriver.Status.FAILED) return fail("dialogue after " + option + ": " + driver.failure());
                driver = null; waitTicks = 0;
                return until == null ? St.DONE : St.RUNNING;
            }
            if (clicks > 0) {
                if (picks != null && DialogueDriver.open(bot)) { driver = new DialogueDriver(bot, picks); return St.RUNNING; }
                if (bot.getWalkingQueue().isMoving() || bot.getPulseManager().hasPulseRunning()) return St.RUNNING;
                if (++waitTicks < 6) return St.RUNNING;
                if (until == null) return St.DONE;
                if (clicks >= 5) return fail(option + " on " + label() + " didn't do it (5 clicks)");
                if (target != null && lastAt != null && openDoorToward(bot, lastAt, 6)) { r.log("  opened a door on the way to " + label()); waitTicks = 0; return St.RUNNING; }
            }
            Node n = npc ? findNpc(bot, near, radius, ids, nameLower) : findScenery(bot, near, radius, ids, nameLower, option);
            if (n == null) return fail("no " + label() + " with " + option + " near " + (near != null ? near : bot.getLocation()));
            clearPopups(bot);
            if (!npc && clicks >= 2 && option.toLowerCase(Locale.ROOT).startsWith("climb") && bot.getLocation().getDistance(n.getLocation()) <= 3) { // a stair the click didn't take: the climb handler directly, as its listener would
                Scenery sc = (Scenery) n;
                boolean ok = false;
                try { ok = core.game.global.action.ClimbActionHandler.climbLadder(bot, sc, option.toLowerCase(Locale.ROOT)); } catch (Throwable ignored) { }
                if (!ok) {
                    Location dest = null;
                    try { dest = core.game.global.action.ClimbActionHandler.getDestination(sc); } catch (Throwable ignored) { }
                    if (dest != null) { core.game.global.action.ClimbActionHandler.climb(bot, option.toLowerCase(Locale.ROOT).contains("down") ? core.game.global.action.ClimbActionHandler.CLIMB_DOWN : core.game.global.action.ClimbActionHandler.CLIMB_UP, dest); ok = true; }
                }
                r.log("  climb fallback on " + sc.getId() + "@" + sc.getLocation() + ": " + (ok ? "climbing" : "no destination"));
                clicks++; waitTicks = 0; target = sc.getId() + "@" + sc.getLocation() + " (fallback)";
                return St.RUNNING;
            }
            if (!click(bot, n, option, npc ? IntType.NPC : IntType.SCENERY)) return fail(label() + " has no " + option);
            clicks++; waitTicks = 0; clickedFrom = bot.getLocation(); target = n.getId() + "@" + n.getLocation(); lastAt = n.getLocation();
            return St.RUNNING;
        }
        String label() { return nameLower != null ? nameLower : ids != null ? Arrays.toString(ids) : "?"; }
        String target; Location lastAt;
        @Override String trace() { return (driver == null ? "clicks " + clicks : "seen " + driver.seen()) + ", target " + target + ", clicked from " + clickedFrom + ", now at " + (lastBot != null ? lastBot.getLocation() : "?"); }
        Player lastBot;
    }

    // ------------------------------------------------------------------ use an item on something

    static final class UseOn extends Step {
        final int itemId; final int[] ids; final String nameLower; final IntType type; final Location near; final int radius;
        Predicate<Player> until; Object[] picks; int clicks, waitTicks, gone, sinceFind; DialogueDriver driver; final Mover m = new Mover(); boolean walked; Node found;
        UseOn(String name, int itemId, int[] ids, String targetName, IntType type, Location near, int radius) {
            super(name, 300);
            this.itemId = itemId; this.ids = ids; this.nameLower = targetName == null ? null : targetName.toLowerCase(Locale.ROOT); this.type = type; this.near = near; this.radius = radius;
            this.xpStep = true;
        }
        UseOn until(Predicate<Player> u) { this.until = u; return this; }
        UseOn picks(Object... p) { this.picks = p; return this; }
        boolean single;
        /** Only one in the pack (the rest banked): with two, cooking asks "how many?" instead of cooking. */
        UseOn single() { this.single = true; return this; }
        @Override void begin(QuestRunner r) { super.begin(r); clicks = 0; waitTicks = 0; gone = 0; driver = null; walked = false; found = null; sinceFind = 0; }
        @Override St tick(QuestRunner r) {
            Player bot = r.bot;
            if (until != null && until.test(bot) && driver == null) return St.DONE;
            if (driver != null) {
                DialogueDriver.Status s = driver.tick();
                if (s == DialogueDriver.Status.RUNNING) return St.RUNNING;
                if (s == DialogueDriver.Status.FAILED) return fail("dialogue after using " + itemId + ": " + driver.failure());
                driver = null; waitTicks = 0;
                return until == null ? St.DONE : St.RUNNING;
            }
            // looked up once, then again only every 20 ticks or after a click: the flour bin's hidden-object search is a few thousand map reads (audit Q-L1)
            if (found == null || type == IntType.NPC || ++sinceFind >= 20) { found = type == IntType.NPC ? findNpc(bot, near, radius, ids, nameLower) : findScenery(bot, near, radius, ids, nameLower, null); sinceFind = 0; }
            Node target = found;
            if (target == null) return fail("nothing to use " + itemId + " on near " + near);
            if (!walked) { // stand next to it first: a use-with from across the room does nothing
                Location to = target.getLocation();
                if (m.dest == null || !m.dest.equals(to)) m.reset(to, type == IntType.NPC ? 1 : 1);
                St s = m.tick(bot);
                double dd = bot.getLocation().getDistance(to);
                if (s == St.RUNNING && dd > 2.5 && !(dd <= 5 && m.stall > 8)) return St.RUNNING; // big objects (a cow, a range) can't be stood on: close is close enough, the click paths the rest
                walked = true;
            }
            if (clicks > 0) {
                if (picks != null && DialogueDriver.open(bot)) { driver = new DialogueDriver(bot, picks); return St.RUNNING; }
                if (bot.getWalkingQueue().isMoving() || bot.getPulseManager().hasPulseRunning()) return St.RUNNING;
                if (++waitTicks < 6) return St.RUNNING;
                if (until == null) return St.DONE;
                if (clicks >= 5) return fail("using " + itemId + " on " + target.getName() + " didn't do it (5 clicks, at " + bot.getLocation() + ")");
                if (openDoorToward(bot, target.getLocation(), 6)) { r.log("  opened a gate or door on the way to " + target.getName()); waitTicks = 0; walked = false; return St.RUNNING; } // a fenced pen: the click can't path in
            }
            Item it = null;
            for (Item i : bot.getInventory().toArray()) if (i != null && i.getId() == itemId) { it = i; break; }
            if (it != null && single) {
                int extra = bot.getInventory().getAmount(itemId) - 1;
                if (extra > 0 && bot.getInventory().remove(new Item(itemId, extra))) { if (!bot.getBank().add(new Item(itemId, extra))) bot.getInventory().add(new Item(itemId, extra)); }
                for (Item i : bot.getInventory().toArray()) if (i != null && i.getId() == itemId) { it = i; break; }
            }
            if (it == null) {
                if (clicks > 0 && ++gone < 20) return St.RUNNING; // used up by the last click: the result can take a few ticks (cooking)
                return fail("no item " + itemId + " in the pack");
            }
            clearPopups(bot);
            boolean ran;
            try { ran = InteractionListeners.run(it, target, type, bot); } catch (Throwable t) { return fail("use-with threw " + t); }
            if (!ran) try { ran = QuestRunner.legacyUseWith(bot, it, target); } catch (Throwable t) { ran = false; }
            if (!ran) return fail("no use-with handler for " + itemId + " on " + target.getName());
            clicks++; waitTicks = 0;
            return St.RUNNING;
        }
        @Override String trace() { return driver == null ? "clicks " + clicks : "seen " + driver.seen(); }
    }

    // ------------------------------------------------------------------ gathering with the bots' own task runner

    /** An AfkTasks.Runner started inside the quest (shearing, picking, mining, killing for drops); keeps its haul. */
    static final class Gather extends Step {
        final AfkTasks.Task task; final int itemId; final Location spot; String[] targets; int[] products;
        java.util.function.ToIntFunction<Player> want; int n;
        AfkTasks.Runner runner; int base; final Mover m = new Mover(); boolean approaching;
        Gather(String name, AfkTasks.Task task, int itemId, int n, Location spot) { super(name, COLLECT_LIMIT); this.task = task; this.itemId = itemId; this.n = n; this.want = p -> n; this.spot = spot; this.recheck = true; this.done = p -> p.getInventory().getAmount(itemId) >= want.applyAsInt(p); }
        Gather count(java.util.function.ToIntFunction<Player> f) { this.want = f; return this; }
        Gather targets(String... t) { this.targets = t; return this; }
        Gather products(int... p) { this.products = p; return this; }
        @Override void begin(QuestRunner r) {
            super.begin(r);
            runner = null;
            n = want.applyAsInt(r.bot);
            // the quest walker takes it to the spot (it opens doors and stops close enough); the runner works from there.
            // The runner's own long walks gave up out of Lumbridge for bots without teleport money.
            approaching = r.bot.getLocation().getZ() != spot.getZ() || r.bot.getLocation().getDistance(spot) > 12;
            if (approaching) m.reset(spot, 8); else startRunner(r);
        }
        void startRunner(QuestRunner r) {
            Player p = r.bot;
            int have = p.getInventory().getAmount(itemId);
            if (have >= n) return;
            QuestRunner.makeRoom(p, n - have + 2);
            if (task.tools != null && !AfkTasks.hasAny(p, task.tools)) { // the tool, as start(job) does: from the bank, else bought (a bot mined "Rocks" bare-handed for 1,700 ticks)
                boolean got = false;
                for (int t : task.tools) if (!got && p.getBank().getAmount(t) > 0 && p.getBank().remove(new Item(t, 1))) { if (p.getInventory().add(new Item(t, 1))) got = true; else p.getBank().add(new Item(t, 1)); }
                if (!got) { int t = task.tools[0]; if (Ledger.Wallet.pay(p, Math.max(1, MarketBrain.value(t)))) { if (!p.getInventory().add(new Item(t))) p.getBank().add(new Item(t)); else got = true; } }
                r.log("  " + (got ? "took a tool for " + task.key : "no tool for " + task.key + " and no coins for one"));
            }
            AfkTasks.Runner old = AfkTasks.RUNNING.get(p.getUsername().toLowerCase(Locale.ROOT));
            if (old != null) old.halt();
            AfkTasks.Runner g = new AfkTasks.Runner(p, task, false, n - have, false, true);
            g.questHold = true;
            g.walker = Ledger.Wallet.coins(p) < 5_000;
            if (targets != null) g.targetsOverride = targets;
            if (products != null) g.productsOverride = products;
            g.labelOverride = name;
            g.spot = spot;
            AfkTasks.launch(g, AfkTasks.State.TO_SPOT, spot); // audit 10-23 A-11 (tick() sees runner.halted)
            runner = g;
        }
        @Override St tick(QuestRunner r) {
            Player p = r.bot;
            if (p.getInventory().getAmount(itemId) >= n) return St.DONE;
            if (approaching) {
                St w = m.tick(p);
                if (w == St.RUNNING) return St.RUNNING;
                if (w == St.FAILED && (p.getLocation().getZ() != spot.getZ() || p.getLocation().getDistance(spot) > 20)) return fail("walk to " + spot + ": " + m.last);
                approaching = false;
                startRunner(r);
            }
            if (runner == null) return fail("no runner");
            if (runner.halted || AfkTasks.RUNNING.get(p.getUsername().toLowerCase(Locale.ROOT)) != runner) return fail("the " + task.key + " runner stopped: " + runner.last + " (" + p.getInventory().getAmount(itemId) + "/" + n + ")");
            return St.RUNNING;
        }
        @Override void end(QuestRunner r) {
            if (runner != null && !runner.halted) { try { runner.finish("Got what the quest needs."); } catch (Throwable t) { runner.halt(); } }
            runner = null;
        }
        @Override String doing() { return name + (runner != null ? " (" + runner.collected + " so far)" : ""); }
        @Override String trace() { return runner == null ? (approaching ? "walking: " + m.last : null) : "runner " + runner.state + " last " + runner.last + " at " + runner.p.getLocation(); }
    }

    /** Kill something for a drop or until a condition (rats for a tail, imps for beads). */
    static final class Kill extends Step {
        final String[] names; final int[] drops; final Location spot; final int count; Predicate<Player> until; Location[] more = {};
        AfkTasks.Runner runner; final Mover m = new Mover(); boolean approaching;
        Kill(String name, String[] names, int[] drops, int count, Location spot) { super(name, KILL_LIMIT); this.names = names; this.drops = drops; this.count = count; this.spot = spot; this.recheck = true; }
        Kill until(Predicate<Player> u) { this.until = u; return this; }
        Kill limit(int ticks) { this.limitTicks = ticks; return this; }
        /** Other places the same monster lives: the runner moves round these when nothing is in sight. */
        Kill spots(Location... l) { this.more = l; return this; }
        @Override void begin(QuestRunner r) {
            super.begin(r);
            runner = null;
            approaching = r.bot.getLocation().getZ() != spot.getZ() || r.bot.getLocation().getDistance(spot) > 12; // as Gather: the quest walker gets it there
            if (approaching) m.reset(spot, 8); else startRunner(r);
        }
        void startRunner(QuestRunner r) {
            Player p = r.bot;
            QuestRunner.bankQuestFood(p); // cooked meat for Witch's Potion is food: never eat it mid-fight
            QuestRunner.makeRoom(p, 6);
            QuestRunner.packFood(p, 6);
            AfkTasks.Runner old = AfkTasks.RUNNING.get(p.getUsername().toLowerCase(Locale.ROOT));
            if (old != null) old.halt();
            AfkTasks.Runner g = new AfkTasks.Runner(p, AfkTasks.Task.RAT, false, count, false, true);
            g.questHold = true;
            g.walker = Ledger.Wallet.coins(p) < 5_000;
            g.targetsOverride = names; g.productsOverride = drops; g.labelOverride = name;
            g.spot = spot;
            AfkTasks.Spot[] qs = new AfkTasks.Spot[more.length + 1];
            qs[0] = new AfkTasks.Spot(name, spot.getX(), spot.getY());
            for (int i = 0; i < more.length; i++) qs[i + 1] = new AfkTasks.Spot(name, more[i].getX(), more[i].getY());
            g.questSpots = qs;
            try { p.getProperties().setRetaliating(true); } catch (Throwable ignored) { }
            AfkTasks.launch(g, AfkTasks.State.TO_SPOT, spot); // audit 10-23 A-11 (tick() sees runner.halted)
            runner = g;
        }
        @Override St tick(QuestRunner r) {
            Player p = r.bot;
            if (until != null && until.test(p)) return St.DONE;
            if (approaching) {
                St w = m.tick(p);
                if (w == St.RUNNING) return St.RUNNING;
                if (w == St.FAILED && (p.getLocation().getZ() != spot.getZ() || p.getLocation().getDistance(spot) > 20)) return fail("walk to " + spot + ": " + m.last);
                approaching = false;
                startRunner(r);
            }
            if (runner == null) return fail("no runner");
            if (runner.halted || AfkTasks.RUNNING.get(p.getUsername().toLowerCase(Locale.ROOT)) != runner) {
                if (until == null) return St.DONE;
                return fail("the fight runner stopped: " + runner.last);
            }
            return St.RUNNING;
        }
        @Override void end(QuestRunner r) {
            if (runner != null && !runner.halted) { try { runner.finish("Quest kill done."); } catch (Throwable t) { runner.halt(); } }
            runner = null;
        }
        @Override String trace() { return runner == null ? null : "runner " + runner.state + " last " + runner.last + " at " + runner.p.getLocation(); }
    }

    // ------------------------------------------------------------------ shops

    /** Walk to a shop and buy, paying the shop price in real coins (as SmartAdventurers.finishShop does). */
    static final class Buy extends Step {
        final int itemId, n; final Location at; final String shop; final Mover m = new Mover(); int stalls;
        Buy(String name, int itemId, int n, String shop, Location at) { super(name, WALK_LIMIT); this.itemId = itemId; this.n = n; this.shop = shop; this.at = at; }
        @Override void begin(QuestRunner r) { super.begin(r); m.reset(at, 4); }
        @Override St tick(QuestRunner r) {
            Player p = r.bot;
            int need = n - p.getInventory().getAmount(itemId);
            if (need <= 0) return St.DONE;
            St s = m.tick(p);
            boolean close = p.getLocation().getZ() == at.getZ() && p.getLocation().getDistance(at) <= 9 && m.stall > 20;
            if (s == St.RUNNING && !close) return St.RUNNING;
            if (s == St.FAILED) return fail("walk to " + shop + ": " + m.last);
            long price = (long) Math.max(1, MarketBrain.value(itemId)) * need;
            if (!Ledger.Wallet.pay(p, price)) { if (Watchdog.FAIL_ON && Watchdog.traced(p)) try { Failures.Econ.fee(p, "quest-shop", price); } catch (Throwable ignored) { } return fail("can't afford " + need + " x " + itemId + " (" + price + " gp) at " + shop); } // batch 19c (design 4.7): logged only
            QuestRunner.makeRoom(p, need);
            for (int i = 0; i < need; i++) p.getInventory().add(new Item(itemId));
            r.log("  bought " + need + " x " + MarketBrain.name(itemId) + " at " + shop + " for " + price + " gp");
            return St.DONE;
        }
        @Override String trace() { return "walk " + m.last + ", stall " + m.stall + ", retries " + m.retries; }
    }

    // ------------------------------------------------------------------ collecting: pack, bank, bot stock, a sub-plan, a shop

    static final Integer[] GLUT = {434, 436, 440, 1737, 1944, 1957, 2132}; // clay, copper, iron, wool, egg, onion, raw beef

    static final class Collect extends Step {
        final int itemId; final List<Step> plan = new ArrayList<>(); Step buy; boolean fromStock;
        java.util.function.ToIntFunction<Player> want; int n;
        int phase, sub; boolean subBegun; int subTicks;
        Collect(String name, int itemId, int n) { super(name, COLLECT_LIMIT); this.itemId = itemId; this.n = n; this.want = p -> n; this.fromStock = Arrays.asList(GLUT).contains(itemId); this.done = p -> p.getInventory().getAmount(itemId) >= want.applyAsInt(p); this.recheck = true; }
        Collect count(java.util.function.ToIntFunction<Player> f) { this.want = f; return this; }
        Collect stock() { this.fromStock = true; return this; }
        Collect limit(int ticks) { this.limitTicks = ticks; return this; }
        Collect then(Step s) { plan.add(s); return this; }
        Collect buy(Step s) { this.buy = s; return this; }
        Collect noStock() { this.fromStock = false; return this; }
        @Override void begin(QuestRunner r) { super.begin(r); phase = 0; sub = 0; subBegun = false; subTicks = 0; n = want.applyAsInt(r.bot); }
        int have(Player p) { return p.getInventory().getAmount(itemId); }
        @Override St tick(QuestRunner r) {
            Player p = r.bot;
            if (have(p) >= n) return St.DONE;
            if (phase == 0) { // the instant sources
                int need = n - have(p);
                int inBank = p.getBank().getAmount(itemId);
                if (inBank > 0) {
                    int take = Math.min(need, inBank);
                    QuestRunner.makeRoom(p, take);
                    if (p.getBank().remove(new Item(itemId, take))) { if (!p.getInventory().add(new Item(itemId, take))) p.getBank().add(new Item(itemId, take)); else r.log("  took " + take + " x " + MarketBrain.name(itemId) + " from the bank"); }
                    need = n - have(p);
                }
                if (need > 0 && fromStock && !Boolean.getBoolean("quests.noStock") && MarketBrain.stockOf(itemId) > 0) {
                    QuestRunner.makeRoom(p, need);
                    int got = Ledger.consume(p, itemId, Math.min(need, p.getInventory().freeSlots()), "quest");
                    if (got > 0) { p.getInventory().add(new Item(itemId, got)); r.log("  bought " + got + " x " + MarketBrain.name(itemId) + " from bot stock"); }
                }
                phase = 1;
                return have(p) >= n ? St.DONE : St.RUNNING;
            }
            if (phase == 1) { // the sub-plan: make or gather it
                if (sub >= plan.size()) { phase = 2; return St.RUNNING; }
                Step s = plan.get(sub);
                if (!subBegun) {
                    if (s.checkpoint(p)) { sub++; return St.RUNNING; }
                    s.begin(r); subBegun = true; subTicks = 0; r.log("  - " + s.name);
                }
                St st = s.tick(r);
                if (st == St.RUNNING && ++subTicks > s.limitTicks) st = s.fail("time limit " + s.limitTicks + " ticks");
                if (st == St.RUNNING && s.recheck && s.checkpoint(p)) st = St.DONE;
                if (st == St.RUNNING) return St.RUNNING;
                s.end(r);
                subBegun = false;
                if (st == St.FAILED) { r.log("  - " + s.name + " failed: " + s.failure + (s.trace() != null ? " [" + s.trace() + "]" : "")); phase = 2; return St.RUNNING; } // fall through to the shop
                r.log("  - " + s.name + " done in " + subTicks + " ticks");
                sub++;
                return St.RUNNING;
            }
            if (phase == 2 && buy != null) {
                if (!subBegun) { buy.begin(r); subBegun = true; subTicks = 0; r.log("  - " + buy.name); }
                St st = buy.tick(r);
                if (st == St.RUNNING && ++subTicks > buy.limitTicks) st = buy.fail("time limit");
                if (st == St.RUNNING) return St.RUNNING;
                buy.end(r); subBegun = false; phase = 3;
                if (st == St.FAILED) return fail("shop: " + buy.failure);
                return have(p) >= n ? St.DONE : fail("still short after the shop (" + have(p) + "/" + n + ")");
            }
            return fail("short of " + MarketBrain.name(itemId) + " (" + have(p) + "/" + n + ")");
        }
        @Override void end(QuestRunner r) {
            if (subBegun) { Step s = phase == 1 && sub < plan.size() ? plan.get(sub) : buy; if (s != null) s.end(r); subBegun = false; }
        }
        @Override String doing() {
            if (phase == 1 && sub < plan.size()) return plan.get(sub).doing();
            return name;
        }
        @Override String trace() { Step s = phase == 1 && sub < plan.size() ? plan.get(sub) : buy; return s != null ? s.trace() : null; }
    }

    // ------------------------------------------------------------------ picking an item up off the ground

    /** Walk to a ground spawn (an egg, a pot) and take it; waits there for a respawn until the time runs out. */
    static final class PickUp extends Step {
        final int itemId; final Location at; final Mover m = new Mover(); int tries, wait;
        PickUp(String name, int itemId, Location at) { super(name, 600); this.itemId = itemId; this.at = at; this.done = has1(itemId); this.recheck = true; }
        static Predicate<Player> has1(int id) { return p -> p.getInventory().getAmount(id) > 0; }
        PickUp limit(int ticks) { this.limitTicks = ticks; return this; }
        @Override void begin(QuestRunner r) { super.begin(r); m.reset(at, 1); tries = 0; wait = 0; }
        @Override St tick(QuestRunner r) {
            Player p = r.bot;
            if (p.getInventory().getAmount(itemId) > 0) return St.DONE;
            if (p.getLocation().getDistance(at) > 1.5) {
                St s = m.tick(p);
                if (s == St.FAILED) return fail("walk to " + at + ": " + m.last);
                if (s == St.RUNNING) return St.RUNNING;
            }
            if (wait > 0) { wait--; return St.RUNNING; }
            core.game.node.item.GroundItem gi = null;
            for (int dx = -1; dx <= 1 && gi == null; dx++) for (int dy = -1; dy <= 1 && gi == null; dy++) {
                try { core.game.node.item.GroundItem g = core.game.node.item.GroundItemManager.get(itemId, at.transform(dx, dy, 0), p); if (g != null && g.isActive()) gi = g; } catch (Throwable ignored) { }
            }
            if (gi == null) { wait = 5; return St.RUNNING; } // taken by someone else: it respawns
            QuestRunner.makeRoom(p, 1);
            if (!click(p, gi, "Take", IntType.GROUNDITEM)) return fail("no Take option on the " + itemId);
            tries++; wait = 4;
            return St.RUNNING;
        }
        @Override String trace() { return "tries " + tries + ", walk " + m.last; }
    }

    // ------------------------------------------------------------------ waiting and custom

    static final class WaitStage extends Step {
        final int stage;
        WaitStage(String name, int stage, int timeout) { super(name, timeout); this.stage = stage; this.recheck = true; }
        @Override St tick(QuestRunner r) { return r.stage() >= stage ? St.DONE : St.RUNNING; }
    }

    /** A one-off step: a lambda that is called every tick. */
    /** NPCs that charge from the pack (Wyson, Aggie, Leela) don't see coins in the bank, where a bot's wallet keeps them: move n to the pack first. */
    static Custom pocketCoins(int n) {
        return new Custom("take " + n + " coins into the pack", 5, null, r -> {
            Player p = r.bot;
            int need = n - p.getInventory().getAmount(995);
            int take = Math.min(Math.max(0, need), p.getBank().getAmount(995));
            if (take > 0 && p.getBank().remove(new Item(995, take)) && !p.getInventory().add(new Item(995, take))) p.getBank().add(new Item(995, take));
            return St.DONE;
        });
    }

    static final class Custom extends Step {
        final Function<QuestRunner, St> f; final java.util.function.Consumer<QuestRunner> onBegin;
        Custom(String name, int limit, java.util.function.Consumer<QuestRunner> onBegin, Function<QuestRunner, St> f) { super(name, limit); this.f = f; this.onBegin = onBegin; }
        @Override void begin(QuestRunner r) { super.begin(r); if (onBegin != null) onBegin.accept(r); }
        @Override St tick(QuestRunner r) { St s = f.apply(r); if (s == St.FAILED && failure == null) failure = "custom step failed"; return s; }
    }
}
