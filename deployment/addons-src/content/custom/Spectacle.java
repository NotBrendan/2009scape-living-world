package content.custom;

import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;
import core.game.world.update.flag.context.Animation;
import core.game.world.update.flag.context.Graphics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Spectacle (2026-10-20: "implement this with great thought"). Shows bots put on for the people watching,
 * the way players hung about in 2009: arm wrestles, dance-offs, skill boasts, follow-the-leader emote games, and
 * fishing/mining races between two bots on the same spot.
 *
 * Rules that keep it player-like:
 * - Only when a real player can see it (within 20 tiles): it's theatre, there's no point otherwise.
 * - Outcomes come from the bots' real stats (Strength for arm wrestling, Agility for dancing, the real skill for a
 *   boast, the real catch for a race) plus luck, never a coin flip.
 * - Wagers are real coins moved from loser to winner (nothing created); spectators may side-bet the same way.
 * - Nearby idle bots gather round, cheer and heckle; the result becomes a rumour and a news line.
 * - A bot may challenge the watching player to an arm wrestle (pride only, never their coins): say "you're on".
 * - Rivalries are remembered (rematch talk) for the rest of the session.
 */
public final class Spectacle {
    static final Random RNG = new Random();
    static final int SEE = 20;
    static final Map<String, Long> AREA_BUSY = new ConcurrentHashMap<>();   // one show at a time per 16x16 area
    static final Map<String, int[]> RIVALRY = new ConcurrentHashMap<>();     // "a|b" -> {a wins, b wins}
    static final List<Race> RACES = new CopyOnWriteArrayList<>();
    static final Map<String, Object[]> CHALLENGES = new ConcurrentHashMap<>(); // human -> {Brain, expiry}
    static volatile int shows, races;

    static {
        BotChat.LISTENERS.add(Spectacle::heard);
    }

    static void anim(Player p, int id) { try { p.getAnimator().animate(new Animation(id)); } catch (Throwable ignored) { } }
    static void say(Player p, String s) { BotChat.say(p, s); }
    static String area(Player p) { return (p.getLocation().getX() >> 4) + "," + (p.getLocation().getY() >> 4); }

    static Player watcher(Player bot) {
        if (Boolean.getBoolean("spectacle.test")) return bot; // trials only: pretend someone is watching
        for (Player h : Repository.getPlayers())
            if (h != null && h.isActive() && Companions.human(h) && h.getLocation().getZ() == bot.getLocation().getZ() && h.getLocation().getDistance(bot.getLocation()) <= SEE) return h; // batch 28: a trial stand-in counts
        return null;
    }

    /** batch 15 item1: a real player within SEE tiles of a spot (the landing of a teleport). */
    static boolean watched(Location at) {
        for (Player h : Repository.getPlayers())
            if (h != null && h.isActive() && !h.isArtificial() && h.getLocation().getZ() == at.getZ() && h.getLocation().getDistance(at) <= SEE) return true;
        return false;
    }

    static volatile int teleShows; // batch 15 item1: teleports a player saw

    /**
     * batch 15 item1: a bot casting a teleport. The standard spell's animation and graphic (TeleportType.NORMAL's start
     * emote) only when a real player is within SEE tiles of where it stands or where it lands (the landing is in the same
     * tick, so a player by the start only sees it if the landing is in view too); the landing is set in this tick either
     * way, so where and when it lands is exactly as before. Ladders, trapdoors, portals and gates keep their own steps.
     */
    static void teleport(Player bot, Location to) {
        if (bot != null && bot.isArtificial() && to != null && (watcher(bot) != null || watched(to))) {
            try { if (bot.getAnimator().animate(new Animation(8939), new Graphics(1576))) teleShows++; } catch (Throwable ignored) { }
        }
        if (bot != null && bot.isArtificial() && to != null) try { gateTrace(bot, to); } catch (Throwable ignored) { } // batch 21 B1
        bot.getProperties().setTeleportLocation(to);
    }

    static volatile int gatedLandings; // batch 21 B1

