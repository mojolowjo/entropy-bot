package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * run JAF (2026-11-04 14:26:18, docs/runs/runJAF-4f60cd8d-probe): "goto 5 64 -5 near 10" before "collect dirt 16" at that
 * spot ended at its start ("inside dirt, no free spot within 3 blocks"); the walk-led fact was keyed "collect dirt", which
 * held every dirt spot for the order, and three explores for dirt ran 15 minutes over dirt with no sighting counted.
 */
class RunJafWalkKeyTest {

    @Test
    void aFailedWalkToACollectSpotHoldsThatSpotOnly() {
        Step walk = new Step.Walk(5, 64, -5, Step.Collect.MINE_SIGHT);
        Step collect = new Step.Collect("dirt", 16, new int[]{5, 64, -5});
        FactChange.Fact f = Steps.walkLeads(List.of(walk, collect), 0, "error: 5 64 -5 is inside dirt and there's no free spot within 3 blocks");
        assertNotNull(f);
        assertEquals("collect dirt at 5 64 -5", FactChange.ownSourceKey(f), "the spot's key, the form of NeedChain.spotKey(item, spot)");
        assertEquals("the walk to collect dirt at 5 64 -5 failed: 5 64 -5 is inside dirt and there's no free spot within 3 blocks", f.text());
    }

    @Test
    void aCollectWithoutASpotKeepsTheItemKey() {
        Step walk = new Step.Walk(5, 64, -5, 0);
        FactChange.Fact f = Steps.walkLeads(List.of(walk, new Step.Collect("dirt", 16)), 0, "x");
        assertEquals("collect dirt", FactChange.ownSourceKey(f), "no spot named: the fork's mine takes any block of the item");
        assertNull(NeedChain.spotKey(new Step.Collect("dirt", 16)));
        assertNull(NeedChain.spotKey((Step.Collect) null));
    }

    @Test
    void theSpotKeyMatchesTheChainsSpotKeyForm() {
        // the chain holds a spot when held.test(spotKey(item, sp)): "collect <shown word> at x y z"
        assertEquals("collect dirt at 1 2 3", NeedChain.spotKey(new Step.Collect("minecraft:dirt", 1, new int[]{1, 2, 3})));
        assertEquals("collect #logs at 1 2 3", NeedChain.spotKey(new Step.Collect("#minecraft:logs", 1, new int[]{1, 2, 3})));
    }
}
