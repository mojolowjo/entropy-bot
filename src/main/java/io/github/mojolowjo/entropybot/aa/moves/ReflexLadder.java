package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.brain.Failures;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.List;

/**
 * Reflex choosers (no silent failure, a concrete end): each reflex has a short list of named ways, each a Baritone goal or
 * the fork's BurrowProcess. Every failed try is charged in the brain's {@link Failures} under desire
 * {@code reflex.<kind>}, option {@code "<x y z>"} (the cell the reflex started in); after
 * {@code reflex.triesPerWay} tries (Tuning) the next way runs; when every way is spent the reflex is FAILED and the
 * line goes to the danger desire ({@link #spent}). A reflex that ends well forgets its record.
 *
 * <p>Ways: air = surface goal, burrow up, FAILED; lava / fire = run from the hazard, to water, FAILED; creeper = run
 * away, burrow, FAILED. Pure (JUnit: ReflexLadderTest). Loader notes: none.
 */
public final class ReflexLadder {
    public enum Kind {
        AIR("air", Way.SURFACE, Way.BURROW_UP), LAVA("lava", Way.RUN_FROM_HAZARD, Way.TO_WATER), FIRE("fire", Way.RUN_FROM_HAZARD, Way.TO_WATER),
        /** Water seen near: the same records (reflex.lava / reflex.fire), water first, then the run (staged 0.39.s1: never only one). */
        LAVA_WATER_NEAR("lava", Way.TO_WATER, Way.RUN_FROM_HAZARD), FIRE_WATER_NEAR("fire", Way.TO_WATER, Way.RUN_FROM_HAZARD),
        CREEPER("creeper", Way.RUN_AWAY, Way.BURROW);

        final List<Way> ways;
        private final String key;

        Kind(String key, Way... ways) {
            this.key = "reflex." + key;
            this.ways = List.of(ways);
        }

        public String key() { return key; }

        /** The water-first order of a lava / fire ladder (itself for the others). */
        public Kind waterNear() { return this == LAVA ? LAVA_WATER_NEAR : this == FIRE ? FIRE_WATER_NEAR : this; }
    }

    public enum Way {
        SURFACE("surface goal"), BURROW_UP("burrow up"), RUN_FROM_HAZARD("run from the hazard"), TO_WATER("to water"),
        RUN_AWAY("run away"), BURROW("burrow");

        public final String label;

        Way(String label) { this.label = label; }
    }

    private static volatile Failures failures = new Failures();

    /** The brain's record (AaRuntime binds it; an unbound ladder keeps its own). */
    public static void bind(Failures f) {
        if (f != null) failures = f;
    }

    static Failures failures() { return failures; }

    public static int triesPerWay() { return Math.max(1, (int) Tuning.get("reflex.triesPerWay")); }

    public static long retryTicks() { return Math.max(1, (long) Tuning.get("reflex.retryTicks")); }

    public static String cell(int[] c) { return c[0] + " " + c[1] + " " + c[2]; }

    /** The way to run now for {@code kind} at {@code cell}, never before {@code from}; null = every way spent. */
    public static Way way(Kind kind, int[] cell, Way from) {
        int i = failures.fails(kind.key(), cell(cell)) / triesPerWay();
        int start = from == null ? 0 : Math.max(0, kind.ways.indexOf(from));
        int k = Math.max(i, start);
        return k < kind.ways.size() ? kind.ways.get(k) : null;
    }

    public static Way way(Kind kind, int[] cell) { return way(kind, cell, null); }

    /** One failed try charged; returns the way to run next (null = spent: FAILED to the danger desire). */
    public static Way charge(Kind kind, int[] cell, String why, Way from) {
        Way tried = way(kind, cell, from);
        failures.counted(kind.key(), cell(cell), why == null ? "no reason given" : why);
        Way next = way(kind, cell, from);
        watch(tryLine(kind, tried, why, next));
        return next;
    }

    public static int fails(Kind kind, int[] cell) { return failures.fails(kind.key(), cell(cell)); }

    /** The reflex ended well: its tries are forgotten. */
    public static void ended(Kind kind) { failures.forget(kind.key()); }

    // ---- reflex probe 0.39.s5: the watch aid (one PM line per try, only while aa slow is on) ----

    private static volatile java.util.function.Consumer<String> watcher;

    /** The owner's PM while {@code aa slow} is on; null = quiet (normal play). */
    public static void watcher(java.util.function.Consumer<String> w) { watcher = w; }

    public static boolean watching() { return watcher != null; }

    public static void watch(String line) {
        var w = watcher;
        if (w != null && line != null) {
            try { w.accept(line); } catch (RuntimeException ignored) { }
        }
    }

    /** "air: surface goal -> no path (Baritone found no path ...), next: burrow up". Pure. */
    public static String tryLine(Kind kind, Way tried, String why, Way next) {
        String k = kind.key().substring("reflex.".length());
        String w = why == null ? "no reason given" : why;
        String what = w.contains("found no path") ? "no path" : w.contains("refused by the break rules") ? "dig refused" : w.contains("timed out") ? "timed out" : "failed";
        return k + ": " + (tried == null ? "?" : tried.label) + " -> " + what + " (" + w + "), next: " + (next == null ? "FAILED to the danger desire" : next.label);
    }

    /** The line the danger desire gets when every way is spent. */
    public static String spent(Kind kind, int[] cell, String why) {
        StringBuilder w = new StringBuilder();
        for (Way x : kind.ways) w.append(w.length() == 0 ? "" : ", ").append(x.label);
        return kind.key() + " " + cell(cell) + ": FAILED after " + fails(kind, cell) + " tries (" + w + "): " + why;
    }

    private ReflexLadder() { }
}
