package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Trial only (-Dtest.b17b=true, with -Dquests.only=true so the quest share cap doesn't hide eligibility): batch 17 items 1-3.
 * E: QuestBook.eligible(bot) contains Shield of Arrav? for a Black Arm-parity bot and a Phoenix-parity bot under each
 * condition (bot stock map, Arrav pool and the active-runner list set by hand for one check each, restored after; wallet
 * coins set in the bank). Before: always eligible. After: Black Arm only with key/2 crossbows/Phoenix bot at 60+, and every
 * start needs 50 gp. F: bowsFromStock with a pack full of kept items (28 lobsters, its runner registered so they are kept)
 * and 4 crossbows supplied to bot stock: before, coins and stock go and no crossbow arrives; after, nothing is bought.
 */
public final class TestB17B implements StartupListener {
    static void log(String s) { System.out.println("[TestB17B] " + s); }
    static final Location LUM = Location.create(3222, 3218, 0);

    static AIPlayer bot(boolean phoenix) {
        for (int i = 0; i < 40; i++) {
            AIPlayer b = new AIPlayer(LUM);
            SmartAdventurers.finishTutorial(b);
            if ((Math.floorMod(b.getUsername().toLowerCase(Locale.ROOT).hashCode(), 2) == 0) != phoenix) continue;
            for (int sk : new int[]{Skills.HITPOINTS, Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE}) { b.getSkills().setStaticLevel(sk, 40); b.getSkills().setLevel(sk, 40); }
            b.getSkills().setLifepoints(40);
            try { b.getSkills().updateCombatLevel(); } catch (Throwable ignored) { }
            return b;
        }
        return null;
    }
    static void coins(Player p, int n) {
        p.getInventory().remove(new Item(995, p.getInventory().getAmount(995)));
        p.getBank().remove(new Item(995, p.getBank().getAmount(995)));
        if (n > 0) p.getBank().add(new Item(995, n));
    }
    static void stock(int key, int bows) { Map<Integer, Integer> m = new HashMap<>(MarketBrain.stock); m.put(759, key); m.put(767, bows); MarketBrain.stock = m; }
    static void pool(int key, int bows) { QuestShieldOfArrav.Pool.count(759); QuestShieldOfArrav.Pool.N.put(759, key); QuestShieldOfArrav.Pool.N.put(767, bows); }
    static boolean elig(Player p) { for (QuestScript s : QuestBook.eligible(p)) if (s.quest == content.data.Quests.SHIELD_OF_ARRAV) return true; return false; }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.b17b")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t; AIPlayer ba, ph, ph2; QuestScript soa; QuestRunner fr; int w0, s0;
            @Override public boolean pulse() {
                try {
                    if (++t < 120) return false;
                    if (t == 120) {
                        soa = QuestBook.SCRIPTS.get("shieldofarrav");
                        ba = bot(false); ph = bot(true); ph2 = bot(true);
                        log("bots: Black Arm " + ba.getUsername() + ", Phoenix " + ph.getUsername() + ", Phoenix partner " + ph2.getUsername() + ", script " + (soa != null) + " enabled " + (soa != null && soa.enabled) + ", active " + ba.isActive());
                        Map<Integer, Integer> st0 = MarketBrain.stock; int pk = QuestShieldOfArrav.Pool.count(759), pb = QuestShieldOfArrav.Pool.count(767);
                        log("real stock: key " + MarketBrain.stockOf(759) + ", crossbows " + MarketBrain.stockOf(767) + "; pool key " + pk + ", crossbows " + pb);
                        Object[][] cases = {
                            // name, bot, wallet, stockKey, stockBows, poolKey, poolBows, phoenixAt (-1 none), ownKey
                            {"BA none", ba, 1000, 0, 0, 0, 0, -1, false},
                            {"BA key in stock", ba, 1000, 1, 0, 0, 0, -1, false},
                            {"BA 2 crossbows in stock", ba, 1000, 0, 2, 0, 0, -1, false},
                            {"BA 1 crossbow in stock", ba, 1000, 0, 1, 0, 0, -1, false},
                            {"BA key in pool", ba, 1000, 0, 0, 1, 0, -1, false},
                            {"BA 2 crossbows in pool", ba, 1000, 0, 0, 0, 2, -1, false},
                            {"BA Phoenix bot at stage 60", ba, 1000, 0, 0, 0, 0, 60, false},
                            {"BA Phoenix bot at stage 50", ba, 1000, 0, 0, 0, 0, 50, false},
                            {"BA own key in bank", ba, 1000, 0, 0, 0, 0, -1, true},
                            {"BA key in stock, coins 0", ba, 0, 1, 0, 0, 0, -1, false},
                            {"BA key in stock, coins 30", ba, 30, 1, 0, 0, 0, -1, false},
                            {"PH coins 1000", ph, 1000, 0, 0, 0, 0, -1, false},
                            {"PH coins 0", ph, 0, 0, 0, 0, 0, -1, false},
                            {"PH coins 30 (bank)", ph, 30, 0, 0, 0, 0, -1, false},
                            {"PH coins 50 (bank)", ph, 50, 0, 0, 0, 0, -1, false},
                        };
                        for (Object[] c : cases) {
                            Player p = (Player) c[1];
                            coins(p, (Integer) c[2]); stock((Integer) c[3], (Integer) c[4]); pool((Integer) c[5], (Integer) c[6]);
                            if ((Boolean) c[8]) p.getBank().add(new Item(759, 1));
                            QuestRunner pr = null;
                            if ((Integer) c[7] >= 0) {
                                ph2.getQuestRepository().setStageNonmonotonic(ph2.getQuestRepository().getQuest(content.data.Quests.SHIELD_OF_ARRAV), (Integer) c[7]);
                                pr = new QuestRunner(ph2, soa, 0, true); QuestRunner.ACTIVE.put(QuestRunner.key(ph2), pr);
                            }
                            boolean e = elig(p);
                            log("RESULT E " + c[0] + ": eligible " + e + " (wallet " + Ledger.Wallet.coins(p) + ")");
                            if (pr != null) QuestRunner.ACTIVE.remove(QuestRunner.key(ph2));
                            if ((Boolean) c[8]) p.getBank().remove(new Item(759, 1));
                        }
                        MarketBrain.stock = st0; QuestShieldOfArrav.Pool.N.put(759, pk); QuestShieldOfArrav.Pool.N.put(767, pb);
                        // F: crossbows into bot stock for the full-pack test
                        Ledger.supply(null, 767, 4);
                        return false;
                    }
                    if (t == 160) {
                        coins(ba, 20000);
                        ba.getInventory().clear();
                        for (int i = 0; i < 28; i++) ba.getInventory().add(new Item(379, 1));
                        fr = new QuestRunner(ba, soa, 0, true); QuestRunner.ACTIVE.put(QuestRunner.key(ba), fr);
                        Map<Integer, Integer> m = new HashMap<>(MarketBrain.stock); m.put(767, Math.max(4, m.getOrDefault(767, 0))); MarketBrain.stock = m;
                        w0 = (int) Ledger.Wallet.coins(ba); s0 = core.game.ge.GrandExchange.Companion.getBotstockForId(767);
                        log("F before: free slots " + ba.getInventory().freeSlots() + ", wallet " + w0 + ", GE bot stock crossbows " + s0 + ", bot crossbows " + QuestShieldOfArrav.count(ba, 767));
                        QuestSteps.St st = QuestShieldOfArrav.bowsFromStock(fr);
                        int w1 = (int) Ledger.Wallet.coins(ba);
                        log("RESULT F full pack bowsFromStock: " + st + ", wallet " + w0 + " -> " + w1 + " (paid " + (w0 - w1) + "), bot crossbows pack " + ba.getInventory().getAmount(767) + " bank " + ba.getBank().getAmount(767)
                            + ", reserved takes " + MarketBrain.reservedTakes(767) + (w0 - w1 > 0 && QuestShieldOfArrav.count(ba, 767) == 0 ? " LOST (paid, no crossbows)" : " KEPT (nothing lost)"));
                        QuestRunner.ACTIVE.remove(QuestRunner.key(ba));
                        // F2: the same with room in the pack (control: it still buys)
                        ba.getInventory().clear();
                        w0 = (int) Ledger.Wallet.coins(ba);
                        Map<Integer, Integer> m2 = new HashMap<>(MarketBrain.stock); m2.put(767, Math.max(4, m2.getOrDefault(767, 0))); MarketBrain.stock = m2;
                        QuestRunner.ACTIVE.put(QuestRunner.key(ba), fr);
                        st = QuestShieldOfArrav.bowsFromStock(fr);
                        QuestRunner.ACTIVE.remove(QuestRunner.key(ba));
                        log("RESULT F2 empty pack bowsFromStock: " + st + ", paid " + (w0 - Ledger.Wallet.coins(ba)) + ", bot crossbows pack " + ba.getInventory().getAmount(767) + " bank " + ba.getBank().getAmount(767));
                        log("DONE");
                        return true;
                    }
                } catch (Throwable e) { log("error " + e); e.printStackTrace(System.out); log("DONE"); return true; }
                return false;
            }
        });
    }
}
