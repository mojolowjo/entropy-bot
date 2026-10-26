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

    /**
     * 0.39.z75 metric fix B: an explore's missing source became known ({@code text}: what was seen) - the order is priced
     * again where the explore runs, and the explore ends only when that price picks other steps (the planner's own test of
     * the source, Steps.reprice(true)); nothing is charged.
     */
    public static Fact found(String text) { return new Fact(PRICE + "source", text); }

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

    /**
     * 0.39.z65 review 1 (Opus Design): the chain's priced walk to the known station at {@code at} ("x y z") failed (or did not
     * start): a source fact keyed {@link io.github.mojolowjo.entropybot.aa.NeedChain#stationKey} - held for THIS order only
     * (Goals.sources; released on a new order or once the bot stands failures.moved blocks closer), the order re-plans with
     * the station out (the next known one or a new one placed here). Never the session-wide refusal of a use failure.
     */
    public static void stationWalk(String name, int x, int y, int z, String why) {
        last = new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.stationKey(x, y, z),
                name + " at " + x + " " + y + " " + z + " out for this order: " + why);
    }

    /**
     * 0.39.z68: the explore's walk up to the surface cell x y z failed (or did not start): a source fact keyed
     * {@link io.github.mojolowjo.entropybot.aa.NeedChain#surfaceKey}, held for this order; the order re-plans and the explore
     * runs from where the bot is (the chain prices no walk up while one is held).
     */
    public static void surface(int x, int y, int z, String why) {
        last = new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.surfaceKey(x, y, z),
                "the walk up to the surface at " + x + " " + y + " " + z + " out for this order: " + why);
    }

    /**
     * 0.39.z75 take (run AI 20:06:10: "take crafting_table at -34 70 -55: cluster 1 block: picked up 0 of 1" stopped the
     * order with no fact): a station take that ended without the station in the bag - the source slot keyed by the take's
     * own key ({@link io.github.mojolowjo.entropybot.aa.NeedChain#sourceKey}: "take at x y z"); the order re-plans without
     * that take (the walk to it, another station or a new one made and placed).
     */
    public static void take(int x, int y, int z, String why) {
        last = new Fact("source take at " + x + " " + y + " " + z, why);
    }

    /**
     * 0.39.z76 send-back 2 (run AP 00:07-00:17): a collect whose mine walked away from the spot the chain priced - that spot's
     * near-collect fact ({@link io.github.mojolowjo.entropybot.aa.NeedChain#nearKey}): the order prices the item's spots
     * within failures.moved of it out and re-plans.
     */
    public static void nearCollect(String item, int[] at, String why) {
        last = new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.nearKey(item, at), why);
    }

    /**
     * 0.39.z76 send-back 2 (rule 7; run AN 23:30:07: an NPE inside the explore ended the step "error: ..." with no fact, so
     * the chestplate order stopped): a throw inside a step is that step's fact - keyed by the mod's frame it came from
     * ("threw at Mining.exploreStep0:1228"), its text the exception and the frame - and the order re-plans on it. 0.39.z76 sb4
     * review 2: no count bounds it; the same step throwing at the same frame with the same needs in the bag again in this
     * order - from any cell, other tries between - parks (Steps' order tries), as does the same try twice from one cell.
     */
    public static Fact threwFact(Throwable e) {
        String w = io.github.mojolowjo.entropybot.Quiet.where(e);
        return new Fact("threw at " + w, "a throw inside the step: " + e + " at " + w);
    }

    /** 0.39.z76 send-back 2: a hand that caught a throw inside the running step says it ({@link #threwFact}), taken once. */
    public static void threw(Throwable e) { last = threwFact(e); }

    /**
     * 0.39.z76 send-back 3 (rule 22): the standing step stood its first-answer wait with the walks not known - the order is
     * priced again where the bot stands (a price fact, no cap counted), the walks still not known taken by Baritone's estimate.
     */
    public static void repriceAfterStand(String why) { last = priced(why); }

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

    /**
     * 0.39.z66 item 2 (run X 08:41:01): the step's blocks were reached and broken but dropped other items than the step
     * counts ({@code why}: the counts). The same source slot as {@link #unreachable}: the order re-plans without that
     * source, in the true words.
     */
    public static void otherDrops(String block, String at, String why) {
        last = new Fact("source " + block + " " + at, why);
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
        if (f != null && f.key().startsWith("source " + io.github.mojolowjo.entropybot.aa.NeedChain.PLACE_AT)) return f.key().substring("source ".length());   // 0.39.z67 item 1
        if (f != null && f.key().startsWith("source " + io.github.mojolowjo.entropybot.aa.NeedChain.NEAR)) return f.key().substring("source ".length());       // z75 review 2
        if (f != null && f.key().startsWith("source " + io.github.mojolowjo.entropybot.aa.NeedChain.EXPLORE_FROZEN)) return f.key().substring("source ".length());   // z76 send-back 2
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

    /**
     * 0.39.z67 item 1 (run Y 09:26:14 / 09:31:16: the re-plan offered the same "place near me" at the same sea-level spot and
     * the order stopped): the failure is a fact about WHERE it failed ({@code at}: the feet then; null: not known, the
     * plain fact above): a source slot keyed {@link io.github.mojolowjo.entropybot.aa.NeedChain#placeKey}, held for the
     * order; the re-plan places near a cell with room from facts instead, by its priced walk (NeedChain.roomWalk).
     */
    public static void placeFailed(String item, String why, int[] at) {
        if (at == null || at.length < 3) { placeFailed(item, why); return; }     // structural: x y z
        last = new Fact("source " + io.github.mojolowjo.entropybot.aa.NeedChain.placeKey(at[0], at[1], at[2]),
                "place " + item.replaceFirst("^minecraft:", "") + " near me failed at " + at[0] + " " + at[1] + " " + at[2] + ": " + why);
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
