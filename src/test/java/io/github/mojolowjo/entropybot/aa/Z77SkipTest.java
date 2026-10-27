package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Kernels;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepNeeds;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 skip (run CC 16:41:54.569-571): a step is skipped only on the bag AT that step. The chain is net of the bag: its
 * "collect raw_iron 2" was 2 MORE than the 2 carried (for a total of 4), and the kernel check read the 2 in the bag as the
 * collect's output a second time ("step 1/3 collect raw_iron 2 skipped: 2 raw_iron already in the bag"); the seen vein was
 * walked past. Now the skipped steps' output must all be taken by the later steps' readable needs, and the bag must hold
 * those needs (the dry run's bag at the step the run goes on from).
 */
class Z77SkipTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    static final String RAW = "minecraft:raw_iron";

    /** CC's chain: [collect raw_iron 2, up to the surface, explore 5 until raw_iron]. */
    static List<Step> ccChain() {
        return List.of(new Step.Collect("raw_iron", 2), new Step.ToSurface(19, 22, -8), new Step.Explore(5, "raw_iron"));
    }

    static StepNeeds.Bag bag(Map<String, Integer> items) {
        Z73RepairTest.Hands h = new Z73RepairTest.Hands();
        h.bag.putAll(items);
        return h.bag();
    }

    /** Scenario (CC): 2 raw_iron in the bag, the chain wants 2 more -> the collect runs, nothing said skipped. */
    @Test
    void theCollectRunsWhenTheBagHoldsOnlyWhatTheChainCountedAlready() {
        Z73RepairTest.Hands h = new Z73RepairTest.Hands();
        h.bag.put(RAW, 2);
        Steps run = new Steps(h);
        run.start("goal.iron_helmet", ccChain(), 0, (f, fact, why) -> null);
        assertEquals(List.of("collect raw_iron 2"), h.started, h.logged.toString());
        assertTrue(h.logged.stream().noneMatch(l -> l.contains("already in the bag")), h.logged.toString());
        assertFalse(run.why().contains("skipped steps"), run.why());

        // the pure check: no skip from step 1, whatever the bag holds - no readable later step takes the collect's output
        List<StepNeeds> none = new ArrayList<>(List.of(StepNeeds.NONE, StepNeeds.NONE, StepNeeds.NONE));
        assertNull(Kernels.latest(ccChain(), 0, none, bag(Map.of(RAW, 2))));
        assertNull(Kernels.latest(ccChain(), 0, none, bag(Map.of(RAW, 64))));
    }

    /**
     * A known later step that takes less than the skipped step makes: the rest was planned on top of the bag - not skipped
     * (the old floor, max(the later need, the output) = 2, read the 2 carried as the collect's).
     */
    @Test
    void anOutputPartlyTakenIsNotSkipped() {
        List<Step> steps = List.of(new Step.Collect("raw_iron", 2), new Step.Smelt("iron_ingot", 1, true));
        List<StepNeeds> needs = List.of(StepNeeds.NONE, Z73RepairTest.uses(Map.of(RAW, 1)));
        assertNull(Kernels.latest(steps, 0, needs, bag(Map.of(RAW, 2))));
    }

    /**
     * Control: the chain's own earlier step already brought them (the vein gave 4 for "collect raw_iron 2", run on 2
     * carried): at step 2 the bag holds the 4 the smelt takes, so "collect raw_iron 2" is skipped, as before; one short, not.
     */
    @Test
    void controlTheBagAtTheStepHoldsWhatTheLaterStepsTake() {
        List<Step> steps = List.of(new Step.Collect("raw_iron", 2), new Step.Collect("raw_iron", 2), new Step.Smelt("iron_ingot", 4, true));
        List<StepNeeds> fromTwo = List.of(StepNeeds.NONE, Z73RepairTest.uses(Map.of(RAW, 4)));
        Kernels.Skip k = Kernels.latest(steps, 1, fromTwo, bag(Map.of(RAW, 4)));
        assertNotNull(k);
        assertEquals(2, k.to());
        assertEquals(List.of("collect raw_iron 2 skipped: 4 raw_iron already in the bag"), k.said());
        assertNull(Kernels.latest(steps, 1, fromTwo, bag(Map.of(RAW, 3))), "3 of the 4 the smelt takes: the collect runs");
    }
}
