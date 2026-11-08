package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.Failures;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Thing;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-lookup (run JAY 07:27:01-07:28:56): iron seen at -178 34 -43 through the shaft wall (inside rock, 15 up and 7 across
 * from the feet at -171 19 -41) was left out of the live lookup as "buried", and the unparked order waited two minutes on
 * the brain's hold before it ran again.
 */
class JobLookupTest {
    static final String IRON = "minecraft:iron_ore", STONE = "minecraft:stone";

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** The seen vein inside rock (six closed faces, 15 blocks of rock up to air) is the lookup's source, with its cover. */
    @Test
    void aVeinFifteenUpAndSevenAcrossInsideRockIsASource() {
        NearbyExposureTest.World w = new NearbyExposureTest.World().air(-178, 50, -43);
        Spot seen = new Spot(-178, 34, -43, 16.6, IRON);
        Nearby.Source s = Nearby.source(w, new int[]{-171, 19, -41}, Set.of(IRON), () -> seen);
        assertNotNull(s.spot(), s.line());
        assertEquals("fallback", s.from());
        assertEquals(15, s.spot().cover(), "the cells 35..49 are rock");
        assertTrue(s.spot().isCovered());
        assertTrue(s.buried().isEmpty(), s.line());
    }

    /** A cover of 15 (deeper than sight.coverMax) is priced per level, said with its cover. */
    @Test
    void aCoverOfFifteenIsPricedPerLevel() {
        Spot open = new Spot(5, 64, 0, 5, STONE).covered(0, null);
        Spot one = open.covered(1, STONE), deep = open.covered(15, STONE);
        Chain a = NeedChain.resolve("cobblestone", 3, Z77StoneHereTest.world(word -> List.of(open)));
        Chain b = NeedChain.resolve("cobblestone", 3, Z77StoneHereTest.world(word -> List.of(one)));
        Chain c = NeedChain.resolve("cobblestone", 3, Z77StoneHereTest.world(word -> List.of(deep)));
        assertTrue(a.ok() && b.ok() && c.ok(), a.why() + " / " + b.why() + " / " + c.why());
        double perLevel = b.seconds() - a.seconds();
        assertTrue(perLevel > 0);
        assertEquals(15 * perLevel, c.seconds() - a.seconds(), 1e-6, "15 levels of stone");
        assertTrue(c.why().contains("15 blocks of stone over it (deeper than sight.coverMax 4: priced as a dig of 15 levels of stone)"), c.why());
    }

    /** The unpark ("tries once more: a new source seen") forgets the brain's hold on the order's desire: it runs at once. */
    @Test
    void theUnparkReArmsTheOrderAtOnce() {
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(new Clock.Fake(0), port, new Tracer());
        bb.in.bag = Map.of();
        bb.in.pos = new int[]{0, 64, 0};
        Goals.Goal g = Goals.add(bb, new Thing.Result("furnace", "furnace", false, List.of("minecraft:furnace"), List.of("furnace"), 0, null, "furnace", null), 1, 0, 0);
        Goals.replanner(bb, g).parked(Z70StepCheckTest.cobbleTry());
        assertTrue(Goals.park(bb, g, "stopped at step 16: the same way failed twice"));
        Failures brain = new Failures();
        brain.failed(g.id(), null, "stopped at step 16", 0, bb.in);       // the failed run's hold (failures.cdBaseMs)
        assertTrue(brain.blocked(g.id(), null, 1_000));
        bb.in.bag = Map.of("cobblestone", 3);
        assertEquals(1, Goals.retryLoopParks(bb, brain).size());
        assertNull(g.parked);
        assertFalse(brain.blocked(g.id(), null, 1_000), "re-armed at once, not after the brain's two-minute hold");
    }
}
