package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepMonitor;
import io.github.mojolowjo.entropybot.aa.moves.StepSignals;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z76 send-back 2 (the manager: four older bugs that repeat across runs, fixed as correctness): (a) the band walk's
 * feet end IN the band (run AN 23:16:18 "to y 56..56: Baritone's goal ended at y 57"; 13x in AK, 32x in AL); (b) an
 * explore's re-target without a move is no progress and its frozen way is held by position (run AO 20:37); (c) a collect
 * names the spot it was priced at and ends when Baritone's mine walks away from it (run AP 00:07-00:17); (d) a throw inside
 * a step is that step's fact and the order re-plans (rule 7; run AN 23:30:07).
 */
class Z76SendBack2Test {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    // ---- (a) the band walk ----

    /** A provider that records which asks wanted the feet IN the cell (priceInto) and which stand by it (price). */
    static final class Asks implements PathPrice {
        final List<String> by = new ArrayList<>(), into = new ArrayList<>();
        @Override public Answer price(int x, int y, int z, double dist) { by.add(x + "," + y + "," + z); return Answer.priced(new Walk(FakePaths.walkS(dist))); }
        @Override public Answer priceInto(int x, int y, int z, double dist) { into.add(x + "," + y + "," + z); return Answer.priced(new Walk(FakePaths.walkS(dist))); }
    }

