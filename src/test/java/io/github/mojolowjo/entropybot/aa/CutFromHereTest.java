package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-hold-sight point 3, replaying worlds run 4 w037 (log lines 808-968): the bot at 25 33 -13, its column open to the sky
 * but the ground round it at 70 (27 under ground), planned "cut 5 mangrove_log at 30 70 -7"; Baritone's chop said "no path
 * to any target", the run held "cut at 30 70 -7" (a fact of the tree, the trunk hold then took the whole tree), and the next
 * plan took the next surface tree from the same hole: 10 no-paths, 9 trees held one by one. The fact is "no way from
 * here": keyed by the feet, held by distance, no tree held; the next plan prices the walk up (or the climb) before the cut.
 * Tested on the tick's resolve and through the planner worker's path (SyncPlanner: snapshot, worker, live check).
 */
class CutFromHereTest {
    private static final int[] FEET = {25, 33, -13};
    private static final Spot TREE = new Spot(30, 70, -7, 38, "minecraft:oak_log", 5);
    private static final String WHY = "no path to any target";

    @BeforeEach
    void data() { ScenariosTest.toolWorld(); }

    @AfterEach
    void clear() { Tuning.reset(); }

    /** w037's place: under ground (the ground at 70 round the sky-open column), a tree seen on the surface. */
    private static World w037(int[] feet) {
        NeedChain.Surface over = new NeedChain.Surface(37, 24, 73, -16, 40, true);
        return ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", List.of(TREE)))
                .withWhere(new NeedChain.WhereFacts(w -> null, () -> over, () -> feet.clone(), () -> null));
    }

    private static World held(World w) {
        FactChange.Fact f = FactChange.cutFromFact(FEET, WHY);
        return w.withoutSources(Map.of(FactChange.ownSourceKey(f), f.text()));
    }

    @Test
    void theCutsFailureFromUnderGround_isThePlacesFact_notTheTrees() {
        FactChange.Fact f = FactChange.cutFromFact(FEET, WHY);
        assertEquals("source " + NeedChain.cutFromKey(FEET), f.key());
        String own = FactChange.ownSourceKey(f);
        assertEquals("cut from 25 33 -13", own, "held by the place's key");
        assertTrue(NeedChain.heldCuts(List.of(own)).isEmpty(), "no 'cut at': the trunk hold holds no tree");
        assertNull(NeedChain.trunksHeld(NeedChain.heldCuts(List.of(own)), w -> List.of(TREE)), "no tree held for the order's life");
        assertTrue(f.text().contains("no way from here"), f.text());
    }

    @Test
    void w037_theNextPlanPricesTheWayUpBeforeTheCut() {
        World open = w037(FEET);
        Chain before = NeedChain.resolve("oak_log", 5, open);
        assertTrue(before.ok() && before.steps().get(0) instanceof Step.ChopTree, "the run's plan: the cut straight from under ground: " + before.steps() + " / " + before.why());
        Chain after = NeedChain.resolve("oak_log", 5, held(open));
        assertTrue(after.ok(), after.why());
        assertInstanceOf(Step.ToSurface.class, after.steps().get(0), "the walk up first: " + after.steps() + " / " + after.why());
        Step.ChopTree cut = assertInstanceOf(Step.ChopTree.class, after.steps().get(1), after.steps().toString());
        assertArrayEquals(new int[]{30, 70, -7}, cut.at(), "the same tree, not held: " + after.why());
        assertTrue(after.why().contains("walk up to the surface at 24 73 -16"), after.why());
        assertTrue(after.why().contains("from the surface at 24 73 -16"), "the cut's walk is from where the way up leaves the bot: " + after.why());
        assertTrue(after.cost() > before.cost(), "the way up is priced in: " + after.cost() + " vs " + before.cost());
    }

    @Test
    void w037_thePlacesFactLiftsOnceTheBotMoved() {
        int[] away = {FEET[0] + 30, 70, FEET[2]};
        NeedChain.Surface top = new NeedChain.Surface(0, away[0], away[1], away[2], 0);
        World there = ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", List.of(TREE)))
                .withWhere(new NeedChain.WhereFacts(w -> null, () -> top, () -> away.clone(), () -> null));
        Chain c = NeedChain.resolve("oak_log", 5, held(there));
        assertTrue(c.ok() && c.steps().get(0) instanceof Step.ChopTree, "held by distance: from elsewhere the cut is priced as ever: " + c.steps() + " / " + c.why());
    }

    @Test
    void w037_throughThePlannerWorker_theSameWay() {
        World w = held(w037(FEET));
        var sync = new io.github.mojolowjo.entropybot.commands.SyncPlanner(null);
        var ask = new BatchWay.OwnAsk("oak_log", List.of("minecraft:oak_log"), 5, false);
        Chain worker = sync.own("goal.oak_log", ask, w);
        Chain tick = ask.resolve(w);
        assertTrue(sync.drops.isEmpty(), "no planner drop: " + sync.drops);
        assertTrue(worker.ok(), worker.why());
        assertEquals(tick.steps().stream().map(Step::text).toList(), worker.steps().stream().map(Step::text).toList(), "the worker and the tick price the same way");
        assertInstanceOf(Step.ToSurface.class, worker.steps().get(0), worker.why());
    }
}
