package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * deposit-goal stage 4 (piece 7): the planned put names the main chest, so the leg skips CHOOSE and goes to WALK / USE at
 * that chest; the strip order's deposit and the plain aa deposit (no chest) still choose. Hand numbers: a made-up cell.
 */
class DepositLegChestTest {
    private static final Map<String, Integer> OWED = Map.of("minecraft:cobblestone", 32);

    @Test
    void aGivenChestSkipsChoose() {
        DepositLeg leg = new DepositLeg(null, new Step.Deposit(OWED, new int[] {10, 64, -20}), null, null);
        assertEquals(DepositLeg.Phase.WALK, leg.phase());
    }

    @Test
    void noChestStillChooses() {
        assertEquals(DepositLeg.Phase.CHOOSE, new DepositLeg(null, new Step.Deposit(OWED), null, null).phase());
        assertEquals(DepositLeg.Phase.CHOOSE, new DepositLeg(null, new Step.Deposit(), null, null).phase());
    }

    @Test
    void theChestIsInTheStepsTextAndId() {
        Step.Deposit d = new Step.Deposit(OWED, new int[] {10, 64, -20});
        assertEquals("deposit cobblestone 32 to the chest at 10 64 -20", d.text());
        assertNotEquals(StepId.of(d), StepId.of(new Step.Deposit(OWED)), "the put to a chest is its own step");
        assertNotEquals(StepId.of(d), StepId.of(new Step.Deposit(OWED, new int[] {11, 64, -20})), "another chest, another step");
    }
}
