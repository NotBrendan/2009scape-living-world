package content.custom;

import core.game.interaction.IntType;
import core.game.node.entity.combat.CombatStyle;
import core.game.node.entity.player.Player;
import core.game.node.entity.player.link.prayer.Prayer;
import core.game.node.entity.player.link.prayer.PrayerType;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.node.scenery.Scenery;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

import java.util.Locale;

/**
 * W6 wave 3 combat lane: the engine side of W6Combat for an AfkTasks.Runner (game thread only). Every entry point is
 * called behind its flag (-Dbots.prayer.stat, -Dbots.ranged.kit2); with the flags off nothing here runs and Runner.w6
 * stays null.
 */
final class W6Fight {
    private W6Fight() { }

    /** Per-runner state (one trip). */
    static final class St {
        boolean on, counted, altarDone, logged;
        int lastFight = -1000, fights, altar = -1, altarTicks, altarWait = -1, prayTicks, sweep;
        double lastPts = -1, used;
        String altarNote = "none";
    }

    static St st(AfkTasks.Runner r) { if (r.w6 == null) r.w6 = new St(); return r.w6; }

    static PrayerType type(String name) { try { return PrayerType.valueOf(name); } catch (Throwable t) { return null; } }

    static int style(Player p) {
        CombatStyle s = p.getProperties().getCombatPulse().getStyle();
        return s == CombatStyle.RANGE ? W6Combat.RANGED : s == CombatStyle.MAGIC ? W6Combat.MAGIC : W6Combat.MELEE;
    }

    /** -Dbots.prayer.stat, from Runner.fight() after prayerTick(): the style's stat prayers on while fighting, inside the budget. */
    static void prayTick(AfkTasks.Runner r) {
        Player p = r.p;
        try {
            Skills sk = p.getSkills();
            int lvl = sk.getStaticLevel(Skills.PRAYER);
            if (lvl < W6Combat.STAT_MIN) return;
            St s = st(r);
            double pts = sk.getPrayerPoints();
            int now = GameWorld.getTicks();
            boolean fight = p.inCombat() || p.getProperties().getCombatPulse().isAttacking();
            if (fight) s.lastFight = now;
            if (s.on && s.lastPts > pts) { s.used += s.lastPts - pts; W6Combat.addUsed(s.lastPts - pts); }
            s.lastPts = pts;
            if (!W6Combat.prayNow(s.on, fight || now - s.lastFight <= W6Combat.LINGER, pts, lvl, r.usePrayer)) { if (s.on) off(r); return; }
            String[] want = W6Combat.statPrayers(style(p), lvl);
            Prayer pr = p.getPrayer();
            for (String n : W6Combat.ALL_STAT) { // a style change mid-trip: the other style's prayer goes off
                boolean keep = false; for (String w : want) if (w.equals(n)) keep = true;
                PrayerType t = keep ? null : type(n);
                if (t != null && pr.get(t)) pr.toggle(t);
            }
            boolean any = false;
            for (String n : want) { PrayerType t = type(n); if (t == null) continue; if (!pr.get(t)) pr.toggle(t); if (pr.get(t)) any = true; }
            if (any && !s.on) { s.on = true; s.fights++; W6Combat.prayFights++; if (!s.counted) { s.counted = true; W6Combat.prayTrips++; } }
            if (any) s.prayTicks++;
        } catch (Throwable ignored) { }
    }

    /** The stat prayers off (the protection prayers are prayerTick's). */
    static void off(AfkTasks.Runner r) {
        St s = r.w6; if (s == null) return;
        try {
            Prayer pr = r.p.getPrayer();
            for (String n : W6Combat.ALL_STAT) { PrayerType t = type(n); if (t != null && pr.get(t)) pr.toggle(t); }
            double pts = r.p.getSkills().getPrayerPoints();
            if (s.on && s.lastPts > pts) { s.used += s.lastPts - pts; W6Combat.addUsed(s.lastPts - pts); }
            s.lastPts = pts;
        } catch (Throwable ignored) { }
        s.on = false;
    }

