package io.github.mojolowjo.entropybot.commands;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 0.39.z54 B (run M 19:00:59: "Baritone's walk ended at y 70 short of y 16 (no path down to the band)" while the priced
 * path to y 16 was complete; 38 UNREACHABLE "no progress" answers in 3.5 min): the probe's line texts from plain values,
 * so the game side ({@link WalkProbe}) only gathers facts. No fix and no decision here: facts for one watched run.
 * Pure (JUnit: Z54WalkProbeTest). Loader notes: none.
 */
public final class ProbeLines {
    private ProbeLines() {}

    /** The probe is on: Tuning debug.walkProbe 1 (WalkProbe's gate; every probe entry returns at once when this is false). */
    public static boolean on() { return io.github.mojolowjo.entropybot.aa.brain.Tuning.get("debug.walkProbe") >= 1; }

    /**
     * 0.39.z54 review 1: what a movement already knows, read without side effects (the probe never calls Baritone's
     * toBreak / toPlace, which fill these caches from a world read): the caches when Baritone computed them (null = not
     * yet), the planned break positions and whether a placement is planned.
     */
    public interface MoveFacts {
        List<?> breakCached();
        List<?> placeCached();
        int plannedBreaks();
        boolean plannedPlace();
    }

    /** "breaks 1 places 0" from Baritone's caches, else "breaks/places not computed yet (planned: breaks N, place yes|no)". */
    public static String move(MoveFacts m) {
        List<?> b = m.breakCached(), p = m.placeCached();
        String planned = "planned: breaks " + m.plannedBreaks() + ", place " + (m.plannedPlace() ? "yes" : "no");
        if (b == null || p == null)
            return "breaks " + (b == null ? "?" : b.size()) + " places " + (p == null ? "?" : p.size()) + " (not computed yet by Baritone; " + planned + ")";
        return "breaks " + b.size() + " places " + p.size() + " (" + planned + ")";
    }

    /** "x y z", or "-" for none. */
    public static String at(int[] p) { return p == null ? "-" : p[0] + " " + p[1] + " " + p[2]; }

    /**
     * One client tick of a walk step. {@code origin}: "kept" (PathingBehavior got the executing path from the priced path),
     * "fresh" (a search of its own), "-" (no path executing); {@code offer}: segments the walk offered / taken so far.
     */
    public record Walk(long tick, int[] feet, String step, String goal, String inControl, boolean goalProcessActive, boolean executing,
                       int[] src, int[] dest, int pos, int len, boolean calc, String origin, int[] offer, String walkAnswer,
                       String move, String moveFacts, String intent, boolean hold) {
        public String line() {
            return "t=" + tick + " feet " + at(feet) + " | step " + step + " | goal " + goal + " | in control " + inControl
                    + " | custom goal active " + goalProcessActive
                    + " | path " + (executing ? at(src) + " -> " + at(dest) + " at " + pos + "/" + len : "none executing")
                    + " | calc " + (calc ? "in progress" : "none")
                    + " | origin " + origin + " (offered " + offer[0] + ", taken " + offer[1] + "; walk start: " + walkAnswer + ")"
                    + " | move " + move + (move.equals("-") ? "" : " " + moveFacts)
                    + " | intent " + intent + " | reflex hold " + hold;
        }
    }

    /** A Baritone path event while a walk step runs. */
    public static String event(long tick, int[] feet, String event, String step) {
        return "t=" + tick + " feet " + at(feet) + " | " + event + " | step " + (step == null ? "-" : step);
    }

    /** A cancel the mod sent while a walk step ran: who, and the calling frames. */
    public static String cancel(long tick, int[] feet, String who, String frames, String step) {
        return "t=" + tick + " feet " + at(feet) + " | CANCEL by " + who + " (" + frames + ") | step " + (step == null ? "-" : step);
    }

    /**
     * One kept segment of a price: start, end, ticks, the movements by kind, the break positions and placements they plan
     * (the movements' own plan, no world read: review 1) and the falls (a drop of more than one block).
     */
    public record Segment(int[] src, int[] dest, double ticks, Map<String, Integer> kinds, int breaks, int places, int falls) {
        public String text() {
            StringBuilder k = new StringBuilder();
            for (Map.Entry<String, Integer> e : new TreeMap<>(kinds).entrySet()) k.append(k.length() == 0 ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue());
            return at(src) + " -> " + at(dest) + " " + Math.round(ticks) + " ticks [" + k + "] planned breaks " + breaks + " places " + places + " falls " + falls;
        }
    }

    /** The priced path at its landing: from, goal, the answer and each kept segment (none = why). */
    public static String price(int[] from, int[] goal, String answer, List<Segment> segs) {
        StringBuilder b = new StringBuilder("from " + at(from) + " to " + at(goal) + " | " + answer + " | " + segs.size() + " segment(s)");
        if (segs.isEmpty()) b.append(" (none kept: no path, or the price is not the fork's priced path)");
        for (int i = 0; i < segs.size(); i++) b.append(" | ").append(i + 1).append(": ").append(segs.get(i).text());
        return b.toString();
    }

    /**
     * An UNREACHABLE / NO_PROGRESS answer: from, goal, the fork's failure detail (null: the fork kept none - which fields
     * are missing is said), the goal's chunk, the cost hook's refusals since the last probe line (every search, not this
     * one alone: the hook's counters are global).
     */
    public static String unreachable(int[] from, int[] goal, String reason, String failure, boolean goalChunkLoaded, long costDenies, long placeDenies) {
        return "from " + at(from) + " to " + at(goal) + " | " + reason
                + " | " + (failure == null ? "no failure detail kept by the fork (segment, nodes and best node missing: IPricedPath.failure gave null)" : failure)
                + " | goal chunk " + (goalChunkLoaded ? "loaded" : "NOT loaded")
                + String.format(Locale.ROOT, " | cost hook refusals since the last probe line (all searches): break %d, place %d", costDenies, placeDenies);
    }
}
