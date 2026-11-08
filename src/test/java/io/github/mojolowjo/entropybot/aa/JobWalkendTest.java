package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-walkend (run JBD 09:22:47): "goto -129 63 -26 near 1" before "place crafting_table near me" ended "goal is walled in"
 * and the order stopped. Every walk end not "arrived" is a fact the planner re-prices on (rule 7).
 */
class JobWalkendTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    static final String WHY = "error: couldn't get there (goal is walled in at -129 63 -26; stopped 2 blocks away)";

    @Test
    void theWalkTargetKeyForms() {
        Step walk = new Step.Walk(-129, 63, -26, 1);
        assertNull(Steps.walkLeads(List.of(walk, new Step.PlaceNear("crafting_table")), 0, WHY), "a place names no source");
        FactChange.Fact f = Steps.walkTargetFact(List.of(walk, new Step.PlaceNear("crafting_table")), 0, WHY);
        assertEquals("source walk target -129 63 -26", f.key());
        assertTrue(f.text().contains("goal is walled in"), f.text());
        assertFalse(f.text().startsWith("error"), f.text());
        assertEquals("source walk target -129 63 -26", Steps.walkTargetFact(List.of(walk), 0, WHY).key(), "the last step too");
        assertNull(Steps.walkTargetFact(List.of(new Step.PlaceNear("crafting_table")), 0, WHY), "not a walk");
    }

    @Test
    void aFailedWalkBeforeAStepWithNoSourceIsAHeldFactAndAReplan() {
        // the test hands' bag holds no crafting_table (the place's own need wins there), so a step with no needs and no
        // source key stands in for the place: the same walkLeads null
        Step walk = new Step.Walk(-129, 63, -26, 1), place = new Step.Walk(-129, 70, -26, 0);
        Z61ReservationsTest.Hands h = new Z61ReservationsTest.Hands();
        List<FactChange.Fact> asked = new ArrayList<>();
        Steps run = new Steps(h);
        run.start("owner goal", List.of(walk, place), 0, (failed, fact, w) -> { asked.add(fact); return new Steps.Routine(List.of(place), null); });
        h.fact = null;
        h.status = Car.Status.failed(WHY);
        run.tick(1);
        assertEquals(1, asked.size(), "re-planned, not stopped: " + run.why());
        assertEquals("source walk target -129 63 -26", asked.get(0).key());
        assertTrue(run.running(), run.why());
    }
}
