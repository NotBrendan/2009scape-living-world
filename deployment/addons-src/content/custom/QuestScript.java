package content.custom;

import core.game.node.entity.player.Player;
import core.game.node.entity.player.link.quest.Quest;
import core.game.world.map.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * One quest a bot can do, written as a list of steps (QuestSteps), with what it needs and what it pays.
 * Requirements are checked here, never with Quest.hasRequirements (several quests in this server return true for anyone).
 */
final class QuestScript {
    final content.data.Quests quest;
    final String slug, display;
    int estMinutes = 15;
    int coins;                  // coins the server pays on completion
    int[] rewardItems = {};     // items it hands out (valued at guide price for the weight)
    double usefulXp;            // XP that counts for the weight (in skills a bot uses)
    double unlockValue;         // what finishing it opens up (a shop, a route), in gp terms
    int[][] levels = {};        // {skill, level}
    int minQp;
    content.data.Quests[] prereqs = {};
    Location start;             // where the giver stands (caps count bots near here)
    int maxNear = 2;            // bots doing it at once within 30 tiles of the start
    boolean enabled = true;
    java.util.function.Supplier<QuestScript> factory; // builds fresh steps for each run (QuestRunner)
    final List<QuestSteps.Step> steps = new ArrayList<>();
    final List<QuestSteps.Step> exit = new ArrayList<>(); // after the reward: back down the tower, out of the mill (failures here cost nothing)

    QuestScript(content.data.Quests quest, String slug, String display) { this.quest = quest; this.slug = slug; this.display = display; }

    static QuestScript of(content.data.Quests q, String slug, String display) { return new QuestScript(q, slug, display); }

    QuestScript minutes(int m) { estMinutes = m; return this; }
    QuestScript coins(int c) { coins = c; return this; }
    QuestScript items(int... ids) { rewardItems = ids; return this; }
    QuestScript xp(double x) { usefulXp = x; return this; }
    QuestScript unlock(double v) { unlockValue = v; return this; }
    QuestScript level(int skill, int lvl) { int[][] n = java.util.Arrays.copyOf(levels, levels.length + 1); n[levels.length] = new int[]{skill, lvl}; levels = n; return this; }
    QuestScript qp(int q) { minQp = q; return this; }
    QuestScript after(content.data.Quests... q) { prereqs = q; return this; }
    QuestScript startAt(int x, int y, int z) { start = Location.create(x, y, z); return this; }
    QuestScript near(int n) { maxNear = n; return this; }
    QuestScript step(QuestSteps.Step s) { steps.add(s); return this; }
    QuestScript exit(QuestSteps.Step s) { exit.add(s); return this; }

    static java.util.function.Predicate<Player> stageAtLeast(content.data.Quests q, int n) { return p -> p.getQuestRepository().getStage(q) >= n; }
    static java.util.function.Predicate<Player> has(int id, int n) { return p -> p.getInventory().getAmount(id) >= n; }
    static java.util.function.Predicate<Player> onFloor(int z) { return p -> p.getLocation().getZ() == z; }
    static boolean attr(Player p, String key) { Object v = p.getAttribute(key, null); return v instanceof Boolean ? (Boolean) v : v instanceof Number && ((Number) v).intValue() != 0; }
    static int attrInt(Player p, String key) { Object v = p.getAttribute(key, null); return v instanceof Number ? ((Number) v).intValue() : 0; }

    Quest quest(Player p) { try { return p.getQuestRepository().getQuest(quest); } catch (Throwable t) { return null; } }

    int stage(Player p) { try { return p.getQuestRepository().getStage(quest); } catch (Throwable t) { return 0; } }

    boolean complete(Player p) { Quest q = quest(p); return q != null && q.isCompleted(p); }

    /** Levels, quest points and earlier quests, checked by hand. */
    boolean requirementsMet(Player p) {
        for (int[] l : levels) if (p.getSkills().getStaticLevel(l[0]) < l[1]) return false;
        try { if (minQp > 0 && p.getQuestRepository().getPoints() < minQp) return false; } catch (Throwable t) { return false; }
        for (content.data.Quests q : prereqs) { try { if (!p.getQuestRepository().isComplete(q)) return false; } catch (Throwable t) { return false; } }
        return true;
    }

    int itemValue() { int v = 0; for (int id : rewardItems) v += Math.max(0, MarketBrain.value(id)); return v; }
}
