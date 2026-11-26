package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.GroundItem;
import core.game.node.item.GroundItemManager;
import core.game.node.scenery.Scenery;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;

/**
 * Trial only, never shipped (P59). -Dtest.p59=true -Dquest.wolfWhistle=true: -Dtest.p59.bots (1) fresh bots spawned in
 * Falador do Wolf Whistle from stage 0. PASS per bot = stage 100, quest complete (summoning unlocked), no familiar out,
 * runner finished. Whole trial PASS = all bots PASS and no quest item on the ground near Taverley or in the cellar.
 * Also prints the tiles of the quest's scenery (dead body, ladders, trapdoor, obelisks) from the loaded regions.
 * Cap: -Dtest.p59.min (40).
 */
public final class TestP59WolfTrial implements StartupListener {
    static final String T = "[TestP59WolfTrial] ";
    static final int[] ITEMS = {2859, 12047, 12425, 12528, 12158, 12155, 12183};

    @Override public void startup() {
        if (!Boolean.getBoolean("test.p59")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t;
            @Override public boolean pulse() {
                if (++t < 100) return false;
                try { scan(); scanBody(); go(); } catch (Throwable x) { System.out.println(T + "RESULT FAIL setup " + x); x.printStackTrace(System.out); System.out.println(T + "DONE"); }
                return true;
            }
        });
    }

    static void scan() {
        int[] want = {28586, 28572, 28675, 28676, 28714, 28653, 28716, 28719, 28722, 28725, 28278, 28731, 28734};
        for (int id : new int[]{11573, 11574, 11317, 11829, 8787, 8786, 8531, 8530}) {
            try {
                core.game.world.map.Region r = RegionManager.forId(id);
                core.game.world.map.Region.load(r);
                for (int z = 0; z < 4; z++) {
                    int n = 0;
                    for (Scenery s : r.getPlanes()[z].getObjectList()) {
                        if (s == null) continue; n++;
                        for (int w : want) if (s.getId() == w) System.out.println(T + "scan " + w + " at " + s.getLocation() + " (region " + id + ")");
                        try { if (s.getChilds() != null) for (Scenery ch : s.getChilds()) if (ch != null) for (int w : want) if (ch.getId() == w) System.out.println(T + "scan child " + w + " of " + s.getId() + " at " + s.getLocation()); } catch (Throwable ignored) { }
                    }
                    if (n > 0) System.out.println(T + "scan region " + id + " z " + z + " objects " + n);
                }
            } catch (Throwable x) { System.out.println(T + "scan region " + id + " failed " + x); }
        }
    }

    /** -Dtest.p59.scanAll: the dead body 28586 anywhere from Falador to Catherby, overworld and dungeons. */
    static void scanBody() {
        if (!Boolean.getBoolean("test.p59.scanAll")) return;
        int found = 0, regions = 0;
        for (int rx = 40; rx <= 50; rx++) for (int ry : new int[]{48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 80, 81, 82, 83, 84, 85, 86, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155}) {
            try {
                core.game.world.map.Region r = RegionManager.forId((rx << 8) | ry);
                core.game.world.map.Region.load(r);
                regions++;
                for (int z = 0; z < 4; z++) for (Scenery s : r.getPlanes()[z].getObjectList()) {
                    if (s == null) continue;
                    boolean hit = s.getId() == 28586;
                    try { if (s.getChilds() != null) for (Scenery ch : s.getChilds()) if (ch != null && ch.getId() == 28586) hit = true; } catch (Throwable ignored) { }
                    if (hit) { found++; System.out.println(T + "scanAll body 28586 at " + s.getLocation()); }
                }
            } catch (Throwable ignored) { }
        }
        System.out.println(T + "scanAll " + regions + " regions, body found " + found);
    }

    static int stage(Player p) { return p.getQuestRepository().getStage(QuestWolfWhistle.Q); }
    static int held(Player p, int id) { return p.getInventory().getAmount(id) + p.getBank().getAmount(id); }

    static void go() {
        QuestScript ws = QuestBook.SCRIPTS.get(QuestWolfWhistle.SLUG);
        if (ws == null) { System.out.println(T + "RESULT FAIL no script (flag quest.wolfWhistle=" + System.getProperty("quest.wolfWhistle") + ")"); System.out.println(T + "DONE"); return; }
        int n = Integer.getInteger("test.p59.bots", 1);
        final Player[] bots = new Player[n];
        Location fally = Location.create(2965, 3380, 0);
        for (int i = 0; i < n; i++) {
            AIPlayer b = new AIPlayer(fally.transform(i % 2, i / 2, 0));
            try { b.getInventory().clear(); b.getBank().clear(); b.getEquipment().clear(); } catch (Throwable ignored) { }
            SmartAdventurers.finishTutorial(b);
            QuestBook.q(b).test = true;
            Ledger.Wallet.give(b, 500);
            int lvl = Integer.getInteger("test.p59.level", 0); // the body route needs combat 25+ (white wolves)
            if (lvl > 0) { for (int sk : new int[]{Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.HITPOINTS}) { b.getSkills().setStaticLevel(sk, lvl); b.getSkills().setLevel(sk, lvl); } b.getSkills().updateCombatLevel(); }
            bots[i] = b;
            System.out.println(T + "bot " + i + " " + b.getUsername() + " stage " + stage(b) + " combat " + b.getProperties().getCurrentCombatLevel() + " summoning " + b.getSkills().getStaticLevel(Skills.SUMMONING));
            QuestRunner.start(b, ws, 0, true);
        }
        final long cap = Integer.getInteger("test.p59.min", 40) * 100L;
        GameWorld.getPulser().submit(new Pulse(5) {
            long ticks; final String[] res = new String[n];
            @Override public boolean pulse() {
                ticks += 5;
                boolean all = true;
                for (int i = 0; i < n; i++) {
                    if (res[i] != null) continue;
                    Player p = bots[i];
                    boolean active = QuestRunner.active(p);
                    boolean fam = QuestWolfWhistle.hasFamiliar(p);
                    if (stage(p) >= 100 && !active) {
                        boolean complete = p.getQuestRepository().isComplete(QuestWolfWhistle.Q);
                        res[i] = (complete && !fam ? "PASS" : "FAIL") + " tick " + ticks + " complete " + complete + " familiar " + fam + " summoning lvl " + p.getSkills().getStaticLevel(Skills.SUMMONING)
                                + " xp " + (int) p.getSkills().getExperience(Skills.SUMMONING) + " points " + p.getSkills().getLevel(Skills.SUMMONING) + " charms " + held(p, 12158) + " shards " + held(p, 12183)
                                + " howl " + held(p, 12425) + " pouch " + held(p, 12047) + " key " + held(p, 12528) + " bones " + held(p, 2859) + " body " + QuestScript.attr(p, "searched-body") + " at " + p.getLocation();
                    } else if (!active || ticks >= cap) res[i] = "FAIL active " + active + " stage " + stage(p) + " at " + p.getLocation() + " tick " + ticks + " familiar " + fam;
                    if (res[i] != null) System.out.println(T + "bot " + i + " " + p.getUsername() + " " + res[i]);
                    else all = false;
                }
                if (!all) return false;
                int ground = 0;
                for (GroundItem g : GroundItemManager.getItems()) {
                    if (g == null || g.getLocation() == null) continue;
                    boolean questItem = false; for (int id : ITEMS) if (g.getId() == id) questItem = true;
                    boolean near = g.getLocation().getZ() == 0 && g.getLocation().getDistance(QuestWolfWhistle.PIKK_AT) <= 40 || QuestWolfWhistle.inCellar(g.getLocation());
                    if (questItem && near) { ground++; System.out.println(T + "ground item " + g.getId() + " x" + g.getAmount() + " at " + g.getLocation()); }
                }
                boolean pass = ground == 0;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) { pass &= res[i].startsWith("PASS"); sb.append(" | ").append(i).append(' ').append(res[i], 0, Math.min(40, res[i].length())); }
                System.out.println(T + "RESULT " + (pass ? "PASS" : "FAIL") + " bots " + n + " ground " + ground + sb);
                System.out.println(T + "DONE");
                return true;
            }
        });
    }
}
