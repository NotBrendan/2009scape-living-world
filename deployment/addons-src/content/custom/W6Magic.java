package content.custom;

/**
 * W6 skills, wave 3 Magic lane (the design notes section 3, Magic row): three -D flags, default OFF;
 * with a flag off its caller runs train-61's code unchanged.
 *
 *   -Dbots.magic.tele      a bot with the Magic level casts a real standard teleport in place of the paid fare
 *                          (Ledger.Wallet.payTeleport): runes from its bank, then its pack (a worn elemental staff pays
 *                          its rune), the shortfall bought from bot stock with Ledger.consume (sellers paid); Magic xp as a
 *                          player's cast. Short of level or runes: the old fare, untouched
 *   -Dbots.magic.alchAll   High Alchemy as a trip-end routine for any bot with Magic 55+ (not only on a break at the GE);
 *                          the profit test and Ledger's fleet daily cap (alch.cap) stay, plus a fleet-wide hourly cap on
 *                          the coins alching mints (bots.magic.alchAll.perHour); 65 Magic xp a cast as the engine gives
 *   -Dbots.magic.anyStaff  a mage that cannot afford the staff of fire (keep 300) wears a basic staff (1379) and casts
 *                          wind strike: its own staff from the bank, else bought at guide (one staff, the shop's coins
 *                          leave the game), wind strike runes from bot stock (else guide), instead of the
 *                          "mage without a staff" melee fallback
 *
 * This class holds only the flags, the pure rules and the counters (plain java, TestW6Magic). The world side of the
 * teleport cast is W6MagicCast; the alch and staff hooks are in SmartAdventurers.
 */
final class W6Magic {
    private W6Magic() { }

    static boolean flag(String k) { return Boolean.getBoolean(k); }

    static final int LAW = 563, AIR = 556, WATER = 555, EARTH = 557, FIRE = 554, MIND = 558, NATURE = 561;

    // ------------------------------------------------------------------ teleport casts
    static final boolean TELE = flag("bots.magic.tele");
    /** {Magic level, law, air, element rune id (0 none), element amount, xp x10}: Varrock, Lumbridge, Falador, Camelot (the engine's standard book). */
    static final int[][] TELES = {{25, 1, 3, FIRE, 1, 350}, {31, 1, 3, EARTH, 1, 410}, {37, 1, 3, WATER, 1, 480}, {45, 1, 5, 0, 0, 555}};
    static final String[] TELE_NAMES = {"Varrock", "Lumbridge", "Falador", "Camelot"};
    static volatile int teleCast, teleBought, teleNoLevel, teleShort, teleRunesOwn, teleMissing;
    static volatile long teleGp;

    /** The rune a worn staff gives free: staff of air/water/earth/fire (1381/1383/1385/1387) and the battlestaves / mystic staves of the same; 0 none. */
    static int staffRune(int weapon) {
        switch (weapon) {
            case 1381: case 1397: case 1405: return AIR;
            case 1383: case 1395: case 1403: return WATER;
            case 1385: case 1399: case 1407: return EARTH;
            case 1387: case 1393: case 1401: case 3053: case 3054: return FIRE;
            default: return 0;
        }
    }
    /** {id, amount} pairs a teleport row needs, the staff's rune left out. */
    static int[][] teleRunes(int row, int weapon) {
        int[] t = TELES[row]; int free = staffRune(weapon);
        java.util.List<int[]> out = new java.util.ArrayList<>();
        if (t[1] > 0 && free != LAW) out.add(new int[]{LAW, t[1]});
        if (t[2] > 0 && free != AIR) out.add(new int[]{AIR, t[2]});
        if (t[3] != 0 && t[4] > 0 && free != t[3]) out.add(new int[]{t[3], t[4]});
        return out.toArray(new int[0][]);
    }
    /**
     * The teleport row to cast: the highest the level allows whose runes the bot already holds (pack + bank); else the
     * highest the level allows (the shortfall is bought). -1 under Magic 25.
     */
    static int pickTele(int magic, int weapon, java.util.function.IntUnaryOperator held) {
        int top = -1; for (int i = 0; i < TELES.length; i++) if (magic >= TELES[i][0]) top = i;
        for (int i = top; i >= 0; i--) { boolean all = true; for (int[] r : teleRunes(i, weapon)) if (held.applyAsInt(r[0]) < r[1]) all = false; if (all) return i; }
        return top;
    }
    /** What must be bought: amount - held, per rune (0 when held). */
    static int shortOf(int need, int held) { return Math.max(0, need - Math.max(0, held)); }
    /** The shortfall can be bought: stock has it (and a floor of 10 left) and the purse pays it at the consume price. */
    static boolean canBuy(int[] shortN, int[] stock, long[] price, long coins) {
        long cost = 0;
        for (int i = 0; i < shortN.length; i++) { if (shortN[i] <= 0) continue; if (stock[i] < shortN[i] + 10) return false; cost += Math.max(1, price[i]) * shortN[i]; }
        return coins >= cost;
    }
    /** t62 fix 3: the cast goes ahead only when bank + pack hold every rune it needs (else the fare, nothing taken). */
    static boolean allHeld(int[][] runes, java.util.function.IntUnaryOperator held) {
        for (int[] r : runes) if (held.applyAsInt(r[0]) < r[1]) return false;
        return true;
    }

