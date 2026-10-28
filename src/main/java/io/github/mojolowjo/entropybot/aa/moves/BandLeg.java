package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.PathPrice;

/**
 * 0.39.z78 J6b queue item 14 (the manager's ruling on run ER (2); run ER 07:37:48-07:38:18: the pickaxe's dig-down from the
 * floorless edge cell -10 61 69 waited 30 s on a priced search that stopped on its budget, the wait's estimate was read as
 * "no path into the band from y 61", and that became a world fact every other order held): what the band leg (Step.ToBand)
 * does with the priced answer of its cell.
 * <ul>
 * <li>PRICED: walk it. ESTIMATED (past the first-answer wait, Baritone's estimate): walk it too - the walk computes its full
 * path on the way (rule 22, PricedAnswers.afterWait), never a "no path".</li>
 * <li>COMPUTING / CHUNKS: wait (the step's own bound).</li>
 * <li>UNREACHABLE - the complete search found none - from a loaded, STANDABLE start (a floor under it): the band's fact,
 * "no path into the band from x y z" (rule 23 (1): one fact the orders share).</li>
 * <li>Anything else (no estimate, a start with no floor): NOT KNOWN (rule 15) - the order re-plans, nothing is held, never a
 * world fact.</li>
 * </ul>
 * Pure (JUnit: J6bEsTest). Loader notes: none.
 */
public final class BandLeg {
    private BandLeg() {}

    public enum Act { WALK, WAIT, FACT, NOT_KNOWN }

    public record Next(Act act, String why) {}

    /**
     * 0.39.z78 J6b (job-vein a2d4e1c8): the band walk's own end (Baritone's goal ended outside the band) - the band's fact
     * only when its last calc EXHAUSTED the graph from a standable start: null then; otherwise the not-known words
     * ({@code exhausted} null: no calc kept; false: a budget stop or another end).
     */
    public static String walkEnd(Boolean exhausted, int[] start, boolean standable, int y) {
        String at = start == null ? "an unknown cell" : start[0] + " " + start[1] + " " + start[2];
        if (!standable) return "Baritone's goal ended at y " + y + ", its start " + at + " has no floor - not a true no-path (rule 15: not known)";
        if (!Boolean.TRUE.equals(exhausted))
            return "Baritone's goal ended at y " + y + " without an exhausted search (" + (exhausted == null ? "no calc kept" : "a budget stop or another end")
                    + ") - not a no-path (rule 15: not known)";
        return null;
    }

    /**
     * The leg's next act on the answer {@code kind} ({@code reason}: its words) for the search started at {@code start}
     * ({@code standable}: a floor under it).
     */
    public static Next of(PathPrice.Answer a, int[] start, boolean standable) {
        String at = start == null ? "an unknown cell" : start[0] + " " + start[1] + " " + start[2];
        String r = a.reason() == null ? a.kind().name().toLowerCase(java.util.Locale.ROOT) : a.reason();
        return switch (a.kind()) {
            case PRICED -> new Next(Act.WALK, "walking the priced path");
            case ESTIMATED -> new Next(Act.WALK, "walking on Baritone's own search (" + r + ")");
            case COMPUTING, CHUNKS -> new Next(Act.WAIT, "not known yet (" + a.kind().name().toLowerCase(java.util.Locale.ROOT) + (a.reason() == null ? "" : ": " + a.reason()) + ")");
            case UNREACHABLE -> standable
                    ? new Next(Act.FACT, "no path into the band from " + at + " (" + r + ")")
                    : new Next(Act.NOT_KNOWN, "the search from " + at + " found no path, but that cell has no floor - not a true no-path (rule 15: not known)");
            default -> new Next(Act.NOT_KNOWN, "the path into the band from " + at + " is not known (" + r + "; rule 15: not known, never a no-path)");
        };
    }
}
