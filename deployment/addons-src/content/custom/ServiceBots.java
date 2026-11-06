package content.custom;

import core.game.bots.AIPlayer;
import core.game.bots.SkillingBotAssembler;
import core.game.node.entity.player.Player;
import core.game.node.item.Item;
import core.game.world.map.Location;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * the owner 2026-10-20: the travelling traders, town folk and herb gatherer keep their names, worn gear, bank and coins
 * over restarts like players (they used to come back as new people each boot, and their Exchange sales were then paid
 * to names that no longer existed). Stored in data/eco/servicebots.json by role ("trader:MINER@3285,3367", "local:VARROCK", "herbs").
 */
final class ServiceBots {
    static final Map<String, JSONObject> SAVED = new ConcurrentHashMap<>();
    static final Map<String, Player> LIVE = new ConcurrentHashMap<>();
    static volatile boolean loaded;
    /** audit 10-23 O-1: entries that failed to load, written back unchanged (unless that role was saved live this boot). */
    static final Map<String, Object> BAD = new ConcurrentHashMap<>();

    /** Is this a service bot's name (saved or live)? */
    static boolean knows(String lower) {
        for (Player p : LIVE.values()) if (p != null && p.getUsername().equalsIgnoreCase(lower)) return true;
        for (JSONObject j : SAVED.values()) if (j.get("name") != null && String.valueOf(j.get("name")).equalsIgnoreCase(lower)) return true;
        return false;
    }

    static File file() { return new File(core.ServerConstants.GRAND_EXCHANGE_DATA_PATH, "servicebots.json"); }

    static synchronized void load() {
        if (loaded) return;
        loaded = true;
        File f = file(); if (!f.exists()) return;
        JSONObject d;
        try (FileReader r = new FileReader(f)) { d = (JSONObject) new JSONParser().parse(r); }
        catch (Throwable t) { Ledger.quarantine(f, "ServiceBots", t, false, true); return; } // audit 10-23 O-1 (A-12): never saved over
        int bad = 0;
        for (Object k : d.keySet()) {
            Object v = d.get(k);
            if (v instanceof JSONObject && ((JSONObject) v).get("name") != null) SAVED.put(String.valueOf(k), (JSONObject) v);
            else { bad++; BAD.put(String.valueOf(k), v); System.out.println("[ServiceBots] entry " + k + " could not be read, kept as it is (audit 10-23 O-1)"); }
        }
        if (bad > 0) Ledger.quarantine(f, "ServiceBots", bad + " unreadable entries", false, false);
        System.out.println("[ServiceBots] " + SAVED.size() + " service bots remembered");
    }

    /** The bot for this role: the same one as last time if it can be recreated, otherwise a new one (float given by the caller). */
    static AIPlayer make(String role, Location at, SkillingBotAssembler.Wealth wealth, boolean[] returning) {
        load();
        JSONObject j = SAVED.remove(role);
        AIPlayer bot = j != null ? SmartAdventurers.namedBot(String.valueOf(j.get("name")), at) : null;
        if (bot == null) bot = new SkillingBotAssembler().produce(wealth, at);
        returning[0] = j != null;
        if (j != null) {
            try {
                if (!bot.getUsername().equalsIgnoreCase(String.valueOf(j.get("name")))) Ledger.rename(String.valueOf(j.get("name")), bot.getUsername());
                if (j.get("gear") instanceof JSONArray && bot.getUsername().equalsIgnoreCase(String.valueOf(j.get("name")))) // a recreated bot starts bare: its own gear back on
                    for (Object g : (JSONArray) j.get("gear")) SmartAdventurers.wear(bot, ((Number) g).intValue());
                bot.getInventory().clear();
                for (Item it : bot.getBank().toArray()) if (it != null) bot.getBank().remove(it);
                if (j.get("bank") instanceof JSONArray) for (Object o : (JSONArray) j.get("bank")) { JSONArray e = (JSONArray) o; bot.getBank().add(new Item(((Number) e.get(0)).intValue(), ((Number) e.get(1)).intValue())); }
                if (j.get("pack") instanceof JSONArray) for (Object o : (JSONArray) j.get("pack")) { JSONArray e = (JSONArray) o; Item it = new Item(((Number) e.get(0)).intValue(), ((Number) e.get(1)).intValue()); if (!bot.getInventory().add(it)) bot.getBank().add(it); } // audit 10-23 A-12
                Ledger.Wallet.collect(bot);
                bot.getEquipment().refresh();
            } catch (Throwable t) { System.out.println("[ServiceBots] could not restore " + role + ": " + t); }
        }
        LIVE.put(role, bot);
        return bot;
    }

    @SuppressWarnings("unchecked")
    static synchronized void saveAll() {
        JSONObject d = new JSONObject();
        for (Map.Entry<String, JSONObject> e : SAVED.entrySet()) d.put(e.getKey(), e.getValue()); // not spawned yet this boot
        for (Map.Entry<String, Player> e : LIVE.entrySet()) {
            Player b = e.getValue();
            if (b == null) continue;
            JSONObject j = new JSONObject();
            j.put("name", b.getUsername());
            JSONArray gear = new JSONArray(); for (Item it : b.getEquipment().toArray()) if (it != null && it.getAmount() == 1) gear.add(it.getId());
            JSONArray bank = new JSONArray(); for (Item it : b.getBank().toArray()) if (it != null) { JSONArray x = new JSONArray(); x.add(it.getId()); x.add(it.getAmount()); bank.add(x); }
            JSONArray pack = new JSONArray(); for (Item it : b.getInventory().toArray()) if (it != null) { JSONArray x = new JSONArray(); x.add(it.getId()); x.add(it.getAmount()); pack.add(x); } // audit 10-23 A-12: the pack was cleared on restore but never saved
            j.put("gear", gear); j.put("bank", bank); j.put("pack", pack);
            d.put(e.getKey(), j);
        }
        for (Map.Entry<String, Object> e : BAD.entrySet()) d.put(d.containsKey(e.getKey()) && !e.getKey().endsWith("#bad") ? e.getKey() + "#bad" : e.getKey(), e.getValue()); // audit 10-23 O-1: kept as it was
        if (d.isEmpty()) return;
        try { Ledger.writeAtomic(file(), d.toJSONString()); } catch (Throwable t) { System.out.println("[ServiceBots] could not save: " + t); }
    }
}