    /**
     * batch 21 B1: logs a bot teleport that lands inside a guild-sized gate box whose rule the bot fails, from outside it, with
     * the calling method. The Warriors' Guild doors (15653 east, 1530 west) run the 130 check from both sides, so such a bot
     * can't walk out (Preflight.stranded pays its way out later). On the Batch 19 live data the bots got in this way: a shop
     * walk to the Warriors' Guild Food Shop stalled at the door and its stall teleport landed by the counter. Batch 20 refuses
     * that pick; this line shows any teleport path still left. Logging only.
     */
    static void gateTrace(Player bot, Location to) {
        if (!Gates.ON) return;
        Location here = bot.getLocation();
        for (Gates.Gate g : Gates.at(to)) {
            if ((g.x1 - g.x0) * (g.y1 - g.y0) >= 10_000 || (here != null && g.contains(here)) || g.rule.ok(bot)) continue;
            gatedLandings++;
            StackTraceElement[] st = new Throwable().getStackTrace();
            String by = st.length > 2 ? st[2].getClassName().replace("content.custom.", "") + "." + st[2].getMethodName() + ":" + st[2].getLineNumber() : "?";
            System.out.println("[Gates] " + bot.getUsername() + " teleports into " + g.name + " without " + g.rule.need() + ": " + here + " -> " + to + " (by " + by + ")");
            return;
        }
    }

    static boolean idle(SmartAdventurers.Brain b) {
        return b != null && b.bot != null && b.bot.isActive() && b.runner == null && b.shopAt == null && !b.delivering && b.mill == 0 && b.graveAt == null && b.agiStep < 0 && b.starUntil == 0 && b.partyUntil <= System.currentTimeMillis(); // batch 16 item3: a party guest is busy
    }

    static long stakeFor(Player a, Player b) {
        long s = Math.min(Math.min(Ledger.Wallet.coins(a), Ledger.Wallet.coins(b)) / 10, 2_000L);
        return Math.max(0, s - s % 50);
    }