    /** The runner halts: prayers off and one line for the trip (only for a trip that prayed). */
    static void tripEnd(AfkTasks.Runner r) {
        St s = r.w6; if (s == null) return;
        off(r);
        if (!s.counted || s.logged) return;
        s.logged = true;
        try {
            Skills sk = r.p.getSkills();
            System.out.println("[W6c] prayer " + r.p.getUsername() + " trip: stat prayers in " + s.fights + " fights (" + s.prayTicks + " ticks), points used " + String.format(Locale.ROOT, "%.1f", s.used)
                    + ", left " + (int) sk.getPrayerPoints() + "/" + sk.getStaticLevel(Skills.PRAYER) + ", altar " + s.altarNote);
            Scen.prayTrip(r.p, s.fights, sk.getPrayerPoints()); // w14 scen prayerStat (no-op unless Seed.ON)
        } catch (Throwable ignored) { }
    }

    static Scenery altarNear(Location c) {
        Scenery best = null; double bd = 99;
        for (int dx = -8; dx <= 8; dx++) for (int dy = -8; dy <= 8; dy++) {
            Scenery s = RegionManager.getObject(c.getZ(), c.getX() + dx, c.getY() + dy);
            if (s == null || s.getName() == null || !s.getName().equalsIgnoreCase("Altar")) continue;
            double d = s.getLocation().getDistance(c);
            if (d < bd) { bd = d; best = s; }
        }
        return best;
    }

    static String prayOption(AfkTasks.Runner r, Scenery s) { return r.hasOption(s, "Pray-at") ? "Pray-at" : r.hasOption(s, "Pray") ? "Pray" : null; }

    /**
     * -Dbots.prayer.stat, from the runner's TO_BANK tick: a bot that prayed this trip and is under half its points takes
     * the church altar on its way to the bank (W6Combat.altarPick), prays there with the engine's own altar option and
     * walks on. True while the altar leg owns the tick. Surface walks only; ALTAR_TICKS and it gives up; an altar row
     * with ALTAR_BAD misses is left alone until the restart.
     */
    static boolean altarTick(AfkTasks.Runner r) {
        St s = r.w6;
        if (s == null || !s.counted || s.altarDone) return false;
        Player p = r.p;
        try {
            Location here = p.getLocation();
            Skills sk = p.getSkills();
            int lvl = sk.getStaticLevel(Skills.PRAYER);
            if (s.altar < 0) {
                if (s.on) off(r);
                if (r.dest == null || r.boat != null || r.underDest != null || r.upDest != null || here.getZ() != 0 || here.getY() >= 6400 || r.dest.getZ() != 0 || r.dest.getY() >= 6400) return false;
                if (p.inCombat() || p.getProperties().getTeleportLocation() != null || !W6Combat.needsAltar(sk.getPrayerPoints(), lvl)) return false;
                int a = W6Combat.altarPick(here.getX(), here.getY(), r.dest.getX(), r.dest.getY(), W6Combat.ALTARS, W6Combat.ALTAR_MISSES, W6Combat.ALTAR_LEG, W6Combat.ALTAR_DETOUR);
                if (a < 0) return false;
                s.altar = a; s.altarTicks = 0;
                if (!r.goTo(AfkTasks.State.TO_BANK, Location.create(W6Combat.ALTARS[a][0], W6Combat.ALTARS[a][1], 0))) { s.altarDone = true; return true; } // goTo ended the runner
                r.last = "to the altar on the way back";
                return true;
            }
            int[] row = W6Combat.ALTARS[s.altar];
            Location at = Location.create(row[0], row[1], 0);
            if (s.altarWait < 0) {
                if (++s.altarTicks > W6Combat.ALTAR_TICKS) return leave(r, s, "gave up (" + (int) here.getDistance(at) + " tiles short)", 2);
                if (here.getDistance(at) > 3) { r.walk(AfkTasks.State.TO_BANK); return true; }
                s.altarWait = 0;
            }
            // in the nave: the engine's altar, a few ticks for the walk up to it and the prayer
            Scenery altar = altarNear(here);
            String opt = altar == null ? null : prayOption(r, altar);
            if (opt == null) return leave(r, s, "no altar found at " + row[0] + "," + row[1], 1);
            if (sk.getPrayerPoints() >= lvl) { p.getPulseManager().clear(); return leave(r, s, "prayed at " + row[0] + "," + row[1], 0); }
            int waited = s.altarWait++;
            if (waited % 6 == 0 && waited < 18) { p.faceLocation(altar.getLocation()); r.interact(altar, opt, IntType.SCENERY); r.last = "praying at the altar"; return true; }
            if (waited < 20) return true;
            // the engine's option did not fill the points in ~12 s: next to the altar the same recharge, as the option gives; else a miss
            if (here.getDistance(altar.getLocation()) <= 3) {
                p.getPulseManager().clear();
                try { p.getAnimator().animate(new core.game.world.update.flag.context.Animation(645)); } catch (Throwable ignored) { }
                sk.rechargePrayerPoints(); W6Combat.altarFallback++;
                return leave(r, s, "prayed at " + row[0] + "," + row[1] + " (fallback)", 0);
            }
            p.getPulseManager().clear();
            return leave(r, s, "could not reach the altar at " + row[0] + "," + row[1], 1);
        } catch (Throwable t) {
            s.altarDone = true;
            try { Location b = r.nearestBank(); if (b != null) r.goTo(AfkTasks.State.TO_BANK, b); } catch (Throwable ignored) { }
            return true;
        }
    }

