package content.custom;

import core.api.StartupListener;
import core.game.bots.AIPlayer;
import core.game.bots.GeneralBotCreator;
import core.game.bots.Script;
import core.game.bots.SkillingBotAssembler;
import core.game.container.Container;
import core.game.ge.GrandExchange;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.system.task.Pulse;
import core.game.world.GameSettings;
import core.game.world.GameWorld;
import core.game.world.map.Location;
import core.game.world.repository.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Small additions that make the bot population more useful and more alive:
 *  - Fish sweeper: the stock fishing bots bank their catch and never sell it, so every
 *    10 minutes their banked raw fish is moved into the bots' Grand Exchange stock.
 *  - A herb gatherer that roams Taverley and supplies grimy herbs to the GE.
 *  - Town folk: one chatty local per town who wanders the square and makes small talk.
 */
public final class BotLife implements StartupListener {
    static final Random RNG = new Random();
    static final Timer TIMER = new Timer("BotLife", true);
    static final boolean FAST = Boolean.getBoolean("traders.fast");

    // Raw fish the fishing bots bank (lobster, shark, swordfish, tuna, shrimp, anchovies, sardine, herring, trout, salmon).
    static final int[] RAW_FISH = {377, 383, 371, 359, 317, 321, 327, 345, 335, 331};

    @Override
    public void startup() {
        GameSettings settings = GameWorld.getSettings();
        if (settings == null || !settings.getEnable_bots()) return;
        TIMER.schedule(new TimerTask() {
            @Override
            public void run() {
                GameWorld.getPulser().submit(new Pulse(FAST ? 50 : 1000) { // about 10 minutes
                    @Override
                    public boolean pulse() {
                        try { sweepFish(); } catch (Throwable t) { System.out.println("[BotLife] sweep failed: " + t); }
                        return false;
                    }
                });
            }
        }, 60_000);
        later(() -> { boolean[] back = new boolean[1]; AIPlayer hb = ServiceBots.make("herbs", Location.create(2905, 3450, 0), SkillingBotAssembler.Wealth.AVERAGE, back);
                HerbGatherer hg = new HerbGatherer(Location.create(2905, 3450, 0)); new GeneralBotCreator(hg, hb); SmartAdventurers.finishTutorial(hb);
                // heartbeat: the server only ticks a bot script while the bot has no pulse, and a fight can leave one behind
                GameWorld.getPulser().submit(new Pulse(5) { @Override public boolean pulse() { if (!hb.isActive()) return true; if (hb.getPulseManager().hasPulseRunning()) { try { hg.tick(); } catch (Throwable ignored) { } } return false; } });
                core.game.bots.AIRepository.getPulseRepository().remove(hb.getUsername().toLowerCase()); }, 50_000);
        for (Town t : Town.values()) {
            later(() -> {
                boolean[] back = new boolean[1];
                AIPlayer bot = ServiceBots.make("local:" + t.name(), t.spot, SkillingBotAssembler.Wealth.values()[RNG.nextInt(3)], back);
                new GeneralBotCreator(new TownFolk(t), bot);
                SmartAdventurers.finishTutorial(bot);
                if (t == Town.VARROCK) VARROCK_LOCAL = bot;
                System.out.println("[BotLife] " + bot.getUsername() + " is the local in " + t.name);
            }, 30_000 + RNG.nextInt(90_000));
        }
        System.out.println("[BotLife] fish sweeper, herb gatherer and " + Town.values().length + " town folk scheduled");
    }

    static void later(Runnable r, long delay) {
        TIMER.schedule(new TimerTask() {
            @Override
            public void run() {
                // bots are made on the game thread, never on a timer (audit #27)
                GameWorld.getPulser().submit(new Pulse(1) { @Override public boolean pulse() { try { r.run(); } catch (Throwable t) { System.out.println("[BotLife] spawn failed: " + t); try { Failures.Life.loginFailed("service", null, t); } catch (Throwable ignored) { } /* batch 20 (19b+c follow-up 4) */ } return true; } });
            }
        }, FAST ? 5_000 : delay);
    }

    static final int FISH_CAP = 2000;
    static volatile AIPlayer VARROCK_LOCAL;

