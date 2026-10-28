package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.PlanNodes;
import io.github.mojolowjo.entropybot.aa.Playbook;
import io.github.mojolowjo.entropybot.aa.brain.StripWays;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 strip-off: the batch (the gate's kit: torch .. boots) with aa ways strip off - its derivation leaves the strip
 * leg out and says the switch (rule 7, never "no strip way here"); each order's own chain then prices without a strip.
 */
class StripOffBatchTest {
    @BeforeEach
    void setUp() {
        D6WorkerTest.data();
        PlanNodes.forgetMine();
        FactChange.clear();
    }

    @AfterEach
    void off() { StripWays.set(false); }

    @Test
    void theKitWithStripsOff_noBatchStrip_saidAsTheSwitch_andTheOrdersChainHasNoStrip() {
        StripWays.set(true);
        NeedChain.World live = Z78StripHeadingHoldTest.world();
        NeedChain.World w = live.withStrip(StripWays.gate(live.strip()));
        Playbook on = Playbook.derive(D6WorkerTest.KIT, Playbook.facts(w, null));
        assertTrue(on.ok(), "on: the batch strips as before - " + on.why());

        StripWays.set(false);
        Playbook pb = Playbook.derive(D6WorkerTest.KIT, Playbook.facts(w, null));
        assertFalse(pb.ok());
        assertTrue(pb.why().contains(StripWays.OFF), pb.why());
        assertFalse(pb.why().contains("no strip way here"), pb.why());
        NeedChain.Chain c = PlanNodes.old(() -> NeedChain.resolve("raw_iron", 12, w));
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Strip || s instanceof Step.ToStrip), c.why());
    }
}
