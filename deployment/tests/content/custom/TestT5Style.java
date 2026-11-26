package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.node.entity.skill.Skills;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Trial only (-Dtest.t5style=true; needs TestCR1 compiled in): train-5 mage and ranged fixes F1-F5
 * (the design notes). Each section prints its own RESULT PASS/FAIL line, DONE at the end.
 *  S1  F1 budget: rangers (Ranged 40, maple) at 500 / 5,000 / 100,000 gp and no arrows; a Magic 60 mage at 2,000 gp;
 *      with data/style5.off the old quantities (200 of the level's arrow) and no yew.
 *  S2  F2 weapon first: (a) 1,000 gp, no bow -> a bow first in the kit; (b) death containers with three pricier items:
 *      the bow moves into the kept container; (c) 100 gp, no bow -> melee fallback, Strength/Attack XP and no Ranged XP (live).
 *  S4  F4 yew: 2 yew shortbows in Exchange stock -> a Ranged 40 bot at 5,000 gp wears 857; no stock -> the kit asks for 853.
 *  S1b F1 D (live, goblins): a ranger with 20 bronze arrows gets fired arrows back (arrowsBack > 0).
 *  S3  F3 (live): a ranger with 5 arrows finishes "Out of arrows." within 60 ticks of the empty quiver; a mage with 2 mind
 *      runes finishes "Out of runes.".
 *  S5  F5: every live runner ends with a [StyleTrip] line whose rng/mag/att/str xp fields match the runner's own deltas.
 */
public final class TestT5Style implements StartupListener {
    static void log(String s) { System.out.println("[TestT5Style] " + s); }
    static final java.util.Set<String> USED = new java.util.HashSet<>();
    static int seq;

    static SmartAdventurers.Brain make(int style, Location at, int lvl, long coins) {
        String name = null;
        for (int i = seq; i < seq + 4000 && name == null; i++) { String n = "Tfs" + (char) ('a' + i % 26) + (char) ('a' + (i / 26) % 26) + (100 + i % 800); if (!USED.contains(n) && SmartAdventurers.styleFor(n.toLowerCase(Locale.ROOT), -1) == style) { name = n; seq = i + 1; } }
        if (name == null) return null;
        USED.add(name);
        AIPlayer b = SmartAdventurers.namedBot(name, at);
        if (b == null) return null;
        SmartAdventurers.finishTutorial(b);
        SmartAdventurers.FAV.put(b.getUsername().toLowerCase(Locale.ROOT), -1);
        SmartAdventurers.Brain br = TestCR1.rig(b, false);
        b.getInventory().clear(); for (Item it : b.getBank().toArray()) if (it != null) b.getBank().remove(it);
        for (int s = 0; s < 14; s++) { Item w = b.getEquipment().get(s); if (w != null) b.getEquipment().remove(w); }
        int sk = style == SmartAdventurers.RANGED ? Skills.RANGE : Skills.MAGIC;
        b.getSkills().setStaticLevel(sk, lvl); b.getSkills().setLevel(sk, lvl);
        for (int k : new int[]{Skills.HITPOINTS, Skills.DEFENCE}) { b.getSkills().setStaticLevel(k, 40); b.getSkills().setLevel(k, 40); }
        if (coins > 0) Ledger.Wallet.give(b, coins);
        b.getBank().add(new Item(333, 20)); // trout: no "Out of food." finish
        return br;
    }
    static void wear(SmartAdventurers.Brain br, int id, int n) { br.bot.getInventory().add(new Item(id, n)); if (n > 1) { br.bot.getInventory().remove(new Item(id, n)); br.bot.getEquipment().add(new Item(id, n), -1, true, false); } else SmartAdventurers.wear(br.bot, id); br.bot.getEquipment().refresh(); }
    static String desc(List<int[]> l) { StringBuilder s = new StringBuilder(); for (int[] x : l) s.append(x[0]).append('x').append(x[1]).append(' '); return s.toString().trim(); }
    static File off() { return new File(ServerConstants.DATA_PATH, "style5.off"); }
    static void setOff(boolean on) { try { if (on) off().createNewFile(); else off().delete(); } catch (Throwable t) { log("off-file " + t); } SmartAdventurers.style5Recheck(); }
    static int xpOf(String line, String key) { int i = line.indexOf(" " + key + "="); if (i < 0) return Integer.MIN_VALUE; int j = line.indexOf(' ', i + key.length() + 2); return Integer.parseInt(line.substring(i + key.length() + 2, j < 0 ? line.length() : j)); }

    static final class Live { String tag; SmartAdventurers.Brain br; AfkTasks.Runner r; int emptyAt = -1, endAt = -1, maxQ, lastQ; boolean rose; int[] dLine; }

    @Override public void startup() {
        if (!Boolean.getBoolean("test.t5style")) return;
        GameWorld.getPulser().submit(new Pulse(1) {
            int t, judgeAt = -1; Live a, b, m, c; Live[] all; boolean ok1, ok2, ok4;
            Location spot;
            @Override public boolean pulse() {
                t++;
                try {
                    if (t == 150) { setOff(false); section1(); }
                    if (t == 170) section2();
                    if (t == 190) section4();
                    if (t == 210) startLive();
                    if (t > 210 && all != null) return tickLive();
                } catch (Throwable e) { log("RESULT FAIL error " + e); e.printStackTrace(System.out); setOff(false); log("DONE"); return true; }
                return false;
            }

            void section1() {
                Location at = TestCR1.LUMBRIDGE;
                long[] purse = {500, 5_000, 100_000}; boolean ok = true; StringBuilder sum = new StringBuilder();
                log("guide: bronze " + MarketBrain.value(882) + " iron " + MarketBrain.value(884) + " steel " + MarketBrain.value(886) + " mithril " + MarketBrain.value(888) + " adamant " + MarketBrain.value(890) + "; runes mind " + MarketBrain.value(558) + " chaos " + MarketBrain.value(562) + " death " + MarketBrain.value(560) + " air " + MarketBrain.value(556) + "; bows 841 " + MarketBrain.value(841) + " 843 " + MarketBrain.value(843) + " 849 " + MarketBrain.value(849) + " 853 " + MarketBrain.value(853) + " 857 " + MarketBrain.value(857) + "; staff " + MarketBrain.value(1387));
                for (int i = 0; i < purse.length; i++) {
                    SmartAdventurers.Brain br = make(SmartAdventurers.RANGED, at, 40, purse[i]);
                    if (br == null) { log("RESULT FAIL S1 could not make a ranger"); return; }
                    wear(br, 853, 1);
                    List<int[]> need = br.supplyNeeds(); long budget = br.supplyBudget();
                    int id = need.isEmpty() ? -1 : need.get(0)[0], n = need.isEmpty() ? 0 : need.get(0)[1]; long v = Math.max(1, MarketBrain.value(Math.max(id, 882)));
                    boolean good;
                    if (i == 0) good = id == 882 && n == 40;
                    else good = id > 0 && n == (int) Math.max(40, Math.min(200, budget / v)) && (id == 882 || 40 * v <= budget) && (i < 2 || id == 890);
                    if (i == 1 && id == 890) good = false; // 5,000 gp must step down from adamant
                    ok &= good;
                    sum.append(purse[i]).append("gp->").append(desc(need)).append(" (budget ").append(budget).append(") ");
                    if (i == 2) { // off-file: today's quantities and no yew
                        setOff(true);
                        List<int[]> old = br.supplyNeeds(); int bb = br.bestBow();
                        br.bot.getSkills().setStaticLevel(Skills.RANGE, 45);
                        int bbOff = br.bestBow();
                        setOff(false);
                        int bbOn = br.bestBow();
                        boolean offOk = old.size() == 1 && old.get(0)[0] == br.bestArrow() && old.get(0)[1] == 200 && bbOff == 3 && bbOn == 4;
                        ok &= offOk;
                        sum.append("| off-file: ").append(desc(old)).append(" bestBow(45) off ").append(bbOff).append(" on ").append(bbOn).append(offOk ? " ok " : " BAD ");
                        br.bot.getSkills().setStaticLevel(Skills.RANGE, 40);
                    }
                }
                SmartAdventurers.Brain mg = make(SmartAdventurers.MAGIC, at, 60, 2_000);
                wear(mg, 1387, 1);
                List<int[]> mn = mg.supplyNeeds(); int[] s = mg.tripSpell;
                boolean mOk = s != null && (s[0] == 8 || s[0] == 1);
                if (mOk) for (int[] x : mn) mOk &= x[0] == s[1] ? x[1] >= 40 * s[2] : x[0] == s[3] && x[1] >= 40 * s[4];
                mOk &= mn.size() == 2;
                ok &= mOk;
                sum.append("| mage 2000gp M60: spell ").append(s == null ? "-" : String.valueOf(s[0])).append(" buys ").append(desc(mn)).append(" (budget ").append(mg.supplyBudget()).append(")");
                ok1 = ok;
                log("RESULT " + (ok ? "PASS" : "FAIL") + " S1 F1 budget: " + sum);
            }

            void section2() {
                Location at = TestCR1.LUMBRIDGE;
                // (a) 1,000 gp, no bow
                SmartAdventurers.Brain ra = make(SmartAdventurers.RANGED, at, 30, 1_000);
                List<int[]> kit = ra.styleKit(SmartAdventurers.RANGED);
                int first = kit.isEmpty() ? -1 : kit.get(0)[0];
                setOff(true); List<int[]> kitOff = ra.styleKit(SmartAdventurers.RANGED); setOff(false);
                boolean bowOff = false; for (int[] k : kitOff) bowOff |= SmartAdventurers.bowIndex(k[0]) >= 0;
                boolean aOk = SmartAdventurers.bowIndex(first) >= 0 && 1_000 - MarketBrain.value(first) >= 300;
                // (b) the death swap on the engine's own kept/lost containers
                SmartAdventurers.Brain rb = make(SmartAdventurers.RANGED, at, 30, 1_000);
                wear(rb, 853, 1); wear(rb, 1127, 1); wear(rb, 1079, 1); wear(rb, 1163, 1);
                core.game.container.Container[] cc = core.game.node.entity.combat.DeathTask.getContainers(rb.bot);
                boolean before = cc[0].getAmount(853) > 0; int keptN = 0; for (Item it : cc[0].toArray()) if (it != null) keptN++;
                boolean swapped = SmartAdventurers.keepStyleWeapon(cc);
                int keptN2 = 0; for (Item it : cc[0].toArray()) if (it != null) keptN2++;
                boolean bOk = !before && swapped && cc[0].getAmount(853) == 1 && cc[1].getAmount(853) == 0 && keptN2 == keptN;
                // (c) 100 gp, no bow: the fallback (XP checked on the live trip)
                c = new Live(); c.tag = "C"; c.br = make(SmartAdventurers.RANGED, at, 30, 100);
                c.br.styleFallback();
                Item cw = c.br.bot.getEquipment().get(3);
                boolean cOk = c.br.meleeFallback && (cw == null || SmartAdventurers.bowIndex(cw.getId()) < 0);
                ok2 = aOk && bOk && cOk;
                log("RESULT " + (aOk && bOk && cOk ? "PASS" : "FAIL") + " S2 F2: (a) 1000gp kit " + desc(kit) + " (bow first " + aOk + "; off-file kit " + desc(kitOff) + ", bow in it " + bowOff + ")"
                        + " (b) bow kept before " + before + ", swapped " + swapped + ", kept " + keptN + "->" + keptN2 + " " + bOk
                        + " (c) fallback " + c.br.meleeFallback + " weapon " + (cw == null ? "none" : cw.getName()) + " " + cOk);
            }

            void section4() {
                Location at = TestCR1.LUMBRIDGE;
                Ledger.supply(null, 857, 2);
                java.util.Map<Integer, Integer> st = new java.util.HashMap<>(MarketBrain.stock); st.put(857, core.game.ge.GrandExchange.Companion.getBotstockForId(857)); MarketBrain.stock = st;
                SmartAdventurers.Brain y = make(SmartAdventurers.RANGED, at, 40, 5_000);
                String want = desc(y.armourNeeds());
                y.kitFromExchange();
                Item w = y.bot.getEquipment().get(3);
                boolean yOk = w != null && w.getId() == 857;
                java.util.Map<Integer, Integer> st2 = new java.util.HashMap<>(MarketBrain.stock); st2.put(857, 0); MarketBrain.stock = st2;
                SmartAdventurers.Brain z = make(SmartAdventurers.RANGED, at, 40, 5_000);
                List<int[]> zk = z.styleKit(SmartAdventurers.RANGED); boolean zOk = false; for (int[] k : zk) zOk |= k[0] == 853;
                ok4 = yOk && zOk;
                log("RESULT " + (ok4 ? "PASS" : "FAIL") + " S4 F4 yew: stock " + st.get(857) + ", wants " + want + ", worn " + (w == null ? "none" : w.getId()) + "; no stock: kit " + desc(zk));
            }

            void startLive() {
                AfkTasks.Spot[] sp = AfkTasks.SPOTS.get(AfkTasks.Task.GOBLIN);
                spot = sp[0].at;
                a = new Live(); a.tag = "A"; a.br = make(SmartAdventurers.RANGED, spot, 10, 2_000); wear(a.br, 841, 1); wear(a.br, 882, 20);
                b = new Live(); b.tag = "B"; b.br = make(SmartAdventurers.RANGED, spot.transform(1, 0, 0), 10, 2_000); wear(b.br, 841, 1); wear(b.br, 882, 5);
                m = new Live(); m.tag = "M"; m.br = make(SmartAdventurers.MAGIC, spot.transform(0, 1, 0), 10, 2_000); wear(m.br, 1387, 1);
                m.br.bot.getInventory().add(new Item(558, 2)); m.br.bot.getInventory().add(new Item(556, 100));
                m.br.equipSupplies(); // tripSpell = wind strike (held: no, 2 minds) -> armed if it holds 1+1
                log("mage autocast " + AfkTasks.Runner.castId(m.br.bot) + " castTrip " + m.br.castTrip + " tripSpell " + (m.br.tripSpell == null ? "-" : m.br.tripSpell[0]));
                c.br.bot.getProperties().setTeleportLocation(spot.transform(0, -1, 0));
                all = new Live[]{a, b, m, c};
                for (Live l : all) {
                    l.r = new AfkTasks.Runner(l.br.bot, AfkTasks.Task.GOBLIN, true, 500, false, l == a);
                    l.r.spot = spot; l.r.setDelay(1); l.r.styleSkill = Skills.STRENGTH;
                    if (!AfkTasks.launch(l.r, AfkTasks.State.TO_SPOT, spot)) log(l.tag + " launch failed: " + l.r.last);
                    Item q = l.br.bot.getEquipment().get(13); l.lastQ = q == null ? 0 : q.getAmount(); l.maxQ = l.lastQ;
                }
                log("live: 4 runners at " + spot + " (A 20 arrows + pickup, B 5 arrows no pickup, M 2 mind runes, C melee fallback)");
            }

            boolean done(Live l) { return l.r.halted || l.r.bankingOut || l.r.styleTrip != null; }

            boolean tickLive() {
                for (Live l : all) {
                    if (l.endAt < 0 && done(l)) l.endAt = t;
                    if (l.dLine == null && l.r.styleTrip != null) l.dLine = deltas(l); // the xp when the line was written (a halted bot can still be hit and hit back)
                    Item q = l.br.bot.getEquipment().get(13); int n = q == null ? 0 : q.getAmount();
                    if (n > l.lastQ) l.rose = true; l.lastQ = n; l.maxQ = Math.max(l.maxQ, n);
                    if (l.emptyAt < 0 && n == 0 && l.br.bot.getInventory().getAmount(882) == 0) l.emptyAt = t;
                }
                if (m.endAt < 0) m.br.runesCheck(); // the brain's think loop is off in a rigged bot: its out-of-runes check by hand
                boolean timeUp = t > 210 + 1500;
                if (!timeUp && (a.r.arrowsBack == 0 || b.endAt < 0 || m.endAt < 0 || t < 210 + 600)) return false;
                if (judgeAt < 0) { judgeAt = t + 400; for (Live l : new Live[]{a, c}) if (l.endAt < 0) l.r.finish("test end"); }
                if (t < judgeAt && (a.r.styleTrip == null || c.r.styleTrip == null)) return false; // a runner with loot walks it to a bank before its line
                // S1b
                boolean s1b = a.r.arrowsBack > 0;
                log("RESULT " + (s1b ? "PASS" : "FAIL") + " S1b F1 own arrows: A arrows back " + a.r.arrowsBack + ", quiver rose " + a.rose + ", attacks " + a.r.kills);
                // S3
                String bw = b.r.styleTrip == null ? String.valueOf(b.r.goalMsg) : b.r.styleTrip;
                boolean s3b = bw.contains("Out of arrows.") && b.emptyAt > 0 && b.endAt >= 0 && b.endAt - b.emptyAt <= 60;
                String mw = m.r.styleTrip == null ? String.valueOf(m.r.goalMsg) : m.r.styleTrip;
                boolean s3m = mw.contains("Out of runes.");
                log("RESULT " + (s3b && s3m ? "PASS" : "FAIL") + " S3 F3 exits: B empty at " + b.emptyAt + " ended at " + b.endAt + " (" + s3b + "), M ended at " + m.endAt + " (" + s3m + ")");
                // S2c XP
                int[] dc = deltas(c);
                boolean s2c = dc[0] == 0 && dc[2] + dc[3] > 0 && c.r.styleTrip != null && c.r.styleTrip.contains("fallback=melee");
                log("RESULT " + (s2c ? "PASS" : "FAIL") + " S2c F2 fallback trip: xp rng " + dc[0] + " att " + dc[2] + " str " + dc[3] + ", line fallback=melee " + (c.r.styleTrip != null && c.r.styleTrip.contains("fallback=melee")));
                // S5
                boolean s5 = true; StringBuilder sb = new StringBuilder();
                for (Live l : all) {
                    String line = l.r.styleTrip; int[] d = l.dLine != null ? l.dLine : deltas(l);
                    boolean ok = line != null && xpOf(line, "rng") == d[0] && xpOf(line, "mag") == d[1] && xpOf(line, "att") == d[2] && xpOf(line, "str") == d[3];
                    s5 &= ok; sb.append(l.tag).append(ok ? " ok " : " BAD ");
                    log(l.tag + " line: " + line);
                }
                log("RESULT " + (s5 ? "PASS" : "FAIL") + " S5 F5 [StyleTrip] lines: " + sb);
                log("RESULT " + (ok1 && ok2 && ok4 && s1b && s3b && s3m && s2c && s5 ? "PASS" : "FAIL") + " ALL");
                setOff(false);
                log("DONE");
                return true;
            }

            int[] deltas(Live l) {
                int[] sk = {Skills.RANGE, Skills.MAGIC, Skills.ATTACK, Skills.STRENGTH};
                int[] out = new int[4]; for (int i = 0; i < 4; i++) out[i] = (int) (l.br.bot.getSkills().getExperience(sk[i]) - l.r.startXp[sk[i]]);
                return out;
            }
        });
    }
}
