package io.github.mojolowjo.entropybot.aa.brain;

/**
 * 0.39.z33 (owner 2026-10-23, S38): a tool's uses left from the item's own data, never its tooltip. The game way (1.21.1):
 * the MAX_DAMAGE and DAMAGE data components; uses left = max damage - damage (a fresh tool has damage 0: a new stone
 * pickaxe reads 131 though the game shows no bar yet). Three states (rule 15): a number; {@link #UNLIMITED} (no MAX_DAMAGE,
 * or 0: it does not wear); {@link #UNKNOWN} (a tool by the game's tags that carries no MAX_DAMAGE but a component of a mod:
 * worn by energy or charges the mod keeps - not "almost broken" and not "unlimited"). Pure.
 */
public final class ToolUses {
    private ToolUses() {}

    public static final int UNLIMITED = Integer.MAX_VALUE; // structural: no wear
    public static final int UNKNOWN = -1;                  // structural: the item's data does not say

    /**
     * @param maxDamage      the MAX_DAMAGE component (null: the stack has none)
     * @param damage         the DAMAGE component (0 when absent)
     * @param tool           the game's tags or the TOOL component call it a tool
     * @param modComponent   the stack carries a data component outside the minecraft namespace
     */
    public static int of(Integer maxDamage, int damage, boolean tool, boolean modComponent) {
        if (maxDamage != null && maxDamage > 0) return Math.max(0, maxDamage - Math.max(0, damage));
        return tool && modComponent ? UNKNOWN : UNLIMITED;
    }

    /** The words for a state: "131 uses left", "no durability", "durability unknown". */
    public static String text(int uses) {
        return uses == UNKNOWN ? "durability unknown" : uses == UNLIMITED ? "no durability" : uses + " uses left";
    }
}
