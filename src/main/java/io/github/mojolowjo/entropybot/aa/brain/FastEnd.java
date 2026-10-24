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
     * 0.39.z43 (Run D 2026-10-23): the end words of a job that stopped because a fact it waited for arrived (a path became
     * known, a source became known). Such an end is a SUCCESS - the order is priced again on the new fact - and the
     * no-change check below never fires on it. Every job ending that way ends its text with this phrase.
     */
    public static final String FACT_ARRIVED = "the chain is priced again";

    /** True when the end text says a waited-for fact arrived ({@link #FACT_ARRIVED}). */
    public static boolean factArrived(String end) { return end != null && end.contains(FACT_ARRIVED); }

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
        if (factArrived(end)) return null;   // z43: the fact it waited for arrived - a success, the order re-prices
        if (!Objects.equals(bagBefore, bagAfter) || movedBlocks >= Failures.movedV()) return null;
        String rest = end.substring(4).replaceFirst("^\\s*[-:]?\\s*", "");
        return "failed: ended after " + ranMs + " ms changing nothing at " + (stage == null ? "its first step" : stage) + (rest.isEmpty() ? "" : " - " + rest);
    }
}
