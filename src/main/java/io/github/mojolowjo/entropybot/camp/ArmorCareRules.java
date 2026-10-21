package io.github.mojolowjo.entropybot.camp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 0.23.6 armour care, the tool-care rule for the four armour slots: a worn piece (below {@link ToolCareRules#WORN} of its
 * uses) or a missing one (an empty slot) is replaced at the next pause: the same item from the storage notes, else the
 * best tier the bag plus storage can pay for in raw material (diamond, iron, leather; ingots/diamonds/leather only, so a
 * block or a higher tier is never taken apart), and never a tier below the worn piece's unless nothing else is
 * affordable. The new piece is worn at once ({@code then wear}; {@code wear} swaps a worn piece for a better one).
 * Pure; the game side is CampCommands.toolCareTick.
 *
 * <p>Loader notes: none (ItemStack.getMaxDamage/getDamageValue and the armour slots are vanilla).
 */
public final class ArmorCareRules {
    private ArmorCareRules() {}

    public static final List<String> SLOTS = List.of("helmet", "chestplate", "leggings", "boots");
    /** The armour pieces of the game (the trimmable-armour tag): [tier name, chestplate id], the chestplates only. */
    private static List<String> chestplates() {
        List<String> out = new ArrayList<>();
        for (String id : io.github.mojolowjo.entropybot.aa.brain.ItemWorth.withTag("trimmable_armor")) if ("chestplate".equals(slot(id))) out.add(id);
        return out;
    }

    /**
     * Best first: [tier name, its material id]. Read from the game: each tier's chestplate recipe names the material (its
     * first ingredient), the tiers go by the chestplate's defence then toughness. A tier whose chestplate has no crafting
     * recipe (chainmail, netherite) is no tier to craft.
     */
    static List<String[]> tiers() {
        List<String> chest = new ArrayList<>(chestplates());
        chest.sort((a, b) -> {
            var fa = io.github.mojolowjo.entropybot.aa.brain.ItemWorth.facts(a);
            var fb = io.github.mojolowjo.entropybot.aa.brain.ItemWorth.facts(b);
            int c = Double.compare(fb.defence(), fa.defence());
            return c != 0 ? c : Double.compare(fb.toughness(), fa.toughness());
        });
        List<String[]> out = new ArrayList<>();
        for (String id : chest) {
            String m = io.github.mojolowjo.entropybot.aa.brain.ItemWorth.firstIngredient(id);
            if (m != null) out.add(new String[]{tier(id), m});
        }
        return out;
    }

    /** The material one piece of the slot takes in the tier: the piece's recipe (a big number when the tier has none for the slot). */
    public static int cost(String slot, String tier) {
        for (String c : chestplates()) {
            if (!tier(c).equals(tier)) continue;
            String piece = c.substring(0, c.length() - "chestplate".length()) + slot;
            var r = io.github.mojolowjo.entropybot.aa.brain.ItemWorth.recipes(piece);
            if (!r.isEmpty()) return r.get(0).inputs().size();
        }
        return Integer.MAX_VALUE;
    }

    /** The material id of a tier word ("iron" -> the ingot), or null when the game has no such tier to craft. */
    public static String material(String tier) {
        for (String[] t : tiers()) if (t[0].equals(tier)) return t[1];
        return null;
    }

    /** A worn piece: slot (helmet..boots), id, uses left, max (id null = the slot is empty). */
    public record Piece(String slot, String id, int left, int max) {
        public boolean empty() { return id == null; }
        public boolean worn() { return id != null && max > 0 && left <= max * ToolCareRules.wornV(); }
    }

    /** The slot of an armour id, or null. */
    public static String slot(String id) {
        if (id == null) return null;
        String p = id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        for (String s : SLOTS) if (p.endsWith("_" + s)) return s;
        return null;
    }

    /** The tier name of an armour id ("iron"), "" when unknown. */
    public static String tier(String id) {
        if (id == null) return "";
        String p = id.substring(id.indexOf(':') + 1);
        int u = p.lastIndexOf('_');
        return u < 0 ? "" : p.substring(0, u);
    }

    /** A tier's rank: its chestplate's defence, then toughness (the game's own numbers); 0 for a tier the game doesn't know. */
    static double rank(String tier) {
        for (String c : chestplates()) {
            if (!tier(c).equals(tier)) continue;
            var f = io.github.mojolowjo.entropybot.aa.brain.ItemWorth.facts(c);
            return f.defence() + f.toughness() / 100.0;    // structural: toughness only breaks a tie in defence
        }
        return 0;
    }

    /**
     * The slots that need a piece: slot -> the worn id (or null for an empty slot). A worn piece counts only when the bag
     * holds no good piece for the slot (that one is put on by {@code wear} instead, see {@link #spare}).
     */
    public static Map<String, String> needs(List<Piece> worn, List<Piece> bag) {
        Map<String, String> out = new LinkedHashMap<>(), empty = new LinkedHashMap<>();
        for (String s : SLOTS) {
            Piece on = null;
            for (Piece p : worn) if (s.equals(p.slot())) on = p;
            if (on != null && !on.empty() && !on.worn()) continue;
            if (spare(s, bag) != null) continue;
            if (on == null || on.empty()) empty.put(s, null);
            else out.put(s, on.id());          // worn pieces first, then the empty slots
        }
        out.putAll(empty);
        return out;
    }

    /** 0.25.1 gear.ironFirst: the order iron goes into (the owner's play: chestplate, then leggings, then helmet and boots). */
    public static final List<String> IRON_ORDER = List.of("chestplate", "leggings", "helmet", "boots");

    /**
     * 0.25.1 gear.ironFirst: the needs in the iron order (worn pieces keep going first; within each group chestplate,
     * leggings, helmet, boots). Off: as given.
     */
    public static Map<String, String> ordered(Map<String, String> needs, boolean ironFirst) {
        if (!ironFirst) return needs;
        Map<String, String> out = new LinkedHashMap<>();
        for (boolean worn : new boolean[]{true, false})
            for (String s : IRON_ORDER) if (needs.containsKey(s) && (needs.get(s) != null) == worn) out.put(s, needs.get(s));
        return out;
    }

    /**
     * 0.25.1 gear.ironFirst: what a helmet or boots may spend. The iron a chestplate and leggings below iron tier (worn
     * or missing) still need is held back, so leather fills helmet and boots and the ingots go to the body first. Off,
     * or for the chestplate/leggings themselves: have as given.
     */
    public static Map<String, Integer> spendable(String slot, List<Piece> worn, Map<String, Integer> have, boolean ironFirst) {
        String iron = ironFirst ? material("iron") : null;          // the owner's word "iron" (gear.ironFirst), read from the game
        if (iron == null || slot.equals("chestplate") || slot.equals("leggings") || have == null) return have;
        int hold = 0;
        for (String body : List.of("chestplate", "leggings")) {
            Piece on = null;
            for (Piece p : worn) if (body.equals(p.slot())) on = p;
            if (on == null || on.empty() || on.worn() || rank(tier(on.id())) < rank("iron")) hold += Math.min(cost(body, "iron"), Integer.MAX_VALUE - hold);
        }
        if (hold == 0) return have;
        Map<String, Integer> out = new LinkedHashMap<>(have);
        out.put(iron, Math.max(0, have.getOrDefault(iron, 0) - hold));
        return out;
    }

    /** The "tools" line for the order. */
    public static String orderText(boolean ironFirst) {
        return ironFirst ? "gear order (gear.ironFirst on): iron to chestplate then leggings first; helmet and boots take leather while the body needs the iron"
                : "gear order (gear.ironFirst off): worn pieces first, then helmet, chestplate, leggings, boots";
    }

    /** A good (not worn) piece for the slot in the bag, or null. */
    public static String spare(String slot, List<Piece> bag) {
        for (Piece p : bag) if (slot.equals(slot(p.id())) && !p.worn()) return p.id();
        return null;
    }

    /**
     * The command that replaces it, or null. worn: the worn id (null: empty slot); stored: ids in the storage notes;
     * have: bag plus storage (id -> count). Ends with "then wear".
     */
    public static String replacement(String slot, String worn, Set<String> stored, Map<String, Integer> have) {
        if (worn != null && stored.contains(worn)) return "get " + StockRules.shortId(worn) + " 1 then wear";
        double floor = worn == null ? 0 : rank(tier(worn));
        String best = null;
        for (String[] t : tiers()) {
            if (have.getOrDefault(t[1], 0) < cost(slot, t[0])) continue;
            if (best == null) best = t[0];
            if (rank(t[0]) >= floor) { best = t[0]; break; }
        }
        if (best == null) {
            // any piece for the slot sitting in storage, the best tier first
            String pick = null;
            for (String id : stored) if (slot.equals(slot(id)) && (pick == null || rank(tier(id)) > rank(tier(pick)))) pick = id;
            return pick == null ? null : "get " + StockRules.shortId(pick) + " 1 then wear";
        }
        return "craft " + best + "_" + slot + " 1 then wear";
    }

    /**
     * aa-prereq (aa391: "craft leather_helmet" ended "needs a crafting table and there is none"): a craft with no table
     * within reach, none in the bag and under 4 planks (a log is 4) gets the table's wood as a prerequisite first
     * ("cut 4 then craft ..."); the craft verb then makes and places its own table. Anything else is unchanged.
     */
    public static String withTablePrereq(String cmd, boolean tableNear, Map<String, Integer> bag) {
        if (cmd == null || !cmd.startsWith("craft ") || tableNear) return cmd;
        if (bag.getOrDefault("crafting_table", 0) > 0) return cmd;
        int planks = 0;
        for (Map.Entry<String, Integer> e : bag.entrySet()) {
            String k = e.getKey();
            if (k.endsWith("_planks")) planks += e.getValue();
            else if (k.endsWith("_log") || k.endsWith("_stem") || k.endsWith("_wood") || k.endsWith("_hyphae")) planks += 4 * e.getValue();  // game fact: a log makes 4 planks
        }
        return planks >= 4 ? cmd : "cut 4 then " + cmd;  // game fact: a crafting table takes 4 planks
    }

    /** The "tools" line: "armour: iron_helmet 150/165, chestplate -, ..." */
    public static String text(List<Piece> worn) {
        List<String> parts = new ArrayList<>();
        for (String s : SLOTS) {
            Piece on = null;
            for (Piece p : worn) if (s.equals(p.slot())) on = p;
            parts.add(on == null || on.empty() ? s + " -" : StockRules.shortId(on.id()) + " " + on.left() + "/" + on.max() + (on.worn() ? " (worn)" : ""));
        }
        return "armour care (below 10 % or missing: fetched or crafted at the next pause, then worn): " + String.join(", ", parts);
    }

    /** For the brain's gear need: the first worn or missing slot ("my iron_chestplate is at 6 %", "no boots"), or null. */
    public static String lowest(List<Piece> worn) {
        for (Piece p : worn) {
            if (p.empty()) continue;
            if (p.worn()) return "my " + StockRules.shortId(p.id()) + " is at " + (100 * p.left() / Math.max(1, p.max())) + " %";  // structural: percent
        }
        return null;
    }
}
