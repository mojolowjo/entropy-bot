package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
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
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import io.github.mojolowjo.entropybot.sense.CaveIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 run EY (2026-10-28 13:30:45-13:32:54, round 1 run 2; save runEY-z78j6d.zip): the lip walk to the cave at 62 94 -354
 * (a cliff overhang's underside: no standable cell within reach and sight of it) ended short at 67 88 -344 with the EL named
 * end - a walk-short fact of the running order only, under a key the cave way never read - and every one of the five orders
 * ran the same lip twice and parked; the lip's PRICE had asked a stand-by goal on the entry ("complete" at 2.9 s) while the
 * step walked the fork's reach goal (empty). The fix (rules 7, 23 (1), 23 (2)): the lip's end is the OPENING's source fact
 * (CaveWay.keyOf) held for the order and, as a world fact, for every order; and the cave-way snapshot asks the lip step's own
 * test per opening (LipGoals / ExploreRules.lipsWithStand) before any walk is priced.
 */
class J6bEyTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final int[] ENTRY = {62, 94, -354}, FEET = {67, 88, -344};
    static final String KEY = "known cave 62,94,-354";
    static final String WHY = ExploreRules.noLipCell(ENTRY) + " (the fork's reach goal, its cells loaded)";

    @Test
    void theLipsSourceKey_isTheOpenings_theOneTheCaveWayFiltersBy() {
        assertEquals(KEY, NeedChain.sourceKey(new Step.ToCave(62, 94, -354)));
        assertEquals(KEY, CaveWay.keyOf(ENTRY), "the same key the cave way holds an opening by (CaveWay.without)");
        FactChange.Fact f = FactChange.lipFailedFact(ENTRY, FEET, WHY);
        assertEquals("source " + KEY, f.key(), "a source fact (was: a walk-short fact of the running order)");
        assertEquals(KEY, FactChange.ownSourceKey(f), "the order holds that key (Goals.replan)");
        assertTrue(f.text().startsWith("the walk to the cave's lip at 62 94 -354 failed at 67 88 -344: no standable cell"), f.text());
    }

    /** The snapshot's filter: an opening with no standable cell is left out with its reason; an unloaded one is not known and stays. */
    @Test
    void theSnapshotLeavesOutAnOpeningWithNoLipCell_keepsAnUnloadedOne() {
        int[] loadedOk = {-33, 63, 42}, unloaded = {183, 65, 49};
        ExploreRules.Lips lp = ExploreRules.lipsWithStand(List.of(ENTRY, unloaded, loadedOk),
                o -> o == ENTRY ? ExploreRules.Lip.NO_CELL : o == unloaded ? ExploreRules.Lip.APPROACH : ExploreRules.Lip.WALKING);
        assertEquals(2, lp.kept().size(), "the unloaded (not known, rule 15) and the standable one stay");
        assertSame(unloaded, lp.kept().get(0));
        assertSame(loadedOk, lp.kept().get(1));
        assertEquals(Map.of(KEY, ExploreRules.noLipCell(ENTRY) + " (the lip test, its cells loaded)"), lp.out());
        assertTrue(ExploreRules.lipsWithStand(List.of(), o -> ExploreRules.Lip.WALKING).out().isEmpty());
    }

    /** The planner with the snapshot's answer: the opening left out is said and never walked to; with it the cave's only opening, the cave is out with its reason. */
    @Test
    void anOpeningTheLipTestLeftOut_isSaidAndNeverWalkedTo() {
        var over = new NeedChain.Surface(0, 67, 88, -344, 3, false);
        CaveWay lipless = cave(List.of()).withLipOut(Map.of(KEY, ExploreRules.noLipCell(ENTRY) + " (the lip test, its cells loaded)"));
        Chain c = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, Map.of()).withCave(w -> w.contains("raw_iron") || w.contains("iron_ore") ? lipless : null));
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.ToCave), "no lip walk to an opening with no stand: " + c.steps());
        String said = c.why() + " | " + c.missing();
        assertTrue(said.contains("no opening with a standable cell within reach and sight of it (the lip test left out 1: no standable cell within reach and sight of the entry 62 94 -354"),
                "said with its reason (rule 7): " + said);
        // the same cave with a second opening that has a stand: that one is walked to
        int[] other = {70, 90, -350};
        CaveWay twoOpenings = cave(List.of(other)).withLipOut(Map.of(KEY, ExploreRules.noLipCell(ENTRY) + " (the lip test, its cells loaded)"));
        Chain c2 = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, Map.of()).withCave(w -> w.contains("raw_iron") || w.contains("iron_ore") ? twoOpenings : null));
        assertTrue(c2.ok(), c2.why());
        assertEquals(new Step.ToCave(70, 90, -350), c2.steps().stream().filter(s -> s instanceof Step.ToCave).findFirst().orElse(null), "the next opening: " + c2.steps());
    }

    /** The EY scenario: the helmet's lip end re-plans it, holds the opening, is a world fact; the pickaxe never prices that lip. */
    @Test
    void theHelmetsLipEnd_stopsThePickaxesIdenticalLipWalk() {
        var over = new NeedChain.Surface(0, 67, 88, -344, 3, false);
        CaveWay cw = cave(List.of(ENTRY));
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());
        bb.fact(NeedChain.WORLD_FACT, (Supplier<NeedChain.World>) () -> J6bEhTest.iron(over, Map.of()).withCave(w -> w.contains("raw_iron") || w.contains("iron_ore") ? cw : null));
        bb.in.pos = FEET.clone();
        bb.in.bag = Map.of("minecraft:stone_pickaxe", 1);
        Goals.Goal helmet = Goals.draft(DebugTreeTest.item("raw_iron"), 3, 0, clock.now());
        Goals.Goal pickaxe = Goals.draft(DebugTreeTest.item("raw_iron"), 3, 0, clock.now());
        Chain before = Goals.chain(bb, pickaxe);
        assertNotNull(before);
        assertTrue(before.steps().stream().anyMatch(s -> s instanceof Step.ToCave t && t.x() == 62 && t.z() == -354), "before: the lip walk is the way: " + before.steps());

        FactChange.Fact f = FactChange.lipFailedFact(ENTRY, FEET, WHY);
        assertNotNull(Goals.replan(bb, helmet, new Step.ToCave(62, 94, -354), f, "no standable cell"), "re-planned");
        assertTrue(Goals.droppedSources(helmet).containsKey(KEY), "the helmet holds the opening: " + Goals.droppedSources(helmet));
        assertTrue(WorldFacts.of(bb).heldAt(bb).containsKey(KEY), "a world fact: " + WorldFacts.of(bb).heldAt(bb));

        Chain after = Goals.chain(bb, pickaxe);
        assertNotNull(after);
        assertTrue(after.steps().stream().noneMatch(s -> s instanceof Step.ToCave t && t.x() == 62 && t.z() == -354),
                "the pickaxe never walks to that lip (run EY: five orders did, twice each): " + after.steps());

        // far from where it was learned (past failures.moved + the margin): the opening is a way again
        bb.in.pos = new int[]{67, 88, -284};
        Chain far = Goals.chain(bb, pickaxe);
        assertNotNull(far);
        assertTrue(far.steps().stream().anyMatch(s -> s instanceof Step.ToCave t && t.x() == 62 && t.z() == -354), "60 blocks away: priced again: " + far.steps());
    }

    static CaveWay cave(List<int[]> entries) {
        return new CaveWay(62, 94, -354, 12, "minecraft:iron_ore", "iron", Double.NaN, Double.NaN, CaveIndex.Size.LARGE, 30, List.of(), entries).withInside(false);
    }
}