    static int stockOf(int id) {
        try { return GrandExchange.Companion.getBotstockForId(id); } catch (Throwable t) { return Integer.MAX_VALUE; }
    }

    /** Moves banked raw fish from every bot's bank into the bots' GE stock, up to a soft cap. */
    static void sweepFish() {
        Map<Integer, Integer> moved = new HashMap<>();
        for (Player p : Repository.getPlayers()) {
            if (p == null || !p.isArtificial()) continue;
            String k = p.getUsername().toLowerCase(java.util.Locale.ROOT);
            if (SmartAdventurers.BRAINS.containsKey(k) || Artisans.TRADE_OF.containsKey(k)) continue; // their fish are theirs (split inputs, callouts, errands)
            Container bank = p.getBank();
            if (bank == null) continue;
            for (int id : RAW_FISH) {
                int n = bank.getAmount(id);
                if (n <= 0) continue;
                // Don't pile up past the soft cap; the rest stays in the bots' banks for later.
                int room = FISH_CAP - stockOf(id) - moved.getOrDefault(id, 0);
                if (room <= 0) continue;
                n = Math.min(n, room);
                if (bank.remove(new Item(id, n))) {
                    Ledger.supply(p, id, n);
                    moved.merge(id, n, Integer::sum);
                }
            }
        }
        if (!moved.isEmpty()) System.out.println("[BotLife] fish to the GE: " + moved);
    }

    static String pick(String[] options) { return BotChat.pick(options); } // lines: no repeats in a short window

    static <T> T pick(T[] options) {
        return options[RNG.nextInt(options.length)];
    }

    /** Wall-clock timed script base: bot scripts are not run every game tick. */
    abstract static class Timed extends Script {
        long last = System.currentTimeMillis();
        long nextChat = System.currentTimeMillis() + 20_000 + RNG.nextInt(40_000);

        Timed() {
            for (int s : new int[]{0, 1, 2, 3, 4, 6}) skills.put(s, 80); // monsters leave them alone
        }

        @Override
        public Script newInstance() {
            return null;
        }

        void say(String text) {
            BotChat.say(bot, text);
        }

        /** A human player within a few tiles, if any. */
        Player nearbyPlayer(int range) {
            for (Player p : Repository.getPlayers()) {
                if (p == null || p.isArtificial() || !p.isActive()) continue;
                if (p.getLocation().getZ() == bot.getLocation().getZ() && p.getLocation().getDistance(bot.getLocation()) <= range) return p;
            }
            return null;
        }
    }

    // ------------------------------------------------------------ herb gatherer

    static final class HerbGatherer extends Timed {
        // grimy guam, marrentill, tarromin, harralander, ranarr, irit, avantoe, kwuarm, cadantine, lantadyme
        static final int[][] HERBS = {{199, 30}, {201, 25}, {203, 20}, {205, 15}, {207, 5}, {209, 10}, {211, 8}, {213, 6}, {215, 4}, {2485, 3}};
        static final String[] LINES = {
            "Guam, marrentill, tarromin... my satchel smells like a druid's sock.",
            "The druids pay well for herbs, but the Grand Exchange pays faster.",
            "Mind where you step, that's a perfectly good patch of weeds.",
            "Ranarr! No... just a weed. Again.",
            "Herblore is ninety percent picking things and ten percent smelling of them.",
            "If you see a pointy hat and a cauldron, tell Kaqemeex I said hello.",
            "Chaos druids drop the best herbs. They also throw curses. Worth it.",
            "Ranarr from a druid! Today's a good day.",
            "Druids again. They never learn, I never stop.",
            "Grimy herbs need cleaning. That's the herblore folk's problem, not mine.",
            "A satchel of herbs for the Exchange, and a lot of curses for me."};
        final Location home;
        long nextHaul = System.currentTimeMillis() + (FAST ? 30_000 : 15 * 60_000);

        HerbGatherer(Location home) {
            this.home = home;
        }

        boolean introduced;

