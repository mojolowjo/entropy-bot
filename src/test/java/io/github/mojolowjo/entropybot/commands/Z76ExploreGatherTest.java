package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.cave.ExploreRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z76 send-back 1 (run AN 23:29:43-23:30:07): the explore walked toward cave candidate 117 13 213, gathered a coal vein
 * on the way (the target dropped, the candidate kept), and back from the vein the "walk toward cave candidate ... ended"
 * line read the null target: NullPointerException "ex.target is null", which stopped the iron_chestplate order. A gather
 * now drops the target, its hold and the candidate together; and a throw in a Seq step names the mod's frame it came from.
 */
class Z76ExploreGatherTest {
    @Test
    void aGatherDropsTheCandidateWithItsTarget() {
        Mining.ExRun ex = new Mining.ExRun();
        ex.target = new ExploreRules.Target(117, 213);
        ex.aimed = "117 13 213";
        ex.dropTargetForGather();
        assertNull(ex.target);
        assertNull(ex.hold);
        assertNull(ex.aimed, "no candidate without its target (the NPE's state)");
    }

    @Test
    void aThrowNamesTheModsFrame() {
        RuntimeException e;
        try {
            Object o = null;
            o.hashCode();
            e = null;
        } catch (NullPointerException npe) {
            e = npe;
        }
        assertTrue(io.github.mojolowjo.entropybot.Quiet.where(e).startsWith("Z76ExploreGatherTest.aThrowNamesTheModsFrame:"), io.github.mojolowjo.entropybot.Quiet.where(e));
        assertEquals("an unknown frame", io.github.mojolowjo.entropybot.Quiet.where(new RuntimeException("x") {{ setStackTrace(new StackTraceElement[0]); }}));
    }
}
