package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Overnight 2026-10-19 fixes: climb out at dawn, the coal goal's reserve (the coals tag, not a name). */
@org.junit.jupiter.api.extension.ExtendWith(io.github.mojolowjo.entropybot.aa.brain.VanillaData.class)
class AaFix3Test {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());

    static final Thing.Result COAL = new Thing.Result("coal", "coal", true, java.util.List.of("minecraft:coal"),
            java.util.List.of("coal"), 1, null, null, null);

    @Test
    void climbsOutAtDawnAfterDiggingDownAtNight() {
        bb.in.hungerDrains = true;
        bb.in.dayTime = 18_000;
        bb.in.underDepth = 33;
        assertFalse(NightWork.climbAtDawn(bb, 3), "nothing dug yet");
        NightWork.noteDug(bb, NightWork.Work.CRAFT);
        assertFalse(NightWork.climbAtDawn(bb, 3), "a craft digs nothing");
        NightWork.noteDug(bb, NightWork.Work.STAIRS);
        assertFalse(NightWork.climbAtDawn(bb, 3), "still night: stay down");
        bb.in.dayTime = 0;
        assertTrue(NightWork.climbAtDawn(bb, 3), "dawn, 33 under: climb");
        bb.in.underDepth = 1;
        assertFalse(NightWork.climbAtDawn(bb, 3), "out");
        bb.in.underDepth = 33;
        assertFalse(NightWork.climbAtDawn(bb, 3), "the note was cleared at the surface");
    }

    @Test
    void coalGoalWeighsCoalAgainstTorches() {
        assertEquals(0, Goals.coalGoal(bb)[1]);
        Goals.add(bb, new Thing.Result("stick", "stick", false, java.util.List.of("minecraft:stick"), java.util.List.of("stick"), 0, null, null, null), 8, 0, clock.now());
        assertEquals(0, Goals.coalGoal(bb)[1], "not a coal goal");
        Goals.add(bb, COAL, 16, 0, clock.now());
        double[] cg = Goals.coalGoal(bb);
        assertEquals(16, cg[1]);
        bb.in.coalGoalScore = cg[0];
        bb.in.coalGoalN = (int) cg[1];
        bb.in.coal = 4;
        bb.in.torches = 14;
        assertFalse(bb.in.torchCoal(), "14 torches: the coal goes to the goal: " + bb.in.torchCoalTerms());
        bb.in.planks = 4; bb.in.tableInBag = true;
        bb.in.torches = 0;
        assertTrue(bb.in.torchCoal(), "no torches: 4 torches are worth more than 1 of 16 coal: " + bb.in.torchCoalTerms());
        assertEquals("craft torch 16", NightWork.craft(bb.in).text());
    }

}