    /**
     * Run AN's base dig "to y 56..56" from the feet at y 60: the band walk is asked as the feet IN the band's cell (0 56 0),
     * never as "stand by it" (that goal is reached from y 57, outside a one-high band); the tree's walk stays a stand-by ask.
     */
    @Test
    void a_theBandWalkEndsInTheBand() {
        ScenariosTest.world("wooden", "stone");
        Asks pp = new Asks();
        NeedChain.World w = ScenariosTest.w(Map.of("minecraft:wooden_pickaxe", 1), false, false, Map.of())
                .withPath(pp).withBand(word -> new int[]{56, 56})
                .withWhere(new NeedChain.WhereFacts(word -> null, () -> null, () -> new int[]{0, 60, 0}));
        Chain c = NeedChain.resolve("diamond", 1, w);
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.ToBand), c.steps().toString());
        assertEquals(new Step.ToBand(56, 56).target(60), 56);
        assertTrue(pp.into.contains("0,56,0"), "the band cell asked as feet-in: " + pp.into + " / by " + pp.by);
        assertFalse(pp.by.contains("0,56,0"), "never as stand-by: " + pp.by);
    }

    // ---- (b) the under-ground explore ----

    static StepSignals sig(String goalId, int[] feet, long calcFailed) {
        return new StepSignals(true, null, feet, 100, goalId, false, false, false, calcFailed, -1, -1, null, 0, 0, null);
    }

    /**
     * Run AO 23:54-23:56: the explore took a new target every 3 s from the same feet, each search failing (a CALC_FAILED a
     * second), nothing executing. The failed searches count on across the re-targets: "Baritone found no path 3 times" at
     * 3 s (was: the count started again on each target, only the 30 s stall ended it). The fact is the position's
     * ("explore frozen at -375 24 -265"). A walk keeps z72's rule (another goal is another question).
     */
    @Test
    void b_aRetargetWithoutAMoveIsNoNewQuestion() {
        int[] feet = {-375, 24, -265};
        StepMonitor m = new StepMonitor();
        Step ex = new Step.Explore(5, "bamboo|#logs");
        m.begin(ex, sig("t0", feet, 0), 0);
        StepMonitor.Look l = null;
        int at = -1;
        for (int s = 1; s <= 40 && at < 0; s++) {
            l = m.look(sig("t" + (s / 3), feet, s), s * 20L);
            if (l.v() == StepMonitor.Verdict.REPLAN) at = s;
        }
        assertEquals(3, at, "3 failed searches since the start, re-targets or not");
        assertTrue(l.reason().startsWith("Baritone found no path 3 times from -375 24 -265"), l.reason());
        assertEquals("source " + NeedChain.EXPLORE_FROZEN + "-375 24 -265", l.fact().key());
        // a walk: another goal starts the count again (z72) - no REPLAN on the re-targets alone by 3 s
        StepMonitor w = new StepMonitor();
        Step walk = new Step.Walk(1, 64, 1, 0);
        w.begin(walk, sig("t0", feet, 0), 0);
        StepMonitor.Look lw = null;
        for (int s = 1; s <= 3; s++) lw = w.look(sig("t" + s, feet, s), s * 20L);
        assertEquals(StepMonitor.Verdict.CONTINUE, lw.v(), lw.reason());
    }

    /** The frozen explore is held by position: within failures.moved (16) of it the same explore is no way (said); 40 m off it is. */
    @Test
    void b_theFrozenExploreIsHeldByPosition() {
        ScenariosTest.world("wooden");
        NeedChain.World base = ScenariosTest.w(Map.of(), false, false, Map.of())
                .withWhere(new NeedChain.WhereFacts(word -> null, () -> null, () -> new int[]{0, 24, 0}));
        Chain free = NeedChain.resolve("oak_log", 3, base);
        assertTrue(free.explore(), free.why());
        Chain held = NeedChain.resolve("oak_log", 3, base.withHeld(List.of(NeedChain.EXPLORE_FROZEN + "3 24 0: no progress 30 s")));
        assertFalse(held.explore(), held.why());
        assertTrue(held.why().contains("the explore from here froze (" + NeedChain.EXPLORE_FROZEN + "3 24 0"), held.why());
        Chain far = NeedChain.resolve("oak_log", 3, base.withHeld(List.of(NeedChain.EXPLORE_FROZEN + "40 24 0: no progress 30 s")));
        assertTrue(far.explore(), "40 m from the frozen cell: the explore again: " + far.why());
        assertEquals(NeedChain.EXPLORE_FROZEN + "1 2 3", FactChange.ownSourceKey(FactChange.noProgress(NeedChain.exploreFrozenKey(new int[]{1, 2, 3}), "x")),
                "the order holds it as a source (replan.maxSourceDrops, not maxPerOrder)");
    }

    // ---- (c) the collect's spot ----

    /** The chain's collect names the spot it priced (STONE_12 at 12 60 3); the step's identity (equals, text) is the same. */
    @Test
    void c_aCollectNamesItsPricedSpot() {
        ScenariosTest.world("wooden", "stone");
        Chain c = NeedChain.resolve("cobblestone", 3, ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1), false, false, Map.of("cobblestone", List.of(ScenariosTest.STONE_12))));
        Step.Collect k = (Step.Collect) c.steps().stream().filter(s -> s instanceof Step.Collect).findFirst().orElseThrow();
        assertEquals("12 60 3", k.spot(), c.steps().toString());
        assertEquals(new Step.Collect("cobblestone", 3), k, "the spot is not the step's identity");
        assertEquals("collect cobblestone 3", k.text());
    }

    /**
     * Run AP: the collect started 75 m from its priced spot; Baritone's mine walked away. Leaving: farther than 75 + 16
     * (failures.moved) from the spot - at 92 m it ended, at 90 it was still on; walking to it (60 m) is no leaving.
     */
    @Test
    void c_aMineWalkingAwayFromTheSpotEnds() {
        Step.Collect k = new Step.Collect("oak_planks", 11, new int[]{75, 64, 0});
        int[] start = {0, 64, 0};
        double d0 = k.dist(start);
        assertEquals(75, d0, 1e-9);
        assertFalse(k.leftSpot(new int[]{15, 64, 0}, d0, 16), "60 m from it: on the way");
        assertFalse(k.leftSpot(new int[]{-15, 64, 0}, d0, 16), "90 m: within failures.moved of the start's distance");
        assertTrue(k.leftSpot(new int[]{-17, 64, 0}, d0, 16), "92 m: gone away");
        assertFalse(new Step.Collect("oak_planks", 11).leftSpot(new int[]{-1000, 64, 0}, Double.NaN, 16), "no spot named: never (rule 15)");
    }

    // ---- (d) a throw inside a step ----

    /** Hands whose status (or start) throws; the planner records the fact. */
    static final class Throwing implements Steps.Hands {
        boolean onStart;
        @Override public String start(Step s) { if (onStart && s instanceof Step.Explore) throw new IllegalStateException("start blew up"); return null; }
        @Override public Car.Status status(Step s) { if (!(s instanceof Step.Explore)) return Car.Status.running("waiting"); Object o = null; return Car.Status.running(o.toString()); }
        @Override public void cancel(String why) {}
    }

    /**
     * Run AN 23:30:07: a NullPointerException inside the explore step. The step ends FAILED with the exception and the mod's
     * frame as its reason, and the order re-plans on it (fact "threw at <frame>") - never a plain stop.
     */
    @Test
    void d_aThrowInsideAStepRePlans() {
        for (boolean onStart : new boolean[]{false, true}) {
            Throwing h = new Throwing();
            h.onStart = onStart;
            List<FactChange.Fact> facts = new ArrayList<>();
            Steps run = new Steps(h);
            run.start("goal.iron", List.of(new Step.Explore(5, "raw_iron")), 0, (failed, fact, why) -> {
                facts.add(fact);
                return new Steps.Routine(List.of(new Step.Wait(60)), null);
            });
            if (!onStart) run.tick(1);
            assertEquals(1, facts.size(), "re-planned once (" + (onStart ? "start" : "status") + "): " + run.why());
            FactChange.Fact f = facts.get(0);
            assertTrue(f.key().startsWith("threw at Z76SendBack2Test$Throwing."), f.key());
            assertTrue(f.text().contains(onStart ? "IllegalStateException: start blew up" : "NullPointerException"), f.text());
            assertTrue(run.running(), "the run goes on with the re-plan");
        }
    }
}
