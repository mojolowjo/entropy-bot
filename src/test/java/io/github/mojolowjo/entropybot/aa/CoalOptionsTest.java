package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.CoalOptions.CoalFacts;
import io.github.mojolowjo.entropybot.aa.brain.Discovery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** three-tier r1: coal as a priced set (mine seen / find / charcoal) and the torches that price their missing coal through it. */
class CoalOptionsTest {
    static IronOptions.Ore ore(double dist, boolean open) { return new IronOptions.Ore(10, 40, 10, dist, true, open, false, 6, false, null, false); }

    static Discovery.Opt strip(double cost) { return new Discovery.Opt(Discovery.Kind.STRIP, cost, "Y 40", "mine strip coal 8"); }

    static CoalFacts facts(List<IronOptions.Ore> ores, List<Discovery.Opt> disc, int logs, boolean furnace) {
        return new CoalFacts(8, 1, ores, disc, List.of(), logs, 0, furnace, furnace ? -1 : -1, true);
    }

    @Test
    void seenCoalIsTheCheapestWayWhenNear() {
        Options.Choice c = CoalOptions.choose(facts(List.of(ore(20, true)), List.of(strip(300)), 12, true), new Options.OptionMemory(), 0);
        assertEquals("mine seen coal", c.best().id(), c.line());
        assertEquals("mine coal 8", c.best().command());
        assertTrue(c.ranked().stream().anyMatch(o -> o.id().equals("charcoal")), c.line());
    }

    @Test
    void noCoalSeenCharcoalBeatsAFarStripAndSaysWhy() {
        Options.Choice c = CoalOptions.choose(facts(List.of(), List.of(strip(900)), 12, true), new Options.OptionMemory(), 0);
        assertEquals("charcoal", c.best().id(), c.line());
        assertEquals("gather charcoal 8", c.best().command());
        assertTrue(c.line().contains("no coal ore known yet"), "the drop reason is kept: " + c.line());
    }

    @Test
    void noFurnaceDropsCharcoalWithTheStationReason() {
        Options.Choice c = CoalOptions.choose(facts(List.of(), List.of(strip(900)), 12, false), new Options.OptionMemory(), 0);
        assertEquals("strip at Y for coal", c.best().id(), c.line());
        assertTrue(c.dropped().stream().anyMatch(d -> d.contains("no furnace yet")), c.line());
    }

    @Test
    void missingLogsAreCutAndPriced() {
        Options.Choice rich = CoalOptions.choose(facts(List.of(), List.of(), 12, true), new Options.OptionMemory(), 0);
        Options.Choice poor = CoalOptions.choose(facts(List.of(), List.of(), 0, true), new Options.OptionMemory(), 0);
        assertTrue(poor.best().cost() > rich.best().cost());
        assertTrue(poor.best().why().contains("cut " + CoalOptions.logsFor(8)), poor.best().why());
    }

    @Test
    void aFailedWayIsHeldAndTheNextRuns() {
        Options.OptionMemory m = new Options.OptionMemory();
        CoalFacts f = facts(List.of(ore(20, true)), List.of(strip(300)), 12, true);
        Options.Choice a = CoalOptions.choose(f, m, 0);
        m.failed(a.best().memoryKey(), "failed: nothing gained - mining coal: got 0 of 8", 0);
        Options.Choice b = CoalOptions.choose(f, m, 1000);
        assertNotEquals(a.best().id(), b.best().id());
        assertTrue(b.line().contains("nothing gained"), b.line());
        assertTrue(CoalOptions.choose(f, m, Options.RETRY_MS + 1).ranked().stream().anyMatch(o -> o.id().equals(a.best().id())));
    }

    @Test
    void noPickaxeLeavesCharcoalOnly() {
        CoalFacts f = new CoalFacts(8, 0, List.of(ore(5, true)), List.of(strip(100)), List.of(), 12, 0, true, -1, true);
        Options.Choice c = CoalOptions.choose(f, new Options.OptionMemory(), 0);
        assertEquals(1, c.ranked().size(), c.line());
        assertEquals("charcoal", c.best().id());
    }

    @Test
    void nothingRunnableIsNoWayWithEveryReason() {
        CoalFacts f = new CoalFacts(8, 1, List.of(), List.of(), List.of("explore cave: no known cave"), 0, 0, false, -1, false);
        Options.Choice c = CoalOptions.choose(f, new Options.OptionMemory(), 0);
        assertNull(c.best());
        assertTrue(c.line().contains("no coal ore known yet") && c.line().contains("no furnace yet") && c.line().contains("no known cave"), c.line());
    }

    @Test
    void torchesAreCoalFirstNeverABareCraft() {
        assertEquals("gather charcoal 2 then craft torch 16", CoalOptions.torchChain("gather charcoal 2", 16));
        assertEquals(2, CoalOptions.coalForTorches(8, 16));
        assertEquals(1, CoalOptions.coalForTorches(15, 16));
        assertEquals(4, CoalOptions.coalForTorches(0, 16));
    }
}
