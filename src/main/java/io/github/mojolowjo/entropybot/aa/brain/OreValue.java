package io.github.mojolowjo.entropybot.aa.brain;

import java.util.Map;
import java.util.Set;

/**
 * small-3: what a mined ore block is worth to the bot, in seconds of its work, for the ore detour. Pure.
 * Value per drop = 60 s x the drop's tech-tree share (0..1), or the full 60 s when an open owner goal wants it
 * (the ore or its drop, and the bag is short of the goal). Times the mean drops per block. Loader notes: none.
 */
public final class OreValue {
    private OreValue() {}

    public static double dropS() { return Tuning.get("oreValue.dropS"); }

    /** Mean drops of one ore block (no Fortune). */
    public static double dropsPerBlock(String ore) {
        String o = family(ore);
        return switch (o) {
            case "copper" -> 3.5;
            case "lapis" -> 6.5;
            case "redstone" -> 4.5;
            default -> 1;
        };
    }

    /** "minecraft:deepslate_iron_ore" -> "iron". */
    static String family(String ore) {
        String p = ore.substring(ore.indexOf(':') + 1);
        if (p.startsWith("deepslate_")) p = p.substring(10);
        if (p.startsWith("nether_")) p = p.substring(7);
        return p.endsWith("_ore") ? p.substring(0, p.length() - 4) : p;  // structural: length of the "_ore" suffix
    }

    /** The item an ore block drops ("iron" -> raw_iron). */
    public static String dropOf(String ore) {
        String f = family(ore);
        return switch (f) {
            case "iron", "copper", "gold" -> "raw_" + f;
            case "lapis" -> "lapis_lazuli";
            case "quartz" -> "quartz";
            case "gold_nether" -> "gold_nugget";
            default -> f;
        };
    }

    /**
     * Seconds of value in one mined block of {@code ore}: drops x 60 s x max(tree share, 1 for an open goal).
     * @param goals the open owner goals as the set of ids they count (ores and drops, paths), with the wanted count
     */
    public static double perBlock(String ore, TechTree tree, Set<String> have, Map<String, Integer> goals, Map<String, Integer> bag) {
        String drop = dropOf(ore);
        double share = tree == null || tree.size() == 0 ? 0 : tree.norm(drop, have == null ? Set.of() : have);
        String o = ore.substring(ore.indexOf(':') + 1);
        for (Map.Entry<String, Integer> g : goals.entrySet())
            if ((g.getKey().equals(drop) || g.getKey().equals(o)) && bag.getOrDefault(drop, 0) < g.getValue()) share = 1;
        return dropS() * share * dropsPerBlock(ore);
    }

    /** Dig time per ore block with the right tool (s). */
    public static double digS() { return Tuning.get("oreValue.digS"); }
    public static final double WALK_BPS = 4.317; // game fact: vanilla walking speed 4.317 blocks per second

    /** The detour's cost in seconds: there and back across {@code off} blocks plus digging {@code blocks}. */
    public static double detourCostS(double off, int blocks) { return 2 * off / WALK_BPS + blocks * digS(); }

    /** Take the detour only when the vein's value beats its cost. */
    public static boolean worthIt(double perBlockS, int blocks, double off) {
        return perBlockS * blocks > detourCostS(off, blocks);
    }
}