package io.github.mojolowjo.entropybot.aa.brain;

import java.util.Map;
import java.util.Objects;

/**
 * Rule 7 (rm-bootstrap, the owner-watched 2026-10-21 run: a job "done" in 0 s restarted 840 times): a job that ends within
 * {@code failures.fastEndMs} without changing anything is a FAILURE with its stage and reason, never "done". "Changing" is
 * the bag (what a job is for) and the bot's cell moving; the failure is charged in {@link Failures} under the job's key by
 * the caller. Pure (JUnit: FastEndTest). Loader notes: none.
 */
public final class FastEnd {
    private FastEnd() {}

    public static long limitMs() { return Tuning.l("failures.fastEndMs"); }

    /**
     * @param end      the job's end text
     * @param ranMs    how long it ran
     * @param bagBefore / bagAfter the bag at its start and now; {@code movedBlocks} how far the bot went
     * @param stage    the step it was on when it ended (null: unknown)
     * @return null when the end stands, else the failure text
     */
    public static String verdict(String end, long ranMs, Map<String, Integer> bagBefore, Map<String, Integer> bagAfter, double movedBlocks, String stage) {
        if (end == null || !end.startsWith("done")) return null;
        if (ranMs >= limitMs()) return null;
        if (!Objects.equals(bagBefore, bagAfter) || movedBlocks >= Failures.movedV()) return null;
        String rest = end.substring(4).replaceFirst("^\\s*[-:]?\\s*", "");
        return "failed: ended after " + ranMs + " ms changing nothing at " + (stage == null ? "its first step" : stage) + (rest.isEmpty() ? "" : " - " + rest);
    }
}
