package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.Site;
import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AA_ROUND4A O, one-planner: a station (the crafting table, the furnace: the stations the game's recipe data names; the
 * camp chest) as a priced option set, the ways:
 * <ul>
 * <li><b>use known</b>: one is known within {@code station.near}: walk to its cell; sensed near with no cell: dropped
 *     (nothing to walk to).</li>
 * <li><b>place from bag</b>: the item in the bag: "place it near me" (the fork's placeNear picks the cell), 0.15 s.</li>
 * <li><b>chain + place</b>: the need's chain for the item ({@link NeedChain}: craft it from what is had or known, the
 *     ingredients recursing, else explore), then place it: the chain's price + 0.15 s.</li>
 * </ul>
 * Every row not offered is dropped with its reason; a held row says what failed. Pure (JUnit: StationOptionsTest).
 * Loader notes: none (facts from {@code commands.NeedFacts}).
 */
public final class StationOptions {
    private StationOptions() {}

    public enum Kind {
        TABLE, FURNACE, CHEST;

        /** The station's block item: the crafting and furnace stations from the game's recipe data; the camp chest. */
        public String item() {
            return switch (this) {
                case TABLE -> ItemWorth.stationOf(ItemWorth.CRAFT);
                case FURNACE -> ItemWorth.stationOf(ItemWorth.SMELT);
                case CHEST -> CHEST_ITEM;
            };
        }

        /** Blackboard fact key of this station's facts supplier. */
        public String factsKey() { return "station." + name().toLowerCase(Locale.ROOT) + ".facts"; }
    }

    /** The camp chest's item (the owner's camp rule names a chest; no recipe data names it). */
    public static final String CHEST_ITEM = "minecraft:chest";

    /**
     * @param kind     which station
     * @param inBag    the station item in the bag
     * @param known    one is sensed near; {@code knownAt} its cell (null: only "near" known), {@code knownDist} blocks (-1 unknown)
     * @param under    under ground ({@code depth} blocks)
     */
    public record StationFacts(Kind kind, boolean inBag, boolean known, int[] knownAt, double knownDist, boolean under, int depth) {}

    public static Options.Choice choose(StationFacts f, Options.OptionMemory m, long now, List<Step> planned, double plannedCost, String plannedWhy) {
        List<Options.Option> rows = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        Kind k = f.kind();
        String id = k.item();
        if (id == null) return Options.Choice.of(rows, List.of("the game's data names no " + k.name().toLowerCase(Locale.ROOT) + " station (no recipe data loaded)"), "no way to a station");
        String item = id.startsWith("minecraft:") ? id.substring(10) : id;
        double near = Price.tune("station.near");
        // 1. a known one
        if (!f.known()) dropped.add("use known " + item + ": none sensed near");
        else if (f.knownAt() == null) dropped.add("use known " + item + ": sensed near but its cell is not known (nothing to walk to)");
        else {
            double d = f.knownDist() >= 0 ? f.knownDist() : Price.tune("base.distGuess");
            int[] at = f.knownAt();
            if (d > near) dropped.add("use known " + item + ": " + Price.r(d) + " away, over " + Price.r(near));
            else Price.add(rows, dropped, m, now, Options.typed("use known " + item, "known", List.of(new Step.Walk(at[0], at[1], at[2], (int) io.github.mojolowjo.entropybot.aa.brain.Tuning.get("steps.walkNear"))), d,
                    "one " + Price.r(d) + " away" + (f.knownDist() >= 0 ? "" : " (distance not sensed, priced at base.distGuess)"), "known " + item, m)
                    .withSite(Site.of(at)));
        }
        // a placed chest is noted by a scan round me (the base chests fact reads the noted chests)
        List<Step> place = new ArrayList<>();
        place.add(new Step.PlaceNear(item));
        if (k == Kind.CHEST) place.add(new Step.Scan());
        // 2. from the bag
        if (f.inBag()) Price.add(rows, dropped, m, now, Options.typed("place " + item, "place", place, Price.s(Price.placeS()),
                "in the bag, place " + Price.placeS() + " s", "place " + item, m));
        else dropped.add("place " + item + " from the bag: none in the bag");
        // 3. the chain for the item, then place it
        if (f.inBag()) { /* the bag row stands */ }
        else if (planned == null) dropped.add("chain + place " + item + ": " + (plannedWhy == null ? "no chain" : plannedWhy));
        else {
            List<Step> all = new ArrayList<>(planned);
            all.addAll(place);
            Price.add(rows, dropped, m, now, Options.typed("chain + place " + item, "chain", all, plannedCost + Price.s(Price.placeS()),
                    (plannedWhy == null ? "the chain" : plannedWhy) + ", place " + Price.placeS() + " s", "chain " + item, true, m));
        }
        return Options.Choice.of(rows, dropped, "no way to a " + item);
    }

    /** A failed place or craft may be answered with the next way (never the owner's stop, a fight, a death). */
    public static boolean switchable(String end) { return end != null && !Options.ownerOrDanger(end); }

    public static final class Set implements Options.OptionSet<StationFacts> {
        @Override public Options.Choice choose(StationFacts f, Options.OptionMemory m, long now, List<Step> planned, double cost, String plannedWhy, String afterFail) {
            return StationOptions.choose(f, m, now, planned, cost, plannedWhy);
        }

        @Override public boolean switchable(String end) { return StationOptions.switchable(end); }
    }
}
