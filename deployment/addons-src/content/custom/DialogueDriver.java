package content.custom;

import core.game.component.Component;
import core.game.dialogue.DialogueInterpreter;
import core.game.dialogue.DialoguePlugin;
import core.game.dialogue.Topic;
import core.game.node.entity.player.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Clicks a bot through a server dialogue, the way the client's continue/option packet would
 * (PacketProcessor ContinueOption to DialogueInterpreter.handle(iface, child)).
 * Options screens are chatbox 224 + 2 * count (228, 230, 232, 234); option n is child n+1 (the plugin sees n).
 * Topic dialogues keep options in interpreter.activeTopics (matched by words). Continue screens: child 1. 137 = nothing open.
 */
public final class DialogueDriver {
    public enum Status { RUNNING, DONE, FAILED }

    static final int CHATBOX_DEFAULT = 137;
    static final int CONTINUE_CHILD = 1;

    final Player p;
    /** Each pick is an Integer (1-based option) or a String (words of a Topic option, or a fallback index after "|"). */
    final Deque<Object> picks = new ArrayDeque<>();
    final List<String> seen = new ArrayList<>();
    String failure;
    int clicks, idle, wait, opening, same; String lastWhere; // same/lastWhere: a continue screen repeating unchanged
    boolean started;
    final int maxClicks;
    /** Ticks to wait for the conversation to open: talk-to first walks the bot up to the NPC. */
    static final int OPEN_TICKS = 30;

    public DialogueDriver(Player p, Object... picks) {
        this.p = p;
        for (Object o : picks) this.picks.add(o);
        this.maxClicks = 80;
    }

    public static boolean open(Player p) {
        return p.getDialogueInterpreter().getDialogue() != null || chatboxId(p) > 0;
    }

    static int chatboxId(Player p) {
        Component c = p.getInterfaceManager().getChatbox();
        if (c == null || c.getId() == CHATBOX_DEFAULT) return -1;
        return c.getId();
    }

    static boolean isOptions(int id) { return id == 228 || id == 230 || id == 232 || id == 234; }

    /** One tick. Clicks once every 2 to 4 ticks so a watching player sees a normal pause. */
    public Status tick() {
        if (failure != null) return Status.FAILED;
        DialogueInterpreter di = p.getDialogueInterpreter();
        DialoguePlugin d = di.getDialogue();
        int box = chatboxId(p);
        if (d == null && !started) {
            if (++opening > OPEN_TICKS) return fail("dialogue never opened");
            return Status.RUNNING;
        }
        started = true;
        if (d == null) {
            if (box > 0) { // a plain message box with no plugin behind it
                p.getInterfaceManager().closeChatbox();
            }
            return picks.isEmpty() ? Status.DONE : fail("dialogue ended with picks left " + picks + state());
        }
        if (wait > 0) { wait--; return Status.RUNNING; }
        if (++clicks > maxClicks) return fail("too many clicks");
        if (box < 0) {
            // Plugin set but nothing shown: either it is between screens or it has finished without closing.
            if (++idle > 6) { di.close(); return picks.isEmpty() ? Status.DONE : fail("no chatbox, picks left " + picks); }
            return Status.RUNNING;
        }
        idle = 0;
        String where = describe(d, box);
        try {
            if (isOptions(box) || !di.activeTopics.isEmpty()) {
                int count = !di.activeTopics.isEmpty() ? di.activeTopics.size() : (box - 224) / 2;
                Object pick = picks.poll();
                if (pick == null) return fail("options with no pick left at " + where + topics(di));
                int idx = resolve(pick, di, count);
                if (idx < 1 || idx > count) return fail("pick " + pick + " not among " + count + " at " + where + topics(di));
                seen.add(where + " pick " + idx + (pick instanceof String ? " (" + pick + ")" : ""));
                di.handle(box, idx + 1);
            } else {
                // A screen that comes back unchanged after "continue" is a dialogue that can't end itself (live 10-23: the
                // Falador Squire sets quest stage 10, then his stage-999 "end" sits in the stage-0 branch only, so the box
                // never closes). Close it like the client's Escape and let the step's own check decide.
                if (where.equals(lastWhere) && ++same >= 4) { di.close(); try { p.getInterfaceManager().closeChatbox(); } catch (Throwable ignored) { } return picks.isEmpty() ? Status.DONE : fail("stuck on " + where + " with picks left " + picks); }
                if (!where.equals(lastWhere)) same = 0;
                lastWhere = where;
                seen.add(where);
                di.handle(box, CONTINUE_CHILD);
            }
        } catch (Throwable t) {
            return fail(t + " at " + where);
        }
        wait = 1 + (int) (Math.random() * 3);
        return Status.RUNNING;
    }

    static int resolve(Object pick, DialogueInterpreter di, int count) {
        if (pick instanceof Integer) return (Integer) pick;
        String s = String.valueOf(pick);
        int fallback = -1;
        int bar = s.lastIndexOf('|');
        if (bar >= 0) { try { fallback = Integer.parseInt(s.substring(bar + 1).trim()); } catch (NumberFormatException ignored) { } s = s.substring(0, bar); }
        String want = norm(s);
        for (int i = 0; i < di.activeTopics.size(); i++) {
            Topic<?> t = di.activeTopics.get(i);
            if (t != null && norm(t.getText()).startsWith(want)) return i + 1;
        }
        return fallback;
    }

    static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").trim(); }

    static String topics(DialogueInterpreter di) {
        if (di.activeTopics.isEmpty()) return "";
        StringBuilder b = new StringBuilder(" topics=[");
        for (Topic<?> t : di.activeTopics) b.append(t == null ? "?" : t.getText()).append("; ");
        return b.append("]").toString();
    }

    static String describe(DialoguePlugin d, int box) {
        String file = d.file != null ? "/" + d.file.getClass().getSimpleName() + "@" + d.file.getStage() : "";
        return d.getClass().getSimpleName() + "@" + d.stage + file + " box " + box;
    }

    Status fail(String why) {
        failure = why;
        try { p.getDialogueInterpreter().close(); p.getInterfaceManager().closeChatbox(); } catch (Throwable ignored) { }
        return Status.FAILED;
    }

    /** What else was acting on the bot when its dialogue closed early (live 10-23: Rain293's first screens closed in three quests, cause unknown). */
    String state() {
        try {
            core.game.system.task.Pulse cur = p.getPulseManager().getCurrent();
            return " [state: pulse " + (cur == null ? "none" : cur.getClass().getName()) + ", moving " + p.getWalkingQueue().isMoving() + ", afk " + AfkTasks.RUNNING.containsKey(p.getUsername().toLowerCase(java.util.Locale.ROOT))
                + ", modal " + p.hasModalOpen() + ", combat " + p.inCombat() + ", at " + p.getLocation() + "]";
        } catch (Throwable t) { return " [state: " + t + "]"; }
    }

    public String failure() { return failure; }
    public List<String> seen() { return seen; }
}
