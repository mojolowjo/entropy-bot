package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.Failures;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.StationHolds;
import io.github.mojolowjo.entropybot.aa.brain.StripOrder;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.StationReach;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.guard.BreakPolicy;
import io.github.mojolowjo.entropybot.guard.Policy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J5 slice 1 (to-do/j5-slice1-stations-holds.md; audit D1, D2, D5, D6, D8, B1, B3): one station store
 * (StationHolds) read by every reader, "station near" = the lookup alone, a walk not needed holds nothing, and one break
 * authority for the strip's start (BreakPolicy over blocksToAvoidBreaking). The scenarios: run DU 16:29:01-05 (DU-2: the bot
 * one block from its own table held it and placed a second one), run DT 16:19:41 (the furnace two blocks above the feet),
 * run DU 16:31:55-58 (DU-3: its own band table blocked the strip's south heading).
 */
class J5StationHoldsTest {
    static final String OW = "minecraft:overworld";
    /** Vanilla's default block interaction range (Player#blockInteractionRange, attribute default). */
    static final double RANGE = 4.5;
    /** Vanilla's standing eye height. */
    static final double EYE = 1.62;

    @BeforeEach
    void fresh() { StationHolds.INSTANCE.reset(); }

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        StationHolds.INSTANCE.reset();
    }

    static double[] eye(int[] feet) { return new double[]{feet[0] + 0.5, feet[1] + EYE, feet[2] + 0.5}; }

    // ---- DU-2: one block from its own table ----

    /** Run DU 16:29:01.344 "stopped 1 blocks away": the table in reach - the walk ends done, walled in or not, nothing held. */
    @Test
    void du2_aTableOneBlockAwayIsInReach_walledInOrNot() {
        int[] feet = {0, 64, 0};
        assertTrue(StationReach.inReach(eye(feet), new int[]{1, 64, 0}, RANGE), "next to it");
        assertTrue(StationReach.inReach(eye(feet), new int[]{2, 64, 0}, RANGE), "one block between (the walled-in table): reach is distance, the walk is not needed");
        assertTrue(StationReach.inReach(eye(feet), new int[]{1, 63, 1}, RANGE), "below, diagonal");
        assertFalse(StationReach.inReach(eye(feet), new int[]{6, 64, 0}, RANGE), "six blocks off: a walk");
        assertFalse(StationReach.inReach(null, new int[]{1, 64, 0}, RANGE), "no eye: not known, not in reach");
        assertTrue(StationHolds.INSTANCE.lines(feet, null).isEmpty(), "a walk not needed holds nothing");
    }

    /** DU-2: the table one block off is the "table near" fact and the chain uses it - no second table made or placed. */
    @Test
    void du2_theTableNearIsUsed_noSecondTable() {
        ScenariosTest.world("wooden", "stone");
        Spot table = new Spot(1, 64, 0, 1, "minecraft:crafting_table");
        assertTrue(StationFact.near(new StationFact.Known(List.of(table))), "the lookup knows it: table near");
        assertFalse(StationFact.near(new StationFact.None(StationFact.REFUSED_ALL)), "every one held: not near");
        World w = FakePaths.paths(new World(Map.of("minecraft:cobblestone", 3, "minecraft:stick", 2, "minecraft:oak_log", 1), true, k -> null))
                .withStations(k -> ItemWorth.CRAFT.equals(k) ? table : null);
        Chain c = NeedChain.resolve("stone_pickaxe", 1, w);
        assertTrue(c.ok() && !c.explore(), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.PlaceNear || s.equals(new Step.Craft("crafting_table", 1))), c.steps().toString());
    }

    /** DU-2: a held table is said held, with its order and reason, by the stations line, aa why and the chain's dropped list. */
    @Test
    void du2_aHeldTableIsSaidHeldForItsOrderEverywhere() {
        ScenariosTest.world("wooden", "stone");
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());
        bb.fact(NeedChain.WORLD_FACT, (Supplier<World>) Z65RunVTest::farTableWorld);
        bb.in.pos = new int[]{-261, 16, 22};
        Goals.Goal g = Goals.draft(DebugTreeTest.item("stone_pickaxe"), 1, 0, clock.now());
        assertTrue(Z65RunVTest.walksToTable(Goals.chain(bb, g)), "first: the walk to the known table");

        String why = "the walk to it ended short: couldn't get there";
        StationHolds.INSTANCE.walkFailed("minecraft:stone_pickaxe", -261, 75, 21, why, bb.in.pos);
        String said = "held for minecraft:stone_pickaxe: " + why;
        // the chain reads the one store (no source of the order's own, no world fact)
        assertFalse(Z65RunVTest.walksToTable(Goals.chain(bb, g)), "the chain prices without it");
        assertEquals(Map.of("station at -261 75 21", said), Goals.stationHolds(bb));
        // the stations line's part and aa why
        Spot sp = new Spot(-261, 75, 21, 60, "minecraft:crafting_table");
        assertEquals(" | held: -261 75 21 " + said, StationHolds.INSTANCE.note(List.of(sp), bb.in.pos, null));
        assertEquals(List.of("station at -261 75 21: " + said), StationHolds.INSTANCE.lines(bb.in.pos, null));
        assertEquals("", StationHolds.INSTANCE.note(List.of(new Spot(5, 64, 5, 3, "minecraft:crafting_table")), bb.in.pos, null), "another table: not held");
        // the no-way line lists it
        assertTrue(Goals.noWayLeft(g, "x").contains("station at -261 75 21: " + said), Goals.noWayLeft(g, "x"));
    }

    /**
     * The walk hold's band, by the distance from where the walk failed (WorldFacts.refresh's band): back past failures.moved
     * + 8 away, held again within failures.moved (no flip at one edge). Toward the station counts as away too.
     */
    @Test
    void aWalkHoldLiftsByDistanceWithTheBand() {
        double m = Failures.movedV();
        int[] from = {0, 10, 0};
        StationHolds.INSTANCE.walkFailed("o", 0, 70, 0, "short", from);
        assertNotNull(StationHolds.INSTANCE.held(0, 70, 0, new int[]{0, (int) (10 + m + 4), 0}, null), "inside the band: still held");
        assertNull(StationHolds.INSTANCE.held(0, 70, 0, new int[]{0, (int) (10 + m + 9), 0}, null), "past failures.moved + 8 away: back");
        assertNull(StationHolds.INSTANCE.held(0, 70, 0, new int[]{0, (int) (10 + m + 4), 0}, null), "back inside the band: stays back");
        assertNotNull(StationHolds.INSTANCE.held(0, 70, 0, new int[]{0, (int) (10 + m - 1), 0}, null), "within failures.moved: held again");
        assertNotNull(StationHolds.INSTANCE.held(0, 70, 0, null, null), "feet not known: held (rule 15)");
    }

    /**
     * j5s1 review (run DU: the walk failed 1-2 blocks from its table): a hold learned 2 blocks from the table lifts once the
     * bot moves the band away from there and comes back within failures.moved of it - never held for good (the old gain
     * toward the station could never pass the band from 2 blocks off).
     */
    @Test
    void aHoldLearnedTwoBlocksFromItsTableLiftsAwayAndComesBackWithin() {
        double m = Failures.movedV();
        int[] table = {100, 64, 100}, from = {102, 64, 100};
        StationHolds.INSTANCE.walkFailed("minecraft:stone_pickaxe", table[0], table[1], table[2], "the walk to it ended short: stopped 2 blocks away", from);
        assertNotNull(StationHolds.INSTANCE.held(100, 64, 100, from, null), "where it failed: held");
        int[] away = {(int) (102 + m + 9), 64, 100};
        assertNull(StationHolds.INSTANCE.held(100, 64, 100, away, null), "past the band away: lifted");
        assertTrue(StationHolds.INSTANCE.lines(away, null).isEmpty(), "aa why says nothing held");
        assertNotNull(StationHolds.INSTANCE.held(100, 64, 100, new int[]{(int) (102 + m - 1), 64, 100}, null), "back within failures.moved: held again");
        assertNull(StationHolds.INSTANCE.held(100, 64, 100, away, null), "and lifted again past the band");
    }

    /** The craft's refusals live in the one store, keyed by the block: a re-placed block is another station. */
    @Test
    void aRefusalHoldsWhileItsBlockStands() {
        StationHolds.INSTANCE.refused(3, 64, 3, "minecraft:crafting_table", "it answered clicks but never opened");
        Function<int[], String> same = c -> "minecraft:crafting_table", unknown = c -> null, other = c -> "minecraft:air";
        assertEquals("refused for every order: it answered clicks but never opened", StationHolds.INSTANCE.held(3, 64, 3, null, same));
        assertNotNull(StationHolds.INSTANCE.held(3, 64, 3, null, unknown), "not loaded: held (rule 15)");
        assertNull(StationHolds.INSTANCE.held(3, 64, 3, null, other), "the block changed: gone");
        assertNull(StationHolds.INSTANCE.held(3, 64, 3, null, same), "and stays gone");
    }

    // ---- DT: the furnace two blocks above the feet ----

    /** Run DT 16:19:41: feet 6 16 7, the furnace at 6 18 7 - in reach, the walk is done, nothing held. */
    @Test
    void dt_theFurnaceAboveTheHeadIsInReach() {
        int[] feet = {6, 16, 7};
        assertTrue(StationReach.inReach(eye(feet), new int[]{6, 18, 7}, RANGE));
        assertTrue(StationHolds.INSTANCE.lines(feet, null).isEmpty());
        assertTrue(StationFact.near(new StationFact.Known(List.of(new Spot(6, 18, 7, 2, "minecraft:furnace")))), "furnace near");
    }

    // ---- DU-3 (1b): one break authority at the strip's start ----

    /** Run DU 16:31:55-58: feet -373 16 351, its own table at -373 16 352 (south): the heading is open now. */
    @Test
    void du3_ownBandTableNoLongerBlocksTheStripHeading() {
        int[] feet = {-373, 16, 351}, table = {-373, 16, 352};
        Function<int[], String> own = c -> java.util.Arrays.equals(c, table)
                ? StripOrder.avoidedRefused("crafting_table", c, BreakPolicy.structure(Policy.EMPTY, OW, c[0], c[1], c[2], BreakPolicy.Structure.YES, true))
                : null;
        assertNull(StripOrder.startBlocked(feet, "south", own), "placed by me: BreakPolicy allows it, the heading is open");

        Function<int[], String> notMine = c -> java.util.Arrays.equals(c, table)
                ? StripOrder.avoidedRefused("crafting_table", c, BreakPolicy.structure(Policy.EMPTY, OW, c[0], c[1], c[2], BreakPolicy.Structure.YES, false))
                : null;
        String why = StripOrder.startBlocked(feet, "south", notMine);
        assertNotNull(why, "a station not mine outside the areas: refused, blocked");
        assertTrue(why.startsWith("crafting_table at -373 16 352, which the break policy refuses: "), why);

        Policy keep = Policy.parse("{\"areas\":[],\"protect\":[{\"name\":\"camp\",\"x1\":-380,\"y1\":0,\"z1\":340,\"x2\":-360,\"y2\":40,\"z2\":360}]}");
        Function<int[], String> kept = c -> java.util.Arrays.equals(c, table)
                ? StripOrder.avoidedRefused("crafting_table", c, BreakPolicy.structure(keep, OW, c[0], c[1], c[2], BreakPolicy.Structure.YES, true))
                : null;
        assertNotNull(StripOrder.startBlocked(feet, "south", kept), "a protect box stays absolute, placed by me or not");

        assertNull(StripOrder.avoidedRefused("crafting_table", table, BreakPolicy.structure(Policy.EMPTY, OW, table[0], table[1], table[2], BreakPolicy.Structure.NOT_LOADED, true)),
                "its structure fact not loaded: not known to be blocked (rule 15)");
        // the start's own answer (AaRuntime.startBlocked -> StripOrder.breakRefused): the bot's own avoided table, no box: open
        assertNull(StripOrder.breakRefused("crafting_table", table, Policy.EMPTY, OW, true, BreakPolicy.Structure.YES, true));
        assertNull(StripOrder.breakRefused("stone", table, Policy.EMPTY, OW, false, null, false), "a plain block, no box: open");
    }

    /**
     * j5s1 re-review (rule 16): keep-out / no-break block the strip start whatever the structure fact - BreakPolicy.structure
     * answers "not a structure" (structure NO) before its box check, so the box is asked first.
     */
    @Test
    void du3_aTableInAKeepOutOrNoBreakBoxBlocksTheStartWhateverItsStructureFact() {
        int[] feet = {-373, 16, 351}, table = {-373, 16, 352};
        Policy keepOut = Policy.parse("{\"areas\":[{\"name\":\"vault\",\"type\":\"keep-out\",\"x1\":-380,\"y1\":0,\"z1\":340,\"x2\":-360,\"y2\":40,\"z2\":360}],\"protect\":[]}");
        Policy noBreak = Policy.parse("{\"areas\":[{\"name\":\"shrine\",\"type\":\"no-break\",\"x1\":-380,\"y1\":0,\"z1\":340,\"x2\":-360,\"y2\":40,\"z2\":360}],\"protect\":[]}");
        for (Policy p : List.of(keepOut, noBreak)) {
            Function<int[], String> cell = c -> java.util.Arrays.equals(c, table)
                    ? StripOrder.breakRefused("crafting_table", c, p, OW, true, BreakPolicy.Structure.NO, true)
                    : null;
            String why = StripOrder.startBlocked(feet, "south", cell);
            assertNotNull(why, "the bot's own table (structure NO) in a box: blocked at the start");
            assertTrue(why.startsWith("crafting_table at -373 16 352, in ") && why.endsWith("): never broken there"), why);
            assertNotNull(StripOrder.breakRefused("stone", table, p, OW, false, null, false), "any block in the box: blocked");
        }
    }
}
