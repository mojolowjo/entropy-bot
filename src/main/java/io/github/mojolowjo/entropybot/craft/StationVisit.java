package io.github.mojolowjo.entropybot.craft;

import io.github.mojolowjo.entropybot.aa.moves.JobHands;

/**
 * station-memory (0.39.z14, owner 2026-10-22: "it should walk to the crafting table it placed and make it there"): a craft
 * that needs the crafting table (a smelt the furnace) uses the KNOWN station wherever it is. The known station is the
 * nearest one of the station memory (Nearby's one lookup: my placements and what Baritone's scan knows). The mod names the
 * block; the fork's InteractProcess ({@link JobHands#use}) walks within reach of it (Baritone picks the stand) and opens it.
 * A new station is made only when none is known anywhere ({@link #tick} answers NONE then). Forgetting: when the use has
 * ended (the bot is there, or the fork could get no nearer) and the block there is not that station any more, it leaves the
 * memory with the line "the crafting table at x y z is gone", and the step fails with that reason so the chain re-prices.
 * Never for distance or time. Pure (JUnit: StationVisitTest). Loader notes: none.
 */
public final class StationVisit {
    private StationVisit() {}

    public enum Kind { WAIT, OPENED, GONE, FAILED, NONE }

    /** One tick's answer and its line. */
    public record Out(Kind kind, String why) {
        public boolean waiting() { return kind == Kind.WAIT; }
    }

    /** The station memory as the step sees it (the live one is Crafting's, over NearbyLive and the ledger). */
    public interface Memory {
        /** The known stations of the recipe kind (rule 15: known, none known anywhere, or not loaded). */
        io.github.mojolowjo.entropybot.aa.StationFact nearest(String kind);

        /** TRUE: the block at the cell is a station of the kind; FALSE: something else is there; null: cannot tell (not loaded). */
        Boolean isStation(String kind, int[] at);

        /** Drop the station at the cell from the memory, with why (the ledger record, the cached answers). */
        void forget(String kind, int[] at, String why);
    }

    /** "the crafting table at 1 2 3 is gone" */
    public static String gone(String name, int[] at) {
        return "the " + name + " at " + at[0] + " " + at[1] + " " + at[2] + " is gone";
    }

    /**
     * One tick of the visit: the hands use the known station at {@code at} (InteractProcess: walk within reach, look, click),
     * every tick until it ends. Then the arrival check: the block is not that station any more -> GONE (forgotten, with
     * the line); the use failed -> FAILED with the fork's reason; else OPENED. {@code at} null: NONE (make one).
     */
    public static Out tick(JobHands hands, Memory mem, String kind, int[] at, String name) {
        if (at == null) return new Out(Kind.NONE, "no " + name + " known anywhere");
        JobHands.Result r = hands.use(at, "the " + name);
        if (r.running()) return new Out(Kind.WAIT, r.why());
        if (Boolean.FALSE.equals(mem.isStation(kind, at))) {
            String why = gone(name, at);
            mem.forget(kind, at, why);
            return new Out(Kind.GONE, why);
        }
        if (r.failed()) return new Out(Kind.FAILED, r.why());
        return new Out(Kind.OPENED, r.why());
    }
}
