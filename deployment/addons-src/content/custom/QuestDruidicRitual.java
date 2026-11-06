package content.custom;

import core.game.interaction.IntType;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.world.map.Location;

import static content.custom.QuestScript.has;
import static content.custom.QuestScript.stageAtLeast;

/**
 * Druidic Ritual: Kaqemeex (455, Taverley stone circle, spawn 2925,3485,0) -> Sanfew (454, upstairs in the Taverley
 * herbalist's house, spawn 2899,3430,1) -> raw beef 2132, raw rat meat 2134, raw bear meat 2136, raw chicken 2138, each
 * used on the Cauldron of Thunder (2142 at 2893,9831,0, Taverley Dungeon) for the enchanted meats 522/523/524/525
 * (CauldronOfThunderListener) -> Sanfew takes all four (stage 99) -> Kaqemeex finishes it (4 QP, 250 Herblore XP).
 *
 * Stages (DruidicRitual.kt, KaqemeexDialogue.java, SanfewDialogue.java): 10 started, 20 Sanfew told us the meats,
 * 99 Sanfew has the meats, 100 done.
 * Dialogue picks (1-based):
 * - Kaqemeex not started: "Who are you? / I'm in search of a quest. / Did you build this?" 2, then "Ok, I will try and
 *   help." 1 -> stage 10. At 99: continue only -> finish (the close event then opens his herblore lecture: continue).
 * - Sanfew at 10: "I've been sent to help purify..." 1 -> stage 20 (case 8). At 20 with 522-525 in the PACK: continue only.
 *
 * The cauldron room: in this cache the doors are 31841/31844 at 2888,9830/9831 (plain door_configs doors). The
 * suit-of-armour plugin (TaverlyDungeonPlugin) is registered on 2143/2144, which this map doesn't have, so the suits
 * (scenery 32292 at 2887,9829/9832) stay scenery. The way there still runs past aggressive skeletons (5332-5337 around
 * 2885,9816-9825), and spiders (61) sit in the cauldron room; hellhounds (49) live west at x 2861-2871. Hence the
 * combat levels and Guarded (eat at half hp, no retaliation) for the whole dungeon trip, as Knight's Sword's ice dungeon.
 * Way in: ladder 20987 "Climb-down" at 2884,3397,0 -> ladder 32015 "Climb-up" at 2884,9797,0.
 *
 * Meats: the bank or bot stock first, then: raw beef and raw chicken at Wydin's Food Store (shop 77, Port Sarim),
 * raw rat meat from giant rats (drop tables 86/446/...; one at 2994,3194 by Wydin's, many in Lumbridge swamp), raw
 * bear meat from black bears (106, drop table) north-east of Taverley (2959,3445 / 2968,3480 / 2968,3490 / 2978,3507).
 * No shop a bot can reach sells rat or bear meat (Rufus in Canifis, Fresh Meat in Oo'glog).
 */
final class QuestDruidicRitual {
    private QuestDruidicRitual() { }

    static final content.data.Quests Q = content.data.Quests.DRUIDIC_RITUAL;
    static final int KAQEMEEX = 455, SANFEW = 454;
    static final int BEEF = 2132, RAT = 2134, BEAR = 2136, CHICKEN = 2138;
    static final int E_BEEF = 522, E_RAT = 523, E_BEAR = 524, E_CHICKEN = 525;
    static final int CAULDRON = 2142, LADDER_DOWN = 20987, LADDER_UP = 32015;
    static final Location KAQEMEEX_AT = Location.create(2925, 3485, 0);
    static final Location SANFEW_AT = Location.create(2899, 3430, 1);
    static final Location LADDER_TOP = Location.create(2884, 3397, 0);
    static final Location LADDER_TOP_SIDE = Location.create(2884, 3398, 0);
    static final Location LADDER_FOOT = Location.create(2884, 9797, 0);
    static final Location LADDER_FOOT_SIDE = Location.create(2884, 9798, 0);
    static final Location CAULDRON_AT = Location.create(2893, 9831, 0);
    static final Location WYDIN = Location.create(3013, 3204, 0);        // Wydin's Food Store, Port Sarim (557 at 3012,3203)
    static final Location PORT_RAT = Location.create(2994, 3194, 0);     // giant rat 446
    static final Location LUM_RATS = Location.create(3181, 3185, 0);     // Lumbridge swamp giant rats (86)
    static final Location BEARS = Location.create(2965, 3478, 0);        // black bears 106

