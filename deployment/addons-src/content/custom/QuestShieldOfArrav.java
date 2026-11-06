package content.custom;

import core.game.component.Component;
import core.game.interaction.IntType;
import core.game.interaction.InteractionListeners;
import core.game.interaction.InterfaceListeners;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.item.GroundItem;
import core.game.node.item.GroundItemManager;
import core.game.node.item.Item;
import core.game.world.map.Location;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static content.custom.QuestScript.attr;
import static content.custom.QuestScript.has;
import static content.custom.QuestScript.stageAtLeast;

/**
 * Shield of Arrav, BOTH gangs, played by two bots. Server files: content/region/misthalin/varrock/quest/shieldofarrav/
 * (ShieldofArrav.java, ReldoDialogue.java, ShieldofArravBook.kt, StravenDialogue.java, JohnnyBeardNPC.java, KatrineDialogue.java,
 * CharlieTheTrampDialogue.kt, WeaponsMasterDialogue.java, ShieldArravPlugin.java, CuratorHaigHalenDialogue.kt +
 * CuratorHaigHalenSOADialogue.java, CertificatePlugin.java, KingRoaldArravDialogue.kt), content/region/misthalin/varrock/dialogue/
 * BaraekDialogue.java and KingRoaldDialogue.kt.
 *
 * ONE BOT CANNOT FINISH THIS ALONE. A player joins exactly one gang; the chest with the Phoenix half (2404) only gives it to a
 * Phoenix member at stage 70, the cupboard with the Black Arm half (2401) only to a Black Arm member at stage 70
 * (ShieldArravPlugin). The curator turns a half shield into TWO half-certificates of that gang (Phoenix 11173 x2, Black Arm
 * 11174 x2), and the king only pays for the joined certificate 769 (11173 used on 11174, CertificatePlugin). The halves are
 * tradeable (item_configs.json; TradeContainer lets even ironmen trade 11173/11174/759). So two friends of the two gangs each
 * get their own gang's pair and swap one half: both can then join a certificate.
 *
 * The owner's design (2026-10-23): the gang goes by the bot's name, hash parity: even = Phoenix, odd = Black Arm (a bot that
 * already belongs to a gang, or is on one gang's mission, keeps it). Each bot plays its own side to its two half-certificates,
 * then waits at the museum for a partner of the other gang (Crew below: a pairing claimed atomically in a static map, released
 * when either bot leaves the quest). The two stand together and swap one half each (BotSwap: one tick, pack to pack, rolled
 * back on any failure). No partner within -Dquests.soaWaitMin (30 min; up to twice that while a bot of the other gang is still
 * on the quest): the spare half goes into bot stock (Pool) and the other gang's half comes out of it when there is one.
 *
 * The Black Arm side needs the Phoenix weapon store key 759 (door 2398 opens at stage 60 only with the key in the pack), which
 * only Straven hands out, to Phoenix members. A Black Arm bot gets two Phoenix crossbows from bot stock if there are any,
 * else the key from bot stock, else from a Phoenix partner at the museum (the same pairing: the Phoenix bot lends it, the way
 * the journal says "a friend's help"); then it robs the store itself: kill the Weaponsmaster (643: he stops a non-member taking
 * a crossbow while he is visible, WeaponsMasterDialogue "Stop! Thief!"), take the two crossbows (ground spawns 767 at
 * 3245,3385,1 and 3243,3383,1).
 *
 * PHOENIX stages: 0 -> 10 Reldo (2660, palace library, spawn 3208,3496): options 1 ("Hello stranger."), 1 ("I'm in search of a
 * quest."): quest.start. 10: bookcase 2402 "Search" at 3212,3493 (ShieldArravPlugin; only at stage 10 and with no book 757
 * in pack or bank) opens Reldo's "book" lines, continue only: the book (757). Reading it (ShieldofArravBook.kt, item
 * "read", book interface 49) sets stage 20 on its last page: the next-page button is child 53 (BookInterface
 * FANCY_BOOK_3_49_BUTTON_IDS). 20 -> 30 Reldo: option 1 ("Hello stranger."), then continue only. 30 -> 40 Baraek (547,
 * Varrock square, spawn 3217,3435): options 1 ("Can you tell me where I can find the Phoenix Gang?"), 1 ("Okay. Have 20
 * gold coins."): 20 coins (in the PACK) taken. 40 -> 60 Straven (644, Phoenix hideout under the alley, spawn
 * 3247,9781): options 1 ("I know who you are!"), 1 ("I'd like to offer you my services."): stage 60 + phoenix-mission.
 * Kill Jonny the beard (645, Blue Moon Inn, spawn 3223,3401; 2 hp, npc_configs.json): JohnnyBeardNPC drops the intel
 * report 761 for the killer at stage 60 with the phoenix mission. 60 -> 70 Straven with the report in the pack: continue
 * only: the key 759, stage 70, phoenix-gang.
 * Hideout (cache map): ladder 24363 "Climb-down" at 3244,3383,0 / ladder 2405 "Climb-up" at 3244,9783 (generic
 * ClimbActionHandler); door 2397 at 3247,9779 (north edge) opens only for a Phoenix member (ShieldArravPlugin); doors
 * 24381 at 3249,9774 / 3250,9770 / 3238,9767 (plain doors, the walker opens them); the chest 2403 "Open" at 3235,9761
 * becomes 2404 (shared object); 2404 "Search" at stage 70 as a Phoenix: the Phoenix shield half 763.
 *
 * BLACK ARM stages: 0..40 -> 50 Charlie the Tramp (641, the alley mouth, spawn 3207,3392, walks 6): options 5 ("Is there
 * anything down this alleyway?"), 2 ("Do you think they would let me join?"), continue only: starts the quest if it isn't and
 * sets stage 50 for a bot in no gang. 50 -> 60 Katrine (642, the Black Arm house, spawn 3185,3385): options 1 ("I've heard
 * you're the Black Arm Gang."), 1 ("I'd rather not reveal my sources."), 1 ("I want to become a member of your gang."), 1
 * ("Well, you can give me a try can't you?"), 1 ("Ok, no problem."): stage 60 + blackarm-mission. The weapon store (probe):
 * door 2398 at 3251,3386,0 (south edge; inside is y <= 3385), ladder 24354 "Climb-up" at 3252,3384,0, ladder 24362
 * "Climb-down" at 3252,3384,1; the Weaponsmaster 643 (42 hp, 15/15/15) at 3246,3383,1. 60 -> 70 Katrine with the two
 * crossbows in the pack: continue only, stage 70 + black-arm-gang. Her house: door 2399 at 3185,3388 (south edge, only a
 * member gets through), staircase 24356 "Climb-up" at 3188,3389 to 3188,3392,1 (ShieldArravPlugin), cupboard 2400 "Open" at
 * 3189,3385,1 becomes 2401, "Search" at stage 70 as a Black Arm: the Black Arm shield half 765; staircase 24359 "Climb-down"
 * at 3188,3390,1.
 *
 * BOTH: Curator Haig Halen (646, Varrock museum, spawn 3257,3447, doesn't walk): Topic "I have the Shield of Arrav" (shown at
 * stage 70 only), continue only: the half shield (in the PACK) for two half-certificates of the gang (two slots). The
 * partner swap at the museum. King Roald (648, palace, spawn 3220,3471): an options screen "Talk about Priest in Peril / All
 * Fired Up" + "Talk about Shield of Arrav" when he has another topic for the bot (KingRoaldDialogue: Priest in Peril not
 * complete, or it is and All Fired Up is unstarted or at stage 90), pick 2; with only the Arrav topic no options. With 769 in
 * the pack (and no half-certificate): continue only, 600 coins, quest.finish: 1 QP (ShieldofArrav constructor), stage 100.
 * Rewards: 600 coins, 1 QP.
 */
final class QuestShieldOfArrav {
    private QuestShieldOfArrav() { }

