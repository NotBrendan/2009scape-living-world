package content.custom;

import core.api.StartupListener;
import core.game.node.entity.npc.NPC;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.command.Command;
import core.game.system.command.CommandMapping;
import core.game.system.command.Privilege;
import core.game.system.communication.ClanEntry;
import core.game.system.communication.ClanRepository;
import core.game.system.task.Pulse;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.map.RegionManager;
import core.game.world.repository.Repository;
import kotlin.Unit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bots that work with a real player (2026-10-21, fourth consultant list):
 *  - Crews: "join my crew" / "follow me": up to three idle adventurers of a fitting combat level follow the player and
 *    fight what the player fights (and whatever attacks the player), eat their own food, and go home on "disband" or
 *    after an hour of nothing. They never loot: the drops are the player's.
 *  - Errands: "fetch me 200 coal": an idle adventurer who can do that job runs the trip, banks the haul and holds it at
 *    the bank for the player at guide price (a callout only that player can take), telling them where it is.
 *  - Partner quests: Shield of Arrav and Heroes' Quest need a partner from the other gang. "I need a Phoenix partner":
 *    an idle adventurer plays the partner and hands over the other half of the certificate, the candlestick (to a Phoenix
 *    member) or Grip's key ring (to a Black Arm member) through the trade window (PlayerTrades.give). batch 15 item6
 *  - The bots' clan chat: a dozen bots sit in the server's default clan chat and talk there (gz on levels, stars), and
 *    `::ask <question>` gets an answer from one of them wherever the player is.
 */
