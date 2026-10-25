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

    /**
     * 0.39.z48 (run I 03:19:34, S62): the fork's use of the known station ended failed from where it stood ("no face visible
     * from ..." after its second try from another side): the station slot; the order re-plans and the chain prices the next
     * known station or making a new one (z33's far-station rule), this one refused from now on.
     */
    public static void stationUnusable(String kind, String name, String at, String why) {
        last = new Fact("station " + kind, name + " at " + at + " unusable: " + why);
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

    /** 0.39.z42: the source key of a band ("to y 5..31"): the explore way that goes to that height first. */
    public static String bandKey(int minY, int maxY) { return "to y " + minY + ".." + maxY; }

    /**
     * 0.39.z42 (run C 20:27:06): Baritone's goal ended outside the band (no path into it from here): the same source slot;
     * the order re-plans without that band (the chain takes the next way or explores overland).
     */
    public static void band(int minY, int maxY, String why) {
        last = new Fact("source " + bandKey(minY, maxY), why);
    }

    /**
     * 0.39.z46: a strip way ended short (the strip's own end - "no path", lava, no pickaxe - or no path down to its band):
     * the source slot keyed by the strip ({@code key}: Step.Strip#key); the order re-plans without that band way.
     */
    public static void strip(String key, String why) {
        last = new Fact("source strip " + key, "strip at " + key + ": " + why);
    }

    /**
     * 0.39.z62 item 1b (run S 04:57:30-35): a strip that ended with no new block in its heading - blocked at its start (a
     * station Baritone will not break, a refused cell) - is a fact about that HEADING at that Y, not about the ores: the
     * source slot keyed by {@link io.github.mojolowjo.entropybot.aa.NeedChain#headingKey}; the order re-plans and every strip
     * way at that Y takes its next heading.
     */
    public static void stripBlocked(int y, String heading, String why) {
        last = new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.headingKey(y, heading),
                "strip " + heading + " at feet y " + y + " blocked at its start: " + why);
    }

    /** 0.39.z62: the source key a fact names itself (a blocked strip heading), null: the failed step's key applies. */
    public static String ownSourceKey(Fact f) {
        if (f != null && f.key().startsWith(WALK_TO)) return f.key().substring(WALK_TO.length());     // 0.39.z65 item 1
        String p = "source " + io.github.mojolowjo.entropybot.aa.NeedChain.STRIP_HEADING;
        return f != null && f.key().startsWith(p) ? f.key().substring("source ".length()) : null;
    }

    /** 0.39.z65: the key prefix of {@link #walkFor}. */
    static final String WALK_TO = "source walk to ";

    /**
     * 0.39.z65 item 1 (run V 07:15:22: "goto -261 75 21 near 2: couldn't get there (walk: Baritone stopped short)" stopped
     * the iron order - a walk's failure said no fact, so the run ended and the order parked): a walk that failed on its way
     * to the next step's source ({@code sourceKey}: that step's {@link io.github.mojolowjo.entropybot.aa.NeedChain#sourceKey},
     * e.g. "take at x y z") is that source's fact - unreachable from here, the order re-plans without it (rule 7).
     */
    public static Fact walkFor(String sourceKey, String why) {
        return new Fact(WALK_TO + sourceKey, "the walk to " + sourceKey + " failed: " + why);
    }

    /**
     * 0.39.z46 (run G 23:40:12): a step made no progress (no move, no step change) for the watch's time: the source slot
     * keyed by the step's target ({@code key}); the order re-plans instead of parking.
     */
    public static Fact noProgress(String key, String text) { return new Fact("source " + key, text); }

    /** The reason an unreachable source is dropped with ("unreachable (Baritone: Unable to find any path to ...)"). */
    public static String unreachableText(String why) { return "unreachable (Baritone: " + why + ")"; }

    /**
     * 0.39.z60 item 3 (run R 03:52:52: "place crafting_table near me: no cell within 3 blocks" stopped the iron order with no
     * re-plan): the fork's placeNear found no cell for {@code item} here (none free, none to clear) or its leg failed: the
     * "place" slot keyed by the item; the order re-plans on the facts of now (the same step failing again ends the run with
     * both reasons, Steps).
     */
    public static void placeFailed(String item, String why) {
        last = new Fact("place " + item, "place " + item.replaceFirst("^minecraft:", "") + " near me failed here: " + why);
    }

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
