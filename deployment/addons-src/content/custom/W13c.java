package content.custom;

import content.global.skill.construction.BuildHotspot;
import content.global.skill.construction.BuildingUtils;
import content.global.skill.construction.Decoration;
import content.global.skill.construction.Hotspot;
import content.global.skill.construction.HouseLocation;
import content.global.skill.construction.HouseManager;
import content.global.skill.construction.NailType;
import content.global.skill.construction.Room;
import content.global.skill.construction.RoomProperties;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;

/**
 * W13 lane C (the design notes). Four flags, all default OFF; with every flag off nothing here runs, no RNG is
 * drawn here at any time, and no coins are created (house, rooms and furniture are paid out of the bot's own coins and planks).
 *
 *  - bots.con.hotspot       (WORKS, data model): at the plank run's MILL_BUILD step, a bot with Construction and planks buys a
 *                           Rimmington house (5000 gp) and a parlour (1000 gp) if it has none, then builds one real chair in the
 *                           parlour's CHAIRS_1 hotspot (the house's saved Room/Hotspot state, the same fields the build interface
 *                           sets). The bot is NOT moved into the dynamic house region (TODO below).
 *  - bots.prayer.houseAltar (PART): with con.hotspot, a Construction 45+ bot holding 4 oak planks and 50k + reserve builds a chapel
 *                           and an oak altar the same way. Offering bones at it is a TODO (needs the bot inside its house region).
 *  - bots.rc.natureLaw      (DRAFT): eligibility only (levels, talisman/tiara); no trip yet. Karamja: AfkTasks.Boat exists
 *                           (Port Sarim to Musa Point), the walk Musa Point to the nature ruins is unchecked. Entrana: no boat in the
 *                           walker and the monks' weapon search is not modelled.
 *  - artisans.herbDruidic   (DRAFT): reports whether the herbalist has Druidic Ritual; the herbalist is still levelled by
 *                           Artisans.seedTrade. TODO: hand the herbalist to QuestBook/QuestRunner (QuestDruidicRitual) first.
 */
final class W13c {
    private W13c() { }

    static final boolean CON_HOTSPOT = Boolean.getBoolean("bots.con.hotspot");
    static final boolean HOUSE_ALTAR = Boolean.getBoolean("bots.prayer.houseAltar");
    static final boolean NATURE_LAW = Boolean.getBoolean("bots.rc.natureLaw");
    static final boolean HERB_DRUIDIC = Boolean.getBoolean("artisans.herbDruidic");

    static final int HOUSE_GP = 5000, PARLOUR_GP = 1000, CHAPEL_GP = 50_000;
    /** Coins kept back after any house purchase (the plank run's own 15k gate is the floor it starts from). */
    static final long RESERVE = Long.getLong("bots.con.hotspot.reserve", 20_000L);
    static final int GARDEN_X = 4, GARDEN_Y = 3, PARLOUR_X = 4, PARLOUR_Y = 4, CHAPEL_X = 5, CHAPEL_Y = 4;
    static final int PLANK = 960, OAK_PLANK = 8778;

    static int built, altars, noRoom, noCoins, failed;

    // ------------------------------------------------------------------ pure parts

    /** The best decoration of a hotspot this bot can build with only the given plank (and enough of it), or null. Pure. */
    static Decoration pick(Decoration[] decos, int plank, int have, int level) {
        Decoration best = null;
        for (Decoration d : decos) {
            Item[] it = d.getItems();
            if (it == null || it.length != 1 || it[0].getId() != plank || it[0].getAmount() > have || d.getLevel() > level) continue;
            if (best == null || d.getExperience() > best.getExperience()) best = d;
        }
        return best;
    }

    /** May the bot buy this much and keep the reserve? Pure. */
    static boolean affords(long coins, long cost) { return coins - cost >= RESERVE; }

    // ------------------------------------------------------------------ bots.con.hotspot / bots.prayer.houseAltar

    /**
     * Called from SmartAdventurers.Brain MILL_BUILD (flag on only), before its planks leave the bank. Builds at most one real
     * hotspot and returns how many of `build` planks it used (0 = nothing done; the caller builds the rest as before).
     * nailsPaid: the caller already took a nail per plain plank (W7c bots.plankTypes on).
     */
    static int hotspot(Player p, int plank, int build, boolean nailsPaid) {
        if (!CON_HOTSPOT || p == null || build <= 0) return 0;
        HouseManager h = p.getHouseManager();
        int con = p.getSkills().getStaticLevel(Skills.CONSTRUCTION);
        if (h.getLocation() == HouseLocation.NOWHERE) {
            if (!affords(Ledger.Wallet.coins(p), HOUSE_GP) || !Ledger.Wallet.payFee(p, HOUSE_GP, "house")) { noCoins++; return 0; }
            h.createNewHouseAt(HouseLocation.RIMMINGTON);
        }
        Room[][][] rooms = h.getRooms();
        if (HOUSE_ALTAR && plank == OAK_PLANK && con >= 45) {
            int used = altar(p, rooms, build);
            if (used != 0) return Math.max(0, used);
        }
        Room parlour = rooms[0][PARLOUR_X][PARLOUR_Y];
        if (parlour == null) {
            if (!affords(Ledger.Wallet.coins(p), PARLOUR_GP) || !Ledger.Wallet.payFee(p, PARLOUR_GP, "parlour")) { noCoins++; return 0; }
            parlour = Room.create(p, RoomProperties.PARLOUR);
            BuildingUtils.buildRoom(p, parlour, 0, PARLOUR_X, PARLOUR_Y, parlour.getExits(), false);
        }
        if (parlour.getProperties() != RoomProperties.PARLOUR) { noRoom++; return 0; }
        Hotspot hs = parlour.getHotspot(BuildHotspot.CHAIRS_1);
        if (hs == null || hs.getDecorationIndex() > -1) { noRoom++; return 0; } // one chair per house; later rows: TODO other hotspots
        return place(p, hs, plank, build, con, nailsPaid);
    }

