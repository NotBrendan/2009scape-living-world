package content.custom;

import core.game.world.map.Location;

import java.util.Map;

/**
 * W13a engine side (W13aSkills holds the flags and the pure rules). Every caller sits behind its flag and a try/catch, so with
 * the flags off nothing here runs.
 */
final class W13aHooks {
    private W13aHooks() { }

    /** -Dbots.wc.trees2: append the reachable willow / yew spots to the WILLOW and YEW rows (existing spots keep their order). */
    static void trees2(Map<AfkTasks.Task, AfkTasks.Spot[]> spots) {
        int added = 0;
        for (int i = 0; i < W13aSkills.TREE_SPOTS.length; i++) {
            int[] s = W13aSkills.TREE_SPOTS[i];
            AfkTasks.Task t = s[0] == W13aSkills.KIND_YEW ? AfkTasks.Task.YEW : AfkTasks.Task.WILLOW;
            AfkTasks.Spot[] old = spots.get(t);
            int n = old == null ? 0 : old.length;
            AfkTasks.Spot[] nu = new AfkTasks.Spot[n + 1];
            if (old != null) System.arraycopy(old, 0, nu, 0, n);
            nu[n] = AfkTasks.sp(W13aSkills.TREE_NAMES[i], s[1], s[2]);
            spots.put(t, nu);
            added++;
        }
        System.out.println("[W13a] trees2: " + added + " willow/yew spots added (yew " + spots.get(AfkTasks.Task.YEW).length + ", willow " + spots.get(AfkTasks.Task.WILLOW).length + ")");
    }

    /** -Dbots.craft.spin: the ground-floor wheel the world map lists. */
    static Location wheel() { return Location.create(W13aSkills.WHEEL_AT[0], W13aSkills.WHEEL_AT[1], 0); }
}
