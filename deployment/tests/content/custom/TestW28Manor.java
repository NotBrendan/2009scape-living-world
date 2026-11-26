package content.custom;

import core.game.world.map.Location;

import java.util.List;

/**
 * Plain-java unit (no world): W28 -Dquest.ernestManor (default off).
 * Off: the Ernest step list is the train-73 one (pinned signature: every step's class, name, limit and plan).
 * On: step 2 is a chain (front doors, hall door, stairs) under the same name and number; the fish food plan starts with
 * the landing walk and the landing door; the oil can and tube plans start with the front doors. The room tests and
 * the soft-leg fallback (review change 1) are checked either way.
 * java -cp out;server.jar [-Dquest.ernestManor=true] content.custom.TestW28Manor
 */
public final class TestW28Manor {
    static int bad;
    static void ok(boolean c, String what) { if (!c) { bad++; System.out.println("RESULT FAIL " + what); } }

    static String sig(List<QuestSteps.Step> steps, String pad) {
        StringBuilder b = new StringBuilder();
        for (QuestSteps.Step s : steps) {
            b.append(pad).append(s.getClass().getSimpleName()).append('|').append(s.name).append('|').append(s.limitTicks).append('|').append(s.done != null).append('|').append(s.recheck).append('\n');
            if (s instanceof QuestSteps.Collect) {
                QuestSteps.Collect c = (QuestSteps.Collect) s;
                b.append(sig(c.plan, pad + "  "));
                if (c.buy != null) b.append(pad).append("  buy ").append(c.buy.name).append('\n');
            }
        }
        return b.toString();
    }

    static QuestSteps.Collect collect(List<QuestSteps.Step> steps, String name) {
        for (QuestSteps.Step s : steps) if (s.name.equals(name) && s instanceof QuestSteps.Collect) return (QuestSteps.Collect) s;
        return null;
    }