    // ------------------------------------------------------------------ High Alchemy as a routine
    static final boolean ALCH_ALL = flag("bots.magic.alchAll");
    static final int ALCH_LEVEL = 55;
    static final double ALCH_XP = 65.0;
    /** Coins the whole fleet's alchs may mint an hour (gross: the alch value paid out, not the profit). */
    static final long ALCH_HOUR = Math.max(0, Long.getLong("bots.magic.alchAll.perHour", 20_000L));
    static volatile int alchRoutine, alchDone, alchCasts, alchCapped;
    static volatile long alchGross, alchProfit, alchHourAt, alchHourGp;
    /** The fleet's hour: room for this many minted coins (the hour resets an hour after its first alch). */
    static synchronized boolean alchHourOk(long now, long gross) {
        if (now - alchHourAt > 3_600_000L) { alchHourAt = now; alchHourGp = 0; }
        return alchHourGp + gross <= ALCH_HOUR;
    }
    static synchronized void alchBook(long gross, long profit, int casts) { alchHourGp += gross; alchGross += gross; alchProfit += profit; alchCasts += casts; alchDone++; }
    /** The hour check as a pure rule (for the unit). */
    static boolean hourRoom(long used, long gross, long cap) { return used + gross <= cap; }

    // ------------------------------------------------------------------ a basic staff and wind strike
    static final boolean ANY_STAFF = flag("bots.magic.anyStaff");
    static final int FIRE_STAFF = 1387, BASIC_STAFF = 1379, KEEP = 300, KEEP_BASIC = 100, MIN_CASTS = 20, MAX_CASTS = 100;
    /** Wind strike, the spell() row a basic staff casts: {spell id, mind, 1, air, 1}. */
    static final int[] WIND_STRIKE = {1, MIND, 1, AIR, 1};
    static volatile int staffWorn, staffBought, staffRunes, staffNoCoins, staffNoRoom, staffRefund, staffRuneNoRoom, staffRuneRefund;
    /** t62 fix 4b: coins back for Wind Strike runes bought but not placed: the stock part at its consume price plus the coins paid for the rest. */
    static long runeRefund(int fromStock, long unit, long paid) { return (long) Math.max(0, fromStock) * Math.max(0, unit) + Math.max(0, paid); }
    /** t62 fix 4: the staff is paid for only when it has a place to go (bank or pack). */
    static boolean staffRoom(boolean bankRoom, boolean packRoom) { return bankRoom || packRoom; }
    /** Kit rule: a worn basic staff stays (and the robes are bought) while the staff of fire would leave under 300. */
    static boolean keepBasic(boolean on, int worn, long coins, long fireStaffPrice) { return on && worn == BASIC_STAFF && coins - Math.max(1, fireStaffPrice) < KEEP; }
    /** A mage's staff for the casting rules: the staff of fire, and with the flag the basic staff. */
    static boolean isStaff(boolean on, int worn) { return worn == FIRE_STAFF || (on && worn == BASIC_STAFF); }
    /**
     * The last-chance buy for a mage without a staff: {staff cost (0 = owns one), wind strike casts to buy}; null = no help
     * (it cannot keep 100 gp after the staff and MIN_CASTS casts it does not already hold).
     */
    static long[] basicPlan(long coins, boolean ownsBasic, long staffPrice, long castPrice, int heldCasts) {
        long staff = ownsBasic ? 0 : Math.max(1, staffPrice);
        long purse = coins - staff - KEEP_BASIC;
        if (purse < 0) return null;
        int want = Math.max(0, MAX_CASTS - Math.max(0, heldCasts));
        long n = heldCasts >= MIN_CASTS ? 0 : Math.min(want, purse / Math.max(1, castPrice));
        if (heldCasts < MIN_CASTS && heldCasts + n < MIN_CASTS) return null;
        return new long[]{staff, n};
    }

    // ------------------------------------------------------------------ one line for the hourly log
    static boolean anyOn() { return TELE || ALCH_ALL || ANY_STAFF; }
    static String flags() {
        StringBuilder b = new StringBuilder();
        if (TELE) b.append("tele "); if (ALCH_ALL) b.append("alchAll "); if (ANY_STAFF) b.append("anyStaff ");
        return b.toString().trim();
    }
    /** alchedToday / cap: Ledger's fleet day (passed in so this class stays world-free). */
    static String line(long alchedToday, long dayCap) {
        return "[W6Magic] on=[" + flags() + "] teleCast=" + teleCast + " teleBought=" + teleBought + " teleRunesOwn=" + teleRunesOwn + " teleGp=" + teleGp
                + " teleNoLevel=" + teleNoLevel + " teleShort=" + teleShort + " teleMissing=" + teleMissing
                + " alchRoutine=" + alchRoutine + " alchDone=" + alchDone + " alchCasts=" + alchCasts + " alchGross=" + alchGross + " alchProfit=" + alchProfit
                + " alchCapped=" + alchCapped + " alchHourGp=" + alchHourGp + "/" + ALCH_HOUR + " alchDay=" + alchedToday + "/" + dayCap
                + " staffWorn=" + staffWorn + " staffBought=" + staffBought + " staffRunes=" + staffRunes + " staffNoCoins=" + staffNoCoins + " staffNoRoom=" + staffNoRoom + " staffRefund=" + staffRefund + " staffRuneNoRoom=" + staffRuneNoRoom + " staffRuneRefund=" + staffRuneRefund;
    }
}
