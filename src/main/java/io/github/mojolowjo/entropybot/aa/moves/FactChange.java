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
    public record Fact(String key, String text) {
        /** 0.39.z41: a new price, not a failure: a walk's path became known (the order is priced again, nothing is charged). */
        public boolean price() { return key.startsWith(PRICE); }
    }

    /** 0.39.z41: the key of {@link #priced}. */
    public static final String PRICE = "price ";

    /** 0.39.z41: a walk's complete path landed ({@code text}: what landed); the running order is priced again at its next step. */
    public static Fact priced(String text) { return new Fact(PRICE + "walk", text); }

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

    /**
     * 0.39.z33 (S36): the step's source could not be reached: Baritone's process ended with none of it ({@code why}: its own
     * words, or that it said none). The same slot as {@link #source}: the order re-plans without that source.
     */
    public static void unreachable(String block, String at, String why) {
        last = new Fact("source " + block + " " + at, unreachableText(why));
    }

    /** Baritone's last chat line since a process started (its count then and now), or that it said none: never guessed. */
    public static String baritoneSaid(long linesAtStart, long linesNow, String lastLine) {
        if (linesNow <= linesAtStart || lastLine == null || lastLine.isBlank()) return "Baritone said nothing since the mine started";
        String l = lastLine.strip();
        return l.length() > 160 ? l.substring(0, 157) + "..." : l;  // structural: one chat line's length in the step text
    }

    /** The reason an unreachable source is dropped with ("unreachable (Baritone: Unable to find any path to ...)"). */
    public static String unreachableText(String why) { return "unreachable (Baritone: " + why + ")"; }

    /** An item the step needs is not in the bag (nor in storage) when it starts. */
    public static void bag(String item, String why) {
        last = new Fact("bag " + item, item.replaceFirst("^minecraft:", "") + " not in the bag (" + why + ")");
    }

    /**
     * 0.39.z36 (S41): a craft ran out of one recipe ingredient mid-step ({@code alts}: every item the recipe's Ingredient
     * accepts in that cell, as the game expanded it): the "bag" slot keyed by that ingredient as a need word (several items
     * joined, {@link io.github.mojolowjo.entropybot.aa.NeedChain#word}); the order re-plans on the bag of now.
     */
    public static void ranOut(java.util.List<String> alts, String why) {
        if (alts == null || alts.isEmpty()) return;
        java.util.List<String> sh = new java.util.ArrayList<>();
        for (String a : alts) sh.add(a.replaceFirst("^minecraft:", ""));
        last = new Fact("bag " + io.github.mojolowjo.entropybot.aa.NeedChain.word(String.join("|", alts)),
                "none of " + String.join(", ", sh) + " left in the bag (" + why + ")");
    }

    /**
     * 0.39.z34 (S39): the step's declared need ({@link StepNeeds}) is short in the bag - its tool gone, an item it uses up
     * short. The same "bag" slot as {@link #bag}; {@code what}: the missing need ("pickaxe tier>=1"), {@code text}: the words.
     */
    public static void bagNeed(String what, String text) {
        last = new Fact("bag " + what, text);
    }

    /** The fact said since the last {@link #clear}, taken once; null: none. */
    public static Fact take() {
        Fact f = last;
        last = null;
        return f;
    }

    public static void clear() { last = null; }
}
