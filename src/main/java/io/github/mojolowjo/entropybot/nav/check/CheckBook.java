package io.github.mojolowjo.entropybot.nav.check;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Nav step 9c: the route checker's books. Each part of a trip the pilot drives ends as one {@link Row}: what the ghost
 * said before (pass, refuse with the pilot's reason, or unchecked with why), what really happened (arrived, the part
 * done, failed, or unknown when the trip was stopped from outside), and whether the two agree. A ghost that flew only
 * up to the first move needing the hands is "flown" and agrees with anything that got at least that far. The books keep
 * the counts and the ghost's cost per game tick (average and worst) for {@code aa nav status}, and the last rows.
 * Pure (JUnit: CheckBookTest). Loader notes: none.
 */
public final class CheckBook {
    public enum Ghost { PASS, FLOWN, REFUSE, UNCHECKED }

    public enum Real { ARRIVED, PART_DONE, FAILED, UNKNOWN }

    /** One part: the ghost's word, its reason, the real ending and its line. */
    public record Row(int part, Ghost ghost, String why, Real real, String note) {
        /** True: agree; false: DISAGREE; null: nothing to compare (unchecked, unknown, or flown and failed later). */
        public Boolean agree() {
            if (ghost == Ghost.UNCHECKED || real == Real.UNKNOWN) return null;
            boolean ok = real != Real.FAILED;
            return switch (ghost) {
                case PASS -> ok;
                case REFUSE -> !ok;
                case FLOWN -> ok ? Boolean.TRUE : null;
                default -> null;
            };
        }

        public String line() {
            Boolean a = agree();
            return String.format(Locale.ROOT, "part %d: ghost %s%s, real %s%s: %s", part, ghost.name().toLowerCase(Locale.ROOT),
                    why == null || why.isEmpty() ? "" : " (" + why + ")", real.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    note == null || note.isEmpty() ? "" : " (" + note + ")", a == null ? "not compared" : a ? "agree" : "DISAGREE");
        }
    }

    static final int KEEP = 20;                                              // structural: rows kept for the status line

    private final Deque<Row> last = new ArrayDeque<>();
    private int parts, agree, disagree, pass, flown, refuse, unchecked;
    private long costTicks;
    private double costMs, worstMs;

    /** A new part's number. */
    public int newPart() { return ++parts; }

    /** Books a finished part; returns its agreement (see {@link Row#agree}). */
    public Boolean close(Row r) {
        switch (r.ghost()) {
            case PASS -> pass++;
            case FLOWN -> flown++;
            case REFUSE -> refuse++;
            case UNCHECKED -> unchecked++;
        }
        Boolean a = r.agree();
        if (Boolean.TRUE.equals(a)) agree++;
        if (Boolean.FALSE.equals(a)) disagree++;
        last.addFirst(r);
        while (last.size() > KEEP) last.removeLast();
        return a;
    }

    /** The ghost's work in one game tick, in ms (only ticks with ghost work count). */
    public void cost(double ms) {
        costTicks++;
        costMs += ms;
        worstMs = Math.max(worstMs, ms);
    }

    public String status() {
        int compared = agree + disagree;
        return String.format(Locale.ROOT, "check: %d parts, ghost pass %d, flown to the hands %d, refuse %d, unchecked %d; agree %d of %d compared%s; ghost %.3f ms a tick, worst %.1f",
                parts, pass, flown, refuse, unchecked, agree, compared, compared == 0 ? "" : String.format(Locale.ROOT, " (%.0f%%)", 100.0 * agree / compared),
                costTicks == 0 ? 0 : costMs / costTicks, worstMs);
    }

    public List<Row> last() { return new ArrayList<>(last); }
}
