package content.custom;

import core.game.world.map.Location;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * W6 wave 3 Slayer: -Dbots.slayer.vannakaHunts (W6Slayer). Plain java (no world), TestW1Slayer/TestW5bSkills pattern:
 *   java -cp "<build>;server.jar" content.custom.TestW6Slayer <addons-src>/content/custom
 * Run it bare (flags off) and with -Dbots.slayer.vannakaHunts=true -Dbots.slayer.iceGiantFix=true -Dnav.p82Fix=true -Dnav.p84Fix=true.
 *  F  the static follows its property.
 *  P  pure rules: spotFor (name, curated, surface count, key walk), gate (combat/prayer/food), hellBox, seats of a built hunt,
 *     P82.behindKeyGate on a hellhound tile only with the flag (and the old box unchanged).
 *  S  (with the source path) the hooks sit behind W6Slayer.ON and the flag-off expressions are train-61's.
 */
public final class TestW6Slayer {
    static int pass, fail;
    static void check(String what, boolean ok) { System.out.println("[TestW6Slayer] " + (ok ? "PASS " : "FAIL ") + what); if (ok) pass++; else fail++; }
    static String read(String dir, String f) throws Exception { return new String(Files.readAllBytes(Paths.get(dir, f)), StandardCharsets.UTF_8); }
    static boolean all(String src, String... parts) { for (String p : parts) if (!src.contains(p)) { System.out.println("[TestW6Slayer]   missing: " + p); return false; } return true; }

