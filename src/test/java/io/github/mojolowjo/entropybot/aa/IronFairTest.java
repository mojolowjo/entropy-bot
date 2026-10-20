package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.IronOptions.Choice;
import io.github.mojolowjo.entropybot.aa.IronOptions.Facts;
import io.github.mojolowjo.entropybot.aa.IronOptions.Fair;
import io.github.mojolowjo.entropybot.aa.IronOptions.Memory;
import io.github.mojolowjo.entropybot.aa.IronOptions.Ore;
import io.github.mojolowjo.entropybot.aa.IronOptions.Way;
import io.github.mojolowjo.entropybot.aa.brain.Discovery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND3 3: the IRON scorer under aa fair: unseen ore dropped "not seen", the discovery ways priced in. */
class IronFairTest {
    static Ore buried(int x, int y, int z, double dist, int depth) { return new Ore(x, y, z, dist, true, false, false, depth, false, null, false); }

    static Facts facts(List<Ore> ores, Fair fair) {
        return new Facts(ores, 64, false, 0, true, -1, false, "no mine marked (place mine)", 10, false, false, 0, null, fair);
    }

    static Fair fair(Set<String> seen) {
        List<String> dd = new ArrayList<>();
        var opts = Discovery.rank(new Discovery.Facts(-1, 0.5, false, true, null, 100, 64, 200, 16, 0.002, 0.4, false), "iron", 3, dd);
        return new Fair(seen, opts, dd);
    }

    @Test
    void anUnseenBuriedOreIsDroppedAndTheStripRuns() {
        Choice c = IronOptions.choose(facts(List.of(buried(5, 56, 0, 6, 5)), fair(Set.of())), new Memory(), 0);
        assertEquals(Way.STRIP, c.way());
        assertTrue(c.dropped().contains("5 56 0: not seen"), c.dropped().toString());
        assertTrue(c.why().contains("strip at Y"), c.why());
        assertTrue(c.dropped().stream().anyMatch(d -> d.startsWith("explore cave: no known cave")), c.dropped().toString());
    }

    @Test
    void aSeenOreIsTakenAsBefore() {
        Choice c = IronOptions.choose(facts(List.of(buried(5, 56, 0, 6, 5)), fair(Set.of("5 56 0"))), new Memory(), 0);
        assertEquals(Way.DIG_KNOWN, c.way());
        assertEquals("mine iron 1 at 5 56 0 dig", c.command(3));
    }

    @Test
    void fairOffIsTodaysBehaviour() {
        Choice c = IronOptions.choose(facts(List.of(buried(5, 56, 0, 6, 5)), null), new Memory(), 0);
        assertEquals(Way.DIG_KNOWN, c.way());
    }

    @Test
    void theStripRowPlacesTheMineAtTheBestBand() {
        var set = new IronOptions.Set();
        var ch = set.choose(facts(List.of(), fair(Set.of())), new Options.OptionMemory(), 0,
                "mine iron_ore 3 dig then smelt raw_iron 3", 10, null, null);
        assertNotNull(ch.best(), ch.line());
        assertTrue(ch.best().command().endsWith("goto 100 34 170"), ch.best().command());
    }
}