    static final content.data.Quests Q = content.data.Quests.SHIELD_OF_ARRAV;
    static final String SLUG = "shieldofarrav";
    static final int RELDO = 2660, BARAEK = 547, STRAVEN = 644, JONNY = 645, CURATOR = 646, KING = 648;
    static final int CHARLIE = 641, KATRINE = 642, WEAPONSMASTER = 643;
    static final int BOOK = 757, KEY = 759, REPORT = 761, PHOENIX_SHIELD = 763, BLACKARM_SHIELD = 765, CROSSBOW = 767, PHOENIX_HALF = 11173, BLACKARM_HALF = 11174, CERTIFICATE = 769, COINS = 995;
    static final int BOOKCASE = 2402, LADDER_DOWN = 24363, LADDER_UP = 2405, PHOENIX_DOOR = 2397, CHEST_SHUT = 2403, CHEST_OPEN = 2404;
    static final int STORE_DOOR = 2398, STORE_LADDER_UP = 24354, STORE_LADDER_DOWN = 24362;
    static final int BLACKARM_DOOR = 2399, BLACKARM_STAIRS_UP = 24356, BLACKARM_STAIRS_DOWN = 24359, CUPBOARD_SHUT = 2400, CUPBOARD_OPEN = 2401;
    static final int BOOK_IFACE = 49, BOOK_NEXT = 53;                     // BookInterface.FANCY_BOOK_3_49 and its next-page button

    static final Location RELDO_AT = Location.create(3210, 3494, 0);      // the library (Reldo walks 3 from 3208,3496)
    static final Location BOOKCASE_AT = Location.create(3212, 3493, 0);
    static final Location BARAEK_AT = Location.create(3217, 3434, 0);     // Varrock square (spawn 3217,3435, doesn't walk)
    static final Location ALLEY = Location.create(3244, 3384, 0);         // by the hideout ladder 24363 (3244,3383)
    static final Location LADDER_DOWN_AT = Location.create(3244, 3383, 0);
    static final Location LADDER_UP_AT = Location.create(3244, 9783, 0);
    static final Location STRAVEN_AT = Location.create(3247, 9781, 0);
    static final Location DOOR_AT = Location.create(3247, 9779, 0);
    static final Location DOOR_SOUTH = Location.create(3247, 9778, 0);
    static final Location CHEST_AT = Location.create(3235, 9761, 0);
    static final Location CHEST_SIDE = Location.create(3235, 9762, 0);
    static final Location JONNY_AT = Location.create(3223, 3401, 0);      // Blue Moon Inn (spawn, walks 2)
    static final Location CURATOR_AT = Location.create(3257, 3445, 0);
    static final Location KING_AT = Location.create(3221, 3471, 0);

    static final Location CHARLIE_AT = Location.create(3207, 3392, 0);    // the alley mouth (spawn, walks 6)
    static final Location KATRINE_AT = Location.create(3185, 3385, 0);    // spawn, walks 6
    static final Location BLACKARM_DOOR_AT = Location.create(3185, 3388, 0);
    static final Location BLACKARM_STAIRS_AT = Location.create(3188, 3389, 0);
    static final Location BLACKARM_DOWN_AT = Location.create(3188, 3390, 1);
    static final Location CUPBOARD_AT = Location.create(3189, 3385, 1);
    static final Location STORE_OUTSIDE = Location.create(3251, 3387, 0);
    static final Location STORE_DOOR_AT = Location.create(3251, 3386, 0);
    static final Location STORE_LADDER_AT = Location.create(3252, 3384, 0);
    static final Location STORE_LADDER_TOP = Location.create(3252, 3384, 1);
    static final Location MASTER_AT = Location.create(3246, 3383, 1);
    static final Location[] CROSSBOW_AT = {Location.create(3245, 3385, 1), Location.create(3243, 3383, 1)};

    static boolean under(Player p) { return p.getLocation().getY() > 6400; }
    static boolean southOfDoor(Player p) { return under(p) && p.getLocation().getY() <= 9779 && p.getLocation().getX() < 3260; }

    static boolean any(Player p, int id) { return p.getInventory().getAmount(id) > 0 || p.getBank().getAmount(id) > 0; }
    static int count(Player p, int id) { return p.getInventory().getAmount(id) + p.getBank().getAmount(id); }

    // ------------------------------------------------------------------ the gang

    /** Phoenix or Black Arm: a gang it is in or a mission it is on first, else its name's hash parity (even = Phoenix). */
    static boolean phoenix(Player p) {
        if (attr(p, "phoenix-gang")) return true;
        if (attr(p, "black-arm-gang")) return false;
        boolean pm = attr(p, "phoenix-mission"), bm = attr(p, "blackarm-mission");
        if (pm != bm) return pm;
        if (any(p, PHOENIX_SHIELD) || any(p, REPORT)) return true;
        if (any(p, BLACKARM_SHIELD)) return false;
        return Math.floorMod(p.getUsername().toLowerCase(Locale.ROOT).hashCode(), 2) == 0;
    }

    static int ownHalf(Player p) { return phoenix(p) ? PHOENIX_HALF : BLACKARM_HALF; }
    static int otherHalf(Player p) { return phoenix(p) ? BLACKARM_HALF : PHOENIX_HALF; }
    static String gangName(boolean phoenix) { return phoenix ? "Phoenix" : "Black Arm"; }

    /** The step only for one gang: the other gang's bots skip it (its checkpoint holds for them). */
    static QuestSteps.Step only(boolean phoenixSide, QuestSteps.Step s) {
        final java.util.function.Predicate<Player> d = s.done;
        s.done = p -> phoenix(p) != phoenixSide || d != null && d.test(p);
        return s;
    }
    static QuestSteps.Step ph(QuestSteps.Step s) { return only(true, s); }
    static QuestSteps.Step ba(QuestSteps.Step s) { return only(false, s); }

    /** The bot has the Phoenix half or something it was turned into (half-certificates, the certificate, the reward). */
    static boolean shieldOrLater(Player p) { return any(p, PHOENIX_SHIELD) || certsOrLater(p); }
    static boolean blackShieldOrLater(Player p) { return any(p, BLACKARM_SHIELD) || certsOrLater(p); }
    static boolean certsOrLater(Player p) { return any(p, PHOENIX_HALF) || any(p, BLACKARM_HALF) || any(p, CERTIFICATE) || stageAtLeast(Q, 100).test(p); }
    static boolean bothHalvesOrLater(Player p) { return has(PHOENIX_HALF, 1).test(p) && has(BLACKARM_HALF, 1).test(p) || any(p, CERTIFICATE) || stageAtLeast(Q, 100).test(p); }
    /** A Phoenix bot's two crossbows for a partner: done when it has them, has no key to the store, or is past the swap. */
    static boolean spareBows(Player p) { return count(p, CROSSBOW) >= 2 || !any(p, KEY) || bothHalvesOrLater(p) || !stageAtLeast(Q, 70).test(p); }
    static boolean bowsOrLater(Player p) { return has(CROSSBOW, 2).test(p) || stageAtLeast(Q, 70).test(p); }

    static boolean chestOpen(Player p) { return QuestSteps.findScenery(p, CHEST_AT, 0, new int[]{CHEST_OPEN}, null, null) != null; }
    static boolean cupboardOpen(Player p) { return QuestSteps.findScenery(p, CUPBOARD_AT, 0, new int[]{CUPBOARD_OPEN}, null, null) != null; }

    static boolean inStore(Player p) { Location l = p.getLocation(); return l.getZ() == 0 && l.getY() <= 3385 && l.getY() >= 3378 && l.getX() >= 3247 && l.getX() <= 3256; }
    static boolean upStore(Player p) { Location l = p.getLocation(); return l.getZ() == 1 && l.getX() >= 3236 && l.getX() <= 3258 && l.getY() >= 3376 && l.getY() <= 3392; }
    static boolean upHouse(Player p) { Location l = p.getLocation(); return l.getZ() == 1 && l.getX() >= 3176 && l.getX() <= 3200 && l.getY() >= 3378 && l.getY() <= 3402; }
    static boolean behindBlackArmDoor(Player p) { Location l = p.getLocation(); return l.getZ() == 0 && l.getY() >= 3388 && l.getY() <= 3394 && l.getX() >= 3180 && l.getX() <= 3192; }

    static Item first(Player p, int id) {
        for (Item i : p.getInventory().toArray()) if (i != null && i.getId() == id) return i;
        return null;
    }

    /** Up to n of an item from the bank into the pack. */
    static void toPack(Player p, int id, int n) {
        int need = n - p.getInventory().getAmount(id);
        int take = Math.min(need, p.getBank().getAmount(id));
        if (take <= 0) return;
        QuestRunner.makeRoom(p, take);
        if (p.getBank().remove(new Item(id, take)) && !p.getInventory().add(new Item(id, take))) p.getBank().add(new Item(id, take));
    }

