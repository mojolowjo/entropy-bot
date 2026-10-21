package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Site;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AA_ROUND4A O (AA_ROUND4 A3): a station (crafting table, furnace, chest) as a priced option set, the ways:
 * <ul>
 * <li><b>use known</b>: one is known within {@code station.near} (48): walk there (its distance, or
 *     {@code station.nearGuess} when only "near" is sensed).</li>
 * <li><b>place from bag</b>: the item in the bag and a free cell (ChestSpot / the table cell): place 0.25 s.</li>
 * <li><b>craft + place</b>: the materials in the bag (table 4 planks, chest 8 planks, furnace 8 cobblestone; a log is 4
 *     planks): craft 0.5 s + place 0.25 s.</li>
 * <li><b>cut + craft</b> (table, chest): wood short: the wood set's best row (WoodOptions) prepended at its price.</li>
 * <li><b>gather + craft</b> (furnace): cobblestone short and a pickaxe: {@link CoalOptions#MINE_ORE} per block.</li>
 * <li><b>planner</b>: the planner's chain at its step cost x {@link Options#PLAN_UNIT}.</li>
 * </ul>
 * Every row not offered is dropped with its reason; a held row says what failed. Pure (JUnit: StationOptionsTest).
 * Loader notes: none (facts from {@code commands.NeedFacts}).
 */
public final class StationOptions {
    private StationOptions() {}

    public enum Kind {
        TABLE("crafting_table", "planks", 4), FURNACE("furnace", "cobblestone", 8), CHEST("chest", "planks", 8);
        public final String item, material;
        public final int amount;

        Kind(String item, String material, int amount) {
            this.item = item;
            this.material = material;
            this.amount = amount;
        }

        /** Blackboard fact key of this station's facts supplier. */
        public String factsKey() { return "station." + name().toLowerCase(Locale.ROOT) + ".facts"; }
    }

    /**
     * @param kind     which station
     * @param inBag    the station item in the bag
     * @param known    one is sensed near; {@code knownAt} its cell (null: only "near" known), {@code knownDist} blocks (-1 unknown)
     * @param planks   planks in the bag; {@code logs} logs (4 planks each); {@code cobble} cobblestone
     * @param pickTier best pickaxe (0 none)
     * @param cell     the free cell to place it at (ChestSpot / the table cell), null none found
     * @param wood     the wood set's best row (cut logs), null none; {@code woodWhy} the wood set's line
     * @param under    under ground ({@code depth} blocks); a wood or cobble gather climbs first
     */
    public record StationFacts(Kind kind, boolean inBag, boolean known, int[] knownAt, double knownDist, int planks, int logs, int cobble,
                               int pickTier, int[] cell, Options.Option wood, String woodWhy, boolean under, int depth) {}

    public static Options.Choice choose(StationFacts f, Options.OptionMemory m, long now, String planned, double plannedCost, String plannerWhy) {
        List<Options.Option> rows = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        Kind k = f.kind();
        String item = k.item;
        double near = Price.tune("station.near", 48);
        // 1. a known one
        if (!f.known()) dropped.add("use known " + item + ": none sensed near");
        else if (f.knownDist() > near) dropped.add("use known " + item + ": " + Price.r(f.knownDist()) + " away, over " + Price.r(near));
        else {
            double d = f.knownDist() >= 0 ? f.knownDist() : Price.tune("station.nearGuess", 16);
            String cmd = f.knownAt() == null ? Options.NOTHING : "goto " + RungOptions.xyz(f.knownAt());
            Options.Option o = Options.scored("use known " + item, "known", cmd, d,
                    "one " + (f.knownDist() >= 0 ? Price.r(d) + " away" : "near (distance not sensed, priced " + Price.r(d) + ")"), "known " + item, false, m);
            RungOptions.add(rows, dropped, m, now, f.knownAt() == null ? o : o.withSite(Site.of(f.knownAt())));
        }
        String place = f.cell() == null ? null : "place " + item + " " + RungOptions.xyz(f.cell());
        if (place == null) dropped.add("place " + item + ": no free cell");
        // 2. from the bag
        if (f.inBag()) {
            if (place != null) add(rows, dropped, m, now, Options.scored("place " + item, "place", place, Price.s(Price.placeS()),
                    "in the bag, place " + Price.placeS() + " s", "place " + item, false, m), f);
        } else dropped.add("place " + item + " from the bag: none in the bag");
        // 3. craft from the bag's materials
        int have = k == Kind.FURNACE ? f.cobble() : f.planks() + 4 * f.logs();
        int lack = Math.max(0, k.amount - have);
        double craftPlace = Price.s(Price.craftS() + Price.placeS());
        String craft = "craft " + item + " 1";
        if (!f.inBag() && place != null) {
            if (lack == 0) add(rows, dropped, m, now, Options.scored("craft " + item, "craft", craft + " then " + place, craftPlace,
                    have + " " + k.material + " (" + k.amount + " wanted), craft " + Price.craftS() + " s + place " + Price.placeS() + " s", "craft " + item, false, m), f);
            else if (k == Kind.FURNACE) {
                if (f.pickTier() < 1) dropped.add("gather + craft furnace: " + lack + " cobblestone short, no pickaxe");
                else {
                    add(rows, dropped, m, now, Options.scored("gather + craft furnace", "craft", "gather cobblestone " + k.amount + " then " + craft + " then " + place,
                            lack * CoalOptions.MINE_ORE + craftPlace, lack + " cobblestone short x " + Price.r(CoalOptions.MINE_ORE) + ", craft + place",
                            "gather craft furnace", false, m), f);
                }
            } else if (f.wood() == null) dropped.add("cut + craft " + item + ": " + lack + " planks short, no wood way (" + (f.woodWhy() == null ? "no wood facts" : f.woodWhy()) + ")");
            else {
                double climb = Price.climb(f.under(), f.depth());
                add(rows, dropped, m, now, Options.scored("cut + craft " + item, "craft", (f.under() ? AaLadder.CLIMB + " then " : "") + f.wood().command() + " then " + craft + " then " + place,
                        climb + f.wood().total() + craftPlace, lack + " planks short: " + f.wood().id() + " " + Price.r(f.wood().total())
                                + (f.under() ? ", climb " + Price.r(climb) : "") + ", craft + place", "cut craft " + item, false, m), f);
            }
        }
        // 4. the planner
        if (planned == null) dropped.add("planner: " + (plannerWhy == null ? "no chain" : plannerWhy));
        else RungOptions.add(rows, dropped, m, now, Options.scored("planner", "planner", planned, plannedCost * Options.PLAN_UNIT,
                "plan cost " + Price.r(plannedCost) + " x " + Price.r(Options.PLAN_UNIT), "planner " + item, true, m));
        return Options.Choice.of(rows, dropped, "no way to a " + item);
    }

    private static void add(List<Options.Option> rows, List<String> dropped, Options.OptionMemory m, long now, Options.Option o, StationFacts f) {
        RungOptions.add(rows, dropped, m, now, f.cell() == null ? o : o.withSite(Site.of(f.cell())));
    }

    /** A failed place or craft may be answered with the next way (never the owner's stop, a fight, a death). */
    public static boolean switchable(String end) { return end != null && !Options.ownerOrDanger(end); }

    public static final class Set implements Options.OptionSet<StationFacts> {
        @Override public Options.Choice choose(StationFacts f, Options.OptionMemory m, long now, String planned, double cost, String plannerWhy, String afterFail) {
            return StationOptions.choose(f, m, now, planned, cost, plannerWhy);
        }

        @Override public boolean switchable(String end) { return StationOptions.switchable(end); }
    }
}
