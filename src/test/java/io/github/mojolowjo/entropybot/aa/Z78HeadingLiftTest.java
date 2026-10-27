package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Failures;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
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
        FactChange.strip(st.key(), "strip: water ahead at 4 16 3 (strip west feet y 16: 1 blocks)");
        assertNotNull(Goals.replan(bb, g, st, FactChange.take(), "strip: water ahead").steps());
        String key = NeedChain.headingKey(16, "west");
        assertEquals(16.0, Failures.movedV());
        assertTrue(Goals.droppedSources(g, new int[]{7, 16, 3}).containsKey(key), "held where it was learned");
        assertTrue(Goals.droppedSources(g, new int[]{7, 16, 23}).containsKey(key), "20 blocks: inside the band, held");
        assertFalse(Goals.droppedSources(g, new int[]{7, 16, 30}).containsKey(key), "27 blocks (past 16 + 8): lifted, west may be tried from here");
        assertFalse(Goals.droppedSources(g, new int[]{7, 16, 23}).containsKey(key), "back to 20: still lifted (the band)");
        assertTrue(Goals.droppedSources(g, new int[]{7, 16, 10}).containsKey(key), "back within 16: held again");
    }
}