    static QuestSteps.Custom room(String name, int slots) { return new QuestSteps.Custom(name, 5, null, r -> { QuestRunner.makeRoom(r.bot, slots); return QuestSteps.St.DONE; }); }

    /** Reldo's quest lines don't run while The Knight's Sword sits at stage 10 (ReldoDialogue checks that first). */
    static boolean knightsSwordBusy(Player p) { try { return p.getQuestRepository().getStage(content.data.Quests.THE_KNIGHTS_SWORD) == 10; } catch (Throwable t) { return false; } }

    /** Read the book to its last page: the item's read option, then the book's next-page button until stage 20. */
    static QuestSteps.Step readBook() {
        final int[] st = {0, 0, 0}; // opens, wait, page clicks
        return new QuestSteps.Custom("read the Shield of Arrav book", 200, r -> { st[0] = 0; st[1] = 0; st[2] = 0; }, r -> {
            Player p = r.bot;
            if (stageAtLeast(Q, 20).test(p)) { try { if (p.getInterfaceManager().getComponent(BOOK_IFACE) != null) p.getInterfaceManager().close(); } catch (Throwable ignored) { } return QuestSteps.St.DONE; }
            if (st[1] > 0) { st[1]--; return QuestSteps.St.RUNNING; }
            Component c = p.getInterfaceManager().getComponent(BOOK_IFACE);
            if (c == null) {
                if (st[0] >= 3) { r.log("  the book wouldn't open"); return QuestSteps.St.FAILED; }
                Item b = first(p, BOOK);
                if (b == null) { r.log("  no book in the pack"); return QuestSteps.St.FAILED; }
                QuestSteps.clearPopups(p);
                try { InteractionListeners.run(BOOK, IntType.ITEM, "read", p, b); } catch (Throwable t) { r.log("  read threw " + t); return QuestSteps.St.FAILED; }
                st[0]++; st[1] = 2;
                return QuestSteps.St.RUNNING;
            }
            if (++st[2] > 20) { r.log("  turned 20 pages and the stage didn't move"); return QuestSteps.St.FAILED; }
            try { InterfaceListeners.run(p, c, 0, BOOK_NEXT, 0, -1); } catch (Throwable t) { r.log("  page turn threw " + t); return QuestSteps.St.FAILED; }
            st[1] = 1 + (int) (Math.random() * 3);
            return QuestSteps.St.RUNNING;
        });
    }

    /** Use one half-certificate on the other (CertificatePlugin, a legacy UseWithHandler): the certificate 769. */
    static QuestSteps.St joinCertificates(QuestRunner r) {
        Player p = r.bot;
        if (any(p, CERTIFICATE)) return QuestSteps.St.DONE;
        Item a = first(p, PHOENIX_HALF), b = first(p, BLACKARM_HALF);
        if (a == null || b == null) { r.log("  need both half-certificates in the pack"); return QuestSteps.St.FAILED; }
        boolean ran = false;
        try { ran = InteractionListeners.run(a, b, IntType.ITEM, p); } catch (Throwable ignored) { }
        if (!ran) try { QuestRunner.legacyUseWith(p, a, b); } catch (Throwable t) { r.log("  use-with threw " + t); return QuestSteps.St.FAILED; }
        return p.getInventory().getAmount(CERTIFICATE) > 0 ? QuestSteps.St.DONE : QuestSteps.St.RUNNING;
    }

    /** King Roald's opening screen: a choice only when he has a second topic for this bot (KingRoaldDialogue.open). */
    static Object[] kingPicks(Player p) {
        boolean other;
        try {
            if (!p.getQuestRepository().isComplete(content.data.Quests.PRIEST_IN_PERIL)) other = true;
            else other = !p.getQuestRepository().hasStarted(content.data.Quests.ALL_FIRED_UP) || p.getQuestRepository().getStage(content.data.Quests.ALL_FIRED_UP) == 90;
        } catch (Throwable t) { other = true; }
        return other ? new Object[]{2} : new Object[0];
    }

    static QuestSteps.Talk longTalk(QuestSteps.Talk t) { t.limitTicks = 450; return t; }

    // ------------------------------------------------------------------ the Black Arm robbery

    /** Two Phoenix crossbows from bot stock, if it has them (the store run is skipped then). */
    static QuestSteps.St bowsFromStock(QuestRunner r) {
        Player p = r.bot;
        toPack(p, CROSSBOW, 2);
        int need = 2 - p.getInventory().getAmount(CROSSBOW);
        if (need > 0 && !Boolean.getBoolean("quests.noStock") && MarketBrain.stockOf(CROSSBOW) >= need) {
            QuestRunner.makeRoom(p, need);
            if (p.getInventory().freeSlots() < need) { r.log("  no room in the pack for " + need + " crossbows: not buying"); return QuestSteps.St.DONE; } // batch 17 item3 (audit Q-25): room first, as Pool.take does
            int got = Ledger.consume(p, CROSSBOW, need, "quest");
            if (got > 0) {
                if (p.getInventory().add(new Item(CROSSBOW, got))) r.log("  bought " + got + " x Phoenix crossbow from bot stock");
                else { p.getBank().add(new Item(CROSSBOW, got)); r.log("  bought " + got + " x Phoenix crossbow from bot stock: the pack refused them, banked"); } // batch 17 item3: paid for, never lost
            }
        }
        return QuestSteps.St.DONE; // either way: the store run below does the rest
    }

    // ------------------------------------------------------------------ who may start (batch 17 items 1-2, audit Q-23)

    static final int START_COINS = 50; // Baraek's 20 gp and a margin

    /** Something a Black Arm bot can rob the store with: the key or two crossbows in bot stock or the Arrav pool, or a Phoenix bot at stage 60+ on the quest to lend it. */
    static boolean blackArmSupplied(Player me) {
        if (!Boolean.getBoolean("quests.noStock") && (MarketBrain.stockOf(KEY) >= 1 || MarketBrain.stockOf(CROSSBOW) >= 2)) return true;
        if (Pool.count(KEY) >= 1 || Pool.count(CROSSBOW) >= 2) return true;
        for (QuestRunner r : QuestRunner.ACTIVE.values()) {
            if (r == null || r.over || r.bot == me || r.script == null || !SLUG.equals(r.script.slug) || !r.bot.isActive()) continue;
            try { if (phoenix(r.bot) && r.stage() >= 60) return true; } catch (Throwable ignored) { }
        }
        return false;
    }

    /** QuestBook.eligible's gate for a NEW start (resumes don't come through here): enough coins for Baraek, and a Black Arm bot only when the store can be robbed. */
    static boolean mayStart(Player p) { return whyNot(p) == null; }
    static String whyNot(Player p) {
        int stage = 0; try { stage = p.getQuestRepository().getStage(Q); } catch (Throwable ignored) { }
        if (stage < 40 && Ledger.Wallet.coins(p) < START_COINS) return "wallet " + Ledger.Wallet.coins(p) + " < " + START_COINS + " gp (Baraek's 20)"; // batch 17 item2: live "short of Coins" abandons at step 7
        if (stage >= 70 || phoenix(p)) return null;
        if (any(p, KEY) || count(p, CROSSBOW) >= 2) return null; // brings its own
        return blackArmSupplied(p) ? null : "Black Arm side with no key 759 / crossbows 767 in stock or the pool and no Phoenix bot at stage 60+"; // batch 17 item1 (audit Q-23): was a 1.5-2.5 h stall
    }

    /** Eat at 60% (as the fight runner's bots do). */
    static void eatIfLow(Player p) {
        int hp = p.getSkills().getLifepoints(), max = p.getSkills().getMaximumLifepoints();
        if (hp > max * 3 / 5) return;
        for (Item it : p.getInventory().toArray()) {
            if (it == null) continue;
            boolean food = false; for (int f : AfkTasks.Runner.FOOD) if (f == it.getId()) food = true;
            if (!food) continue;
            try { new core.game.bots.ScriptAPI(p).forceEat(it.getId()); } catch (Throwable ignored) { }
            return;
        }
    }

