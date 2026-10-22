package io.github.mojolowjo.entropybot.sense;

/**
 * AA_CLEANUP 3: the gear facts the old brain held (NightSafety's tiers, PlayRules' pickaxe materials, SupplyCheck's
 * stage tier), kept for the sense side and the shelter job. Pure; loader notes: none.
 */
public final class Gear {
    private Gear() {}

    /** Armour tier from a material word in the worn pieces' ids (the weakest piece counts; a missing piece = 0). */
    public static int armourTier(java.util.List<String> worn) {
        if (worn == null || worn.size() < 4) return 0;
        int min = 3;
        for (String id : worn) min = Math.min(min, tierOf(id));
        return min;
    }

    /** Material tier of an item id: wood 0; leather, stone, chainmail, copper, gold 1; iron 2; diamond, netherite 3. */
    public static int tierOf(String id) {
        String s = id == null ? "" : id.toLowerCase();
        if (s.contains("netherite") || s.contains("diamond")) return 3;
        if (s.contains("iron")) return 2;
        if (s.contains("leather") || s.contains("chainmail") || s.contains("copper") || s.contains("golden") || s.contains("stone")) return 1;
        return 0;
    }

    /** The pickaxe the stage can make: wood -> wooden, stone -> stone, iron and up -> iron; nothing -> null. */
    public static String stageTier(String stage) {
        return switch (stage == null ? "" : stage) {
            case "wood" -> "wooden";
            case "stone" -> "stone";
            case "iron", "diamond" -> "iron";
            default -> null;
        };
    }

    /**
     * R8: the pickaxe materials for one of this tier in the bag (bag counts by id without the namespace): 3 heads
     * (planks, cobblestone-like stone, iron_ingot, diamond) and 2 sticks or 1 plank/log to make them.
     */
    public static boolean pickMaterials(String tier, java.util.Map<String, Integer> bag) {
        if (tier == null || bag == null) return false;
        int planks = 0, logs = 0, stone = 0;
        for (var e : bag.entrySet()) {
            String id = e.getKey().substring(e.getKey().indexOf(':') + 1);
            int n = e.getValue() == null ? 0 : e.getValue();
            if (id.endsWith("_planks")) planks += n;
            if (id.endsWith("_log") || id.endsWith("_stem")) logs += n;
            if (id.equals("cobblestone") || id.equals("cobbled_deepslate") || id.equals("blackstone")) stone += n;
        }
        int sticks = bag.getOrDefault("stick", bag.getOrDefault("minecraft:stick", 0));
        boolean handle = sticks >= 2 || planks >= 2 || logs >= 1;
        return switch (tier) {
            case "wooden" -> planks + logs * 4 >= 5 || (planks >= 3 && sticks >= 2);
            case "stone" -> stone >= 3 && handle;
            case "iron" -> bag.getOrDefault("iron_ingot", bag.getOrDefault("minecraft:iron_ingot", 0)) >= 3 && handle;
            case "diamond" -> bag.getOrDefault("diamond", bag.getOrDefault("minecraft:diamond", 0)) >= 3 && handle;
            default -> false;
        };
    }
}
