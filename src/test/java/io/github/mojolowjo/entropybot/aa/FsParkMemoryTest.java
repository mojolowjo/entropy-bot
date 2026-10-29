package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FS fix (run FS 2026-10-28 22:34-22:47: "up to the surface at 43 39 43 ... at the surface cell" 78 times, the explore after it
 * failing "under ground" the same way each time; the park never came because a step done cleared the failed try's memory): a
 * step done IN PLACE (the feet still on the failed try's cell, the bag unchanged) is no progress - the second identical failure
 * parks honestly. A step done after the bot MOVED still clears it (D2 3.1: progress was made).
 */
class FsParkMemoryTest {
    static final FactChange.Fact UNDER = new FactChange.Fact("source band -64..384", "under ground (26 blocks over the feet at 44 39 40): no cave cell in the band");

    private static Steps.Routine upThenExplore() {
        return new Steps.Routine(List.of(new Step.ToSurface(43, 39, 43), new Step.Explore(5, "raw_iron")), null);
    }

    @Test
    void aStepDoneInPlace_keepsTheFailedTry_soTheSecondSameFailureParks() {
        Z70StepCheckTest.Hands h = new Z70StepCheckTest.Hands();
        h.feet = new int[]{43, 39, 43};
        h.status = s -> s instanceof Step.ToSurface ? Car.Status.done("ok: at the surface cell") : Car.Status.failed(UNDER.text());
        h.fact = s -> UNDER;
        int[] asked = {0};
        Steps run = new Steps(h);
        run.start("goal.iron_helmet", upThenExplore().steps(), 0, (failed, fact, why) -> { asked[0]++; return upThenExplore(); });
        for (int t = 1; t <= 8 && run.running(); t++) run.tick(t);
        assertEquals(Steps.Phase.FAILED, run.phase(), "parked: " + run.why());
        assertTrue(run.why().contains("the same way failed twice from the same place with the same bag: explore 5 until raw_iron"), run.why());
        assertEquals(1, asked[0], "one re-plan, then the honest park (was: a re-plan per loop, 78 of them)");
    }

    @Test
    void aStepDoneAfterTheBotMoved_clearsTheFailedTry_asBefore() {
        Z70StepCheckTest.Hands h = new Z70StepCheckTest.Hands();
        h.feet = new int[]{43, 39, 43};
        h.status = s -> s instanceof Step.ToSurface ? Car.Status.done("ok: at the surface cell") : Car.Status.failed(UNDER.text());
        h.fact = s -> UNDER;
        int[] asked = {0};
        Steps run = new Steps(h);
        run.start("goal.iron_helmet", upThenExplore().steps(), 0, (failed, fact, why) -> {
            asked[0]++;
            h.feet = new int[]{43 + 10 * asked[0], 39, 43};                   // the walk up takes the bot somewhere else each time
            return upThenExplore();
        });
        for (int t = 1; t <= 6; t++) run.tick(t);
        assertTrue(run.running(), "another cell is another question (D2 3.1): " + run.why());
        assertTrue(asked[0] >= 2);
    }
}
