package io.github.mojolowjo.entropybot.aa.brain;

import java.util.Set;
import java.util.function.Function;

/**
 * 0.39.z78 strip-off (owner 2026-10-27: the strip disabled in general): the owner's switch {@code aa ways strip on|off},
 * saved per world like fair (AaRuntime; a world with no saved answer: OFF). While off the planner offers no strip way: the
 * live World's strip facts answer none ({@link #gate}), so the chain's strip ways, the playbook's descent strip and the
 * old option rows (IronOptions, Discovery) are left out, and the batch derives without the strip leg - the order's own
 * chain prices caves, seen veins and the explore. Said once per order (rule 7): {@link #OFF}. The owner's own strip order
 * (aa want strip ...) is an order, not a way: it is not gated. Here (JUnit, no world) the switch starts ON, so the tests
 * and the replay price strips as before. Pure. Loader notes: none.
 */
public final class StripWays {
    private StripWays() {}

    /** The words every reader says while the strip way is off. */
    public static final String OFF = "strip way off (aa ways strip on to allow it)";

    private static volatile boolean on = true;
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
}
