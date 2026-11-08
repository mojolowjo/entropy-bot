package io.github.mojolowjo.entropybot.aa.moves;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * job-band2 (rule 7: every end has a reason; the job-band coder left the band leg with its path and no-path words only): the
 * words of a {@link Step.ToBand} end and the column it held. Reached: "reached y 16 (48 levels, 92 s, 41 blocks broken)".
 * Short: the depth reached and the cause read from the column ahead (the first water or lava cell, bedrock, a cell the cost
 * advisor forbids with its rule) and the fork's path end ({@link WalkEndText#reason}); every short end names its column
 * ("column x z"), so the band way's next price ({@link #heldColumn}) side-steps band.sideStepM away from it
 * ({@link #sideStep}). Pure (JUnit: JobBand2Test). Loader notes: none here; StepHands reads the world (vanilla
 * Level.getBlockState / getFluidState) and the mod's PolicyRuntime.verdict for the cells.
 */
public final class BandEnd {
    private BandEnd() {}

    /** One cell of the column ahead, as read: its Y, block id, fluid ("water", "lava" or null) and the advisor's deny (rule: reason) or null. */
    public record Cell(int y, String block, String fluid, String denied) {}

    /** The success words. */
    public static String reached(int fromY, int y, double seconds, int broken) {
        return String.format(Locale.ROOT, "reached y %d (%d levels, %.0f s, %d blocks broken)", y, Math.abs(fromY - y), seconds, broken);
    }

    /**
     * The first cause in the column ahead (top first, the cells under the feet down to the target): a fluid cell, bedrock, a
     * cell the cost advisor forbids. Null: none of these in the cells read.
     */
    public static String cause(int x, int z, List<Cell> ahead) {
        if (ahead == null) return null;
        for (Cell c : ahead) {
            String at = x + " " + c.y() + " " + z;
            if (c.fluid() != null) return c.fluid() + " in the column ahead at " + at;
            if ("minecraft:bedrock".equals(c.block())) return "bedrock at " + at;
            if (c.denied() != null) return "the cost advisor forbids breaking " + shortId(c.block()) + " at " + at + " (" + c.denied() + ")";
        }
        return null;
    }

    /**
     * A short end: "stopped at y 40 of 16 (8 of 32 levels, column x z): cause; fork: path end". {@code cause} null: "no water,
     * lava, bedrock or forbidden cell in the column ahead" is said (rule 7: never a bare stop).
     */
    public static String stopped(int x, int z, int fromY, int y, int targetY, String cause, String forkEnd) {
        int done = Math.max(0, fromY > targetY ? fromY - y : y - fromY), all = Math.abs(fromY - targetY);
        return "stopped at y " + y + " of " + targetY + " (" + done + " of " + all + " levels, column " + x + " " + z + "): "
                + (cause != null ? cause : "no water, lava, bedrock or forbidden cell in the column ahead")
                + "; the fork: " + (forkEnd == null || forkEnd.isBlank() ? "no path end read" : forkEnd);
    }

    private static final Pattern COLUMN = Pattern.compile("column (-?\\d+) (-?\\d+)");

    /** The column a held end names ({x, z}); null: none named (an end without a column: the peak is out, not the column). */
    public static int[] heldColumn(String why) {
        if (why == null) return null;
        Matcher m = COLUMN.matcher(why);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : null;
    }

    /**
     * The column to dig from when {@code held} ({x, z}) is held: {@code step} metres from the feet, away from the held column
     * along the axis it lies off most (+x when it is the feet's own). Null: the held column is farther than half a step from
     * the feet (the feet's column is not the held one: dig here).
     */
    public static int[] sideStep(int[] feet, int[] held, double step) {
        if (feet == null || held == null) return null;
        int dx = feet[0] - held[0], dz = feet[2] - held[1];
        if (Math.hypot(dx, dz) > step / 2) return null;
        int s = (int) Math.max(1, Math.round(step));
        if (Math.abs(dz) > Math.abs(dx)) return new int[]{feet[0], feet[2] + (dz > 0 ? s : -s)};
        return new int[]{feet[0] + (dx < 0 ? -s : s), feet[2]};
    }

    private static String shortId(String id) { return id == null ? "the block" : id.replace("minecraft:", ""); }
}
