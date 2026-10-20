package io.github.mojolowjo.entropybot.aa;

import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.36.a6 (aa371 14:45-14:53: IRON's walk to the camp ended "stuck under ground ... the answer comes as a whisper", the
 * task failed, a climb rung failed the same way, cooldowns, minutes lost): under AA a walk that ends stuck under ground
 * is not a failure. AA picks the way out by the situation, logs one line, gets out and goes on with the task's step.
 *
 * <ul>
 * <li>PILLAR: the column straight above the head is open up to the surface (a shaft dug straight down) and the bag has
 *     enough throwaway blocks: walk to the cell above the shaft with placing on (Baritone pillars: jump and place).</li>
 * <li>CLIMB: a pickaxe: the existing {@code climb} (a 1-wide staircase dug up, 3-high headroom).</li>
 * <li>NONE: neither; the trail back was already walked by the walk itself, so the task fails with the reason.</li>
 * </ul>
 * Pure (JUnit: AaQuickTest). Loader notes: none (the world reads are in AaRuntime).
 */
public final class AaEscape {
    private AaEscape() {}

    public enum Way { OUT, PILLAR, CLIMB, NONE }

    public record Choice(Way way, String why) {}

    /** At most this many escapes per task (a way out that keeps ending stuck fails the task as before). */
    public static final int MAX_PER_TASK = 2;

    /** Baritone's default acceptableThrowawayItems that a bag has (dirt, cobblestone, netherrack, stone). */
    public static final List<String> THROWAWAY = List.of("cobblestone", "dirt", "netherrack", "stone");

    private static final Pattern STEP = Pattern.compile("^stopped at step (\\d+) \\(");
    private static final Pattern NO_PATH = Pattern.compile("no path to (-?\\d+) (-?\\d+) (-?\\d+)");

    /** How far up the hole scan looks for a way out sideways. */
    public static final int HOLE_MAX_UP = 48;

    public static boolean stuck(String chainEnd) {
        return chainEnd != null && chainEnd.contains("stuck under ground");
    }

    /** A walk that did not get there: no path, stuck, or the under-ground end. */
    public static boolean walkFailed(String chainEnd) {
        return chainEnd != null && (stuck(chainEnd) || chainEnd.contains("no path") || chainEnd.contains("stuck at"));
    }

    /** "... no path to x y z ..." -> the destination; else null. */
    public static int[] destOf(String chainEnd) {
        if (chainEnd == null) return null;
        Matcher m = NO_PATH.matcher(chainEnd);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))} : null;
    }

    /**
     * aa372 gap (the shared under-ground rule calls a 1x1 shaft open to the sky "not under ground", so the way out never
     * fired at its bottom): the height of the hole the bot stands in. Scanning up from the feet, {@code dy} is the first
     * level where it could step out sideways ({@code stepOut}: a side cell with room for feet and head); the column above
     * the head must be open all the way ({@code columnOpen(dy)}: the cell dy above the feet). 0 = it can step out here
     * (no hole); -1 = no way out within {@code maxUp} (a roof, or walls all the way up).
     */
    public static int holeExit(int maxUp, IntPredicate columnOpen, IntPredicate stepOut) {
        for (int dy = 0; dy <= maxUp; dy++) {
            if (stepOut.test(dy)) return dy;
            if (!columnOpen.test(dy + 2)) return -1;        // the cell above the head at this level: a roof
        }
        return -1;
    }

    /**
     * The trigger (owner, aa372): the way out fires on "the walk failed and the goal is above me / I'm in a hole I dug",
     * not on "under a roof". {@code exitUp} from {@link #holeExit} (> 0: there is a hole with a way out above);
     * {@code inDugCell}: the feet stand in a cell the bot broke itself (the policy's dug set).
     */
    public static boolean holeStuck(String chainEnd, int meY, int[] dest, boolean inDugCell, int exitUp) {
        if (!walkFailed(chainEnd) || exitUp <= 0) return false;
        return inDugCell || (dest != null && dest[1] > meY + 1);
    }

    /** "stopped at step 5 (goto ...): ..." -> 5; anything else -1. */
    public static int failedStep(String chainEnd) {
        if (chainEnd == null) return -1;
        Matcher m = STEP.matcher(chainEnd);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** The chain from step n (1-based, the step that ended stuck: it did not get there) to the end; null if out of range. */
    public static String resume(List<String> steps, int n) {
        if (steps == null || n < 1 || n > steps.size()) return null;
        return String.join(" then ", steps.subList(n - 1, steps.size()));
    }

    /**
     * AA_STEP3 A3 (step2-watch: "no pillar (0 blocks)" with 16 oak planks in the bag): the danger moves place their own
     * blocks (useItemOn, not Baritone's throwaway list), so planks count with the throwaway blocks.
     */
    public static boolean pillarBlock(String id) {
        String k = id == null ? "" : id.replaceFirst("^minecraft:", "");
        return THROWAWAY.contains(k) || k.endsWith("_planks") || k.equals("planks");
    }

    /** The bag's blocks a danger pillar can place ({@link #pillarBlock}). */
    public static int pillarBlocks(Map<String, Integer> bag) {
        int n = 0;
        if (bag != null) for (var e : bag.entrySet()) if (pillarBlock(e.getKey())) n += Math.max(0, e.getValue() == null ? 0 : e.getValue());
        return n;
    }

    public static int throwaway(Map<String, Integer> bag) {
        int n = 0;
        for (String k : THROWAWAY) n += bag.getOrDefault(k, 0);
        return n;
    }

    /**
     * @param depth     blocks of ground over the bot (Jobs.underDepth), or for a hole open to the sky the levels up to
     *                  where it can step out ({@link #holeExit}); 0 = out
     * @param shaftOpen every cell from above the head up to the surface is open (no block, no fluid)
     * @param throwaway throwaway blocks in the bag
     * @param pickaxe   a pickaxe in the bag
     */
    public static Choice choose(int depth, boolean shaftOpen, int throwaway, boolean pickaxe) {
        if (depth <= 0) return new Choice(Way.OUT, "not under ground any more");
        // AA_ROUND3 1: a thin call into the one way-up set (no trail/sky facts here: the walk already walked its trail)
        var f = new RungOptions.SurfaceFacts(depth, new int[]{0, 0, 0}, shaftOpen, throwaway, true, pickaxe, null, 0, null, -1, false);
        Options.Choice c = WayUp.choose(f, new Options.OptionMemory(), 0);
        if (c.best() == null) return new Choice(Way.NONE, c.why());
        return new Choice("pillar".equals(c.best().id()) ? Way.PILLAR : Way.CLIMB, c.why());
    }

    /** The live escape: the whole WayUp choice over the adapter's facts (stairs per heading, tunnel, trail, sky). */
    public static Options.Choice chooseAll(RungOptions.SurfaceFacts f, Options.OptionMemory m, long now) {
        return WayUp.choose(f, m, now);
    }
}
