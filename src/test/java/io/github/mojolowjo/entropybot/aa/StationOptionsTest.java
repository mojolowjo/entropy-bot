package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.StationOptions.Kind;
import io.github.mojolowjo.entropybot.aa.StationOptions.StationFacts;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND4A O, one-planner: a station's ways priced: a known one, the bag, the chain + place; no way says every reason. */
@org.junit.jupiter.api.extension.ExtendWith(io.github.mojolowjo.entropybot.aa.brain.VanillaData.class)
class StationOptionsTest {
    static final int[] CELL = {1, 64, 0};

    static StationFacts f(Kind k, boolean bag, boolean known, double dist, boolean under) {
        return new StationFacts(k, bag, known, known ? new int[]{5, 64, 5} : null, dist, under, under ? 10 : 0);
    }

    /** A chain for a table from the bag's log: two crafts (0.6 s = 0.6 x 4.317 walk-blocks). */
    static final List<Step> CHAIN = List.of(new Step.Craft("oak_planks", 4), new Step.Craft("crafting_table", 1));

    @Test
    void theStationSetOwnsTheChestAndScansIt() {
        Options.Choice c = StationOptions.choose(f(Kind.CHEST, true, false, -1, false), new Options.OptionMemory(), 0, null, 0, null);
        assertEquals("place chest near me > scan", c.best().command());
        assertTrue(c.best().typed(), "rm-execution: typed steps");
        Options.Choice t = StationOptions.choose(f(Kind.TABLE, true, false, -1, false), new Options.OptionMemory(), 0, null, 0, null);
        assertEquals("place crafting_table near me", t.best().command(), "only the chest scans; the table's item is the recipe data's station");
    }

    @Test
    void theChainThenThePlaceAtItsOwnPrice() {
        Options.Choice c = StationOptions.choose(f(Kind.TABLE, false, false, -1, false), new Options.OptionMemory(), 0, CHAIN, 0.6 * 4.317, "crafting_table <- craft: 4 oak_planks <- craft: 1 oak_log (in the bag): 0.6 s");
        assertEquals("craft oak_planks 4 > craft crafting_table 1 > place crafting_table near me", c.best().command());
        assertEquals(0.75 * 4.317, c.best().cost(), 1e-6, "the chain 0.6 s + place 0.15 s");
        assertTrue(c.best().needsPlanner(), "a chain row");
    }

    @Test
    void aKnownStationNearIsUsedAndAFarOneDropped() {
        Options.Choice near = StationOptions.choose(f(Kind.FURNACE, false, true, 20, false), new Options.OptionMemory(), 0, null, 0, null);
        assertEquals("goto 5 64 5 near 2", near.best().command());
        Options.Choice far = StationOptions.choose(f(Kind.FURNACE, false, true, 60, false), new Options.OptionMemory(), 0, List.of(new Step.Craft("furnace", 1)), 1, "x");
        assertEquals("craft furnace 1 > place furnace near me", far.best().command());
        assertTrue(far.why().contains("60 away, over 48"), far.why());
    }

    @Test
    void noWayGivesEveryReason() {
        Options.Choice c = StationOptions.choose(f(Kind.FURNACE, false, false, -1, false), new Options.OptionMemory(), 0, null, 0, "no facts for the chain yet");
        assertNull(c.best());
        assertTrue(c.why().startsWith("no way to a furnace"), c.why());
        assertTrue(c.why().contains("chain + place furnace: no facts for the chain yet"), c.why());
    }

    @Test
    void aHeldRowIsDroppedWithItsReason() {
        Options.OptionMemory m = new Options.OptionMemory();
        m.failed("place crafting_table", "the game refused placing", 0);
        Options.Choice c = StationOptions.choose(f(Kind.TABLE, true, false, -1, false), m, 1000, null, 0, null);
        assertNull(c.best());
        assertTrue(c.why().contains("the game refused placing"), c.why());
    }

    @Test
    void aKnownStationWithNoCellIsDroppedNotAWalkToNowhere() {
        StationFacts nf = new StationFacts(Kind.TABLE, false, true, null, -1, false, 0);
        Options.Choice c = StationOptions.choose(nf, new Options.OptionMemory(), 0, null, 0, null);
        assertNull(c.best());
        assertTrue(c.why().contains("its cell is not known"), c.why());
    }

    @Test
    void everyChainTheSetsEmitParses() {
        java.util.List<Options.Choice> all = new java.util.ArrayList<>();
        for (Kind k : Kind.values()) {
            all.add(StationOptions.choose(f(k, false, true, 5, true), new Options.OptionMemory(), 0, CHAIN, 3, null));
            all.add(StationOptions.choose(f(k, false, false, -1, true), new Options.OptionMemory(), 0, CHAIN, 3, null));
            all.add(StationOptions.choose(f(k, true, false, -1, false), new Options.OptionMemory(), 0, null, 0, null));
        }
        all.add(BaseOptions.choose(new BaseOptions.BaseFacts("home", null, 20, true, 1, true, 5, true), new Options.OptionMemory(), 0));
        all.add(BagOptions.choose(new BagOptions.BagFacts(0, 1, true, 5, CELL, false, 8, 0, CELL, 1, java.util.List.of(new Step.DropJunk("dirt", 3))), new Options.OptionMemory(), 0));
        all.add(BagOptions.choose(new BagOptions.BagFacts(0, 1, false, -1, null, false, 8, 0, CELL, 0, java.util.List.<Step.DropJunk>of()), new Options.OptionMemory(), 0));
        int rows = 0;
        for (Options.Choice c : all) {
            for (Options.Option o : c.ranked()) {
                rows++;
                assertTrue(o.typed(), o.command());
                for (var s : o.steps())
                    if (!(s instanceof Step.Obtain))
                        assertNotNull(io.github.mojolowjo.entropybot.commands.VerbTable.of(io.github.mojolowjo.entropybot.commands.StepCommands.command(s).split("\\s+")[0]), s.text());
            }
            for (String d : c.dropped()) assertFalse(d.contains("no verb"), d);
        }
        assertTrue(rows >= 12, "rows checked: " + rows);
    }
}