    /** Called from an idle bot's think (on a break at the Exchange or a hangout). True if a show started. */
    static boolean tick(SmartAdventurers.Brain me, long now) {
        if (!idle(me) || now < me.nextSpectacle) return false;
        Player crowd = watcher(me.bot);
        if (crowd == null) return false;
        String ar = area(me.bot);
        if (AREA_BUSY.getOrDefault(ar, 0L) > now) return false;
        int hub = Scenes.Show.roll(me.bot.getLocation(), now); // batch 28: -1 = this hub had its show in the last 30 min, 2 = due at a hub (1 in 2), 0 = the old rule
        if (hub < 0) return false;
        if (RNG.nextInt(Boolean.getBoolean("spectacle.test") || hub == 2 ? 2 : 6) != 0) return false; // not every moment of every break
        SmartAdventurers.Brain other = null;
        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values())
            if (b != me && idle(b) && now >= b.nextSpectacle && b.bot.getLocation().getZ() == me.bot.getLocation().getZ() && b.bot.getLocation().getDistance(me.bot.getLocation()) <= 12) { other = b; break; } // P38: partner within 12 (was 8; break loiter radius 5 puts two breakers 8-10 apart)
        if (other == null && Boolean.getBoolean("spectacle.test")) for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) // trials only: bring a partner over
            if (b != me && idle(b) && now >= b.nextSpectacle) { b.bot.getProperties().setTeleportLocation(me.bot.getLocation().transform(1, 0, 0)); b.idleUntil = now + 60_000; other = b; break; }
        me.nextSpectacle = now + (Boolean.getBoolean("spectacle.test") ? 60_000 : 15 * 60_000);
        if (other == null) {
            if (crowd != me.bot && crowd.getLocation().getDistance(me.bot.getLocation()) <= 8 && RNG.nextInt(3) == 0) { challengeHuman(me, crowd, now); AREA_BUSY.put(ar, now + 60_000); return true; }
            return false;
        }
        other.nextSpectacle = now + (Boolean.getBoolean("spectacle.test") ? 60_000 : 15 * 60_000);
        AREA_BUSY.put(ar, now + 90_000);
        Scenes.Show.started(me.bot.getLocation(), now); // batch 28: the hub's next show in 30 min
        List<SmartAdventurers.Brain> fans = new ArrayList<>();
        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values())
            if (b != me && b != other && idle(b) && fans.size() < 3 && b.bot.getLocation().getZ() == me.bot.getLocation().getZ() && b.bot.getLocation().getDistance(me.bot.getLocation()) <= 12) fans.add(b);
        int kind = RNG.nextInt(4);
        switch (kind) {
            case 0: armWrestle(me.bot, other.bot, fans, crowd); break;
            case 1: danceOff(me.bot, other.bot, fans, crowd); break;
            case 2: boast(me.bot, other.bot, fans, crowd); break;
            default: followTheLeader(me.bot, other.bot, fans, crowd);
        }
        shows++;
        return true;
    }

    // ------------------------------------------------------------------ the shows

    /** A sequence of steps a few ticks apart. */
    static void run(List<Runnable> steps, int gap) {
        GameWorld.getPulser().submit(new Pulse(gap) {
            int i = 0;
            @Override public boolean pulse() {
                try { if (i < steps.size()) steps.get(i).run(); } catch (Throwable t) { if (Boolean.getBoolean("afk.debug")) System.out.println("[Spectacle] " + t); }
                return ++i >= steps.size();
            }
        });
    }

    static void gather(List<SmartAdventurers.Brain> fans, Player a, Player b) {
        for (SmartAdventurers.Brain f : fans) {
            try { f.bot.face(RNG.nextBoolean() ? a : b); if (f.bot.getLocation().getDistance(a.getLocation()) > 4) f.scriptAPI.randomWalkTo(a.getLocation(), 3); } catch (Throwable ignored) { }
            f.idleUntil = Math.max(f.idleUntil, System.currentTimeMillis() + 40_000); // they stay to watch
        }
    }

    static void heckle(List<SmartAdventurers.Brain> fans, Player a, Player b) {
        if (fans.isEmpty()) return;
        SmartAdventurers.Brain f = fans.get(RNG.nextInt(fans.size()));
        Player fav = RNG.nextBoolean() ? a : b;
        say(f.bot, BotChat.pick("Go on " + fav.getUsername() + "!", "My money's on " + fav.getUsername() + ".", "Come on, put your back into it!", "This is better than the Exchange.", "Someone call the guards!", "Get in there!", "Ooh, close one!", "Go on, you can do it!", "My lunch is riding on this!"));
        anim(f.bot, RNG.nextBoolean() ? 862 : 865);
    }

    /** Two spectators bet on opposite sides; settled with the result. Returns {backer of a, backer of b, stake} or null. */
    static Object[] sideBet(List<SmartAdventurers.Brain> fans, Player a, Player b) {
        if (fans.size() < 2 || RNG.nextInt(2) != 0) return null;
        Player x = fans.get(0).bot, y = fans.get(1).bot;
        long st = stakeFor(x, y) / 2;
        if (st < 50) return null;
        BotChat.speak(x, y, java.util.Collections.singletonList(String.format("%,d on %s, %s?", st, a.getUsername(), y.getUsername())), 1);
        BotChat.speak(y, x, java.util.Collections.singletonList(BotChat.pick("Done. " + b.getUsername() + " all day.", "You're on.")), 3);
        return new Object[]{x, y, st};
    }

    static void settle(Player winner, Player loser, long stake, Object[] side, Player a, String what, Player crowd) {
        boolean paid = stake > 0 && winner.isActive() && loser.isActive() && Ledger.Wallet.pay(loser, stake);
        if (paid) Ledger.Wallet.give(winner, stake);
        if (side != null) {
            Player x = (Player) side[0], y = (Player) side[1]; long st = (Long) side[2];
            Player sw = winner == a ? x : y, sl = winner == a ? y : x;
            if (sw.isActive() && sl.isActive() && Ledger.Wallet.pay(sl, st)) { Ledger.Wallet.give(sw, st); say(sw, BotChat.pick("Pay up!", "Easiest " + st + " I've made all day.")); }
        }
        String key = winner.getUsername().toLowerCase(Locale.ROOT).compareTo(loser.getUsername().toLowerCase(Locale.ROOT)) < 0 ? winner.getUsername() + "|" + loser.getUsername() : loser.getUsername() + "|" + winner.getUsername();
        int[] rec = RIVALRY.computeIfAbsent(key.toLowerCase(Locale.ROOT), k -> new int[2]);
        rec[key.toLowerCase(Locale.ROOT).startsWith(winner.getUsername().toLowerCase(Locale.ROOT)) ? 0 : 1]++;
        int total = rec[0] + rec[1];
        String st = String.format("%,d gp", stake);
        BotChat.speak(winner, loser, java.util.Collections.singletonList(total > 1 ? BotChat.pick("That's " + Math.max(rec[0], rec[1]) + " to " + Math.min(rec[0], rec[1]) + " now.", "Again? Same result.") : BotChat.pick("Ha! " + (paid ? st + ", cheers." : "Pride's mine."), "And THAT is how it's done.")), 1);
        BotChat.speak(loser, winner, java.util.Collections.singletonList(BotChat.pick("Rematch. Tomorrow.", paid ? "Fine. " + st + ". Daylight robbery." : "Fine, you win.", "The crowd was biased.", "I let you have that one.", "You got lucky.", "I want a rematch.", "Next time. Next time.")), 4);
        anim(winner, 862); anim(loser, RNG.nextBoolean() ? 860 : 2113);
        BotChat.news(String.format(BotChat.pick("%s beat %s at %s in front of a crowd%s.", "Word is %s won %s off %s%s."), winner.getUsername(), what.equals("a race") ? "a race" : loser.getUsername(), what.equals("a race") ? loser.getUsername() : what, paid ? " (" + st + " on it)" : "").replace("won a race off", "won a race against"));
        System.out.println("[Spectacle] " + winner.getUsername() + " beat " + loser.getUsername() + " at " + what + (paid ? " for " + st : "") + (crowd != null ? ", watched by " + crowd.getUsername() : ""));
    }

    static void armWrestle(Player a, Player b, List<SmartAdventurers.Brain> fans, Player crowd) {
        long stake = stakeFor(a, b);
        List<Runnable> s = new ArrayList<>();
        Object[][] side = new Object[1][];
        int[] score = new int[2];
        s.add(() -> { a.face(b); b.face(a); say(a, BotChat.pick(b.getUsername() + ". Arm wrestle. " + (stake > 0 ? String.format("%,d", stake) + " gp" : "Pride") + " on it.", "Right " + b.getUsername() + ", best of three. Table's free.")); anim(a, 864); gather(fans, a, b); });
        s.add(() -> { say(b, BotChat.pick("You're on.", "Easy money.", "Bring it.")); anim(b, 858); side[0] = sideBet(fans, a, b); });
        for (int r = 0; r < 3; r++) {
            final int round = r;
            s.add(() -> { anim(a, 859); anim(b, 859); if (round == 0) say(a, "Three, two, one..."); heckle(fans, a, b); });
            s.add(() -> {
                if (score[0] == 2 || score[1] == 2) return;
                int ra = a.getSkills().getLevel(Skills.STRENGTH) + RNG.nextInt(40), rb = b.getSkills().getLevel(Skills.STRENGTH) + RNG.nextInt(40);
                Player rw = ra >= rb ? a : b; score[ra >= rb ? 0 : 1]++;
                say(rw, BotChat.pick("Round to me!", "Ha! " + score[0] + "-" + score[1] + ".", "Feel that?", "Ha! Mine!", "Too slow!", "Point to me."));
            });
        }
        s.add(() -> { Player w = score[0] >= score[1] ? a : b; settle(w, w == a ? b : a, stake, side[0], a, "an arm wrestle", crowd); });
        run(s, 3);
    }

    static final int[] DANCES = {866, 2106, 2107, 2108, 2109, 865};

    static void danceOff(Player a, Player b, List<SmartAdventurers.Brain> fans, Player crowd) {
        long stake = stakeFor(a, b);
        List<Runnable> s = new ArrayList<>();
        Object[][] side = new Object[1][];
        int[] pts = new int[2];
        s.add(() -> { a.face(b); b.face(a); say(a, BotChat.pick("Dance-off, " + b.getUsername() + ". The crowd decides.", b.getUsername() + "! Dance-off. Loser buys the lobsters.")); gather(fans, a, b); });
        s.add(() -> { say(b, BotChat.pick("Oh it's on.", "You'll regret this.", "Watch and learn.")); side[0] = sideBet(fans, a, b); });
        for (int r = 0; r < 3; r++) {
            s.add(() -> { int m = DANCES[RNG.nextInt(DANCES.length)]; anim(a, m); pts[0] += a.getSkills().getLevel(Skills.AGILITY) / 3 + RNG.nextInt(30); });
            s.add(() -> { int m = DANCES[RNG.nextInt(DANCES.length)]; anim(b, m); pts[1] += b.getSkills().getLevel(Skills.AGILITY) / 3 + RNG.nextInt(30); heckle(fans, a, b); });
        }
        s.add(() -> {
            for (SmartAdventurers.Brain f : fans) { Player pick = pts[0] >= pts[1] ? a : b; say(f.bot, BotChat.pick(pick.getUsername() + " wins that.", "Has to be " + pick.getUsername() + ".")); anim(f.bot, 865); break; }
            Player w = pts[0] >= pts[1] ? a : b; settle(w, w == a ? b : a, stake, side[0], a, "a dance-off", crowd);
        });
        run(s, 3);
    }

    static final int[] BOAST_SKILLS = {Skills.MINING, Skills.FISHING, Skills.WOODCUTTING, Skills.COOKING, Skills.STRENGTH, Skills.ATTACK, Skills.DEFENCE, Skills.THIEVING, Skills.AGILITY};

    /** "Bet my Mining's higher than yours": settled on the real levels. */
    static void boast(Player a, Player b, List<SmartAdventurers.Brain> fans, Player crowd) {
        int sk = BOAST_SKILLS[RNG.nextInt(BOAST_SKILLS.length)];
        String name = Skills.SKILL_NAME[sk];
        long stake = stakeFor(a, b) / 2;
        int la = a.getSkills().getStaticLevel(sk), lb = b.getSkills().getStaticLevel(sk);
        List<Runnable> s = new ArrayList<>();
        s.add(() -> { a.face(b); say(a, BotChat.pick("Bet my " + name + "'s higher than yours, " + b.getUsername() + ".", b.getUsername() + ", " + name + ". Bet I've got you beat.") + (stake > 0 ? " " + String.format("%,d", stake) + " gp." : "")); gather(fans, a, b); });
        s.add(() -> { say(b, BotChat.pick("Go on then. What is it?", "Not a chance. Numbers.")); });
        s.add(() -> { say(a, name + " " + la + "."); anim(a, 857); });
        s.add(() -> { say(b, la == lb ? "Same. " + lb + "." : name + " " + lb + (lb > la ? ". Unlucky." : ". Hm.")); anim(b, lb >= la ? 862 : 860); });
        s.add(() -> { if (la == lb) { say(a, "Draw then. Pint's on nobody."); return; } Player w = la > lb ? a : b; settle(w, w == a ? b : a, stake, null, a, name + " levels", crowd); });
        run(s, 3);
    }

    /** Follow the leader: one bot does emotes, the rest copy; whoever slips is out. Pure fun, no money. */
    static void followTheLeader(Player a, Player b, List<SmartAdventurers.Brain> fans, Player crowd) {
        List<Player> players = new ArrayList<>(); players.add(b); for (SmartAdventurers.Brain f : fans) players.add(f.bot);
        List<Runnable> s = new ArrayList<>();
        s.add(() -> { say(a, BotChat.pick("Follow the leader! Copy me or you're out.", "Simon says... everyone copy me!")); gather(fans, a, b); });
        int[] seq = {863, 858, 866, 2109, 865};
        for (int r = 0; r < 4; r++) {
            final int m = seq[RNG.nextInt(seq.length)];
            s.add(() -> {
                anim(a, m);
                for (Player p : new ArrayList<>(players)) {
                    if (RNG.nextInt(5) == 0 && players.size() > 1) { anim(p, 856); say(p, BotChat.pick("Wait, what?", "Oh come on!", "That wasn't it?", "No! I knew that one!", "Rigged!", "Oops.")); players.remove(p); }
                    else anim(p, m);
                }
            });
        }
        s.add(() -> { Player w = players.isEmpty() ? a : players.get(0); say(a, w == a ? "Nobody kept up. I win." : w.getUsername() + " wins! Never slipped once."); anim(w, 862); System.out.println("[Spectacle] follow-the-leader by " + a.getUsername() + " for " + (crowd != null ? crowd.getUsername() : "?") + ", winner " + w.getUsername()); });
        run(s, 3);
    }

    // ------------------------------------------------------------------ a challenge to the watching player

    static void challengeHuman(SmartAdventurers.Brain me, Player h, long now) {
        me.bot.face(h);
        say(me.bot, BotChat.pick(h.getUsername() + "! Fancy an arm wrestle? Say \"you're on\".", "Oi " + h.getUsername() + ", arm wrestle? Pride only. Say \"you're on\".", h.getUsername() + ", bet you can't beat me at arm wrestling. Say \"you're on\"."));
        CHALLENGES.put(h.getUsername().toLowerCase(Locale.ROOT), new Object[]{me, now + 30_000});
    }

    static boolean heard(Player h, String text) {
        Object[] c = CHALLENGES.get(h.getUsername().toLowerCase(Locale.ROOT));
        if (c == null) return false;
        if (System.currentTimeMillis() > (Long) c[1]) { CHALLENGES.remove(h.getUsername().toLowerCase(Locale.ROOT)); return false; }
        String t = text.toLowerCase(Locale.ROOT);
        if (!(t.contains("you're on") || t.contains("youre on") || t.contains("ur on") || t.equals("yes") || t.equals("ok") || t.contains("bring it") || t.contains("sure"))) {
            if (t.equals("no") || t.contains("no thanks")) { CHALLENGES.remove(h.getUsername().toLowerCase(Locale.ROOT)); say(((SmartAdventurers.Brain) c[0]).bot, "Scared. Fair enough."); return true; }
            return false;
        }
        CHALLENGES.remove(h.getUsername().toLowerCase(Locale.ROOT));
        Player a = ((SmartAdventurers.Brain) c[0]).bot;
        if (a == null || !a.isActive()) return false;
        List<Runnable> s = new ArrayList<>();
        int[] score = new int[2];
        s.add(() -> { a.face(h); say(a, "Right. Elbows on the table. Best of three."); anim(a, 858); });
        for (int r = 0; r < 3; r++) {
            s.add(() -> { anim(a, 859); });
            s.add(() -> {
                if (score[0] == 2 || score[1] == 2) return;
                int ra = a.getSkills().getLevel(Skills.STRENGTH) + RNG.nextInt(40), rh = h.getSkills().getLevel(Skills.STRENGTH) + RNG.nextInt(40);
                score[ra > rh ? 0 : 1]++;
                say(a, ra > rh ? BotChat.pick("Round to me!", "Ha! " + score[0] + "-" + score[1] + ".", "Ha! Mine!", "Too slow!", "Point to me.") : BotChat.pick("Argh. " + score[0] + "-" + score[1] + ".", "Lucky..."));
            });
        }
        s.add(() -> {
            boolean botWins = score[0] > score[1];
            say(a, botWins ? BotChat.pick("Better luck next time, " + h.getUsername() + ".", "Strength training, mate. Look into it.") : BotChat.pick("Fair play, " + h.getUsername() + ". You're stronger than you look.", "Rematch next time. Good on you."));
            anim(a, botWins ? 862 : 2113);
            BotChat.Trust.add(h, a, 1);
            BotChat.news(String.format(botWins ? "%2$s beat %1$s at arm wrestling. Rematch expected." : "%1$s beat %2$s at arm wrestling in front of everyone.", h.getUsername(), a.getUsername()));
            System.out.println("[Spectacle] " + a.getUsername() + " arm-wrestled player " + h.getUsername() + ": " + (botWins ? "bot" : "player") + " won");
        });
        run(s, 3);
        return true;
    }

    // ------------------------------------------------------------------ batch 16 item3: a party at the Falador party room

    // Light version: no lever, chest, balloons or items. At most once every PARTY_GAP, and only while a real player is
    // online, the clan chat hears about a party; up to PARTY_GUESTS bots already on a break (and with a walkable route,
    // within PARTY_RANGE tiles) walk to the party room floor and dance there with the show emotes for a minute or two,
    // then carry on with their day. Checked from the brains' think (every 30 s at most): no scans of its own.
    static final Location PARTY_ROOM = Location.create(3045, 3378, 0); // the dance floor (BalloonManager drops round 3045,3378)
    static final long PARTY_GAP = 4 * 3_600_000L; static final int PARTY_GUESTS = 6, PARTY_RANGE = 200;
    static long nextPartyCheck, nextParty = System.currentTimeMillis() + (Boolean.getBoolean("test.b16c") ? 0 : 20 * 60_000L);
    static volatile long partyStart, partyEnd; static volatile int parties, partyInvited, partyArrived, partyDances, partySkipped;
    static volatile int partyDoors; static final Map<SmartAdventurers.Brain, Location> PARTY_LAST = new java.util.concurrent.ConcurrentHashMap<>(); // batch 17 item1: the guest's tile at its last fallback walk

    static void partyCheck(long now) {
        if (now < nextPartyCheck) return;
        nextPartyCheck = now + 30_000;
        if (now < nextParty || now < partyEnd) return;
        boolean human = false;
        for (Player h : Repository.getPlayers()) if (h != null && h.isActive() && !h.isArtificial()) { human = true; break; }
        if (!human) return;
        if (!Boolean.getBoolean("test.b16c") && RNG.nextInt(10) != 0) return; // not on the dot
        List<SmartAdventurers.Brain> guests = new ArrayList<>(); int far = 0;
        for (SmartAdventurers.Brain b : SmartAdventurers.BRAINS.values()) {
            if (guests.size() >= PARTY_GUESTS) break;
            if (!idle(b) || now >= b.idleUntil || b.extraKind != 0 || b.companion != null || b.errandFor != null || Deals.involved(b.bot) || QuestRunner.active(b.bot)) continue;
            Location at = b.bot.getLocation();
            if (at.getZ() != 0 || at.getDistance(PARTY_ROOM) > PARTY_RANGE || !NavPlanner.reachable(at, PARTY_ROOM)) { far++; continue; } // no route or too far: not this one
            guests.add(b);
        }
        if (guests.size() < 2) { nextParty = now + 20 * 60_000L; partySkipped++; if (Boolean.getBoolean("test.b16c")) System.out.println("[Spectacle] party skipped: " + guests.size() + " guest(s), " + far + " too far or no route"); return; }
        nextParty = now + PARTY_GAP; parties++;
        partyStart = now + (Boolean.getBoolean("test.b16c") ? 60_000 : 120_000); partyEnd = partyStart + 90_000 + RNG.nextInt(30_000);
        StringBuilder names = new StringBuilder();
        for (SmartAdventurers.Brain b : guests) {
            b.partyUntil = partyEnd; b.partyArrived = false; b.partyAt = PARTY_ROOM.transform(RNG.nextInt(9) - 4, RNG.nextInt(7) - 3, 0);
            long was = b.idleUntil; b.idleUntil = Math.max(b.idleUntil, partyEnd + 5_000);
            if (b.extraBreak == was) b.extraBreak = b.idleUntil; if (b.extraRollBreak == was) b.extraRollBreak = b.idleUntil; // batch 17 item1: the same break, so its one extra (and its roll) still count
            b.nextSpectacle = Math.max(b.nextSpectacle, partyEnd + 60_000); b.nextExtra = Math.max(b.nextExtra, partyEnd + 60_000);
            partyInvited++; if (names.length() > 0) names.append(", "); names.append(b.bot.getUsername());
        }
        Player cb = Companions.clanBot();
        String line = BotChat.pick("Party at the Falador party room! Come and dance.", "Who's up for a party? Falador party room, now!", "Dance party in the Falador party room. Bring your best moves.");
        if (cb != null) Companions.clanSay(cb, line);
        System.out.println("[Spectacle] party at the Falador party room: " + guests.size() + " guests (" + names + "), " + far + " too far or no route, " + (cb != null ? "announced by " + cb.getUsername() + " in the clan chat" : "no clan bot to announce it"));
    }

    /** A guest's part, from its think: walk to the floor, then dance until the party ends. */
    static void partyTick(SmartAdventurers.Brain me, long now) {
        Player bot = me.bot; Location at = bot.getLocation(); double d = at.getDistance(me.partyAt);
        if (!me.partyArrived) {
            if (d <= 3) { me.partyArrived = true; partyArrived++; if (RNG.nextInt(2) == 0) say(bot, BotChat.pick("Party!", "Did someone say party?", "I'm here, where's the music?", "Right, who's dancing?")); }
            else if (now > partyStart + 45_000) { me.partyUntil = 0; System.out.println("[Spectacle] " + bot.getUsername() + " didn't make the party (" + (int) d + " tiles off)"); return; } // never got there: the break as before
            else {
                if (!bot.getWalkingQueue().isMoving()) {
                    Location nav = null; try { nav = NavWalker.ON ? NavWalker.step(bot, me.partyAt) : null; } catch (Throwable ignored) { }
                    Location step = nav != null ? nav : d <= 25 ? me.partyAt : Location.create(at.getX() + (int) Math.round((me.partyAt.getX() - at.getX()) * 25 / d), at.getY() + (int) Math.round((me.partyAt.getY() - at.getY()) * 25 / d), 0);
                    Location last = PARTY_LAST.put(me, at); // batch 17 item1: on the straight fallback, a guest still on the same tile as last think opens the shut door or gate in its way, like a player
                    if (nav == null && at.equals(last)) try { if (QuestSteps.openDoorToward(bot, me.partyAt, 5)) { partyDoors++; return; } } catch (Throwable ignored) { }
                    try { ((core.game.bots.AIPlayer) bot).walkToPosSmart(step); } catch (Throwable ignored) { }
                }
                return;
            }
        }
        if (now < partyStart) { if (RNG.nextInt(3) == 0) anim(bot, RNG.nextBoolean() ? 863 : 862); return; } // waving the others in
        anim(bot, DANCES[RNG.nextInt(DANCES.length)]); partyDances++;
        if (RNG.nextInt(8) == 0) say(bot, BotChat.pick("Best party in Asgarnia!", "Mind my feet.", "Who brought the drinks?", "Pete would be proud.", "Woo!", "One more song!"));
        else if (RNG.nextInt(5) == 0 && !bot.getWalkingQueue().isMoving()) try { me.scriptAPI.randomWalkTo(me.partyAt, 2); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ races between two bots on the same spot

    static final class Race {
        final SmartAdventurers.Brain a, b; final String label; final long stake, until; int ra = -1, rb = -1;
        Race(SmartAdventurers.Brain a, SmartAdventurers.Brain b, String label, long stake) { this.a = a; this.b = b; this.label = label; this.stake = stake; this.until = System.currentTimeMillis() + 25 * 60_000; }
    }

    /** A bot starting a gathering trip where another bot is already working the same job: sometimes it's a race. */
    static void maybeRace(SmartAdventurers.Brain me, SmartAdventurers.Job job) {
        if (job == null || job.hunt != null || job.skill < 0 || RNG.nextInt(3) != 0) return;
        for (Race r : RACES) if (r.a == me || r.b == me) return;
        for (SmartAdventurers.Brain o : SmartAdventurers.BRAINS.values()) {
            if (o == me || o.runner == null || o.lastJob != job || o.bot == null || o.bot.getLocation().getDistance(me.bot.getLocation()) > 25) continue;
            boolean busy = false; for (Race r : RACES) if (r.a == o || r.b == o) busy = true;
            if (busy) continue;
            long stake = stakeFor(me.bot, o.bot) / 2;
            Race race = new Race(me, o, job.label().toLowerCase(Locale.ROOT), stake);
            race.rb = -1; RACES.add(race);
            say(me.bot, BotChat.pick("Race you, " + o.bot.getUsername() + ". Most " + race.label + " by the bank wins" + (stake > 0 ? ", " + String.format("%,d", stake) + " gp" : "") + ".", o.bot.getUsername() + "! Bet I bank more than you this trip."));
            BotChat.speak(o.bot, me.bot, java.util.Collections.singletonList(BotChat.pick("You're on.", "Pfft. Easy.", "Get mining then. Or whatever.")), 3);
            races++;
            return;
        }
    }

    /** A trip finished: record the haul, and settle the race when both are in. */
    static void tripDone(SmartAdventurers.Brain me, int collected) {
        long now = System.currentTimeMillis();
        for (Race r : RACES) {
            if (now > r.until) { RACES.remove(r); continue; }
            if (r.a == me) r.ra = collected; else if (r.b == me) r.rb = collected; else continue;
            if (r.ra < 0 || r.rb < 0) { say(me.bot, BotChat.pick(collected + ". Beat that.", "Banked " + collected + ". Your move.")); return; }
            RACES.remove(r);
            if (r.ra == r.rb) { say(me.bot, "Dead heat. " + collected + " each. Unbelievable."); return; }
            SmartAdventurers.Brain w = r.ra > r.rb ? r.a : r.b, l = w == r.a ? r.b : r.a;
            if (w.bot == null || l.bot == null) return;
            settle(w.bot, l.bot, r.stake, null, r.a.bot, "a race", watcher(me.bot));
            return;
        }
    }
}
