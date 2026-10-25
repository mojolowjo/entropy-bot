package io.github.mojolowjo.entropybot.aa.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * 0.39.z62 item 4 (run S 04:57:07 / 04:57:12: client ticks of 3050 and 5586 ms, all in "guard+commands (aa)", the section
 * the load probe could not split; design D3 3.1 "probe aatick"): the AA tick's stopwatch. While {@link #on} (the load probe
 * on, set per AA tick by its caller), {@link #begin} starts a tick, {@link #lap} ends a named section, {@link #resolved}
 * counts one chain resolve (Goals.chain) and its time; {@link #line} is the "probe aatick:" text when the tick took longer
 * than the limit. Off: every entry returns on one field read, nothing is built. The clock is a function (nanoseconds), so
 * JUnit drives it. Pure (JUnit: Z62RunSTest). Loader notes: none.
 */
public final class TickLaps {
    /** The AA tick's one stopwatch (client thread only). */
    public static final TickLaps AA = new TickLaps(System::nanoTime);

    private final LongSupplier clock;
    private volatile boolean on;
    private long start, last, resolveNs;
    private int resolves;
    private final List<String> names = new ArrayList<>();
    private final List<Long> ns = new ArrayList<>();

    public TickLaps(LongSupplier clock) { this.clock = clock; }

    public boolean on() { return on; }

    /** A tick starts; {@code active}: the probe is on for it (off: nothing is measured until the next begin). */
    public void begin(boolean active) {
        on = active;
        if (!active) return;
        names.clear();
        ns.clear();
        resolves = 0;
        resolveNs = 0;
        start = clock.getAsLong();
        last = start;
    }

    /** The section {@code name} ended now (its time: since the last lap or the begin). */
    public void lap(String name) {
        if (!on) return;
        long now = clock.getAsLong();
        names.add(name);
        ns.add(now - last);
        last = now;
    }

    /** One chain resolve took {@code nanos}. */
    public void resolved(long nanos) {
        if (!on) return;
        resolves++;
        resolveNs += Math.max(0, nanos);
    }

    /** Now on this stopwatch's clock (0 when off: the caller skips the resolve count). */
    public long now() { return on ? clock.getAsLong() : 0; }

    /**
     * The "probe aatick:" text when the tick from {@link #begin} to now took more than {@code limitMs}, else null (off: null).
     * "t=12077 | aa tick 5584.9 ms (over debug.aaTickMs 20) | worldFacts 0.1, senses 0.0, ... tree 5580.2, ... | resolves 3 = 5570.0 ms".
     * The sections over a tenth of a millisecond are named, the rest summed as "others".
     */
    public String line(long tick, double limitMs) {
        if (!on) return null;
        double total = (clock.getAsLong() - start) / 1e6;     // structural: ns per ms
        if (!(total > limitMs)) return null;
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "t=%d | aa tick %.1f ms (over debug.aaTickMs %.0f) |", tick, total, limitMs));
        double others = 0;
        int named = 0;
        for (int i = 0; i < names.size(); i++) {
            double ms = ns.get(i) / 1e6;     // structural: ns per ms
            if (ms < 0.1) { others += ms; continue; }       // structural: below a tenth of a ms is noise in this line
            b.append(named++ == 0 ? " " : ", ").append(names.get(i)).append(String.format(Locale.ROOT, " %.1f", ms));
        }
        if (named == 0) b.append(" no section over 0.1 ms");
        if (others > 0) b.append(String.format(Locale.ROOT, ", others %.1f", others));
        b.append(String.format(Locale.ROOT, " | resolves %d = %.1f ms", resolves, resolveNs / 1e6));     // structural: ns per ms
        return b.toString();
    }
}
