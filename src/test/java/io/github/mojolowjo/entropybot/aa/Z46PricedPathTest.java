package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.OreBand;
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

/**
 * docs/SCENARIOS.md S56-S58 (0.39.z46, owner 2026-10-24: "run our pricing search the way Baritone runs its own, keep the
 * path, and walk that path when the way is picked - one search, not two"). S56/S57's search half is the fork's
 * (PricedPathStepTest); here: the switch, the strip's start at the band's peak, the explore that runs on, the watch's fact.
 */
class Z46PricedPathTest {

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** S56/S57: the priced path is on by default; 0 sends the chain back to z41's route (search once, walk fresh). */
    @Test
    void s56_thePricedPathIsOnAndASwitchSendsItBack() {
        assertTrue(PathPrice.pricedPathOn());
        assertTrue(PathPrice.pricedKeepTicks() > 0 && PathPrice.pricedMaxSegments() > 0);
        Tuning.set("pricedPath.on", 0);
        assertFalse(PathPrice.pricedPathOn());
    }

    /** S58: iron wanted at y 63, no exposed source: "to y 16 (priced path)" then the strip at y 16 - never a strip at y 63. */
    @Test
    void s58_theStripStartsAtTheBandThroughThePricedPath() {
        ScenariosTest.world("wooden", "stone");
        Chain c = NeedChain.resolve("raw_iron", 3, Z43StripWayTest.stripWorld(Map.of("minecraft:stone_pickaxe", 1), Map.of()));
        assertTrue(c.ok() && !c.explore(), c.why());
        int to = -1, strip = -1;
        for (int i = 0; i < c.steps().size(); i++) {
            Step s = c.steps().get(i);
            if (s instanceof Step.ToStrip t && to < 0) { to = i; assertEquals(16, t.y()); assertEquals("to y 16 (priced path)", t.text()); }
            if (s instanceof Step.Strip st) { strip = i; assertEquals(16, st.y(), "never a strip at the feet"); }
        }
        assertTrue(to >= 0 && strip == to + 1, c.steps().toString());
        Step.Strip st = (Step.Strip) c.steps().get(strip);
        Step.ToStrip t = (Step.ToStrip) c.steps().get(to);
        assertEquals(NeedChain.sourceKey(st), NeedChain.sourceKey(t), "no path down to the band drops the same strip way");
        assertEquals(NeedChain.stripHold(Z43StripWayTest.ironBand().key()), NeedChain.sourceKey(t));     // 0.39.z78 strip: its heading
    }

    /** S58 (runs E-G: coal's 0..192 band was struck at the feet, y 70): a peak above the feet stays the peak. */
    @Test
    void s58_aPeakAboveTheFeetIsNeverClampedToTheFeet() {
        List<OreBand.Feature> coal = List.of(new OreBand.Feature(0, 192, 0, 20, 17), new OreBand.Feature(136, 320, 184, 30, 17));
        assertEquals(96, OreBand.bestY(coal, 70), "the lowest peak above, the priced path tells whether it is reached");
        assertEquals(16, OreBand.bestY(Z43StripWayTest.iron(), 63), "a peak at or below the feet wins");
    }

    /** S58 / rule 3: a strip that ended short says the strip's source fact with Baritone's words. */
    @Test
    void s58_aStripEndShortIsTheStripsSourceFact() {
        Step.Strip st = new Step.Strip(List.of("minecraft:iron_ore"), Map.of("iron", 3), 16, "east", 0);
        FactChange.strip(st.key(), "no path", "strip: no path (strip east feet y 16: 0 blocks)");
        FactChange.Fact f = FactChange.take();
        assertEquals("source " + NeedChain.sourceKey(st), f.key());
        assertTrue(f.text().contains("no path"), f.text());
    }
    /** A fake run: every step runs until told; the explore never ends by itself. */
    static final class Hands implements Steps.Hands {
        final List<String> ran = new ArrayList<>(), cancelled = new ArrayList<>();

        @Override public String start(Step s) { ran.add(s.text()); return null; }

        @Override public Car.Status status(Step s) { return Car.Status.running("running"); }

        @Override public void cancel(String why) { cancelled.add(why); }
    }

    /** z46 (runs E-G): a path answer re-prices the running explore where it runs; same pick: it runs on, not cancelled. */
    @Test
    void anExploreWhilePathsRunsOnWhenThePickDidNotChange() {
        Step explore = new Step.AwaitPaths(30);           // z76 send-back 3: the standing step (was the explore while paths compute)
        Step cut = new Step.ChopTree(new int[]{10, 64, 0}, 4, null);
        Hands h = new Hands();
        Steps run = new Steps(h);
        List<Step>[] next = new List[]{List.of(explore, cut)};
        run.start("owner goal", List.of(explore, cut), 0, (failed, fact, why) -> new Steps.Routine(next[0], null));
        run.tick(1);
        run.repriceAtNextStep(FactChange.priced("walk to 20 64 0: 6 s, complete"));
        run.tick(2);
        assertTrue(h.cancelled.isEmpty(), "the same pick: the explore runs on: " + h.cancelled);
        assertEquals(List.of(explore.text()), h.ran);
        // the next answer changes the pick: the explore is cancelled and the new way starts
        next[0] = List.of(cut);
        run.repriceAtNextStep(FactChange.priced("walk to 10 64 0: 3 s, complete"));
        run.tick(3);
        assertEquals(1, h.cancelled.size(), h.cancelled.toString());
        assertEquals(List.of(explore.text(), cut.text()), h.ran);
    }

    /** z46 (run G 23:40:12): the no-progress watch is the step's source fact; the order re-plans instead of parking. */
    @Test
    void theNoProgressWatchRePlansOnTheStepsSourceFact() {
        Step cut = new Step.ChopTree(new int[]{10, 64, 0}, 4, null), other = new Step.ChopTree(new int[]{20, 64, 0}, 4, null);
        Hands h = new Hands();
        Steps run = new Steps(h);
        List<FactChange.Fact> facts = new ArrayList<>();
        run.start("owner goal", List.of(cut), 0, (failed, fact, why) -> { facts.add(fact); return new Steps.Routine(List.of(other), null); });
        run.tick(1);
        assertTrue(run.noProgress("no progress 180 s"));
        assertEquals(1, facts.size());
        assertEquals("source " + NeedChain.sourceKey(cut), facts.get(0).key());
        assertEquals("no progress 180 s at " + cut.text(), facts.get(0).text());
        assertEquals(List.of(cut.text(), other.text()), h.ran);
        Steps none = new Steps(new Hands());
        none.start("plain", List.of(cut), 0, null);
        none.tick(1);
        assertFalse(none.noProgress("no progress 180 s"), "no order behind the run: the watch ends it as before");
    }
}
