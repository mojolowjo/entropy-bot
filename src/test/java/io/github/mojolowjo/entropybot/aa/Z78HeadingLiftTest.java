package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Failures;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.aa.moves.StripEnd;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 strip (manager 2026-10-27, item c): the order's own heading hold lifts as the other holds do - past
 * failures.moved (+ the same 8-block band as the station holds) from where it was learned, so a later strip may take that
 * heading from another cell; held again back within failures.moved.
 */
class Z78HeadingLiftTest {
    @Test
    void aWaterHeldHeading_liftsAfterTheMove_andIsHeldAgainBackNearIt() {
        ScenariosTest.world("wooden", "stone");
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = Z77WorldFactsTest.bb(Z77WorldFactsTest.band(16, "west"), clock);
        bb.in.pos = new int[]{7, 16, 3};                                    // DS 15:14:33: the strip's start
        Goals.Goal g = Goals.draft(DebugTreeTest.item("raw_iron"), 3, 0, clock.now());
        Step.Strip st = new Step.Strip(Z62RunSTest.IRON, Map.of("iron", 3), 16, "west", 0);
        FactChange.strip(st.key(), "water ahead at 4 16 3", "strip: water ahead at 4 16 3 (strip west feet y 16: 1 blocks)");
        assertNotNull(Goals.replan(bb, g, st, FactChange.take(), "strip: water ahead").steps());
        String key = NeedChain.headingKey(16, "west");
        assertEquals(16.0, Failures.movedV());
        assertTrue(Goals.droppedSources(g, new int[]{7, 16, 3}).containsKey(key), "held where it was learned");
        assertTrue(Goals.droppedSources(g, new int[]{7, 16, 23}).containsKey(key), "20 blocks: inside the band, held");
        assertFalse(Goals.droppedSources(g, new int[]{7, 16, 30}).containsKey(key), "27 blocks (past 16 + 8): lifted, west may be tried from here");
        assertFalse(Goals.droppedSources(g, new int[]{7, 16, 23}).containsKey(key), "back to 20: still lifted (the band)");
        assertTrue(Goals.droppedSources(g, new int[]{7, 16, 10}).containsKey(key), "back within 16: held again");
    }

    /** Strip review 2: a bag-caused end ("no pickaxe left") holds no heading - a bag fact; the re-plan strips west again on the bag. */
    @Test
    void noPickaxeLeft_holdsNoHeading_theBagDecides() {
        ScenariosTest.world("wooden", "stone");
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = Z77WorldFactsTest.bb(Z77WorldFactsTest.band(16, "west"), clock);
        bb.in.pos = new int[]{7, 16, 3};
        Goals.Goal g = Goals.draft(DebugTreeTest.item("raw_iron"), 3, 0, clock.now());
        Step.Strip st = new Step.Strip(Z62RunSTest.IRON, Map.of("iron", 3), 16, "west", 0);
        FactChange.strip(st.key(), "no pickaxe left", "strip: no pickaxe left (strip west feet y 16: 12 blocks, 0 clusters mined)");
        FactChange.Fact f = FactChange.take();
        assertEquals(FactChange.BAG_STRIP, f.key(), "a bag fact, no source");
        assertNull(FactChange.ownSourceKey(f));
        Steps.Routine r = Goals.replan(bb, g, st, f, "strip: no pickaxe left");
        assertNotNull(r.steps(), r.why());
        assertFalse(Goals.droppedSources(g).containsKey(NeedChain.headingKey(16, "west")), "no heading held for the order");
        assertEquals(0, io.github.mojolowjo.entropybot.aa.brain.WorldFacts.of(bb).size(), "nor for the other orders");
        assertEquals("west", Z77WorldFactsTest.heading(Goals.chain(bb, g)), "west stays open: the bag (a pickaxe in it) decides");
        // water ahead, by contrast, is the heading's
        FactChange.strip(st.key(), "water ahead at 4 16 3", "strip: water ahead at 4 16 3 (strip west feet y 16: 1 blocks)");
        assertEquals("source " + NeedChain.headingKey(16, "west"), FactChange.take().key());
    }

    /**
     * Strip re-review 1 / 2 / 3: only an end naming a world cause along the heading holds it. An owner / monitor / reflex
     * stop ("stopped"), a loss of control, an end the fork does not list, and a malformed key hold nothing - and leave no
     * earlier fact behind for this end; the reasons are matched whole or by their fixed prefix, never a substring.
     */
    @Test
    void aStopOrAnUnmappedEnd_holdsNoHeading_andNoEarlierFactIsConsumed() {
        Step.Strip st = new Step.Strip(Z62RunSTest.IRON, Map.of("iron", 3), 16, "west", 0);
        FactChange.walkShort(new int[]{1, 2, 3}, new int[]{4, 5, 6}, "an earlier, unrelated fact");
        FactChange.strip(st.key(), "stopped", "strip: stopped (strip west feet y 16: 4 blocks)");
        assertNull(FactChange.take(), "the owner's / a monitor's stop: nothing held, the earlier fact not left for it");
        FactChange.strip(st.key(), "lost control", "strip: lost control");
        assertNull(FactChange.take());
        FactChange.strip(st.key(), "no path to the moon", "strip: no path to the moon");
        assertNull(FactChange.take(), "not the fork's \"no path\" (a whole match): unmapped, nothing held");
        FactChange.strip(st.key(), "the miner said no pickaxe left", "x");
        assertNull(FactChange.take(), "a substring is no match");
        FactChange.walkShort(new int[]{1, 2, 3}, new int[]{4, 5, 6}, "an earlier, unrelated fact");
        FactChange.strip("not a strip key", "water ahead at 1 2 3", "strip: water ahead at 1 2 3");
        assertNull(FactChange.take(), "a malformed key: nothing written, the earlier fact cleared");
        assertEquals(StripEnd.Hold.HEADING, StripEnd.of("no path").hold);
        assertEquals(StripEnd.Hold.HEADING, StripEnd.of("lava ahead at 3 -40 7").hold);
        assertEquals(StripEnd.Hold.BAG, StripEnd.of("bag full").hold);
        assertEquals(StripEnd.Hold.NONE, StripEnd.of("done: 64 blocks").hold);
    }
}