    /** Fight the Weaponsmaster when he stands in the way, then take the two crossbows off the floor while he is down. */
    static QuestSteps.Step steal(String name, java.util.function.Predicate<Player> done) {
        final int[] st = {0, 0, 0, 0}; // wait, takes, fights, ticks in this fight
        return new QuestSteps.Custom(name, 2000, r -> { st[0] = 0; st[1] = 0; st[2] = 0; st[3] = -1; }, r -> {
            Player p = r.bot;
            if (p.getInventory().getAmount(CROSSBOW) >= 2) return QuestSteps.St.DONE;
            if (p.getLocation().getZ() != 1) return QuestSteps.St.FAILED;
            NPC master = QuestSteps.findNpc(p, MASTER_AT, 12, new int[]{WEAPONSMASTER}, null);
            boolean guarding = !phoenix(p) && master != null && !master.isInvisible() && master.getSkills().getLifepoints() > 0; // he lets a Phoenix member help himself
            if (guarding) {
                if (st[3] < 0) { // a new fight
                    int cb = 3; try { cb = p.getProperties().getCurrentCombatLevel(); } catch (Throwable ignored) { }
                    if (cb < 30) { r.log("  combat " + cb + " is too low to rob the Weaponsmaster (15/15/15, 42 hp): leaving it for a Phoenix partner or bot stock"); return QuestSteps.St.FAILED; } // audit 10-23 Q-21: the only gate was Hitpoints 10
                    if (++st[2] > 4) { r.log("  four fights and the Weaponsmaster still stands guard"); return QuestSteps.St.FAILED; }
                    QuestSteps.clearPopups(p);
                    QuestRunner.makeRoom(p, 5);
                    QuestRunner.packFood(p, 5);
                    try { p.getProperties().setRetaliating(true); } catch (Throwable ignored) { }
                    r.log("  the Weaponsmaster guards the crossbows: fight " + st[2]);
                    st[3] = 0;
                }
                eatIfLow(p);
                if (st[3]++ > 400) { r.log("  the fight went on 400 ticks"); st[3] = -1; return QuestSteps.St.RUNNING; }
                if (st[3] % 6 == 1 && !p.inCombat()) { QuestSteps.clearPopups(p); QuestSteps.click(p, master, "Attack", IntType.NPC); }
                return QuestSteps.St.RUNNING;
            }
            if (st[3] >= 0) { r.log("  the Weaponsmaster is down after " + st[3] + " ticks"); st[3] = -1; }
            if (st[0] > 0) { st[0]--; return QuestSteps.St.RUNNING; }
            GroundItem gi = null;
            for (Location at : CROSSBOW_AT) {
                if (gi != null) break;
                try { GroundItem g = GroundItemManager.get(CROSSBOW, at, p); if (g != null && g.isActive()) gi = g; } catch (Throwable ignored) { }
            }
            if (gi == null) { st[0] = 5; return QuestSteps.St.RUNNING; } // both taken: they respawn (ground_spawns.json, 50 ticks)
            if (++st[1] > 30) { r.log("  30 tries at the crossbows"); return QuestSteps.St.FAILED; }
            QuestRunner.makeRoom(p, 1);
            QuestSteps.clearPopups(p);
            if (!QuestSteps.click(p, gi, "Take", IntType.GROUNDITEM)) { r.log("  no Take on the crossbow"); return QuestSteps.St.FAILED; }
            st[0] = 4;
            return QuestSteps.St.RUNNING;
        }).done(done);
    }

    // ------------------------------------------------------------------ the bots' stock of half-certificates

    /**
     * Bot stock for the fallback. The halves 11173/11174 are not in the GE price index (canTrade is false, so
     * GrandExchange.addBotOffer refuses them and Ledger.supply would lose the item while booking a lot), so for an item the
     * GE won't hold the stock is kept here, in data/quests/arrav-pool.json; items the GE takes (the key 759, the crossbows
     * 767) go through Ledger.supply / Ledger.consume as all bot stock does. The item leaves the pack before the count goes
     * up, and the count goes down before the item goes into a pack (put back if the pack refuses it): never one in both.
     */
    static final class Pool {
        static final Map<Integer, Integer> N = new HashMap<>();
        static boolean loaded;

        static File file() { return new File(QuestBook.dir(), "arrav-pool.json"); }

        static boolean geTakes(int id) { try { return core.game.ge.PriceIndex.canTrade(id); } catch (Throwable t) { return false; } }

        static synchronized void load() {
            if (loaded) return;
            loaded = true;
            File f = file();
            if (!f.exists()) return;
            try (FileReader rd = new FileReader(f)) {
                JSONObject j = (JSONObject) new JSONParser().parse(rd);
                for (Object k : j.keySet()) N.put(Integer.parseInt(String.valueOf(k)), ((Number) j.get(k)).intValue());
            } catch (Throwable t) { System.out.println("[Quests] arrav pool: " + t); Ledger.quarantine(f, "Quests", t, true, false); } // audit 10-23 batch14 item1: moved aside, not blocked (real items keep going in and out; a blocked pool would lose this boot's deposits)
        }

        @SuppressWarnings("unchecked")
        static synchronized void save() {
            try {
                JSONObject j = new JSONObject();
                for (Map.Entry<Integer, Integer> e : N.entrySet()) j.put(String.valueOf(e.getKey()), e.getValue());
                file().getParentFile().mkdirs();
                Ledger.writeAtomic(file(), j.toJSONString());
            } catch (Throwable t) { System.out.println("[Quests] arrav pool save: " + t); }
        }

        static synchronized int count(int id) { load(); return N.getOrDefault(id, 0); }

        /** One of this item from the bot's pack (null bot: the world, trials) into bot stock. */
        static synchronized boolean deposit(Player p, int id) {
            if (p != null && !p.getInventory().remove(new Item(id, 1))) return false;
            if (geTakes(id)) Ledger.supply(p, id, 1);
            else { load(); N.merge(id, 1, Integer::sum); save(); }
            System.out.println("[Quests] " + (p != null ? p.getUsername() : "world") + " " + SLUG + ": put 1 x " + MarketBrain.name(id) + " (" + id + ") into bot stock" + (geTakes(id) ? "" : " (the Arrav pool, now " + N.getOrDefault(id, 0) + ")"));
            return true;
        }

        /** One of this item out of bot stock into the bot's pack, if there is one. */
        static synchronized boolean take(Player p, int id) {
            QuestRunner.makeRoom(p, 1);
            if (p.getInventory().freeSlots() < 1) return false;
            if (geTakes(id)) {
                if (Boolean.getBoolean("quests.noStock") || MarketBrain.stockOf(id) <= 0) return false;
                int got = Ledger.consume(p, id, 1, "quest");
                if (got <= 0) return false;
                p.getInventory().add(new Item(id, got));
            } else {
                load();
                int n = N.getOrDefault(id, 0);
                if (n <= 0) return false;
                N.put(id, n - 1); save();
                if (!p.getInventory().add(new Item(id, 1))) { N.merge(id, 1, Integer::sum); save(); return false; }
            }
            System.out.println("[Quests] " + p.getUsername() + " " + SLUG + ": took 1 x " + MarketBrain.name(id) + " (" + id + ") from bot stock");
            return true;
        }
    }

    // ------------------------------------------------------------------ the partner

    static final long WAIT_MS = Math.max(1, Long.getLong("quests.soaWaitMin", 30)) * 60_000L;
    static final Location MEET_AT = CURATOR_AT;

    /** A bot waiting at the museum: what it brings and what it needs. partner/walker/got are only touched under the WAITING lock. */
    static final class Waiter {
        final String key; final boolean phoenix, wantsKey;
        String partner; boolean walker; int kind; // kind 1: the Phoenix lends the key; kind 2: one half for one half
        int got;                                   // set by the bot that made the exchange: the item this bot received (-1: gave the key)
        Waiter(String key, boolean phoenix, boolean wantsKey) { this.key = key; this.phoenix = phoenix; this.wantsKey = wantsKey; }
    }

    static final Map<String, Waiter> WAITING = new HashMap<>();
    /** When a bot first started waiting (so a retry of the step doesn't start the clock again). */
    static final Map<String, Long> SINCE = new ConcurrentHashMap<>();
    /** Phoenix bot -> the Black Arm bot it lent its key to: it waits for that one to come back with its halves. */
    static final Map<String, String> LENT = new ConcurrentHashMap<>();