    static boolean under(Player p) { return p.getLocation().getY() > 6400; }
    static boolean done(Player p) { return stageAtLeast(Q, 100).test(p); }
    static boolean handedIn(Player p) { return stageAtLeast(Q, 99).test(p); }
    static boolean allEnchanted(Player p) { return has(E_BEEF, 1).test(p) && has(E_RAT, 1).test(p) && has(E_BEAR, 1).test(p) && has(E_CHICKEN, 1).test(p); }
    /** Raw meat in hand, or already dipped, or handed in. */
    static boolean meat(Player p, int raw, int dipped) { return has(raw, 1).test(p) || has(dipped, 1).test(p) || handedIn(p); }
    static boolean allRaw(Player p) { return meat(p, BEEF, E_BEEF) && meat(p, RAT, E_RAT) && meat(p, BEAR, E_BEAR) && meat(p, CHICKEN, E_CHICKEN); }

    static QuestSteps.Talk talk(QuestSteps.Talk t) { t.limitTicks = 300; return t; }

    static QuestSteps.Step guarded(QuestSteps.Step s) { return new QuestBlackKnightsFortress.Guarded(s); }

    static QuestScript build() {
        QuestScript s = QuestScript.of(Q, "druidicritual", "Druidic Ritual").startAt(2925, 3485, 0).minutes(60).xp(250 * 0.5).unlock(20)
                // Taverley Dungeon: aggressive skeletons on the way to the cauldron, spiders inside (see above)
                .level(Skills.ATTACK, 40).level(Skills.STRENGTH, 40).level(Skills.DEFENCE, 40).level(Skills.HITPOINTS, 40);

        // Kaqemeex starts it
        s.step(new QuestSteps.Walk("walk to Kaqemeex at the stone circle", KAQEMEEX_AT, 4).done(p -> stageAtLeast(Q, 10).test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(KAQEMEEX_AT) <= 4));
        s.step(talk(new QuestSteps.Talk("ask Kaqemeex for a quest", new int[]{KAQEMEEX}, 2, 1)).until(stageAtLeast(Q, 10)).done(stageAtLeast(Q, 10)));

        // Sanfew tells us the meats
        s.step(new QuestSteps.Walk("up to Sanfew in Taverley", SANFEW_AT, 3).done(p -> stageAtLeast(Q, 20).test(p) || p.getLocation().getZ() == 1 && p.getLocation().getDistance(SANFEW_AT) <= 3));
        s.step(talk(new QuestSteps.Talk("offer Sanfew help with the ritual", new int[]{SANFEW}, 1)).until(stageAtLeast(Q, 20)).done(stageAtLeast(Q, 20)));

        // the four raw meats: bank or bot stock first
        int[][] raws = {{BEAR, E_BEAR}, {BEEF, E_BEEF}, {CHICKEN, E_CHICKEN}, {RAT, E_RAT}};
        for (int[] m : raws) s.step(new QuestSteps.Collect("look for " + MarketBrain.name(m[0]) + " in the bank and the Exchange", m[0], 1).stock().done(p -> meat(p, m[0], m[1])).optional());
        // bear meat: black bears north-east of Taverley
        s.step(new QuestSteps.Kill("hunt black bears for bear meat", new String[]{"Black bear"}, new int[]{BEAR}, 40, BEARS)
                .spots(Location.create(2959, 3445, 0), Location.create(2968, 3490, 0), Location.create(2978, 3507, 0))
                .until(p -> meat(p, BEAR, E_BEAR)).limit(4000).done(p -> meat(p, BEAR, E_BEAR)));
        // beef and chicken: Wydin's in Port Sarim
        s.step(new QuestSteps.Collect("get raw beef", BEEF, 1).buy(new QuestSteps.Buy("buy raw beef at Wydin's", BEEF, 1, "Wydin's Food Store", WYDIN)).done(p -> meat(p, BEEF, E_BEEF)));
        s.step(new QuestSteps.Collect("get raw chicken", CHICKEN, 1).buy(new QuestSteps.Buy("buy raw chicken at Wydin's", CHICKEN, 1, "Wydin's Food Store", WYDIN)).done(p -> meat(p, CHICKEN, E_CHICKEN)));
        // rat meat: the giant rat by Wydin's, then Lumbridge swamp
        s.step(new QuestSteps.Kill("hunt giant rats for rat meat", new String[]{"Giant rat"}, new int[]{RAT}, 40, PORT_RAT)
                .spots(LUM_RATS, Location.create(3172, 3178, 0), Location.create(3185, 3199, 0))
                .until(p -> meat(p, RAT, E_RAT)).limit(4000).done(p -> meat(p, RAT, E_RAT)));

        // Taverley Dungeon and the Cauldron of Thunder
        java.util.function.Predicate<Player> dipped = p -> allEnchanted(p) || handedIn(p);
        s.step(new QuestSteps.Custom("pack food for Taverley Dungeon", 5, null, r -> { QuestRunner.makeRoom(r.bot, 10); QuestRunner.packFood(r.bot, 10); return QuestSteps.St.DONE; }).done(p -> under(p) || dipped.test(p)));
        s.step(new QuestSteps.Walk("walk to the Taverley Dungeon ladder", LADDER_TOP_SIDE, 2).done(p -> under(p) || dipped.test(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(LADDER_TOP_SIDE) <= 2));
        s.step(new QuestSteps.Interact("climb down into Taverley Dungeon", new int[]{LADDER_DOWN}, null, "Climb-down", LADDER_TOP, 6, false).until(QuestDruidicRitual::under).done(p -> under(p) || dipped.test(p)));
        s.step(guarded(new QuestSteps.Walk("walk past the skeletons to the cauldron room", CAULDRON_AT, 3)).done(p -> dipped.test(p) || under(p) && p.getLocation().getDistance(CAULDRON_AT) <= 3));
        int[][] dips = {{BEEF, E_BEEF}, {RAT, E_RAT}, {BEAR, E_BEAR}, {CHICKEN, E_CHICKEN}};
        for (int[] d : dips) {
            s.step(guarded(new QuestSteps.Collect("enchant " + MarketBrain.name(d[0]), d[1], 1).noStock()
                    .then(new QuestSteps.UseOn("dip the " + MarketBrain.name(d[0]) + " in the Cauldron of Thunder", d[0], new int[]{CAULDRON}, null, IntType.SCENERY, CAULDRON_AT, 6).until(has(d[1], 1))))
                    .done(p -> has(d[1], 1).test(p) || handedIn(p)));
        }
        s.step(guarded(new QuestSteps.Walk("back to the dungeon ladder", LADDER_FOOT_SIDE, 2)).done(p -> !under(p) || p.getLocation().getDistance(LADDER_FOOT_SIDE) <= 2));
        s.step(guarded(new QuestSteps.Interact("climb out of Taverley Dungeon", new int[]{LADDER_UP}, null, "Climb-up", LADDER_FOOT, 6, false).until(p -> !under(p))).done(p -> !under(p)));

        // Sanfew gets the meats
        s.step(new QuestSteps.Walk("back up to Sanfew with the meats", SANFEW_AT, 3).done(p -> handedIn(p) || p.getLocation().getZ() == 1 && p.getLocation().getDistance(SANFEW_AT) <= 3));
        s.step(talk(new QuestSteps.Talk("give Sanfew the enchanted meats", new int[]{SANFEW})).until(QuestDruidicRitual::handedIn).done(QuestDruidicRitual::handedIn));

        // Kaqemeex teaches Herblore
        s.step(new QuestSteps.Walk("back to Kaqemeex", KAQEMEEX_AT, 4).done(p -> done(p) || p.getLocation().getZ() == 0 && p.getLocation().getDistance(KAQEMEEX_AT) <= 4));
        s.step(talk(new QuestSteps.Talk("tell Kaqemeex the meats are ready", new int[]{KAQEMEEX})).until(QuestDruidicRitual::done).done(QuestDruidicRitual::done));
        s.step(new QuestSteps.WaitStage("wait for the reward", 100, 30));
        return s;
    }
}
