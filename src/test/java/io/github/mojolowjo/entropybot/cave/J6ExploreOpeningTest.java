package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6, run ED (21:40:11: "toward cave opening -4 69 14 (5 m)", 72 ms later "walk toward cave opening -4 69 14
 * ended 4 m from it" - 244 such ends, none more than 24 m away: the chunk target's exploreRules.arrived, 24 blocks x + z,
 * read as arrival): a walk toward an opening ends only within reach of the opening cell, or on Baritone's no progress;
 * a visited opening is one the bot stood at.
 */
class J6ExploreOpeningTest {
    @AfterEach
    void clear() { Tuning.reset(); }

    static final int[] OPENING = {-4, 69, 14};

    @Test
    void anOpeningFiveMetresAwayIsNotReached_untilTheBotIsWithinReachOfIt() {
        int[] feet = {-9, 67, 12};                                      // the opening 5 m off, as in run ED
        var t = new ExploreRules.Target(OPENING[0], OPENING[2]);
        assertTrue(ExploreRules.arrived(feet[0], feet[2], t), "the chunk target's rule would say arrived (the run ED churn)");
        var h = ExploreRules.TargetHold.start(t, 0, feet, 0);
        h = ExploreRules.TargetHold.look(h, 1, feet, 0, true, OPENING);
        assertEquals(ExploreRules.TargetHold.State.HELD, h.state(), "5 m away: held, not visited");
        h = ExploreRules.TargetHold.look(h, 40, new int[]{-9, 68, 15}, 0, true, OPENING);
        assertEquals(ExploreRules.TargetHold.State.HELD, h.state(), "5.1 m off: still held");
        assertFalse(ExploreRules.withinReach(new int[]{-9, 68, 14}, OPENING), "5 m: out of reach (4.5)");
        assertTrue(ExploreRules.withinReach(new int[]{-8, 68, 14}, OPENING), "4 m: within reach");
        h = ExploreRules.TargetHold.look(h, 60, new int[]{-5, 68, 14}, 0, true, OPENING);
        assertEquals(ExploreRules.TargetHold.State.REACHED, h.state(), "within reach: reached - visited");
    }

    @Test
    void aWalkWithNoProgressFails_notVisited_andIsPassedOverFromNearThere() {
        int[] feet = {-9, 67, 12};
        var h = ExploreRules.TargetHold.start(new ExploreRules.Target(OPENING[0], OPENING[2]), 0, feet, 0);
        h = ExploreRules.TargetHold.look(h, ExploreRules.TargetHold.stallTicks() + 1, feet, 0, false, OPENING);
        assertEquals(ExploreRules.TargetHold.State.FAILED, h.state(), "no new best for monitor.stallS: failed");
        var failed = List.of(new ExploreRules.TargetHold.Failed(OPENING[0], OPENING[2], feet, h.why()));
        assertTrue(ExploreRules.TargetHold.skipCell(failed, OPENING[0], OPENING[2], feet, 32), "passed over from near where it failed");
        assertFalse(ExploreRules.TargetHold.skipCell(failed, OPENING[0], OPENING[2], new int[]{60, 67, 12}, 32), "aimed at again once the bot moved");
        assertFalse(ExploreRules.TargetHold.skipCell(failed, 0, 0, feet, 32), "another opening is not skipped");
    }
}
