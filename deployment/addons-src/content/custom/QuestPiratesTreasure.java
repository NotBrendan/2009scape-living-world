package content.custom;

import core.game.interaction.IntType;
import core.game.interaction.InteractionListeners;
import core.game.interaction.Option;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

import static content.custom.QuestScript.attr;
import static content.custom.QuestScript.attrInt;
import static content.custom.QuestScript.has;
import static content.custom.QuestScript.stageAtLeast;

/**
 * Pirate's Treasure: Redbeard Frank (375, Port Sarim, spawn 3053,3249,0) wants Karamjan rum (431). The rum is smuggled
 * off Karamja in Luthas' banana crate to Wydin's back room, Frank swaps it for Hector's chest key (432), the chest in
 * the Blue Moon Inn (Varrock) holds the pirate message (433), and digging in Falador park finishes it.
 * NPC spawns (data/configs/npc_spawns.json): Frank 375 {3053,3249}; seamen 376 {3027,3217}, 377 {3028,3223}, 378
 * {3028,3220}; customs officer 380 {2953,3149} (Musa Point) and {2770,3226}; Luthas 379 {2939,3156}; Zambo 568
 * {2925,3143} (Karamja Wines, sells 431, shops.json 20); Wydin 557 {3012,3203}; Thessalia 548 {3205,3416} (sells the
 * white apron 1005, shops.json 60); gardener 1217 {3000,3386} / {3019,3368}.
 *
 * Stages (PiratesTreasure.java, RedbeardFrankDialogue.java, PiratesTreasurePlugin.java): 10 Frank wants rum, 20 rum
 * given (key in hand), 100 done (TreasureDigPlugin -> quest.finish: 2 QP and Hector's casket 7956; opening the casket
 * gives 450 coins, a gold ring 1635 and an emerald 1605).
 *
 * Dialogue picks (1-based option buttons):
 * - Frank at stage 0: continue, then 1 ("I'm in search of treasure."), continue, then 1 ("Ok, I will bring you some
 *   rum.") -> quest.start = stage 10. Stage 10 with rum in the PACK: continue only -> rum taken, key given, stage 20.
 *   (Stage 10 without rum ends in a stage 25 his handle() has no case for: never talk to him empty-handed.) Stage 20
 *   with no key in pack or bank: continue only, a spare key.
 * - Seamen 376/377/378 (SeamanDialoguePlugin): continue, continue, then 1 ("Yes, please.", also with the Ring of charos
 *   third option) -> 30 coins from the pack, Ships.PORT_SARIM_TO_KARAMAJA: lands on the deck at 2956,3143,1 (teleport on
 *   the 2nd tick, locked 10 ticks). Gangplank 2082 "Cross" (PortsObjectPlugin) -> 2956,3146,0.
 * - Luthas 379 (LuthasDialogue) without the job: continue, then 1 ("Could you offer me employment on your plantation?")
 *   -> GlobalData.luthasTask. With the job and 10+ bananas in the crate: continue only -> 30 coins, the crate empties,
 *   and if the rum is stashed ("stashed-rum") the attribute "/save:wydin-rum" is set.
 * - Customs officer 380 (CustomsOfficerDialogue), Talk-to before the quest is done: continue, then 1 ("Can I journey on
 *   this ship?"), continue, then 2 ("Search away, I have nothing to hide."), then (no rum in the PACK) continue, 1 ("Ok.")
 *   -> 30 coins from the pack, Ships.KARAMJAMA_TO_PORT_SARIM lands at 3029,3217,0. Rum in the pack is confiscated.
 *
 * The smuggling (KaramjaBananaCrateListener, PortSarimPlugin, WydinDoorPlugin): crate 2072 at 2943,3151 (cache map):
 * use 431 on it with the job -> "/save:stashed-rum"; "Fill" packs the pack's bananas (1963) up to 10. Banana trees 2073
 * "Pick" (FieldPickingPlugin, five bananas a tree, regrows after 300 ticks) across 2906-2930,3155-3170. Wydin's back
 * door 2069 at 3012,3204 only opens while the white apron is WORN (no job check: becoming his employee isn't needed);
 * crate 2071 at 3009,3207 "Search" with "wydin-rum" set and a free slot -> the rum. White apron ground spawns
 * (ground_spawns.json) at 3014,3227 (Gerrant's) and 3009,3204 (inside the back room).
 * Chest 2079 at 3219,3396,1 (Blue Moon Inn first floor, stairs 24356 at 3227,3393): "Open" only says it is locked;
 * key 432 used on it (ChestKeyListener) -> the message 433. Reading it (PiratesTreasurePlugin) sets "/save:pirate-read"
 * but the dig doesn't check it.
 * The dig (TreasureDigPlugin, DigSpadeHandler): spade "Dig" standing ON 2999,3383 or 3000,3383 at stage 20. First time:
 * gardener 1217 spawns one tile north, attacks, attackable only by this player ("target"), "gardener-dug" set (not
 * saved), so further digs do nothing until he dies (GardenerNPC.finalizeDeath -> QuestData.gardenerAttack). He walks
 * off (clear) if the target is 16+ tiles away. Gardener: 5 hp, attack/strength/defence 4 (npc_configs.json).
 * Then the next dig finishes the quest.
 */