    static Player questing(String key) {
        QuestRunner r = key == null ? null : QuestRunner.ACTIVE.get(key);
        if (r == null || r.over || r.script == null || !SLUG.equals(r.script.slug) || !r.bot.isActive()) return null;
        return r.bot;
    }

    /** Another bot of the other gang is still on the quest (so waiting a while longer is worth it). */
    static boolean otherGangQuesting(Player me, boolean phoenix) {
        for (QuestRunner r : QuestRunner.ACTIVE.values()) {
            if (r == null || r.over || r.bot == me || r.script == null || !SLUG.equals(r.script.slug) || !r.bot.isActive()) continue;
            try { if (phoenix(r.bot) != phoenix && r.stage() < 100) return true; } catch (Throwable ignored) { }
        }
        return false;
    }

    static int compatible(Waiter a, Player pa, Waiter b, Player pb) {
        if (a.phoenix == b.phoenix) return 0;
        Waiter ph = a.phoenix ? a : b, bl = a.phoenix ? b : a; Player pph = a.phoenix ? pa : pb, pbl = a.phoenix ? pb : pa;
        if (bl.wantsKey) return (count(pph, CROSSBOW) >= 2 || any(pph, KEY)) && !any(pbl, KEY) ? 1 : 0;
        return count(pph, PHOENIX_HALF) >= 2 && count(pbl, BLACKARM_HALF) >= 2 ? 2 : 0;
    }

    /** Claim a partner, both sides at once, under the lock. */
    static void tryPair(Waiter me, Player bot) {
        synchronized (WAITING) {
            if (me.partner != null) return;
            String lent = LENT.get(me.key);
            Waiter best = null; int bestKind = 0;
            for (Waiter w : WAITING.values()) {
                if (w == me || w.partner != null) continue;
                Player o = questing(w.key);
                if (o == null || o.getLocation().getZ() != 0 || o.getLocation().getDistance(MEET_AT) > 15) continue;
                int k = compatible(me, bot, w, o);
                if (k == 0) continue;
                if (best == null || w.key.equals(lent)) { best = w; bestKind = k; }
            }
            if (best == null) return;
            me.partner = best.key; best.partner = me.key; me.walker = true; best.walker = false; me.kind = best.kind = bestKind; me.got = best.got = 0;
        }
    }

    static void unpair(Waiter me) {
        synchronized (WAITING) {
            Waiter o = me.partner == null ? null : WAITING.get(me.partner);
            if (o != null && me.key.equals(o.partner)) { o.partner = null; o.walker = false; o.kind = 0; }
            me.partner = null; me.walker = false; me.kind = 0;
        }
    }

    /**
     * Wait at the museum for a partner of the other gang and swap with it. keyMode: a Black Arm bot at stage 60 that needs the
     * Phoenix key. Otherwise a bot with its two half-certificates that needs one of the other gang's.
     */
    static final class Meet extends QuestSteps.Step {
        final boolean keyMode;
        final QuestSteps.Mover m = new QuestSteps.Mover();
        Waiter me; int phase, wait, tradeTicks, says; boolean deposited; long poolSince; String lastPartner;
        Meet(String name, boolean keyMode) { super(name, (int) (5 * WAIT_MS / 600) + 1200); this.keyMode = keyMode; }

        @Override void begin(QuestRunner r) {
            super.begin(r);
            phase = 0; wait = 0; tradeTicks = 0; says = 0; deposited = false; poolSince = 0; lastPartner = null;
            m.reset(MEET_AT, 3);
        }

        boolean satisfied(Player p) { return keyMode ? any(p, KEY) || bowsOrLater(p) : bothHalvesOrLater(p); }

        @Override QuestSteps.St tick(QuestRunner r) {
            Player p = r.bot;
            if (satisfied(p)) { if (keyMode) toPack(p, KEY, 1); return QuestSteps.St.DONE; }
            if (phase == 0) { // to the museum
                QuestSteps.St s = m.tick(p);
                if (s == QuestSteps.St.FAILED) { if (p.getLocation().getZ() == 0 && p.getLocation().getDistance(MEET_AT) <= 8) s = QuestSteps.St.DONE; else return fail("walk to the museum: " + m.last); }
                if (s == QuestSteps.St.RUNNING) return s;
                if (!keyMode) toPack(p, ownHalf(p), 2);
                me = new Waiter(QuestRunner.key(p), phoenix(p), keyMode);
                synchronized (WAITING) { WAITING.put(me.key, me); }
                SINCE.putIfAbsent(me.key, System.currentTimeMillis());
                long since = SINCE.get(me.key);
                if (System.currentTimeMillis() - since > 4 * WAIT_MS) { since = System.currentTimeMillis(); SINCE.put(me.key, since); } // a stale clock from an earlier day's try
                r.log("  waiting at the museum for a " + gangName(!me.phoenix) + " partner (" + (keyMode ? "need the weapon store key" : "have " + count(p, ownHalf(p)) + " x " + MarketBrain.name(ownHalf(p)) + ", need 1 x " + otherHalf(p)) + "), up to " + WAIT_MS / 60_000 + " min");
                phase = 1;
                return QuestSteps.St.RUNNING;
            }
            if (phase == 1) return waitTick(r, p);
            return poolTick(r, p);
        }

        QuestSteps.St waitTick(QuestRunner r, Player p) {
            // the exchange was made by the partner
            int got; String partnerKey; boolean walker; int kind;
            synchronized (WAITING) { got = me.got; me.got = 0; partnerKey = me.partner; walker = me.walker; kind = me.kind; }
            if (got != 0) {
                if (got == -1) { unpair(me); r.log("  helped " + LENT.get(me.key) + " with the weapon store; waiting on for the halves"); return QuestSteps.St.RUNNING; }
                unpair(me);
                return satisfied(p) ? QuestSteps.St.DONE : QuestSteps.St.RUNNING;
            }
            if (partnerKey != null && !partnerKey.equals(lastPartner)) { lastPartner = partnerKey; tradeTicks = 0; }
            if (partnerKey != null) {
                Player o = questing(partnerKey);
                Waiter ow; synchronized (WAITING) { ow = WAITING.get(partnerKey); }
                if (o == null || ow == null || !me.key.equals(ow.partner)) { r.log("  the partner " + partnerKey + " left: waiting on"); unpair(me); return QuestSteps.St.RUNNING; }
                try { p.faceLocation(o.getLocation()); } catch (Throwable ignored) { }
                if (++tradeTicks > 200) { r.log("  couldn't get next to " + o.getUsername() + ": waiting on"); unpair(me); tradeTicks = 0; return QuestSteps.St.RUNNING; }
                if (!walker) return QuestSteps.St.RUNNING; // the partner comes over and makes the trade
                double d = p.getLocation().getZ() == o.getLocation().getZ() ? p.getLocation().getDistance(o.getLocation()) : 99;
                if (d > 2.9) {
                    if (tradeTicks == 1) BotChat.say(p, kind == 1 ? BotChat.pick("You Phoenix? I need into your weapon store.", "Heard you've a key to the Phoenix stash.") : BotChat.pick("Got the other half? Swap you.", gangName(me.phoenix) + " half for yours?"));
                    if (tradeTicks % 4 == 1 && !p.getWalkingQueue().isMoving()) { // a free tile next to the partner (its own tile can't be stood on)
                        Location ol = o.getLocation(), next = null;
                        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
                        int k = (tradeTicks / 4) % around.length;
                        next = ol.transform(around[k][0], around[k][1], 0);
                        try { if (p instanceof core.game.bots.AIPlayer) ((core.game.bots.AIPlayer) p).walkToPosSmart(next); } catch (Throwable ignored) { }
                    }
                    return QuestSteps.St.RUNNING;
                }
                if (tradeTicks < 6) return QuestSteps.St.RUNNING; // stand together a moment
                return exchange(r, p, o, partnerKey, kind);
            }
            if (++wait % 5 == 0) tryPair(me, p);
            long since = SINCE.getOrDefault(me.key, System.currentTimeMillis());
            long waited = System.currentTimeMillis() - since;
            boolean longer = waited < 2 * WAIT_MS && (otherGangQuesting(p, me.phoenix) || questing(LENT.get(me.key)) != null);
            if (waited > WAIT_MS && !longer) {
                r.log("  no " + gangName(!me.phoenix) + " partner in " + waited / 60_000 + " min: bot stock instead");
                synchronized (WAITING) { WAITING.remove(me.key, me); }
                phase = 2; poolSince = System.currentTimeMillis(); wait = 0;
            }
            return QuestSteps.St.RUNNING;
        }

