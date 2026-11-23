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

    /**
     * Merge review 2026-11-23: the go-and-look walk (the chain's only step: a far spot whose fact is not loaded) ended by
     * the step monitor's no-progress watch came with the monitor's plain fact "step goto ...", a key nothing reads: the
     * re-plan returned the same walk and its second stall parked the order. It is the walk target's fact now - the key the
     * chain's go-and-look passes a spot over by (NeedChain.exploreLeaf), so the next spot or the explore is taken.
     */
    @Test
    void aWalkTheMonitorEndedOnNoProgressHoldsItsTarget_notAStepKeyNothingReads() {
        Step.Walk look = new Step.Walk(110, 64, 0, 32);
        String stall = FactChange.STALL_NO_PROGRESS + " 30 s";
        FactChange.Fact plain = new FactChange.Fact(io.github.mojolowjo.entropybot.aa.moves.StepMonitor.stepKey(look), stall + " at " + look.text());
        FactChange.Fact f = Steps.walkStall(List.of(look), 0, stall, plain, null, null);
        assertEquals("source " + NeedChain.walkTargetKey(110, 64, 0), f.key(), "the spot's walk target is held, as a walk that ended with no fact");
        assertEquals(plain.text(), f.text(), "the monitor's own words are the hold's reason");
        // a walk that led to a named source is that source's fact (z65), as when it ends with no fact of its own
        Step cut = new Step.ChopTree(new int[]{40, 64, 0}, 4, "minecraft:oak_log");
        assertEquals(Steps.walkLeads(List.of(look, cut), 0, stall).key(), Steps.walkStall(List.of(look, cut), 0, stall, plain, null, null).key());
        // any other fact, another step or no fact: as it came
        FactChange.Fact boxed = new FactChange.Fact("source " + NeedChain.boxedInKey(new int[]{0, 64, 0}), "the feet never left");
        assertSame(boxed, Steps.walkStall(List.of(look), 0, stall, boxed, null, null));
        assertNull(Steps.walkStall(List.of(look), 0, stall, null, null, null));
        Step place = new Step.PlaceNear("crafting_table");
        FactChange.Fact other = new FactChange.Fact(io.github.mojolowjo.entropybot.aa.moves.StepMonitor.stepKey(place), stall);
        assertSame(other, Steps.walkStall(List.of(place), 0, stall, other, null, null), "only a walk");
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
