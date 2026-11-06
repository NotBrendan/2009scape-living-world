package content.custom;

import core.cache.def.impl.ItemDefinition;
import core.game.node.entity.player.Player;
import core.game.node.entity.skill.Skills;
import core.game.world.map.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * More things a player can say to a bot and get a real answer (2026-10-21: "Expand the Smart Bot detections ...
 * and just different stuff too. Also apply this to other Bots and their trading").
 *
 * Trading: "do you need coal?", "will you buy my lobsters", "what are you buying", "how much would you pay for 20
 * sharks", "would you pay 1k each for yews", "do you have any iron ore", "sell me your nature runes", "what are you
 * selling". Answers come from the bot's real want-list (PlayerTrades.want), its real bank, and guide prices; a bot at a
 * bank that has what the player asked for puts it up for them (Deals.sellOnRequest).
 *
 * Everyday: where it's going, what it's saving for, how rich it is, its total level and best skill, where it's from,
 * money making and training tips, where to mine/fish/chop something (from what the surveyors have seen), doubling
 * scams, duels, friends and clans, compliments on gear, begging, lag, worlds, news, boredom, apologies, brb.
 */
final class TradeTalk {
    private TradeTalk() { }

    static final String ART = "(?:any |some |a |an |the |your |ur |my |more |of your |of the |of them |these |those )*";
    static final String TAIL = "(?:\\s+(?:from me|off me|from you|off you|at all|today|by any chance|for guide|at guide|for sale|to sell|please|pls|plz|mate|pal|bro|man|friend|then|now|for me|do you|right now|atm))*[?.! ]*$";
    static final Pattern WANT = Pattern.compile("(?:^|\\b)(?:do you|d'?you|you|would you|will you|are you|r you|could you|can you|wanna|want to|you wanna)\\s+(?:need|want|use|buy|be buying|buying|be interested in|interested in|looking for|after|in the market for|take|have a use for|got a use for|have use for|collect|collecting)\\s+" + ART + "([a-z' ]+?)" + TAIL);
    static final Pattern WANT2 = Pattern.compile("^(?:need|want|buying|buy|interested in|use)\\s+" + ART + "([a-z' ]+?)\\?" + TAIL);
    static final Pattern SELLTO = Pattern.compile("(?:^|\\b)(?:can i sell you|could i sell you|can i sell|i'?ll sell you|i can sell you|wanna buy|want to buy|you want to buy|i have|i got|i've got|ive got|selling)\\s+" + ART + "(?:\\d+\\s+)?([a-z' ]+?)(?:\\s+(?:if you want|for you|you want|interested|wanna buy|for sale|to sell|cheap))*" + TAIL);
    static final Pattern BUYING_Q = Pattern.compile("\\b(what (?:are|r) you (?:buying|after|looking for|in need of|short on|collecting|in the market for)|what do you (?:need|buy|want to buy|use|want)|what you (?:need|buying|want)|need anything|buying anything|want anything|anything you need|what can i sell you|can i sell you (?:something|anything)|what will you buy|what would you buy|what do you take|what are you short of|what're you after|whatcha need|whatcha buying|you need anything|you buying anything)\\b");
    static final Pattern PAY_Q = Pattern.compile("(?:how much|what)\\s+(?:would|will|do|d|'d|could|can)\\s+you\\s+(?:pay|give)(?:\\s+me)?\\s+(?:for|on)\\s+" + ART + "(?:\\d+\\s+)?([a-z' ]+?)" + TAIL);
    static final Pattern PAY_Q2 = Pattern.compile("(?:would|will|could)\\s+you\\s+(?:pay|give(?: me)?|do)\\s+\\S+(?:\\s+(?:each|ea|gp|coins|a piece))?\\s+(?:for|each for|per)\\s+" + ART + "(?:\\d+\\s+)?([a-z' ]+?)" + TAIL);
    static final Pattern PAY_Q3 = Pattern.compile("what(?:'s|s| is)\\s+your\\s+(?:offer|price|bid)\\s+(?:for|on)\\s+" + ART + "(?:\\d+\\s+)?([a-z' ]+?)" + TAIL);
    static final Pattern HAVE_Q = Pattern.compile("(?:^|\\b)(?:do you have|you have|have you got|you got|got any|got some|are you selling|you selling|r you selling|selling any|sell me|can i buy|could i buy|can i get|could i get|can i have|could i have|i'?m looking for|im looking for|looking for|wtb|anyone selling|anybody selling|who'?s selling|who has|anyone got|anybody got|any spare|spare)\\s+" + ART + "(?:\\d+\\s+)?([a-z' ]+?)" + TAIL);
    static final Pattern SELLING_Q = Pattern.compile("\\b(what (?:are|r) you selling|what you selling|whatcha selling|what do you sell|what have you got for sale|what'?s for sale|anything for sale|selling anything|got anything to sell|what you got|whatcha got)\\b");

    static final Map<String, String> SLANG = new HashMap<>();
    static {
        String[][] s = {{"lobs", "lobster"}, {"lobbies", "lobster"}, {"lobby", "lobster"}, {"lobbys", "lobster"}, {"sharkies", "shark"}, {"swordies", "swordfish"}, {"monks", "monkfish"},
                {"nats", "nature rune"}, {"natures", "nature rune"}, {"nat", "nature rune"}, {"laws", "law rune"}, {"law", "law rune"}, {"cosmics", "cosmic rune"}, {"chaos", "chaos rune"}, {"deaths", "death rune"},
                {"bloods", "blood rune"}, {"souls", "soul rune"}, {"fires", "fire rune"}, {"airs", "air rune"}, {"waters", "water rune"}, {"earths", "earth rune"}, {"minds", "mind rune"}, {"bodies", "body rune"},
                {"ess", "rune essence"}, {"rune ess", "rune essence"}, {"pess", "pure essence"}, {"pure ess", "pure essence"}, {"addy bar", "adamantite bar"}, {"addy bars", "adamantite bar"}, {"addy ore", "adamantite ore"},
                {"addy", "adamantite bar"}, {"rune bar", "runite bar"}, {"rune bars", "runite bar"}, {"rune ore", "runite ore"}, {"mith bar", "mithril bar"}, {"mith bars", "mithril bar"}, {"mith ore", "mithril ore"},
                {"mith", "mithril bar"}, {"iron", "iron ore"}, {"copper", "copper ore"}, {"tin", "tin ore"}, {"silver", "silver ore"}, {"steel", "steel bar"},
                {"rune scim", "rune scimitar"}, {"r scim", "rune scimitar"}, {"rscim", "rune scimitar"}, {"d scim", "dragon scimitar"}, {"dscim", "dragon scimitar"}, {"addy scim", "adamant scimitar"}, {"mith scim", "mithril scimitar"},
                {"whip", "abyssal whip"}, {"d bones", "dragon bones"}, {"dbones", "dragon bones"}, {"d long", "dragon longsword"}, {"dlong", "dragon longsword"}, {"d med", "dragon med helm"}, {"dmed", "dragon med helm"},
                {"rune pl8", "rune platebody"}, {"rune plate", "rune platebody"}, {"rune legs", "rune platelegs"}, {"rune full", "rune full helm"}, {"rune kite", "rune kiteshield"}, {"rune sq", "rune sq shield"},
                {"yews", "yew logs"}, {"yew", "yew logs"}, {"magics", "magic logs"}, {"willows", "willow logs"}, {"willow", "willow logs"}, {"oaks", "oak logs"}, {"oak", "oak logs"}, {"maples", "maple logs"}, {"maple", "maple logs"},
                {"logs", "logs"}, {"normal logs", "logs"}, {"ranarr", "ranarr weed"}, {"ranarrs", "ranarr weed"}, {"snaps", "snapdragon"}, {"pp", "prayer potion(4)"}, {"ppots", "prayer potion(4)"}, {"prayer pots", "prayer potion(4)"},
                {"prayer pot", "prayer potion(4)"}, {"str pots", "strength potion(4)"}, {"super str", "super strength(4)"}, {"cowhides", "cowhide"}, {"hides", "cowhide"}, {"feathers", "feather"}, {"flax", "flax"},
                {"bow strings", "bow string"}, {"bowstrings", "bow string"}, {"gems", "uncut sapphire"}, {"saps", "uncut sapphire"}, {"rubies", "uncut ruby"}, {"emeralds", "uncut emerald"}, {"diamonds", "uncut diamond"},
                {"big bones", "big bones"}, {"bones", "bones"}, {"arrows", "iron arrow"}, {"addy arrows", "adamant arrow"}, {"rune arrows", "rune arrow"}, {"mith arrows", "mithril arrow"}, {"steel arrows", "steel arrow"}};
        for (String[] p : s) SLANG.put(p[0], p[1]);
    }

    /** "any of your yews" -> the item id of yew logs, or null when it isn't an item. */
    static Integer resolve(String q) {
        if (q == null) return null;
        q = q.trim().replaceAll("^(?:any|some|a|an|the|your|ur|my|more|of|these|those)\\s+", "").replaceAll("\\s+(?:please|pls|mate|today)$", "").trim();
        if (q.length() < 2 || q.matches("(?:it|them|that|this|those|these|one|stuff|things|something|anything|help|money|gp|coins|gold|cash|friends?|you|me|food|a friend|time|fun)")) return null;
        String sl = SLANG.get(q);
        if (sl != null) q = sl;
        Integer id = BotChat.Items.idOf(q);
        if (id == null) for (String pre : new String[]{"", "raw ", "cooked ", "uncut "}) for (String suf : new String[]{"", " logs", " ore", " bar", " bones", " hide", " rune", "(4)"}) {
            if (id != null) break;
            id = BotChat.Items.byName.get(pre + q + suf);
            if (id == null && q.endsWith("s")) id = BotChat.Items.byName.get(pre + q.substring(0, q.length() - 1) + suf);
        }
        if (id == null) return null;
        ItemDefinition d = ItemDefinition.forId(id);
        if (d != null && !d.isUnnoted() && d.getNoteId() > 0) id = d.getNoteId(); // a note's name resolves to the item
        return id;
    }

    static String nm(int id) { return MarketBrain.name(id).toLowerCase(Locale.ROOT); }
    static String gp(long n) { return Haggle.gp(n); }
    static String key(Player p) { return p.getUsername().toLowerCase(Locale.ROOT); }

    static String group(Pattern p, String t) { Matcher m = p.matcher(t); return m.find() ? m.group(1).trim() : null; }

    static String list(List<String> xs, int max) {
        List<String> a = xs.size() > max ? xs.subList(0, max) : xs;
        if (a.isEmpty()) return "";
        if (a.size() == 1) return a.get(0);
        return String.join(", ", a.subList(0, a.size() - 1)) + " and " + a.get(a.size() - 1);
    }

    // ------------------------------------------------------------ trading

    static List<String> trade(Player bot, BotChat.Persona who, Player speaker, String t, String raw, boolean addressed) {
        List<String> out = new ArrayList<>();
        String name = speaker.getUsername();
        SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(key(bot));
        boolean artisan = Artisans.TRADE_OF.containsKey(key(bot));
        boolean buyer = br != null || artisan; // town folk and service bots don't buy from players

        // "what are you buying?"
        if (BUYING_Q.matcher(t).find()) {
            if (!buyer) { out.add(who.flavour(BotChat.pick("Me? Nothing, " + name + ". Try the Exchange.", "I'm not buying today.", "Not me. Ask an adventurer at the bank."))); return out; }
            List<String> w = PlayerTrades.wantList(bot);
            if (w.isEmpty()) { out.add(who.flavour(BotChat.pick("Nothing right now, I've got what I need.", "I'm sorted, thanks. Ask me another day.", "Nothing today. Pack's full of what I need."))); return out; }
            out.add(who.flavour(BotChat.pick("Right now: " + list(w, 4) + ". I pay guide, trade me at a bank.", "I'm after " + list(w, 4) + ". Guide price, trade me.", list(w, 3) + ", mostly. Bring them to a bank and trade me.")));
            return out;
        }
        // "how much would you pay for 20 sharks?", "would you pay 1k each for yews?"
        String pq = group(PAY_Q, t); if (pq == null) pq = group(PAY_Q2, t); if (pq == null) pq = group(PAY_Q3, t);
        if (pq != null) {
            Integer id = resolve(pq);
            if (id != null) {
                Haggle.Bid b = Haggle.read(raw, 0);
                int want = buyer ? PlayerTrades.want(bot, br, id) : 0;
                int guide = Math.max(1, MarketBrain.value(id));
                if (want <= 0) { out.add(who.flavour(BotChat.pick("I wouldn't buy " + nm(id) + " at all, " + name + ". No use for it.", "Nothing, sorry. I don't use " + nm(id) + ".", "Try the Exchange. I've no use for " + nm(id) + "."))); return out; }
                int n = b.qty > 0 ? Math.min(b.qty, want) : 0;
                if (b.amount >= 0) {
                    long each = b.each || n == 0 ? b.amount : b.amount / Math.max(1, n);
                    if (each <= guide * 105L / 100) out.add(who.flavour(BotChat.pick("At " + gp(each) + " each? Yes. Trade me at a bank.", "I'd pay that. Bring them to a bank.", "Deal, if you've got them. Trade me.")));
                    else out.add(who.flavour(BotChat.pick("Bit steep. Guide's " + gp(guide) + ", I'd go to " + gp(guide * 105L / 100) + ".", gp(each) + "? No, " + gp(guide) + " each is guide.", "Not that much. Guide, " + gp(guide) + " each.")));
                    return out;
                }
                out.add(who.flavour("Guide, " + gp(guide) + " each" + (n > 0 ? ", so " + gp((long) guide * n) + " for " + n : "") + ". I'd take up to " + want + "."));
                return out;
            }
        }
        // "do you need coal?", "will you buy my lobsters?", "can i sell you some bones?"
        String wq = group(WANT, t); if (wq == null) wq = group(WANT2, t); if (wq == null) wq = group(SELLTO, t);
        if (wq != null) {
            Integer id = resolve(wq);
            if (id != null) {
                int want = buyer ? PlayerTrades.want(bot, br, id) : 0;
                int guide = Math.max(1, MarketBrain.value(id));
                if (want > 0) {
                    boolean here = artisan || PlayerTrades.idle(br);
                    out.add(who.flavour(here ? BotChat.pick("Yes! Up to " + want + ". Guide's " + gp(guide) + " each, trade me.", "I'll take " + (want >= 100 ? "plenty" : "up to " + want) + ". " + gp(guide) + " a piece, trade me.", "Go on then, " + name + ". Trade me, I pay guide.")
                            : BotChat.pick("I could use " + (want == 1 ? "one" : "some") + ". Catch me at a bank and trade me.", "Yes, but I'm mid-trip. Find me at a bank.")));
                } else {
                    List<String> w = buyer ? PlayerTrades.wantList(bot) : new ArrayList<>();
                    out.add(who.flavour(BotChat.pick("No use for " + nm(id) + ", sorry.", "Not me, " + name + ". I don't use " + nm(id) + ".", "Nah. The Exchange will take " + nm(id) + ".") + (w.isEmpty() ? "" : " I'm after " + list(w, 2) + " though.")));
                }
                return out;
            }
        }
        // "do you have any iron ore?", "sell me your nature runes", "wtb lobsters"
        String hq = group(HAVE_Q, t);
        if (hq != null) {
            Integer id = resolve(hq);
            if (id != null) {
                int bank = bot.getBank().getAmount(id), pack = bot.getInventory().getAmount(id);
                boolean general = t.matches(".*\\b(anyone|anybody|who'?s|who has|wtb)\\b.*"); // said to the room, not to this bot
                String r = buyer ? Deals.sellOnRequest(bot, speaker, id) : null;
                if (r != null) { out.add(who.flavour(r)); return out; }
                if (general && bank + pack == 0) return null; // not this bot's business
                if (bank + pack == 0) {
                    int stock = MarketBrain.stockOf(id);
                    out.add(who.flavour(BotChat.pick("None on me, " + name + ".", "Haven't got any, sorry.", "Not a single one.") + (stock > 0 ? " The Exchange has " + gp(stock) + " up." : " Nobody's selling on the Exchange either.")));
                    return out;
                }
                out.add(who.flavour(BotChat.pick("A few, but I need them.", "Some, but they're spoken for.", "I've got some. Not selling, sorry.")));
                return out;
            }
        }
        // "what are you selling?"
        if (SELLING_Q.matcher(t).find() && (addressed || t.length() < 30)) {
            for (Deals.Offer o : Deals.OFFERS) if (o.seller == bot) { out.add(o.qty + " " + nm(o.id) + " at " + o.unit + " each. Trade me, or say how many."); return out; }
            out.add(who.flavour(BotChat.pick("Nothing right now. I call it out at the bank when I've got a haul.", "Nothing today. Ask me what I've got, though: I might have some in the bank.", "Not selling. Ask about something specific?")));
            return out;
        }
        return null;
    }

    // ------------------------------------------------------------ everyday questions

    static final String[] SKILL = {"Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Prayer", "Magic", "Cooking", "Woodcutting", "Fletching", "Fishing", "Firemaking", "Crafting", "Smithing", "Mining", "Herblore", "Agility", "Thieving", "Slayer", "Farming", "Runecrafting", "Hunter", "Construction", "Summoning"};

    static final Map<String, String[]> TRAIN = new HashMap<>();
    static {
        TRAIN.put("mining", new String[]{"Copper and tin south-east of Varrock to 15, iron at Al Kharid or the Varrock east mine after that. Coal at Barbarian Village once you're 30."});
        TRAIN.put("fishing", new String[]{"Shrimp at Lumbridge or Draynor, then fly fishing at Barbarian Village from 20. Lobsters at Karamja or Catherby from 40."});
        TRAIN.put("woodcutting", new String[]{"Normal trees anywhere, oaks from 15, then willows at Draynor from 30. Yews at Edgeville or Seers' from 60."});
        TRAIN.put("cooking", new String[]{"Cook what you catch. The range in Lumbridge castle burns less. Catherby's range is handy for lobsters."});
        TRAIN.put("smithing", new String[]{"Bronze then iron at the Varrock west anvils. The Edgeville or Falador furnace for bars."});
        TRAIN.put("firemaking", new String[]{"Burn logs in a line at the Grand Exchange. Willows from 30, maples from 45."});
        TRAIN.put("combat", new String[]{"Chickens and cows at Lumbridge, then Al Kharid warriors, then hill giants in the Edgeville dungeon from about 30. Rock crabs at Rellekka are great too."});
        TRAIN.put("attack", TRAIN.get("combat")); TRAIN.put("strength", TRAIN.get("combat")); TRAIN.put("defence", TRAIN.get("combat")); TRAIN.put("hitpoints", TRAIN.get("combat"));
        TRAIN.put("ranged", new String[]{"Chickens and cows with a shortbow, then rock crabs. Moss giants behind the fence are nice and safe."});
        TRAIN.put("magic", new String[]{"Strike spells on chickens, then curse and bolt spells. Splashing on a big monster is slow but cheap."});
        TRAIN.put("prayer", new String[]{"Bury every bone you get. Big bones from hill giants add up fast."});
        TRAIN.put("crafting", new String[]{"Leather from cowhides at the Al Kharid tanner, then spin flax at Seers' from 10. Gems from 20."});
        TRAIN.put("fletching", new String[]{"Arrow shafts, then shortbows and longbows. Willow longbows from 40 sell well strung."});
        TRAIN.put("agility", new String[]{"The Gnome Stronghold course to 35, then Barbarian Outpost. It's slow, but the run energy is worth it."});
        TRAIN.put("thieving", new String[]{"Men in Lumbridge, then the Ardougne cake stall from 5 and silk from 20."});
        TRAIN.put("runecrafting", new String[]{"Air runes from Rune Mysteries onwards. Falador's air altar is close to a bank."});
        TRAIN.put("herblore", new String[]{"Druidic Ritual first, then attack potions. Chaos druids drop plenty of herbs."});
        TRAIN.put("slayer", new String[]{"Turael in Burthorpe for easy tasks, then Vannaka in the Edgeville dungeon once you're stronger."});
    }

    static final Pattern GATHER = Pattern.compile("where (?:can|do|should|could) (?:i |you |we )?(?:go )?(?:to )?(mine|chop|cut|fish|catch|kill|fight|hunt|pick|pickpocket|steal from|find)\\s+" + ART + "([a-z' ]+?)" + TAIL);
    static final Pattern GATHER2 = Pattern.compile("(?:best|good|nearest|closest) (?:place|spot|area) (?:to|for) (mine|chop|cut|fish|catch|kill|fight|hunt|pick|mining|fishing|woodcutting|chopping)\\s+" + ART + "([a-z' ]+?)" + TAIL);
    static final Pattern REQ_Q = Pattern.compile("(?:what do (?:i|you) need|what'?s needed|do i need (?:a quest|anything|a level|a key)|requirements?|reqs?|how do i get (?:in|into)|can i (?:get|go) (?:in|into)|am i allowed (?:in|into)|what quest)\\s+(?:for|to (?:get to|get into|enter|go to|reach|visit)|into|in|to)?\\s*(?:the )?([a-z' ]{3,}?)[?.! ]*$");
    static final Pattern TRAIN_Q = Pattern.compile("(?:where|how) (?:can|do|should|could|to|would) (?:i |you |we )?(?:best )?(?:train|level|get|lvl|level up|raise|get xp in|get exp in|work on|improve)\\s+(?:my |up |in |at )?([a-z]+)");
    static final Pattern TRAIN_Q2 = Pattern.compile("(?:best|good|fastest|quickest) (?:way|place|spot|method) (?:to|for) (?:train |level |lvl )?([a-z]+)");

    static List<String> everyday(Player bot, BotChat.Persona who, Player speaker, String t, boolean addressed, boolean question) {
        List<String> out = new ArrayList<>();
        String name = speaker.getUsername();
        SmartAdventurers.Brain br = SmartAdventurers.BRAINS.get(key(bot));

        // where to mine / fish / chop something: what the surveyors have actually seen
        Matcher g = GATHER.matcher(t); if (!g.find()) { g = GATHER2.matcher(t); if (!g.find()) g = null; }
        if (g != null) {
            String verb = g.group(1), what = g.group(2).trim().replaceAll("s$", "");
            String kind = verb.startsWith("min") ? "rock:" : verb.startsWith("chop") || verb.startsWith("cut") || verb.startsWith("wood") ? "tree:" : verb.startsWith("fish") || verb.startsWith("catch") ? "fish:" : verb.startsWith("pick") && !verb.startsWith("pickp") ? "pick:" : verb.startsWith("pickp") || verb.startsWith("steal") ? "pickpocket:" : "npc:";
            Location at = Resources.nearestMatching(kind, what, speaker.getLocation());
            if (at == null && kind.equals("fish:")) at = Resources.nearestMatching("fish:", fishOption(what), speaker.getLocation());
            if (at != null) {
                String thing = what + (kind.equals("rock:") ? " rocks" : kind.equals("tree:") && !what.endsWith("tree") ? " trees" : kind.equals("fish:") ? " fishing" : "");
                String need = Gates.missing(speaker, at);
                out.add(who.flavour(BotChat.pick("The surveyors have seen " + thing + " ", "There's " + thing + " ", "You'll find " + thing + " ") + BotChat.WorldData.bearing(speaker.getLocation(), at.getX(), at.getY()) + BotChat.WorldData.near(at.getX(), at.getY()) + "." + (need != null ? " You'll need " + need + " to get in." : "")));
                return out;
            }
            String w = BotChat.WorldData.whereIs(what, speaker.getLocation());
            if (w != null) { out.add(w); return out; }
        }
        // "what do I need for Canifis?", "do I need a quest for the Fishing Guild?", "can I get into the Heroes' Guild?"
        Matcher rq = REQ_Q.matcher(t);
        if (rq.find()) {
            String place = rq.group(1).trim();
            Gates.Gate gt = Gates.byName(place);
            Location loc = null;
            if (gt == null) { for (Object[] l : BotChat.WorldData.labels) if (((String) l[0]).toLowerCase(Locale.ROOT).contains(place)) { loc = Location.create((int) l[1], (int) l[2], 0); break; } if (loc != null) { java.util.List<Gates.Gate> gs = Gates.at(loc); if (!gs.isEmpty()) gt = gs.get(0); } }
            if (gt != null) {
                boolean has = gt.rule.ok(speaker);
                out.add(who.flavour(capital(gt.name) + " needs " + gt.rule.need() + ". " + (has ? BotChat.pick("You've got that, " + name + ". Off you go.", "You're sorted for it.") : BotChat.pick("You don't have that yet, " + name + ".", "Not yet for you, I'm afraid."))));
                return out;
            }
            if (loc != null) { out.add(who.flavour(BotChat.pick("Nothing special. Just walk there.", "Anyone can go there.", "No quest needed for that."))); return out; }
        }
        // training tips
        String sk = group(TRAIN_Q, t); if (sk == null) sk = group(TRAIN_Q2, t);
        if (sk != null) {
            sk = sk.replaceAll("^(?:wc|woodcut|woodcuting)$", "woodcutting").replaceAll("^(?:fm)$", "firemaking").replaceAll("^(?:str)$", "strength").replaceAll("^(?:att|atk)$", "attack").replaceAll("^(?:def)$", "defence")
                    .replaceAll("^(?:hp)$", "hitpoints").replaceAll("^(?:range|ranging)$", "ranged").replaceAll("^(?:mage)$", "magic").replaceAll("^(?:pray)$", "prayer").replaceAll("^(?:rc|runecraft)$", "runecrafting")
                    .replaceAll("^(?:cb|melee|fighting)$", "combat").replaceAll("^(?:mine)$", "mining").replaceAll("^(?:fish)$", "fishing").replaceAll("^(?:cook)$", "cooking").replaceAll("^(?:smith)$", "smithing")
                    .replaceAll("^(?:craft)$", "crafting").replaceAll("^(?:fletch)$", "fletching").replaceAll("^(?:agil|agi)$", "agility").replaceAll("^(?:thieve|thiev)$", "thieving").replaceAll("^(?:herb)$", "herblore");
            String[] tip = TRAIN.get(sk);
            if (tip != null) { out.add(who.flavour(tip[0])); return out; }
        }
        if (t.matches(".*\\b(bless (?:my|the|that) grave|grave blessing|bless it for me|can you bless|could you bless|repair my grave|keep my grave up)\\b.*")) {
            out.add(who.flavour(blessGrave(bot, speaker)));
            return out;
        }
        if (t.matches(".*\\b(where (?:are|r) you (?:going|off to|headed|heading)|where you (?:going|headed|off to)|where to next|where'?re you going|going somewhere)\\b.*")) {
            String a = BotChat.activityOf(bot);
            out.add(who.flavour(a != null ? BotChat.pick("I'm " + a + ".", capital(a) + ", " + name + ".", "Oh, " + a + ". Same as always.") : BotChat.pick("Wherever the work is.", "Bank, probably. Then who knows.", "Nowhere fast.")));
            return out;
        }
        if (t.matches(".*\\b(what (?:are|r) you saving (?:up )?for|saving (?:up )?for|what'?s your goal|whats your goal|your goal|what are you working towards|what do you want to buy|dream item)\\b.*")) {
            if (br != null && br.goal > 0) out.add(who.flavour(BotChat.pick("A " + nm(br.goal) + ". Getting there.", "Saving for a " + nm(br.goal) + ", " + name + ".", "The " + nm(br.goal) + ". One day.")));
            else out.add(who.flavour(BotChat.pick("A quiet life and a full bank.", "Nothing big. Just keeping busy.")));
            return out;
        }
        if (t.matches(".*\\b(how much (?:money|gp|cash|gold|coins) (?:do you|you) (?:have|got)|how rich|you rich|are you rich|your bank|how much you got|net ?worth|how loaded|how much are you worth)\\b.*")) {
            long c = Ledger.Wallet.coins(bot);
            out.add(who.flavour(c < 10_000 ? BotChat.pick("Not much, honestly.", "Barely enough for a lobster.", "Skint, " + name + ".") : c < 100_000 ? BotChat.pick("A few thousand. Don't tell anyone.", "Enough to get by.") : c < 1_000_000 ? BotChat.pick("Doing alright, thanks.", "Comfortable. Not rich.") : BotChat.pick("Let's say I'm comfortable.", "More than I let on.", "Rich enough to not say.")));
            return out;
        }
        if (t.matches(".*\\b(total level|your total|what'?s your total|whats your total|total lvl)\\b.*")) {
            int tot = 0; for (int i = 0; i < 24; i++) tot += bot.getSkills().getStaticLevel(i);
            out.add(who.flavour("Total " + tot + ". " + BotChat.pick("Working on it.", "Not bad, eh?", "Could be worse.")));
            return out;
        }
        if (t.matches(".*\\b(favou?rite skill|fav skill|best skill|highest skill|what do you train|what skill)\\b.*")) {
            int best = 0; for (int i = 1; i < 24; i++) if (i != Skills.HITPOINTS && bot.getSkills().getStaticLevel(i) > bot.getSkills().getStaticLevel(best)) best = i;
            out.add(who.flavour(SKILL[best] + ", " + bot.getSkills().getStaticLevel(best) + ". " + BotChat.pick("Can't get enough of it.", "It pays the bills.", "Took me ages.")));
            return out;
        }
        if (t.matches(".*\\b(where (?:are|r) you from|where do you live|your home ?town|where'?s home|whats home|where you from)\\b.*")) {
            String[] st = SmartAdventurers.story(bot);
            out.add(who.flavour(st != null ? BotChat.pick(st[2] + ", born and bred.", "From " + st[2] + ". You?", st[2] + ". Best town there is.") : BotChat.pick("Round here.", "Lumbridge, like everyone.")));
            return out;
        }
        if (t.matches(".*\\b(make money|money making|moneymaking|making money|get rich|best money|make gp|making gp|earn money|earn gp|good money|how (?:do|to|can) i (?:get|make) (?:money|gp|cash|rich))\\b.*")) {
            out.add(who.flavour(BotChat.pick("Cowhides from Lumbridge sell well when you're starting. Tan them for more.", "Fish lobsters at Karamja and bank them at the deposit box. Steady money.", "Mine coal and iron, smelt steel. The bots buy bars at the bank.", "Yew logs never go out of fashion. Seers' or Edgeville.", "Pick flax at Seers' and spin it. Bow strings always sell.")));
            out.add("The website's price list shows what sells best right now.");
            return out;
        }
        if (t.matches(".*\\b(doubling|double (?:my|your|ur) (?:money|gp|coins)|doubl(?:e|ing) money)\\b.*")) { out.add(who.flavour(BotChat.pick("Doubling? Never trust it. You'll be walking home in your socks.", "Nobody doubles money, " + name + ". Nobody.", "Oldest trick in Gielinor."))); return out; }
        if (t.matches(".*\\b(duel|1v1|fight me|wanna fight|want to fight|pk me|lets fight|let's fight|square up|stake)\\b.*")) { out.add(who.flavour(BotChat.pick("Not today, " + name + ".", "I don't fight for fun. Goblins, maybe.", "Find a dummy. I bruise easily.", "Duel Arena's that way. I'm not."))); return out; }
        if (t.matches(".*\\b(add me|friend me|be my friend|add you|can i add you|friends? list|be friends)\\b.*")) { out.add(who.flavour(BotChat.pick("Sure, add " + bot.getUsername() + ".", "Go on then. I'm " + bot.getUsername() + ".", "Friends it is."))); return out; }
        if (t.matches(".*\\b(join (?:my|our|a) clan|in a clan|your clan|clan chat|join cc|join my cc)\\b.*")) { out.add(who.flavour(BotChat.pick("No clan for me. Free agent.", "I'm more of a lone wolf.", "Clans are too much drama."))); return out; }
        if (t.matches(".*\\b(nice|cool|sick|love|like) (?:your )?(?:gear|armou?r|outfit|cape|hat|helm|sword|weapon|scimitar|scim|bow|staff|look|style|drip|fit|shield|boots)\\b.*")) {
            core.game.node.item.Item wep = bot.getEquipment().get(3);
            out.add(who.flavour(BotChat.pick("Cheers, " + name + "!", "Thanks! Took a while to get.", "Ha, cheers.") + (wep != null ? " The " + wep.getName().toLowerCase(Locale.ROOT) + " cost me a fair bit." : "")));
            return out;
        }
        if (t.matches(".*\\b(what (?:weapon|sword|scim) (?:is that|are you using|do you use)|what are you wearing|what'?s that (?:sword|weapon|armou?r))\\b.*")) {
            core.game.node.item.Item wep = bot.getEquipment().get(3), body = bot.getEquipment().get(4);
            out.add(who.flavour((wep != null ? "A " + wep.getName().toLowerCase(Locale.ROOT) : "Nothing in my hands") + (body != null ? " and a " + body.getName().toLowerCase(Locale.ROOT) : "") + "."));
            return out;
        }
        if (t.matches(".*\\b(spare (?:some )?(?:gp|money|coins|change|cash)|can i have (?:some )?(?:gp|money|coins|cash)|give me (?:some )?(?:gp|money|coins|cash)|need money|i'?m poor|im poor|loan me|lend me|any spare (?:gp|cash|coins)|free (?:gp|money|stuff|items))\\b.*")) {
            out.add(who.flavour(BotChat.pick("Earn it like the rest of us, " + name + ".", "I worked for mine. Cowhides are a good start.", "Sorry, " + name + ". Try fishing shrimp at Lumbridge.", "Nice try. No.")));
            return out;
        }
        if (t.matches(".*\\b(lag|laggy|lagging|is it lagging)\\b.*")) { out.add(who.flavour(BotChat.pick("Feels fine here.", "No lag on my end.", "Blame the goblins."))); return out; }
        if (t.matches(".*\\b(what world|which world|world are we)\\b.*")) { out.add(who.flavour("This one, same as you.")); return out; }
        if (t.matches(".*\\b(any news|what'?s new|whats new|what'?s happening|whats happening|what'?s going on|whats going on|gossip|heard anything)\\b.*")) {
            String n = BotChat.someNews();
            out.add(who.flavour(n != null ? BotChat.pick("Heard this: ", "Word is, ", "Apparently ") + Character.toLowerCase(n.charAt(0)) + n.substring(1) : BotChat.pick("Quiet day.", "Nothing much. Same old.")));
            return out;
        }
        if (t.matches(".*\\b(i'?m bored|im bored|bored|what should i do|what to do|nothing to do|any ideas)\\b.*")) {
            int cb = speaker.getProperties().getCurrentCombatLevel();
            out.add(who.flavour(cb < 20 ? BotChat.pick("Do Cook's Assistant. Then Sheep Shearer. Easy quest points.", "Kill some cows and sell the hides.", "Try the Stronghold of Security. Free gear at the end.")
                    : cb < 60 ? BotChat.pick("Hill giants in the Edgeville dungeon. Big bones and limpwurts.", "Try the Gnome agility course.", "Waterfall Quest. Great XP for its size.")
                    : BotChat.pick("Go for a fire cape. I'll wait.", "Barrows, if you've got the prayer.", "Slayer! Never boring.")));
            return out;
        }
        if (t.matches("^(?:good luck|gl|gl hf|glhf|best of luck)\\b.*")) { out.add(who.flavour(BotChat.pick("You too!", "Cheers, " + name + ".", "And to you."))); return out; }
        if (t.matches("^(?:sorry|my bad|oops|soz|sry|apologies)\\b.*")) { out.add(who.flavour(BotChat.pick("No harm done.", "Don't worry about it.", "All good."))); return out; }
        if (t.matches("^(?:brb|afk|one sec|1 sec|be right back|back soon)\\b.*") && addressed) { out.add(who.flavour(BotChat.pick("I'll be here. Probably.", "Take your time.", "Go on then."))); return out; }
        if (t.matches(".*\\b(first quest|easy quest|easiest quest|what quest should|which quest should|good quest|best quest)\\b.*")) {
            out.add(who.flavour(BotChat.pick("Cook's Assistant, Sheep Shearer and Rune Mysteries. All in Lumbridge.", "Waterfall Quest if you want XP. Cook's Assistant if you want easy.", "Rune Mysteries. It opens up Runecrafting.")));
            return out;
        }
        if (t.matches(".*\\b(how long (?:have you|you been|you) (?:play|playing|been here|been around)|how old are you|when did you start)\\b.*")) { out.add(who.flavour(BotChat.pick("Since before the Grand Exchange was built. Feels like it, anyway.", "Long enough to remember when lobsters were 300.", "Years. Don't ask."))); return out; }
        if (t.matches(".*\\b(are you (?:ok|alright|okay)|you good|are you hurt|you ok)\\b.*") && bot.getSkills().getLifepoints() < bot.getSkills().getMaximumLifepoints() / 2) { out.add(who.flavour(BotChat.pick("Bit bruised. I'll eat something.", "Seen better days. I'll live."))); return out; }
        return null;
    }

    static String capital(String s) { return BotChat.capital(s); }

    static final Map<String, Long> BLESSED = new HashMap<>(); // player -> last blessing (one per 30 min)

    /** Finds the player's gravestone and sends the bot to bless it (teleporting if far, as a player would): 15 minutes added. */
    @SuppressWarnings("unchecked")
    static String blessGrave(Player bot, Player speaker) {
        core.game.node.entity.combat.graves.Grave grave = null;
        try {
            java.lang.reflect.Field f = core.game.node.entity.combat.graves.GraveController.class.getDeclaredField("activeGraves");
            f.setAccessible(true);
            for (Object g : ((java.util.Map<Integer, Object>) f.get(null)).values()) {
                core.game.node.entity.combat.graves.Grave gr = (core.game.node.entity.combat.graves.Grave) g;
                if (speaker.getUsername().equalsIgnoreCase(gr.getOwnerUsername())) { grave = gr; break; }
            }
        } catch (Throwable t) { return "I can't see any grave of yours."; }
        if (grave == null) return BotChat.pick("You haven't got a grave up, " + speaker.getUsername() + ".", "No grave of yours anywhere. Lucky you.");
        long now = System.currentTimeMillis();
        if (now - BLESSED.getOrDefault(speaker.getUsername().toLowerCase(Locale.ROOT), 0L) < 30 * 60_000L) return "I've blessed it once already. Get a move on!";
        final core.game.node.entity.combat.graves.Grave gr = grave;
        final Location there = gr.getLocation();
        if (bot.getLocation().getDistance(there) > 20 && !Ledger.Wallet.payTeleport(bot)) return "It's too far and I'm out of runes, sorry.";
        BLESSED.put(speaker.getUsername().toLowerCase(Locale.ROOT), now);
        if (bot.getLocation().getDistance(there) > 20) { Location near = Surveyors.Surveyor.walkable(there.transform(1, 1, 0)); try { Spectacle.teleport(bot, near != null ? near : there); } catch (Throwable ignored) { } } // batch 15 item1
        else { try { ((core.game.bots.AIPlayer) bot).walkToPosSmart(there); } catch (Throwable ignored) { } }
        final Player b = bot;
        core.game.world.GameWorld.getPulser().submit(new core.game.system.task.Pulse(2) {
            int n, walks;
            @Override public boolean pulse() {
                try {
                    if (!b.isActive() || !gr.isActive()) return true; // logged out, or the stone crumbled first
                    // batch 15 item3: it blesses only once it is standing next to the stone (it used to animate wherever it was 5 ticks on)
                    boolean there2 = b.getLocation().getZ() == there.getZ() && b.getLocation().getDistance(there) <= 2;
                    if (!there2) {
                        if (++n > 40) { BLESSED.remove(speaker.getUsername().toLowerCase(Locale.ROOT)); BotChat.say(b, "Couldn't reach it, sorry."); System.out.println("[SmartAdv] " + b.getUsername() + " couldn't reach " + speaker.getUsername() + "'s grave at " + there + " (" + (int) b.getLocation().getDistance(there) + " tiles short)"); return true; }
                        if (!b.getWalkingQueue().isMoving() && walks++ < 6) { Location near = Surveyors.Surveyor.walkable(there.transform(1, 0, 0)); try { ((core.game.bots.AIPlayer) b).walkToPosSmart(near != null ? near : there); } catch (Throwable ignored) { } }
                        return false;
                    }
                    b.face(gr); b.getAnimator().animate(new core.game.world.update.flag.context.Animation(645));
                    gr.setTicksRemaining(Math.max(gr.getTicksRemaining(), 1500)); // fifteen minutes
                    BotChat.say(b, BotChat.pick("Blessed. You've got a quarter of an hour now, " + speaker.getUsername() + ".", "There. Fifteen minutes, go!", "Grave's blessed. Run."));
                    System.out.println("[SmartAdv] " + b.getUsername() + " blessed " + speaker.getUsername() + "'s grave at " + there + " from " + b.getLocation());
                } catch (Throwable t) { BotChat.say(b, "Couldn't reach it, sorry."); }
                return true;
            }
        });
        return BotChat.pick("On my way to it. Hold on.", "Right, grave duty. Give me a moment.", "I'll bless it. Don't die again.");
    }

    /** Trials only (-Dtradetalk.test=true): runs phrases through the chat brain and a haggle on a pretend callout. */
    static void selfTest() {
        Player a = null, b = null;
        for (SmartAdventurers.Brain br : SmartAdventurers.BRAINS.values()) { if (br.bot == null || !br.bot.isActive()) continue; if (a == null) a = br.bot; else if (b == null) { b = br.bot; break; } }
        if (a == null || b == null) { System.out.println("[TradeTest] need two smart bots"); return; }
        String[] ask = {"do you need coal?", "will you buy my lobsters", "what are you buying?", "how much would you pay for 20 sharks?", "would you pay 1k each for yew logs?",
                "do you have any iron ore?", "sell me your nature runes", "what are you selling", "need any bones?", "can i sell you some feathers", "got any lobbies?",
                "where can i mine coal?", "where can i fish lobsters", "best place to train fishing", "how do i train wc", "where are you going?", "what are you saving for?",
                "how rich are you", "what's your total level", "where are you from", "how do i make money", "wanna duel?", "nice gear", "spare some gp?", "any news?", "i'm bored",
                "what's your favourite skill", "doubling money at ge", "join my clan", "what quest should i do first"};
        for (String q : ask) {
            String t = q.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' &?!.]", " ").replaceAll("\\s+", " ").trim();
            boolean question = t.contains("?") || t.startsWith("how ") || t.startsWith("where ") || t.startsWith("what ");
            List<String> r = BotChat.Brain.answer(a, BotChat.Persona.of(a), b, q, true);
            System.out.println("[TradeTest] " + q + " -> " + r);
        }
        Deals.Offer o = new Deals.Offer(a, 453, 300, Math.max(1, MarketBrain.value(453) * 92 / 100));
        System.out.println("[TradeTest] callout: 300 coal at " + o.unit + " (guide " + MarketBrain.value(453) + ")");
        for (String q : new String[]{"how much for 50?", "can you do 50 each", "deal", "i'll take 40", "would you take 20k for the lot", "how about 25k", "meet me in the middle", "ok", "too expensive", "10% off?", "free?", "how much is a rune scimitar"}) {
            boolean used = Deals.haggle(o, b, q, false);
            long[] st = o.hag.get(b.getUsername().toLowerCase(Locale.ROOT));
            System.out.println("[TradeTest] callout '" + q + "' used=" + used + " state=" + (st == null ? "-" : java.util.Arrays.toString(st)) + " due=" + o.dueFrom(b) + " qty=" + o.qtyFor(b));
        }
    }

    /** "lobster" -> "cage", "shrimp" -> "net": fishing spots are known by their option. */
    static String fishOption(String what) {
        if (what.matches(".*(lobster|crayfish).*")) return "cage";
        if (what.matches(".*(shrimp|anchov|monk).*")) return "net";
        if (what.matches(".*(trout|salmon|pike|sardine|herring).*")) return what.matches(".*(trout|salmon).*") ? "lure" : "bait";
        if (what.matches(".*(tuna|swordfish|shark).*")) return "harpoon";
        return what;
    }
}