final class QuestPiratesTreasure {
    private QuestPiratesTreasure() { }

    static final content.data.Quests Q = content.data.Quests.PIRATES_TREASURE;
    static final int FRANK = 375, LUTHAS = 379, CUSTOMS = 380, GARDENER = 1217;
    static final int[] SEAMEN = {376, 377, 378};
    static final int RUM = 431, KEY = 432, MESSAGE = 433, CASKET = 7956, BANANA = 1963, APRON = 1005, SPADE = 952, COINS = 995;
    static final int BANANA_CRATE = 2072, GANGPLANK = 2082, WYDIN_DOOR = 2069, WYDIN_CRATE = 2071, CHEST = 2079;
    static final int BODY = 4;                                            // EquipmentContainer.SLOT_CHEST
    static final String OLD_BODY = "quest-pt:old-body";
    static final Location FRANK_AT = Location.create(3053, 3249, 0);
    static final Location SEAMEN_AT = Location.create(3028, 3220, 0);     // Port Sarim docks (378's spawn)
    static final Location DECK = Location.create(2956, 3143, 1);          // Ships.PORT_SARIM_TO_KARAMAJA
    static final Location LUTHAS_AT = Location.create(2939, 3156, 0);
    static final Location ZAMBO_AT = Location.create(2925, 3143, 0);
    static final Location CRATE_AT = Location.create(2943, 3151, 0);
    static final Location PLANTATION = Location.create(2918, 3162, 0);
    static final Location CUSTOMS_AT = Location.create(2953, 3149, 0);
    static final Location GERRANT_APRON = Location.create(3014, 3227, 0);
    static final Location THESSALIA = Location.create(3205, 3416, 0);
    static final Location WYDIN_FRONT = Location.create(3013, 3205, 0);   // in the shop, by the back door 2069 (3012,3204)
    static final Location WYDIN_DOOR_AT = Location.create(3012, 3204, 0);
    static final Location WYDIN_CRATE_AT = Location.create(3009, 3207, 0);
    static final Location CHEST_AT = Location.create(3219, 3396, 1);
    static final Location CHEST_SIDE = Location.create(3220, 3396, 1);    // NOT verified walkable: the use-with paths the rest
    static final Location SARAH = Location.create(3039, 3292, 0);         // Sarah's Farming Shop (sells 952, shops.json 75), as QuestErnestTheChicken
    static final Location DIG_AT = Location.create(2999, 3383, 0);        // TreasureDigPlugin.LOCATIONS (also 3000,3383)
    static final Location DIG_AT2 = Location.create(3000, 3383, 0);

