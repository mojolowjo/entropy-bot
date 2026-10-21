package io.github.mojolowjo.entropybot.aa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND4A O: deposit, chest here, drop junk priced. */
class BagOptionsTest {
    static final int[] CELL = {1, 64, 0}, ME = {0, 64, 0};

    @Test
    void baseChestsNearDepositAndTheChestHereIsDropped() {
        Options.Choice c = BagOptions.choose(new BagOptions.BagFacts(0, 1, true, 10, ME, false, 0, 0, CELL, ME, 0), new Options.OptionMemory(), 0);
        assertEquals("deposit", c.best().command());
        assertTrue(c.why().contains("chest here: base chests known") && c.why().contains("drop junk: no junk"), c.why());
    }

    @Test
    void noBaseAChestIsCraftedPlacedAndMarked() {
        Options.Choice c = BagOptions.choose(new BagOptions.BagFacts(0, 1, false, -1, null, false, 0, 2, CELL, ME, 0), new Options.OptionMemory(), 0);
        assertEquals("craft chest 1 then place chest 1 64 0 then place base 0 64 0 then scan base then deposit", c.best().command());
        assertNotNull(c.best().site());
    }

    @Test
    void junkThatFreesEnoughBeatsAFarDeposit() {
        Options.Choice c = BagOptions.choose(new BagOptions.BagFacts(0, 1, true, 100, ME, false, 0, 0, CELL, ME, 2), new Options.OptionMemory(), 0);
        assertEquals(BagOptions.DROP_JUNK, c.best().command());
        Options.Choice shortJunk = BagOptions.choose(new BagOptions.BagFacts(0, 5, true, 3, ME, false, 0, 0, CELL, ME, 1), new Options.OptionMemory(), 0);
        assertEquals("deposit", shortJunk.best().command(), "the drop alone leaves 4 short");
        assertTrue(shortJunk.why().contains("still 4 short"), shortJunk.why());
    }

    @Test
    void nothingPossibleSaysWhy() {
        Options.Choice c = BagOptions.choose(new BagOptions.BagFacts(0, 1, false, -1, null, false, 3, 0, CELL, ME, 0), new Options.OptionMemory(), 0);
        assertNull(c.best());
        assertTrue(c.why().contains("3 planks, 8 wanted"), c.why());
    }
}