    public static void main(String[] a) throws Exception {
        Class.forName("content.custom.AfkTasks"); // the outer class first, as the server loads it
        boolean on = QuestErnestTheChicken.MANOR_FIX;
        ok(on == Boolean.getBoolean("quest.ernestManor"), "flag reads quest.ernestManor");

        QuestScript e = QuestErnestTheChicken.build();
        String sg = sig(e.steps, "") + "exit\n" + sig(e.exit, "");
        if (a.length > 0 && a[0].equals("sig")) System.out.print(sg);
        int h = sg.replace("\r", "").hashCode();
        boolean ef = QuestErnestTheChicken.FIX;
        if (!on) ok(h == (ef ? BASE_FIX : BASE), "flags-off signature " + h + " (ernestFix " + ef + ") is not train-73's");
        else ok(h != BASE && h != BASE_FIX, "flag on changes the plan");

        // the numbering is the same either way
        ok(e.steps.size() == 19, "19 steps, got " + e.steps.size());
        QuestSteps.Step up = e.steps.get(2);
        ok(up.name.equals("up to Professor Oddenstein") && up.done != null && up.limitTicks == QuestSteps.WALK_LIMIT, "step 2 name, checkpoint, limit");
        ok(on ? up instanceof QuestSteps.Custom : up instanceof QuestSteps.Walk, "step 2 is a " + up.getClass().getSimpleName());

        QuestSteps.Collect poisoned = collect(e.steps, "get poisoned fish food");
        QuestSteps.Collect food = poisoned == null ? null : collect(poisoned.plan, "get the fish food");
        ok(food != null, "fish food plan found");
        if (food != null) {
            ok(food.plan.size() == (on ? 4 : 2), "fish food plan size " + food.plan.size());
            int n = food.plan.size();
            ok(food.plan.get(n - 2).name.equals("walk to the fish food upstairs") && food.plan.get(n - 1).name.equals("pick up the fish food"), "fish food plan ends as before");
            if (on) ok(food.plan.get(0).name.equals("walk to the landing door upstairs") && food.plan.get(1).name.equals("through the landing door"), "fish food plan starts at the landing");
        }
        for (String nm : new String[]{"get the oil can", "get the rubber tube"}) {
            QuestSteps.Collect c = collect(e.steps, nm);
            ok(c != null, nm + " found");
            if (c == null) continue;
            ok(c.plan.get(on ? 1 : 0).name.equals("walk into the manor hall"), nm + ": hall walk in place");
            ok(on == c.plan.get(0).name.equals("in by the front doors"), nm + ": starts with " + c.plan.get(0).name);
        }

        // review change 1: the new legs are soft (fail or time out -> done, the old walk after them still runs)
        if (on && food != null) ok(food.plan.get(0).limitTicks == QuestErnestTheChicken.SOFT_CAP + 1 && food.plan.get(1).limitTicks == QuestErnestTheChicken.SOFT_CAP + 1 && food.plan.get(0).done != null && food.plan.get(1).done != null, "landing legs are soft, with their checkpoints");
        final int[] ran = {0, 0};
        QuestSteps.Step failing = new QuestSteps.Custom("leg", 50, r -> ran[0]++, r -> QuestSteps.St.FAILED);
        QuestSteps.Step never = new QuestSteps.Custom("slow leg", 800, null, r -> QuestSteps.St.RUNNING);
        QuestSteps.Step old = new QuestSteps.Custom("old walk", 50, null, r -> { ran[1]++; return QuestSteps.St.DONE; });
        QuestSteps.Step sf = QuestErnestTheChicken.soft(failing, 10);
        sf.begin(null);
        ok(ran[0] == 1 && sf.tick(null) == QuestSteps.St.DONE, "a failed soft leg counts as done");
        ok(sf.name.equals("leg") && sf.limitTicks == 11, "soft keeps the name, limit is cap + 1");
        QuestSteps.Step sn = QuestErnestTheChicken.soft(never, 5);
        sn.begin(null);
        boolean run4 = true; for (int k = 0; k < 4; k++) run4 &= sn.tick(null) == QuestSteps.St.RUNNING;
        ok(run4 && sn.tick(null) == QuestSteps.St.DONE, "a soft leg is done at its cap, before the outer limit");
        sn.begin(null);
        ok(sn.tick(null) == QuestSteps.St.RUNNING, "begin resets the cap count");
        ok(QuestErnestTheChicken.soft(old, 5).tick(null) == QuestSteps.St.DONE && ran[1] == 1, "a soft leg that works is just done");
        ran[1] = 0;
        QuestSteps.Step ch = QuestErnestTheChicken.chain("c", QuestErnestTheChicken.soft(failing, 10), QuestErnestTheChicken.soft(never, 3), old);
        ch.begin(null);
        QuestSteps.St last = QuestSteps.St.RUNNING; int ticks = 0;
        while (last == QuestSteps.St.RUNNING && ticks++ < 50) last = ch.tick(null);
        ok(last == QuestSteps.St.DONE && ran[1] == 1 && ticks <= 8, "chain: after a failed and a timed-out soft leg the old walk still runs (" + last + ", " + ticks + " ticks)");
        QuestSteps.Step hard = QuestErnestTheChicken.chain("c2", failing, old);
        hard.begin(null); ran[1] = 0;
        ok(hard.tick(null) == QuestSteps.St.FAILED && ran[1] == 0 && String.valueOf(hard.failure).startsWith("leg: "), "chain: a hard failure names its leg (" + hard.failure + ")");

        // rooms, against the cache map (walls x 3105/3113/3120, front doors on the y 3353/3354 edge, door 3116,3361,1)
        ok(QuestErnestTheChicken.outside(QuestErnestTheChicken.VERONICA_AT), "Veronica is outside");
        ok(QuestErnestTheChicken.outside(QuestErnestTheChicken.FRONT_OUT), "the front step is outside");
        ok(!QuestErnestTheChicken.outside(Location.create(3109, 3354, 0)), "the hall is inside");
        ok(QuestErnestTheChicken.outside(Location.create(3123, 3361, 0)), "north of the east door is outside");
        ok(!QuestErnestTheChicken.outside(Location.create(3123, 3360, 0)), "the east wing is inside");
        ok(!QuestErnestTheChicken.outside(Location.create(3120, 3359, 0)), "the spade is inside");
        ok(!QuestErnestTheChicken.outside(Location.create(3093, 3360, 0)), "the secret room is inside");
        ok(!QuestErnestTheChicken.outside(Location.create(3111, 3370, 0)), "the north room is inside");
        ok(QuestErnestTheChicken.outside(Location.create(3087, 3334, 0)), "the fountain is outside");
        ok(!QuestErnestTheChicken.outside(Location.create(3117, 9753, 0)), "the basement is not outside");
        ok(!QuestErnestTheChicken.outside(Location.create(3108, 3356, 1)) && !QuestErnestTheChicken.outside(QuestErnestTheChicken.ODDENSTEIN_AT), "upstairs is not outside");
        ok(QuestErnestTheChicken.inSouthUp(QuestErnestTheChicken.FISH_FOOD_AT) && QuestErnestTheChicken.inSouthUp(QuestErnestTheChicken.SOUTH_UP), "fish food and the south-east room are south of the door");
        ok(QuestErnestTheChicken.inSouthUp(Location.create(3116, 3361, 1)), "the door tile is south");
        ok(!QuestErnestTheChicken.inSouthUp(QuestErnestTheChicken.LANDING_UP) && !QuestErnestTheChicken.inSouthUp(Location.create(3116, 3362, 1)), "the landing is north of the door");
        ok(!QuestErnestTheChicken.inSouthUp(Location.create(3106, 3356, 1)), "the west room (stuck tile) is not south-east");
        ok(!QuestErnestTheChicken.inSouthUp(Location.create(3108, 3356, 0)), "ground floor is not upstairs");
        ok(QuestErnestTheChicken.LANDING_UP.getDistance(Location.create(3116, 3361, 1)) <= 3, "the landing is within door reach (3)");

        System.out.println("[TestW28Manor] ernestManor " + on + " ernestFix " + ef + ": " + e.steps.size() + " steps, sig " + h + ", " + (bad == 0 ? "PASS" : "FAIL " + bad));
    }

    /** sig hash of train-73 (c0b8dfc) with -Dquest.ernestFix off / on, taken from that build with this class's sig(). */
    static final int BASE = 17637683, BASE_FIX = -742236579;
}