        @Override
        public void tick() {
            if (bot == null || !bot.isActive()) return;
            if (!introduced) { introduced = true; BotChat.activity(bot, () -> "picking herbs around Taverley for the Exchange"); }
            long now = System.currentTimeMillis();
            if (now - last > 6_000 && !AfkTasks.RUNNING.containsKey(bot.getUsername().toLowerCase(java.util.Locale.ROOT))) { scriptAPI.randomWalkTo(home, 14); last = now; } // not while on a haul (it was cancelling the runner's walk every 6 s, review 10-22)
            if (now > nextChat) { say(pick(LINES)); nextChat = now + 60_000 + RNG.nextInt(120_000); }
            if (now > nextHaul && !AfkTasks.RUNNING.containsKey(bot.getUsername().toLowerCase(java.util.Locale.ROOT))) {
                // Herbs don't grow wild in 2009: it gets them the way players did, from chaos druids' drops, and banks
                // the haul onto the Exchange through the ledger like any other bot (2026-10-20).
                AfkTasks.ensureHerbSpots();
                AfkTasks.Spot[] spots = AfkTasks.SPOTS.get(AfkTasks.Task.HERB_DRUIDS);
                if (spots != null && spots.length > 0) {
                    AfkTasks.Spot sp = spots[0]; for (AfkTasks.Spot s2 : spots) if (s2.at.getDistance(home) < sp.at.getDistance(home)) sp = s2;
                    AfkTasks.Runner r = new AfkTasks.Runner(bot, AfkTasks.Task.HERB_DRUIDS, true, FAST ? 6 : 24, false, true);
                    r.sell = true; r.spot = sp.at; r.setDelay(3);
                    AfkTasks.launch(r, AfkTasks.State.TO_SPOT, sp.at); // audit 10-23 A-11: a halted start isn't left in RUNNING
                    say(pick(new String[]{"Off to the druids. They've got the good herbs.", "Herb run. Mind the druids' spells.", "Satchel's empty. Druid time."}));
                    System.out.println("[BotLife] herb gatherer " + bot.getUsername() + " off to the chaos druids");
                }
                nextHaul = now + (FAST ? 30_000 : 15 * 60_000 + RNG.nextInt(10 * 60_000));
            }
        }
    }

    // ------------------------------------------------------------ town folk

