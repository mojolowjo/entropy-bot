package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.Personality;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** docs/SCENARIOS.md S48, S50 (0.39.z42): never stand still for a path; a timed-out source step and an unreachable band re-plan. */
class Z42ReplanFactsTest {

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static Chain logs(ScenariosTest.FakePath fake, Spot... trees) {
        return NeedChain.resolve("oak_log", 1, ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", List.of(trees))).withPath(fake));
    }

    /** S48: two ways whose paths compute: the order does not wait, it explores meanwhile; a landed path prices it again. */
    @Test
    void s48_noWayWithACompletePathExploresMeanwhile() {
        ScenariosTest.world("wooden");
        Spot a = ScenariosTest.tree(10, 4), b = ScenariosTest.tree(20, 4);
        ScenariosTest.FakePath fake = new ScenariosTest.FakePath().computing(a).computing(b);
        Chain c = logs(fake, a, b);
        assertTrue(c.ok() && c.explore(), c.why());
        assertFalse(c.waiting(), c.why());
        assertTrue(c.why().contains("stand (paths computing: 2 ways waiting; standing up to " + io.github.mojolowjo.entropybot.aa.NeedChain.firstAnswerWaitS() + " s while their answers compute, then the walks still not known are taken by Baritone's estimate)"), c.why());      // z76 send-back 3 (rule 22): it stands
        assertEquals(List.of(new Step.AwaitPaths(io.github.mojolowjo.entropybot.aa.NeedChain.firstAnswerWaitS())), c.steps(), "the sources are known: it stands until a landed path prices the order");
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.ToBand), c.steps().toString());

        fake.path(a, 10);                                     // a's complete answer lands: the next pricing takes it
        Chain after = logs(fake, a, b);
        assertTrue(after.ok() && !after.explore(), after.why());
        assertTrue(after.why().startsWith("oak_log <- cut tree seen 10 m"), after.why());
    }

    /** One fake hand: the step runs until the clock says otherwise. */
    static final class Slow implements Steps.Hands {
        final List<String> ran = new ArrayList<>();
        @Override public String start(Step s) { ran.add(s.text()); return null; }
        @Override public Car.Status status(Step s) { return Car.Status.running("mining"); }
        @Override public void cancel(String why) {}
    }

    /** S50a: a source step that times out is the z33 source fact ("timed out after N s"): the order re-plans, not parks. */
    @Test
    void s50a_aTimedOutCollectRePlansWithTheSourceFact() {
        Tuning.set("steps.stepMaxS", 1);
        Slow h = new Slow();
        List<FactChange.Fact> asked = new ArrayList<>();
        Steps run = new Steps(h);
        run.start("owner goal", List.of(new Step.Collect("raw_iron_block", 1)), 0, (failed, fact, why) -> {
            asked.add(fact);
            return new Steps.Routine(List.of(new Step.Explore(5, "raw_iron")), null);
        });
        run.tick(1);
        run.tick(40);                                         // past 1 s (20 ticks)
        assertEquals(1, asked.size(), run.why());
        assertEquals("source collect raw_iron_block", asked.get(0).key());
        assertEquals("timed out after 1 s", asked.get(0).text());
        assertEquals(Steps.Phase.RUNNING, run.tick(41), run.why());
        assertEquals(List.of("collect raw_iron_block 1", "explore 5 until raw_iron"), h.ran);
    }

    /** S50a: a step with no source keeps the old end on a timeout. */
    @Test
    void s50a_aTimedOutStepWithNoSourceStillEnds() {
        Tuning.set("steps.stepMaxS", 1);
        Steps run = new Steps(new Slow());
        run.start("owner goal", List.of(new Step.Wait(5)), 0, (failed, fact, why) -> fail("no fact to re-plan on"));
        run.tick(1);
        assertEquals(Steps.Phase.FAILED, run.tick(40));
        assertTrue(run.why().contains("timed out after 1 s"), run.why());
    }

    /** S50b: "no path into the band" is a fact: that band is dropped for the order and the chain explores overland. */
    @Test
    void s50b_anUnreachableBandIsDroppedAndTheChainExploresOverland() {
        ScenariosTest.world("wooden");
        var w = ScenariosTest.w(Map.of(), false, false, Map.of()).withBand(word -> new int[]{5, 31});
        Chain first = NeedChain.resolve("cobblestone", 1, w);
        assertTrue(first.steps().stream().anyMatch(s -> s instanceof Step.ToBand), first.steps().toString());
        Step band = first.steps().stream().filter(s -> s instanceof Step.ToBand).findFirst().orElseThrow();
        String key = NeedChain.sourceKey(band);
        assertEquals("to y 5..31", key);
        FactChange.band(5, 31, "Baritone's goal ended at y 62 (no path into the band)");
        FactChange.Fact f = FactChange.take();
        assertEquals("source " + key, f.key());
        Chain again = NeedChain.resolve("cobblestone", 1, w.withoutSources(Map.of(key, f.text())));
        assertTrue(again.explore(), again.why());
        assertTrue(again.steps().stream().noneMatch(s -> s instanceof Step.ToBand), "overland: " + again.steps());
    }

    /** aa status on a fresh world: a saved file's stale "tools" key is dropped, not an error line. */
    @Test
    void theRetiredToolsKeyIsDroppedNotAnError() {
        Personality p = Personality.of(Map.of("tools", 1.0, "build", 30.0));
        assertTrue(p.errors.isEmpty(), p.errors.toString());
        assertEquals(1, p.retired.size());
        assertTrue(p.retired.get(0).startsWith("tools: the tools need went in 0.39.y1"), p.retired.toString());
        assertEquals(30, p.get("build"));
        assertFalse(p.toJson().contains("tools"));
    }
}