    /** The altar leg ends (kind 0 prayed, 1 miss, 2 gave up): on to the bank. */
    static boolean leave(AfkTasks.Runner r, St s, String note, int kind) {
        if (kind == 0) { W6Combat.altarVisits++; if (s.altar >= 0) W6Combat.ALTAR_MISSES[s.altar] = 0; }
        else { if (kind == 1) W6Combat.altarMiss++; else W6Combat.altarGaveUp++; if (s.altar >= 0) W6Combat.ALTAR_MISSES[s.altar]++; }
        s.altarDone = true; s.altarNote = note;
        System.out.println("[W6c] altar " + r.p.getUsername() + " " + note + " (points " + (int) r.p.getSkills().getPrayerPoints() + "/" + r.p.getSkills().getStaticLevel(Skills.PRAYER) + ")");
        Location b = r.nearestBank();
        if (b != null) r.goTo(AfkTasks.State.TO_BANK, b);
        return true;
    }

    /**
     * -Dbots.ranged.kit2, from Runner.work() when the pack is full: before the walk to the bank the quiver's own fired
     * arrows come off the ground (pickUpLoot with w6Only: straight into the quiver, so a full pack does not matter).
     * At most 30 ticks a leg; reset by the next fight.
     */
    static void back(int n, boolean sweep) { W6Combat.arrowsBack += n; if (sweep) W6Combat.sweepBack += n; }

    static boolean arrowSweep(AfkTasks.Runner r) {
        Player p = r.p;
        if (!p.isArtificial() || r.task.kind != AfkTasks.Kind.FIGHT) return false;
        St s = st(r);
        if (s.sweep > 30) return false;
        Item q = p.getEquipment().get(13);
        if (q == null || p.inCombat() || p.getProperties().getCombatPulse().isAttacking()) return false;
        if (p.getWalkingQueue().isMoving()) { s.sweep++; return true; }
        r.w6Only = true;
        try { if (r.pickUpLoot()) { s.sweep++; r.wait = 1; r.last = "picking up arrows"; return true; } } finally { r.w6Only = false; }
        s.sweep = 31;
        return false;
    }
}
