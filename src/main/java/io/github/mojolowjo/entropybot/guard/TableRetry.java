package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.Quiet;

import java.util.Set;
import java.util.function.Supplier;

/**
 * 0.39.z26 (last) 1: the structure table's build state, plain Java. The built-for marks (recipe count, world-gen set) are
 * set only after a build succeeds, so a failed build is asked again on the next tick that has recipes. A failure is said once
 * per world, the success after it once. Loader notes: none.
 */
final class TableRetry<T> {
    volatile T value;
    volatile int builtRecipes = -1;
    volatile Set<String> builtGen;
    private volatile boolean warned;

    /** True when this recipe count and world-gen set are already built. */
    boolean current(int n, Set<String> gen) { return value != null && n == builtRecipes && gen == builtGen; }

    /** Builds; returns whether the value is built. A throw leaves it null and the marks unset. */
    boolean attempt(int n, Set<String> gen, Supplier<T> builder) {
        value = null;
        try {
            value = builder.get();
        } catch (RuntimeException e) {
            value = null;
            if (!warned) {
                warned = true;
                Quiet.warn("structure facts: the table could not be built (" + e + ") - every block answers not loaded, every break stays refused; it is tried again on the next tick", e);
            }
            return false;
        }
        builtRecipes = n;
        builtGen = gen;
        if (warned) {
            warned = false;
            Quiet.line("structure facts: the table is built now (the earlier build had failed)");
        }
        return true;
    }

    /** A new world or no level: forget everything, including the failure said. */
    void reset() { value = null; builtRecipes = -1; builtGen = null; warned = false; }

    /** The failure said by something else than the build (the tick's own catch): true the first time per world. */
    boolean firstWarn() {
        if (warned) return false;
        warned = true;
        return true;
    }
}
