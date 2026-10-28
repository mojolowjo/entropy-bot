package io.github.mojolowjo.entropybot.aa.brain;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 0.39.z78 strip-off (owner 2026-10-27: the strip disabled in general): the owner's switch {@code aa ways strip on|off},
 * saved per world like fair (AaRuntime; a world with no saved answer: OFF). While off the planner offers no strip way: the
 * live World's strip facts answer none ({@link #gate}), so the chain's strip ways, the playbook's descent strip and the
 * old option rows (IronOptions, Discovery) are left out, and the batch derives without the strip leg - the order's own
 * chain prices caves, seen veins and the explore. Said once per order (rule 7): {@link #OFF}. The owner's own strip order
 * (aa want strip ...) is an order, not a way: it is not gated.
 * <p>0.39.z78 strip-off review: the default is OFF, here too - a game prices before its world's switch is read (Core waits
 * up to its key-wait ticks for the world key while AA may already tick), so nothing strips before the owner said so. A
 * strip step planned before the switch went off (a running routine, a repair's kept tail) is refused at its start
 * ({@link #planned}, StripStep / StepHands) and never kept by a repair (Goals.repair, {@link #holdsStrip}). Tests that
 * price strips set it on (D6ReplayTest, the option rows' tests). Pure. Loader notes: none.
 */
public final class StripWays {
    private StripWays() {}

    /** The words every reader says while the strip way is off. */
    public static final String OFF = "strip way off (aa ways strip on to allow it)";

    private static volatile boolean on = false;
    private static final Set<String> SAID = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean on() { return on; }

    /** The owner's switch (AaRuntime, from the world's saved answer or aa ways strip on|off); a change says it again. */
    public static void set(boolean v) {
        if (v != on) SAID.clear();
        on = v;
    }

    /** The order {@code order} has not been told yet that the strip way is off (true once per order while off). */
    public static boolean sayOnce(String order) { return !on && order != null && SAID.add(order); }

    /** The strip facts as given while on, none while off (read at each ask: a switch takes effect on the next price). */
    public static <T> Function<String, T> gate(Function<String, T> strip) {
        return word -> on ? strip.apply(word) : null;
    }

    /**
     * A planner's strip (a chain's whole strip: serial 0; its legs: 1..n, NeedChain.stripLegs), not the owner's own strip
     * order (StripStep.resolve stamps it with the order's time in ms). Pure.
     */
    public static boolean planned(io.github.mojolowjo.entropybot.aa.moves.Step.Strip s) {
        return s != null && s.serial() < OWNER_SERIAL_MIN;
    }

    /** structural: below any order time in ms (the owner's strip order's serial), above any leg number. */
    static final long OWNER_SERIAL_MIN = 1_000_000_000L;

    /** The steps hold a strip way's step (a planned strip, its walk to the band, the old strip leg or strip mine). */
    public static boolean holdsStrip(List<io.github.mojolowjo.entropybot.aa.moves.Step> steps) {
        if (steps == null) return false;
        for (var s : steps) {
            if (s instanceof io.github.mojolowjo.entropybot.aa.moves.Step.Strip st && planned(st)) return true;
            if (s instanceof io.github.mojolowjo.entropybot.aa.moves.Step.ToStrip || s instanceof io.github.mojolowjo.entropybot.aa.moves.Step.StripLeg) return true;
            if (s instanceof io.github.mojolowjo.entropybot.aa.moves.Step.Mine m && m.how() == io.github.mojolowjo.entropybot.aa.moves.Step.Mine.How.STRIP) return true;
        }
        return false;
    }
}