public final class Companions implements StartupListener {
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("companions", "true"));
    static final java.util.Random RNG = new java.util.Random();
    /** Trials only (-Dcompanions.test): bots treated as players so the self-test can drive the chat. */
    static final Set<String> TEST_HUMANS = new HashSet<>();
    static final Map<String, Long> TAKEN = new ConcurrentHashMap<>(); // player -> when a bot last took their crew/errand ask (one bot answers, not five)

    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }
    static boolean human(Player p) { return p != null && (!p.isArtificial() || TEST_HUMANS.contains(key(p))); }

    static boolean idle(SmartAdventurers.Brain br) {
        return br != null && br.bot != null && br.bot.isActive() && br.runner == null && br.shopAt == null && !br.delivering && br.graveAt == null
                && br.mill == 0 && br.agiStep < 0 && br.companion == null && br.errandFor == null && !AfkTasks.RUNNING.containsKey(key(br.bot))
                && !PlayerTrades.SESSIONS.containsKey(br.bot) && !Deals.involved(br.bot) && !QuestRunner.active(br.bot); // a questing bot is busy (audit 10-22)
    }

    // ------------------------------------------------------------ chat: what a player can ask for

    static final Pattern CREW = Pattern.compile("\\b(join (?:my|the|our) (?:crew|team|party|group)|come (?:fight|kill|slay|boss|hunt)|help me (?:fight|kill|slay|boss|hunt|with (?:these|them|this))|fight with me|team up|follow me|come with me|be my (?:bodyguard|partner in crime))\\b");
    static final Pattern DISBAND = Pattern.compile("\\b(disband|go home|stop following|that'?s all|thanks lads|thanks guys|dismissed|you'?re free|leave me|we'?re done|all done|go back to work)\\b");
    static final Pattern ERRAND = Pattern.compile("\\b(?:fetch|bring|get|grab|gather|collect|mine|chop|cut|fish|catch|kill|farm)(?: me| us)?\\s+(?:some\\s+)?(\\d{1,4}k?|a hundred|two hundred|fifty|a dozen)\\s+(?:of\\s+)?([a-z' ]{3,40}?)(?:\\s+(?:please|pls|plz|for me|mate|m8|would you|will you|can you))*[?.! ]*$");
    static final Pattern ASKED = Pattern.compile("\\b(?:fetch|bring|get|grab|gather|collect|mine|chop|cut|fish|catch|kill|farm) (?:me|us)\\b|\\b(?:can|could|will|would) you\\b|\\bplease\\b|\\bpls\\b|\\bfor me\\b"); // a request, not "i'll go cut 100 yews"
    static boolean asking; // ::ask in progress: no recruiting or errands from afar
    static final Pattern PARTNER = Pattern.compile("\\b(partner|other half|black ?arm|phoenix|candlestick|miscellaneous key|grip'?s key|shield of arrav|heroes'? quest)\\b");

    /** Called first from the bots' chat brain. Null when the line isn't for this file. */
    static List<String> chat(Player bot, Player speaker, String t, boolean addressed) {
        if (!ON || !human(speaker) || bot == null || asking) return null;
        long now = System.currentTimeMillis();
        SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(key(bot));
        List<String> out = new ArrayList<>();
        Crew mine = CREWS.get(key(speaker));
        if (mine != null && DISBAND.matcher(t).find() && (addressed || bot.getLocation().getDistance(speaker.getLocation()) <= 15)) {
            boolean inIt = false; for (SmartAdventurers.Brain b : mine.bots) if (b.bot == bot) inIt = true;
            if (!inIt && !addressed) return null;
            disband(speaker, BotChat.pick("Good hunting, " + speaker.getUsername() + ".", "Right, back to it then.", "Cheers. Shout if you need us again.", "That was fun. See you about."));
            return out; // the crew say their own goodbyes
        }
        if (CREW.matcher(t).find()) {
            if (br == null) { if (addressed) out.add(BotChat.pick("I'm not one for fighting, " + speaker.getUsername() + ". Ask an adventurer.", "Not me. I've got a trade to keep.")); return addressed ? out : null; }
            if (!addressed && now - TAKEN.getOrDefault(key(speaker), 0L) < 8_000) return out; // another bot just answered
            String r = recruit(bot, speaker, t.matches(".*\\b(crew|team|party|group|us|all of you|lads|guys)\\b.*"));
            if (r == null) return null;
            TAKEN.put(key(speaker), now);
            out.add(r); return out;
        }
        Matcher m = ERRAND.matcher(t);
        if (m.find()) {
            int qty = qty(m.group(1));
            String what = m.group(2).trim();
            if (!addressed && !ASKED.matcher(t).find()) return null; // "gonna fish 50 lobsters" is not an order (review 10-22)
            if (what.matches("^(the|a|an|my|your|me|us|it|them|coins|gp|gold|money)$")) return null;
            Integer id = TradeTalk.resolve(what);
            if (id == null && what.endsWith("s")) id = TradeTalk.resolve(what.substring(0, what.length() - 1));
            if (id == null && what.endsWith("ies")) id = TradeTalk.resolve(what.substring(0, what.length() - 3) + "y");
            if (id == null) { if (addressed) out.add(BotChat.pick("Fetch you what, sorry? Don't know '" + what + "'.", "Never heard of " + what + ".")); return addressed ? out : null; }
            if (br == null) { if (addressed) out.add(BotChat.pick("Not my line, " + speaker.getUsername() + ". Ask an adventurer.", "I don't gather, sorry. Try one of the adventurers.")); return addressed ? out : null; }
            if (!addressed && now - TAKEN.getOrDefault(key(speaker), 0L) < 8_000) return out;
            String r = errand(bot, speaker, id, qty);
            if (r == null) return null;
            TAKEN.put(key(speaker), now);
            out.add(r); return out;
        }
        if (PARTNER.matcher(t).find() && t.matches(".*\\b(need|needs|looking|want|wants|help|be my|anyone|got|can you|could you|will you|have|has|other)\\b.*")) {
            if (br == null) { if (addressed) out.add("Gang business? Not me. Ask one of the adventurers."); return addressed ? out : null; }
            if (!addressed && now - TAKEN.getOrDefault(key(speaker), 0L) < 8_000) return out;
            String r = partner(bot, speaker, t);
            if (r == null) return null;
            TAKEN.put(key(speaker), now);
            out.add(r); return out;
        }
        return null;
    }

    static int qty(String s) {
        switch (s) { case "a hundred": return 100; case "two hundred": return 200; case "fifty": return 50; case "a dozen": return 12; }
        try { return s.endsWith("k") ? Integer.parseInt(s.substring(0, s.length() - 1)) * 1000 : Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    // ------------------------------------------------------------ crews

    static final class Crew {
        Player human; final List<SmartAdventurers.Brain> bots = new ArrayList<>();
        long since = System.currentTimeMillis(), lastFight = System.currentTimeMillis();
        final Map<SmartAdventurers.Brain, long[]> far = new java.util.HashMap<>(); // audit C-1: per bot {fell behind at, catch-up teleports}; one shared timer let a member left on another floor wait for ever
    }
    static final Map<String, Crew> CREWS = new ConcurrentHashMap<>();
    static final Map<SmartAdventurers.Brain, Integer> LAST_BITE = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>()); // combat review 10-23 #13: world tick of each crewmate's last bite

    /** "join my crew": the bot that heard it, plus up to two more idle adventurers within 10 tiles when a crew was asked for. */
    static String recruit(Player bot, Player human, boolean wholeCrew) {
        SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(key(bot));
        if (br == null) return null;
        if (br.companion == human) return BotChat.pick("I'm already with you, " + human.getUsername() + ".", "Already following. Lead on.");
        if (!idle(br)) return BotChat.pick("Mid-trip, sorry. Catch me at a bank.", "Can't right now, hands full.", "Busy. Ask me between trips.");
        int mine = bot.getProperties().getCurrentCombatLevel(), theirs = human.getProperties().getCurrentCombatLevel();
        if (mine < theirs * 0.6) return BotChat.pick("I'd just get flattened next to you, " + human.getUsername() + ". Find someone tougher.", "You're way above me. I'd be a liability.");
        Crew c = CREWS.computeIfAbsent(key(human), k -> new Crew());
        c.human = human;
        if (c.bots.size() >= 3) return "You've got three of us already. That's a crew.";
        join(c, br, human);
        int more = 0;
        if (wholeCrew) for (SmartAdventurers.Brain o : SmartAdventurers.BRAINS.values()) {
            if (c.bots.size() >= 3) break;
            if (o == br || o.bot == human || !idle(o) || o.bot.getLocation().getZ() != human.getLocation().getZ() || o.bot.getLocation().getDistance(human.getLocation()) > 10) continue;
            if (o.bot.getProperties().getCurrentCombatLevel() < theirs * 0.6) continue;
            join(c, o, human); more++;
            BotChat.say(o.bot, BotChat.pick("Count me in.", "Right behind you, " + human.getUsername() + ".", "Let's go then.", "Me too."));
        }
        return BotChat.pick("On your lead, " + human.getUsername() + ". Say 'disband' when you're done.", "Alright, I'm with you. I'll hit what you hit.", "Lead on. I've got food.") + (more > 0 ? " " + (more == 1 ? "One more" : more + " more") + " coming." : "");
    }

    static void join(Crew c, SmartAdventurers.Brain br, Player human) {
        br.companion = human;
        br.nextThink = System.currentTimeMillis() + 2_000;
        c.bots.add(br);
        try { br.dressBack(); } catch (Throwable ignored) { } // audit C-2: the helm and shield stashed at the Exchange go back on
        try { br.buyFood(Math.max(20, human.getProperties().getCurrentCombatLevel())); } catch (Throwable ignored) { } // into the bank, like a trip
        try { // then from the bank into the pack, like a player packing for a trip
            int moved = 0;
            for (int f : AfkTasks.Runner.FOOD) {
                int n = br.bot.getBank().getAmount(f); if (n <= 0) continue;
                int take = Math.min(n, Math.min(12 - moved, br.bot.getInventory().freeSlots())); if (take <= 0) break;
                if (br.bot.getBank().remove(new Item(f, take))) { br.bot.getInventory().add(new Item(f, take)); moved += take; }
            }
        } catch (Throwable ignored) { }
        try { br.bot.getWalkingQueue().reset(); } catch (Throwable ignored) { }
        BotChat.activity(br.bot, () -> "in " + human.getUsername() + "'s crew");
        System.out.println("[Companions] " + br.bot.getUsername() + " joined " + human.getUsername() + "'s crew (combat " + br.bot.getProperties().getCurrentCombatLevel() + " with " + human.getProperties().getCurrentCombatLevel() + ")");
    }

    static String disband(Player human, String why) {
        Crew c = CREWS.remove(key(human));
        if (c == null) return null;
        for (SmartAdventurers.Brain br : new ArrayList<>(c.bots)) leave(br, why);
        System.out.println("[Companions] " + human.getUsername() + "'s crew disbanded (" + c.bots.size() + " bots, " + (System.currentTimeMillis() - c.since) / 60_000 + " min)");
        return c.bots.size() + " bots";
    }

    static void leave(SmartAdventurers.Brain br, String why) {
        if (br.companion == null) return;
        br.companion = null;
        BotChat.clearActivity(br.bot);
        br.nextThink = System.currentTimeMillis() + 10_000;
        try { br.bot.getProperties().getCombatPulse().stop(); } catch (Throwable ignored) { }
        try { int hp = br.bot.getSkills().getLifepoints(), max = br.bot.getSkills().getMaximumLifepoints(); if (hp < max / 2) for (int f : AfkTasks.Runner.FOOD) if (br.bot.getInventory().getAmount(f) > 0) { br.scriptAPI.forceEat(f); break; } } catch (Throwable ignored) { } // low after the fight: eat before the think loop takes over
        if (why != null) BotChat.say(br.bot, why);
        // food back in the bank happens at the next bank visit; the normal think loop takes it from here (next trip, or home)
        try { // too far from any bank for the next trip to make sense: a paid teleport, as a player would
            Location bank = Watchdog.nearestBank(br.bot.getLocation(), br.bot); // batch 22-1: gate, toll and sea aware
            if (bank != null && bank.getDistance(br.bot.getLocation()) > 60 && Ledger.Wallet.payTeleport(br.bot)) Spectacle.teleport(br.bot, bank.transform(RNG.nextInt(3) - 1, RNG.nextInt(3) - 1, 0)); // batch 15 item1
        } catch (Throwable ignored) { }
    }

    /** Every 2 ticks: follow, fight what the player fights, eat, give up when the player is gone. */
    static void tickCrews() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Crew> e : CREWS.entrySet()) {
            Crew c = e.getValue(); Player h = c.human;
            if (h == null || !h.isActive()) { for (SmartAdventurers.Brain br : new ArrayList<>(c.bots)) leave(br, BotChat.pick("They've gone. Back to work.", "Boss logged. Home then.")); CREWS.remove(e.getKey()); continue; }
            if (now - c.lastFight > 60 * 60_000L) { for (SmartAdventurers.Brain br : new ArrayList<>(c.bots)) leave(br, BotChat.pick("An hour of standing about. I'm off.", "Nothing doing. See you, " + h.getUsername() + ".")); CREWS.remove(e.getKey()); continue; }
            NPC target = null;
            try { Object v = h.getProperties().getCombatPulse().getVictim(); if (v instanceof NPC && ((NPC) v).isActive()) target = (NPC) v; } catch (Throwable ignored) { }
            if (target == null) try { for (NPC n : RegionManager.getLocalNpcs(h, 8)) { if (n != null && n.isActive() && n.getProperties().getCombatPulse().getVictim() == h) { target = n; break; } } } catch (Throwable ignored) { }
            if (target != null) c.lastFight = now;
            for (SmartAdventurers.Brain br : new ArrayList<>(c.bots)) {
                Player b = br.bot;
                if (b == null || !b.isActive() || br.companion != h) { c.bots.remove(br); continue; }
                if (br.graveAt != null) { c.bots.remove(br); leave(br, BotChat.pick("Died. Off to get my things back.", "That's me dead. Going for my stuff.")); continue; } // its own grave run comes first (review 10-22)
                if (br.blessUntil > now) continue; // batch 15 item3: blessing a crewmate's gravestone; back to following after
                br.nextThink = now + 4_000; // the think loop stays out of it
                double d = b.getLocation().getDistance(h.getLocation());
                boolean sameFloor = b.getLocation().getZ() == h.getLocation().getZ();
                long[] fr = c.far.computeIfAbsent(br, k -> new long[6]); // {farSince, catchUps, closerSince, best d x10, player x, player y}, this bot's own
                // Pathing review 10-23 C-1: within 40 tiles but walled off (a gate, a toll, a shut door), the bot re-pathed every 2
                // ticks for ever. Not one tile closer in 20 s while the player stood (within 3 tiles of) still and nothing was being
                // fought: treated as fallen behind, so the catch-up below (paid teleport, or give up) takes it.
                boolean fighting = false; try { fighting = b.inCombat() || b.getProperties().getCombatPulse().isAttacking(); } catch (Throwable ignored) { }
                boolean blocked = false;
                if (sameFloor && d > 6 && target == null && !fighting) {
                    Location hl = h.getLocation();
                    if (fr[2] == 0 || Math.abs(hl.getX() - fr[4]) > 3 || Math.abs(hl.getY() - fr[5]) > 3 || d * 10 < fr[3] - 10) { fr[2] = now; fr[3] = (long) (d * 10); fr[4] = hl.getX(); fr[5] = hl.getY(); }
                    else if (now - fr[2] > 20_000) blocked = true;
                } else fr[2] = 0;
                if (d > 40 || !sameFloor || blocked) { // fallen behind: catch up with a paid teleport, or give up after two minutes
                    if (fr[0] == 0) fr[0] = now;
                    else if (now - fr[0] > 15_000 && fr[1] >= 3) { c.bots.remove(br); c.far.remove(br); leave(br, "Can't keep up with you, " + h.getUsername() + ". Heading back."); }
                    else if (now - fr[0] > 15_000 && !Gates.canEnter(b, h.getLocation())) { c.bots.remove(br); c.far.remove(br); System.out.println("[Companions] " + b.getUsername() + " can't follow " + h.getUsername() + " into " + Gates.missing(b, h.getLocation()) + " ground at " + h.getLocation()); leave(br, "Can't follow you in there, " + h.getUsername() + ". Not without " + Gates.missing(b, h.getLocation()) + "."); } // pathing review 10-23 C-2: the catch-up teleport put bots inside quest and guild areas they may not enter
                    else if (now - fr[0] > 15_000 && Ledger.Wallet.payTeleport(b)) { fr[1]++; fr[2] = 0; Location near = Surveyors.Surveyor.walkable(h.getLocation().transform(RNG.nextInt(3) - 1, RNG.nextInt(3) - 1, 0)); try { Spectacle.teleport(b, near != null ? near : h.getLocation()); } catch (Throwable ignored) { } fr[0] = 0; } // batch 15 item1
                    else if (now - fr[0] > 120_000) { c.bots.remove(br); c.far.remove(br); leave(br, "Lost you, " + h.getUsername() + ". Heading back."); }
                    continue;
                }
                fr[0] = 0;
                // eat like a player: at 60% with food in the pack; out of food and under 40%: leave
                int hp = b.getSkills().getLifepoints(), max = b.getSkills().getMaximumLifepoints();
                int food = 0; for (int f : AfkTasks.Runner.FOOD) food += b.getInventory().getAmount(f);
                if (hp <= max * 3 / 5 && food > 0) { Integer ate = LAST_BITE.get(br); if (ate != null && GameWorld.getTicks() - ate < 3) continue; for (int f : AfkTasks.Runner.FOOD) if (b.getInventory().getAmount(f) > 0) { try { br.scriptAPI.forceEat(f); LAST_BITE.put(br, GameWorld.getTicks()); } catch (Throwable ignored) { } break; } continue; } // combat review 10-23 #13: once every 3 ticks, like a player (this loop runs every 2)
                if (hp <= max * 2 / 5 && food == 0) { c.bots.remove(br); leave(br, BotChat.pick("Out of food, I'm out!", "No food left. Sorry, " + h.getUsername() + "!")); continue; }
                boolean attacking = false; try { attacking = b.getProperties().getCombatPulse().isAttacking() || b.inCombat(); } catch (Throwable ignored) { }
                if (target != null && target.getLocation().getDistance(b.getLocation()) <= 12) {
                    Object myVictim = null; try { myVictim = b.getProperties().getCombatPulse().getVictim(); } catch (Throwable ignored) { }
                    // The kill stays the player's: a crewmate hits only while it has dealt less damage to this monster than the
                    // player has (so the player is always the most-damage entity and gets the drop), and only where the server
                    // lets a second attacker in (single-way combat refuses: no tagging the player's monster, no message spam).
                    int mine = 0, theirs = 0;
                    try { Map<Integer, Integer> log = target.getImpactHandler().getPlayerImpactLog(); mine = log.getOrDefault(b.getDetails().getUid(), 0); theirs = log.getOrDefault(h.getDetails().getUid(), 0); } catch (Throwable ignored) { } // bots are Players too: their hits land in the player log by uid (review 10-22)
                    boolean mayHit = mine < theirs;
                    try { int lp = target.getSkills().getLifepoints(), mx = target.getSkills().getMaximumLifepoints(); if (lp <= Math.max(5, mx / 4)) mayHit = false; } catch (Throwable ignored) { } // the last quarter is the player's: a bot's killing blow would take the drop
                    if (mayHit) { try { mayHit = b.isAttackable(target, core.game.node.entity.combat.CombatStyle.MELEE, false); } catch (Throwable ignored) { } }
                    if (!mayHit) { if (myVictim == target) { try { b.getProperties().getCombatPulse().stop(); } catch (Throwable ignored) { } } continue; }
                    if (myVictim != target && !(attacking && myVictim instanceof NPC && ((NPC) myVictim).isActive())) { try { b.attack(target); } catch (Throwable ignored) { } }
                    continue;
                }
                if (attacking) continue;
                if (d > 3 && !b.getWalkingQueue().isMoving()) { Location spot = Surveyors.Surveyor.walkable(h.getLocation().transform(RNG.nextInt(5) - 2, RNG.nextInt(5) - 2, 0)); try { ((core.game.bots.AIPlayer) b).walkToPosSmart(spot != null ? spot : h.getLocation()); } catch (Throwable ignored) { } }
                else if (d <= 3 && RNG.nextInt(40) == 0) { try { b.face(h); } catch (Throwable ignored) { } }
            }
            if (c.bots.isEmpty()) CREWS.remove(e.getKey());
        }
    }


    // ------------------------------------------------------------ errands

    /** "fetch me 200 coal": the bot that heard takes the job if it can do it. */
    static String errand(Player bot, Player human, int itemId, int qty) {
        SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(key(bot));
        String item = MarketBrain.name(itemId).toLowerCase(Locale.ROOT);
        if (br == null) return null;
        if (!idle(br)) return BotChat.pick("Mid-trip, sorry. Ask me at a bank.", "Hands full right now. Later?");
        if (!MarketBrain.tradeable(itemId)) return BotChat.pick("Can't trade " + item + ", mate.", "Nobody trades " + item + ".");
        SmartAdventurers.Job j = br.jobFor(itemId);
        boolean cook = false;
        if (j == null) { // "100 lobsters" means cooked ones: fish the raw and cook the haul (review 10-22)
            for (Artisans.Recipe r : Artisans.RECIPES) {
                if (r.product != itemId || r.inIds == null || r.inIds.length != 1 || r.trade == null || !r.trade.toUpperCase(Locale.ROOT).contains("COOK")) continue;
                SmartAdventurers.Job jr = br.jobFor(r.inIds[0]);
                if (jr != null) { j = jr; cook = true; break; }
            }
        }
        if (j == null) return BotChat.pick("I can't get " + item + ". Not my skills, or I don't know where.", "No idea where to get " + item + ", sorry. Ask someone else.");
        int n = Math.max(1, Math.min(qty, 112));
        br.errandFor = human.getUsername(); br.errandId = itemId; br.errandQty = n; br.errandCook = cook;
        br.nextThink = System.currentTimeMillis() + 2_000;
        br.idleUntil = 0;
        try { br.start(j); } catch (Throwable t) { br.errandFor = null; System.out.println("[Companions] errand start: " + t); return "Can't get going, sorry. Ask again later."; }
        System.out.println("[Companions] " + bot.getUsername() + " took an errand from " + human.getUsername() + ": " + n + " x " + MarketBrain.name(itemId) + " (" + j.label() + ")");
        return BotChat.pick("Right, " + n + " " + item + ". I'll leave word when it's banked; you pay guide.", n + " " + item + " coming up. Back at a bank in a bit; trade me there.", "On it. " + n + " " + item + ", guide price when I'm back.") + (qty > n ? " " + n + " is my limit in one go." : "");
    }

    /** The errand trip is done: hold the haul at this bank for the player at guide, as a callout only they can take, and tell them. */
    static void errandDone(SmartAdventurers.Brain br) {
        String who = br.errandFor; int id = br.errandId; br.errandFor = null;
        Player bot = br.bot;
        if (who == null) return;
        if (Watchdog.FAIL_ON && Watchdog.traced(bot)) try { Failures.econNote(bot, Failures.ERRAND); } catch (Throwable ignored) { } // batch 19c (design 4.1): an errand's haul and sale are legitimate coin/item moves in this window
        int have = bot.getBank().getAmount(id);
        if (have <= 0) { BotChat.say(bot, "Came back with nothing, sorry " + who + "."); System.out.println("[Companions] " + bot.getUsername() + " came back empty from the errand for " + who); return; }
        int n = Math.min(have, br.errandQty);
        int guide = Math.max(1, MarketBrain.value(id));
        for (Deals.Offer x : new ArrayList<>(Deals.OFFERS)) if (x.seller == bot) Deals.expire(x); // one callout at a time
        Deals.Offer o = new Deals.Offer(bot, id, n, guide);
        o.onRequest = true; o.errand = true; o.holdUntil = System.currentTimeMillis() + 30 * 60_000L;
        long[] s = o.hag.computeIfAbsent(who.toLowerCase(Locale.ROOT), k -> new long[]{0, 0, 0, 0, 0, -1}); s[0] = o.total(); s[1] = n;
        Deals.OFFERS.add(o);
        BotChat.activity(bot, () -> "holding " + n + " " + MarketBrain.name(id).toLowerCase(Locale.ROOT) + " for " + who);
        br.idleUntil = System.currentTimeMillis() + 30 * 60_000L; br.loiterAt = bot.getLocation(); br.loiterName = "the bank";
        String bank = BotChat.WorldData.near(bot.getLocation().getX(), bot.getLocation().getY()).replace(", near ", "").trim();
        String item = MarketBrain.name(id).toLowerCase(Locale.ROOT);
        BotChat.say(bot, BotChat.pick(who + ", your " + n + " " + item + " are here" + (bank.isEmpty() ? "" : " at " + bank) + ". " + Haggle.gp(o.total()) + " gp, trade me.", "Got your " + item + ", " + who + ". Trade me, " + Haggle.gp(o.total()) + " gp."));
        Player h = Ledger.Wallet.findOnline(who);
        if (h != null && !h.isArtificial()) h.sendMessage("<col=0b5e1f>" + bot.getUsername() + " has your " + n + " " + item + (bank.isEmpty() ? "" : " at " + bank) + ": trade them for " + Haggle.gp(o.total()) + " gp. Held for 30 minutes.");
        System.out.println("[Companions] " + bot.getUsername() + " holds " + n + " x " + MarketBrain.name(id) + " for " + who + " at " + Haggle.gp(o.total()) + " gp" + (bank.isEmpty() ? "" : " (" + bank + ")"));
    }

    // ------------------------------------------------------------ partner quests

    static boolean inGang(Player h, String attr) {
        try { Object v = h.getAttribute(attr, null); if (v == null) v = h.getAttributes().get(attr); return Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v)); } catch (Throwable t) { return false; }
    }
    static boolean has(Player h, int id) { try { return h.getInventory().getAmount(id) > 0 || h.getBank().getAmount(id) > 0; } catch (Throwable t) { return false; } }

    /**
     * Shield of Arrav: the other half of the certificate. Heroes' Quest (live in the engine): a Phoenix member kills Grip
     * and needs a candlestick from a Black Arm partner; a Black Arm member can't touch Grip and needs his key ring (1588,
     * dropped where he dies) to open the treasure room and search the chest (that search is its stage 5; with a candlestick
     * already in the pack it finds nothing, so a Black Arm member is given the key, not a candlestick). batch 15 item6
     */
    static String partner(Player bot, Player human, String text) {
        boolean phoenix = inGang(human, "/save:phoenix-gang"), blackArm = inGang(human, "/save:black-arm-gang");
        boolean heroes = text.contains("hero") || text.contains("candlestick") || text.contains("key");
        boolean asksPhoenix = text.contains("phoenix"), asksBlack = text.contains("black arm") || text.contains("blackarm");
        if (!phoenix && !blackArm) { if (asksPhoenix) blackArm = true; else if (asksBlack) phoenix = true; else return BotChat.pick("Which gang are you in, Phoenix or Black Arm?", "Phoenix or Black Arm? Say which and I'll see."); }
        int give;
        if (heroes) give = blackArm ? 1588 : 1577; // batch 15 item6: Black Arm gets Grip's key ring (HeroesQuestListener: door 2621, then the chest), Phoenix the candlestick for Katrine
        else {
            boolean has73 = has(human, 11173), has74 = has(human, 11174);
            if (!has73 && !has74) return BotChat.pick("Get your own gang's half of the certificate first, then find me.", "You need your half before I hand over mine.");
            give = has73 ? 11174 : 11173;
        }
        if (has(human, give)) return BotChat.pick("You've already got it. Go finish the quest.", "Check your bank, you've got that already.");
        SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(key(bot));
        if (br == null) return null;
        if (!idle(br)) return BotChat.pick("Not now. Find me at a bank.", "I'm busy, come back in a bit.");
        String gang = phoenix ? "Black Arm" : "Phoenix";
        if (!new Item(give).getDefinition().isTradeable()) { // batch 15 item6: the trade window refuses it: left on the ground for them, as Grip's death leaves the keys
            if (bot.getLocation().getDistance(human.getLocation()) > 8) return "Come closer, I'll drop it for you.";
            core.game.node.item.GroundItemManager.create(new Item(give), bot.getLocation(), human);
            BotChat.Trust.gave(human, bot); // batch 16 item2: a hand-over through the window already counts (PlayerTrades.end); this one never did
            System.out.println("[Companions] " + bot.getUsername() + " (as " + gang + ") drops " + MarketBrain.name(give) + " for " + human.getUsername());
            return BotChat.pick("Dropped it by my feet. Grab it before anyone sees.", "There, on the floor. " + gang + " business, keep it quiet.");
        }
        if (!PlayerTrades.give(bot, human, give)) return "Can't, my pack's full. Give me a minute.";
        System.out.println("[Companions] " + bot.getUsername() + " (as " + gang + ") offers " + MarketBrain.name(give) + " to " + human.getUsername());
        return BotChat.pick("Keep this quiet. " + gang + " business. Trade me and I'll hand it over.", "Fine, I'm " + gang + ". Trade me, it's in my pack.", "Partner it is. Trade me, quick, before anyone sees.");
    }

    // ------------------------------------------------------------ the bots' clan chat and ::ask

    static final List<Player> CLAN_BOTS = new ArrayList<>();
    static final Map<String, Integer> HUMAN_TOTALS = new ConcurrentHashMap<>();
    static final Set<String> AUTO_JOINED = ConcurrentHashMap.newKeySet(); // real players put in the clan chat this login
    static boolean starSaid;

    static ClanRepository clan() {
        try {
            ClanRepository c = ClanRepository.getDefault();
            String server = GameWorld.getSettings() != null ? GameWorld.getSettings().getName() : null;
            if (c == null && server != null) c = ClanRepository.get(server.toLowerCase(Locale.ROOT), true); // nobody has opened it yet
            if (c != null && server != null && server.toLowerCase(Locale.ROOT).equals(c.getOwner()) && ("Chat disabled".equals(c.getName()) || c.getName() == null)) {
                // the server's own clan, fresh: a name and an open door (a new clan starts "Chat disabled" with joining for friends only)
                c.setName(server); c.setJoinRequirement(core.game.system.communication.ClanRank.NONE); c.setMessageRequirement(core.game.system.communication.ClanRank.NONE);
                System.out.println("[Companions] opened the server's clan chat '" + server + "' (join it from the clan chat tab: " + server + ")");
            }
            return c;
        } catch (Throwable t) { return null; }
    }

    static void joinClan() {
        ClanRepository c = clan(); if (c == null) { System.out.println("[Companions] no default clan chat"); return; }
        List<SmartAdventurers.Brain> all = new ArrayList<>(SmartAdventurers.BRAINS.values());
        java.util.Collections.shuffle(all, RNG);
        for (SmartAdventurers.Brain br : all) {
            if (CLAN_BOTS.size() >= 12) break;
            if (br.bot == null || !br.bot.isActive() || CLAN_BOTS.contains(br.bot)) continue;
            try { if (c.enter(br.bot)) CLAN_BOTS.add(br.bot); } catch (Throwable t) { System.out.println("[Companions] clan enter: " + t); break; }
        }
        System.out.println("[Companions] " + CLAN_BOTS.size() + " bots in the clan chat '" + c.getName() + "'");
    }

    static boolean inClan(Player h) {
        ClanRepository c = clan(); if (c == null) return false;
        try { for (ClanEntry e : c.getPlayers()) if (e.getPlayer() == h) return true; } catch (Throwable ignored) { }
        return false;
    }

    static Player clanBot() {
        CLAN_BOTS.removeIf(b -> b == null || !b.isActive());
        return CLAN_BOTS.isEmpty() ? null : CLAN_BOTS.get(RNG.nextInt(CLAN_BOTS.size()));
    }

    static void clanSay(Player bot, String text) {
        ClanRepository c = clan(); if (c == null || bot == null) return;
        try { c.message(bot, text); } catch (Throwable t) { System.out.println("[Companions] clan message: " + t); }
    }

    /** Every 100 ticks: top up the dozen, gz real players in the clan on level-ups, call out a shooting star. */
    static void tickClan() {
        CLAN_BOTS.removeIf(b -> b == null || !b.isActive());
        if (CLAN_BOTS.size() < 8) joinClan();
        if (CLAN_BOTS.isEmpty()) return;
        AUTO_JOINED.removeIf(k -> Ledger.Wallet.findOnline(k) == null); // logged out: next login may be auto-joined again
        for (Player h : Repository.getPlayers()) {
            if (h == null || h.isArtificial() || !h.isActive()) continue;
            try { // a real player in no clan chat at all is put in the server's (once per login); they can leave, and the choice persists with the save
                ClanRepository c = clan();
                if (c != null && h.getCommunication().getClan() == null && (h.getCommunication().getCurrentClan() == null || h.getCommunication().getCurrentClan().isEmpty()) && AUTO_JOINED.add(key(h))) {
                    if (c.enter(h)) { h.getCommunication().setClan(c); h.getCommunication().setCurrentClan(c.getOwner()); h.sendMessage("<col=0b5e1f>You've joined the " + c.getName() + " clan chat, where the regulars talk. Leave it from the clan tab if you'd rather not."); System.out.println("[Companions] " + h.getUsername() + " auto-joined the clan chat"); }
                }
            } catch (Throwable t) { System.out.println("[Companions] auto-join: " + t); }
            int total = 0; for (int i = 0; i < 24; i++) total += h.getSkills().getStaticLevel(i);
            Integer was = HUMAN_TOTALS.put(key(h), total);
            if (was != null && total > was && inClan(h)) {
                Player b = clanBot(); if (b == null) continue;
                clanSay(b, BotChat.pick("Gz " + h.getUsername() + "!", "Nice one, " + h.getUsername() + ", gz!", "gz " + h.getUsername(), "Grats " + h.getUsername() + "! " + total + " total now."));
            }
        }
        try { // a star down: tell the clan once per star
            content.global.activity.shootingstar.ShootingStar star = content.global.activity.shootingstar.ShootingStarPlugin.getStar();
            boolean up = star != null && star.isSpawned();
            if (up && !starSaid) { String where = star.getLocation(); Player b = clanBot(); if (b != null) clanSay(b, "Star's down" + (where == null || where.isEmpty() ? "!" : " at " + where.replace('_', ' ').toLowerCase(Locale.ROOT) + "!") + " Bring a pick."); starSaid = true; }
            if (!up) starSaid = false;
        } catch (Throwable ignored) { }
    }

    /** ::ask <question>: a clan bot answers wherever you are (in the clan chat if you're in it, else as a chat line). */
    static void ask(Player player, String[] args) {
        if (args == null || args.length < 2) { player.sendMessage("Ask the bots something: ::ask where is the nearest furnace"); return; }
        StringBuilder q = new StringBuilder(); for (int i = 1; i < args.length; i++) { if (q.length() > 0) q.append(' '); q.append(args[i]); }
        Player b = clanBot();
        if (b == null) { player.sendMessage("<col=0b5e1f>Nobody's in the clan chat right now."); return; }
        List<String> lines = null;
        asking = true; // answers only: no crew, errand or partner from across the map
        try { lines = BotChat.Brain.answer(b, BotChat.Persona.of(b), player, q.toString(), true); } catch (Throwable t) { System.out.println("[Companions] ask: " + t); } finally { asking = false; }
        if (lines == null || lines.isEmpty()) lines = java.util.Collections.singletonList(BotChat.pick("No idea, sorry.", "Not a clue. Try the website.", "You've got me there."));
        boolean clan = inClan(player);
        for (String l : lines) { if (clan) clanSay(b, l); else player.sendMessage("<col=7f3f00>" + b.getUsername() + ": " + l); }
        System.out.println("[Companions] ::ask from " + player.getUsername() + " '" + q + "' -> " + b.getUsername() + ": " + lines);
    }

    // ------------------------------------------------------------ startup and the trial self-test

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots() || !ON) return;
        GameWorld.getPulser().submit(new Pulse(2) { @Override public boolean pulse() { try { tickCrews(); } catch (Throwable t) { System.out.println("[Companions] crews: " + t); } return false; } });
        GameWorld.getPulser().submit(new Pulse(100) { int n; @Override public boolean pulse() { try { if (n++ >= 2) tickClan(); } catch (Throwable t) { System.out.println("[Companions] clan: " + t); } return false; } });
        try {
            CommandMapping.INSTANCE.register(new Command("ask", Privilege.STANDARD, "", "", (player, args) -> { try { ask(player, args); } catch (Throwable t) { player.sendMessage("<col=ff0000>Ask: something went wrong."); System.out.println("[Companions] ::ask failed: " + t); } return Unit.INSTANCE; }));
        } catch (Throwable t) { System.out.println("[Companions] ::ask: " + t); }
        if (Boolean.getBoolean("companions.test")) GameWorld.getPulser().submit(new Pulse(5) { @Override public boolean pulse() { try { return selfTest(); } catch (Throwable t) { System.out.println("[CompTest] " + t); t.printStackTrace(); return true; } } });
        System.out.println("[Companions] crews, errands, partner quests and the clan chat are on (::ask)");
    }

    static Player tH, tB, tC, tE; static int phase; static long phaseAt = System.currentTimeMillis() + 90_000;
    /** Trials: one bot stands in for a player; two others are asked for a crew, an errand and a quest partner. Bots are grabbed as they come off trips. */
    static boolean selfTest() {
        long now = System.currentTimeMillis();
        if (now < phaseAt) return false;
        if (phase == 0) { // collect three idle adventurers, pinning each one where it stands until the test needs it
            for (SmartAdventurers.Brain br : SmartAdventurers.BRAINS.values()) {
                if (br.bot == null || !br.bot.isActive() || br.bot == tH || br.bot == tB || br.bot == tC) continue;
                if (!idle(br)) { // trials only: a bot on a shop walk (no runner) is pulled off it; the fast test world keeps them walking to shops for minutes
                    if (br.runner != null || br.delivering || br.graveAt != null || br.mill != 0 || br.agiStep >= 0 || br.companion != null || br.errandFor != null || AfkTasks.RUNNING.containsKey(key(br.bot)) || PlayerTrades.SESSIONS.containsKey(br.bot)) continue;
                    br.shopAt = null; br.pendingJob = null; br.shopName = null;
                }
                if (tH == null) { tH = br.bot; TEST_HUMANS.add(key(tH)); } else if (tB == null) tB = br.bot; else if (tC == null) tC = br.bot; else break;
                br.idleUntil = now + 10 * 60_000L; br.loiterAt = tH.getLocation(); br.loiterName = "the test spot"; br.nextThink = now + 5_000;
                System.out.println("[CompTest] grabbed " + br.bot.getUsername() + " (cb " + br.bot.getProperties().getCurrentCombatLevel() + ") as " + (br.bot == tH ? "the stand-in player" : "a bot"));
            }
            if (tH == null || tB == null || tC == null) { phaseAt = now + 3_000; if (now % 60_000 < 3_000) System.out.println("[CompTest] waiting for idle bots: " + (tH != null) + " " + (tB != null) + " " + (tC != null)); return false; }
            // roles by combat: the weakest stands in for the player (so the others qualify for the crew), the strongest is bot B
            List<Player> three = new ArrayList<>(java.util.Arrays.asList(tH, tB, tC));
            three.sort((a, b) -> Integer.compare(a.getProperties().getCurrentCombatLevel(), b.getProperties().getCurrentCombatLevel()));
            TEST_HUMANS.remove(key(tH)); tH = three.get(0); tC = three.get(1); tB = three.get(2); TEST_HUMANS.add(key(tH));
            Location at = tH.getLocation();
            tB.getProperties().setTeleportLocation(at.transform(1, 0, 0)); tC.getProperties().setTeleportLocation(at.transform(0, 1, 0));
            System.out.println("[CompTest] stand-in " + tH.getUsername() + " (cb " + tH.getProperties().getCurrentCombatLevel() + ") at " + at + "; bots B " + tB.getUsername() + " (cb " + tB.getProperties().getCurrentCombatLevel() + "), C " + tC.getUsername() + " (cb " + tC.getProperties().getCurrentCombatLevel() + ")");
            phase = 1; phaseAt = now + 4_000; return false;
        }
        SmartAdventurers.Brain bB = SmartAdventurers.BRAINS.get(key(tB)), bC = SmartAdventurers.BRAINS.get(key(tC));
        if (bB == null || bC == null) { System.out.println("[CompTest] a test bot logged out; stopping"); return true; }
        SmartAdventurers.Brain bE = tE == null ? null : SmartAdventurers.BRAINS.get(key(tE));
        switch (phase) {
            case 1: System.out.println("[CompTest] 'join my crew' -> " + chat(tB, tH, "join my crew", true)); phaseAt = now + 6_000; break;
            case 2: {
                Crew c = CREWS.get(key(tH));
                System.out.println("[CompTest] crew: " + (c == null ? "none" : c.bots.size() + " bots") + "; " + tB.getUsername() + " companion=" + (bB.companion != null) + " dist=" + tB.getLocation().getDistance(tH.getLocation()) + " pack=" + tB.getInventory().itemCount());
                tH.getProperties().setTeleportLocation(tH.getLocation().transform(6, 0, 0)); // the player moves on: the crew should follow
                phaseAt = now + 8_000; break;
            }
            case 3: System.out.println("[CompTest] after the move: dist=" + tB.getLocation().getDistance(tH.getLocation()) + " moving=" + tB.getWalkingQueue().isMoving()); System.out.println("[CompTest] 'disband' -> " + chat(tB, tH, "thanks lads, disband", true) + "; crews now " + CREWS.size() + ", companion=" + (bB.companion != null)); phaseAt = now + 4_000; break;
            case 4: {
                System.out.println("[CompTest] 'fetch me 30 coal' (B) -> " + chat(tB, tH, "fetch me 30 coal", true));
                if (bB.errandFor != null) tE = tB; else { System.out.println("[CompTest] 'fetch me 30 coal' (C) -> " + chat(tC, tH, "fetch me 30 coal", true)); if (bC.errandFor != null) tE = tC; }
                if (tE != null) { SmartAdventurers.Brain e = SmartAdventurers.BRAINS.get(key(tE)); System.out.println("[CompTest] errand taken by " + tE.getUsername() + ": for=" + e.errandFor + " id=" + e.errandId + " qty=" + e.errandQty + " runner=" + (e.runner != null) + " shopAt=" + e.shopAt + " job=" + (e.lastJob != null ? e.lastJob.label() : "?")); }
                else System.out.println("[CompTest] nobody could do the coal errand (no mining among the test bots)");
                phaseAt = now + 3_000; break;
            }
            case 5: {
                Player free = tE == tB ? tC : tB;
                System.out.println("[CompTest] 'can you get me 5000 lobsters' (" + free.getUsername() + ") -> " + chat(free, tH, "can you get me 5000 lobsters", true));
                System.out.println("[CompTest] 'get me a drink' -> " + chat(free, tH, "get me a drink", true) + " (expect null)");
                if (tE != null) System.out.println("[CompTest] 'fetch me 20 coal' to the busy errand bot -> " + chat(tE, tH, "fetch me 20 coal", true) + " (expect busy)");
                phaseAt = now + 3_000; break;
            }
            case 6: {
                Player free = tE == tB ? tC : tB;
                tH.setAttribute("/save:phoenix-gang", true); tH.getInventory().add(new Item(11173, 1));
                System.out.println("[CompTest] 'i need a black arm partner for shield of arrav' (" + free.getUsername() + ") -> " + chat(free, tH, "i need a black arm partner for shield of arrav", true));
                System.out.println("[CompTest] gives pending: " + PlayerTrades.GIVES.keySet() + "; " + free.getUsername() + " holds half=" + free.getInventory().getAmount(11174));
                if (tE != null) System.out.println("[CompTest] 'need a partner for heroes quest' to the busy errand bot -> " + chat(tE, tH, "need a partner for heroes quest", true) + " (expect busy)");
                phaseAt = now + 3_000; break;
            }
            case 7: {
                tH.getInventory().remove(new Item(11173, 1)); tH.removeAttribute("/save:phoenix-gang");
                System.out.println("[CompTest] clan: " + CLAN_BOTS.size() + " bots; inClan(standin)=" + inClan(tH));
                ask(tH, new String[]{"ask", "where", "is", "the", "nearest", "bank"});
                ask(tH, new String[]{"ask", "how", "much", "is", "a", "rune", "scimitar"});
                System.out.println("[CompTest] chat tests done; errand bot " + (tE == null ? "none" : tE.getUsername() + " runner=" + (bE.runner != null) + " at " + tE.getLocation()));
                if (tE == null) return true;
                phaseAt = now + 120_000; break;
            }
            case 8: {
                System.out.println("[CompTest] errand after 2 min: for=" + bE.errandFor + " runner=" + (bE.runner != null) + " offers=" + Deals.OFFERS.size() + " bank coal=" + tE.getBank().getAmount(453) + " at " + tE.getLocation());
                System.out.println("[CompTest] clan now: " + CLAN_BOTS.size() + " bots; inClan(standin)=" + inClan(tH)); // the dozen join three minutes after boot
                ask(tH, new String[]{"ask", "where", "is", "the", "nearest", "bank"});
                ask(tH, new String[]{"ask", "how", "much", "is", "a", "rune", "scimitar"});
                phaseAt = now + 180_000; break;
            }
            case 9: System.out.println("[CompTest] errand after 5 min: for=" + bE.errandFor + " runner=" + (bE.runner != null) + " offers=" + Deals.OFFERS.size() + " bank coal=" + tE.getBank().getAmount(453) + " idleUntil in " + (bE.idleUntil - now) / 1000 + "s at " + tE.getLocation()); phaseAt = now + 240_000; break;
            case 10: System.out.println("[CompTest] errand after 9 min: for=" + bE.errandFor + " runner=" + (bE.runner != null) + " offers=" + Deals.OFFERS.size() + " bank coal=" + tE.getBank().getAmount(453) + " idleUntil in " + (bE.idleUntil - now) / 1000 + "s at " + tE.getLocation()); return true;
        }
        phase++;
        return false;
    }
}
