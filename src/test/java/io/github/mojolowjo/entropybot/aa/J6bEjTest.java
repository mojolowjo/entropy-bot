package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.WorldFacts;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.commands.ProbeLines;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6b run EJ (01:33:40-01:47:52: the feet cell -6 56 18 filled with water, every way up a pillar Baritone cannot
 * do from water; six orders each ran the same climb three times, twice, 36 tries): a climb's every end is the ONE climb
 * fact ("climb up") - the monitor's, the outer watch's, the climb's own - and a world fact, so one failed climb at a cell
 * holds for every order there (rule 7); the outer watch leaves a judged step to the step monitor (rule 23 (2)); the
 * "probe lease:" line.
 */
class J6bEjTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** Every end of a climb keys the one climb fact: the monitor's / outer watch's no progress and the climb's own end. */
    @Test
    void everyEndOfAClimb_isTheOneClimbFact() {
        assertEquals(NeedChain.CLIMB_KEY, NeedChain.sourceKey(new Step.Climb()), "the climb's source key");
        FactChange.Fact monitor = FactChange.noProgress(NeedChain.sourceKey(new Step.Climb()), "no progress 30 s ... at climb");
        FactChange.climb("Baritone's climb goal ended at -6 56 18 with 6 blocks over the feet");
        FactChange.Fact own = FactChange.take();
        assertEquals(own.key(), monitor.key(), "the monitor (and the outer watch: Steps.noProgress keys by sourceKey too) and the climb's own end");
        assertEquals("source " + NeedChain.CLIMB_KEY, monitor.key(), "the source key the order holds (Goals' re-plan) and the world learns");
    }

    /**
     * The EJ water cell: the climb failed once at -6 56 18 (the torch learned it): a world fact - the next order holds it,
     * never prices that climb again; with its walk up held too it stops at once with both reasons (rule 7, no 30 s climb);
     * with a walk up priced, the walk up runs.
     */
    @Test
    void theEjWaterCell_oneFailedClimb_theNextOrderNeverPricesIt() {
        WorldFacts w = new WorldFacts();
        Inputs in = new Inputs();
        in.pos = new int[]{-6, 56, 18};
        in.bag = Map.of("minecraft:cobblestone", 7, "minecraft:dirt", 4);
        String why = "no progress 30 s: the goal heuristic 46.8 (best 46.8 at 1 s), feet -6 56 18 (farthest 0 from the start) ... at climb";
        assertTrue(w.learned(NeedChain.CLIMB_KEY, why, "torch", in), "a failed climb is a world fact now");
        Map<String, String> forPickaxe = w.heldFor(Set.of(), in, null);
        assertTrue(forPickaxe.containsKey(NeedChain.CLIMB_KEY), "held for the next order: " + forPickaxe);
        assertTrue(forPickaxe.get(NeedChain.CLIMB_KEY).contains("(a world fact: torch learned it at -6 56 18)"), forPickaxe.toString());

        var deep = Z77ExploreTest.deep(word -> null).withBag(Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20));
        var noWalk = deep.withPath((x, y, z, d) -> io.github.mojolowjo.entropybot.aa.PathPrice.Answer.unreachable("no path"));
        Chain before = NeedChain.resolve("raw_iron", 3, noWalk);
        assertTrue(before.steps().contains(new Step.Climb()), "without the fact the climb is priced: " + before.steps());
        Chain held = NeedChain.resolve("raw_iron", 3, noWalk.withoutSources(forPickaxe));
        assertFalse(held.ok(), held.why());
        assertTrue(held.steps().stream().noneMatch(s -> s instanceof Step.Climb), "that climb is never priced again: " + held.steps());
        assertTrue(held.missing().contains("the climb: ") && held.missing().contains("torch learned it at -6 56 18"), "both reasons, said: " + held.missing());
        Chain walk = NeedChain.resolve("raw_iron", 3, deep.withoutSources(forPickaxe));
        assertTrue(walk.ok(), walk.why());
        assertEquals(new Step.ToSurface(-181, 64, 27), walk.steps().get(0), "the next way runs (the walk up): " + walk.steps());
        assertTrue(walk.steps().stream().noneMatch(s -> s instanceof Step.Climb), walk.steps().toString());
    }

    /** The one more try under ground (every order parked): taken by an order the world fact holds for, and forgets it for all. */
    @Test
    void theOneMoreTry_forgetsTheWorldsClimbFact() {
        Blackboard bb = new Blackboard(new Clock.Fake(0), new FakePort(), new Tracer());
        bb.in.bag = Map.of();
        bb.in.pos = new int[]{-6, 56, 18};
        bb.in.underDepth = 6;
        Goals.Goal torch = Z77ClimbDigUpTest.order(bb, "torch"), pick = Z77ClimbDigUpTest.order(bb, "iron_pickaxe");
        WorldFacts.of(bb).learned(NeedChain.CLIMB_KEY, "no progress 30 s at climb", "torch", bb.in);
        Goals.park(bb, torch, "stopped at step 10 (climb): no progress 30 s at climb");
        Goals.park(bb, pick, "under ground (6 blocks over the feet) ... the climb: no progress 30 s at climb (a world fact: torch learned it at -6 56 18)");
        List<String> said = Goals.parkedTick(bb, true);
        assertTrue(said.stream().anyMatch(l -> l.startsWith("order torch tries once more on the climb way")), said.toString());
        assertTrue(said.stream().anyMatch(l -> l.startsWith("order iron_pickaxe tries once more on the climb way")), said.toString());
        assertEquals(Map.of(), WorldFacts.of(bb).heldAt(bb), "the one more try: the climb is offered again for every order");
        assertNull(pick.parked);
    }

    /** The probe lease line: the flags read now and the fork's RunLease.describe; Baritone not loaded: said. */
    @Test
    void theProbeLeaseLine() {
        assertEquals("probe lease: t=4658 | climb climb | at the step's start | allowBreak true | allowPlace true | allowInventory false"
                        + " | fork lease break true, place true, inventory false, holders [Owner]",
                ProbeLines.leaseLine(4658, "climb", "climb", "the step's start", true, true, false, "break true, place true, inventory false, holders [Owner]"));
        assertTrue(ProbeLines.leaseLine(1, "x", "y", "the cancel by Jobs.cancel", null, null, null, null)
                .endsWith("allowBreak not read (Baritone not loaded) | allowPlace not read (Baritone not loaded) | allowInventory not read (Baritone not loaded) | fork lease not read (Baritone not loaded)"));
    }
}
