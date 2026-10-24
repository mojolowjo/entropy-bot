package io.github.mojolowjo.entropybot.aa.moves;

/**
 * 0.39.z30 (owner 2026-10-23: a job whose fact changed re-plans and continues): the fact a failing step relied on and found
 * changed - a station forgotten or gone, a known source gone, a needed item missing from the bag at the step's start. The
 * code that finds the change says it here with the fact's key and words; the step hands hand it to {@link Steps} with the
 * step's failure ({@link Steps.Hands#lastFact}), which re-plans the order instead of ending it. One slot, client thread
 * only; cleared at each step start, taken once. Typed: no failure text is ever matched for it. Pure.
 */
public final class FactChange {
    /** {@code key}: the fact's kind and subject ("station minecraft:crafting_table"); {@code text}: the chat words ("table at 4 -60 -104 gone"). */
    public record Fact(String key, String text) {}

    private static Fact last;

    private FactChange() {}

    /** A station of the recipe kind the step needed is forgotten or gone ({@code at}: where it was, null: none known anywhere). */
    public static void station(String kind, String name, String at) {
        last = new Fact("station " + kind, name + (at == null ? ": none known" : " at " + at + " gone"));
    }

    /** A known source the step went for is gone. */
    public static void source(String block, String at) {
        last = new Fact("source " + block + " " + at, block.replaceFirst("^minecraft:", "") + " at " + at + " gone");
    }

    /** An item the step needs is not in the bag (nor in storage) when it starts. */
    public static void bag(String item, String why) {
        last = new Fact("bag " + item, item.replaceFirst("^minecraft:", "") + " not in the bag (" + why + ")");
    }

    /** The fact said since the last {@link #clear}, taken once; null: none. */
    public static Fact take() {
        Fact f = last;
        last = null;
        return f;
    }

    public static void clear() { last = null; }
}
