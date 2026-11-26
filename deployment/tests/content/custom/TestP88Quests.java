package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

/**
 * Trial only, never shipped (P88). -Dtest.p88=true plus the live flag line; -Dquest.p88Fix=true for the fix. Bots start in
 * the state live bots had at the train-37+ failures (P83's unit started them with an empty pack, money, supplies banked):
 *  w0 Wolf Whistle stage 40 on 2926,3444 (where the cellar ladder lands), key and every supply in a full pack (control);
 *  w1 stage 40 next to Pikkupstix, bones and key banked, no gold charms anywhere, 1,000 gp (step 20);
 *  w2 stage 40 next to Pikkupstix, bones, charms and key banked, 0 gp (step 20: can't pay the shard bill);
 *  w3 stage 40 next to Pikkupstix, a full pack, the key and every supply banked, 1,000 gp (step 22: the key stays banked);
 *  g0 Restless Ghost stage 30 in the Wizards' Tower wearing the amulet, a full pack (step 10: the skull goes to the floor);
 *  g1 stage 40 in the Wizards' Tower wearing the amulet, no skull anywhere (step 10: the altar finds nothing).
 * PASS: wolf bots reach stage 50, ghost bots finish (100). Also a trapdoor probe: one finished bot per tile next to the
 * trapdoor clicks Climb-down once; logs which tiles get down. Cap -Dtest.p88.ticks (4000).
 */
