package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.moves.Car;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The owner's 2026-10-22 watch: the chop ended with logs on the ground and the step said done. The cut hand's end:
 * the bag's gain decides; short of the want it is a failed step carrying the chop's end text, never done.
 */
class ChopEndTest {

    @Test
    void logsLeftOnTheGroundAndTheBagShortIsAFailedStepWithTheChopText() {
        Car.Status s = ChopEnd.of("collect 3 log", 2, 3, "tree 3 logs: picked up 2 of 3 (1 drop left, unreachable)");
        assertEquals(Car.State.FAILED, s.state());
        assertTrue(s.why().contains("picked up 2 of 3 (1 drop left, unreachable)"), s.why());
        assertTrue(s.why().contains("got 2 of 3"), s.why());
    }

    @Test
    void pickedUpKOfNWithTheBagShortIsNeverDone() {
        Car.Status s = ChopEnd.of("collect 3 log", 1, 3, "component done: tree 3 logs: picked up 1 of 3");
        assertEquals(Car.State.FAILED, s.state(), s.why());
    }

    /** S42 (0.39.z36): a cut with 0 got sets the source fact in the chop's own words, like the mine end since z33. */
    @Test
    void s42_aCutWithNoneGotIsTheSourcesChangedFact() {
        io.github.mojolowjo.entropybot.aa.moves.FactChange.clear();
        Car.Status s = ChopEnd.of("cut 1 logs", 0, 1, "no path to any target", "logs", "at 127 65 14");
        assertEquals(Car.State.FAILED, s.state());
        var f = io.github.mojolowjo.entropybot.aa.moves.FactChange.take();
        assertNotNull(f, "0 of 1: a fact, so the order re-plans");
        assertEquals("source logs at 127 65 14", f.key());
        assertEquals("unreachable (Baritone: no path to any target)", f.text());
        ChopEnd.of("cut 3 logs", 2, 3, "picked up 2 of 3", "logs", "at 1 2 3");
        assertNull(io.github.mojolowjo.entropybot.aa.moves.FactChange.take(), "some got: the source is not unreachable");
        ChopEnd.zeroGot(0, null, null, "x");
        assertNull(io.github.mojolowjo.entropybot.aa.moves.FactChange.take(), "no source: nothing said");
    }

    /**
     * 0.39.z66 item 2 (run X 08:41:01): "collect 3 iron_ore: have 19 valid items (cluster 9 blocks: picked up 9) (got 0 of
     * 3)" - Baritone counted 9 drops (raw_iron), the step's item none: the source's fact in the true words, never
     * "unreachable"; drops not known (-1) or none over the item: as before.
     */
    @Test
    void z66_blocksThatDroppedOtherItemsAreThatFactNotUnreachable() {
        io.github.mojolowjo.entropybot.aa.moves.FactChange.clear();
        Car.Status s = ChopEnd.of("collect 3 iron_ore", 0, 3, "have 19 valid items (cluster 9 blocks: picked up 9)", "iron_ore", "at 208 11 -544", 9);
        assertEquals(Car.State.FAILED, s.state());
        assertTrue(s.why().contains("the blocks dropped other items (9 of their drops picked up, 0 of 3 of the item"), s.why());
        var f = io.github.mojolowjo.entropybot.aa.moves.FactChange.take();
        assertNotNull(f, "a fact: the order re-plans without the source (rule 7)");
        assertEquals("source iron_ore at 208 11 -544", f.key());
        assertFalse(f.text().startsWith("unreachable"), f.text());
        ChopEnd.of("cut 1 logs", 0, 1, "no path to any target", "logs", "at 1 2 3", 0);
        assertTrue(io.github.mojolowjo.entropybot.aa.moves.FactChange.take().text().startsWith("unreachable"), "no drops either: unreachable as before");
        assertEquals(Car.State.DONE, ChopEnd.of("collect 3 raw_iron", 9, 3, "have 19 valid items", "raw_iron", "at 1 2 3", 9).state());
    }

    @Test
    void aBagThatGainedTheWantIsDoneWhateverTheChopSays() {
        assertEquals(Car.State.DONE, ChopEnd.of("collect 3 log", 3, 3, "have 3 valid items (tree 4 logs: picked up 3 of 4)").state());
        assertEquals(Car.State.DONE, ChopEnd.of("collect 3 log", 4, 3, "tree 4 logs: picked up 4").state());
    }
}
