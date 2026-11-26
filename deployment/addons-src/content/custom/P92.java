package content.custom;

/**
 * P92 (-Dquest.p92Fix, default off; off = the P88 behaviour): three quest fixes from the live train-44 fails (11-17).
 *  - wolfwhistle step 20 "get the pouch supplies together": the P88 hand-out was marked once per bot per server run (and
 *    by the dialogue's unsaved taken-summoning-supplies), so a bot whose charms were gone again (used by a Summoning job, or
 *    a restart) failed with "charms 0" every run after. Now no marker at all: Pikkupstix hands only the shortfall, only at
 *    stage 40 or 50 before the infusion, and only when the pack and the bank together do not hold the kit (derived from the
 *    quest stage and the items, so it is the same after a restart; no save field). A soft in-memory cap (CAP per bot per
 *    run) only logs-and-stops a runaway.
 *  - wolfwhistle step 22 "open the trapdoor" / step 33 "climb down to the cellar obelisk": 5 clicks, the bot never moved,
 *    key still in the pack and has-key false = SummoningTrainingRoom's handler never ran. Same signature as P63 (a movement
 *    or interaction lock left by a scene or the guarded walk drops the click without a word). Settle (calm) before both
 *    trapdoor clicks and both climb-downs.
 *  - restlessghost "walk to the altar": the corridor from the ladder (x 3109-3110) and the altar room (3112-3119, 9564-9568)
 *    are split by a wall along x 3110/3111 (cache clip flags); the only way in is the Large door 33060 at 3111,9559, which
 *    is shut and 8 tiles from where the walker stalls (3110,9567), past openDoorToward's reach. Walk to the door, open it,
 *    then the altar; and the same door on the way out.
 */
final class P92 {
    private P92() { }
    static final boolean ON = Boolean.getBoolean("quest.p92Fix");
    static final int CAP = Math.max(1, Integer.getInteger("quest.p92Fix.cap", 2)); // s4b L1: one first kit and one replacement (was 3)
    static final java.util.Map<String, Integer> HANDED = new java.util.concurrent.ConcurrentHashMap<>();
    /** quest2: the UTC day HANDED counts for, per bot. The count is saved in the bot's quests.json entry ("p92Day", "p92Handed") and
     *  read back at load, so a restart on the same day keeps the cap; a new day starts at 0 (as a daily restart did). */
    static final java.util.Map<String, String> DAY = new java.util.concurrent.ConcurrentHashMap<>();

    /** quest2 (pure): the saved count a run starts with: the same UTC day's only, never negative. */
    static int carry(String savedDay, int saved, String today) { return savedDay != null && savedDay.equals(today) && saved > 0 ? saved : 0; }

    /** quest2: hand-outs this bot had today (name in lower case). */
    static int handed(String name, String today) {
        String d = DAY.get(name);
        if (d != null && !d.equals(today)) { DAY.remove(name); HANDED.remove(name); }
        return HANDED.getOrDefault(name, 0);
    }

    static void hand(String name, String today) { handed(name, today); DAY.put(name, today); HANDED.merge(name, 1, Integer::sum); }

    /** quest2: into the bot's quests.json entry (nothing written at 0). */
    @SuppressWarnings("unchecked")
    static void put(org.json.simple.JSONObject j, String name, String today) {
        int n = handed(name, today);
        if (n > 0) { j.put("p92Day", today); j.put("p92Handed", n); }
    }

    /** quest2: back from the entry; a missing, malformed or older-day value leaves the count at 0 and never fails the load. */
    static void read(org.json.simple.JSONObject j, String name, String today) {
        try {
            if (j == null || !(j.get("p92Day") instanceof String) || !(j.get("p92Handed") instanceof Number)) return;
            int n = carry((String) j.get("p92Day"), ((Number) j.get("p92Handed")).intValue(), today);
            if (n > 0) { HANDED.put(name, n); DAY.put(name, today); }
        } catch (Throwable ignored) { }
    }

    /**
     * The supplies Pikkupstix hands: {charms, pouches, shards} short of `need` sets, counting the pack AND the bank; null =
     * none (wrong stage, already infused, nothing to make, or the kit is already there).
     */
    static int[] handOut(int stage, boolean infused, int need, int charms, int pouches, int shards) {
        if (stage != 40 && stage != 50 || infused || need <= 0) return null;
        int g = Math.max(0, need - charms), e = Math.max(0, need - pouches), sh = Math.max(0, 7 * need - shards);
        return g + e + sh == 0 ? null : new int[]{g, e, sh};
    }

    /** Ghost: the Wizards' Tower basement east of the x 3110/3111 wall (the altar room and the room south of it). */
    static boolean altarSide(int x, int y, int z) { return z == 0 && y > 6400 && x >= 3111 && x <= 3120 && y >= 9556 && y <= 9569; }
}
