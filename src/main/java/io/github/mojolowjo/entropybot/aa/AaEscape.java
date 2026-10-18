package io.github.mojolowjo.entropybot.aa;

import java.util.List;
import java.util.Map;
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

    public static boolean stuck(String chainEnd) {
        return chainEnd != null && chainEnd.contains("stuck under ground");
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

    public static int throwaway(Map<String, Integer> bag) {
        int n = 0;
        for (String k : THROWAWAY) n += bag.getOrDefault(k, 0);
        return n;
    }

    /**
     * @param depth     blocks of ground over the bot (Jobs.underDepth; 0 = out)
     * @param shaftOpen every cell from above the head up to the surface is open (no block, no fluid)
     * @param throwaway throwaway blocks in the bag
     * @param pickaxe   a pickaxe in the bag
     */
    public static Choice choose(int depth, boolean shaftOpen, int throwaway, boolean pickaxe) {
        if (depth <= 0) return new Choice(Way.OUT, "not under ground any more");
        if (shaftOpen && throwaway >= depth)
            return new Choice(Way.PILLAR, "an open shaft straight up (" + depth + " to the surface), " + throwaway + " blocks to place");
        String shaft = shaftOpen ? "a shaft up but only " + throwaway + " of " + depth + " blocks to place" : "no open shaft above";
        if (pickaxe) return new Choice(Way.CLIMB, shaft + ": staircase up (3-high headroom)");
        return new Choice(Way.NONE, shaft + " and no pickaxe for stairs; the trail back was walked already");
    }
}
