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
    /** Material per piece (vanilla recipes). */
    public static final Map<String, Integer> COST = Map.of("helmet", 5, "chestplate", 8, "leggings", 7, "boots", 4);
    /** Best first: tier name -> its material id. */
    static final List<String[]> TIERS = List.of(
            new String[]{"diamond", "minecraft:diamond"},
            new String[]{"iron", "minecraft:iron_ingot"},
            new String[]{"leather", "minecraft:leather"});

    /** A worn piece: slot (helmet..boots), id, uses left, max (id null = the slot is empty). */
    public record Piece(String slot, String id, int left, int max) {
        public boolean empty() { return id == null; }
        public boolean worn() { return id != null && max > 0 && left <= max * ToolCareRules.WORN; }
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

    static int rank(String tier) {
        return switch (tier) {
            case "leather" -> 1;
            case "golden", "chainmail" -> 2;
            case "iron" -> 3;
            case "diamond" -> 4;
            case "netherite" -> 5;
            default -> 0;
        };
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
        int floor = worn == null ? 0 : rank(tier(worn));
        String best = null;
        for (String[] t : TIERS) {
            if (have.getOrDefault(t[1], 0) < COST.get(slot)) continue;
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
            if (p.worn()) return "my " + StockRules.shortId(p.id()) + " is at " + (100 * p.left() / Math.max(1, p.max())) + " %";
        }
        return null;
    }
}
