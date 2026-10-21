package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.StationOptions.Kind;
import io.github.mojolowjo.entropybot.aa.StationOptions.StationFacts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND4A O: a station's ways priced; known beats craft beats cut; no way says every reason. */
class StationOptionsTest {
    static final int[] CELL = {1, 64, 0};
    static final Options.Option WOOD = new Options.Option("cut 2 trees", "wood", "cut 2", 40, "trees 10 away", "cut", false);

    static StationFacts f(Kind k, boolean bag, boolean known, double dist, int planks, int logs, int cobble, int pick, int[] cell, Options.Option wood, boolean under) {
        return new StationFacts(k, bag, known, known ? new int[]{5, 64, 5} : null, dist, planks, logs, cobble, pick, cell, wood, "no trees", under, under ? 10 : 0);
    }

    @Test
    void materialsInTheBagCraftAndPlaceAtTheTimeModelsPrice() {
        Options.Choice c = StationOptions.choose(f(Kind.TABLE, false, false, -1, 4, 0, 0, 0, CELL, WOOD, false), new Options.OptionMemory(), 0, null, 0, "none");
        assertEquals("craft crafting_table 1 then place crafting_table 1 64 0", c.best().command());
        assertEquals(0.75 * 4.317, c.best().cost(), 1e-6);
        assertTrue(c.why().contains("craft 0.5 s + place 0.25 s"), c.why());
        assertNotNull(c.best().site());
    }

    @Test
    void aKnownStationNearIsUsedAndAFarOneDropped() {
        Options.Choice near = StationOptions.choose(f(Kind.FURNACE, false, true, 20, 0, 0, 0, 1, CELL, null, false), new Options.OptionMemory(), 0, null, 0, null);
        assertEquals("goto 5 64 5", near.best().command());
        Options.Choice far = StationOptions.choose(f(Kind.FURNACE, false, true, 60, 0, 0, 8, 1, CELL, null, false), new Options.OptionMemory(), 0, null, 0, null);
        assertEquals("craft furnace 1 then place furnace 1 64 0", far.best().command());
        assertTrue(far.why().contains("60 away, over 48"), far.why());
    }

    @Test
    void woodShortPrependsTheWoodRowAndTheClimbUnderGround() {
        Options.Choice c = StationOptions.choose(f(Kind.CHEST, false, false, -1, 2, 0, 0, 0, CELL, WOOD, true), new Options.OptionMemory(), 0, null, 0, null);
        assertEquals("climb then cut 2 then craft chest 1 then place chest 1 64 0", c.best().command());
        assertTrue(c.best().cost() > 40, "the wood row's price is in it");
        assertTrue(c.why().contains("climb"), c.why());
    }

    @Test
    void thePlannerIsARowAtPlanCost() {
        Options.Choice c = StationOptions.choose(f(Kind.TABLE, false, false, -1, 0, 0, 0, 0, CELL, null, false), new Options.OptionMemory(), 0, "goal crafting_table", 30, null);
        assertEquals("goal crafting_table", c.best().command());
        assertEquals(150, c.best().cost(), 1e-6);
        assertTrue(c.best().needsPlanner());
    }

    @Test
    void noWayGivesEveryReason() {
        Options.Choice c = StationOptions.choose(f(Kind.FURNACE, false, false, -1, 0, 0, 0, 0, null, null, false), new Options.OptionMemory(), 0, null, 0, "no recipe");
        assertNull(c.best());
        assertTrue(c.why().startsWith("no way to a furnace"), c.why());
        assertTrue(c.why().contains("no free cell") && c.why().contains("planner: no recipe"), c.why());
    }

    @Test
    void aHeldRowIsDroppedWithItsReason() {
        Options.OptionMemory m = new Options.OptionMemory();
        m.failed("craft crafting_table", "the game refused placing", 0);
        Options.Choice c = StationOptions.choose(f(Kind.TABLE, false, false, -1, 4, 0, 0, 0, CELL, null, false), m, 1000, "goal crafting_table", 30, null);
        assertEquals("planner", c.best().id());
        assertTrue(c.why().contains("the game refused placing"), c.why());
    }

    @Test
    void aKnownStationWithNoCellIsDroppedNotAWalkToNowhere() {
        StationFacts nf = new StationFacts(Kind.TABLE, false, true, null, -1, 0, 0, 0, 0, CELL, null, null, false, 0);
        Options.Choice c = StationOptions.choose(nf, new Options.OptionMemory(), 0, null, 0, null);
        assertNull(c.best());
        assertTrue(c.why().contains("its cell is not known"), c.why());
    }

    @Test
    void aRowWithAnUnknownVerbIsDroppedWithTheStep() {
        Options.Option bad = new Options.Option("cut", "wood", "chop 3", 10, "x", "cut", false);
        Options.Choice c = StationOptions.choose(f(Kind.TABLE, false, false, -1, 0, 0, 0, 0, CELL, bad, false), new Options.OptionMemory(), 0, null, 0, null);
        assertNull(c.best());
        assertTrue(c.why().contains("no verb for \"chop 3\""), c.why());
    }

    @Test
    void everyChainTheFourSetsEmitParses() {
        java.util.List<Options.Choice> all = new java.util.ArrayList<>();
        for (Kind k : Kind.values()) {
            all.add(StationOptions.choose(f(k, false, true, 5, 8, 0, 8, 1, CELL, WOOD, true), new Options.OptionMemory(), 0, "goal table", 3, null));
            all.add(StationOptions.choose(f(k, false, false, -1, 0, 0, 0, 1, CELL, WOOD, true), new Options.OptionMemory(), 0, null, 0, null));
            all.add(StationOptions.choose(f(k, true, false, -1, 0, 0, 0, 1, CELL, WOOD, false), new Options.OptionMemory(), 0, null, 0, null));
        }
        all.add(BaseOptions.choose(new BaseOptions.BaseFacts("home", null, 20, true, 1, true, 5, true), new Options.OptionMemory(), 0));
        all.add(BagOptions.choose(new BagOptions.BagFacts(0, 1, true, 5, CELL, false, 8, 0, CELL, CELL, 1, java.util.List.of("drop dirt 3")), new Options.OptionMemory(), 0));
        all.add(BagOptions.choose(new BagOptions.BagFacts(0, 1, false, -1, null, false, 8, 0, CELL, CELL, 0, java.util.List.of()), new Options.OptionMemory(), 0));
        all.add(BedOptions.choose(new BedOptions.BedFacts(0, 2, new int[]{9, 9, 30, 50}, CELL, true, true, true, 4), new Options.OptionMemory(), 0));
        all.add(BedOptions.choose(new BedOptions.BedFacts(3, 0, null, CELL), new Options.OptionMemory(), 0));
        int rows = 0;
        for (Options.Choice c : all) {
            for (Options.Option o : c.ranked()) { rows++; assertNull(Price.unknownVerb(o.command()), o.command()); }
            for (String d : c.dropped()) assertFalse(d.contains("no verb"), d);
        }
        assertTrue(rows >= 20, "rows checked: " + rows);
    }
}