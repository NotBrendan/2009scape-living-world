package content.custom;

import content.global.skill.slayer.Tasks;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;

import java.util.EnumSet;
import java.util.Set;

/**
 * W8 wave 4 lane B item 4 (the design notes section 4, Slayer row): -Dbots.slayer.only1, default OFF.
 * Needs the Slayer errand itself (-Dbots.slayer); off, nothing here runs (both hooks test ONLY1 first).
 *
 * The first Slayer-only monsters: at Turael, a bot that qualifies may also be given crawling hands (Slayer 5) and banshees
 * (Slayer 15, earmuffs), both on the Slayer Tower's ground floor. "Qualifies" (all of): the engine's Slayer level for the
 * task, combat 25+ (hands) / 40+ (banshees), hitpoints 25+, 5k coins (the trip's food and the fare), a JOBS hunt for the
 * monster whose spot Gates lets the bot enter (Morytania needs Priest in Peril), and for banshees earmuffs it holds or can
 * pay for. A weak bot never sees these tasks. At a banshee trip's start the bot wears earmuffs: its own (pack, bank) or
 * bought at guide price (Wallet.pay, the Slayer master's shop: a coin sink, never minted); its old hat goes to its bank.
 *
 * Routes (p38-navport.py, live nav-p0.bin, 11-19): Canifis bank 3512,3480 -> crawling hands 3420,3542 (80 steps, the tower
 * door 3428,3536) and back; -> banshee spawns 3434,3555 / 3443,3551 / 3442,3545 and back; the Salve crossing 3442,3458 ->
 * hands (99). All pass. Cave bugs are not added: they live under the Lumbridge swamp (3150,9573: off the nav grid, a rope
 * and a light source to enter). Chaeldar (-Dbots.slayer.chaeldar) is built apart, in W9Chaeldar (w9 lane D,
 * the design notes); this class never reads that flag.
 */
final class SlayerOnly {
    private SlayerOnly() { }

    static final boolean ONLY1 = Boolean.getBoolean("bots.slayer.only1");
    static final int EARMUFFS = 4166, HAT_SLOT = 0;
    /** Task (engine Tasks name), its minimum combat, the monster's name. Slayer level comes from the engine's Tasks.levelReq. */
    static final String[] TASKS = {"CRAWLING_HAND", "BANSHEE"};
    static final int[] MIN_COMBAT = {25, 40};
    static final int MIN_HP = 25;
    static final long MIN_COINS = 5_000;

    static volatile int offered, refusedLevel, refusedGate, refusedKit, kitBank, kitBought, kitWorn, kitFail;

    /** Pure: may a bot be offered this Slayer-only task. earmuffs: holds them or can pay (only asked for a banshee task). */
    static boolean qualifies(int idx, int slayer, int levelReq, int combat, int hp, long coins, boolean huntOpen, boolean earmuffs) {
        if (idx < 0 || idx >= TASKS.length) return false;
        if (slayer < levelReq || combat < MIN_COMBAT[idx] || hp < MIN_HP || coins < MIN_COINS) return false;
        if (!huntOpen) return false;
        return !"BANSHEE".equals(TASKS[idx]) || earmuffs;
    }

    static boolean needsEarmuffs(String monster) { return monster != null && monster.toLowerCase(java.util.Locale.ROOT).contains("banshee"); }

