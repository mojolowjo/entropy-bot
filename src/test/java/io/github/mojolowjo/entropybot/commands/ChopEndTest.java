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

    @Test
    void aBagThatGainedTheWantIsDoneWhateverTheChopSays() {
        assertEquals(Car.State.DONE, ChopEnd.of("collect 3 log", 3, 3, "have 3 valid items (tree 4 logs: picked up 3 of 4)").state());
        assertEquals(Car.State.DONE, ChopEnd.of("collect 3 log", 4, 3, "tree 4 logs: picked up 4").state());
    }
}
