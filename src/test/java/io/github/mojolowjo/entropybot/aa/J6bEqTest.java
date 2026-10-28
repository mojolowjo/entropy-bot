package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.WorldFacts;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6b run EQ (2026-10-28 06:49-06:55, feet -52 17 -41 in a cave cell): "place furnace near me" made no progress
 * for 30 s (the fork's GoalPlace stand at y 18, no throwaway to pillar), the stall's key was "step place furnace near me"
 * (no source key, so no world fact) and each of five orders ran the same step from the same feet and parked one by one -
 * the chestplate parked at 06:55:30.322, the leggings started the identical step at 06:55:30.579. The fix (rule 23 (1)):
 * the stall is the place fact of WHERE it stalled ("place at x y z", the key the fork's own end holds too), so the order
 * holds it and, as a world fact held by distance, every other order's chain reads it: no place-near here again.
 */
class J6bEqTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final int[] FEET = {-52, 17, -41};
    static final Map<String, Integer> BAG = Map.of("minecraft:furnace", 1, "minecraft:raw_iron", 7, "minecraft:coal", 1);

    /** The monitor's stall on a place-near whose signals never move (Z71's fake hands): the fact it hands the planner. */
    static FactChange.Fact stalled() {
        Z71StepMonitorTest.Hands h = new Z71StepMonitorTest.Hands();
        h.script = s -> Z71StepMonitorTest.sig(1806.7, FEET, false, 0, 0, -1, 0, -1, null);
        Z71StepMonitorTest.Planner p = new Z71StepMonitorTest.Planner(h);
        Steps run = new Steps(h);
        run.start("goal.iron_chestplate", List.of(new Step.PlaceNear("furnace")), 0, p);
        Z71StepMonitorTest.run(run, h, p, 40);
        assertEquals(1, p.facts.size(), "one stall: " + h.logged);
        return p.facts.get(0);
    }

    @Test
    void thePlaceSourceKey_isWhereItFailed() {
        assertEquals("place at -52 17 -41", NeedChain.sourceKey(new Step.PlaceNear("furnace"), FEET));
        assertNull(NeedChain.sourceKey(new Step.PlaceNear("furnace"), null), "no feet: no key (rule 15)");
        assertNull(NeedChain.sourceKey(new Step.PlaceNear("furnace")), "the step alone names no cell");
        assertEquals(NeedChain.CLIMB_KEY, NeedChain.sourceKey(new Step.Climb(), FEET), "other steps as before");
    }

    @Test
    void aStalledPlaceNear_isThePlaceFactOfItsFeet_theSameAsTheForksOwnEnd() {
        FactChange.Fact f = stalled();
        assertEquals("source place at -52 17 -41", f.key(), "was 'step place furnace near me' (run EQ)");
        assertEquals("place at -52 17 -41", FactChange.ownSourceKey(f), "the order holds that key (Goals.replan)");
        assertTrue(f.text().startsWith("place furnace near me failed at -52 17 -41: no progress 30 s"), f.text());
        FactChange.placeFailed("minecraft:furnace", "no cell within 3 blocks", FEET);
        assertEquals(f.key(), FactChange.take().key(), "the fork's own end keys the same fact");
    }

    /** The EQ scenario: the chestplate's stall re-plans it and is a world fact; the leggings never price that place-near. */
    @Test
    void theChestplatesStall_stopsTheLeggingsIdenticalStep() {
        scenario(stalled(), "place furnace near me failed at -52 17 -41: no progress 30 s");
    }

    /**
     * The fork's named end (job-vein 64d1bba3: every near cell's face check failed, the bot's own body in the way; no
     * GoalPlace stand on the block): the place step's FAILED reason is the same place fact (StepHands.placeNearStatus ->
     * FactChange.placeFailed at the feet), so the next way runs and no order places near me here again.
     */
    @Test
    void theForksNamedEnd_isTheStepFact_theNextWayRuns() {
        String end = "gave up at place furnace near me: no cell near -52 17 -41 with a face to place on"
                + " (face check at -52 17 -42: the block would collide with an entity (the bot's own body or a mob))";
        FactChange.placeFailed("minecraft:furnace", end, FEET);
        scenario(FactChange.take(), "place furnace near me failed at -52 17 -41: " + end);
    }

    /** Run EQ: the walk probe's face-check part on a place step. */
    @Test
    void theFaceCheckProbePart() {
        assertEquals(" | face check every face passed (none failed in this leg)", io.github.mojolowjo.entropybot.commands.ProbeLines.faceCheck(null));
        assertEquals(" | face check face check at -52 17 -42: no face", io.github.mojolowjo.entropybot.commands.ProbeLines.faceCheck("face check at -52 17 -42: no face"));
    }

    private static void scenario(FactChange.Fact f, String said) {
        ScenariosTest.world("wooden", "stone");
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());
        bb.fact(NeedChain.WORLD_FACT, (Supplier<NeedChain.World>) () -> ScenariosTest.w(BAG, true, false, Map.of()));
        bb.in.pos = FEET.clone();
        bb.in.bag = BAG;
        Goals.Goal chest = Goals.draft(DebugTreeTest.item("iron_ingot"), 7, 0, clock.now());
        Goals.Goal legs = Goals.draft(DebugTreeTest.item("iron_ingot"), 7, 0, clock.now());
        Chain before = Goals.chain(bb, legs);
        assertEquals(new Step.PlaceNear("furnace"), before.steps().get(0), "before: place furnace near me: " + before.steps());

        Steps.Routine r = Goals.replan(bb, chest, new Step.PlaceNear("furnace"), f, "no progress 30 s");
        assertNotNull(r, "re-planned");
        assertTrue(Goals.droppedSources(chest).containsKey("place at -52 17 -41"), "the chestplate holds it: " + Goals.droppedSources(chest));
        assertTrue(WorldFacts.of(bb).heldAt(bb).containsKey("place at -52 17 -41"), "a world fact: " + WorldFacts.of(bb).heldAt(bb));

        Chain after = Goals.chain(bb, legs);
        assertNotNull(after);
        assertTrue(after.steps().stream().noneMatch(s -> s instanceof Step.PlaceNear), "the leggings never place near me here again: " + after.steps());
        assertTrue((after.why() + " | " + after.missing()).contains(said),
                "said with the chestplate's reason: " + after.why() + " | " + after.missing());

        // far from it (past failures.moved + the margin): placing near me there is a way again
        bb.in.pos = new int[]{-52, 17, 0};
        Chain far = Goals.chain(bb, legs);
        assertEquals(new Step.PlaceNear("furnace"), far.steps().get(0), "41 blocks away: " + far.steps());
    }
}
