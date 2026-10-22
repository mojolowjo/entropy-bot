package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.StationOptions.Kind;
import io.github.mojolowjo.entropybot.aa.StationOptions.StationFacts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND4A O: a station's ways priced; known beats craft beats cut; no way says every reason. */
class StationOptionsTest {
    static final int[] CELL = {1, 64, 0};
    static final Options.Option WOOD = Options.typed("cut 2 trees", "wood", java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.ChopTree(null, 2, null)), 40, "trees 10 away", "cut", new Options.OptionMemory());

    static StationFacts f(Kind k, boolean bag, boolean known, double dist, int planks, int logs, int cobble, int pick, Options.Option wood, boolean under) {
        return new StationFacts(k, bag, known, known ? new int[]{5, 64, 5} : null, dist, planks, logs, cobble, pick, wood, "no trees", under, under ? 10 : 0);
    }

    @Test
    void theStationSetOwnsTheChestAndScansIt() {
        // rm-areas: the camp chest chain is gone; the placed chest is noted by a scan (the base chests fact)
        Options.Choice c = StationOptions.choose(f(Kind.CHEST, true, false, -1, 0, 0, 0, 0, null, false), new Options.OptionMemory(), 0, null, 0, null);
        assertEquals("place chest near me > scan", c.best().command());
        assertTrue(c.best().typed(), "rm-execution: typed steps");
        Options.Choice t = StationOptions.choose(f(Kind.TABLE, true, false, -1, 0, 0, 0, 0, null, false), new Options.OptionMemory(), 0, null, 0, null);
        assertEquals("place crafting_table near me", t.best().command(), "only the chest scans");
    }

    @Test
    void materialsInTheBagCraftAndPlaceAtTheTimeModelsPrice() {
        Options.Choice c = StationOptions.choose(f(Kind.TABLE, false, false, -1, 4, 0, 0, 0, WOOD, false), new Options.OptionMemory(), 0, null, 0, "none");
        assertEquals("craft crafting_table 1 > place crafting_table near me", c.best().command());
        assertEquals(0.45 * 4.317, c.best().cost(), 1e-6);   // SCENARIOS: craft 0.3 s + place 0.15 s
        assertTrue(c.why().contains("craft 0.3 s + place 0.15 s"), c.why());
    }

    @Test
    void aKnownStationNearIsUsedAndAFarOneDropped() {
        Options.Choice near = StationOptions.choose(f(Kind.FURNACE, false, true, 20, 0, 0, 0, 1, null, false), new Options.OptionMemory(), 0, null, 0, null);
        assertEquals("goto 5 64 5 near 2", near.best().command());
        Options.Choice far = StationOptions.choose(f(Kind.FURNACE, false, true, 60, 0, 0, 8, 1, null, false), new Options.OptionMemory(), 0, null, 0, null);
        assertEquals("craft furnace 1 > place furnace near me", far.best().command());
        assertTrue(far.why().contains("60 away, over 48"), far.why());
    }

    @Test
    void woodShortPrependsTheWoodRowAndTheClimbUnderGround() {
        Options.Choice c = StationOptions.choose(f(Kind.CHEST, false, false, -1, 2, 0, 0, 0, WOOD, true), new Options.OptionMemory(), 0, null, 0, null);
        assertEquals("climb > cut 2 > craft chest 1 > place chest near me > scan", c.best().command());
        assertTrue(c.best().cost() > 40, "the wood row's price is in it");
        assertTrue(c.why().contains("climb"), c.why());
    }

    @Test
    void thePlannerIsARowAtPlanCost() {
        Options.Choice c = StationOptions.choose(f(Kind.TABLE, false, false, -1, 0, 0, 0, 0, null, false), new Options.OptionMemory(), 0, io.github.mojolowjo.entropybot.aa.TS.plan("crafting_table"), 30, null);
        assertEquals("plan crafting_table", c.best().command());
        assertEquals(150, c.best().cost(), 1e-6);
        assertTrue(c.best().needsPlanner());
    }

    @Test
    void noWayGivesEveryReason() {
        Options.Choice c = StationOptions.choose(f(Kind.FURNACE, false, false, -1, 0, 0, 0, 0, null, false), new Options.OptionMemory(), 0, null, 0, "no recipe");
        assertNull(c.best());
        assertTrue(c.why().startsWith("no way to a furnace"), c.why());
        assertTrue(c.why().contains("planner: no recipe"), c.why());
    }

    @Test
    void aHeldRowIsDroppedWithItsReason() {
        Options.OptionMemory m = new Options.OptionMemory();
        m.failed("make crafting_table", "the game refused placing", 0);
        Options.Choice c = StationOptions.choose(f(Kind.TABLE, false, false, -1, 4, 0, 0, 0, null, false), m, 1000, io.github.mojolowjo.entropybot.aa.TS.plan("crafting_table"), 30, null);
        assertEquals("planner", c.best().id());
        assertTrue(c.why().contains("the game refused placing"), c.why());
    }

    @Test
    void aKnownStationWithNoCellIsDroppedNotAWalkToNowhere() {
        StationFacts nf = new StationFacts(Kind.TABLE, false, true, null, -1, 0, 0, 0, 0, null, null, false, 0);
        Options.Choice c = StationOptions.choose(nf, new Options.OptionMemory(), 0, null, 0, null);
        assertNull(c.best());
        assertTrue(c.why().contains("its cell is not known"), c.why());
    }

    @Test
    void aWoodWayWithoutTypedStepsIsDroppedWithItsReason() {
        // rm-execution: a wood way that is chain text (not typed steps) is never prepended
        // rm-execution2: an option is typed steps or it cannot exist (the sentinels only: nothing, the planner's wait)
        assertThrows(IllegalArgumentException.class, () -> new Options.Option("cut", "wood", "chop 3", 10, "x", "cut", false, null, null, java.util.List.of()));
        Options.Option nothing = Options.Option.sentinel("nothing", "nothing", Options.NOTHING, 0, "x", "nothing");
        Options.Choice c = StationOptions.choose(f(Kind.TABLE, false, false, -1, 0, 0, 0, 0, nothing, false), new Options.OptionMemory(), 0, null, 0, null);
        assertNull(c.best());
        assertTrue(c.why().contains("the wood way nothing has no typed steps"), c.why());
    }

    @Test
    void everyChainTheFourSetsEmitParses() {
        java.util.List<Options.Choice> all = new java.util.ArrayList<>();
        for (Kind k : Kind.values()) {
            all.add(StationOptions.choose(f(k, false, true, 5, 8, 0, 8, 1, WOOD, true), new Options.OptionMemory(), 0, io.github.mojolowjo.entropybot.aa.TS.plan("table"), 3, null));
            all.add(StationOptions.choose(f(k, false, false, -1, 0, 0, 0, 1, WOOD, true), new Options.OptionMemory(), 0, null, 0, null));
            all.add(StationOptions.choose(f(k, true, false, -1, 0, 0, 0, 1, WOOD, false), new Options.OptionMemory(), 0, null, 0, null));
        }
        all.add(BaseOptions.choose(new BaseOptions.BaseFacts("home", null, 20, true, 1, true, 5, true), new Options.OptionMemory(), 0));
        all.add(BagOptions.choose(new BagOptions.BagFacts(0, 1, true, 5, CELL, false, 8, 0, CELL, 1, java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.DropJunk("dirt", 3))), new Options.OptionMemory(), 0));
        all.add(BagOptions.choose(new BagOptions.BagFacts(0, 1, false, -1, null, false, 8, 0, CELL, 0, java.util.List.<io.github.mojolowjo.entropybot.aa.moves.Step.DropJunk>of()), new Options.OptionMemory(), 0));
        all.add(BedOptions.choose(new BedOptions.BedFacts(0, 2, new int[]{9, 9, 30, 50}, CELL, true, true, true, 4), new Options.OptionMemory(), 0));
        all.add(BedOptions.choose(new BedOptions.BedFacts(3, 0, null, CELL), new Options.OptionMemory(), 0));
        int rows = 0;
        for (Options.Choice c : all) {
            for (Options.Option o : c.ranked()) {
                rows++;
                // rm-execution2: every row is typed steps (its label never parsed), each with a verb the game side runs
                assertTrue(o.typed(), o.command());
                for (var s : o.steps())
                    if (!(s instanceof io.github.mojolowjo.entropybot.aa.moves.Step.Plan))
                        assertNotNull(io.github.mojolowjo.entropybot.commands.VerbTable.of(io.github.mojolowjo.entropybot.commands.StepCommands.command(s).split("\\s+")[0]), s.text());
            }
            for (String d : c.dropped()) assertFalse(d.contains("no verb"), d);
        }
        assertTrue(rows >= 20, "rows checked: " + rows);
    }
}