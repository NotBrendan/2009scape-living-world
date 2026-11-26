package content.custom;

/**
 * P88 (-Dquest.p88Fix, default off): live train-37+ quest failures the P83 unit did not see, because its bots started
 * with an empty pack, money, and the supplies in the bank, next to Pikkupstix:
 *  - wolfwhistle step 20 "get the pouch supplies together": a stage-40 bot with no gold charms anywhere (dropped when the
 *    bones talk handed them to a full pack before P83's room step) or too poor for the shard bill. Pikkupstix hands the
 *    missing charms, pouches and shards once per login, as his own stage-50 branch does (taken-summoning-supplies).
 *  - wolfwhistle step 22 "open the trapdoor": a full pack keeps the trapdoor key in the bank ("take the key from the
 *    bank" puts it back when the pack has no room, and Pikkupstix hands no new key while one is banked), so the talk ends
 *    without one and the Open clicks find it locked. Room first, and the key from the bank before the clicks. (A probe on
 *    every tile round the trapdoor got down from all of them: the standing tile is not it.)
 *  - restlessghost step 10 "get the ghost's skull": a full pack sends the altar's skull to the floor (stage 40), the next
 *    search finds nothing. Room first, the skull off the floor, and at stage 40 with no skull anywhere the altar gives
 *    another (as it does in the game).
 */
final class P88 {
    private P88() { }
    static final boolean ON = Boolean.getBoolean("quest.p88Fix");
    /** Bots Pikkupstix has resupplied since boot: one hand-out per bot per server run, not per login (Fable b41 review). */
    static final java.util.Set<String> HANDED = java.util.concurrent.ConcurrentHashMap.newKeySet();
}
