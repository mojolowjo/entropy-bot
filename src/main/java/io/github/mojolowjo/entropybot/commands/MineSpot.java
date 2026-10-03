package io.github.mojolowjo.entropybot.commands;

/**
 * Package A (the overnight fixes, 2026-10-03): "mark mine" (and "mark &lt;name&gt; north") refuses a spot the bot
 * can't stand in or walk to, so a mine marked by coordinates in solid rock is caught when it is marked, not after
 * a night of "couldn't reach the mine". The same rule as the bridge's standCheck. Plain Java: JUnit tests it.
 */
public final class MineSpot {
    private MineSpot() {}

    /** One cell as the check sees it: its block name, and whether it is solid (a full collision box) or a liquid. */
    public record Cell(String name, boolean solid, boolean liquid) {}

    /** Null when the bot can stand at pos (feet and head free, something under it), else why not. */
    public static String standReason(Cell feet, Cell head, boolean floorSolid, String pos) {
        Cell[] cells = {feet, head};
        for (int y = 0; y < 2; y++) {
            if (cells[y].liquid() || cells[y].solid()) return "I can't stand at " + pos + " (" + cells[y].name() + (y == 1 ? " above it" : "") + ")";
        }
        if (!floorSolid) return "there is nothing to stand on at " + pos;
        return null;
    }

    /** The reply to a refused "mark": the reason, and what to do. */
    public static String refusal(String pos, String why) {
        return "error: I won't start a mine at " + pos + ": " + why + " - mark it where I can stand and walk to";
    }

    /** "block.minecraft.stone" -> "stone", "block.oritech.nickel_ore" -> "oritech:nickel_ore" (as the bridge's blockName). */
    public static String blockName(String descriptionId) {
        return String.valueOf(descriptionId).replaceFirst("^block\\.", "").replaceFirst("^minecraft\\.", "").replaceFirst("\\.", ":");
    }
}
