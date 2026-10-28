package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 strip-off review (1): with the strip way off, a strip planned before (a running routine, a repair's kept tail)
 * is never kept by a repair - the order is planned again in full on the World with no strip - and is refused at its start
 * (StripStep.start / StepHands.mineStrip on {@link StripWays#planned}); the owner's own strip order is not a planned strip.
 */
class StripOffKeptTest {
    @AfterEach
    void off() { StripWays.set(false); }

    static final Step.Strip LEG = new Step.Strip(List.of("minecraft:iron_ore"), Map.of("iron", 2), 16, "south", 1, null);
    static final Step.Strip OWNER = new Step.Strip(List.of("minecraft:iron_ore"), Map.of("iron", 2), 16, "south", 1_761_000_000_000L, null);

    @Test
    void plannedStripsAreTheChainsAndTheOwnersOrderIsNot() {
        assertTrue(StripWays.planned(LEG));
        assertTrue(StripWays.planned(new Step.Strip(List.of("minecraft:iron_ore"), Map.of("iron", 2), 16, "south", 0, null)), "a whole strip");
        assertFalse(StripWays.planned(OWNER), "aa want strip ...: the order's time");
        assertTrue(StripWays.holdsStrip(List.of(new Step.Craft("stick", 4), LEG, new Step.Deposit(LEG))));
        assertTrue(StripWays.holdsStrip(List.of(new Step.ToStrip(0, 16, 0, "k"))));
        assertTrue(StripWays.holdsStrip(List.of(new Step.Mine("iron", 3, Step.Mine.How.STRIP))));
        assertFalse(StripWays.holdsStrip(List.of(new Step.Craft("stick", 4), OWNER)), "the owner's order is no strip way");
    }

    /** A repair whose kept tail holds a strip: none while off (the full re-plan prices without strips). */
    @Test
    void aRepairNeverKeepsAStripTailWhileOff() {
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());
        Goals.Goal g = Goals.draft(new Thing.Result("minecraft:raw_iron", "raw_iron", false, List.of(), List.of(), 0, null, "raw_iron", null), 3, 0, clock.now());
        Steps.Remaining left = new Steps.Remaining(List.of(new Step.Collect("raw_iron", 3), LEG, new Step.Deposit(LEG), new Step.StripBack(LEG)),
                List.of(Map.of(), Map.of(), Map.of(), Map.of()), Map.of());
        StripWays.set(false);
        assertNull(Goals.repair(bb, g, left, null, null), "off: no repair keeps the strip tail");
    }
}
