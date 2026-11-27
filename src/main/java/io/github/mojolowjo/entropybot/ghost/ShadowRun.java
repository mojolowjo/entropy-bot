package io.github.mojolowjo.entropybot.ghost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Dry-run package 1, the body test ("debug ghost shadow"): the real player's inputs and where its feet ended after each
 * tick, kept while it walks; afterwards the ghost replays the same inputs from the same start and this class compares the
 * two tracks. The plan's mark (m0j, 2026-11-26): the ghost's end spot within {@code ghost.shadowPassBlocks} (0.1) of the
 * real one.
 *
 * <p>Pure (JUnit: ShadowRunTest). Loader notes: none; {@code GhostShadow} fills it from {@code PlayerTickEvent.Post}.
 */
public final class ShadowRun {
    /** A foot position. */
    public record Pos(double x, double y, double z) {
        public double dist(Pos o) {
            double dx = x - o.x, dy = y - o.y, dz = z - o.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        @Override
        public String toString() { return String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", x, y, z); }
    }

    /** The comparison: ticks, end gap, the worst gap and its tick, the first tick past {@code driftAt}, pass or not. */
    public record Report(int ticks, Pos realEnd, Pos ghostEnd, double endGap, double worstGap, int worstTick,
                         int firstDriftTick, double driftAt, double passMark, boolean pass) {
        public String line() {
            return String.format(Locale.ROOT, "%s: %d ticks, end gap %.4f (mark %.2f), worst gap %.4f at tick %d, first gap over %.3f at %s; real end %s, ghost end %s",
                    pass ? "PASS" : "FAIL", ticks, endGap, passMark, worstGap, worstTick, driftAt,
                    firstDriftTick < 0 ? "none" : "tick " + firstDriftTick, realEnd, ghostEnd);
        }
    }

    private final Pos start;
    private final int maxTicks;
    private final List<GhostInput> inputs = new ArrayList<>();
    private final List<Pos> real = new ArrayList<>();

    public ShadowRun(Pos start, int maxTicks) {
        if (maxTicks < 1) throw new IllegalArgumentException("a shadow run needs at least 1 tick, not " + maxTicks);
        this.start = start;
        this.maxTicks = maxTicks;
    }

    /** One real tick: the input it used and where the feet were after it. */
    public void add(GhostInput in, Pos after) {
        if (full()) throw new IllegalStateException("the run is full (" + maxTicks + " ticks)");
        inputs.add(in);
        real.add(after);
    }

    public boolean full() { return inputs.size() >= maxTicks; }

    public int size() { return inputs.size(); }

    public int maxTicks() { return maxTicks; }

    public Pos start() { return start; }

    /** The real feet after tick {@code i} (0-based). */
    public Pos real(int i) { return real.get(i); }

    public List<GhostInput> inputs() { return Collections.unmodifiableList(inputs); }

    /** A short summary of what the real player did: ticks, start, end, and how many ticks held jump, sprint and sneak. */
    public String summary() {
        int jumps = 0, sprint = 0, shift = 0;
        for (GhostInput in : inputs) {
            if (in.jump()) jumps++;
            if (in.sprint()) sprint++;
            if (in.shift()) shift++;
        }
        Pos end = real.isEmpty() ? start : real.get(real.size() - 1);
        return String.format(Locale.ROOT, "%d ticks from %s to %s (%.2f blocks), jump held %d, sprint %d, sneak %d",
                inputs.size(), start, end, start.dist(end), jumps, sprint, shift);
    }

    /** Compares the ghost's feet after each replayed tick (same order as the inputs) with the real ones. */
    public Report compare(List<Pos> ghost, double driftAt, double passMark) {
        if (ghost.size() != real.size()) throw new IllegalArgumentException("the ghost has " + ghost.size() + " ticks, the real run " + real.size());
        if (real.isEmpty()) throw new IllegalStateException("no tick recorded yet");
        double worst = -1;
        int worstTick = -1, firstDrift = -1;
        for (int i = 0; i < real.size(); i++) {
            double gap = real.get(i).dist(ghost.get(i));
            if (gap > worst) { worst = gap; worstTick = i + 1; }
            if (firstDrift < 0 && gap > driftAt) firstDrift = i + 1;
        }
        Pos re = real.get(real.size() - 1), ge = ghost.get(ghost.size() - 1);
        double end = re.dist(ge);
        return new Report(real.size(), re, ge, end, worst, worstTick, firstDrift, driftAt, passMark, end <= passMark);
    }
}
