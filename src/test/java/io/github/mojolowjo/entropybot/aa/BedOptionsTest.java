package io.github.mojolowjo.entropybot.aa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Round 4 fix 4: the bed's ways priced; a failed wool hunt is held and the next way runs, never the same retry. */
class BedOptionsTest {
    static final int[] ME = {0, 64, 0}, HERD = {40, -30, 50, 80};

    @Test
    void woolInTheBagCrafts() {
        Options.Choice c = BedOptions.choose(new BedOptions.BedFacts(3, 2, HERD, ME), new Options.OptionMemory(), 0);
        assertEquals("goal bed", c.best().command());
        assertEquals(1, c.ranked().size(), "with the wool there is nothing to hunt");
    }

    @Test
    void sheepInViewBeatTheHerdAndTheHerdBeatsNothing() {
        Options.OptionMemory m = new Options.OptionMemory();
        Options.Choice c = BedOptions.choose(new BedOptions.BedFacts(1, 2, HERD, ME), m, 0);
        assertEquals("hunt 2 sheep for white_wool 3 then goal bed", c.best().command());
        assertEquals(2, c.ranked().size());
        assertEquals("hunt 2 sheep for white_wool 3 at 40 -30 then goal bed", c.ranked().get(1).command());
        assertNotNull(c.ranked().get(1).site(), "the herd is a site for the sequencer");
        Options.Choice herdOnly = BedOptions.choose(new BedOptions.BedFacts(0, 0, HERD, ME), m, 0);
        assertEquals("hunt 3 sheep for white_wool 3 at 40 -30 then goal bed", herdOnly.best().command());
        Options.Choice none = BedOptions.choose(new BedOptions.BedFacts(0, 0, null, ME), m, 0);
        assertNull(none.best());
        assertTrue(none.why().startsWith("no wool (0/3) and no sheep seen"), none.why());
    }

    @Test
    void aFailedHuntIsHeldAndTheNextWayRunsInstead() {
        Options.OptionMemory m = new Options.OptionMemory();
        BedOptions.BedFacts f = new BedOptions.BedFacts(0, 1, HERD, ME);
        Options.Choice c = BedOptions.choose(f, m, 0);
        assertEquals("hunt sheep here", c.best().memoryKey());
        String end = "stopped at step 1 (hunt 3 sheep for white_wool 3): stopped: hunt: 0/3 - I see no sheep (I see about 192 blocks around me) - walk me to some";
        assertTrue(BedOptions.switchable(end));
        assertFalse(BedOptions.switchable("stopped: your stop"), "the owner's stop never switches");
        m.failed("hunt sheep here", end, 1000);
        Options.Choice again = BedOptions.choose(f, m, 2000);
        assertEquals("hunt sheep at 40 -30", again.best().memoryKey(), "the herd now: " + again.why());
        assertTrue(again.why().contains("hunt sheep here: "), "the held one is dropped with its reason: " + again.why());
        m.failed("hunt sheep at 40 -30", end, 3000);
        Options.Choice nothing = BedOptions.choose(f, m, 4000);
        assertNull(nothing.best(), "both held: the rung skips with the reasons instead of retrying: " + nothing.why());
        Options.Choice later = BedOptions.choose(f, m, 1000 + Options.RETRY_MS + 1);
        assertNotNull(later.best(), "after the hold the way is tried again, dearer");
        assertTrue(later.best().why().contains("failed 1x"));
    }

    @Test
    void theOldChainIsTheFallbackWithoutFacts() {
        assertEquals("hunt 3 sheep for white_wool 3 then goal bed", BedOptions.huntChain(0, null));
        assertEquals("hunt 1 sheep for white_wool 3 at 5 6 then goal bed", BedOptions.huntChain(2, new int[]{5, 6, 10, 90}));
    }

    @Test
    void aKnownBedAtNightIsSleptInAndUnderGroundTheHuntClimbsFirst() {
        Options.Choice night = BedOptions.choose(new BedOptions.BedFacts(0, 0, null, ME, true, true, false, 0), new Options.OptionMemory(), 0);
        assertEquals("sleep", night.best().command());
        Options.Choice day = BedOptions.choose(new BedOptions.BedFacts(0, 0, null, ME, true, false, false, 0), new Options.OptionMemory(), 0);
        assertNull(day.best());
        assertTrue(day.why().contains("sleep in a known bed: not night"), day.why());
        Options.Choice under = BedOptions.choose(new BedOptions.BedFacts(0, 2, null, ME, false, false, true, 10), new Options.OptionMemory(), 0);
        assertEquals("climb then hunt 3 sheep for white_wool 3 then goal bed", under.best().command());
        assertTrue(under.why().contains("climb 10 up"), under.why());
    }
}