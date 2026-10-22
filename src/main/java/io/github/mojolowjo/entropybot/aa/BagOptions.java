package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Site;
import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.ArrayList;
import java.util.List;

/**
 * AA_ROUND4A O: a full bag as a priced option set, the ways:
 * <ul>
 * <li><b>deposit</b>: base chests known: walk there and back (2 x the base distance) + {@code bag.depositS}.</li>
 * <li><b>chest here</b>: no base chests: a chest (in the bag, or crafted from 8 planks) placed near me, marked
 *     base, scanned, then the deposit: craft 0.5 s + place 0.25 s + {@code bag.depositS}.</li>
 * <li><b>drop junk</b>: junk (the junk list) in the bag: {@code bag.dropS}, one {@code drop <item> <n>} step per junk item; frees only the junk's slots, so it is priced
 *     per slot it fails to free against the slots wanted.</li>
 * </ul>
 * Pure (JUnit: BagOptionsTest). Loader notes: none (facts from {@code commands.NeedFacts}).
 */
public final class BagOptions {
    private BagOptions() {}

    public static final String FACTS = "bag.facts";

    /**
     * @param freeSlots   free slots now; {@code want} slots the need wants free
     * @param baseChests  base chests known; {@code baseDist} blocks to the base (-1 unknown); {@code base} its cell
     * @param chestInBag  a chest item in the bag; {@code planks} planks (+ 4 per log in {@code logs})
         * @param me          the bot's cell (the base mark of a chest here)
     * @param junkSlots   slots the junk items fill; {@code junkDrops} the drop steps (typed: item and count), one per junk item
     */
    public record BagFacts(int freeSlots, int want, boolean baseChests, double baseDist, int[] base, boolean chestInBag, int planks, int logs,
                           int[] me, int junkSlots, List<Step.DropJunk> junkDrops) {}

    public static Options.Choice choose(BagFacts f, Options.OptionMemory m, long now) {
        List<Options.Option> rows = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        double dep = Price.tune("bag.depositS");
        // 1. the base chests
        if (!f.baseChests()) dropped.add("deposit: no base chests");
        else {
            double d = f.baseDist() >= 0 ? f.baseDist() : Price.tune("base.distGuess");
            Options.Option o = Options.typed("deposit", "deposit", List.of(new Step.Deposit()), 2 * d + Price.s(dep),
                    "base " + (f.baseDist() >= 0 ? Price.r(d) + " away" : "distance not known, priced " + Price.r(d)) + ", there and back + " + Price.r(dep) + " s", "deposit", m);
            Price.add(rows, dropped, m, now, f.base() == null ? o : o.withSite(Site.of(f.base())));
        }
        // 2. a chest here (no base chests)
        int planks = f.planks() + 4 * f.logs();  // game fact: a log makes 4 planks
        if (f.baseChests()) dropped.add("chest here: base chests known");
        else if (f.me() == null) dropped.add("chest here: my cell is not known");
        else if (!f.chestInBag() && planks < 8) dropped.add("chest here: " + planks + " planks, 8 wanted");  // game fact: a chest takes 8 planks
        else {
            double s = (f.chestInBag() ? 0 : Price.craftS()) + Price.placeS() + dep;
            List<Step> st = new java.util.ArrayList<>();
            if (!f.chestInBag()) st.add(new Step.Craft("chest", 1));
            st.add(new Step.PlaceNear("chest"));
            st.add(new Step.MarkBase(f.me()[0], f.me()[1], f.me()[2]));
            st.add(new Step.Scan(true));
            st.add(new Step.Deposit());
            Price.add(rows, dropped, m, now, Options.typed("chest here", "chest", st, Price.s(s),
                    (f.chestInBag() ? "a chest in the bag" : planks + " planks, craft " + Price.craftS() + " s") + ", place " + Price.placeS() + " s, deposit " + Price.r(dep) + " s",
                    "chest here", m));
        }
        // 3. drop the junk
        if (f.junkSlots() <= 0 || f.junkDrops() == null || f.junkDrops().isEmpty()) dropped.add("drop junk: no junk in the bag");
        else {
            double s = Price.tune("bag.dropS");
            int shortBy = Math.max(0, f.want() - f.freeSlots() - f.junkSlots());
            // a slot still wanted after the drop costs a deposit's time (the need is not met by the drop alone)
            double cost = Price.s(s + shortBy * dep);
            Price.add(rows, dropped, m, now, Options.typed("drop junk", "drop", List.copyOf(f.junkDrops()), cost,
                    f.junkSlots() + " junk slots, drop " + Price.r(s) + " s" + (shortBy > 0 ? ", still " + shortBy + " short x " + Price.r(dep) + " s" : ""), "drop junk", m));
        }
        return Options.Choice.of(rows, dropped, "no way to free the bag");
    }

    public static boolean switchable(String end) { return end != null && !Options.ownerOrDanger(end); }

    public static final class Set implements Options.OptionSet<BagFacts> {
        @Override public Options.Choice choose(BagFacts f, Options.OptionMemory m, long now, List<Step> planned, double cost, String plannerWhy, String afterFail) {
            return BagOptions.choose(f, m, now);
        }

        @Override public boolean switchable(String end) { return BagOptions.switchable(end); }
    }
}
