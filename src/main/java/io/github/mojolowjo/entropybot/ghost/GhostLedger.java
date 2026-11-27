package io.github.mojolowjo.entropybot.ghost;

import io.github.mojolowjo.entropybot.ghost.PathCheck.Kind;
import io.github.mojolowjo.entropybot.ghost.PathCheck.Reason;
import io.github.mojolowjo.entropybot.ghost.PathCheck.Verdict;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dry-run package 3: the checker's books. Each path ends as one {@link Row}: the ghost's verdict, what really happened
 * (arrived, or failed where), and whether they agree. Counters for {@code debug ghost status}: paths checked, passed,
 * refused per reason, unchecked per reason, disagreements, and the ghost's cost per game tick. Refusal log lines are
 * rate-limited (plan, "Error catching"): the first {@link #FULL_LINES} in full, then one count line every
 * {@link #COUNT_EVERY} more.
 *
 * <p>Pure (JUnit: GhostLedgerTest). Loader notes: none.
 */
public final class GhostLedger {
    public enum Outcome { ARRIVED, FAILED, UNKNOWN }

    /** One path: its number, verdict, real outcome and where the real walk ended or stopped. */
    public record Row(int path, Verdict verdict, Outcome outcome, String realNote) {
        /** Agreement counts only checked paths with a known outcome: pass = arrived, refuse = failed. */
        public Boolean agrees() {
            if (verdict.kind() == Kind.UNCHECKED || outcome == Outcome.UNKNOWN) return null;
            return (verdict.kind() == Kind.PASS) == (outcome == Outcome.ARRIVED);
        }

        public String line() {
            Boolean a = agrees();
            return "path " + path + ": ghost " + verdict.line() + "; real " + outcome.name().toLowerCase(Locale.ROOT)
                    + (realNote == null || realNote.isEmpty() ? "" : " (" + realNote + ")") + (a == null ? "" : a ? "; agree" : "; DISAGREE");
        }
    }

    static final int FULL_LINES = 5;      // structural: the plan's "the first 5 in full"
    static final int COUNT_EVERY = 10;    // structural: then a count line every 10

    private int paths, passed, refusals, agree, disagree;
    private final Map<Reason, Integer> refused = new EnumMap<>(Reason.class);
    private final Map<Reason, Integer> unchecked = new EnumMap<>(Reason.class);
    private final List<Row> recent = new ArrayList<>();
    private final TickTimes cost = new TickTimes();

    public int newPath() { return ++paths; }

    /** Counts a verdict as it comes; true when the refusal line should be logged in full (else counted only). */
    public boolean verdict(Verdict v) {
        switch (v.kind()) {
            case PASS -> passed++;
            case REFUSE -> { refused.merge(v.reason(), 1, Integer::sum); refusals++; }
            case UNCHECKED -> unchecked.merge(v.reason(), 1, Integer::sum);
        }
        return v.kind() == Kind.REFUSE && refusals <= FULL_LINES;
    }

    /** After a refusal not logged in full: is it time for a count line? */
    public boolean countLineDue() { return refusals > FULL_LINES && (refusals - FULL_LINES) % COUNT_EVERY == 0; }

    public int refusals() { return refusals; }

    /** A finished row; answers its agreement (null: not counted). */
    public Boolean close(Row r) {
        Boolean a = r.agrees();
        if (a != null) { if (a) agree++; else disagree++; }
        recent.add(r);
        if (recent.size() > 20) recent.remove(0);   // structural: the last 20 rows for status
        return a;
    }

    /** The ghost's work in one game tick (nanoseconds); a tick with no work is not counted. */
    public void gameTickCost(long nanos) { if (nanos > 0) cost.add(nanos); }

    public TickTimes cost() { return cost; }

    public int disagreements() { return disagree; }

    public List<Row> recent() { return List.copyOf(recent); }

    public String status() {
        StringBuilder b = new StringBuilder();
        b.append(paths).append(" paths: ").append(passed).append(" pass, ").append(sum(refused)).append(" refused").append(fmt(refused))
                .append(", ").append(sum(unchecked)).append(" unchecked").append(fmt(unchecked))
                .append("; vs the real walk: ").append(agree).append(" agree, ").append(disagree).append(" disagree; ghost cost per game tick: ").append(cost);
        return b.toString();
    }

    private static int sum(Map<Reason, Integer> m) { return m.values().stream().mapToInt(Integer::intValue).sum(); }

    private static String fmt(Map<Reason, Integer> m) {
        if (m.isEmpty()) return "";
        StringBuilder b = new StringBuilder(" (");
        m.forEach((k, v) -> b.append(b.length() > 2 ? ", " : "").append(k.name().toLowerCase(Locale.ROOT)).append(' ').append(v));
        return b.append(')').toString();
    }
}