    // where the bot is along the smuggling, latest first (attributes: LuthasDialogue, KaramjaBananaCrateListener, PortSarimPlugin)
    static boolean onKaramja(Player p) { Location l = p.getLocation(); return l.getY() < 6400 && l.getX() >= 2840 && l.getX() <= 2985 && l.getY() >= 3130 && l.getY() <= 3185; }
    static boolean onDeck(Player p) { return onKaramja(p) && p.getLocation().getZ() == 1; }
    static boolean s20(Player p) { return stageAtLeast(Q, 20).test(p); }
    static boolean done(Player p) { return stageAtLeast(Q, 100).test(p); }
    static boolean rumBack(Player p) { return s20(p) || has(RUM, 1).test(p) && !onKaramja(p) && !attr(p, "stashed-rum") && !attr(p, "wydin-rum"); }
    static boolean paid(Player p) { return attr(p, "wydin-rum") || rumBack(p); }
    static boolean stashed(Player p) { return attr(p, "stashed-rum") || paid(p); }
    static boolean hired(Player p) { try { return p.getSavedData().getGlobalData().isLuthasTask() || stashed(p); } catch (Throwable t) { return stashed(p); } }
    static int inCrate(Player p) { try { return p.getSavedData().getGlobalData().getKaramjaBananas(); } catch (Throwable t) { return 0; } }
    static boolean crateFull(Player p) { return inCrate(p) >= 10 || paid(p); }
    static boolean inShop(Player p) { Location l = p.getLocation(); return l.getZ() == 0 && l.getX() >= 3012 && l.getX() <= 3016 && l.getY() >= 3204 && l.getY() <= 3210; }
    static boolean inBackRoom(Player p) { Location l = p.getLocation(); return l.getZ() == 0 && l.getX() >= 3008 && l.getX() <= 3011 && l.getY() >= 3203 && l.getY() <= 3210; }
    static boolean gardenerDown(Player p) { try { return p.getSavedData().getQuestData().isGardenerAttack(); } catch (Throwable t) { return false; } }
    static boolean wearingApron(Player p) { return p.getEquipment().contains(APRON, 1); }

    /** Wear an item as the client's Wear click does, the bots' own wear as the fallback (as QuestRestlessGhost.equip). */
    static boolean equip(Player p, int id) {
        Item it = null;
        for (int i = 0; i < 28; i++) { Item x = p.getInventory().get(i); if (x != null && x.getId() == id) { it = x; break; } }
        if (it == null) return p.getEquipment().contains(id, 1);
        try { InteractionListeners.run(id, IntType.ITEM, "wear", p, it); } catch (Throwable ignored) { }
        if (!p.getEquipment().contains(id, 1)) SmartAdventurers.wear(p, id);
        return p.getEquipment().contains(id, 1);
    }

    /** An item's own option from the pack (Read, Open, Dig), the way the client's item click runs it (InteractPlugin.handleItemOption). */
    static boolean itemOption(Player p, int id, String option) {
        Item it = null;
        for (int i = 0; i < 28; i++) { Item x = p.getInventory().get(i); if (x != null && x.getId() == id) { it = x; break; } }
        if (it == null) return false;
        Option opt = null;
        try { for (Option o : it.getInteraction().getOptions()) if (o != null && o.getName().equalsIgnoreCase(option)) opt = o; } catch (Throwable ignored) { }
        if (opt == null) return false;
        it.getInteraction().handleItemOption(p, opt, p.getInventory());
        return true;
    }

    // batch 16 item6: the crossing steps on their own, so an AFK trip to or from Karamja (AfkTasks.Runner.goTo) takes the boat the way this quest does
    static final QuestScript BOAT = QuestScript.of(Q, "karamjaboat", "the Karamja boat");
    static QuestSteps.Talk seamanTalk() { return new QuestSteps.Talk("take the boat to Karamja", SEAMEN, 1).until(QuestPiratesTreasure::onKaramja); }
    static QuestSteps.Interact gangplank() { return new QuestSteps.Interact("cross the gangplank", new int[]{GANGPLANK}, null, "Cross", DECK, 4, false).until(p -> !onDeck(p)); }
    static QuestSteps.Talk customsTalk() { return new QuestSteps.Talk("get searched and board for Port Sarim", new int[]{CUSTOMS}, 1, 2, 1).until(p -> !onKaramja(p)); }
    /** Port Sarim to Musa Point: 30 coins into the pack, the seaman, the voyage, down the gangplank. */
    static java.util.List<QuestSteps.Step> boatToKaramja() {
        return java.util.Arrays.asList(QuestSteps.pocketCoins(30), seamanTalk(), shipWait("sail to Karamja").done(p -> !onDeck(p)), gangplank().done(p -> !onDeck(p)));
    }
    /** Musa Point to Port Sarim: 30 coins into the pack, the customs officer's search, the voyage (it lands on the Port Sarim quay). */
    static java.util.List<QuestSteps.Step> boatToPortSarim() {
        return java.util.Arrays.asList(QuestSteps.pocketCoins(30), customsTalk(), shipWait("sail to Port Sarim"));
    }

