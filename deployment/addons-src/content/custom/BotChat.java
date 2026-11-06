package content.custom;

import core.ServerConstants;
import core.api.StartupListener;
import core.api.TickListener;
import core.cache.def.impl.ItemDefinition;
import core.game.node.entity.player.Player;
import core.game.system.task.Pulse;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.node.item.Item;
import core.game.world.repository.Repository;
import core.game.world.update.flag.EFlagProvider;
import core.game.world.update.flag.EFlagType;
import core.game.world.update.flag.EntityFlag;
import core.game.world.update.flag.EntityFlags;
import core.game.world.update.flag.context.ChatMessage;
import core.net.packet.IoBuffer;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Timer;
import java.util.TimerTask;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bots that listen and talk back.
 *
 * Every bot on the server (town folk, artisans, adventurers, traders) can hear public chat from a
 * nearby human and answer it: greetings, "where is the nearest bank", "how much are yew logs",
 * "what are you doing", "how do I start Dragon Slayer", thanks, goodbyes and a few in-character
 * quips. Answers come from the server itself: the world-map data (data/map-data.json), the price
 * index and bot stock, the quest guides (data/quests-data.json) and whatever job the bot's own
 * script says it is on (registered through {@link #activity(Player, Supplier)}).
 *
 * Hearing works by wrapping the player Chat update-flag provider: whenever a chat message is written
 * out to any client it is queued here, and the next game tick picks a nearby bot to reply. Bots also
 * pass remarks between each other when a human is close enough to see it.
 *
 * Nothing here changes what the bots do; it only adds what they say.
 */
public final class BotChat implements StartupListener, TickListener {
    static final Random RNG = new Random();
    static final boolean DEBUG = Boolean.getBoolean("botchat.debug");
    static final int HEAR_RANGE = 8;     // tiles a bot can hear a player from
    static final int SEE_RANGE = 14;     // a human this close sees bot-to-bot banter

    /** One thing a human said, waiting for a reply. */
    static final class Heard {
        final Player speaker; final String text;
        Heard(Player speaker, String text) { this.speaker = speaker; this.text = text; }
    }

    static final ConcurrentLinkedQueue<Heard> HEARD = new ConcurrentLinkedQueue<>();
    static final Set<Object> SEEN_THIS_TICK = Collections.newSetFromMap(new ConcurrentHashMap<>());
    static final Map<String, Supplier<String>> ACTIVITY = new ConcurrentHashMap<>();
    static final Map<String, Long> BOT_COOLDOWN = new HashMap<>();
    static final Map<String, Long> PLAYER_COOLDOWN = new HashMap<>();
    static final Map<String, String> LAST_ANSWER = new HashMap<>(); // bot -> last line, so it doesn't repeat itself
    static long nextBanter = System.currentTimeMillis() + 90_000;
    static long nextRivalry; static volatile int rivalries; // batch 16 item2: home-town rivalry lines, at most one every RIVALRY_GAP; rivalries counts them for the trials
    static final long RIVALRY_GAP = 10 * 60_000L;
    static long nextAmbient = System.currentTimeMillis() + 45_000;
    static volatile boolean ready;
    static final Timer TIMER = new Timer("BotChat", true);

    /** Something that wants first refusal on what a player says (a trader being haggled with). */
    public interface Listener { boolean hear(Player speaker, String text); }
    public static final List<Listener> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Things that happened that bots can gossip about; newest last. */
    static final Deque<String> NEWS = new ArrayDeque<>();
    static final Set<String> NEWS_SEEN = new HashSet<>();
    public static synchronized void news(String line) {
        if (line == null || line.isEmpty() || !NEWS_SEEN.add(line)) return;
        NEWS.addLast(line);
        while (NEWS.size() > 30) NEWS_SEEN.remove(NEWS.removeFirst());
    }
    static synchronized String someNews() {
        if (NEWS.isEmpty()) return null;
        int i = RNG.nextInt(NEWS.size()), k = 0;
        for (String n : NEWS) if (k++ == i) return n;
        return NEWS.peekLast();
    }

    // ------------------------------------------------------------ startup and hooks

    @Override
    public void startup() {
        try { hookChat(); } catch (Throwable t) { System.out.println("[BotChat] could not hook chat: " + t); }
        if (!GameWorld.getTickListeners().contains(this)) GameWorld.getTickListeners().add(this);
        Thread loader = new Thread(() -> {
            try { WorldData.load(); } catch (Throwable t) { System.out.println("[BotChat] world data failed: " + t); }
            try { Items.load(); } catch (Throwable t) { System.out.println("[BotChat] item names failed: " + t); }
            try { Quests.load(); } catch (Throwable t) { System.out.println("[BotChat] quest data failed: " + t); }
            try { Memory.load(); } catch (Throwable t) { System.out.println("[BotChat] memory failed: " + t); Ledger.quarantine(Memory.file(), "BotChat", t, false, true); } // audit 10-23 batch14 item1
            try { Trust.load(); } catch (Throwable t) { System.out.println("[BotChat] trust failed: " + t); Ledger.quarantine(Trust.file(), "BotChat", t, false, true); } // audit 10-23 batch14 item1
            ready = true;
            TIMER.schedule(new TimerTask() { public void run() { try { Memory.save(); Trust.save(); } catch (Throwable t) { System.out.println("[BotChat] memory save: " + t); } } }, 300_000, 300_000);
            TIMER.schedule(new TimerTask() { public void run() { try { Rumours.pollStar(); } catch (Throwable ignored) { } } }, 90_000, 60_000);
            System.out.println("[BotChat] ready: " + WorldData.features.size() + " features, " + WorldData.shops.size() + " shops, "
                    + WorldData.labels.size() + " places, " + Items.byName.size() + " item names, " + Quests.all.size() + " quests");
            if (Boolean.getBoolean("botchat.test")) selfTest();
        }, "BotChat-load");
        loader.setDaemon(true);
        loader.start();
    }

    /** Replace the player Chat flag provider with one that also records what was said. */
    static void hookChat() {
        Map<Integer, EFlagProvider> providers = EntityFlags.flagProviders;
        Integer key = null; EFlagProvider orig = null;
        for (Map.Entry<Integer, EFlagProvider> e : providers.entrySet()) {
            EFlagProvider p = e.getValue();
            if (p != null && p.getType() == EFlagType.Player && p.getFlag() == EntityFlag.Chat) { key = e.getKey(); orig = p; break; }
        }
        if (orig == null) { System.out.println("[BotChat] no player chat flag provider found"); return; }
        final EFlagProvider inner = orig;
        providers.put(key, new EFlagProvider(orig.getRevision(), orig.getType(), orig.getPresenceFlag(), orig.getOrdinal(), orig.getFlag()) {
            @Override
            public void writeTo(IoBuffer buf, Object ctx) {
                hear(ctx);
                inner.writeTo(buf, ctx);
            }

            @Override
            public void writeToDynamic(IoBuffer buf, Object ctx, core.game.node.entity.Entity e) {
                hear(ctx);
                inner.writeToDynamic(buf, ctx, e);
            }
        });
        System.out.println("[BotChat] listening to public chat");
    }

    static void hear(Object ctx) {
        if (!(ctx instanceof ChatMessage) || !SEEN_THIS_TICK.add(ctx)) return; // one entry per message, however many clients draw it
        ChatMessage m = (ChatMessage) ctx;
        Player p = m.getPlayer();
        if (p == null || p.isArtificial() || m.getText() == null) return;
        HEARD.add(new Heard(p, m.getText()));
    }

    /** A bot script tells the chat what it is up to, e.g. "mining iron at the Varrock east mine". */
    public static void activity(Player bot, Supplier<String> what) {
        if (bot != null) ACTIVITY.put(bot.getUsername().toLowerCase(Locale.ROOT), what);
    }

    public static void clearActivity(Player bot) {
        if (bot != null) ACTIVITY.remove(bot.getUsername().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------ the tick

    @Override
    public void tick() {
        SEEN_THIS_TICK.clear();
        if (!ready) { HEARD.clear(); return; }
        Heard h;
        int handled = 0;
        while (handled < 4 && (h = HEARD.poll()) != null) { // audit C-3: limit checked before polling (the 5th line was taken and dropped every busy tick)
            try { respond(h); } catch (Throwable t) { if (DEBUG) System.out.println("[BotChat] respond failed: " + t); }
            handled++;
        }
        long now = System.currentTimeMillis();
        if (GameWorld.getTicks() % 16 == 0) { try { Watch.poll(); } catch (Throwable t) { if (DEBUG) System.out.println("[BotChat] watch failed: " + t); } }
        if (GameWorld.getTicks() % 16 == 8) { try { Watch.pollBots(); } catch (Throwable t) { if (DEBUG) System.out.println("[BotChat] bot watch failed: " + t); } }
        if (GameWorld.getTicks() % 16 == 4) { try { Trust.greet(); } catch (Throwable t) { if (DEBUG) System.out.println("[BotChat] greet failed: " + t); } }
        if (now > nextAmbient) {
            nextAmbient = now + 25_000 + RNG.nextInt(35_000);
            try { ambient(); } catch (Throwable t) { if (DEBUG) System.out.println("[BotChat] ambient failed: " + t); }
        }
        if (now > nextBanter) {
            nextBanter = now + 60_000 + RNG.nextInt(120_000);
            try { banter(); } catch (Throwable t) { if (DEBUG) System.out.println("[BotChat] banter failed: " + t); }
        }
    }

    /** Unprompted remarks to a nearby human: a rumour, something noticed about them, a warning, or just colour. */
    static void ambient() {
        long now = System.currentTimeMillis();
        for (Player h : Repository.getPlayers()) {
            if (h == null || h.isArtificial() || !h.isActive()) continue;
            Player bot = null; double best = 99;
            for (Player b : Repository.getPlayers()) {
                if (b == null || !b.isArtificial() || !b.isActive() || b.getLocation().getZ() != h.getLocation().getZ()) continue;
                double d = b.getLocation().getDistance(h.getLocation());
                if (d <= 10 && d < best && BOT_COOLDOWN.getOrDefault(b.getUsername().toLowerCase(Locale.ROOT), 0L) <= now) { best = d; bot = b; }
            }
            if (bot == null) continue;
            if (best <= 2.5 && RNG.nextInt(3) == 0) { try { if (!bot.getWalkingQueue().isMoving() && !bot.inCombat()) bot.face(h); } catch (Throwable ignored) { } } // D26: notice someone who comes right up to you
            if (best <= 1.0 && RNG.nextInt(2) == 0) { try { if (!bot.getWalkingQueue().isMoving() && !bot.inCombat() && ACTIVITY.get(bot.getUsername().toLowerCase(Locale.ROOT)) == null && bot instanceof core.game.bots.AIPlayer) ((core.game.bots.AIPlayer) bot).randomWalk(2, 2); } catch (Throwable ignored) { } } // D26: step aside when someone walks into you
            Persona who = Persona.of(bot);
            String line = Safety.warning(bot, h);                      // warnings first, always
            if (line == null) {
                String name = h.getUsername();
                Memory.Met met = Memory.get(h);
                int roll = RNG.nextInt(100);
                if (met != null && met.lastTopic != null && !met.followedUp && roll < 35) { met.followedUp = true; line = who.flavour(pick("Find " + met.lastTopic + " in the end, " + name + "?", "Still after " + met.lastTopic + ", " + name + "?", "Did " + met.lastTopic + " work out, " + name + "?")); }
                else if (roll < 55) { String n = someNews(); if (n != null) line = who.flavour(n); }
                else if (roll < 80) line = Notice.remark(bot, h, who);
                else line = who.flavour(pick(CREATIVE));
            }
            if (line == null) continue;
            String bk = bot.getUsername().toLowerCase(Locale.ROOT);
            if (line.equals(LAST_ANSWER.get(bk))) continue;
            LAST_ANSWER.put(bk, line);
            BOT_COOLDOWN.put(bk, now + 45_000 + RNG.nextInt(60_000));
            speak(bot, h, Collections.singletonList(line), 1);
            if (DEBUG) System.out.println("[BotChat] ambient " + bot.getUsername() + " -> " + h.getUsername() + ": " + line);
            return; // one remark per round, world-wide; keeps it rare
        }
    }

    /** Lines any bot can throw in; the persona adds its tic. */
    static final String[] CREATIVE = {
        "I once saw a goblin try to pickpocket a guard. The guard was a statue. It still took ten minutes.",
        "If you find a bronze dagger on the floor, leave it. It's bait. For what, nobody knows.",
        "They say the Wise Old Man in Draynor counts his runes every night. Twice.",
        "A cabbage a day keeps the Draynor manor at bay. That's not a saying. It should be.",
        "The ducks on the Lumbridge river know things. Watch how they look at you.",
        "My grandfather mined coal at Barbarian Village. My father too. I prefer talking.",
        "Never trust a man selling a map. Especially one with an X on it.",
        "Bob the cat was here earlier. Didn't say much. Cats don't.",
        "The Exchange clerks never sleep. I've checked. At three in the morning. Don't ask.",
        "If a wizard offers to teleport you somewhere 'nice', ask where first.",
        "Lumbridge Castle has more stairs than rooms. I've counted both.",
        "Sardines aren't fish, they're a punishment. Fish anything else.",
        "A shark once bit a bot. The bot didn't notice. Unsettling.",
        "The gnomes have a tree so tall the top has weather. Fact.",
        "Every chicken in Lumbridge has a name. None of them are good names.",
        "Heard a rumour there's a man in Varrock who buys nothing but feathers. Thousands. Nobody asks why.",
        "You can tell a seasoned adventurer by the burn marks on their sleeves. Cooking, mostly.",
        "The Al Kharid guards charge ten coins at the gate. For what? The privilege of sand.",
        "Somewhere out there is a yew tree nobody has found. It's my retirement plan.",
        "Imps steal beads. Beads! What kind of economy are they running?",
        "The best thing about Draynor is leaving it before dark.",
        "I've got a theory about the Barbarian Village. Everybody's named Peksa. Not just the one.",
        "Don't eat anything the Monastery monks offer. Lovely men, terrible cooks.",
        "If the Duke of Lumbridge asks you to fetch something, bring a spare bucket. Trust me.",
        "There's a scarecrow in the Falador farm that gets more visitors than the museum.",
        "A dragon's just a very large, very angry lizard with opinions.",
        "Thirteen is lucky here. Thirteen cows, thirteen coins, thirteen goblins. Mind the goblins.",
        "The wizards' tower has a basement. The basement has a basement. It stops there, probably.",
        "Fancy a walk? No? Fair. Walking's for people with nowhere to teleport.",
        "The river Lum runs backwards on Tuesdays. Have a look. It doesn't, but have a look.",
        "A travelling trader once sold me a 'genuine' dragon scale. It was a cabbage leaf painted red.",
        "The Falador park gardener talks to the flowers. The flowers are winning the argument.",
        "Port Sarim smells of fish and ambition. Mostly fish.",
        "Edgeville used to have a dragon problem. Now it has a man-with-no-shirt problem.",
        "Heard the penguins are a spy ring. Makes sense. Nobody suspects a penguin.",
        "If you see a pile of bones with a sword in it, that's not a drop. That's a warning.",
        "I keep my coins in the bank and my regrets in a sack. The sack's fuller.",
        "A friend of mine trained Firemaking to 99 by accident. Long story. Short tree.",
        "Legend says if you say 'coal' three times at the Dwarven Mine, a dwarf sighs.",
        "The guard at Varrock palace blinks every forty seconds. I timed it. Twice. Then he arrested me.",
        "Somebody left a bucket of milk by the Lumbridge cows. The cows look smug about it.",
        "The Draynor bank has the best view of the market. Mostly of people not buying anything.",
        "I tried to sell a cabbage to a dwarf once. He took it personally.",
        "The fishing spots move when nobody's looking. I've almost caught them at it.",
        "If you hear chanting near Varrock at night, it's the Zamorakians. Or a choir. Hard to tell.",
        "Gnome balls are thrown, not kicked. I learned that the painful way.",
        "Never lend a rune pickaxe to a friend. You'll get back a story and half a handle.",
        "Somewhere in Falador is a white knight who has never seen a black knight. He's very confused about his job.",
        "Rimmington has a mine, a well and a lot of opinions. Mostly from the well.",
        "The Barbarian Village fire never goes out. Nobody admits to feeding it.",
        "A man in Al Kharid sells kebabs that can heal you or hurt you. It's a gamble.",
        "I once followed a hellhound's paw prints for an hour. They led to a hellhound. Obviously.",
        "The secret to fishing is patience. And bait. Mostly bait.",
        "Tried to cook a lobster once and it looked at me. Ate bread that night.",
        "There's a sheep in Lumbridge that won't be sheared. It's got a lawyer.",
        "Varrock's sewers have rats the size of dogs. And dogs the size of horses. I don't go down there.",
        "My bank's got a bronze dagger I can't throw away. Sentimental value. First kill.",
        "The Seers' Village folk can see the future, apparently. Nobody warned me about the rain.",
        "If you ever find a red cabbage, eat it. Or don't. I'm not your mum.",
        "The tutor in Lumbridge has taught thousands. Still can't tie his own boots.",
        "Mining essence at Aubury's is like counting sand. Calming, though.",
        "Port Sarim's jail has a sign saying no visitors. Everyone visits.",
        "A dark wizard once asked me for directions. I sent him to Draynor. He deserved it.",
        "They keep building more banks. Nobody builds more cows.",
        "The Wise Old Man robbed the Draynor bank once. Rumour says he kept the receipt.",
        "Someone's been leaving bones by the church. The priest says it's a blessing. I say it's a mess.",
        "Ice giants are just hill giants that forgot their coats.",
        "I asked a monk in Edgeville what his secret was. He said 'bread'. He might have been hungry.",
        "There's a man in Varrock who sells nothing but staffs. He's very well supported.",
        "The Catherby ranges cook evenly. The Lumbridge one burns everything. Ask anyone.",
        "Someone told me the Exchange runs on goblins on treadmills. Explains the noise.",
        "Swamp tar is the only thing in Lumbridge that smells worse than the swamp.",
        "Dwarves can smell iron ore from a mile away. I can smell dwarves from about the same.",
        "Brimhaven's agility arena is a fine way to lose your eyebrows.",
        "I've never met a happy fisherman at Barbarian Village. Plenty of wet ones.",
        "Taverley druids talk to trees. The trees don't talk back, which says a lot about the druids.",
        "If you see a gnome in a hurry, follow it. They always know where the food is.",
        "There's a door in Falador nobody opens. It's not locked. Nobody's brave enough.",
        "Lost my best boots to a hill giant. He wasn't even wearing them properly.",
        "The quickest way to make friends at the Exchange is to say 'free'. The quickest way to lose them is to mean 'not really'.",
        "Somebody shouted 'selling lobbies' at the bank and three people ran over. Capitalism.",
        "If you've never been to the Duel Arena, keep it that way. Your wallet will thank you.",
        "The Varrock museum has bones of a dragon. Smaller than you'd hope.",
        "I once spent a whole afternoon trying to find Fred the Farmer's sheep. They were behind me.",
        "The Ernest the Chicken quest is about a chicken. And a professor. Mostly the chicken.",
        "There's a tutor in Lumbridge for every skill. None for patience.",
        "Never trust a level 3 with a rune scimitar. Something's going on there.",
        "Party Pete in Falador throws a great party. Drop parties are a rumour. Mostly.",
        "Draynor's Wise Old Man has a partyhat. Allegedly. Nobody's seen it up close.",
        "The best fishing advice I ever got: fish where the fish are.",
        "I keep a spade in my bank just in case. In case of what? I'll know when it happens.",
        "Gertrude's cat ran off again. Every adventurer in Varrock has found that cat.",
        "The Sorceress's Garden smells lovely. The guards don't.",
        "Somebody spent all day at the Ardougne stalls stealing cakes. The baker was furious.",
        "If you see a flashing NPC asking for help, it's a random event. Probably. Answer it anyway.",
        "Brother Brace in Edgeville prays for everyone. Even goblins. Even me.",
        "The Lumbridge cook lost his ingredients again. Some quests never really end.",
        "Zanaris is real. I've been. You need a dramen staff and a very good excuse.",
        "The Seers' bank on a busy day is like the Exchange, but with more magic logs.",
        "Shilo Village has gems. Karamja has bananas. Pick your career.",
        "Watched someone try to trade a rubber chicken for a rune scimitar. Bold.",
        "The camel in Al Kharid used to talk, apparently. It's quieter now.",
        "Rune essence goes for almost nothing, but Aubury's mine goes on forever.",
        "Every time I visit the Barbarian Village, someone's arguing about a helmet.",
        "Saw a level 100 cooking spaghetti in Lumbridge. Not every hero wears armour."};

    static void respond(Heard h) {
        String text = h.text.trim();
        if (text.isEmpty() || text.startsWith("::") || text.startsWith("/")) return;
        Player speaker = h.speaker;
        if (!speaker.isActive()) return;
        long now = System.currentTimeMillis();
        String sk = speaker.getUsername().toLowerCase(Locale.ROOT);
        if (PLAYER_COOLDOWN.getOrDefault(sk, 0L) > now) return;
        for (Listener l : LISTENERS) { try { if (l.hear(speaker, text)) return; } catch (Throwable ignored) { } }
        Memory.saw(speaker);
        // Who heard it: bots on the same plane within range, nearest first, unless one was named.
        List<Player> near = new ArrayList<>();
        for (Player b : Repository.getPlayers()) {
            if (b == null || !b.isArtificial() || !b.isActive()) continue;
            if (b.getLocation().getZ() != speaker.getLocation().getZ()) continue;
            if (b.getLocation().getDistance(speaker.getLocation()) <= HEAR_RANGE) near.add(b);
        }
        if (near.isEmpty()) return;
        near.sort((a, b) -> Double.compare(a.getLocation().getDistance(speaker.getLocation()), b.getLocation().getDistance(speaker.getLocation())));
        String lower = text.toLowerCase(Locale.ROOT);
        Player bot = null;
        for (Player b : near) if (lower.contains(b.getUsername().toLowerCase(Locale.ROOT))) { bot = b; break; }
        boolean addressed = bot != null;
        if (bot == null) for (Player b : near) if (BOT_COOLDOWN.getOrDefault(b.getUsername().toLowerCase(Locale.ROOT), 0L) <= now) { bot = b; break; }
        if (bot == null) return;
        Persona who = Persona.of(bot);
        List<String> lines = Brain.answer(bot, who, speaker, text, addressed);
        if (lines == null || lines.isEmpty()) return;
        String bk = bot.getUsername().toLowerCase(Locale.ROOT);
        if (lines.size() == 1 && lines.get(0).equals(LAST_ANSWER.get(bk)) && !addressed) return; // don't parrot
        LAST_ANSWER.put(bk, lines.get(0));
        BOT_COOLDOWN.put(bk, now + 5_000 + RNG.nextInt(4_000));
        PLAYER_COOLDOWN.put(sk, now + 2_500);
        speak(bot, speaker, lines, 1 + RNG.nextInt(2));
        Trust.chatted(speaker, bot);
        if (DEBUG) System.out.println("[BotChat] " + speaker.getUsername() + ": \"" + text + "\" -> " + bot.getUsername() + ": " + lines);
        try { chimeIn(bot, speaker, lower, near, 4 + 2 * lines.size()); } catch (Throwable ignored) { }
    }

    /** Says the lines a tick or two apart, facing the player, like someone typing. */
    static void speak(Player bot, Player face, List<String> lines, int delay) {
        GameWorld.getPulser().submit(new Pulse(delay) {
            int i = 0; boolean faced;
            @Override
            public boolean pulse() {
                if (!bot.isActive()) return true;
                // Only a bot standing still turns to face you; one walking or running keeps going the way it's headed (2026-10-20)
                boolean moving = bot.getWalkingQueue().isMoving();
                if (i == 0 && face != null && face.isActive() && !moving) { bot.face(face); faced = true; }
                say(bot, lines.get(i++));
                if (i >= lines.size()) { if (faced) GameWorld.getPulser().submit(new Pulse(3) { @Override public boolean pulse() { try { bot.face(null); } catch (Throwable ignored) { } return true; } }); return true; } // let go after talking
                setDelay(2);
                return false;
            }
        });
    }

    static final int LINE = 60; // what fits overhead and on one chatbox line

    /** Splits text into pieces of at most `max` characters, at sentence ends where possible, else at spaces. */
    static java.util.List<String> chunks(String text, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String rest = text.trim();
        while (rest.length() > max) {
            int cut = -1;
            for (int i = max; i > max / 2; i--) { char c = rest.charAt(i - 1); if ((c == '.' || c == '!' || c == '?' || c == ',') && rest.charAt(i) == ' ') { cut = i; break; } }
            if (cut < 0) cut = rest.lastIndexOf(' ', max);
            if (cut <= 0) cut = max;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    /**
     * Bot speech goes out as public chat (ChatMessage), which shows overhead AND in nearby players' chatboxes.
     * (Entity.sendChat is "force chat": overhead only, which is why only some bot lines used to reach the chatbox.)
     */
    static void say(Player bot, String text) {
        if (bot == null || text == null || text.isEmpty()) return;
        if (Boolean.getBoolean("botchat.debug")) System.out.println("[Say] " + bot.getUsername() + ": " + text);
        java.util.List<String> parts = chunks(text, LINE);
        GameWorld.getPulser().submit(new Pulse(1) {
            int i = 0;
            @Override
            public boolean pulse() {
                if (!bot.isActive() || i >= parts.size()) return true;
                String p = parts.get(i++);
                try { bot.getUpdateMasks().register(EntityFlag.Chat, new ChatMessage(bot, p, 0, 0)); } catch (Throwable ignored) { }
                setDelay(3);
                return i >= parts.size();
            }
        });
    }

    // ------------------------------------------------------------ overlapping conversations (the owner's notes, 2026-10-20)

    /** Another idle bot within 6 tiles sometimes (1 in 4) chips in after a bot answers a player: agrees, teases, or disagrees. */
    static void chimeIn(Player answered, Player human, String asked, List<Player> near, int delay) {
        if (RNG.nextInt(4) != 0) return;
        long now = System.currentTimeMillis();
        for (Player c : near) {
            if (c == answered || c.getLocation().getDistance(answered.getLocation()) > 6) continue;
            String ck = c.getUsername().toLowerCase(Locale.ROOT);
            if (BOT_COOLDOWN.getOrDefault(ck, 0L) > now || ACTIVITY.containsKey(ck) && !String.valueOf(activityOf(c)).startsWith("hanging around")) continue;
            String a = answered.getUsername(), h = human.getUsername(), line;
            if (asked.contains("how much") || asked.contains("price") || asked.contains("worth") || asked.contains("sell"))
                line = pick("Check the Exchange before you pay anyone, " + h + ".", "Don't let " + a + " talk you into overpaying.", a + " says that about everything. Guide's guide.");
            else if (asked.contains("where") || asked.contains("how do i get") || asked.contains("way to"))
                line = pick("Quicker round the other side, mind.", a + " always sends people the long way.", "What " + a + " said. Take food.");
            else
                line = pick("Don't mind " + a + ", they've been out in the sun.", "Ha. Same answer every time, " + a + ".", "I'd have said the same. Mostly.", "Listen to " + a + ", " + h + ". Rare, but it happens.", "That's what they want you to think.");
            BOT_COOLDOWN.put(ck, now + 15_000);
            speak(c, human, Collections.singletonList(Persona.of(c).flavour(line)), delay);
            if (DEBUG) System.out.println("[BotChat] chime-in " + c.getUsername() + " after " + a);
            return;
        }
    }

    /** A bank callout with a player in sight: a rival bot sometimes (1 in 4) chips in about the price. */
    public static void chimeCallout(Player seller, String item, int unit) {
        if (RNG.nextInt(4) != 0) return;
        Player watcher = null;
        for (Player h : Repository.getPlayers()) if (h != null && h.isActive() && !h.isArtificial() && h.getLocation().getZ() == seller.getLocation().getZ() && h.getLocation().getDistance(seller.getLocation()) <= SEE_RANGE) { watcher = h; break; }
        if (watcher == null) return;
        long now = System.currentTimeMillis();
        for (Player c : Repository.getPlayers()) {
            if (c == null || c == seller || !c.isArtificial() || !c.isActive() || c.getLocation().getZ() != seller.getLocation().getZ() || c.getLocation().getDistance(seller.getLocation()) > 6) continue;
            String ck = c.getUsername().toLowerCase(Locale.ROOT);
            if (BOT_COOLDOWN.getOrDefault(ck, 0L) > now) continue;
            String s = seller.getUsername();
            String line = pick("Don't pay " + s + " " + unit + " for " + item + ", that took ten minutes to get.", unit + " each? " + s + " got those for free.", "Wait for the Exchange, the clerks'll do better than " + s + ".", "Fair price, that. Annoyingly.");
            BOT_COOLDOWN.put(ck, now + 20_000);
            speak(c, seller, Collections.singletonList(Persona.of(c).flavour(line)), 6);
            return;
        }
    }

    /** Two bots talk about the player watching them: what they've been training, or how long they've been around. */
    static String[] gossip(Player a, Player b, Player h) {
        String n = h.getUsername();
        int sk = Watch.training(h);
        if (sk >= 0 && sk < core.game.node.entity.skill.Skills.SKILL_NAME.length) {
            String where;
            switch (sk) {
                case 14: where = "down the mines"; break;
                case 8: where = "chopping trees"; break;
                case 10: where = "fishing"; break;
                case 7: where = "at a range"; break;
                case 13: where = "at the anvils"; break;
                case 17: where = "picking pockets"; break;
                case 16: where = "on an agility course"; break;
                case 11: where = "lighting fires"; break;
                case 5: where = "burying bones"; break;
                case 0: case 1: case 2: case 4: case 6: where = "fighting"; break;
                default: where = "training " + core.game.node.entity.skill.Skills.SKILL_NAME[sk];
            }
            int lv = h.getSkills().getStaticLevel(sk);
            String skn = core.game.node.entity.skill.Skills.SKILL_NAME[sk];
            return new String[]{pick("Isn't that " + n + "? Saw them " + where + " earlier.", "That's " + n + ". They've been " + where + " all day.", "Look, " + n + ". Still " + where + "?"),
                    lv < 99 ? pick("Yeah, nearly level " + (lv + 1) + " " + skn + " now.", "Level " + lv + " " + skn + ". Getting there.", "Keen, that one. " + lv + " " + skn + " already.") : pick("99 " + skn + ". Show-off.", "Maxed it, haven't they.")};
        }
        Memory.Met m = Memory.get(h);
        if (m != null && m.times > 1) {
            int days = m.daysSinceFirst();
            return new String[]{pick("That's " + n + ". " + (days > 0 ? "Been coming round " + days + " days now." : "Third time today I've seen them."), "Oh, " + n + "'s back."),
                    pick("Friendly enough. Asks a lot of questions.", "Wave, they're watching.", "Good. Someone has to keep us honest.")};
        }
        return new String[]{pick("Who's that? New face.", "Don't know that one."), pick("Say hello then.", "Probably lost. They usually are.")};
    }

    // ------------------------------------------------------------ bot-to-bot banter

    static final String[][] BANTER = {
        {"Still at it, %s?", "Till the pick breaks, %s."},
        {"Seen the Exchange prices today, %s?", "Don't start. I'm not selling yet."},
        {"Any luck, %s?", "Define luck."},
        {"You look tired, %s.", "I look rich. Different thing."},
        {"%s! Thought you'd gone to the Wilderness.", "And come back poorer? No thanks."},
        {"Heard a shooting star came down last night.", "Stardust everywhere and I missed it. Typical."},
        {"Fancy a trip to Karamja, %s?", "Only if the bananas pay for the boat."},
        {"My cousin says the goblins are organising.", "Your cousin says a lot of things, %s."},
        {"Smelting again, %s?", "Someone has to. The market won't fill itself."},
        {"Nice weather for it, %s.", "It's always nice weather. Have you noticed that?"},
        {"How's the %s business?", "Steady. Boring. Profitable."},
        {"Watch the penguins this week, %s.", "Those birds know too much."},
        {"Lost anything good today, %s?", "Only my dignity. Again."},
        {"%s, is that a new helmet?", "Don't. It was on offer."},
        {"Did you hear the crier this morning?", "I hear him every morning. That's the problem."},
        {"Bet you can't name every Lumbridge chicken, %s.", "Bet you can't stop asking."},
        {"Back from the mines, %s?", "Never really leave, do you?"},
        {"How's the haul looking, %s?", "Heavier than my wallet. Somehow."},
        {"Cold out by the fishing spots today.", "Fish don't mind. Fish never mind."},
        {"%s! You owe me ten coins.", "I owe you nothing but advice."},
        {"Fancy splitting a lobster, %s?", "Fancy buying your own?"},
        {"The Exchange clerk winked at me, %s.", "That's a twitch. They're overworked."},
        {"Saw a dragon in Taverley, %s.", "You saw a lizard and panicked."},
        {"My feet hurt, %s.", "Mine went numb an hour ago. Lucky."},
        {"Any news from Falador, %s?", "White knights, white walls, white bread. Same as ever."},
        {"Think it'll rain, %s?", "It never rains here. That's the suspicious part."},
        {"You're blocking the bank, %s.", "I'm decorating the bank. There's a difference."},
        {"How much did you pay for that pickaxe, %s?", "Too much. Don't tell anyone."},
        {"Heard the goblins have a new general, %s.", "The old one tripped. Long story."},
        {"Nice cape, %s.", "It's a towel. Don't tell anyone."},
        {"Ever been to Morytania, %s?", "Once. Never again. Ghosts everywhere."},
        {"Smell that, %s? Fresh bread.", "Smells like burnt bread to me."},
        {"Where are you off to next, %s?", "Wherever the coins are."},
        {"I'm thinking of taking up Herblore, %s.", "And I'm thinking of taking up napping."},
        {"Got any spare food, %s?", "I've got spare advice. Eat before you're hungry."},
        {"Was that you snoring by the bank, %s?", "That was a guard. Probably."},
        {"Look at the queue for that booth.", "Queues are just bank-standing with purpose."},
        {"What's the best money right now, %s?", "Whatever you're not doing."},
        {"I've decided I'm retiring, %s.", "You said that yesterday."},
        {"Have you met the new trader, %s?", "Tried to sell me my own boots."},
        {"Got a light, %s?", "Got a tinderbox. Same thing, more effort."},
        {"Did you finish Dragon Slayer yet, %s?", "Still looking for the map pieces. One's in a maze. Of course it is."},
        {"Selling lobbies, %s?", "Not to you. You'd only burn them."},
        {"Buying gf, 1k. Kidding. Mostly.", "Classic."},
        {"Where's the best place to mine iron, %s?", "Wherever nobody else is. So nowhere."},
        {"Saw someone with a party hat earlier, %s.", "You saw a paper crown. Calm down."},
        {"Cooks' Assistant took me an hour, %s.", "It's an egg, flour and milk. How?"},
        {"Heard Zamorak monks are giving out free wine.", "Free until you pick it up."},
        {"My cooking's up to 40 now, %s.", "So you burn lobsters instead of shrimp. Progress."},
        {"Is that a black scimitar, %s?", "Iron with soot on it. Shh."},
        {"Any good drops today, %s?", "A bronze med helm. I'm framing it."},
        {"Thinking of buying a members' pass, %s.", "Think harder. Then do it."},
        {"Should I train Strength or Attack, %s?", "Yes."},
        {"Lumbridge swamp again, %s?", "Someone's got to mine the copper."},
        {"How do you get to Entrana, %s?", "Leave everything you love at the bank. Then the monks let you on."},
        {"Bet the GE price of nature runes drops tomorrow.", "Bet it doesn't. Bet you buy anyway."},
        {"Did the crier just say coal again?", "He always says coal. Coal is life."},
        {"%s, why are you wearing a cape in this heat?", "Fashion has no season."},
        {"Taverley dungeon's packed today, %s.", "Blue dragons are popular. So are deaths."},
        {"My bank's full, %s.", "Of bronze daggers, I bet."},
        {"Ever done the Barbarian course, %s?", "Once. The pipe and I don't speak any more."}};

    /** batch 16 item2: a short, friendly home-town rivalry between two bots ({first line, reply}). */
    static String[] rivalry(String mine, String theirs, String me, String them) {
        String[][] lines = {
            {"Bet " + theirs + " still hasn't got a decent bank, " + them + ".", "Says the one from " + mine + ". At least ours has a roof, " + me + "."},
            {"Nobody's ever come back from " + theirs + " richer, " + them + ".", "Funny, everyone leaves " + mine + " poorer, " + me + "."},
            {mine + " folk could out-chop " + theirs + " folk any day.", "Go on then. Loser buys the lobsters."},
            {"Still telling people " + theirs + " is the place to be, " + them + "?", "It is. " + mine + " just hasn't noticed yet."},
            {"You can always spot someone from " + theirs + ". Lost, mostly.", "And you lot from " + mine + " never stop talking about it."},
            {"Proper adventurers start in " + mine + ". Everyone knows that.", "Proper adventurers leave " + mine + " as quick as they can."}
        };
        return lines[RNG.nextInt(lines.length)];
    }

    static void banter() {
        List<Player> bots = new ArrayList<>(), humans = new ArrayList<>();
        for (Player p : Repository.getPlayers()) {
            if (p == null || !p.isActive()) continue;
            (p.isArtificial() ? bots : humans).add(p);
        }
        if (humans.isEmpty() || bots.size() < 2) return;
        Collections.shuffle(bots, RNG);
        long now = System.currentTimeMillis();
        for (Player a : bots) {
            if (BOT_COOLDOWN.getOrDefault(a.getUsername().toLowerCase(Locale.ROOT), 0L) > now) continue;
            Player watcher = null;
            for (Player h : humans) if (h.getLocation().getZ() == a.getLocation().getZ() && h.getLocation().getDistance(a.getLocation()) <= SEE_RANGE) { watcher = h; break; }
            if (watcher == null) continue;
            for (Player b : bots) {
                if (b == a || b.getLocation().getZ() != a.getLocation().getZ() || b.getLocation().getDistance(a.getLocation()) > 6) continue;
                if (BOT_COOLDOWN.getOrDefault(b.getUsername().toLowerCase(Locale.ROOT), 0L) > now) continue;
                if (RNG.nextInt(3) == 0) { // talk about the human watching, behind their back (in earshot, like people do)
                    String[] g = gossip(a, b, watcher);
                    BOT_COOLDOWN.put(a.getUsername().toLowerCase(Locale.ROOT), now + 20_000);
                    BOT_COOLDOWN.put(b.getUsername().toLowerCase(Locale.ROOT), now + 20_000);
                    speak(a, b, Collections.singletonList(Persona.of(a).flavour(g[0])), 1);
                    speak(b, a, Collections.singletonList(Persona.of(b).flavour(g[1])), 4);
                    if (DEBUG) System.out.println("[BotChat] gossip about " + watcher.getUsername() + ": " + g[0] + " / " + g[1]);
                    return;
                }
                String[] ra = SmartAdventurers.story(a), rb = SmartAdventurers.story(b); // batch 16 item2: bots from different home towns, 1 in 5: a friendly rivalry
                if (ra != null && rb != null && !ra[2].equals(rb[2]) && !ra[2].startsWith("nowhere") && !rb[2].startsWith("nowhere") && RNG.nextInt(5) == 0 && now >= nextRivalry) {
                    nextRivalry = now + RIVALRY_GAP; rivalries++;
                    String[] r = rivalry(ra[2], rb[2], a.getUsername(), b.getUsername());
                    BOT_COOLDOWN.put(a.getUsername().toLowerCase(Locale.ROOT), now + 20_000);
                    BOT_COOLDOWN.put(b.getUsername().toLowerCase(Locale.ROOT), now + 20_000);
                    speak(a, b, Collections.singletonList(Persona.of(a).flavour(r[0])), 1);
                    speak(b, a, Collections.singletonList(Persona.of(b).flavour(r[1])), 4);
                    if (DEBUG || Boolean.getBoolean("test.b16b")) System.out.println("[BotChat] rivalry " + a.getUsername() + " (" + ra[2] + ") <-> " + b.getUsername() + " (" + rb[2] + "): " + r[0] + " / " + r[1]);
                    return;
                }
                String[] pair = BANTER[RNG.nextInt(BANTER.length)];
                String first = pair[0].contains("%s") ? String.format(pair[0], pair[0].startsWith("How's the") ? tradeWord(b) : b.getUsername()) : pair[0];
                String second = String.format(pair[1], a.getUsername());
                BOT_COOLDOWN.put(a.getUsername().toLowerCase(Locale.ROOT), now + 20_000);
                BOT_COOLDOWN.put(b.getUsername().toLowerCase(Locale.ROOT), now + 20_000);
                speak(a, b, Collections.singletonList(Persona.of(a).flavour(first)), 1);
                speak(b, a, Collections.singletonList(Persona.of(b).flavour(second)), 4);
                if (RNG.nextInt(3) == 0) for (Player c : bots) { // a third bot overhears and joins in
                    if (c == a || c == b || c.getLocation().getZ() != a.getLocation().getZ() || c.getLocation().getDistance(a.getLocation()) > 6) continue;
                    if (BOT_COOLDOWN.getOrDefault(c.getUsername().toLowerCase(Locale.ROOT), 0L) > now) continue;
                    BOT_COOLDOWN.put(c.getUsername().toLowerCase(Locale.ROOT), now + 20_000);
                    speak(c, a, Collections.singletonList(Persona.of(c).flavour(pick("Every day with you two.", "Ha! Told you, " + b.getUsername() + ".", "Don't get them started.", "I'm with " + a.getUsername() + " on this one.", "Can we talk about something else?"))), 7);
                    break;
                }
                if (DEBUG) System.out.println("[BotChat] banter " + a.getUsername() + " <-> " + b.getUsername() + " for " + watcher.getUsername());
                return;
            }
        }
    }

    static String tradeWord(Player bot) {
        String a = activityOf(bot);
        if (a == null) return "adventuring";
        String w = a.split(" ")[0];
        return w.isEmpty() ? "adventuring" : w;
    }

    static String activityOf(Player bot) {
        Supplier<String> s = ACTIVITY.get(bot.getUsername().toLowerCase(Locale.ROOT));
        if (s == null) return null;
        try { return s.get(); } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------ personas

    /** A voice per bot, fixed by its name, so the same bot always sounds the same. */
    static final class Persona {
        static final Map<String, Persona> CACHE = new ConcurrentHashMap<>();
        enum Style { CHEERY, GRUFF, POSH, DREAMY, SALTY }
        final Style style;

        Persona(Style style) { this.style = style; }

        static Persona of(Player bot) {
            return CACHE.computeIfAbsent(bot.getUsername().toLowerCase(Locale.ROOT), n -> new Persona(Style.values()[Math.abs(n.hashCode()) % Style.values().length]));
        }

        String greet(String name) {
            switch (style) {
                case CHEERY: return pick("Hello " + name + "! Lovely to see you.", "Hi " + name + "! How's the adventuring?", "Oh hello, " + name + "!", "Hey " + name + "! What a lovely surprise.", "Hiya " + name + "! Good day for it.", "There you are, " + name + "!");
                case GRUFF: return pick("Hm. " + name + ".", "What do you want, " + name + "?", "Aye. Hello.", "Mm. " + name + ".", "Aye, " + name + ". What is it?", "You again, " + name + "? Fine. Hello.");
                case POSH: return pick("Good day, " + name + ".", "Ah, " + name + ". Charmed, I'm sure.", "Greetings, " + name + ".", "A pleasure, " + name + ".", "Well met, " + name + ".", "How delightful. " + name + ".");
                case DREAMY: return pick("Oh... hello " + name + ". Didn't see you there.", "Hello " + name + ". Lovely sky today.", "Hm? Oh, hi " + name + ".", "Mm? " + name + "? Hello, hello.", "Hi " + name + "... were the clouds always that shape?", "Oh! " + name + ". I was miles away.");
                default: return pick("Alright, " + name + "?", "Well, well. " + name + ".", "Ahoy, " + name + ".", "Oi oi, " + name + ".", "Evening, " + name + ". Or morning. Whichever.", "Look who it is. " + name + "!");
            }
        }

        String greetAgain(String name, Memory.Met met) {
            String since = met.daysSinceFirst() >= 2 ? pick("Still here after " + met.daysSinceFirst() + " days, eh?", "You're practically a local now.", "Back again, like the tide.") : pick("Back so soon?", "Twice in one day. I'm honoured.", "Again? Can't keep away.");
            switch (style) {
                case CHEERY: return pick("Hello again, " + name + "! " + since, "Oh, it's " + name + "! " + since);
                case GRUFF: return pick(name + ". " + since, "You again. " + since);
                case POSH: return pick("Ah, " + name + ", welcome back. " + since, "Good to see you once more, " + name + ".");
                case DREAMY: return pick("Oh! " + name + ". Have we met? We have. " + since, "Hello again... " + name + ", wasn't it?");
                default: return pick("Well, well. " + name + ". " + since, "Look what the river dragged in. " + name + "!");
            }
        }

        String thanks() {
            switch (style) {
                case CHEERY: return pick("You're very welcome!", "Any time!", "Happy to help!", "Glad I could help!", "No trouble at all!", "That's what I'm here for!");
                case GRUFF: return pick("Don't mention it. Really.", "Mm.", "Go on, then.", "Aye.", "Fine. Off you go.", "Don't make a habit of it.");
                case POSH: return pick("Think nothing of it.", "My pleasure entirely.", "Quite all right.", "Delighted to oblige.", "Of course.", "One does what one can.");
                case DREAMY: return pick("Oh, it's nothing.", "Was I helpful? How nice.", "Sure, sure.", "Mm, any time.", "Did I do that? Lovely.");
                default: return pick("You owe me a pint.", "Cheers. Now off you go.", "No bother.", "Easy.", "Any time, mate.", "Put it on my tab.");
            }
        }

        String bye(String name) {
            switch (style) {
                case CHEERY: return pick("Bye " + name + "! Safe travels!", "See you soon, " + name + "!", "Take care, " + name + "!", "Come back soon, " + name + "!", "Bye bye, " + name + "! Don't get eaten!");
                case GRUFF: return pick("Aye. Mind the goblins.", "Right.", "Off you go.", "Watch your back.", "Mm. Later.");
                case POSH: return pick("Farewell, " + name + ".", "Do come again.", "Until next time, " + name + ".", "Safe journeys.");
                case DREAMY: return pick("Bye bye... or was it hello?", "Oh, going already?", "See you in another life. Or tomorrow.", "Wave if you see a cloud shaped like me.");
                default: return pick("Don't die out there, " + name + ".", "Later, " + name + ".", "See ya, " + name + ".", "Mind how you go, " + name + ".", "Bring me back something shiny, " + name + ".");
            }
        }

        String unknown() {
            switch (style) {
                case CHEERY: return pick("Not sure about that one! Ask me where things are, or what they cost.", "Hmm, that's beyond me. Try the website, it knows everything.", "Ooh, no idea! Ask me about prices or places though.");
                case GRUFF: return pick("No idea. Ask me where something is, or how much it costs.", "Can't help with that.", "Don't know. Don't care much either.");
                case POSH: return pick("I'm afraid that's outside my expertise. Directions and prices, however...", "One doesn't know everything, dear.", "I couldn't possibly say.");
                case DREAMY: return pick("What was the question again?", "I was thinking about clouds. Say that again?", "Sorry, I was counting the birds.");
                default: return pick("Do I look like a wizard? Ask me where something is.", "Not a clue, friend.", "Beats me.", "Ask someone cleverer. Or me, about prices.");
            }
        }

        String intro(String botName, String doing) {
            String d = doing == null ? "minding my own business" : doing;
            switch (style) {
                case CHEERY: return "I'm " + botName + "! Today I'm " + d + ".";
                case GRUFF: return botName + ". " + capital(d) + ". That's all you're getting.";
                case POSH: return "I am " + botName + ", and I am presently " + d + ".";
                case DREAMY: return "Me? " + botName + ". I think. Currently " + d + ".";
                default: return botName + ", and I'm " + d + ". Don't wear it out.";
            }
        }

        /** Small verbal tics on top of a plain sentence. */
        String flavour(String s) {
            switch (style) {
                case GRUFF: return RNG.nextInt(3) == 0 ? s.replace("!", ".") : s;
                case POSH: return RNG.nextInt(3) == 0 ? s + " Quite." : s;
                case DREAMY: return RNG.nextInt(3) == 0 ? s + "..." : s;
                case SALTY: return RNG.nextInt(4) == 0 ? "Heh. " + s : s;
                default: return s;
            }
        }
    }

    /**
     * Picks a line, avoiding any line any bot said in the last 20 minutes when a fresh one is available (otherwise the
     * one said longest ago). the owner 2026-10-20: "reduce the chances of seeing the same thing twice in a short window".
     */
    static final Map<String, Long> USED_LINES = new ConcurrentHashMap<>();
    static String pick(String... options) {
        if (options.length == 1) return options[0];
        long now = System.currentTimeMillis();
        List<String> fresh = new ArrayList<>(); String oldest = options[0]; long ot = Long.MAX_VALUE;
        for (String o : options) {
            Long t = USED_LINES.get(o);
            if (t == null || now - t > 20 * 60_000L) fresh.add(o);
            else if (t < ot) { ot = t; oldest = o; }
        }
        String c = fresh.isEmpty() ? oldest : fresh.get(RNG.nextInt(fresh.size()));
        USED_LINES.put(c, now);
        if (USED_LINES.size() > 6000) USED_LINES.entrySet().removeIf(e -> now - e.getValue() > 20 * 60_000L);
        return c;
    }

    static String capital(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    // ------------------------------------------------------------ understanding

    static final class Brain {
        static final Pattern WHERE = Pattern.compile("(?:where(?:'s|s| is| are| can i (?:find|get|buy)| do i (?:find|get|buy)| to (?:find|get|buy))|how do i get to|directions to|nearest|closest)\\s+(?:the |a |an |some )?([a-z' ]+?)[?.!]*$");
        static final Pattern PRICE = Pattern.compile("(?:price of|price for|how much (?:is|are|for|do|does)|what(?:'s|s| is| are| do| does)|cost of|value of|worth of)\\s+(?:the |a |an |some )?([a-z' 0-9]+?)(?: worth| cost| sell for| go for| selling for| fetch| price)?[?.!]*$");
        static final Pattern PRICE2 = Pattern.compile("^([a-z' 0-9]+?)\\s+(?:price|cost|worth)[?.!]*$");
        static final Pattern STUCK = Pattern.compile("(?:stuck (?:on|with|at|in)|what(?:'s| is) next (?:on|in|for)|where (?:do i go|am i) (?:next )?(?:on|in|for)|how far (?:am i|through)(?: on| in)?|next step (?:on|in|for))\\s+(?:the )?([a-z' &]+?)[?.!]*$");
        static final Pattern QUEST = Pattern.compile("(?:start|begin|do|find|where(?:'s| is)|how do i (?:start|do|begin))\\s+(?:the )?(?:quest )?([a-z' &]+?)(?: quest)?[?.!]*$");

        static List<String> answer(Player bot, Persona who, Player speaker, String text, boolean addressed) {
            String name = speaker.getUsername();
            String t = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' &?!.]", " ").replaceAll("\\s+", " ").trim();
            String botName = bot.getUsername().toLowerCase(Locale.ROOT);
            if (t.contains(botName)) t = t.replace(botName, "").trim();
            boolean question = t.contains("?") || t.startsWith("how ") || t.startsWith("where ") || t.startsWith("what ") || t.startsWith("who ") || t.startsWith("when ") || t.startsWith("can ") || t.startsWith("is ") || t.startsWith("are ") || t.startsWith("do ") || t.startsWith("which ");
            List<String> out = new ArrayList<>();

            // --- directions
            Matcher m = WHERE.matcher(t);
            if (m.find()) {
                String ans = WorldData.whereIs(m.group(1).trim(), speaker.getLocation());
                if (ans != null) { Memory.topic(speaker, "the " + m.group(1).trim().replaceAll("^(the|a|an|some|nearest|closest) ", "")); out.add(ans); return out; }
            }
            if (t.matches(".*\\b(bank|furnace|anvil|altar|range|ge|grand exchange)\\b.*") && question) {
                String key = t.contains("grand exchange") || t.matches(".*\\bge\\b.*") ? "grand exchange" : t.replaceAll(".*\\b(bank|furnace|anvil|altar|range)\\b.*", "$1");
                String ans = WorldData.whereIs(key, speaker.getLocation());
                if (ans != null) { out.add(ans); return out; }
            }
            // --- a player's crew, an errand, a quest partner (Companions, the owner 2026-10-21)
            List<String> co = Companions.chat(bot, speaker, t, addressed);
            if (co != null) return co;
            // --- buying, selling and haggling with this bot (2026-10-21)
            List<String> tr = TradeTalk.trade(bot, who, speaker, t, text, addressed);
            if (tr != null) return tr;
            // --- prices
            m = PRICE.matcher(t);
            Matcher m2 = PRICE2.matcher(t);
            String itemQ = m.find() ? m.group(1) : m2.find() ? m2.group(1) : null;
            if (itemQ != null) {
                String ans = Items.priceLine(itemQ.trim());
                if (ans != null) { Memory.topic(speaker, "a good price for " + itemQ.trim()); out.add(ans); return out; }
            }
            // --- stuck on a quest: answered from the player's real progress (E28)
            Matcher stuck = STUCK.matcher(t);
            if (stuck.find()) {
                List<String> ans = Quests.stageHelp(speaker, stuck.group(1).replace("quest", "").trim());
                if (ans != null) { Memory.topic(speaker, "that quest"); return ans; }
            }
            // --- quests
            if (t.contains("quest") && !t.matches(".*\\b(first quest|easy quest|easiest quest|what quest should|which quest should|good quest|best quest|quest should i)\\b.*")) {
                Matcher q = QUEST.matcher(t);
                String qn = q.find() ? q.group(1).trim() : t.replace("quest", "").replace("?", "").trim();
                List<String> ans = Quests.describe(qn, speaker.getLocation());
                if (ans != null) { Memory.topic(speaker, "that quest"); return ans; }
                if (question) { out.add("Every quest guide is on the website, under Quests. Which one did you mean?"); return out; }
            }
            // --- about the bot
            if (t.matches(".*\\b(what are you doing|what you doing|what do you do|whatcha doing|up to|what are you up to|busy)\\b.*")) {
                String a = activityOf(bot);
                out.add(a != null ? who.flavour(capital(a) + ".") : who.flavour(pick("Minding my own business, mostly.", "Oh, this and that.", "Working. Always working.", "Thinking about lunch.", "Trying to look busy.", "Planning my next trip.")));
                return out;
            }
            if (t.matches(".*\\b(who are you|your name|who r u|what's your name|whats your name)\\b.*")) { out.add(who.intro(bot.getUsername(), activityOf(bot))); String[] st = SmartAdventurers.story(bot); if (st != null && RNG.nextBoolean()) out.add(who.flavour(pick("From " + st[2] + ", originally. Don't get me started on " + st[1] + ".", "Grew up in " + st[2] + ". Can't stand " + st[1] + ".", st[0]))); return out; }
            if (t.matches(".*\\b(are you a bot|r u a bot|you a bot|bot\\?)\\b.*") || t.matches(".*\\bbot\\b.*") && question) {
                out.add(who.flavour(pick("I'm a local. Been here longer than you have.", "A bot? I prefer 'resident'.", "Everyone's a bit of a bot before breakfast.", "Bot? I've been called worse. Mostly by people losing to me.", "Rude. I've got feelings. Somewhere."))); // rec-review 10-23 batch14 item3: no more "That's a person" (it claimed to be human)
                return out;
            }
            if (t.matches(".*\\b(how are you|how r u|how's it going|hows it going|you ok|you alright|how are things|how do you do)\\b.*")) {
                String a = activityOf(bot);
                out.add(who.flavour(pick("Can't complain, " + name + ".", "Not bad at all.", "Better now someone's talking to me.", "Tired, but the coins keep coming.", "Busy, but good.", "Living the dream, " + name + ".", "Better than a goblin's Tuesday.")) + (a != null && RNG.nextBoolean() ? " Been " + a + "." : ""));
                return out;
            }
            if (t.matches(".*\\b(your (?:level|levels|stats|combat)|what level|how strong)\\b.*")) {
                out.add(String.format("Attack %d, Strength %d, Defence %d, Hitpoints %d. Mining %d, Woodcutting %d, Fishing %d.",
                        bot.getSkills().getStaticLevel(0), bot.getSkills().getStaticLevel(2), bot.getSkills().getStaticLevel(1), bot.getSkills().getStaticLevel(3),
                        bot.getSkills().getStaticLevel(14), bot.getSkills().getStaticLevel(8), bot.getSkills().getStaticLevel(10)));
                return out;
            }
            // --- everyday questions: where to mine or train, its goal, money tips, news... (2026-10-21)
            List<String> ev = TradeTalk.everyday(bot, who, speaker, t, addressed, question);
            if (ev != null) return ev;
            // --- social
            if (t.matches("^(hi|hello|hey|hiya|yo|sup|hallo|howdy|greetings|good (?:morning|afternoon|evening|day)|afternoon|morning|evening|ello|oi|hey there|hi there|hello there)\\b.*")) { Memory.Met met = Memory.get(speaker); String fl = met != null && RNG.nextInt(2) == 0 ? Memory.fightLine(met, bot, name) : null; out.add(fl != null ? who.flavour(fl) : met != null && met.times > 1 ? who.greetAgain(name, met) : who.greet(name)); /* batch 16 item2: now and then, the last fight */ try { bot.getAnimator().animate(new core.game.world.update.flag.context.Animation(863)); } catch (Throwable ignored) { } return out; }
            if (t.matches(".*\\b(thanks|thank you|thx|ty|cheers|ta)\\b.*")) { out.add(who.thanks()); return out; }
            if (t.matches("^(bye|cya|see ya|see you|goodbye|later|gtg|g2g|night|good night|farewell|laters)\\b.*")) { out.add(who.bye(name)); return out; }
            if (t.matches(".*\\b(help|what can you do|what can i ask|commands)\\b.*")) {
                out.add("Ask me where something is, what it costs, how to start a quest, or what I'm doing.");
                out.add("The website has the full map, prices and every quest guide.");
                return out;
            }
            if (t.matches(".*\\b(follow me|come with me|come here|follow)\\b.*")) { out.add(who.flavour(pick("I've got work to do, " + name + ". Maybe later.", "Where to? No, don't tell me. I'm busy.", "I don't follow strangers. Policy.", "Can't, I'm on the clock.", "Lead on? No. I'm comfy here."))); return out; }
            if (t.matches(".*\\b(trade|sell me|buy my|wanna trade|want to trade|free stuff|give me|gimme)\\b.*")) {
                out.add(who.flavour(pick("If you've got something I need, trade me, " + name + ". Guide price.", "I buy what I use, " + name + ". Food, kit, that sort of thing. Trade me.", "Selling? Show me in the trade window. If I need it, I'll pay guide.", "Catch me at a bank and trade me, " + name + ". I'll look.")));
                return out;
            }
            if (t.matches(".*\\b(how many (?:people|players)|who's online|whos online|anyone online|is anyone here)\\b.*")) {
                int humans = 0; for (Player p : Repository.getPlayers()) if (p != null && !p.isArtificial() && p.isActive()) humans++;
                out.add(humans <= 1 ? "Just you and us locals right now, " + name + "." : humans + " real adventurers about, plus the rest of us.");
                return out;
            }
            if (t.matches(".*\\b(what time|the time|time is it)\\b.*")) {
                out.add("Server time is " + new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date()) + ". Not that the sun cares.");
                return out;
            }
            if (t.matches(".*\\b(penguin|penguins)\\b.*")) { out.add(who.flavour("The penguins move every week. The Status page on the website has this week's spots.")); return out; }
            if (t.matches(".*\\b(star|shooting star|stardust)\\b.*")) { out.add(who.flavour("When a star comes down the Status page shows where it landed. Bring a pickaxe.")); return out; }
            if (t.matches(".*\\b(noob|idiot|stupid|dumb|loser|shut up|ugly|useless)\\b.*")) {
                out.add(who.flavour(pick("Charming. Good luck with that attitude, " + name + ".", "I've been called worse by goblins.", "And yet here you are, talking to me.", "Noted. And ignored.", "My mum says I'm lovely.")));
                return out;
            }
            if (t.matches(".*\\b(lol|lmao|haha|hehe|rofl)\\b.*")) { out.add(who.flavour(pick("Heh.", "Glad someone's enjoying themselves.", "Ha! Good one.", "Haha.", "You're in a good mood.", "Ha! I needed that."))); return out; }
            if (t.matches(".*\\b(love you|marry me|you're cute|ur cute|kiss)\\b.*")) { out.add(who.flavour(pick("Steady on, " + name + ".", "I'm married to my work.", "Flattery gets you directions, nothing more.", "Buy me a lobster first.", "Ha. No."))); return out; }
            if (t.matches(".*\\b(wilderness|wildy|pk|pking)\\b.*")) { out.add(who.flavour("North of Edgeville anyone can attack you. Bank first, then be brave.")); return out; }
            if (t.matches(".*\\b(website|site|guide|guides|wiki)\\b.*")) { String site = System.getProperty("world.website", "").trim(); out.add(site.isEmpty() ? who.flavour("No website that I know of. Ask around.") : "Everything's on " + site + ": prices, map, hiscores and quest guides."); return out; }
            // --- nothing matched
            if (addressed || question) { out.add(who.unknown()); return out; }
            if (RNG.nextInt(4) == 0) { out.add(who.flavour(pick("Mm-hm.", "Is that so.", "You don't say.", "Right you are, " + name + ".", "Fair enough.", "Huh.", "If you say so.", "Interesting."))); return out; }
            return null;
        }
    }

    // ------------------------------------------------------------ world knowledge

    /** The same map data the website uses, so "where is the nearest furnace" has a real answer. */
    static final class WorldData {
        static final List<Object[]> features = new ArrayList<>(); // type, x, y
        static final List<Object[]> labels = new ArrayList<>();   // name, x, y
        static final List<Object[]> shops = new ArrayList<>();    // title, x, y, int[] stock
        static final List<Object[]> npcs = new ArrayList<>();     // name, x, y, level
        static final Map<String, String> ALIAS = new HashMap<>();
        static {
            ALIAS.put("bank", "Bank"); ALIAS.put("banker", "Bank"); ALIAS.put("furnace", "Furnace"); ALIAS.put("anvil", "Anvil"); ALIAS.put("altar", "Prayer altar");
            ALIAS.put("prayer altar", "Prayer altar"); ALIAS.put("range", "Cooking range"); ALIAS.put("cooking range", "Cooking range"); ALIAS.put("stove", "Cooking range");
            ALIAS.put("ge", "Grand Exchange"); ALIAS.put("grand exchange", "Grand Exchange"); ALIAS.put("exchange", "Grand Exchange");
            ALIAS.put("water", "Water source"); ALIAS.put("well", "Water source"); ALIAS.put("fishing", "Fishing spot"); ALIAS.put("fish", "Fishing spot"); ALIAS.put("fishing spot", "Fishing spot");
            ALIAS.put("spinning wheel", "Spinning wheel"); ALIAS.put("loom", "Loom"); ALIAS.put("pottery", "Pottery wheel"); ALIAS.put("windmill", "Windmill"); ALIAS.put("mill", "Windmill");
            ALIAS.put("cows", "Cow"); ALIAS.put("cow", "Cow"); ALIAS.put("chickens", "Chicken"); ALIAS.put("chicken", "Chicken"); ALIAS.put("goblins", "Goblin"); ALIAS.put("goblin", "Goblin");
            ALIAS.put("trees", "Tree"); ALIAS.put("tree", "Tree"); ALIAS.put("rocks", "rocks"); ALIAS.put("mine", "rocks"); ALIAS.put("mining", "rocks"); ALIAS.put("ore", "rocks");
        }

        static void load() throws Exception {
            File f = new File(ServerConstants.DATA_PATH, "map-data.json");
            if (!f.exists()) { System.out.println("[BotChat] no " + f.getAbsolutePath() + "; directions off"); return; }
            JSONObject d = (JSONObject) new JSONParser().parse(new FileReader(f));
            for (Object o : (JSONArray) d.get("features")) { JSONArray a = (JSONArray) o; features.add(new Object[]{String.valueOf(a.get(0)), num(a.get(1)), num(a.get(2))}); }
            for (Object o : (JSONArray) d.get("labels")) { JSONArray a = (JSONArray) o; labels.add(new Object[]{String.valueOf(a.get(0)).replace("\n", " "), num(a.get(1)), num(a.get(2))}); }
            for (Object o : (JSONArray) d.get("shops")) {
                JSONArray a = (JSONArray) o; JSONArray st = (JSONArray) a.get(3); int[] ids = new int[st.size()];
                for (int i = 0; i < ids.length; i++) ids[i] = num(st.get(i));
                shops.add(new Object[]{String.valueOf(a.get(0)).replaceAll("#.*$", "").trim(), num(a.get(1)), num(a.get(2)), ids});
                Object[] sh = shops.get(shops.size() - 1);
                if ("Harry's Fishing Shop".equals(sh[0]) && (Integer) sh[1] == 2836 && (Integer) sh[2] == 3447) { sh[1] = 2834; sh[2] = 3443; } // batch 26 fix 4: the map tile is behind Harry's counter (46 rescues/24 h, never within 5); 2834,3443 is inside the door, 37 planned tiles from the Catherby bank (TestB26 RING)
            }
            for (Object o : (JSONArray) d.get("npcs")) { JSONArray a = (JSONArray) o; npcs.add(new Object[]{String.valueOf(a.get(0)), num(a.get(3)), num(a.get(4)), num(a.get(1))}); }
            features.add(new Object[]{"Bank", 3208, 3220}); // Lumbridge castle bank is upstairs, so the surface map misses it
            boolean hasGe = false;
            for (Object[] l : labels) if (((String) l[0]).equalsIgnoreCase("Grand Exchange")) hasGe = true;
            if (!hasGe) labels.add(new Object[]{"Grand Exchange", 3165, 3487});
        }

        static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : Integer.parseInt(String.valueOf(o)); }

        static String whereIs(String q, Location from) {
            if (q == null || q.isEmpty() || features.isEmpty()) return null;
            q = q.replaceAll("^(the|a|an|some|nearest|closest) ", "").trim();
            if (q.equals("i") || q.equals("you") || q.length() < 2) return null;
            String target = ALIAS.getOrDefault(q, null);
            String sing = q.endsWith("s") ? q.substring(0, q.length() - 1) : q; // "yew trees" -> "yew tree"
            String stem = sing.replaceAll(" (tree|trees|rocks|rock|ore|logs|log)$", "");
            Set<String> forms = new HashSet<>();
            for (String base : new String[]{q, sing, stem}) { forms.add(base); forms.add(base + " tree"); forms.add(base + " rocks"); forms.add(base + " ore rocks"); }
            // 1. features (bank, furnace, yew tree, coal rocks...)
            Object[] best = null; double bestD = 1e9; String what = null;
            for (Object[] f : features) {
                String type = (String) f[0], lt = type.toLowerCase(Locale.ROOT);
                boolean hit = target != null ? (target.equals("rocks") ? lt.endsWith(" rocks") : target.equals("Tree") ? lt.endsWith(" tree") : type.equals(target))
                        : forms.contains(lt) || lt.startsWith(sing + " ") && !lt.endsWith(" rocks") && !lt.endsWith(" tree");
                if (!hit) continue;
                double dd = dist(from, (int) f[1], (int) f[2]);
                if (dd < bestD) { bestD = dd; best = f; what = type; }
            }
            if (best != null) return "Nearest " + describeType(what) + " is " + bearing(from, (int) best[1], (int) best[2]) + near((int) best[1], (int) best[2]) + ".";
            // 2. places (Varrock, the Grand Exchange, Draynor Manor...)
            String nq = (target != null ? target : q).toLowerCase(Locale.ROOT);
            for (Object[] l : labels) {
                String nm = ((String) l[0]).toLowerCase(Locale.ROOT);
                if (nm.equals(nq) || nm.equals("the " + nq) || nq.length() >= 4 && (nm.startsWith(nq + " ") || nm.endsWith(" " + nq))) {
                    double dd = dist(from, (int) l[1], (int) l[2]);
                    if (dd < bestD) { bestD = dd; best = l; }
                }
            }
            if (best != null) return best[0] + " is " + bearing(from, (int) best[1], (int) best[2]) + ".";
            // 3. fishing spots, monsters and people by name
            int spawns = 0;
            for (Object[] n : npcs) {
                String nm = ((String) n[0]).toLowerCase(Locale.ROOT);
                if (nm.equals(nq) || nm.equals(sing) || nm.equals(nq.replaceAll("s$", ""))) {
                    spawns++;
                    double dd = dist(from, (int) n[1], (int) n[2]);
                    if (dd < bestD) { bestD = dd; best = n; }
                }
            }
            if (best != null) {
                String nm = (String) best[0]; int lvl = (int) best[3];
                return (spawns == 1 ? nm : "Nearest " + nm.toLowerCase(Locale.ROOT) + (lvl > 0 ? " (level " + lvl + ")" : "")) + " is " + bearing(from, (int) best[1], (int) best[2]) + near((int) best[1], (int) best[2]) + ".";
            }
            // 4. shops by name, or by what they sell ("pickaxe" finds a shop with any pickaxe in stock)
            Integer itemId = Items.idOf(q);
            Set<Integer> partial = itemId == null && q.length() >= 4 ? Items.idsContaining(sing) : Collections.emptySet();
            String soldAs = null;
            for (Object[] s : shops) {
                String title = ((String) s[0]).toLowerCase(Locale.ROOT);
                int[] stock = (int[]) s[3];
                boolean hit = q.length() >= 3 && title.contains(q) || (itemId != null && contains(stock, itemId));
                if (!hit && !partial.isEmpty()) for (int id : stock) if (partial.contains(id)) { hit = true; break; }
                if (!hit) continue;
                double dd = dist(from, (int) s[1], (int) s[2]);
                if (dd < bestD) { bestD = dd; best = s; }
            }
            if (best != null) {
                String title = (String) best[0];
                boolean byItem = !title.toLowerCase(Locale.ROOT).contains(q);
                return (byItem ? capital(q.endsWith("s") ? q : q + "s") + " are sold at " : "") + title + ", " + bearing(from, (int) best[1], (int) best[2]) + ".";
            }
            return null;
        }

        static boolean contains(int[] a, int v) { for (int x : a) if (x == v) return true; return false; }

        static String describeType(String type) {
            String t = type.toLowerCase(Locale.ROOT);
            if (t.endsWith(" rocks")) return t.replace(" ore rocks", "").replace(" rocks", "") + " rock";
            return t;
        }

        static double dist(Location from, int x, int y) { return Math.max(Math.abs(from.getX() - x), Math.abs(from.getY() - y)); }

        /** "about 40 tiles north-east of you" / "right here". */
        static String bearing(Location from, int x, int y) {
            int dx = x - from.getX(), dy = y - from.getY();
            int d = Math.max(Math.abs(dx), Math.abs(dy));
            if (d <= 3) return "right here";
            String ns = dy > 0 ? "north" : "south", ew = dx > 0 ? "east" : "west";
            String dir = Math.abs(dy) > 2.4 * Math.abs(dx) ? ns : Math.abs(dx) > 2.4 * Math.abs(dy) ? ew : ns + "-" + ew;
            String far = d < 15 ? "just " : d < 60 ? "about " + round(d) + " tiles " : d < 250 ? "a fair walk, " + round(d) + " tiles " : "a long way, " + round(d) + " tiles ";
            return far + dir + " of you";
        }

        static int round(int d) { return d < 30 ? d : (d + 5) / 10 * 10; }

        /** ", in Varrock" for the nearest place name, if one is close. */
        static String near(int x, int y) {
            Object[] best = null; double bestD = 1e9;
            for (Object[] l : labels) {
                double dd = Math.max(Math.abs((int) l[1] - x), Math.abs((int) l[2] - y));
                if (dd < bestD) { bestD = dd; best = l; }
            }
            return best != null && bestD <= 45 ? ", near " + best[0] : "";
        }
    }

    /** Item names for prices and shop lookups. */
    static final class Items {
        static final Map<String, Integer> byName = new HashMap<>();

        static void load() {
            for (Map.Entry<Integer, ItemDefinition> e : ItemDefinition.getDefinitions().entrySet()) {
                ItemDefinition d = e.getValue();
                if (d == null || d.getName() == null || d.getName().equalsIgnoreCase("null") || !d.isTradeable()) continue;
                byName.putIfAbsent(d.getName().toLowerCase(Locale.ROOT), e.getKey());
            }
        }

        /** Tradeable items whose name contains the words, e.g. "pickaxe" -> every pickaxe. */
        static Set<Integer> idsContaining(String q) {
            Set<Integer> out = new HashSet<>();
            for (Map.Entry<String, Integer> e : byName.entrySet()) if (e.getKey().contains(q)) out.add(e.getValue());
            return out;
        }

        static Integer idOf(String q) {
            if (q == null) return null;
            q = q.trim().toLowerCase(Locale.ROOT);
            Integer id = byName.get(q);
            if (id == null && q.endsWith("s")) id = byName.get(q.substring(0, q.length() - 1));
            if (id == null && !q.endsWith("s")) id = byName.get(q + "s");
            if (id == null && q.endsWith("ies")) id = byName.get(q.substring(0, q.length() - 3) + "y");
            return id;
        }

        static String priceLine(String q) {
            Integer id = idOf(q);
            if (id == null) {
                // maybe "yew" for "yew logs", "lobster" for "raw lobster"
                for (String pre : new String[]{"", "raw ", "cooked "}) for (String suf : new String[]{" logs", " ore", " bar", " bones", " hide", " rune"}) {
                    id = byName.get(pre + q + suf); if (id != null) break;
                }
            }
            if (id == null) return null;
            int v = MarketBrain.value(id), stock = MarketBrain.stockOf(id);
            String nm = ItemDefinition.forId(id).getName();
            String s = capital(nm) + (nm.endsWith("s") ? " go" : " goes") + " for " + gp(v) + " gp on the Exchange";
            s += stock > 0 ? "; " + gp(stock) + " up for sale right now." : "; none for sale right now, so you could sell some.";
            return s;
        }

        static String gp(int n) { return String.format("%,d", n); }
    }

    /** The quest guides, so "how do I start Dragon Slayer" has an answer. */
    static final class Quests {
        static final class Q { String name, slug, start, startPoint, difficulty; int qp; int x = -1, y = -1; List<String> steps = new ArrayList<>(); }
        static final List<Q> all = new ArrayList<>();

        static void load() throws Exception {
            File f = new File(ServerConstants.DATA_PATH, "quests-data.json");
            if (!f.exists()) { System.out.println("[BotChat] no " + f.getAbsolutePath() + "; quest answers off"); return; }
            JSONObject d = (JSONObject) new JSONParser().parse(new FileReader(f));
            for (Object o : (JSONArray) d.get("quests")) {
                JSONObject j = (JSONObject) o; Q q = new Q();
                q.name = String.valueOf(j.get("name")); q.slug = String.valueOf(j.get("slug"));
                q.start = j.get("startNpc") == null ? null : String.valueOf(j.get("startNpc"));
                q.startPoint = j.get("startPoint") == null ? null : String.valueOf(j.get("startPoint"));
                q.difficulty = j.get("difficulty") == null ? "" : String.valueOf(j.get("difficulty"));
                q.qp = j.get("questPoints") == null ? 0 : WorldData.num(j.get("questPoints"));
                if (j.get("startXY") instanceof JSONArray) { JSONArray xy = (JSONArray) j.get("startXY"); q.x = WorldData.num(xy.get(0)); q.y = WorldData.num(xy.get(1)); }
                if (j.get("steps") instanceof JSONArray) for (Object st : (JSONArray) j.get("steps")) q.steps.add(String.valueOf(st));
                all.add(q);
            }
        }

        static Q find(String qn) {
            if (all.isEmpty() || qn == null || qn.length() < 3) return null;
            qn = qn.toLowerCase(Locale.ROOT).replace("'", "").replace("&", "and").trim();
            Q best = null;
            for (Q q : all) {
                String n = q.name.toLowerCase(Locale.ROOT).replace("'", "").replace("&", "and");
                if (n.equals(qn) || n.replace("the ", "").equals(qn.replace("the ", ""))) return q;
                if (best == null && (n.contains(qn) || qn.contains(n))) best = q;
            }
            return best;
        }

        /** E28: "I'm stuck on Dragon Slayer" answered from the player's real quest stage and our guide. */
        static List<String> stageHelp(Player player, String qn) {
            Q q = find(qn);
            if (q == null) return null;
            content.data.Quests key = null;
            try { for (content.data.Quests c : content.data.Quests.values()) if (c.getQuestName() != null && c.getQuestName().equalsIgnoreCase(q.name)) { key = c; break; } } catch (Throwable ignored) { }
            List<String> out = new ArrayList<>();
            int stage = -1; boolean done = false;
            if (key != null) {
                try { core.game.node.entity.player.link.quest.Quest quest = player.getQuestRepository().getQuest(key); if (quest != null) { stage = quest.getStage(player); done = quest.isCompleted(player); } } catch (Throwable ignored) { }
            }
            String name = player.getUsername();
            if (done) { out.add("You've finished " + q.name + " already, " + name + ". Memory like a sieve, eh?"); return out; }
            if (stage <= 0) { out.add("You haven't started " + q.name + " yet. " + (q.start != null ? "Go and see " + q.start + (q.x >= 0 ? ", " + WorldData.bearing(player.getLocation(), q.x, q.y) : "") + "." : "The guide's on the website.")); return out; }
            if (q.steps.isEmpty()) { out.add("You're partway through " + q.name + ". The full guide is on the website under Quests."); return out; }
            int idx = Math.min(q.steps.size() - 1, Math.max(0, (int) Math.floor(stage / 100.0 * q.steps.size())));
            String step = q.steps.get(idx);
            if (step.length() > 76) step = step.substring(0, 73) + "...";
            out.add("You're about " + (idx + 1) + " of " + q.steps.size() + " steps into " + q.name + ", " + name + ".");
            out.add(step);
            out.add("The rest is on the website, under Quests.");
            return out;
        }

        static List<String> describe(String qn, Location from) {
            if (all.isEmpty() || qn == null || qn.length() < 3) return null;
            qn = qn.toLowerCase(Locale.ROOT).replace("'", "").replace("&", "and").trim();
            Q best = null;
            for (Q q : all) {
                String n = q.name.toLowerCase(Locale.ROOT).replace("'", "").replace("&", "and");
                if (n.equals(qn) || n.replace("the ", "").equals(qn.replace("the ", ""))) { best = q; break; }
                if (best == null && (n.contains(qn) || qn.contains(n))) best = q;
            }
            if (best == null) return null;
            List<String> out = new ArrayList<>();
            String where = best.x >= 0 ? ", " + WorldData.bearing(from, best.x, best.y) : "";
            out.add(best.name + " starts with " + (best.start == null ? "something to read" : best.start) + where + ".");
            String second = (best.difficulty.isEmpty() ? "" : best.difficulty + ", ") + best.qp + " quest point" + (best.qp == 1 ? "" : "s") + ". Full guide on the website under Quests.";
            out.add(second.length() > 80 ? second.substring(0, 80) : second);
            return out;
        }
    }

    // ------------------------------------------------------------ memory of players (shared by every bot, survives restarts)

    static final class Memory {
        static final class Met {
            int times; long firstSeen, lastSeen; String lastTopic; boolean followedUp = true;
            String fight; long fightAt; // batch 16 item2: the last fight a bot saw them in, "helped|bot|monster", "killed|bot|monster" or "died|bot|killer"
            int daysSinceFirst() { return (int) ((System.currentTimeMillis() - firstSeen) / 86_400_000L); }
            // batch 24b item 2 (design section 5): the last 5 haggled deals, lowballs, the last walk-away, and trust owed back
            // after walk-away strikes (amendment 1: bot -> {points, since}). Written only while -Dbargain.on is set.
            final List<Bargain.Deal> deals = new ArrayList<>(); int lowballs; long lowballAt, walkedAway;
            final Map<String, long[]> owed = new ConcurrentHashMap<>();
        }
        static final Map<String, Met> MET = new ConcurrentHashMap<>();
        static final Set<String> SEEN_THIS_SESSION = Collections.newSetFromMap(new ConcurrentHashMap<>());
        static File file() { return new File(ServerConstants.DATA_PATH, "botchat-memory.json"); }

        static Met get(Player p) { return MET.get(p.getUsername().toLowerCase(Locale.ROOT)); }
        /** batch 24b: get or create, without counting a visit (BargainHooks). */
        static Met met(Player p) { return MET.computeIfAbsent(p.getUsername().toLowerCase(Locale.ROOT), x -> { Met n = new Met(); n.firstSeen = n.lastSeen = System.currentTimeMillis(); return n; }); }

        /** Called whenever a player speaks near bots; counts a "visit" once per login session. */
        static void saw(Player p) {
            String k = p.getUsername().toLowerCase(Locale.ROOT);
            Met m = MET.computeIfAbsent(k, x -> { Met n = new Met(); n.firstSeen = System.currentTimeMillis(); return n; });
            if (SEEN_THIS_SESSION.add(k + "/" + p.getSession())) m.times++;
            m.lastSeen = System.currentTimeMillis();
        }

        static void topic(Player p, String what) {
            saw(p);
            Met m = get(p);
            if (m != null && what != null && what.length() < 60) { m.lastTopic = what; m.followedUp = false; }
        }

        /** batch 16 item2: remember a fight a bot was part of or saw. kind: helped (the bot hit it too), killed (near the bot), died (near the bot). */
        static void fight(Player h, String kind, Player bot, String other) {
            if (h == null || bot == null || other == null) return;
            saw(h); Met m = get(h); if (m == null) return;
            m.fight = kind + "|" + bot.getUsername() + "|" + other.toLowerCase(Locale.ROOT); m.fightAt = System.currentTimeMillis();
            if (DEBUG || Boolean.getBoolean("test.b16b")) System.out.println("[BotChat] remembers " + h.getUsername() + ": " + m.fight);
        }

        /** batch 16 item2: a greeting about that fight (within 6 hours; said once), or null. */
        static String fightLine(Met m, Player bot, String name) {
            if (m == null || m.fight == null || System.currentTimeMillis() - m.fightAt > 6 * 3_600_000L) return null;
            String[] f = m.fight.split("\\|", 3); if (f.length < 3) return null;
            boolean me = f[1].equalsIgnoreCase(bot.getUsername()); String what = f[2];
            m.fight = null;
            switch (f[0]) {
                case "helped": return me ? pick("Good fight with that " + what + " earlier, " + name + ".", name + "! We showed that " + what + ", didn't we?", "Still buzzing from that " + what + ", " + name + ".") : pick(f[1] + " says you two took down a " + what + ", " + name + ".", "Heard you and " + f[1] + " sorted a " + what + " out, " + name + ".");
                case "killed": return me ? pick("Saw you drop that " + what + ", " + name + ". Tidy.", "Nice work on that " + what + " earlier, " + name + ".") : pick("Word is you're handy against a " + what + ", " + name + ".", f[1] + " saw you flatten a " + what + ", " + name + ".");
                case "died": return me ? pick("You alright after that " + what + ", " + name + "? Nasty.", "Saw that " + what + " get you earlier, " + name + ". Back on your feet?") : pick("Heard a " + what + " got the better of you, " + name + ". Happens to us all.", f[1] + " said you had a rough one with a " + what + ", " + name + ".");
                default: return null;
            }
        }

        /** batch 16 item2: the engine's own kill and death events for a real player (hooked by Watch.poll). */
        static final core.game.event.EventHook<core.game.event.NPCKillEvent> KILL = new core.game.event.EventHook<core.game.event.NPCKillEvent>() {
            @Override public void process(core.game.node.entity.Entity e, core.game.event.NPCKillEvent ev) {
                try {
                    if (!(e instanceof Player) || ev == null || ev.getNpc() == null) return;
                    Player h = (Player) e; Map<Integer, Integer> log = ev.getNpc().getImpactHandler().getPlayerImpactLog(); // still filled: NPC.finalizeDeath fires this before DeathTask clears it
                    Player near = null, helper = null;
                    for (Player b : Repository.getPlayers()) {
                        if (b == null || !b.isArtificial() || !b.isActive() || b.getLocation().getZ() != h.getLocation().getZ() || b.getLocation().getDistance(h.getLocation()) > 8) continue;
                        if (near == null) near = b;
                        if (log != null && log.getOrDefault(b.getDetails().getUid(), 0) > 0) { helper = b; break; }
                    }
                    if (helper != null) fight(h, "helped", helper, ev.getNpc().getName()); else if (near != null) fight(h, "killed", near, ev.getNpc().getName());
                } catch (Throwable t) { if (DEBUG) System.out.println("[BotChat] kill memory: " + t); }
            }
        };
        static final core.game.event.EventHook<core.game.event.SelfDeathEvent> DIED = new core.game.event.EventHook<core.game.event.SelfDeathEvent>() {
            @Override public void process(core.game.node.entity.Entity e, core.game.event.SelfDeathEvent ev) {
                try {
                    if (!(e instanceof Player) || ev == null) return;
                    Player h = (Player) e; core.game.node.entity.Entity k = ev.getKiller(); if (k == null || k == h) return;
                    for (Player b : Repository.getPlayers()) {
                        if (b == null || !b.isArtificial() || !b.isActive() || b.getLocation().getZ() != h.getLocation().getZ() || b.getLocation().getDistance(h.getLocation()) > 10) continue;
                        fight(h, "died", b, k.getName()); return;
                    }
                } catch (Throwable t) { if (DEBUG) System.out.println("[BotChat] death memory: " + t); }
            }
        };
        static void hook(Player h) { try { h.hook(core.game.event.NPCKillEvent.class, KILL); h.hook(core.game.event.SelfDeathEvent.class, DIED); } catch (Throwable t) { if (DEBUG) System.out.println("[BotChat] hook: " + t); } } // Entity.hook skips a hook it already has

        static void load() throws Exception {
            File f = file();
            if (!f.exists()) return;
            JSONObject d = (JSONObject) new JSONParser().parse(new FileReader(f));
            for (Object k : d.keySet()) {
                JSONObject j = (JSONObject) d.get(k); Met m = new Met();
                m.times = WorldData.num(j.get("times")); m.firstSeen = ((Number) j.get("first")).longValue(); m.lastSeen = ((Number) j.get("last")).longValue();
                m.lastTopic = j.get("topic") == null ? null : String.valueOf(j.get("topic")); m.followedUp = j.get("topic") == null;
                if (j.get("fight") != null && j.get("fightAt") instanceof Number) { m.fight = String.valueOf(j.get("fight")); m.fightAt = ((Number) j.get("fightAt")).longValue(); } // batch 16 item2
                loadBargain(j, m); // batch 24b item 2
                MET.put(String.valueOf(k), m);
            }
            System.out.println("[BotChat] remembers " + MET.size() + " players");
        }

        @SuppressWarnings("unchecked")
        static void save() throws Exception {
            JSONObject d = new JSONObject();
            for (Map.Entry<String, Met> e : MET.entrySet()) {
                JSONObject j = new JSONObject(); Met m = e.getValue();
                j.put("times", m.times); j.put("first", m.firstSeen); j.put("last", m.lastSeen); if (m.lastTopic != null) j.put("topic", m.lastTopic); if (m.fight != null) { j.put("fight", m.fight); j.put("fightAt", m.fightAt); } // batch 16 item2
                saveBargain(j, m); // batch 24b item 2
                d.put(e.getKey(), j);
            }
            Ledger.writeAtomic(file(), d.toJSONString()); // audit C-4: a crash mid-write left half a file
        }

        /** batch 24b item 2: keys "deals" (["bot|item|qty|total|when", ...]), "lowballs", "lowballAt", "walked", "owed" ({bot: [points, since]}). Absent = none. */
        static void loadBargain(JSONObject j, Met m) {
            if (j.get("deals") instanceof JSONArray) for (Object o : (JSONArray) j.get("deals")) Bargain.remember(m.deals, Bargain.Deal.decode(String.valueOf(o)));
            if (j.get("lowballs") instanceof Number) m.lowballs = ((Number) j.get("lowballs")).intValue();
            if (j.get("lowballAt") instanceof Number) m.lowballAt = ((Number) j.get("lowballAt")).longValue();
            if (j.get("walked") instanceof Number) m.walkedAway = ((Number) j.get("walked")).longValue();
            if (j.get("owed") instanceof JSONObject) for (Object k : ((JSONObject) j.get("owed")).keySet()) {
                Object v = ((JSONObject) j.get("owed")).get(k);
                if (v instanceof JSONArray && ((JSONArray) v).size() == 2 && ((JSONArray) v).get(0) instanceof Number && ((JSONArray) v).get(1) instanceof Number) {
                    long pts = ((Number) ((JSONArray) v).get(0)).longValue(), since = ((Number) ((JSONArray) v).get(1)).longValue();
                    if (pts > 0) m.owed.put(String.valueOf(k), new long[]{pts, since});
                }
            }
        }
        @SuppressWarnings("unchecked")
        static void saveBargain(JSONObject j, Met m) {
            synchronized (m.deals) { if (!m.deals.isEmpty()) { JSONArray a = new JSONArray(); for (Bargain.Deal d : m.deals) a.add(d.encode()); j.put("deals", a); } }
            if (m.lowballs > 0) { j.put("lowballs", m.lowballs); j.put("lowballAt", m.lowballAt); }
            if (m.walkedAway > 0) j.put("walked", m.walkedAway);
            if (!m.owed.isEmpty()) {
                JSONObject o = new JSONObject();
                for (Map.Entry<String, long[]> e : m.owed.entrySet()) { long[] v = e.getValue(); synchronized (v) { if (v[0] > 0) { JSONArray a = new JSONArray(); a.add(v[0]); a.add(v[1]); o.put(e.getKey(), a); } } }
                if (!o.isEmpty()) j.put("owed", o);
            }
        }
    }

    // ------------------------------------------------------------ trust between a player and a bot (owner notes #5)

    /** Per player, per bot: +1 for buying its callout, selling it something it needed, or a chat (once a day). Saved. */
    public static final class Trust {
        static final Map<String, Integer> T = new ConcurrentHashMap<>();
        static final Map<String, Long> LAST_CHAT = new ConcurrentHashMap<>(), GREETED = new ConcurrentHashMap<>();
        static String k(Player h, Player b) { return h.getUsername().toLowerCase(Locale.ROOT) + "|" + b.getUsername().toLowerCase(Locale.ROOT); }
        public static int of(Player h, Player b) { return h == null || b == null ? 0 : T.getOrDefault(k(h, b), 0); }
        public static void add(Player h, Player b, int n) { if (h == null || b == null || h.isArtificial() || !b.isArtificial()) return; T.merge(k(h, b), n, Integer::sum); }
        static void chatted(Player h, Player b) {
            String key = k(h, b); long now = System.currentTimeMillis();
            if (now - LAST_CHAT.getOrDefault(key, 0L) < 86_400_000L) return;
            LAST_CHAT.put(key, now); add(h, b, 1);
        }
        static final Map<String, Long> LAST_GIFT = new ConcurrentHashMap<>();
        /** batch 16 item2: a bot handed this player something for nothing outside the trade window (a quest partner's dropped key): +1, once an hour per pair. */
        static void gave(Player h, Player b) {
            if (h == null || b == null) return;
            String key = k(h, b); long now = System.currentTimeMillis();
            if (now - LAST_GIFT.getOrDefault(key, 0L) < 3_600_000L) return;
            LAST_GIFT.put(key, now); add(h, b, 1);
        }
        /** A player with trust 3+ with this bot, within r tiles. */
        public static Player trustedNear(Player bot, int r) {
            for (Player h : Repository.getPlayers()) if (h != null && h.isActive() && !h.isArtificial() && h.getLocation().getZ() == bot.getLocation().getZ() && h.getLocation().getDistance(bot.getLocation()) <= r && of(h, bot) >= 3) return h;
            return null;
        }
        static File file() { return new File(ServerConstants.DATA_PATH, "botchat-trust.json"); }
        static void load() throws Exception {
            File f = file(); if (!f.exists()) return;
            JSONObject d = (JSONObject) new JSONParser().parse(new FileReader(f));
            for (Object key : d.keySet()) T.put(String.valueOf(key), ((Number) d.get(key)).intValue());
        }
        @SuppressWarnings("unchecked")
        static void save() throws Exception {
            JSONObject d = new JSONObject(); d.putAll(T);
            Ledger.writeAtomic(file(), d.toJSONString()); // audit C-4
        }
        /** Trusted bots in the same bank greet the player by name (at most every 30 min per pair). */
        static void greet() {
            long now = System.currentTimeMillis();
            for (Player h : Repository.getPlayers()) {
                if (h == null || h.isArtificial() || !h.isActive()) continue;
                boolean bank = false; for (Location b : AfkTasks.BANKS) if (b.getDistance(h.getLocation()) <= 10) bank = true;
                if (!bank) continue;
                for (Player b : Repository.getPlayers()) {
                    if (b == null || !b.isArtificial() || !b.isActive() || b.getLocation().getZ() != h.getLocation().getZ() || b.getLocation().getDistance(h.getLocation()) > 8 || of(h, b) < 3) continue;
                    String key = k(h, b);
                    if (now - GREETED.getOrDefault(key, 0L) < 30 * 60_000L) continue;
                    GREETED.put(key, now);
                    int sk = Watch.training(h);
                    String where = sk == 14 ? "back from the mines?" : sk == 8 ? "been chopping?" : sk == 10 ? "catch anything?" : sk >= 0 && sk < 7 ? "been fighting again?" : "good to see you.";
                    String fl = RNG.nextInt(3) == 0 ? Memory.fightLine(Memory.get(h), b, h.getUsername()) : null; // batch 16 item2
                    speak(b, h, Collections.singletonList(Persona.of(b).flavour(fl != null ? fl : "Alright " + h.getUsername() + ", " + where)), 2);
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------ watching players: level-ups and what they're training

    static final class Watch {
        static final Map<String, int[]> LEVELS = new HashMap<>();
        static final Map<String, double[]> XP = new HashMap<>();
        static final Map<String, Integer> TRAINING = new HashMap<>(); // skill id with recent xp, or -1

        static void poll() {
            for (Player p : Repository.getPlayers()) {
                if (p == null || p.isArtificial() || !p.isActive()) continue;
                Memory.hook(p); // batch 16 item2: its kills and deaths near bots
                String k = p.getUsername().toLowerCase(Locale.ROOT);
                int[] lv = LEVELS.get(k); double[] xp = XP.get(k);
                int n = p.getSkills().getStaticLevel(0) >= 0 ? 24 : 24;
                int[] nowLv = new int[n]; double[] nowXp = new double[n]; int training = -1;
                for (int i = 0; i < n; i++) { nowLv[i] = p.getSkills().getStaticLevel(i); nowXp[i] = p.getSkills().getExperience(i); }
                if (lv != null) for (int i = 0; i < n; i++) {
                    if (nowLv[i] > lv[i] && nowLv[i] >= 10) { Rumours.levelUp(p, i, nowLv[i]); }
                    if (nowXp[i] > xp[i] && i != 3 && (training < 0 || i < 7 && training < 7)) training = i; // combat skills beat hitpoints
                }
                LEVELS.put(k, nowLv); XP.put(k, nowXp); TRAINING.put(k, training);
            }
            for (Player p : Repository.getPlayers()) { // batch 15 item4: quest points up = a quest done
                if (p == null || !p.isActive() || !Companions.human(p)) continue;
                int qp; try { qp = p.getQuestRepository().getPoints(); } catch (Throwable t) { continue; }
                Integer was = QP.put(p.getUsername().toLowerCase(Locale.ROOT), qp);
                if (was != null && qp > was) Rumours.questUp(p, qp);
            }
        }
        static final Map<String, Integer> QP = new HashMap<>(); // batch 15 item4

        static int training(Player p) { return TRAINING.getOrDefault(p.getUsername().toLowerCase(Locale.ROOT), -1); }

        static final Map<String, int[]> BOT_LEVELS = new HashMap<>();
        static final Map<String, Long> BOT_GZ = new HashMap<>();

        /** Bots level too: when one does and a player is in sight, a bot or two nearby says gz. */
        static void pollBots() {
            List<Player> humans = new ArrayList<>();
            for (Player p : Repository.getPlayers()) if (p != null && p.isActive() && !p.isArtificial()) humans.add(p);
            long now = System.currentTimeMillis();
            for (Player b : Repository.getPlayers()) {
                if (b == null || !b.isArtificial() || !b.isActive()) continue;
                String k = b.getUsername().toLowerCase(Locale.ROOT);
                int[] old = BOT_LEVELS.get(k), lv = new int[24];
                for (int i = 0; i < 24; i++) lv[i] = b.getSkills().getStaticLevel(i);
                BOT_LEVELS.put(k, lv);
                if (old == null || humans.isEmpty()) continue;
                int up = -1; for (int i = 0; i < 24; i++) if (lv[i] > old[i] && lv[i] >= 10) up = i;
                if (up < 0 || BOT_GZ.getOrDefault(k, 0L) > now) continue;
                boolean seen = false; for (Player h : humans) if (h.getLocation().getZ() == b.getLocation().getZ() && h.getLocation().getDistance(b.getLocation()) <= SEE_RANGE) seen = true;
                if (!seen) continue;
                BOT_GZ.put(k, now + 120_000);
                int said = 0, want = 1 + RNG.nextInt(2);
                for (Player c : Repository.getPlayers()) {
                    if (said >= want) break;
                    if (c == null || c == b || !c.isArtificial() || !c.isActive() || c.getLocation().getZ() != b.getLocation().getZ() || c.getLocation().getDistance(b.getLocation()) > 8) continue;
                    speak(c, b, Collections.singletonList(pick("gratz", "gz", "nice lvl", "gz " + b.getUsername(), "grats on " + lv[up], "gz!", "grats", "nice one", "gz mate", "woo, gz", "get in, gz")), 2 + 2 * said);
                    said++;
                }
                if (said > 0 && RNG.nextInt(2) == 0) speak(b, null, Collections.singletonList(pick("ty", "thanks", "cheers", ":)", "ty all", "thanks guys", "cheers :)")), 3 + 2 * said);
                if (DEBUG) System.out.println("[BotChat] " + b.getUsername() + " levelled " + core.game.node.entity.skill.Skills.SKILL_NAME[up] + " to " + lv[up] + ", " + said + " gz");
            }
        }
    }

    // ------------------------------------------------------------ rumours from real events

    static final class Rumours {
        static final Map<String, Long> CONGRATULATED = new HashMap<>();
        static String lastStar;

        static final Map<String, Long> QUEST_GZ = new HashMap<>(); // batch 15 item4: player -> last quest gz (one per 2 min)

        /** batch 15 item4: a real player's quest points went up: a bot in sight says gz in local chat, else a clan bot in the clan chat. */
        static void questUp(Player p, int qp) {
            long now = System.currentTimeMillis();
            String k = p.getUsername().toLowerCase(Locale.ROOT), n = p.getUsername();
            if (QUEST_GZ.getOrDefault(k, 0L) > now - 120_000) return;
            String line = pick("gz on the quest", "Gz " + n + "!", "Quest done? Gz!", "gz, " + qp + " qp now", "Nice one, gz", "grats on the quest " + n, "gz! what's next?", "woo, gz");
            for (Player b : Repository.getPlayers()) {
                if (b == null || b == p || !b.isArtificial() || !b.isActive() || Companions.human(b) || b.getLocation().getZ() != p.getLocation().getZ() || b.getLocation().getDistance(p.getLocation()) > 10) continue;
                QUEST_GZ.put(k, now);
                speak(b, p, Collections.singletonList(line), 2);
                System.out.println("[BotChat] quest gz for " + n + " (" + qp + " qp): " + b.getUsername() + " in local: " + line);
                return;
            }
            if (!Companions.ON || !Companions.inClan(p)) return;
            Player b = Companions.clanBot();
            if (b == null || b == p) return;
            QUEST_GZ.put(k, now);
            Companions.clanSay(b, line);
            System.out.println("[BotChat] quest gz for " + n + " (" + qp + " qp): " + b.getUsername() + " in the clan chat: " + line);
        }

        static void levelUp(Player p, int skill, int level) {
            String sk = core.game.node.entity.skill.Skills.SKILL_NAME[skill];
            news(String.format(pick("Did you hear %1$s hit %2$d %3$s? About time.", "%1$s is %2$d %3$s now. Show-off.", "%1$s got %3$s to %2$d today. Buy them a drink.", "Word round the bank is %1$s just made %2$d %3$s."), p.getUsername(), level, sk));
            long now = System.currentTimeMillis();
            if (CONGRATULATED.getOrDefault(p.getUsername(), 0L) > now - 120_000) return;
            for (Player b : Repository.getPlayers()) {
                if (b == null || !b.isArtificial() || !b.isActive() || b.getLocation().getZ() != p.getLocation().getZ() || b.getLocation().getDistance(p.getLocation()) > 10) continue;
                CONGRATULATED.put(p.getUsername(), now);
                speak(b, p, Collections.singletonList(Persona.of(b).flavour(pick("Level " + level + " " + sk + "! Nicely done, " + p.getUsername() + ".", "Was that a level? " + level + " " + sk + ". Not bad at all.", "Congratulations, " + p.getUsername() + ". " + level + " " + sk + " is nothing to sniff at."))), 2);
                return;
            }
        }

        /** Reads the shooting star plugin's private state; silent if the server's version differs. */
        static void pollStar() throws Exception {
            Class<?> c = Class.forName("content.global.activity.shootingstar.ShootingStarPlugin");
            java.lang.reflect.Field f = c.getDeclaredField("star"); f.setAccessible(true);
            Object star = f.get(null);
            if (star == null) return;
            Object obj = star.getClass().getMethod("getStarObject").invoke(star);
            String where = String.valueOf(star.getClass().getMethod("getLocation").invoke(star));
            if (obj == null || where == null || where.equals("null")) { lastStar = null; return; }
            if (where.equals(lastStar)) return;
            lastStar = where;
            news(String.format(pick("Something lit the sky up over %s a little while ago. The miners were running before it landed.", "A star came down near %s. Bring a pickaxe if you're quick, that dust goes fast.", "Did you see that over %s? Whole sky went white. There'll be a crowd on it by now."), where));
        }
    }

    // ------------------------------------------------------------ noticing gear and activity

    static final class Notice {
        static final int[] PICKS = {1265, 1267, 1269, 1273, 1271, 1275}, AXES = {1351, 1349, 1353, 1355, 1357, 1359};
        static final int[] TOOL_LEVEL = {1, 1, 6, 21, 31, 41};

        static String remark(Player bot, Player h, Persona who) {
            String name = h.getUsername();
            int hp = h.getSkills().getLifepoints(), max = Math.max(1, h.getSkills().getMaximumLifepoints());
            if (hp * 100 / max < 40) {
                String range = WorldData.whereIs("range", h.getLocation());
                return who.flavour(pick("You're on " + hp + " hitpoints, " + name + ". Eat something.", "Looking pale, " + name + ". " + (range != null ? range : "Find some food."), name + ", sit down before you fall down. " + hp + " hitpoints."));
            }
            int training = Watch.training(h);
            if (training >= 0 && RNG.nextBoolean()) {
                String sk = core.game.node.entity.skill.Skills.SKILL_NAME[training];
                int lvl = h.getSkills().getStaticLevel(training);
                int[] tools = training == 14 ? PICKS : training == 8 ? AXES : null;
                if (tools != null) {
                    int have = -1, could = 0;
                    for (int i = 0; i < tools.length; i++) { if (h.getInventory().containsItem(new Item(tools[i])) || h.getEquipment().containsItem(new Item(tools[i]))) have = i; if (lvl >= TOOL_LEVEL[i]) could = i; }
                    if (have >= 0 && have < could - 1) return who.flavour(pick("A " + ItemDefinition.forId(tools[have]).getName().toLowerCase(Locale.ROOT) + " at level " + lvl + ", " + name + "? Treat yourself to something sharper.", name + ", that " + ItemDefinition.forId(tools[have]).getName().toLowerCase(Locale.ROOT) + " is holding you back. You could use a " + ItemDefinition.forId(tools[could]).getName().toLowerCase(Locale.ROOT) + " now."));
                }
                return who.flavour(pick("Training " + sk + ", " + name + "? Good spot for it.", sk + " at " + lvl + ". Keep at it, " + name + ".", "Ah, " + sk + ". The patient one's skill, " + name + "."));
            }
            Item weapon = h.getEquipment().get(3);
            if (weapon != null && RNG.nextBoolean()) {
                String w = weapon.getName().toLowerCase(Locale.ROOT);
                int v = MarketBrain.value(weapon.getId());
                return who.flavour(v >= 10_000 ? pick("Nice " + w + ", " + name + ". Try not to lose it.", "Is that a real " + w + "? Keep your back to the wall.") : v < 100 ? pick("A " + w + ", " + name + "? Brave.", "That " + w + " has seen better days, " + name + ".") : pick("Good " + w + ", that.", "A " + w + ". Sensible choice, " + name + "."));
            }
            Item body = h.getEquipment().get(4);
            if (body == null && h.getSkills().getStaticLevel(1) >= 20) return who.flavour(pick("No armour, " + name + "? Bold.", "You'll catch your death without a chest piece, " + name + "."));
            return null;
        }
    }

    // ------------------------------------------------------------ safety sense

    static final class Safety {
        static final Map<String, Long> WARNED = new HashMap<>();

        static String warning(Player bot, Player h) {
            long now = System.currentTimeMillis();
            String name = h.getUsername();
            Location l = h.getLocation();
            if (l.getZ() == 0 && l.getY() >= 3520 && l.getY() < 3980 && l.getX() >= 2944 && l.getX() < 3392) {
                if (WARNED.getOrDefault(name + "/wild", 0L) < now - 1_800_000) { WARNED.put(name + "/wild", now); return "You're past the ditch, " + name + ". Anyone can attack you here. Bank your valuables."; }
            }
            int combat = h.getProperties().getCurrentCombatLevel();
            try {
                for (core.game.node.entity.npc.NPC n : core.game.world.map.RegionManager.getLocalNpcs(h, 7)) {
                    if (n == null || !n.isActive() || !n.isAggressive() || n.getDefinition() == null) continue;
                    int lvl = n.getDefinition().getCombatLevel();
                    if (lvl < 3 || lvl <= combat * 1.1 + 4) continue;
                    String nm = n.getName();
                    if (WARNED.getOrDefault(name + "/" + nm, 0L) > now - 900_000) continue;
                    WARNED.put(name + "/" + nm, now);
                    return pick("Careful, " + name + ": those " + nm.toLowerCase(Locale.ROOT) + "s are level " + lvl + " and attack on sight.", name + ", mind the " + nm.toLowerCase(Locale.ROOT) + "s. Level " + lvl + ", and not friendly.");
                }
            } catch (Throwable ignored) { }
            return null;
        }
    }

    // ------------------------------------------------------------ self test (no players needed)

    static void selfTest() {
        Location lum = Location.create(3222, 3218, 0);
        String[] qs = {"where is the nearest bank", "where can i find yew trees", "wheres the furnace?", "where is coal", "where are cows", "where is varrock",
                "how much are yew logs", "price of raw lobster", "lobster price", "whats a rune scimitar worth", "how do i start dragon slayer", "cooks assistant quest?",
                "where can i buy a pickaxe", "where is the ge", "where is a fishing spot", "where is aubury", "nearest anvil", "where is lumbridge", "where is a general store",
                "where is the grand exchange", "where can i find oak trees", "where are the iron rocks", "where is draynor manor", "where is hans", "where can i buy runes"};
        for (String q : qs) {
            String t = q.toLowerCase(Locale.ROOT);
            String a = null;
            Matcher m = Brain.WHERE.matcher(t);
            if (m.find()) a = WorldData.whereIs(m.group(1).trim(), lum);
            if (a == null) { Matcher p = Brain.PRICE.matcher(t), p2 = Brain.PRICE2.matcher(t); String iq = p.find() ? p.group(1) : p2.find() ? p2.group(1) : null; if (iq != null) a = Items.priceLine(iq.trim()); }
            if (a == null && t.contains("quest") || a == null && t.contains("start")) { Matcher qm = Brain.QUEST.matcher(t); List<String> l = Quests.describe(qm.find() ? qm.group(1) : t.replace("quest", "").replace("?", ""), lum); if (l != null) a = String.join(" | ", l); }
            System.out.println("[BotChat:test] " + q + " -> " + a);
        }
    }
}
