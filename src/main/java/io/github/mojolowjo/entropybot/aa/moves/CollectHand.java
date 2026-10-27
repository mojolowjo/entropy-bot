package io.github.mojolowjo.entropybot.aa.moves;

/**
 * 0.39.z78 vein (owner 2026-10-27 via the manager, "a vein is not a chop"; RULES 17): which of Baritone's processes a
 * cut or collect runs on, and the words its own end gives the step. A tree (logs) and a station take run the fork's chop;
 * ore runs the fork's vein - only blocks in view, from the priced spot (it takes no spot: the step walks there first, on
 * the priced goal; Baritone picks the stand); any other block set runs the stock mine under the run lease (mineWith),
 * which ENDS when it knows none of the block ("none in sight (N blocks of the feet)" / "none in range") instead of its
 * explore. A thin adapter: the process decides, the step only says why it ended (rule 7). Pure (JUnit: CollectHandTest).
 */
public final class CollectHand {
    private CollectHand() {}

    /** The process a cut / collect runs on; {@code name} is what the step's lines call it. */
    public enum Hand {
        CHOP("chop"), VEIN("vein"), MINE("mine");

        public final String name;

        Hand(String name) { this.name = name; }
    }

    /** {@code component}: a station take (the chop's component whatever the block); {@code logs} / {@code ores}: every block of the set is one. */
    public static Hand of(boolean component, boolean logs, boolean ores) {
        if (component || logs) return Hand.CHOP;
        return ores ? Hand.VEIN : Hand.MINE;
    }

    /** The mine's own end: it knew none of the block in sight / in range (the fork's IMineProcess.lastEnd under a run lease). */
    public static boolean noneKnown(String mineEnd) {
        return mineEnd != null && (mineEnd.startsWith("none in sight") || mineEnd.startsWith("none in range"));
    }

    /**
     * The collect at its priced spot whose mine ended knowing none of the block ({@link #noneKnown}): the step's failure
     * text (the words of the explore watch it replaces, with the process's own end), the spot's near-collect fact said
     * ({@link FactChange#nearCollect}); null: another end, or no spot priced (the caller says the end as it is).
     */
    public static String mineNoneKnown(Step.Collect spot, String mineEnd, int[] feet, String what, int got, int want) {
        if (spot == null || spot.at() == null || !noneKnown(mineEnd)) return null;
        boolean sight = mineEnd.startsWith("none in sight");
        String why = what + ": Baritone's mine knows no " + spot.item() + (sight ? " in its sight (" + Step.Collect.MINE_SIGHT + " blocks of the feet)" : " in range")
                + " and ended (its end: " + mineEnd + ") - the priced spot " + spot.spot() + " (" + Math.round(spot.dist(feet)) + " m) is not what it goes for, got " + got + " of " + want;
        FactChange.nearCollect(spot.item(), spot.at(), why);
        return why;
    }

    /**
     * The vein at the priced spot ended with none of it in view ("none in view", the fork's IVeinProcess.lastEnd) short of
     * the want: the step's failure text, the spot's near-collect fact said; null: another end or no spot (the chop's end
     * rules apply, ChopEnd).
     */
    public static String veinNoneInView(Step.Collect spot, String veinEnd, int[] feet, String what, int got, int want) {
        if (spot == null || spot.at() == null || veinEnd == null || !veinEnd.startsWith("none in view") || got >= want) return null;
        String why = what + ": Baritone's vein has no " + spot.item() + " in view at the priced spot " + spot.spot() + " (" + Math.round(spot.dist(feet))
                + " m from it; its end: " + veinEnd + "), got " + got + " of " + want;
        FactChange.nearCollect(spot.item(), spot.at(), why);
        return why;
    }

    /**
     * 0.39.z77 J2 E4, for the mine and (review of 4e5e57e1) the vein after its walk: Baritone's goal cells ({@code goals})
     * all more than {@code moved} (failures.moved) from the priced spot - it goes for other blocks, not the priced ones: the
     * step's failure text, the spot's near-collect fact said; null: no spot, no goal cell, or one near it (rule 15).
     */
    public static String targetAway(Step.Collect spot, java.util.List<int[]> goals, double moved, Hand hand, String what, int got, int want) {
        if (spot == null || !spot.targetAway(goals, moved)) return null;
        String why = what + ": Baritone's " + hand.name + " goes for blocks away from the priced spot " + spot.spot() + " (its " + goals.size() + " goal cell"
                + (goals.size() == 1 ? "" : "s") + " all more than failures.moved " + Math.round(moved) + " from it), got " + got + " of " + want;
        FactChange.nearCollect(spot.item(), spot.at(), why);
        return why;
    }

    /**
     * The walk to the priced spot before the vein ended with the feet not by it: the step's failure text, the spot's
     * near-collect fact said ({@code baritone}: Baritone's last word on the walk, null: none).
     */
    public static String veinWalkShort(Step.Collect spot, int[] feet, String what, String baritone) {
        String why = what + ": the walk to the priced spot " + spot.spot() + " before the vein ended " + Math.round(spot.dist(feet)) + " m from it"
                + (baritone == null || baritone.isEmpty() ? " (Baritone said nothing)" : " (" + baritone + ")") + ", got 0 of " + spot.n();
        FactChange.nearCollect(spot.item(), spot.at(), why);
        return why;
    }
}