    static QuestSteps.Step makeRoom(String name, int n) { return new QuestSteps.Custom(name, 5, null, r -> { QuestRunner.makeRoom(r.bot, n); return QuestSteps.St.DONE; }); }

    /** The ship locks the bot and leaves "The ship arrives at ..." open: wait it out, then clear it. */
    static QuestSteps.Step shipWait(String name) {
        final int[] t = {0};
        return new QuestSteps.Custom(name, 40, r -> t[0] = 0, r -> {
            if (++t[0] < 12) return QuestSteps.St.RUNNING;
            QuestSteps.clearPopups(r.bot);
            return QuestSteps.St.DONE;
        });
    }

    /** Nothing the customs officer would confiscate: rum in the pack goes to the bank (it should be in the crate). */
    static QuestSteps.St noRumInPack(QuestRunner r) {
        Player p = r.bot;
        int n = p.getInventory().getAmount(RUM);
        if (n > 0 && p.getInventory().remove(new Item(RUM, n))) { if (!p.getBank().add(new Item(RUM, n))) p.getInventory().add(new Item(RUM, n)); else r.log("  banked " + n + " rum before the customs search"); }
        return p.getInventory().getAmount(RUM) == 0 ? QuestSteps.St.DONE : QuestSteps.St.FAILED;
    }

    static QuestSteps.St wearApron(QuestRunner r) {
        Player p = r.bot;
        if (wearingApron(p)) return QuestSteps.St.DONE;
        if (p.getInventory().getAmount(APRON) == 0) return QuestSteps.St.FAILED;
        Item old = p.getEquipment().get(BODY);
        if (old != null && old.getId() != APRON) p.setAttribute(OLD_BODY, old.getId());
        if (equip(p, APRON)) { r.log("  wearing the white apron" + (old != null ? " (took off " + MarketBrain.name(old.getId()) + ")" : "")); return QuestSteps.St.DONE; }
        return QuestSteps.St.FAILED;
    }

    static QuestSteps.St oldBodyBack(QuestRunner r) {
        Player p = r.bot;
        int old = attrInt(p, OLD_BODY);
        if (old > 0 && p.getInventory().getAmount(old) > 0) equip(p, old);
        p.removeAttribute(OLD_BODY);
        return QuestSteps.St.DONE;
    }

    /** Read the pirate message (sets pirate-read; the dig doesn't need it, but a player would). */
    static QuestSteps.Step readMessage() {
        final int[] t = {0};
        return new QuestSteps.Custom("read the pirate message", 30, r -> t[0] = 0, r -> {
            Player p = r.bot;
            if (attr(p, "pirate-read")) { if (p.hasModalOpen()) p.getInterfaceManager().close(); return QuestSteps.St.DONE; }
            if (t[0]++ == 0) { if (!itemOption(p, MESSAGE, "Read")) return QuestSteps.St.FAILED; }
            if (t[0] > 10) { if (p.hasModalOpen()) p.getInterfaceManager().close(); return QuestSteps.St.FAILED; }
            return QuestSteps.St.RUNNING;
        });
    }

    /** The gardener this bot's dig woke (TreasureDigPlugin sets "target" on him). */
    static NPC gardener(Player p) {
        try { for (NPC n : RegionManager.getLocalNpcs(p, 16)) if (n != null && n.getId() == GARDENER && n.isActive() && n.getAttribute("target", null) == p) return n; } catch (Throwable ignored) { }
        return null;
    }

    static boolean onDigTile(Player p) { return p.getLocation().equals(DIG_AT) || p.getLocation().equals(DIG_AT2); }

