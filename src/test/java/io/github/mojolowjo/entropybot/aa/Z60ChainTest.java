package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z60 review (Opus Design on Sonnet's review of job-z60): the chain's halves - a source whose structure fact is not
 * loaded yet makes the chain WAIT (rule 15, review 1), and "explore while paths compute" carries the expected first-answer
 * wait, only while first answers are pending (review 2).
 */
class Z60ChainTest {

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    private static Chain logs(PathPrice path, Spot... trees) {
        return NeedChain.resolve("oak_log", 1, ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", List.of(trees))).withPath(path));
    }

    /** Review 1: the only source's structure fact is not loaded: no explore, no "no source" - the chain waits with the reason. */
    @Test
    void aSourceWhoseStructureFactIsNotLoadedMakesTheChainWait() {
        ScenariosTest.world("wooden");
        Spot t = ScenariosTest.tree(10, 4).notLoaded();
        Chain c = logs(new ScenariosTest.FakePath().asLine(t), t);
        assertFalse(c.ok(), c.why());
        assertTrue(c.waiting(), c.missing());
        assertTrue(c.missing().endsWith(NeedChain.SOURCE_NOT_LOADED), c.missing());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof io.github.mojolowjo.entropybot.aa.moves.Step.Explore), c.steps().toString());
    }

    /** A fork with {@code pending} first answers counted (the fake's walks otherwise). */
    private static PathPrice pending(ScenariosTest.FakePath fake, int pending) {
        return new PathPrice() {
            @Override public Answer price(int x, int y, int z, double dist) { return fake.price(x, y, z, dist); }
            @Override public boolean pending() { return fake.pending(); }
            @Override public int firstAnswersPending() { return pending; }
        };
    }

    /**
     * Review 2: the only way still computing - "explore while paths compute". Nothing pending (or not counted): the wait costs
     * 0; 2 pending (run R's start after the trees landed): one round, pathCost.firstAnswerExpectS 2 s; never the 30 s cap.
     */
    @Test
    void theExploreWhilePathsComputeCarriesTheExpectedWaitOnlyWhilePending() {
        ScenariosTest.world("wooden");
        Spot b = ScenariosTest.tree(5, 4);
        double none = logs(pending(new ScenariosTest.FakePath().computing(b), 0), b).seconds();
        double unknown = logs(pending(new ScenariosTest.FakePath().computing(b), -1), b).seconds();
        Chain two = logs(pending(new ScenariosTest.FakePath().computing(b), 2), b);
        assertTrue(two.explore(), two.why());
        assertEquals(none, unknown, 1e-9, "not counted: the hands explore at once, nothing charged");
        assertEquals(2.0, two.seconds() - none, 1e-6, two.why());
    }
}