    public static void main(String[] a) throws Exception {
        boolean on = W6Slayer.ON;
        check("F flag follows -Dbots.slayer.vannakaHunts (" + on + ")", on == Boolean.getBoolean("bots.slayer.vannakaHunts"));

        // spotFor: off -> null always
        check("P spotFor off: null for Hellhound/Vampire", W6Slayer.spotFor(false, "Hellhound", false, 0, true) == null && W6Slayer.spotFor(false, "Vampire", false, 17, true) == null);
        W6Slayer.Spot hh = W6Slayer.spotFor(true, "Hellhound", false, 0, true);
        check("P Hellhound: Taverley dungeon spot 2866,9840, 2 seats, combat 90 prayer 43 food 10", hh != null && hh.at.getX() == 2866 && hh.at.getY() == 9840 && hh.seats == 2 && hh.combat == 90 && hh.prayer == 43 && hh.food == 10 && hh.keyWalk);
        check("P Hellhound needs the key walk (P82+P84): null without", W6Slayer.spotFor(true, "Hellhound", false, 0, false) == null);
        check("P Hellhound with a curated spot or 3+ surface spawns: not W6", W6Slayer.spotFor(true, "Hellhound", true, 0, true) == null && W6Slayer.spotFor(true, "Hellhound", false, 3, true) == null);
        W6Slayer.Spot vp = W6Slayer.spotFor(true, "Vampire", false, 17, true);
        check("P Vampire: HuntMap's own surface spot (at null), 2 seats, combat 84 food 8", vp != null && vp.at == null && vp.seats == 2 && vp.combat == 84 && vp.prayer == 0 && vp.food == 8);
        check("P Vampire with under 3 surface spawns: null (nothing to hunt)", W6Slayer.spotFor(true, "Vampire", false, 2, true) == null);
        check("P other names (Ice warrior, Green dragon, Earth warrior, Ice giant): null", W6Slayer.spotFor(true, "Ice warrior", false, 2, true) == null && W6Slayer.spotFor(true, "Green dragon", false, 0, true) == null && W6Slayer.spotFor(true, "Earth warrior", false, 0, true) == null && W6Slayer.spotFor(true, "Ice giant", false, 4, true) == null);

        // gate
        check("P gate: 90/43/10 open for hellhounds", W6Slayer.gate(hh, 90, 43, 10) == null);
        check("P gate: combat 89 -> w6level, prayer 42 -> w6prayer, food 9 -> w6food", "w6level".equals(W6Slayer.gate(hh, 89, 99, 99)) && "w6prayer".equals(W6Slayer.gate(hh, 99, 42, 99)) && "w6food".equals(W6Slayer.gate(hh, 99, 99, 9)));
        check("P gate: vampires need no prayer; food 7 closed, 8 open", W6Slayer.gate(vp, 84, 1, 8) == null && "w6food".equals(W6Slayer.gate(vp, 84, 1, 7)) && "w6level".equals(W6Slayer.gate(vp, 83, 1, 8)));
        check("P gate: not a W6 hunt -> open", W6Slayer.gate(null, 3, 1, 0) == null);

        // hellBox and the P82 key walk goal
        check("P hellBox: 2866,9840 in, 2866,9780 (black demons) and 2884,9840 (ladder way) out", W6Slayer.hellBox(2866, 9840, 0) && !W6Slayer.hellBox(2866, 9780, 0) && !W6Slayer.hellBox(2884, 9840, 0) && !W6Slayer.hellBox(2866, 9840, 1));
        check("P P82.behindKeyGate: hellhound tile only with the flag; old box (2904,9803 / 2866,9780) unchanged", P82.behindKeyGate(2866, 9840, 0) == on && P82.behindKeyGate(2904, 9803, 0) && P82.behindKeyGate(2866, 9780, 0) && !P82.behindKeyGate(2884, 9840, 0));

        // built: tag, spawns, seats
        HuntMap.Hunt h = new HuntMap.Hunt(); h.name = "Hellhound"; h.npcLevel = 122; h.names = new String[]{"Hellhound"}; h.spot = hh.at; h.spawnsAtSpot = 4;
        HuntMap.Hunt other = new HuntMap.Hunt(); other.name = "Cow"; other.spawnsAtSpot = 9;
        W6Slayer.built(h, hh);
        check("P built: tag 'Taverley dungeon', label 'Hellhounds (Taverley dungeon)', spawns 10, seats 2; other hunts seats -1", "Taverley dungeon".equals(h.tag) && "Hellhounds (Taverley dungeon)".equals(HuntMap.label(h)) && h.spawnsAtSpot == 10 && W6Slayer.seats(h) == 2 && W6Slayer.seats(other) == -1 && W6Slayer.seats(null) == -1);
        HuntMap.Hunt v = new HuntMap.Hunt(); v.name = "Vampire"; v.npcLevel = 61; v.spot = Location.create(3631, 3471, 0); v.spawnsAtSpot = 6;
        W6Slayer.built(v, vp);
        check("P built (surface spot): keeps HuntMap's spawns (6), tag Morytania", v.spawnsAtSpot == 6 && "Morytania".equals(v.tag) && W6Slayer.seats(v) == 2);

        boolean ice = W6Slayer.ICE_FIX;
        check("F iceGiantFix follows -Dbots.slayer.iceGiantFix (" + ice + "), anyOn = ON || ICE_FIX", ice == Boolean.getBoolean("bots.slayer.iceGiantFix") && W6Slayer.anyOn() == (on || ice));
        check("I icePocket: Ice giant at the HuntMap spots 2811,3506 / 2817,3514 / 2824,3510 / 2804,3507 in", W6Slayer.icePocket("Ice giant", Location.create(2811, 3506, 0)) && W6Slayer.icePocket("Ice giant", Location.create(2817, 3514, 0)) && W6Slayer.icePocket("Ice giant", Location.create(2824, 3510, 0)) && W6Slayer.icePocket("Ice giant", Location.create(2804, 3507, 0)));
        check("I icePocket: other names, other spots (Asgarnian 3050,9577, Taverley 2883,9932, outside 2840,3510), plane 1, null: out", !W6Slayer.icePocket("Ice warrior", Location.create(2811, 3506, 0)) && !W6Slayer.icePocket("Ice giant", Location.create(3050, 9577, 0)) && !W6Slayer.icePocket("Ice giant", Location.create(2883, 9932, 0)) && !W6Slayer.icePocket("Ice giant", Location.create(2840, 3510, 0)) && !W6Slayer.icePocket("Ice giant", Location.create(2811, 3506, 1)) && !W6Slayer.icePocket("Ice giant", null) && !W6Slayer.icePocket(null, Location.create(2811, 3506, 0)));
        check("I line names the flags on", W6Slayer.line().startsWith("[W6] on=[" + (on ? "vannakaHunts" : "") + (on && ice ? " " : "") + (ice ? "iceGiantFix" : "") + "] iceDropped="));
        if (a.length > 0) {
            String hm = read(a[0], "HuntMap.java"), sa = read(a[0], "SmartAdventurers.java"), sl = read(a[0], "Slayer.java"), g = read(a[0], "Gates.java"), mb = read(a[0], "MarketBrain.java"), p82 = read(a[0], "P82.java");
            check("S HuntMap: spotFor behind ON, attack check bypassed only for w6, built() after the spot",
                    all(hm, "W6Slayer.Spot w6 = W6Slayer.ON && d0 != null ? W6Slayer.spotFor(true,", "if (w6 != null && w6.at != null) curated = w6.at;",
                            "!d0.hasAction(\"attack\") && w6 == null) continue;", "if (w6 != null) W6Slayer.built(h, w6);", "if (all.size() < 3 && curated == null) continue;"));
            check("S SmartAdventurers: capacity, chooseJob and able hooks behind ON; old capacity expression kept",
                    all(sa, "if (W6Slayer.ON) { int s6 = W6Slayer.seats(j.hunt); if (s6 > 0) return s6; } return Math.max(1, Math.min(4, j.hunt.spawnsAtSpot / 3));",
                            "if (W6Slayer.ON && j.hunt != null && W6Slayer.closed(this, j.hunt, combat) != null) continue;", "if (W6Slayer.ON && W6Slayer.closed(this, j.hunt, combat) != null) return false;"));
            check("S HuntMap: the Ice giant drop behind ICE_FIX, after the P93 drop", all(hm, "if (W6Slayer.ICE_FIX && (W6Slayer.icePocket(h.name, best) || W6Slayer.icePocket(d0.getName(), best))) {") && hm.indexOf("W6Slayer.ICE_FIX &&") > hm.indexOf("if (P93_FIX && (p93Pocket("));
            check("S Slayer: seats and openGate hooks behind ON", all(sl, "j.hunt == null || W6Slayer.ON && W6Slayer.seats(j.hunt) > 0 ? cap : cap8(", "if (W6Slayer.ON && j.hunt != null) { String g6 = W6Slayer.closed(b, j.hunt, combat);"));
            check("S Gates box and MarketBrain line behind ON; P82 old box first", all(g, "if (W6Slayer.ON) g(\"taverley-hellhounds\"") && all(mb, "if (W6Slayer.anyOn()) TIMER.schedule(") && all(p82, "x >= 2850 && x <= 2945 && y >= 9765 && y <= 9815 || W6Slayer.ON && W6Slayer.hellBox(x, y, z)"));
        }
        System.out.println("[TestW6Slayer] RESULT " + (fail == 0 ? "PASS" : "FAIL") + " flag=" + on + " pass=" + pass + " fail=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