    /**
     * Dig on the X, fight off the gardener the first dig wakes, dig again. One step: the gardener walks off if the bot
     * goes 16 tiles away, and "gardener-dug" (unsaved) stops a second gardener until the bot logs out.
     */
    static QuestSteps.Step dig() {
        final QuestSteps.Mover m = new QuestSteps.Mover();
        final int[] st = {0, 0, 0, 0}; // digs with no gardener seen after, wait, eat cooldown, ticks walking to the tile
        return new QuestSteps.Custom("dig for Hector's treasure", 1500, r -> { st[0] = 0; st[1] = 0; st[2] = 0; st[3] = 0; m.reset(DIG_AT, 0); }, r -> {
            Player p = r.bot;
            if (done(p)) return QuestSteps.St.DONE;
            if (p.getInventory().getAmount(SPADE) < 1) { r.log("  no spade in the pack"); return QuestSteps.St.FAILED; }
            if (st[2] > 0) st[2]--;
            NPC g = gardener(p);
            if (g != null) {
                int hp = p.getSkills().getLifepoints(), max = p.getSkills().getMaximumLifepoints();
                if (hp * 2 <= max && st[2] == 0) {
                    for (Item it : p.getInventory().toArray()) {
                        if (it == null || !QuestSteps.contains(AfkTasks.Runner.FOOD, it.getId())) continue;
                        try { new core.game.bots.ScriptAPI(p).forceEat(it.getId()); } catch (Throwable ignored) { }
                        st[2] = 3;
                        break;
                    }
                }
                try { if (p.getProperties().getCombatPulse().getVictim() != g) p.attack(g); } catch (Throwable ignored) { }
                st[0] = 0; st[1] = 3;
                return QuestSteps.St.RUNNING;
            }
            if (st[1] > 0) { st[1]--; return QuestSteps.St.RUNNING; }
            if (p.inCombat() || p.getWalkingQueue().isMoving()) return QuestSteps.St.RUNNING;
            if (!onDigTile(p)) {
                if (++st[3] > 400) { r.log("  couldn't stand on the X at " + DIG_AT + " (at " + p.getLocation() + ")"); return QuestSteps.St.FAILED; }
                if (p.getLocation().getDistance(DIG_AT) > 3) { QuestSteps.St w = m.tick(p); if (w == QuestSteps.St.FAILED) return w; return QuestSteps.St.RUNNING; }
                try { if (p instanceof core.game.bots.AIPlayer) ((core.game.bots.AIPlayer) p).walkToPosSmart(DIG_AT); } catch (Throwable ignored) { }
                st[1] = 2;
                return QuestSteps.St.RUNNING;
            }
            if (st[0] >= 3) { r.log("  dug " + st[0] + " times with no gardener and no treasure (gardener-dug left over from an earlier try?)"); return QuestSteps.St.FAILED; }
            QuestSteps.clearPopups(p);
            if (!itemOption(p, SPADE, "Dig")) core.game.global.action.DigSpadeHandler.dig(p);
            st[0]++; st[1] = 4;
            return QuestSteps.St.RUNNING;
        });
    }

    /** Open Hector's casket as a player would (PiratesTreasurePlugin): the 450 coins go to the quest ledger, as the reward would. */
    static QuestSteps.Step openCasket() {
        final long[] st = {0, 0}; // ticks, coins before
        return new QuestSteps.Custom("open Hector's casket", 20, r -> { st[0] = 0; st[1] = Ledger.Wallet.coins(r.bot); }, r -> {
            Player p = r.bot;
            if (st[0]++ == 0) {
                if (p.getInventory().getAmount(CASKET) == 0) return QuestSteps.St.DONE;
                QuestRunner.makeRoom(p, 3);
                if (!itemOption(p, CASKET, "Open")) return QuestSteps.St.FAILED;
                return QuestSteps.St.RUNNING;
            }
            if (p.getInventory().getAmount(CASKET) > 0 && st[0] < 10) return QuestSteps.St.RUNNING;
            long gained = Ledger.Wallet.coins(p) - st[1];
            if (gained > 0) Ledger.questPaid(gained, 0); // the casket itself was booked at its guide value when the quest finished
            r.log("  opened Hector's casket: " + gained + " gp");
            return p.getInventory().getAmount(CASKET) == 0 ? QuestSteps.St.DONE : QuestSteps.St.FAILED;
        });
    }

