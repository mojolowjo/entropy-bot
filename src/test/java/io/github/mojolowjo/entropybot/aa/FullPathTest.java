package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * docs/SCENARIOS.md S46-S47 (0.39.z41, owner 2026-10-23: "math the actual path it will take ... A path that is not known,
 * or whose chunks are not loaded, is 'path not known', not a number"). The walk fake stands in for Baritone's background
 * search (ScenariosTest.FakePath: complete seconds per cell, or computing / chunks not loaded / unreachable).
 */
class FullPathTest {
    static final double TOL = 0.1;

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    static Chain logs(int n, ScenariosTest.FakePath fake, Spot... trees) {
        return NeedChain.resolve("oak_log", n, ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", List.of(trees))).withPath(fake));
    }

    /**
     * S46 by hand: tree A 10 m complete (10 x 1.3 / 4.317 = 3.01 s) + 4 logs x 3.0 = 15.01 s; tree B 5 m computing: not
     * picked, "path not known yet (computing)". B's answer lands (1.51 s): 13.51 s, B wins.
     */
    @Test
    void s46_aWayWhoseWalkIsStillComputedIsNotPickedAndTheReplaceSwitchesWhenItLands() {
        ScenariosTest.world("wooden");
        Spot a = ScenariosTest.tree(10, 4), b = ScenariosTest.tree(5, 4);
        ScenariosTest.FakePath fake = new ScenariosTest.FakePath().asLine(a).computing(b);
        Chain c = logs(1, fake, a, b);
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(15.01, c.seconds(), TOL, c.why());
        assertTrue(c.why().startsWith("oak_log <- cut tree seen 10 m, walk 3.0 s (path): 15.0 s"), c.why());
        assertTrue(c.dropped().contains("tree 5 m: " + NeedChain.COMPUTING), c.dropped().toString());
        assertEquals("walk: path not known yet (computing)", NeedChain.COMPUTING);
        assertTrue(fake.pending());

        fake.path(b, 5);                                      // the complete answer lands
        Chain after = logs(1, fake, a, b);
        assertEquals(13.51, after.seconds(), TOL, after.why());
        assertTrue(after.why().startsWith("oak_log <- cut tree seen 5 m, walk 1.5 s (path)"), after.why());
        assertTrue(after.dropped().stream().anyMatch(d -> d.startsWith("tree 10 m: 15.0 s (dearer than tree 5 m)")), after.dropped().toString());
    }

    /** S46: the only way still computed: the chain waits (no park, no explore), priced again when the answer lands. */
    @Test
    void s46_theOnlyWayStillComputedWaitsAndNeverExplores() {
        ScenariosTest.world("wooden");
        Spot b = ScenariosTest.tree(5, 4);
        Chain c = logs(1, new ScenariosTest.FakePath().computing(b), b);
        assertFalse(c.ok(), c.why());
        assertFalse(c.explore(), c.why());
        assertTrue(c.waiting(), c.why());
        assertEquals(NeedChain.COMPUTING, c.missing());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Explore), c.steps().toString());
    }

    /** S47: chunks not loaded keeps the way unpicked with that text while a known way stands. */
    @Test
    void s47_chunksNotLoadedKeepAWayUnpickedWithThatText() {
        ScenariosTest.world("wooden");
        Spot a = ScenariosTest.tree(10, 4), b = ScenariosTest.tree(5, 4);
        Chain c = logs(1, new ScenariosTest.FakePath().asLine(a).chunks(b), a, b);
        assertTrue(c.ok(), c.why());
        assertTrue(c.why().startsWith("oak_log <- cut tree seen 10 m"), c.why());
        assertTrue(c.dropped().contains("tree 5 m: walk: path not known (chunks not loaded)"), c.dropped().toString());
        Chain only = logs(1, new ScenariosTest.FakePath().chunks(b), b);
        assertTrue(only.waiting() && !only.explore(), only.why());
    }

    /** S47: "unreachable" (a complete search found nothing) drops the source with Baritone's reason: the z33 source fact. */
    @Test
    void s47_unreachableDropsTheSourceForTheOrder() {
        ScenariosTest.world("wooden");
        Spot a = ScenariosTest.tree(10, 4), b = ScenariosTest.tree(5, 4);
        Chain c = logs(1, new ScenariosTest.FakePath().asLine(a).unreachable(b), a, b);
        assertTrue(c.ok(), c.why());
        String why = FactChange.unreachableText("no path: the complete search found none");
        assertTrue(c.dropped().contains("tree 5 m: " + why), c.dropped().toString());
        assertEquals(Map.of("cut at 5 64 0", why), c.unreachable());
        // the order's next pricing: the source is held with that reason (Goals.chain charges it in the order's sources)
        var w = ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", List.of(a, b))).withPath(new ScenariosTest.FakePath().asLine(a).asLine(b));
        Chain held = NeedChain.resolve("oak_log", 1, w.withoutSources(c.unreachable()));
        assertTrue(held.why().startsWith("oak_log <- cut tree seen 10 m"), held.why());
        assertTrue(held.dropped().contains("cut at 5 64 0: " + why), held.dropped().toString());
    }

    /** No straight line anywhere: no path provider is no walk price ("no path provider"), a way without a walk stands. */
    @Test
    void noPathProviderIsNoWalkPrice() {
        ScenariosTest.world("wooden");
        Spot a = ScenariosTest.tree(10, 4);
        Chain c = NeedChain.resolve("oak_log", 1, ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", List.of(a))).withPath(null));
        assertEquals(PathPrice.NOT_KNOWN + " (no path provider)", c.missing());
        Chain bag = NeedChain.resolve("oak_planks", 4, ScenariosTest.w(Map.of("minecraft:oak_log", 1), false, false, Map.of()).withPath(null));
        assertTrue(bag.ok() && !bag.explore(), bag.why());
    }

    /** A further spot whose walk is not known is left out of a way; the way stands on its known spots. */
    @Test
    void aFurtherSpotNotKnownIsLeftOutOfTheWay() {
        ScenariosTest.world("wooden");
        Spot a = ScenariosTest.tree(10, 4), b = ScenariosTest.tree(20, 4), far = ScenariosTest.tree(40, 4);
        Chain c = logs(8, new ScenariosTest.FakePath().asLine(a).computing(b).asLine(far), a, b, far);
        assertTrue(c.ok(), c.why());
        List<String> cuts = c.steps().stream().map(Step::text).toList();
        assertEquals(List.of("cut 4 at 10 64 0", "cut 4 at 40 64 0"), cuts);
    }

    /** Fake game for the re-price: a cut runs until told done, then its logs land and the tree is gone. */
    static final class Game implements Steps.Hands {
        final Map<String, Integer> bag = new HashMap<>();
        final List<Spot> trees = new ArrayList<>();
        final List<String> ran = new ArrayList<>();
        boolean done;

        @Override public String start(Step s) { ran.add(s.text()); done = false; return null; }

        @Override public Car.Status status(Step s) {
            if (!done) return Car.Status.running("cutting");
            if (s instanceof Step.ChopTree t) {
                bag.merge("minecraft:oak_log", t.logs(), Integer::sum);
                trees.removeIf(sp -> sp.x() == t.at()[0] && sp.y() == t.at()[1] && sp.z() == t.at()[2]);
            }
            return Car.Status.done("ok");
        }

        @Override public void cancel(String why) {}
    }

    /**
     * S46 in a run: 8 logs; A 10 m and the far tree 40 m complete, B 20 m computing: the run is cut A, cut far. B's path
     * lands while A is cut; at the next step the order is priced again on the facts of now (4 logs in the bag, A gone):
     * B 20 m (6.02 + 12.0) beats the far tree (12.05 + 12.0): the run switches and says so; not a failure.
     */
    @Test
    void s46_aLandedPathRePricesTheRunningOrderAtTheNextStep() {
        ScenariosTest.world("wooden");
        Spot a = ScenariosTest.tree(10, 4), b = ScenariosTest.tree(20, 4), far = ScenariosTest.tree(40, 4);
        ScenariosTest.FakePath fake = new ScenariosTest.FakePath().asLine(a).computing(b).asLine(far);
        Game g = new Game();
        g.trees.addAll(List.of(a, b, far));
        java.util.function.Supplier<Chain> plan = () -> NeedChain.resolve("oak_log", 8 - g.bag.getOrDefault("minecraft:oak_log", 0),
                ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", List.copyOf(g.trees))).withPath(fake));
        Chain first = plan.get();
        assertEquals(List.of("cut 4 at 10 64 0", "cut 4 at 40 64 0"), first.steps().stream().map(Step::text).toList());
        List<FactChange.Fact> asked = new ArrayList<>();
        Steps run = new Steps(g);
        run.start("owner goal", first.steps(), 0, (failed, fact, why) -> {
            asked.add(fact);
            Chain c = plan.get();
            return new Steps.Routine(c.ok() ? c.steps() : null, c.ok() ? null : c.why());
        });
        run.tick(1);
        fake.path(b, 20);                                     // B's complete answer lands while A is cut
        run.repriceAtNextStep(FactChange.priced("walk to 20 64 0: 6 s, complete"));
        g.done = true;
        run.tick(2);
        assertEquals(1, asked.size());
        assertTrue(asked.get(0).price(), "a new price, not a failure: " + asked.get(0));
        assertEquals(List.of("cut 4 at 10 64 0", "cut 4 at 20 64 0"), g.ran);
        assertTrue(run.why().contains("re-priced: walk to 20 64 0: 6 s, complete - switched to cut 4 at 20 64 0 first"), run.why());
    }
}
