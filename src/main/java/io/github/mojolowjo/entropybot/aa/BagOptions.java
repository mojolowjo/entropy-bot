package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Site;

import java.util.ArrayList;
import java.util.List;

/**
 * AA_ROUND4A O: a full bag as a priced option set, the ways:
 * <ul>
 * <li><b>deposit</b>: base chests known: walk there and back (2 x the base distance) + {@code bag.depositS}.</li>
 * <li><b>chest here</b>: no base chests: a chest (in the bag, or crafted from 8 planks) placed at the free cell, marked
 *     base, scanned, then the deposit: craft 0.5 s + place 0.25 s + {@code bag.depositS}.</li>
 * <li><b>drop junk</b>: junk (the junk list) in the bag: {@code bag.dropS}; frees only the junk's slots, so it is priced
 *     per slot it fails to free against the slots wanted.</li>
 * </ul>
 * Pure (JUnit: BagOptionsTest). Loader notes: none (facts from {@code commands.NeedFacts}).
 */
public final class BagOptions {
    private BagOptions() {}

    public static final String FACTS = "bag.facts";
    /** The chain that drops the junk list's items (CampCommands' junk drop; see the report: verb to confirm). */
    public static final String DROP_JUNK = "drop junk";

    /**
     * @param freeSlots   free slots now; {@code want} slots the need wants free
     * @param baseChests  base chests known; {@code baseDist} blocks to the base (-1 unknown); {@code base} its cell
     * @param chestInBag  a chest item in the bag; {@code planks} planks (+ 4 per log in {@code logs})
     * @param cell        the free cell for a chest here (ChestSpot), null none
     * @param me          the bot's cell (the base mark of a chest here)
     * @param junkSlots   slots the junk list's items fill
     */
    public record BagFacts(int freeSlots, int want, boolean baseChests, double baseDist, int[] base, boolean chestInBag, int planks, int logs,
                           int[] cell, int[] me, int junkSlots) {}

    public static Options.Choice choose(BagFacts f, Options.OptionMemory m, long now) {
        List<Options.Option> rows = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        double dep = Price.tune("bag.depositS", 5);
        // 1. the base chests
        if (!f.baseChests()) dropped.add("deposit: no base chests");
        else {
            double d = f.baseDist() >= 0 ? f.baseDist() : Price.tune("station.nearGuess", 16);
            Options.Option o = Options.scored("deposit", "deposit", "deposit", 2 * d + Price.s(dep),
                    "base " + (f.baseDist() >= 0 ? Price.r(d) + " away" : "distance not known, priced " + Price.r(d)) + ", there and back + " + Price.r(dep) + " s", "deposit", false, m);
            RungOptions.add(rows, dropped, m, now, f.base() == null ? o : o.withSite(Site.of(f.base())));
        }
        // 2. a chest here (no base chests)
        int planks = f.planks() + 4 * f.logs();
        if (f.baseChests()) dropped.add("chest here: base chests known");
        else if (f.cell() == null || f.me() == null) dropped.add("chest here: no free cell");
        else if (!f.chestInBag() && planks < 8) dropped.add("chest here: " + planks + " planks, 8 wanted");
        else {
            String c = RungOptions.xyz(f.cell()), me = RungOptions.xyz(f.me());
            double s = (f.chestInBag() ? 0 : Price.craftS()) + Price.placeS() + dep;
            RungOptions.add(rows, dropped, m, now, Options.scored("chest here", "chest", (f.chestInBag() ? "" : "craft chest 1 then ") + "place chest " + c
                            + " then place base " + me + " then scan base then deposit", Price.s(s),
                    (f.chestInBag() ? "a chest in the bag" : planks + " planks, craft " + Price.craftS() + " s") + ", place " + Price.placeS() + " s, deposit " + Price.r(dep) + " s",
                    "chest here", false, m).withSite(Site.of(f.cell())));
        }
        // 3. drop the junk
        if (f.junkSlots() <= 0) dropped.add("drop junk: no junk in the bag");
        else {
            double s = Price.tune("bag.dropS", 2);
            int shortBy = Math.max(0, f.want() - f.freeSlots() - f.junkSlots());
            // a slot still wanted after the drop costs a deposit's time (the need is not met by the drop alone)
            double cost = Price.s(s + shortBy * dep);
            RungOptions.add(rows, dropped, m, now, Options.scored("drop junk", "drop", DROP_JUNK, cost,
                    f.junkSlots() + " junk slots, drop " + Price.r(s) + " s" + (shortBy > 0 ? ", still " + shortBy + " short x " + Price.r(dep) + " s" : ""), "drop junk", false, m));
        }
        return Options.Choice.of(rows, dropped, "no way to free the bag");
    }

    public static boolean switchable(String end) { return end != null && !Options.ownerOrDanger(end); }

    public static final class Set implements Options.OptionSet<BagFacts> {
        @Override public Options.Choice choose(BagFacts f, Options.OptionMemory m, long now, String planned, double cost, String plannerWhy, String afterFail) {
            return BagOptions.choose(f, m, now);
        }

        @Override public boolean switchable(String end) { return BagOptions.switchable(end); }
    }
}