    /** Slayer's Turael errand, after served(): the allow set plus the Slayer-only tasks this bot qualifies for. */
    static Set<Tasks> add(SmartAdventurers.Brain b, Set<Tasks> allow) {
        if (!ONLY1 || b == null || b.bot == null || allow == null) return allow;
        Player p = b.bot;
        Set<Tasks> out = null;
        for (int i = 0; i < TASKS.length; i++) {
            Tasks t;
            try { t = Tasks.valueOf(TASKS[i]); } catch (Throwable x) { continue; }
            if (allow.contains(t)) continue;
            int slayer = p.getSkills().getStaticLevel(Skills.SLAYER), combat = p.getProperties().getCurrentCombatLevel(), hp = p.getSkills().getStaticLevel(Skills.HITPOINTS);
            long coins = Ledger.Wallet.coins(p);
            boolean open = huntOpen(p, t);
            boolean muffs = has(p, EARMUFFS) || coins >= MIN_COINS + Math.max(1, MarketBrain.value(EARMUFFS));
            if (!qualifies(i, slayer, t.levelReq, combat, hp, coins, open, muffs)) {
                if (slayer < t.levelReq || combat < MIN_COMBAT[i] || hp < MIN_HP || coins < MIN_COINS) refusedLevel++; else if (!open) refusedGate++; else refusedKit++;
                continue;
            }
            if (out == null) out = EnumSet.copyOf(allow.isEmpty() ? EnumSet.noneOf(Tasks.class) : allow);
            out.add(t); offered++;
        }
        if (out != null) System.out.println("[SlayerOnly] " + p.getUsername() + " may be given " + out.stream().filter(x -> !allow.contains(x)).map(Enum::name).reduce((a, c) -> a + "," + c).orElse("") + " (Slayer " + p.getSkills().getStaticLevel(Skills.SLAYER) + ", combat " + p.getProperties().getCurrentCombatLevel() + ")");
        return out == null ? allow : out;
    }

    /** A JOBS hunt for the task's monster whose spot this bot may enter. */
    static boolean huntOpen(Player p, Tasks t) {
        Set<String> names = Slayer.npcNames(t);
        for (SmartAdventurers.Job j : SmartAdventurers.JOBS) {
            if (j.hunt == null || j.hunt.spot == null) continue;
            String[] jn = Slayer.jobNames(j);
            if (jn == null) continue;
            boolean m = false;
            for (String n : jn) if (n != null && names.contains(n.toLowerCase(java.util.Locale.ROOT))) { m = true; break; }
            if (m && Gates.canEnter(p, j.hunt.spot)) return true;
        }
        return false;
    }

    static boolean has(Player p, int id) {
        Item h = p.getEquipment().get(HAT_SLOT);
        return h != null && h.getId() == id || p.getInventory().getAmount(id) > 0 || p.getBank().getAmount(id) > 0;
    }

    /** Brain.start for a hunt: a banshee trip wears earmuffs. */
    static void kit(Player p, HuntMap.Hunt h) {
        if (!ONLY1 || p == null || h == null || !needsEarmuffs(h.name)) return;
        Item hat = p.getEquipment().get(HAT_SLOT);
        if (hat != null && hat.getId() == EARMUFFS) return;
        boolean got = false;
        if (p.getInventory().getAmount(EARMUFFS) > 0) got = p.getInventory().remove(new Item(EARMUFFS, 1));
        else if (p.getBank().getAmount(EARMUFFS) > 0) { got = p.getBank().remove(new Item(EARMUFFS, 1)); if (got) kitBank++; }
        else if (Ledger.Wallet.pay(p, Math.max(1, MarketBrain.value(EARMUFFS)))) { got = true; kitBought++; }
        if (!got) { kitFail++; System.out.println("[SlayerOnly] " + p.getUsername() + " has no earmuffs for the banshees"); return; }
        if (hat != null) p.getEquipment().remove(hat);
        if (!p.getEquipment().add(new Item(EARMUFFS, 1), -1, true, false)) { // as Brain's own gear swap (fire = bonuses, appearance)
            if (hat != null) p.getEquipment().add(hat, -1, true, false);
            if (!p.getBank().add(new Item(EARMUFFS, 1))) p.getInventory().add(new Item(EARMUFFS, 1));
            kitFail++; return;
        }
        if (hat != null && !p.getBank().add(hat) && !p.getInventory().add(hat)) try { Failures.Econ.itemLost(p, hat.getId(), hat.getAmount(), "earmuffs", "-"); } catch (Throwable ignored) { }
        try { p.getEquipment().refresh(); } catch (Throwable ignored) { }
        kitWorn++;
        System.out.println("[SlayerOnly] " + p.getUsername() + " wears earmuffs for the banshees" + (hat != null ? " (" + MarketBrain.name(hat.getId()) + " to the bank)" : ""));
    }

    static String line() {
        return "[SlayerOnly] on=" + ONLY1 + " offered=" + offered + " refusedLevel=" + refusedLevel + " refusedGate=" + refusedGate + " refusedKit=" + refusedKit
                + " kitWorn=" + kitWorn + " kitBank=" + kitBank + " kitBought=" + kitBought + " kitFail=" + kitFail;
    }
}