    enum Town {
        LUMBRIDGE("Lumbridge", 3222, 3218, new String[]{
            "Welcome to Lumbridge! Duke Horacio's castle is just over there.",
            "The cows east of the river are good for hides. And for being chased by.",
            "Beware the goblins across the bridge. They're cheeky, not dangerous.",
            "The bank's on the top floor of the castle. Mind the stairs.",
            "Bob sells axes just south of here, if you fancy some woodcutting.",
            "Ever been to the swamp to the south? Neither have I. I like my boots dry.",
            "The church bell rings when someone gets a really good drop. Or so I'm told.",
            "Need a cooking range? There's one in the castle kitchen. Ask the Cook nicely.",
            "The Lumbridge guide's been here forever. Ask him anything, he loves it.",
            "Goblins east of the river, cows by the farm, and a lot of new adventurers in between.",
            "Hans walks round the castle all day. Nobody knows why. Nobody asks.",
            "If the Duke gives you a quest, don't lose the anti-dragon shield. Trust me.",
            "The church is lovely. Father Aereck worries about the graveyard, though."}),
        VARROCK("Varrock", 3212, 3428, new String[]{
            "Varrock! Finest city in Misthalin, if you don't count the smell.",
            "The Grand Exchange is up north-west, past the palace. Can't miss it.",
            "Aubury's rune shop is just east of the square. He'll even take you to the essence mine.",
            "Lowe's archery shop, Horvik's armour, Zaff's staves... this square has it all.",
            "Watch out for the guards. They get twitchy around pickpockets.",
            "There's a mine south-east of the city with tin, copper and iron.",
            "The museum's being dug out to the south-west. Very exciting, apparently.",
            "Heard the King's knights are recruiting. Heard they're rubbish too.",
            "Aubury's rune shop is just east of the square. Tell him I sent you. He won't care, but tell him.",
            "Lowe sells bows by the square. Horvik does armour. Thessalia does... frocks.",
            "Keep clear of the sewers unless you like rats the size of cows.",
            "The palace guards are friendly. The ones on the walls less so.",
            "Varrock west bank's busy, east bank's busier. Pick your poison."}),
        FALADOR("Falador", 2965, 3380, new String[]{
            "Falador, home of the White Knights. Very shiny, very proud.",
            "The Dwarven Mine entrance is just north-east of town. Bring a pickaxe.",
            "The park's lovely this time of year. Mind the gardener, he bites.",
            "Wayne's chains, Cassie's shields, and Herquin's gems. All within a short walk.",
            "The furnace in the east of town is perfect for smelting your ore.",
            "Rumour has it the Mining Guild lets you in at 60 Mining. Lucky miners.",
            "Don't go poking around the Black Knights' Fortress to the north. Trust me.",
            "The White Knights train in the castle. Lots of shouting, very little winning.",
            "Wayne's Chains does chainmail. Cassie does shields. Falador does armour, basically.",
            "The park's lovely. The gardener's less lovely if you step on his flowers.",
            "Doric's mine is just north. He'll let you use his anvils if you're nice.",
            "Sir Amik Varze is in the castle if you fancy a quest. Bring a sense of humour."}),
        DRAYNOR("Draynor", 3093, 3247, new String[]{
            "Draynor Village. Don't go near the manor at night. Or the day, really.",
            "The willow trees by the bank are great for woodcutting practice.",
            "Shrimp and anchovies off the south shore. Bring a small net.",
            "Ned the sailor lives here. He'll tell you stories if you let him. You won't.",
            "The market has seeds for sale. Lumbridge's farmers swear by them.",
            "Aggie the witch makes dyes. Slightly scary, very helpful.",
            "Ned in the cottage makes rope. Ask him about his sailing days. Actually, don't.",
            "Draynor Manor at night? Not unless you want a vampire for a neighbour.",
            "The market's got a seed seller, a fish stall and a fellow who sells... nothing. Odd chap.",
            "Willows by the river are perfect for woodcutting. Bring a decent axe.",
            "The bank's always got a queue. Something about the jail next door being quieter."}),
        AL_KHARID("Al Kharid", 3293, 3180, new String[]{
            "Hot enough for you? Al Kharid's always like this.",
            "The toll gate's ten coins. Or free, if you've helped Prince Ali.",
            "The furnace here is the closest to the scorpion mine. Very handy for smiths.",
            "Ellis the tanner turns cowhides into leather. Cheap too.",
            "The Duel Arena's just north. Lose with dignity, friend.",
            "Camels! We've got camels. Not for riding, sadly.",
            "The palace guards fight anyone for practice. Bring food.",
            "Ellis tans hides into leather. Cheap, quick, smells terrible.",
            "The furnace is by the bank. Handy for smelters, sweaty for everyone else.",
            "Shantay Pass is south. Don't go into the desert without water. Really.",
            "Zeke sells scimitars. Louie sells legs. Armour legs. I mean armour."}),
        EDGEVILLE("Edgeville", 3094, 3494, new String[]{
            "Edgeville: last stop before the Wilderness. Bank your valuables!",
            "Past that ditch to the north, anyone can attack you. Anyone.",
            "There's a furnace right here by the bank. Smiths love it.",
            "The monastery up north will heal you if you're a good sort.",
            "Brave adventurers head north. Smart ones bank first.",
            "The Grand Exchange is just south-east. Walk it, it's good for you.",
            "Edgeville bank's the last stop before the Wilderness. Bank your valuables.",
            "Oziach lives in the hut. Grumpy. Sells rune platebodies to the brave.",
            "The monastery's to the west. The monks will heal you if you ask nicely.",
            "There's a dungeon under the trapdoor. Hill giants, mostly. And regret.",
            "The yew trees by the bank are good for woodcutting. Busy, though."}),
        CATHERBY("Catherby", 2809, 3440, new String[]{
            "Catherby! Best fishing on the coast, best views too.",
            "Lobsters, swordfish and tuna, all from that one stretch of shore.",
            "Harry sells fishing gear just off the beach. Lovely chap.",
            "Arhein's ship sails to places I can't pronounce.",
            "There's a range in the house by the bank. Cook your catch before it cooks you.",
            "Camelot's just over the hill to the west. Very kingly.",
            "Catherby beach has the best lobsters. Bring a cage and a lot of patience.",
            "Arhein sells boats. Well, he sails them. Ask him about Entrana.",
            "Harry's fishing shop has bait, nets and fish food. Mostly fish food.",
            "The range here cooks lovely. The Lumbridge one burns everything.",
            "Hickton makes arrows in the house up the hill. Great for rangers."}),
        PORT_SARIM("Port Sarim", 3022, 3209, new String[]{
            "Port Sarim. If it floats, it leaves from here.",
            "Boats to Karamja leave from the docks. Bananas await!",
            "Gerrant's fishy business sells nets and rods.",
            "Betty's magic emporium sells runes. And a very nice pink dye.",
            "The jail's down the road. Visiting hours are whenever you get caught.",
            "Thurgo the dwarf lives south of here. He loves redberry pie.",
            "Gerrant sells fishing gear. Wydin sells food. Brian sells axes. Everyone sells something.",
            "The boats go to Karamja from the docks. Don't forget your thirty coins.",
            "The Port Sarim jail's got a few guests. Don't be one.",
            "Betty's magic emporium has runes and a lovely pink dye if you need it.",
            "Thieves love the docks. Keep your hand on your purse."});