        /** This bot makes the exchange (the partner stands still). */
        QuestSteps.St exchange(QuestRunner r, Player p, Player o, String partnerKey, int kind) {
            boolean iAmPhoenix = me.phoenix;
            Player ph = iAmPhoenix ? p : o, bl = iAmPhoenix ? o : p;
            boolean ok;
            if (kind == 1) {
                toPack(ph, CROSSBOW, 2);
                if (ph.getInventory().getAmount(CROSSBOW) >= 2) { // the crossbows themselves, one at a time across
                    ok = BotSwap.swap(ph, CROSSBOW, bl, 0, "a Phoenix crossbow for Katrine");
                    if (ok) BotSwap.swap(ph, CROSSBOW, bl, 0, "the second Phoenix crossbow");
                    if (ok) BotChat.say(ph, BotChat.pick("Two crossbows, fresh from the stash. Bring the halves back here after.", "Here. Katrine'll love these. Don't tell Straven."));
                } else {
                    toPack(ph, KEY, 1);
                    ok = BotSwap.swap(ph, KEY, bl, 0, "the Phoenix weapon store key, lent");
                    if (ok) BotChat.say(ph, BotChat.pick("Here. Bring the halves back here after.", "Take it. Don't let Straven see."));
                }
            } else {
                toPack(ph, PHOENIX_HALF, 2); toPack(bl, BLACKARM_HALF, 2);
                ok = ph.getInventory().getAmount(PHOENIX_HALF) >= 2 && bl.getInventory().getAmount(BLACKARM_HALF) >= 2 && BotSwap.swap(ph, PHOENIX_HALF, bl, BLACKARM_HALF, "Shield of Arrav half-certificates");
                if (ok) BotChat.say(o, BotChat.pick("Pleasure doing business.", "Done. See you at the king's."));
            }
            synchronized (WAITING) {
                Waiter ow = WAITING.get(partnerKey);
                if (ok && ow != null && me.key.equals(ow.partner)) ow.got = kind == 1 ? (iAmPhoenix ? KEY : -1) : (iAmPhoenix ? PHOENIX_HALF : BLACKARM_HALF);
            }
            if (!ok) { r.log("  the swap with " + o.getUsername() + " didn't go through: waiting on"); unpair(me); tradeTicks = 0; return QuestSteps.St.RUNNING; }
            if (kind == 1) LENT.put(QuestRunner.key(ph), QuestRunner.key(bl));
            if (kind == 1 && iAmPhoenix) { unpair(me); tradeTicks = 0; r.log("  handed " + o.getUsername() + " the means into the weapon store; waiting on for the halves"); return QuestSteps.St.RUNNING; }
            if (kind == 2) SINCE.remove(me.key);
            unpair(me);
            return satisfied(p) ? QuestSteps.St.DONE : QuestSteps.St.RUNNING;
        }

        /** No partner: the spare into bot stock, the other one out of it (polled; parks the quest if none comes). */
        QuestSteps.St poolTick(QuestRunner r, Player p) {
            if (++wait % 10 != 1) return QuestSteps.St.RUNNING;
            if (keyMode) {
                bowsFromStock(r);
                if (bowsOrLater(p) || any(p, KEY) || Pool.take(p, KEY)) { SINCE.remove(QuestRunner.key(p)); return QuestSteps.St.DONE; }
            } else {
                if (!deposited) {
                    deposited = true;
                    toPack(p, ownHalf(p), 2);
                    if (p.getInventory().getAmount(ownHalf(p)) >= 2) Pool.deposit(p, ownHalf(p));
                    if (me.phoenix && any(p, KEY)) { toPack(p, KEY, 1); Pool.deposit(p, KEY); } // for a Black Arm bot without a partner
                }
                if (has(otherHalf(p), 1).test(p) || Pool.take(p, otherHalf(p))) { SINCE.remove(QuestRunner.key(p)); return QuestSteps.St.DONE; }
            }
            if (System.currentTimeMillis() - poolSince > WAIT_MS) return fail("nothing in bot stock after " + WAIT_MS / 60_000 + " min more");
            return QuestSteps.St.RUNNING;
        }

        @Override void end(QuestRunner r) {
            if (me != null) { unpair(me); synchronized (WAITING) { WAITING.remove(me.key, me); } }
            if (r != null && !r.bot.isActive()) SINCE.remove(QuestRunner.key(r.bot));
            if (r != null && r.over) LENT.remove(QuestRunner.key(r.bot));
            me = null;
        }

        @Override String doing() { return keyMode ? "waiting at the museum for a Phoenix friend" : "waiting at the museum for a partner of the other gang"; }
        @Override String trace() { return "phase " + phase + (me != null ? ", partner " + me.partner : "") + ", walk " + m.last; }
    }

    static final Location HOUSE_ALLEY = Location.create(3197, 3384, 0), HOUSE_HALL = Location.create(3191, 3384, 0); // east of the doors 24381 at 3196,3384 and 3190,3384 (west edges)

    /** Down the alley and through the two doors to Katrine's room (from the south a straight walk ends outside the wall at 3188,3381). */
    static void intoHouse(QuestScript s, String again, int stage) {
        s.step(ba(new QuestSteps.Walk("into the Black Arm alley" + again, HOUSE_ALLEY, 1).done(p -> stageAtLeast(Q, stage).test(p) || p.getLocation().getZ() == 0 && (p.getLocation().getDistance(HOUSE_ALLEY) <= 1 || p.getLocation().getX() <= 3195 && p.getLocation().getX() >= 3180 && p.getLocation().getY() >= 3383 && p.getLocation().getY() <= 3387))));
        s.step(ba(new QuestSteps.Walk("through the Black Arm house door" + again, HOUSE_HALL, 1).done(p -> stageAtLeast(Q, stage).test(p) || p.getLocation().getZ() == 0 && (p.getLocation().getDistance(HOUSE_HALL) <= 1 || p.getLocation().getX() <= 3189 && p.getLocation().getX() >= 3180 && p.getLocation().getY() >= 3382 && p.getLocation().getY() <= 3387))));
    }

    /**
     * quest fix 10-23: back into Katrine's room for the Black Arm door. The walk to Katrine and the talk are skipped at stage 70,
     * so a bot that comes back to the quest there (it gave up or stopped after joining) clicked the door 2399 from wherever it
     * was: live "through the Black Arm door: Open on [2399] didn't do it (5 clicks)". The same alley and hall as intoHouse.
     */
    static void backToDoor(QuestScript s) {
        java.util.function.Predicate<Player> skip = p -> !stageAtLeast(Q, 70).test(p) || blackShieldOrLater(p) || behindBlackArmDoor(p) || upHouse(p);
        s.step(ba(new QuestSteps.Walk("back into the Black Arm alley for the door", HOUSE_ALLEY, 1).done(p -> skip.test(p) || p.getLocation().getZ() == 0 && (p.getLocation().getDistance(HOUSE_ALLEY) <= 1 || p.getLocation().getX() <= 3195 && p.getLocation().getX() >= 3180 && p.getLocation().getY() >= 3382 && p.getLocation().getY() <= 3387))));
        s.step(ba(new QuestSteps.Walk("back into Katrine's room", HOUSE_HALL, 1).done(p -> skip.test(p) || p.getLocation().getZ() == 0 && (p.getLocation().getDistance(HOUSE_HALL) <= 1 || p.getLocation().getX() <= 3189 && p.getLocation().getX() >= 3180 && p.getLocation().getY() >= 3382 && p.getLocation().getY() <= 3387))));
    }

