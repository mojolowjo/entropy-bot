package io.github.mojolowjo.entropybot.ghost;

import io.github.mojolowjo.entropybot.ghost.GhostTickRules.Jump;
import io.github.mojolowjo.entropybot.ghost.GhostTickRules.JumpStep;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The vanilla pieces the ghost's tick copies (LivingEntity.aiStep, 1.21.1). */
class GhostTickRulesTest {
    static final double THRESHOLD = 0.4;   // vanilla Entity.getFluidJumpThreshold for a player (eye height 1.62 < 0.4 ? 0.4)

    @Test
    void tinySpeedsAreZeroedAndOthersKept() {
        assertEquals(0.0, GhostTickRules.zeroTiny(0.0029));
        assertEquals(0.0, GhostTickRules.zeroTiny(-0.0029));
        assertEquals(0.003, GhostTickRules.zeroTiny(0.003));
        assertEquals(-0.0784, GhostTickRules.zeroTiny(-0.0784));
    }

    @Test
    void theDelayCountsDownToZero() {
        assertEquals(9, GhostTickRules.decayDelay(10));
        assertEquals(0, GhostTickRules.decayDelay(1));
        assertEquals(0, GhostTickRules.decayDelay(0));
    }

    @Test
    void notJumpingClearsTheDelay() {
        assertEquals(new JumpStep(Jump.NONE, 0), GhostTickRules.jump(false, true, true, false, false, true, 0, THRESHOLD, 7));
        assertEquals(new JumpStep(Jump.NONE, 0), GhostTickRules.jump(true, false, true, false, false, true, 0, THRESHOLD, 7));
    }

    @Test
    void aJumpFromDryGroundStartsTheDelayAndWaitsForIt() {
        assertEquals(new JumpStep(Jump.FROM_GROUND, 10), GhostTickRules.jump(true, true, true, false, false, true, 0, THRESHOLD, 0));
        assertEquals(new JumpStep(Jump.NONE, 4), GhostTickRules.jump(true, true, true, false, false, true, 0, THRESHOLD, 4));
        assertEquals(new JumpStep(Jump.NONE, 0), GhostTickRules.jump(true, true, true, false, false, false, 0, THRESHOLD, 0), "no jump in the air");
    }

    @Test
    void deepWaterSwimsUpAndShallowWaterJumps() {
        assertEquals(Jump.IN_WATER, GhostTickRules.jump(true, true, false, true, false, false, 1.0, THRESHOLD, 0).act());
        assertEquals(Jump.IN_WATER, GhostTickRules.jump(true, true, false, true, false, true, 0.9, THRESHOLD, 0).act(), "deep, on the floor");
        assertEquals(Jump.FROM_GROUND, GhostTickRules.jump(true, true, false, true, false, true, 0.3, THRESHOLD, 0).act(), "ankle deep on ground");
        assertEquals(Jump.IN_WATER, GhostTickRules.jump(true, true, false, true, false, false, 0.3, THRESHOLD, 0).act(), "off the ground any water swims up, however shallow");
    }

    @Test
    void lavaAndOtherFluids() {
        assertEquals(Jump.IN_LAVA, GhostTickRules.jump(true, true, false, false, true, false, 0.8, THRESHOLD, 0).act());
        assertEquals(Jump.FROM_GROUND, GhostTickRules.jump(true, true, false, false, true, true, 0.2, THRESHOLD, 0).act());
        assertEquals(Jump.IN_OTHER_FLUID, GhostTickRules.jump(true, true, false, false, false, false, 0.8, THRESHOLD, 0).act());
    }
}