    /** -1: tried and failed (stop); 0: not this time (caller goes on to the chair); n: planks used for the altar. */
    private static int altar(Player p, Room[][][] rooms, int build) {
        Room chapel = rooms[0][CHAPEL_X][CHAPEL_Y];
        if (chapel == null) {
            if (rooms[0][PARLOUR_X][PARLOUR_Y] == null) return 0; // the parlour comes first (it joins the garden)
            if (!affords(Ledger.Wallet.coins(p), CHAPEL_GP) || !Ledger.Wallet.payFee(p, CHAPEL_GP, "chapel")) { noCoins++; return 0; }
            chapel = Room.create(p, RoomProperties.CHAPEL);
            BuildingUtils.buildRoom(p, chapel, 0, CHAPEL_X, CHAPEL_Y, chapel.getExits(), false);
        }
        if (chapel.getProperties() != RoomProperties.CHAPEL) return 0;
        Hotspot hs = chapel.getHotspot(BuildHotspot.ALTAR);
        if (hs == null || hs.getDecorationIndex() > -1) return 0;
        int used = place(p, hs, OAK_PLANK, build, p.getSkills().getStaticLevel(Skills.CONSTRUCTION), true);
        if (used > 0) altars++;
        return used == 0 ? -1 : used;
    }

    /** Takes the planks (and nails for plain planks) from the bank, sets the hotspot, gives the decoration's XP. */
    private static int place(Player p, Hotspot hs, int plank, int build, int con, boolean nailsPaid) {
        Decoration[] decos = hs.getHotspot().getDecorations();
        Decoration d = pick(decos, plank, Math.min(build, p.getBank().getAmount(plank)), con);
        if (d == null) return 0;
        int need = d.getItems()[0].getAmount();
        int nails = nailsPaid ? 0 : d.getNailAmount();
        int nailId = -1;
        if (nails > 0) { for (NailType t : NailType.values()) if (p.getBank().getAmount(t.getItemId()) >= nails) { nailId = t.getItemId(); break; } if (nailId < 0) return 0; }
        if (!p.getBank().remove(new Item(plank, need))) { failed++; return 0; }
        if (nailId > 0 && !p.getBank().remove(new Item(nailId, nails))) { p.getBank().add(new Item(plank, need)); failed++; return 0; }
        int idx = -1;
        for (int i = 0; i < decos.length; i++) if (decos[i] == d) { idx = i; break; }
        hs.setDecorationIndex(idx);
        try { p.getSkills().addExperience(Skills.CONSTRUCTION, d.getExperience(), true); } catch (Throwable ignored) { }
        built++;
        System.out.println("[W13c] " + p.getUsername() + " built " + d.name().toLowerCase(java.util.Locale.ROOT) + " in its house (" + need + " planks" + (nails > 0 ? ", " + nails + " nails" : "") + ")");
        // TODO (bots.con.hotspot): walk to the Rimmington portal and HouseManager.enter(p, true) so the build shows; a bot in a
        // dynamic region needs AfkTasks/NavGrid to treat the house region as a no-walk area and HouseManager.leave on any errand.
        return need;
    }

    /** bots.prayer.houseAltar: has this bot a built altar (offering TODO: use bones on it inside the house region). */
    static boolean altarBuilt(Player p) {
        if (!HOUSE_ALTAR || p == null) return false;
        try { Room c = p.getHouseManager().getRooms()[0][CHAPEL_X][CHAPEL_Y]; return c != null && c.isBuilt(BuildHotspot.ALTAR); } catch (Throwable t) { return false; }
    }

    // ------------------------------------------------------------------ bots.rc.natureLaw (draft)

    static final int NATURE_TALI = 1462, NATURE_TIARA = 5541, LAW_TALI = 1458, LAW_TIARA = 5545;
    static final int NATURE_LVL = 44, LAW_LVL = 54;

    /** Pure: which of nature/law this runecrafter could run ("nature", "law" or null). Law only once Entrana travel exists. */
    static String natureLaw(int rc, boolean natureKey, boolean lawKey, boolean entranaTravel) {
        if (!NATURE_LAW) return null;
        if (rc >= LAW_LVL && lawKey && entranaTravel) return "law";
        if (rc >= NATURE_LVL && natureKey) return "nature";
        return null;
        // TODO: RcRun.Route rows for nature (Karamja ruins ~2869,3019; bank: none on Karamja, so Shilo or the Port Sarim deposit box)
        // and law (Entrana ruins ~2858,3381; no weapons or armour on the boat); AfkTasks.Boat covers Port Sarim <-> Musa Point only.
    }

    // ------------------------------------------------------------------ artisans.herbDruidic (draft)

    /** Has the herbalist done Druidic Ritual? Logged once at seedTrade with the flag on; seeding is unchanged (TODO). */
    static boolean druidicDone(Player p) {
        try { return p.getQuestRepository().isComplete(content.data.Quests.DRUIDIC_RITUAL); } catch (Throwable t) { return false; }
    }

    static String line() { return "[W13c] hotspot built=" + built + " altars=" + altars + " noRoom=" + noRoom + " noCoins=" + noCoins + " failed=" + failed; }
}