    static QuestScript build() {
        QuestScript s = QuestScript.of(Q, "piratestreasure", "Pirate's Treasure").startAt(3053, 3249, 0).minutes(45)
                .coins(450).items(1635, 1605).xp(0).unlock(20);   // unlock: Pay-fare on the Karamja boats (CustomsOfficerPlugin, SeamanDialoguePlugin)

        // Frank
        s.step(new QuestSteps.Walk("walk to Redbeard Frank", FRANK_AT, 3).done(p -> stageAtLeast(Q, 10).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(FRANK_AT) <= 3));
        s.step(new QuestSteps.Talk("ask Redbeard Frank about treasure", new int[]{FRANK}, 1, 1).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));

        // to Karamja (30 gp in the pack for the seaman)
        s.step(new QuestSteps.Collect("get 30 coins for the boat", COINS, 30).done(p -> onKaramja(p) || paid(p) || has(COINS, 30).test(p)));
        s.step(new QuestSteps.Walk("walk to the Port Sarim docks", SEAMEN_AT, 4).done(p -> onKaramja(p) || paid(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(SEAMEN_AT) <= 4));
        s.step(seamanTalk().done(p -> onKaramja(p) || paid(p)));
        s.step(shipWait("sail to Karamja").done(p -> !onDeck(p)));
        s.step(gangplank().done(p -> !onDeck(p)));

        // a job at the banana plantation
        s.step(new QuestSteps.Walk("walk to Luthas", LUTHAS_AT, 3).done(p -> hired(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(LUTHAS_AT) <= 3));
        s.step(new QuestSteps.Talk("ask Luthas for a job", new int[]{LUTHAS}, 1).until(QuestPiratesTreasure::hired).done(QuestPiratesTreasure::hired));

        // the rum, into the crate
        s.step(new QuestSteps.Collect("get some Karamjan rum", RUM, 1)
                .buy(new QuestSteps.Buy("buy rum from Zambo", RUM, 1, "Karamja Wines, Spirits, and Beers", ZAMBO_AT))
                .done(p -> has(RUM, 1).test(p) || stashed(p)));
        s.step(new QuestSteps.UseOn("hide the rum in the banana crate", RUM, new int[]{BANANA_CRATE}, null, IntType.SCENERY, CRATE_AT, 4).until(QuestPiratesTreasure::stashed).done(QuestPiratesTreasure::stashed));

        // ten bananas on top
        s.step(new QuestSteps.Collect("get bananas for the crate", BANANA, 10).count(p -> Math.max(1, 10 - inCrate(p))).stock()
                .then(new QuestSteps.Gather("pick bananas", AfkTasks.Task.FLAX, BANANA, 10, PLANTATION).count(p -> Math.max(1, 10 - inCrate(p))).targets("Banana Tree").products(BANANA))
                .done(p -> crateFull(p) || has(BANANA, Math.max(1, 10 - inCrate(p))).test(p)));
        s.step(new QuestSteps.Interact("fill the crate with bananas", new int[]{BANANA_CRATE}, null, "Fill", CRATE_AT, 4, false).until(QuestPiratesTreasure::crateFull).done(QuestPiratesTreasure::crateFull));
        s.step(new QuestSteps.Walk("back to Luthas", LUTHAS_AT, 3).done(p -> paid(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(LUTHAS_AT) <= 3));
        s.step(new QuestSteps.Talk("tell Luthas the crate is full", new int[]{LUTHAS}).until(QuestPiratesTreasure::paid).done(QuestPiratesTreasure::paid));

        // home past the customs officer
        s.step(new QuestSteps.Custom("nothing in the pack for customs to find", 5, null, QuestPiratesTreasure::noRumInPack).done(p -> !onKaramja(p)));
        s.step(new QuestSteps.Collect("get 30 coins for the boat home", COINS, 30).done(p -> !onKaramja(p) || has(COINS, 30).test(p)));
        s.step(new QuestSteps.Walk("walk to the customs officer", CUSTOMS_AT, 3).done(p -> !onKaramja(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(CUSTOMS_AT) <= 3));
        s.step(customsTalk().done(p -> !onKaramja(p)));
        s.step(shipWait("sail to Port Sarim").done(p -> !onKaramja(p) && p.getLocation().getDistance(Location.create(3029, 3217, 0)) > 10 || rumBack(p)));

        // Wydin's back room in a white apron
        s.step(new QuestSteps.Collect("get a white apron", APRON, 1).stock()
                .then(new QuestSteps.PickUp("pick up the apron in Gerrant's shop", APRON, GERRANT_APRON).limit(300))
                .buy(new QuestSteps.Buy("buy a white apron from Thessalia", APRON, 1, "Thessalia's Fine Clothes", THESSALIA))
                .done(p -> rumBack(p) || has(APRON, 1).test(p) || wearingApron(p)));
        s.step(new QuestSteps.Custom("put the white apron on", 20, null, QuestPiratesTreasure::wearApron).done(p -> rumBack(p) || wearingApron(p)));
        // Live 10-23: bots coming from the south stopped outside the shop's south wall ("close enough") and the back-door click
        // couldn't path. The shop (x 3012-3016, y 3204-3210, cache map) is entered by its east door 40108 at 3017,3206.
        s.step(QuestErnestTheChicken.intoRoom("walk into Wydin's food store", WYDIN_FRONT, p -> inShop(p) || inBackRoom(p)).done(p -> rumBack(p) || inBackRoom(p) || inShop(p)));
        s.step(new QuestSteps.Interact("through Wydin's back door", new int[]{WYDIN_DOOR}, null, "Open", WYDIN_DOOR_AT, 3, false).until(QuestPiratesTreasure::inBackRoom).done(p -> rumBack(p) || inBackRoom(p)));
        s.step(makeRoom("make room for the rum", 1).done(QuestPiratesTreasure::rumBack));
        s.step(new QuestSteps.Interact("search the banana crate for the rum", new int[]{WYDIN_CRATE}, null, "Search", WYDIN_CRATE_AT, 3, false).until(QuestPiratesTreasure::rumBack).done(QuestPiratesTreasure::rumBack));
        s.step(new QuestSteps.Interact("back out through Wydin's door", new int[]{WYDIN_DOOR}, null, "Open", WYDIN_DOOR_AT, 3, false).until(p -> !inBackRoom(p)).done(p -> !inBackRoom(p)));
        s.step(new QuestSteps.Custom("take the apron off", 20, null, QuestPiratesTreasure::oldBodyBack).done(p -> attrInt(p, OLD_BODY) == 0));

        // the rum to Frank, the key back
        s.step(new QuestSteps.Walk("back to Redbeard Frank with the rum", FRANK_AT, 3).done(p -> s20(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(FRANK_AT) <= 3));
        s.step(makeRoom("make room for the key", 1).done(QuestPiratesTreasure::s20));
        s.step(new QuestSteps.Talk("give Frank the rum", new int[]{FRANK}).until(QuestPiratesTreasure::s20).done(QuestPiratesTreasure::s20));

        // the message from Hector's chest
        s.step(new QuestSteps.Collect("get the pirate message", MESSAGE, 1)
                .then(new QuestSteps.Collect("get Hector's key", KEY, 1)
                        .then(new QuestSteps.Walk("back to Frank for a key", FRANK_AT, 3))
                        .then(makeRoom("make room for the key", 1))
                        .then(new QuestSteps.Talk("tell Frank I lost the key", new int[]{FRANK}).until(has(KEY, 1)))
                        .done(has(KEY, 1)))
                .then(new QuestSteps.Walk("up to Hector's room in the Blue Moon Inn", CHEST_SIDE, 2))
                .then(makeRoom("make room for the message", 1))
                .then(new QuestSteps.UseOn("unlock Hector's chest", KEY, new int[]{CHEST}, null, IntType.SCENERY, CHEST_AT, 3).until(has(MESSAGE, 1)))
                .done(p -> has(MESSAGE, 1).test(p) || attr(p, "pirate-read") || gardenerDown(p) || done(p)));
        s.step(readMessage().optional().done(p -> attr(p, "pirate-read") || done(p)));

        // dig in Falador park
        s.step(new QuestSteps.Collect("get a spade", SPADE, 1).stock()
                .buy(new QuestSteps.Buy("buy a spade", SPADE, 1, "Sarah's Farming Shop", SARAH))
                .done(p -> has(SPADE, 1).test(p) || done(p)));
        s.step(new QuestSteps.Walk("walk to Falador park", DIG_AT, 3).done(p -> done(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(DIG_AT) <= 3));
        s.step(dig().done(QuestPiratesTreasure::done));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));
        s.exit(openCasket());
        return s;
    }
}
