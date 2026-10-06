package io.github.mojolowjo.entropybot.camp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * C5 tool care: the pickaxe rule extended to sword, axe, shovel, hoe and shield. A kind needs a new one when every one
 * of it in the bag is below {@link #WORN} of its uses, or one broke (the kind was carried at the last look and is gone).
 * The replacement: the same item from the storage notes, else crafted from what the bag and storage hold (iron, then
 * stone, then wood; a shield: iron + planks). Pure; the game side (CampCommands.toolCareTick) runs it at a pause.
 *
 * <p>Loader notes: none (the durability comes from {@code ItemStack.getMaxDamage/getDamageValue}, vanilla).
 */
public final class ToolCareRules {
    private ToolCareRules() {}

    public static final double WORN = 0.10;
    public static final List<String> KINDS = List.of("sword", "axe", "shovel", "hoe", "shield");

    /** A damageable item in the bag. */
    public record Tool(String id, int left, int max) {
        boolean worn() { return max > 0 && left <= max * WORN; }
    }

    /** The tool kind of an id ("pickaxe" is not "axe"), or null. */
    public static String kind(String id) {
        String p = id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        if (p.equals("shield")) return "shield";
        if (p.endsWith("_pickaxe")) return "pickaxe";
        for (String k : KINDS) if (p.endsWith("_" + k)) return k;
        return null;
    }

    /** The kinds (of {@link #KINDS}) that need a new one: kind -> the id to copy (the worn or broken one's). */
    public static Map<String, String> needs(List<Tool> tools, Map<String, String> before) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Boolean> good = new LinkedHashMap<>();
        Map<String, String> worn = new LinkedHashMap<>();
        for (Tool t : tools) {
            String k = kind(t.id());
            if (k == null || !KINDS.contains(k)) continue;
            if (t.worn()) worn.putIfAbsent(k, t.id());
            else good.put(k, true);
        }
        for (Map.Entry<String, String> e : worn.entrySet()) if (!good.containsKey(e.getKey())) out.put(e.getKey(), e.getValue());
        if (before != null) for (Map.Entry<String, String> e : before.entrySet()) {
            if (!good.containsKey(e.getKey()) && !worn.containsKey(e.getKey())) out.put(e.getKey(), e.getValue());      // it broke
        }
        return out;
    }

    /** kind -> an id of it carried now (for the next look's "broke" check). */
    public static Map<String, String> carried(List<Tool> tools) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Tool t : tools) {
            String k = kind(t.id());
            if (k != null && KINDS.contains(k)) out.putIfAbsent(k, t.id());
        }
        return out;
    }

    /**
     * The command that replaces it, or null (nothing can). same: the worn one's id; stored: ids in the storage notes;
     * have: what the bag plus storage hold (id -> count).
     */
    public static String replacement(String kind, String same, Set<String> stored, Map<String, Integer> have) {
        if (same != null && stored.contains(same)) return "get " + shortId(same) + " 1";
        if (kind.equals("shield")) return have.getOrDefault("minecraft:iron_ingot", 0) >= 1 && planks(have) >= 6 ? "craft shield 1" : null;
        int head = switch (kind) {
            case "axe" -> 3;
            case "sword", "hoe" -> 2;
            default -> 1;
        };
        int sticks = kind.equals("sword") ? 1 : 2;
        boolean wood = planks(have) * 2 + have.getOrDefault("minecraft:stick", 0) >= sticks + 2;
        if (!wood) return null;
        if (have.getOrDefault("minecraft:iron_ingot", 0) >= head && tier(same) >= 3) return "craft iron_" + kind + " 1";
        int stone = have.getOrDefault("minecraft:cobblestone", 0) + have.getOrDefault("minecraft:cobbled_deepslate", 0);
        if (stone >= head) return "craft stone_" + kind + " 1";
        if (planks(have) >= head + 2) return "craft wooden_" + kind + " 1";
        return null;
    }

    static int planks(Map<String, Integer> have) {
        int n = 0;
        for (Map.Entry<String, Integer> e : have.entrySet()) {
            if (e.getKey().endsWith("_planks")) n += e.getValue();
            else if (e.getKey().endsWith("_log")) n += 4 * e.getValue();
        }
        return n;
    }

    static int tier(String id) {
        if (id == null) return 0;
        String p = id.substring(id.indexOf(':') + 1);
        return p.startsWith("iron_") ? 3 : p.startsWith("diamond_") ? 4 : p.startsWith("netherite_") ? 5 : p.startsWith("stone_") ? 2 : 1;
    }

    /** "tools" adds this: "care: sword 120/131, axe -" (each kind's best uses left / max). */
    public static String text(List<Tool> tools) {
        List<String> parts = new ArrayList<>();
        for (String k : KINDS) {
            Tool best = null;
            for (Tool t : tools) if (k.equals(kind(t.id())) && (best == null || t.left() > best.left())) best = t;
            parts.add(best == null ? k + " -" : shortId(best.id()) + " " + best.left() + "/" + best.max() + (best.worn() ? " (worn)" : ""));
        }
        return "tool care (below 10 % or broken: fetched from the base chests or crafted at the next pause): " + String.join(", ", parts);
    }

    static String shortId(String id) {
        return StockRules.shortId(id);
    }
}
