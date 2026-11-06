package content.custom;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a price out of anything a 2009 player might type at a bot (2026-10-21: "Expand the Smart Bot detections
 * like 'Can you do #' but a lot of different ways"). One reader for every bot that haggles: travelling traders, bank
 * callouts (Deals) and bots buying from players (PlayerTrades).
 *
 * Understands, among others: "can you do 500", "would u take 1.5k", "how about 2k?", "i'll give you 300", "best i can do
 * is 450", "80 each", "80 ea", "80gp a piece", "5k for the lot", "10 for 500", "i'll take 10", "x10", "knock 500 off",
 * "10% off", "half price", "meet me in the middle", "split the difference", "a grand", "half a mil", "five hundred",
 * "1,200", "2m", "deal", "ok", "nah". Numbers that are levels, times or skills ("99 fishing", "5 mins", "lvl 40") are
 * ignored.
 */
final class Haggle {
    private Haggle() { }

    static final class Bid {
        long amount = -1;   // gp named, -1 if none
        boolean each;       // the amount is per item
        boolean lot;        // the amount is for everything
        int qty;            // how many they want, 0 if not said
        long off = -1;      // "knock 500 off"
        int pct = -1;       // "10% off", "half price" = 50
        int pctMore = -1;   // "10% more" (a player selling to a bot)
        boolean middle, free, cued, agree, refuse, buying, cheaper, asks; // asks: a price question ("how much for 50")
        String text = "";

        boolean hasPrice() { return amount >= 0 || off >= 0 || pct >= 0 || pctMore >= 0 || middle || free; }

        /** Anything here worth a haggler's attention. */
        boolean any() { return hasPrice() || qty > 0 || agree || refuse || cheaper; }

        /**
         * What the speaker proposes in total for {@code n} items, where the asking total for those n is {@code ask}.
         * {@code prev} is their previous proposal (for "meet in the middle"), -1 if none. {@code guessEach}: a small
         * bare number for many items means per item ("can you do 80" for 300 coal at 92 each).
         */
        long total(long ask, int n, long prev, boolean guessEach) {
            if (free) return 0;
            if (middle) return prev >= 0 ? (ask + prev) / 2 : ask * 9 / 10;
            if (amount >= 0) {
                boolean per = each;
                if (!per && !lot && guessEach && n > 1 && ask > 0 && amount * 10 < ask * 16 / Math.max(1, n) && amount * n >= ask / 3) per = true;
                return per ? amount * Math.max(1, n) : amount;
            }
            if (off >= 0) return Math.max(0, ask - off);
            if (pct >= 0) return ask * (100 - Math.min(100, pct)) / 100;
            if (pctMore >= 0) return ask * (100 + Math.min(100, pctMore)) / 100;
            return -1;
        }

        @Override public String toString() {
            return "Bid{amount=" + amount + (each ? " each" : "") + (lot ? " lot" : "") + ", qty=" + qty + ", off=" + off + ", pct=" + pct + ", more=" + pctMore
                    + (middle ? ", middle" : "") + (free ? ", free" : "") + (cued ? ", cued" : "") + (agree ? ", agree" : "") + (refuse ? ", refuse" : "")
                    + (buying ? ", buying" : "") + (cheaper ? ", cheaper" : "") + (asks ? ", asks" : "") + "}";
        }
    }

    // ------------------------------------------------------------ patterns

    static final Pattern NUM = Pattern.compile("(?<![a-wyz0-9.,])(\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)\\s*(k|m|b|mil|mill|million|bil|billion|grand|thousand|hundred|gp|g|coins?|%|percent|x)?(?![a-z0-9])");
    static final Pattern AFTER_SKIP = Pattern.compile("^\\s*(?:lvl|level|lv|cb|combat|mins?|minutes?|secs?|seconds?|hours?|hrs?|days?|weeks?|ago|years?|yrs?|kc|kills?|times|xp|exp|att|attack|str|strength|def|defence|defense|hp|hitpoints|range|ranged|ranging|mage|magic|pray|prayer|fishing|fish|mining|mine|woodcutting|wc|cooking|cook|smithing|smith|crafting|craft|fletching|fletch|firemaking|fm|agility|agil|thieving|thieve|herblore|herb|runecrafting|runecraft|rc|slayer|farming|construction|con|hunter|summoning|total|th|st|nd|rd|am|pm|ish|player|players|people|ppl|tiles?|steps?|laps?|world|worlds|quests?|qp)\\b");
    static final Pattern BEFORE_SKIP = Pattern.compile("(?:\\blevel|\\blvl|\\blv|\\bworld|\\bw|\\bcombat|\\bcb|\\btotal|\\bwave|\\bfloor|\\bat lvl|\\bat level|\\bage|\\bpage|\\bstep|\\bwith|\\btimes|\\bon|\\bplus|\\+|-)\\s*$");
    static final Pattern AFTER_EACH = Pattern.compile("^\\s*(?:gp|coins?|gold|g)?\\s*(?:each|ea|apiece|a piece|a pop|a go|a unit|per (?:one|piece|item|unit|each|pop|thing)|per(?![a-z])|/\\s*(?:ea|each|per)|for one|for each|1 each)");
    static final Pattern AFTER_MONEY = Pattern.compile("^\\s*(?:gp|coins?|gold|cash|g)\\b");
    static final Pattern AFTER_QTY = Pattern.compile("^\\s*(?:x\\b|of (?:them|those|these|em|'em|it|ur|your|the|yours)|pcs|pieces|items|units|lots? of|please|pls|plz)");
    static final Pattern AFTER_OFF = Pattern.compile("^\\s*(?:gp\\s*|coins\\s*)?(?:off|less|cheaper|lower|discount|knocked off)\\b");
    static final Pattern AFTER_MORE = Pattern.compile("^\\s*(?:gp\\s*|coins\\s*)?(?:more|extra|over|higher|on top|up)\\b");
    static final Pattern BEFORE_MONEY = Pattern.compile("(?:\\bdo|\\btake|\\bpay|\\bgive|\\boffer|\\bfor|\\bat|\\babout|\\bmake it|\\bto|\\bis|\\baccept|\\bsettle for|\\bgo|\\bcounter|\\bonly|\\bgot|\\bhave|\\bspare|\\bdeal|\\bprice|\\bhow about|\\bwhat about|\\bhows|\\bhow's|\\bwould|\\bjust|\\bsay|\\bcall it|\\bmeet at|\\blike|\\bmaybe|\\bat most|\\bmax|\\btops)\\s*$");
    static final Pattern BEFORE_QTY = Pattern.compile("(?:\\bx|\\b(?:i'?ll|i will|i'?d|can i|could i|may i|let me|lemme|gimme|give me|sell me|i want|i need|i'?d like|i wanna|i'?ll buy|i'?ll have|i'?ll grab|i'?ll get) (?:take |buy |get |have |grab |like |purchase |want )?(?:like |maybe |just |about |only |a |the )?)\\s*$");
    static final Pattern CUE = Pattern.compile("\\b(can (?:you|u|ya|yu) do|could (?:you|u|ya) do|would (?:you|u|ya) do|will (?:you|u|ya) do|do (?:it |them |em |that |those )?for|would (?:you|u|ya) take|will (?:you|u|ya) take|could (?:you|u|ya) take|wud u take|wanna take|take|accept|settle(?: for)?|how about|what about|hows|how's|how does|sounds? good|work for (?:you|u)|works for (?:you|u)|i'?ll (?:give|pay|do|offer|go)|i (?:can|could|would|will|might) (?:give|pay|do|offer|go)|i'?d (?:give|pay|do|go|offer)|best i can do|most i can do|max i can do|my (?:best |final |last |top )?offer|offer|counter|i (?:only )?(?:have|got)|all i (?:have|got)|i'?ve (?:only )?got|meet|make it|round (?:it|that)|lower|drop|knock|down to|go down|come down|for the lot|for all|for everything|the lot|all in|for em|for them|for those|for it|deal at|each|ea|per|apiece|a piece|deal for|price|bid|tops|at most|max|budget|willing to pay|prepared to pay|pay)\\b");
    static final Pattern LOT = Pattern.compile("\\b(for the lot|for all|for everything|for the bunch|the lot|all of it|all of them|all of em|whole lot|whole thing|the whole|all in|for em all|in total|total|for the stack|for the pile|for the bundle|the bundle|altogether|all together|for the set)\\b");
    static final Pattern MIDDLE = Pattern.compile("\\b(meet (?:me |you |u )?(?:in the |at the |half ?way|in between|middle)|split the difference|split it|middle ground|halfway|half way|meet in the middle|meet halfway|somewhere in between|in between)\\b");
    static final Pattern HALF = Pattern.compile("\\b(half price|half off|half the price|50/50|half of that|for half)\\b");
    static final Pattern FREE = Pattern.compile("\\b(for free|free|for nothing|freebie|for nada|for zero|gratis|on the house|for 0)\\b");
    static final Pattern AGREE = Pattern.compile("^(?:ok|okay|okey|k|kk|deal|done|sure|fine|alright|aight|ight|yes|yep|yeah|ya|yea|ye|yup|go on|go on then|sounds good|sold|agreed|you got a deal|u got a deal|youve got a deal|you've got a deal|i'?ll take it|i'?ll take that|ill take it|take it|that works|works for me|perfect|bet|accept|i accept|lets do it|let's do it|lets go|let's go|cool|nice|great|good|that'?s fair|fair|fair enough|happy with that|i'?m in|im in|shake on it|handshake|gotcha|ok deal|ok then|alright then|fine then|deal then)(?:[ !.]+(?:then|mate|bro|man|dude|friend|pal|lol|haha|ty|thanks|deal|sold|cheers))*[ !.]*$");
    static final Pattern REFUSE = Pattern.compile("^(?:no|nah|nope|no thanks|no ty|no thank you|nty|pass|i'?ll pass|not interested|no way|nevermind|never mind|nvm|forget it|not today|no deal|not for me|meh|nah mate|nah ty|im good|i'?m good|i'?m ok|im ok|no need)\\b");
    static final Pattern BUYING = Pattern.compile("\\b(i'?ll take|i will take|can i (?:get|buy|have|grab|take)|could i (?:get|buy|have|grab|take)|may i (?:get|buy|have)|let me (?:get|buy|have|grab)|lemme (?:get|buy|have|grab)|sell me|i want|i'?d like|i need|gimme|give me|i'?ll buy|i'?ll have|i'?ll grab|i wanna buy|i want to buy|want to buy|wanna buy|buying|i'?m buying|im buying|just (?:get|want|need))\\b");
    static final Pattern CHEAPER = Pattern.compile("\\b(too expensive|too much|expensive|pricey|steep|cheaper|discount|lower|bit much|a lot|rip ?off|scam|daylight robbery|robbery|highway robbery|overpriced|over priced|best price|lowest|go lower|any lower|come down|wiggle room|negotiable|haggle|bargain|mates rates|mate's rates|friends price|friend price|deal\\?)\\b");

    static final Pattern ASKS = Pattern.compile("\\b(how much (?:for|is|are|would|do|does|will|to|if)|how much'?s|what(?:'s| is| would be| will be|s) (?:the )?(?:price|cost|damage) (?:for|of|on)|price (?:for|of|on)|what(?:'s| is|s) (?:it|that|they|those) (?:for|cost)|cost (?:for|of)|what do i owe|what would .* cost|what'?ll .* cost|price check|pc on|how much would)\\b");

    static final String[][] WORDS = {
            {"half a mil(?:lion)?", "500k"}, {"half a k", "500"}, {"half a grand", "500"}, {"a quarter mil(?:lion)?", "250k"},
            {"(?:a|one) mil(?:lion|l)?\\b", "1m"}, {"(?:a|one) grand\\b", "1000"}, {"(?:a|one) hundred\\b", "100"}, {"(?:a|one) k\\b", "1k"}, {"(?:a|one) thousand\\b", "1000"},
            {"\\bcouple (?:of )?grand\\b", "2000"}, {"\\bcouple (?:of )?k\\b", "2k"}, {"\\bcouple hundred\\b", "200"}, {"\\bfew grand\\b", "3000"}, {"\\bfew hundred\\b", "300"}, {"\\bfew k\\b", "3k"},
    };
    static final String[] SMALL = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty"};
    static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    /** Word numbers to digits: "five hundred" -> "5 hundred", "twenty k" -> "20 k". Only before a size word, or for small counts. */
    static String digits(String t) {
        for (String[] w : WORDS) t = t.replaceAll("\\b" + w[0], w[1]);
        for (int i = 9; i >= 2; i--) for (int j = 9; j >= 1; j--) t = t.replaceAll("\\b" + TENS[i] + "[ -]" + SMALL[j] + "\\b", String.valueOf(i * 10 + j));
        for (int i = 9; i >= 2; i--) t = t.replaceAll("\\b" + TENS[i] + "\\b", String.valueOf(i * 10));
        for (int i = SMALL.length - 1; i >= 1; i--) t = t.replaceAll("\\b" + SMALL[i] + "\\b", String.valueOf(i));
        return t.replaceAll("(\\d+) (hundred|thousand|grand|k|mil|million|m)\\b", "$1$2");
    }

    static final class Tok {
        long v; boolean money, each, qty, off, pct, more, beforeMoney, lot; int at;
    }

    /** {@code lotQty}: how many items are on offer (0 if unknown), to tell "10 for 500" (ten of them) from "500" (gp). */
    static Bid read(String raw, int lotQty) {
        Bid b = new Bid();
        if (raw == null) return b;
        String t = raw.toLowerCase(Locale.ROOT).replace(' ', ' ').replaceAll("[^a-z0-9.,%/+' ?!-]", " ").replaceAll("\\s+", " ").trim();
        t = t.replaceAll("\\b(u|ya|yu)\\b", "you").replaceAll("\\bim\\b", "i'm").replaceAll("\\bill\\b", "i'll").replaceAll("\\bid\\b", "i'd").replaceAll("\\bive\\b", "i've").replaceAll("\\bur\\b", "your");
        t = digits(t);
        b.text = t;
        boolean anyNum = t.matches(".*\\d.*");
        if (!anyNum && AGREE.matcher(t).find()) b.agree = true;
        if (!anyNum && REFUSE.matcher(t).find()) b.refuse = true;
        if (MIDDLE.matcher(t).find()) { b.middle = true; b.cued = true; }
        if (HALF.matcher(t).find()) { b.pct = 50; b.cued = true; }
        if (FREE.matcher(t).find() && !t.matches(".*\\bfree (?:to play|trade|time|world|stuff)\\b.*")) { b.free = true; b.cued = true; }
        if (CUE.matcher(t).find()) b.cued = true;
        if (BUYING.matcher(t).find()) b.buying = true;
        if (CHEAPER.matcher(t).find()) b.cheaper = true;
        if (ASKS.matcher(t).find()) b.asks = true;
        b.lot = LOT.matcher(t).find();
        boolean eachWord = t.matches(".*\\b(each|ea|apiece|a piece|per)\\b.*");

        List<Tok> toks = new ArrayList<>();
        Matcher m = NUM.matcher(t);
        while (m.find()) {
            String before = t.substring(Math.max(0, m.start() - 22), m.start()), after = t.substring(m.end(), Math.min(t.length(), m.end() + 22));
            String suf = m.group(2) == null ? "" : m.group(2);
            if (BEFORE_SKIP.matcher(before).find() && suf.isEmpty()) continue;
            if (AFTER_SKIP.matcher(after).find()) continue;
            double v;
            try { v = Double.parseDouble(m.group(1).replace(",", "")); } catch (NumberFormatException e) { continue; }
            Tok k = new Tok(); k.at = m.start();
            switch (suf) {
                case "k": case "grand": case "thousand": v *= 1_000; k.money = true; break;
                case "m": case "mil": case "mill": case "million": v *= 1_000_000; k.money = true; break;
                case "b": case "bil": case "billion": v *= 1_000_000_000; k.money = true; break;
                case "hundred": v *= 100; break;
                case "gp": case "g": case "coin": case "coins": k.money = true; break;
                case "%": case "percent": k.pct = true; break;
                case "x": k.qty = true; break;
                default: break;
            }
            if (before.matches(".*\\bx\\s*$")) k.qty = true;
            if (v < 0 || v > 2_000_000_000d) continue;
            k.v = Math.round(v);
            if (!k.pct && !k.qty) {
                if (AFTER_EACH.matcher(after).find()) { k.each = true; k.money = true; }
                else if (AFTER_MONEY.matcher(after).find()) k.money = true;
                if (AFTER_QTY.matcher(after).find() && !k.money) k.qty = true;
                if (BEFORE_QTY.matcher(before).find() && !k.money && !k.each) k.qty = true;
                if (BEFORE_MONEY.matcher(before).find()) k.beforeMoney = true;
                if (AFTER_OFF.matcher(after).find()) k.off = true;
                else if (AFTER_MORE.matcher(after).find() && k.money) k.more = true;
                if (after.matches("^\\s*(?:gp\\s*)?(?:for the lot|for all|for everything|the lot|for em|for them|all in|total|in total).*")) k.lot = true;
            } else if (k.pct) {
                if (AFTER_OFF.matcher(after).find() || t.matches(".*\\b(off|less|discount|cheaper|lower)\\b.*")) k.off = true;
                else if (AFTER_MORE.matcher(after).find() || t.matches(".*\\b(more|extra|over|higher|on top)\\b.*")) k.more = true;
            }
            toks.add(k);
        }
        if (toks.isEmpty()) {
            if (!anyNum && b.buying && t.matches(".*\\b(all of (?:it|them|em|those)|the lot|everything|the whole lot|whole lot|all)\\b.*")) b.qty = lotQty;
            return b;
        }
        // Percentages and "off".
        List<Tok> rest = new ArrayList<>();
        for (Tok k : toks) {
            if (k.pct) { if (k.off) { b.pct = (int) Math.min(100, k.v); b.cued = true; } else if (k.more) { b.pctMore = (int) Math.min(100, k.v); b.cued = true; } continue; }
            if (k.off) { b.off = k.v; b.cued = true; continue; }
            if (k.more) { b.pctMore = -1; b.amount = -1; } // "500 more": treat as asking for that much on top
            rest.add(k);
        }
        Tok money = null, qty = null;
        for (Tok k : rest) if (k.money && money == null) money = k;
        for (Tok k : rest) if (k.qty && k != money && qty == null) qty = k;
        List<Tok> bare = new ArrayList<>();
        for (Tok k : rest) if (k != money && k != qty) bare.add(k);
        int cap = lotQty > 0 ? lotQty : 1_000_000;
        if (b.asks && money == null && qty == null && bare.size() == 1 && bare.get(0).v <= cap) { qty = bare.get(0); bare.clear(); } // "how much for 50": fifty of them
        if (money == null && !bare.isEmpty()) {
            if (bare.size() >= 2) {
                Tok a = bare.get(0), c = bare.get(1);
                Tok small = a.v <= c.v ? a : c, big = small == a ? c : a;
                if (qty == null && small.v <= cap && small.v < big.v) { qty = small; money = big; }
                else money = a;
            } else {
                Tok only = bare.get(0);
                boolean looksQty = b.buying && qty == null && !only.beforeMoney && only.v <= cap && (lotQty > 0 || only.v <= 1000);
                if (looksQty) qty = only;
                else if (only.beforeMoney && b.buying && qty == null && lotQty > 0 && only.v <= lotQty && t.matches(".*\\b(?:i'?ll take|can i (?:get|have|buy|take)|let me (?:get|have)|sell me|i want|i need|gimme|give me)\\s+(?:like |maybe |just |about |only )?" + only.v + "\\b.*")) qty = only;
                else money = only;
            }
        } else if (money != null && qty == null && !bare.isEmpty() && bare.get(0).v <= cap && (bare.get(0).v < money.v || money.each)) qty = bare.get(0);
        if (money != null) {
            b.amount = money.v;
            b.each = money.each || (eachWord && !b.lot && !money.lot && rest.size() <= 2);
            if (money.lot) b.lot = true;
            if (money.money || money.beforeMoney || money.each) b.cued = true;
        }
        if (qty != null && qty.v > 0 && qty.v <= 1_000_000) b.qty = (int) qty.v;
        if (rest.size() == 1 && t.matches("^[^0-9]{0,8}\\d[\\d.,]*\\s*(?:k|m|gp|g|coins|grand|hundred|thousand|mil)?\\s*(?:each|ea)?\\s*[?!.]*$")) b.cued = true; // a bare number on its own
        if (b.amount >= 0 && b.qty > 0) b.cued = true; // "500 for 10 of them"
        if (b.amount == 0) b.free = true;
        return b;
    }

    static String gp(long n) { return String.format("%,d", n); }

    /** A round number near {@code v}, the way people counter: 1,234 -> 1,230; 56,780 -> 56,800. */
    static long round(long v) {
        if (v < 100) return v;
        long step = v < 1_000 ? 5 : v < 10_000 ? 10 : v < 100_000 ? 100 : v < 1_000_000 ? 1_000 : 10_000;
        return Math.max(step, (v + step / 2) / step * step);
    }

    // ------------------------------------------------------------ self test (javac Haggle.java; java -cp . content.custom.Haggle)

    public static void main(String[] a) {
        String[][] cases = {
                {"can you do 500", "300"}, {"would u take 1.5k?", "300"}, {"how about 2k", "0"}, {"i'll give you 300", "0"}, {"80 each", "300"}, {"80 ea", "300"},
                {"80gp a piece", "300"}, {"5k for the lot", "300"}, {"10 for 500", "300"}, {"i'll take 10", "300"}, {"can i get 25 please", "300"}, {"x10", "300"},
                {"knock 500 off", "0"}, {"10% off?", "0"}, {"half price", "0"}, {"meet me in the middle", "0"}, {"split the difference?", "0"},
                {"a grand?", "0"}, {"half a mil", "0"}, {"five hundred", "0"}, {"1,200", "0"}, {"2m", "0"}, {"deal", "0"}, {"ok then", "0"}, {"nah", "0"},
                {"i'm level 99 fishing", "0"}, {"brb 5 mins", "0"}, {"im 40 combat", "0"}, {"best i can do is 450", "0"}, {"500 for 10 of them", "300"},
                {"would you take 20k for all 300", "300"}, {"how much for 50", "300"}, {"too expensive", "0"}, {"can you do 92 each for 100", "300"},
                {"wanna sell me 5 for 400 each", "300"}, {"i only have 700", "0"}, {"twenty k?", "0"}, {"150 each and i'll take the lot", "300"},
                {"i'd pay 120 each", "0"}, {"give me 5k for them", "0"}, {"12k", "0"}, {"free?", "0"}, {"can i have them for free", "0"}, {"i want 20% more", "0"}, {"whats the price for 40", "300"}, {"how much is it", "0"}, {"price for the lot?", "300"},
        };
        for (String[] c : cases) System.out.println(String.format("%-40s -> %s", c[0], read(c[0], Integer.parseInt(c[1]))));
    }
}