        final String name;
        final Location spot;
        final String[] lines;

        Town(String name, int x, int y, String[] lines) {
            this.name = name;
            this.spot = Location.create(x, y, 0);
            this.lines = lines;
        }
    }

    static final String[] SMALL_TALK = {
        "Lovely weather for a quest, isn't it?",
        "I once found a clue scroll. Then I lost it. Then a goblin found it.",
        "Has anyone seen my spade? I left it near a suspicious-looking tree.",
        "You look like you've got a good drop coming. Call it a hunch.",
        "Don't trust anyone offering to double your money. Learned that the hard way.",
        "Penguins are hiding all over the world this week, you know. Sneaky birds.",
        "If a travelling trader waves at you, hear them out. Good bargains sometimes.",
        "The shooting stars are worth chasing. Stardust makes a lovely souvenir.",
        "Mind the cows by the river. They're friendlier than they look. Barely.",
        "If you need a bank, follow the crowd. There's always a crowd.",
        "Market day today. Every day's market day, really.",
        "The town crier gets louder every hour. I think he's competing with someone.",
        "Ever tried the fishing here? Bring a net and a lot of patience.",
        "The guards are friendly if you don't stab anyone.",
        "Somebody's been feeding the ducks again. They're enormous now.",
        "Keep an eye on your purse near the market stalls. Just saying.",
        "There's a quest in every town if you ask enough people.",
        "New in town? The bank's the safest place to stand.",
        "Saw a travelling trader earlier. Loud fellow. Good prices though.",
        "If you're heading out, take food. Everyone forgets the food."};

    static final class TownFolk extends Timed {
        final Town town;
        final List<String> greeted = new ArrayList<>();

        TownFolk(Town town) {
            this.town = town;
        }

        boolean introduced;

        @Override
        public void tick() {
            if (bot == null || !bot.isActive()) return;
            if (!introduced) { introduced = true; BotChat.activity(bot, () -> "keeping an eye on " + town.name + ", like always"); }
            long now = System.currentTimeMillis();
            if (now - last > 8_000) {
                if (bot.getLocation().getDistance(town.spot) > 20) scriptAPI.teleport(town.spot);
                else scriptAPI.randomWalkTo(town.spot, 6);
                last = now;
            }
            try { if (Scenes.Greet.tickLocal(bot, now, greeted)) { nextChat = now + 8_000; return; } } catch (Throwable ignored) { } // batch 28: a newcomer under combat 10 near Lumbridge (Scenes.java)
            Player p = nearbyPlayer(4);
            if (p != null && !greeted.contains(p.getUsername())) {
                greeted.add(p.getUsername());
                if (greeted.size() > 20) greeted.remove(0);
                say(pick(new String[]{"Hello there, " + p.getUsername() + "! Welcome to " + town.name + ".",
                        "Ah, " + p.getUsername() + "! Back in " + town.name + " again?",
                        "Afternoon, " + p.getUsername() + ". Need directions?"}));
                nextChat = now + 8_000;
                return;
            }
            if (now > nextChat) {
                say(RNG.nextInt(4) == 0 ? pick(SMALL_TALK) : pick(town.lines));
                nextChat = now + 45_000 + RNG.nextInt(75_000);
            }
        }
    }
}