public final class TestP88Quests implements StartupListener {
    static final String T = "[TestP88Quests] ";

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p88")) return;
        System.out.println(T + "armed wolfWhistle=" + QuestWolfWhistle.ON + " p88Fix=" + P88.ON);
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 100) return false;
                try { go(); } catch (Throwable x) { System.out.println(T + "RESULT FAIL setup " + x); x.printStackTrace(System.out); System.out.println(T + "DONE"); }
                return true;
            }
        });
    }

    static AIPlayer bot(Location at, content.data.Quests q, int stage, long coins) {
        AIPlayer b = new AIPlayer(at);
        try { b.getInventory().clear(); b.getBank().clear(); b.getEquipment().clear(); } catch (Throwable ignored) { }
        SmartAdventurers.finishTutorial(b);
        QuestBook.q(b).test = true;
        if (coins > 0) Ledger.Wallet.give(b, coins);
        b.getQuestRepository().setStageNonmonotonic(b.getQuestRepository().getQuest(q), stage);
        return b;
    }

    static void fill(Player p) { while (p.getInventory().freeSlots() > 0) p.getInventory().add(new Item(1511, 1)); } // logs: a live pack is full of loot

    /** Live ghost-quest bots are past level 15 HP (the quest's gate) and fight; the fresh test bot would die to the skeleton. */
    static void fighter(Player p) { for (int sk : new int[]{0, 1, 2, 3}) { p.getSkills().setStaticLevel(sk, 40); p.getSkills().setLevel(sk, 40); } }

    static String tag(int i, boolean[] w) { return w[i] ? "w" + (i == 5 ? 3 : i) : "g" + (i - 3); }

    static int st(Player p, content.data.Quests q) { return p.getQuestRepository().getStage(q); }

    static void go() {
        final QuestScript wolf = QuestBook.SCRIPTS.get(QuestWolfWhistle.SLUG), ghost = QuestBook.SCRIPTS.get("restlessghost");
        if (wolf == null || ghost == null) { System.out.println(T + "RESULT FAIL no script wolf " + (wolf != null) + " ghost " + (ghost != null)); System.out.println(T + "DONE"); return; }
        final int BONES = QuestWolfWhistle.BONES, GOLD = QuestWolfWhistle.GOLD, EMPTY = QuestWolfWhistle.EMPTY, SHARDS = QuestWolfWhistle.SHARDS, KEY = QuestWolfWhistle.KEY;
        final content.data.Quests WQ = QuestWolfWhistle.Q, GQ = QuestRestlessGhost.Q;
        Location pk = QuestWolfWhistle.PIKK_AT;
        final Player[] b = new Player[6];
        final boolean[] isWolf = {true, true, true, false, false, true};
        b[0] = bot(Location.create(2926, 3444, 0), WQ, 40, 500);
        for (Item it : new Item[]{new Item(BONES, 2), new Item(GOLD, 2), new Item(EMPTY, 2), new Item(SHARDS, 14), new Item(KEY, 1)}) b[0].getInventory().add(it);
        fill(b[0]);
        b[1] = bot(pk.transform(-1, 0, 0), WQ, 40, 1000);
        b[1].getBank().add(new Item(BONES, 2)); b[1].getBank().add(new Item(KEY, 1));
        b[2] = bot(pk.transform(1, 0, 0), WQ, 40, 0);
        b[2].getBank().add(new Item(BONES, 2)); b[2].getBank().add(new Item(GOLD, 2)); b[2].getBank().add(new Item(KEY, 1));
        b[5] = bot(pk.transform(0, 1, 0), WQ, 40, 1000);
        for (Item it : new Item[]{new Item(BONES, 2), new Item(GOLD, 2), new Item(EMPTY, 2), new Item(SHARDS, 14), new Item(KEY, 1)}) b[5].getBank().add(it);
        fill(b[5]);
        Location tower = QuestRestlessGhost.TOWER;
        b[3] = bot(tower, GQ, 30, 2000); fighter(b[3]);
        b[3].getEquipment().replace(new Item(QuestRestlessGhost.AMULET, 1), QuestRestlessGhost.NECK);
        fill(b[3]);
        b[4] = bot(tower.transform(1, 0, 0), GQ, 40, 2000); fighter(b[4]);
        b[4].getEquipment().replace(new Item(QuestRestlessGhost.AMULET, 1), QuestRestlessGhost.NECK);
        for (int i = 0; i < b.length; i++) {
            QuestRunner.start(b[i], isWolf[i] ? wolf : ghost, 0, true);
            System.out.println(T + tag(i, isWolf) + " " + b[i].getUsername() + " stage " + st(b[i], isWolf[i] ? WQ : GQ) + " at " + b[i].getLocation() + " free " + b[i].getInventory().freeSlots());
        }

        // trapdoor probe: finished bots (varp 28893, the open form) on each tile around 2927,3444 click Climb-down once
        final int[][] tiles = {{2926, 3444}, {2927, 3443}, {2928, 3444}, {2927, 3445}, {2926, 3443}, {2928, 3443}, {2926, 3445}, {2928, 3445}};
        final Player[] pr = new Player[tiles.length];
        if (Boolean.parseBoolean(System.getProperty("test.p88.probe", "false"))) {
            for (int i = 0; i < tiles.length; i++) {
                pr[i] = bot(Location.create(tiles[i][0], tiles[i][1], 0), WQ, 100, 0);
                try { core.api.ContentAPIKt.setVarp(pr[i], 1178, 28893, true); } catch (Throwable ignored) { }
            }
            try {
                core.game.node.scenery.Scenery w = core.game.world.map.RegionManager.getObject(0, 2927, 3444);
                System.out.println(T + "probe wrapper " + (w == null ? "none" : w.getId() + " type " + w.getType() + " rot " + w.getRotation() + " size " + w.getDefinition().getSizeX() + "x" + w.getDefinition().getSizeY()));
            } catch (Throwable x) { System.out.println(T + "probe wrapper error " + x); }
        }

        final long cap = Integer.getInteger("test.p88.ticks", 4000);
        GameWorld.getPulser().submit(new Pulse(5) {
            long ticks; final String[] res = new String[b.length]; boolean probed, probeDone;
            @Override public boolean pulse() {
                ticks += 5;
                if (pr[0] != null && !probed && ticks >= 10) {
                    probed = true;
                    for (Player p : pr) {
                        core.game.node.scenery.Scenery s = QuestSteps.findScenery(p, QuestWolfWhistle.TRAPDOOR_AT, 2, new int[]{QuestWolfWhistle.TRAPDOOR_OPEN}, null, "Climb-down");
                        boolean c = s != null && QuestSteps.click(p, s, "Climb-down", core.game.interaction.IntType.SCENERY);
                        System.out.println(T + "probe click from " + p.getLocation() + " found " + (s != null) + " clicked " + c);
                    }
                }
                if (probed && !probeDone && ticks >= 40) {
                    probeDone = true;
                    for (int i = 0; i < pr.length; i++) System.out.println(T + "probe " + tiles[i][0] + "," + tiles[i][1] + " -> " + pr[i].getLocation() + (QuestWolfWhistle.inCellar(pr[i]) ? " DOWN" : " STUCK"));
                }
                boolean all = true;
                for (int i = 0; i < b.length; i++) {
                    if (res[i] != null) continue;
                    Player p = b[i];
                    boolean active = QuestRunner.active(p);
                    int s = st(p, isWolf[i] ? WQ : GQ);
                    if (isWolf[i] ? s >= 50 : s >= 100 && !active) res[i] = "PASS stage " + s + " tick " + ticks;
                    else if (!active || ticks >= cap) res[i] = "FAIL active " + active + " stage " + s + " at " + p.getLocation() + " tick " + ticks;
                    if (res[i] != null) System.out.println(T + tag(i, isWolf) + " " + res[i]);
                    else all = false;
                }
                if (!all || pr[0] != null && !probeDone) return false;
                int pass = 0; StringBuilder sb = new StringBuilder();
                for (int i = 0; i < b.length; i++) { boolean ok = res[i].startsWith("PASS"); if (ok) pass++; sb.append(' ').append(tag(i, isWolf)).append(ok ? " PASS" : " FAIL"); }
                System.out.println(T + "RESULT " + (pass == b.length ? "PASS" : "FAIL") + " " + pass + "/" + b.length + sb + " p88Fix=" + P88.ON);
                System.out.println(T + "DONE");
                return true;
            }
        });
    }
}