    static QuestScript build() {
        QuestScript s = QuestScript.of(Q, SLUG, "Shield of Arrav").startAt(3210, 3494, 0).minutes(50).coins(600).xp(0).unlock(20)
                .level(core.game.node.entity.skill.Skills.HITPOINTS, 10);

        // ================= the Phoenix side: Reldo, the book, Baraek, Straven, Jonny, the chest
        s.step(ph(new QuestSteps.Custom("check The Knight's Sword isn't at Reldo's stage", 2, null, r -> { r.log("  The Knight's Sword is at stage 10: Reldo only talks about that"); return QuestSteps.St.FAILED; })
                .done(p -> !knightsSwordBusy(p) || stageAtLeast(Q, 30).test(p))));

        // Reldo, the book, Reldo again
        s.step(ph(new QuestSteps.Walk("walk to Reldo in the palace library", RELDO_AT, 3).done(p -> stageAtLeast(Q, 10).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(RELDO_AT) <= 3)));
        s.step(ph(longTalk(new QuestSteps.Talk("ask Reldo for a quest", new int[]{RELDO}, 1, 1)).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10))));
        s.step(ph(new QuestSteps.Collect("get the Shield of Arrav book", BOOK, 1)
                .then(room("make room for the book", 1))
                .then(new QuestSteps.Interact("search the bookcase", new int[]{BOOKCASE}, null, "Search", BOOKCASE_AT, 0, false).picks().until(has(BOOK, 1)))
                .done(p -> has(BOOK, 1).test(p) || stageAtLeast(Q, 20).test(p))));
        s.step(ph(readBook().done(stageAtLeast(Q, 20))));
        s.step(ph(new QuestSteps.Walk("back to Reldo", RELDO_AT, 3).done(p -> stageAtLeast(Q, 30).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(RELDO_AT) <= 3)));
        s.step(ph(longTalk(new QuestSteps.Talk("tell Reldo I read the book", new int[]{RELDO}, 1)).until(stageAtLeast(Q, 30)).done(stageAtLeast(Q, 30))));

        // Baraek's 20 coins
        s.step(ph(new QuestSteps.Collect("put 20 coins in the pack for Baraek", COINS, 20).done(p -> has(COINS, 20).test(p) || stageAtLeast(Q, 40).test(p))));
        s.step(ph(new QuestSteps.Walk("walk to Baraek in Varrock square", BARAEK_AT, 3).done(p -> stageAtLeast(Q, 40).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(BARAEK_AT) <= 3)));
        s.step(ph(longTalk(new QuestSteps.Talk("bribe Baraek for the Phoenix hideout", new int[]{BARAEK}, 1, 1)).until(stageAtLeast(Q, 40)).done(stageAtLeast(Q, 40))));

        // Straven: the mission
        s.step(ph(new QuestSteps.Walk("walk to the hideout alley", ALLEY, 2).done(p -> under(p) || stageAtLeast(Q, 60).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(ALLEY) <= 2)));
        s.step(ph(new QuestSteps.Interact("down the ladder into the hideout", new int[]{LADDER_DOWN}, null, "Climb-down", LADDER_DOWN_AT, 0, false).until(QuestShieldOfArrav::under)
                .done(p -> under(p) || stageAtLeast(Q, 60).test(p))));
        s.step(ph(longTalk(new QuestSteps.Talk("offer Straven my services", new int[]{STRAVEN}, 1, 1)).until(stageAtLeast(Q, 60)).done(stageAtLeast(Q, 60))));
        s.step(ph(new QuestSteps.Walk("back to the hideout ladder", LADDER_UP_AT, 2).done(p -> !under(p) || stageAtLeast(Q, 70).test(p) || p.getLocation().getDistance(LADDER_UP_AT) <= 2)));
        s.step(ph(new QuestSteps.Interact("up the ladder out of the hideout", new int[]{LADDER_UP}, null, "Climb-up", LADDER_UP_AT, 0, false).until(p -> !under(p))
                .done(p -> !under(p) || stageAtLeast(Q, 70).test(p))));

        // Jonny the Beard's report
        s.step(ph(new QuestSteps.Collect("get the intelligence report", REPORT, 1)
                .then(new QuestSteps.Kill("kill Jonny the Beard", new String[]{"Jonny the beard"}, new int[]{REPORT}, 1, JONNY_AT).until(has(REPORT, 1)))
                .done(p -> has(REPORT, 1).test(p) || stageAtLeast(Q, 70).test(p))));

        // Straven: the key, then the chest
        s.step(ph(new QuestSteps.Walk("back to the hideout alley", ALLEY, 2).done(p -> under(p) || shieldOrLater(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(ALLEY) <= 2)));
        s.step(ph(new QuestSteps.Interact("down into the hideout again", new int[]{LADDER_DOWN}, null, "Climb-down", LADDER_DOWN_AT, 0, false).until(QuestShieldOfArrav::under)
                .done(p -> under(p) || shieldOrLater(p))));
        s.step(ph(room("make room for the key", 1).done(stageAtLeast(Q, 70))));
        s.step(ph(longTalk(new QuestSteps.Talk("give Straven the report", new int[]{STRAVEN})).until(stageAtLeast(Q, 70)).done(stageAtLeast(Q, 70))));
        s.step(ph(new QuestSteps.Interact("through the Phoenix door", new int[]{PHOENIX_DOOR}, null, "Open", DOOR_AT, 0, false).until(QuestShieldOfArrav::southOfDoor)
                .done(p -> southOfDoor(p) || shieldOrLater(p))));
        s.step(ph(new QuestSteps.Walk("walk to the gang's chest", CHEST_SIDE, 2).done(p -> shieldOrLater(p) || under(p) && p.getLocation().getDistance(CHEST_SIDE) <= 2)));
        s.step(ph(new QuestSteps.Interact("open the chest", new int[]{CHEST_SHUT}, null, "Open", CHEST_AT, 0, false).until(QuestShieldOfArrav::chestOpen)
                .done(p -> chestOpen(p) || shieldOrLater(p))));
        s.step(ph(room("make room for the shield", 1).done(QuestShieldOfArrav::shieldOrLater)));
        s.step(ph(new QuestSteps.Interact("search the chest for the shield", new int[]{CHEST_OPEN}, null, "Search", CHEST_AT, 0, false).until(has(PHOENIX_SHIELD, 1))
                .done(QuestShieldOfArrav::shieldOrLater)));
        s.step(ph(new QuestSteps.Walk("back to the Phoenix door", DOOR_SOUTH, 1).done(p -> !under(p) || !southOfDoor(p) || p.getLocation().getDistance(DOOR_SOUTH) <= 1)));
        s.step(ph(new QuestSteps.Interact("back through the Phoenix door", new int[]{PHOENIX_DOOR}, null, "Open", DOOR_AT, 0, false).until(p -> under(p) && !southOfDoor(p))
                .done(p -> !under(p) || !southOfDoor(p))));
        s.step(ph(new QuestSteps.Walk("to the hideout ladder", LADDER_UP_AT, 2).done(p -> !under(p) || p.getLocation().getDistance(LADDER_UP_AT) <= 2)));
        s.step(ph(new QuestSteps.Interact("up out of the hideout", new int[]{LADDER_UP}, null, "Climb-up", LADDER_UP_AT, 0, false).until(p -> !under(p)).done(p -> !under(p))));
        s.step(ph(new QuestSteps.Collect("have the half shield in the pack", PHOENIX_SHIELD, 1).done(QuestShieldOfArrav::certsOrLater)));

        // two crossbows from the gang's weapon store for a Black Arm friend (the journal's "a friend's help"): free for a member
        s.step(ph(new QuestSteps.Walk("walk to the gang's weapon store", STORE_OUTSIDE, 2).done(p -> spareBows(p) || inStore(p) || upStore(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(STORE_OUTSIDE) <= 2)));
        s.step(ph(new QuestSteps.Interact("unlock the weapon store", new int[]{STORE_DOOR}, null, "Open", STORE_DOOR_AT, 0, false).until(p -> inStore(p) || upStore(p))
                .done(p -> spareBows(p) || inStore(p) || upStore(p))));
        s.step(ph(new QuestSteps.Interact("up to the crossbows", new int[]{STORE_LADDER_UP}, null, "Climb-up", STORE_LADDER_AT, 0, false).until(QuestShieldOfArrav::upStore)
                .done(p -> spareBows(p) || upStore(p))));
        s.step(ph(steal("take two crossbows for a Black Arm friend", QuestShieldOfArrav::spareBows)));
        s.step(ph(new QuestSteps.Interact("down from the crossbows", new int[]{STORE_LADDER_DOWN}, null, "Climb-down", STORE_LADDER_TOP, 0, false).until(p -> !upStore(p))
                .done(p -> !upStore(p))));
        s.step(ph(new QuestSteps.Interact("out of the gang's weapon store", new int[]{STORE_DOOR}, null, "Open", STORE_DOOR_AT, 0, false).until(p -> !inStore(p))
                .done(p -> !inStore(p))));

        // ================= the Black Arm side: Charlie, Katrine, the Phoenix crossbows, the cupboard
        s.step(ba(new QuestSteps.Walk("walk to Charlie the Tramp", CHARLIE_AT, 4).done(p -> stageAtLeast(Q, 50).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(CHARLIE_AT) <= 4)));
        s.step(ba(longTalk(new QuestSteps.Talk("ask Charlie about the alley", new int[]{CHARLIE}, 5, 2)).until(stageAtLeast(Q, 50)).done(stageAtLeast(Q, 50))));
        intoHouse(s, "", 60);
        s.step(ba(new QuestSteps.Walk("walk to Katrine", KATRINE_AT, 3).done(p -> stageAtLeast(Q, 60).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(KATRINE_AT) <= 3)));
        s.step(ba(longTalk(new QuestSteps.Talk("ask Katrine to join the Black Arm Gang", new int[]{KATRINE}, 1, 1, 1, 1, 1)).until(p -> stageAtLeast(Q, 60).test(p) && attr(p, "blackarm-mission"))
                .done(stageAtLeast(Q, 60))));
        s.step(ba(new QuestSteps.Custom("look for two Phoenix crossbows in bot stock", 5, null, QuestShieldOfArrav::bowsFromStock).done(QuestShieldOfArrav::bowsOrLater)));
        s.step(ba(new QuestSteps.Collect("get the Phoenix weapon store key", KEY, 1).stock().limit(20).optional().done(p -> any(p, KEY) || bowsOrLater(p))));
        s.step(ba(new Meet("get the weapon store key from a Phoenix friend", true).done(p -> any(p, KEY) || bowsOrLater(p))));
        s.step(ba(new QuestSteps.Collect("have the key in the pack", KEY, 1).done(p -> has(KEY, 1).test(p) || bowsOrLater(p))));
        s.step(ba(new QuestSteps.Walk("walk to the Phoenix weapon store", STORE_OUTSIDE, 2).done(p -> bowsOrLater(p) || inStore(p) || upStore(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(STORE_OUTSIDE) <= 2)));
        s.step(ba(new QuestSteps.Interact("unlock the weapon store door", new int[]{STORE_DOOR}, null, "Open", STORE_DOOR_AT, 0, false).until(p -> inStore(p) || upStore(p))
                .done(p -> bowsOrLater(p) || inStore(p) || upStore(p))));
        s.step(ba(new QuestSteps.Interact("up the weapon store ladder", new int[]{STORE_LADDER_UP}, null, "Climb-up", STORE_LADDER_AT, 0, false).until(QuestShieldOfArrav::upStore)
                .done(p -> bowsOrLater(p) || upStore(p))));
        s.step(ba(steal("steal two Phoenix crossbows", QuestShieldOfArrav::bowsOrLater)));
        s.step(ba(new QuestSteps.Interact("down the weapon store ladder", new int[]{STORE_LADDER_DOWN}, null, "Climb-down", STORE_LADDER_TOP, 0, false).until(p -> !upStore(p))
                .done(p -> !upStore(p))));
        s.step(ba(new QuestSteps.Interact("out of the weapon store", new int[]{STORE_DOOR}, null, "Open", STORE_DOOR_AT, 0, false).until(p -> !inStore(p))
                .done(p -> !inStore(p))));
        s.step(ba(new QuestSteps.Collect("have the two crossbows in the pack", CROSSBOW, 2).done(QuestShieldOfArrav::bowsOrLater)));
        intoHouse(s, " again", 70);
        s.step(ba(new QuestSteps.Walk("back to Katrine", KATRINE_AT, 3).done(p -> stageAtLeast(Q, 70).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(KATRINE_AT) <= 3)));
        s.step(ba(longTalk(new QuestSteps.Talk("give Katrine the crossbows", new int[]{KATRINE})).until(stageAtLeast(Q, 70)).done(stageAtLeast(Q, 70))));
        backToDoor(s); // quest fix 10-23
        s.step(ba(new QuestSteps.Interact("through the Black Arm door", new int[]{BLACKARM_DOOR}, null, "Open", BLACKARM_DOOR_AT, 0, false).until(p -> behindBlackArmDoor(p) || upHouse(p))
                .done(p -> blackShieldOrLater(p) || behindBlackArmDoor(p) || upHouse(p))));
        s.step(ba(new QuestSteps.Interact("up the Black Arm stairs", new int[]{BLACKARM_STAIRS_UP}, null, "Climb-up", BLACKARM_STAIRS_AT, 0, false).until(QuestShieldOfArrav::upHouse)
                .done(p -> blackShieldOrLater(p) || upHouse(p))));
        s.step(ba(new QuestSteps.Interact("open the cupboard", new int[]{CUPBOARD_SHUT}, null, "Open", CUPBOARD_AT, 0, false).until(QuestShieldOfArrav::cupboardOpen)
                .done(p -> cupboardOpen(p) || blackShieldOrLater(p))));
        s.step(ba(room("make room for the shield", 1).done(QuestShieldOfArrav::blackShieldOrLater)));
        s.step(ba(new QuestSteps.Interact("search the cupboard for the shield", new int[]{CUPBOARD_OPEN}, null, "Search", CUPBOARD_AT, 0, false).until(has(BLACKARM_SHIELD, 1))
                .done(QuestShieldOfArrav::blackShieldOrLater)));
        s.step(ba(new QuestSteps.Interact("down the Black Arm stairs", new int[]{BLACKARM_STAIRS_DOWN}, null, "Climb-down", BLACKARM_DOWN_AT, 0, false).until(p -> !upHouse(p))
                .done(p -> !upHouse(p))));
        s.step(ba(new QuestSteps.Interact("back through the Black Arm door", new int[]{BLACKARM_DOOR}, null, "Open", BLACKARM_DOOR_AT, 0, false).until(p -> !behindBlackArmDoor(p))
                .done(p -> !behindBlackArmDoor(p))));
        s.step(ba(new QuestSteps.Collect("have the half shield in the pack", BLACKARM_SHIELD, 1).done(QuestShieldOfArrav::certsOrLater)));

        // ================= both: the curator, the partner, the king
        s.step(new QuestSteps.Walk("walk to the museum", CURATOR_AT, 3).done(p -> certsOrLater(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(CURATOR_AT) <= 3));
        s.step(room("make room for two half-certificates", 2).done(QuestShieldOfArrav::certsOrLater));
        s.step(longTalk(new QuestSteps.Talk("show the curator the shield", new int[]{CURATOR}, "I have the Shield of Arrav")).until(p -> has(ownHalf(p), 1).test(p))
                .done(QuestShieldOfArrav::certsOrLater));

        // the other gang's half: from a partner bot at the museum, else bot stock
        s.step(new Meet("swap half-certificates with a partner", false).done(QuestShieldOfArrav::bothHalvesOrLater));
        s.step(new QuestSteps.Collect("have a Phoenix half-certificate in the pack", PHOENIX_HALF, 1)
                .done(p -> has(PHOENIX_HALF, 1).test(p) || any(p, CERTIFICATE) || stageAtLeast(Q, 100).test(p)));
        s.step(new QuestSteps.Collect("have a Black Arm half-certificate in the pack", BLACKARM_HALF, 1)
                .done(p -> has(BLACKARM_HALF, 1).test(p) || any(p, CERTIFICATE) || stageAtLeast(Q, 100).test(p)));
        s.step(new QuestSteps.Custom("join the two halves", 20, null, QuestShieldOfArrav::joinCertificates).done(p -> any(p, CERTIFICATE) || stageAtLeast(Q, 100).test(p)));
        s.step(new QuestSteps.Collect("have the certificate in the pack", CERTIFICATE, 1).done(p -> has(CERTIFICATE, 1).test(p) || stageAtLeast(Q, 100).test(p)));

        // King Roald
        s.step(new QuestSteps.Walk("walk to King Roald", KING_AT, 3).done(p -> stageAtLeast(Q, 100).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(KING_AT) <= 3));
        s.step(room("make room for the reward", 1).done(stageAtLeast(Q, 100)));
        s.step(longTalk(new QuestSteps.Talk("claim the reward from the king", new int[]{KING}).picks(QuestShieldOfArrav::kingPicks)).until(stageAtLeast(Q, 100)).done(stageAtLeast(Q, 100)));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));
        return s;
    }
}
