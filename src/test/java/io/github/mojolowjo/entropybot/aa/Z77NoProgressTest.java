package io.github.mojolowjo.entropybot.aa;

import baritone.api.pathing.calc.IPathCost;
import baritone.api.pathing.calc.IPricedPath;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.GameTimes;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.commands.PricedAnswers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 noprogress (run DH 04:39:43-58, laptop): the descent to y 16 asked from -22 70 1 (the bot had just cut a tree
 * there) came back "walk to -22 16 1: UNREACHABLE (no path: Baritone's search made no progress (no progress))"; the stand
 * ended, the leg "has no way" ended the iron pickaxe's order and, within a second, the four armour orders ("no way to get it:
 * playbook: descent to y 16: unreachable"). The fork's "no progress" is a search stopped by its budget with the start still
 * its best node (IPricedPath.Step.of: any stop but EXHAUSTED), not the search's end: not known (rule 15). Only the search's
 * end (the fork's "unreachable") is a no-way.
 * <p>DH's shape on Z77PlaybookTest's kit (the torches done: five orders, the descent is leg 1/4): its walk answers through
 * {@link PricedAnswers#noPath}, as LivePathPrice gives the fork's reasons to the chain.
 */
class Z77NoProgressTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    /** DH: the torches done (04:38:08), the iron pickaxe and the armour open. */
    static final List<Playbook.Target> DH_ORDERS = Z77PlaybookTest.KIT.subList(1, 6);

    /** The chain's answer for the fork's no-path reason, as LivePathPrice gives it. */
    static PathPrice.Answer fork(String reason) {
        // J6b (job-vein a2d4e1c8): the fork's flags with its reason - "unreachable" is an exhausted graph, "no progress" a budget stop
        boolean exhausted = IPathCost.Answer.UNREACHABLE.equals(reason), budget = IPricedPath.NO_PROGRESS.equals(reason);
        return PricedAnswers.noPath(reason, exhausted, budget, IPathCost.Answer.CHUNKS);
    }

    /** The kit world; the descent's walk (the only one below y 60) answers {@code descent}, every other walk priced. */
    static World dh(PathPrice.Answer descent) {
        return Z77PlaybookTest.world(Z77PlaybookTest.kitBag(9), Map.of())
                .withPath((x, y, z, d) -> y < 60 ? descent : Z77PlaybookTest.PATHS.price(x, y, z, d));
    }

    /** The fork's reasons: "no progress" is not known (computing, the first-answer clock kept); the search's end is UNREACHABLE. */
    @Test
    void onlyTheSearchsEndIsANoWay() {
        PathPrice.Answer np = fork(IPricedPath.NO_PROGRESS);
        assertEquals(PathPrice.Kind.COMPUTING, np.kind(), np.toString());
        assertEquals(PricedAnswers.NO_PROGRESS_WAIT, np.reason());
        assertTrue(PricedAnswers.waits(np), "keeps the first-answer clock");
        NeedChain.Walked wk = NeedChain.walked(np);
        assertTrue(wk.fail().startsWith(PathPrice.NOT_KNOWN + " yet (computing; no progress"), wk.fail());
        assertTrue(NeedChain.waitsHere(wk.fail()), "a wait, never a verdict: " + wk.fail());
        assertEquals(PathPrice.Kind.UNREACHABLE, fork(IPathCost.Answer.UNREACHABLE).kind());
        assertFalse(NeedChain.waitsHere(NeedChain.walked(fork(IPathCost.Answer.UNREACHABLE)).fail()));
        assertEquals(PathPrice.Kind.CHUNKS, fork(IPathCost.Answer.CHUNKS).kind());
        assertEquals(new PathPrice.Answer(null, PathPrice.Kind.NONE, "error"), fork("error"), "any other reason: no answer, the fork's words");
    }

    /** DH's facts: the descent's search made no progress - no leg's no-way, the trip stands, no order ends. */
    @Test
    void dhsShape_noOrderEnds_theTripStands() {
        Z77PlaybookTest.data();
        World w = dh(fork(IPricedPath.NO_PROGRESS));
        Playbook pb = Playbook.derive(DH_ORDERS, Playbook.facts(w, null));
        assertTrue(pb.ok(), pb.why());
        assertEquals(Playbook.Kind.DESCENT, pb.legs().get(0).kind(), "DH: the descent is leg 1: " + pb.why());
        NeedChain.Legs legs = NeedChain.resolvePlaybookLegs(pb, w);
        Chain pc = legs.chain();
        assertFalse(pc.ok(), pc.why());
        assertTrue(NeedChain.waitsHere(pc.missing()), pc.missing());
        assertTrue(pc.missing().contains("descent to y 16: " + PathPrice.NOT_KNOWN + " yet (computing; no progress"), "says what it waits for: " + pc.missing());
        assertEquals(-1, legs.failedLeg(), "a wait is no leg's no-way");
        assertNull(legs.failed(pb));
        BatchWay.Shaped s = BatchWay.shape("b1", 5, pb, pc, legs.starts());
        assertEquals(List.of(new Step.AwaitPaths(NeedChain.firstAnswerWaitS())), s.chain().steps(), s.line());

        FakePort port = new FakePort();
        List<Goals.Goal> gs = new ArrayList<>();
        Blackboard bb = Z77PlaybookTest.batchBoard(port, new Clock.Fake(10_000_000), () -> w, gs);
        gs.get(0).parked = "test: the torches are done (DH 04:38:08)";
        for (Goals.Goal g : gs.subList(1, 6)) {
            Chain ch = Goals.chain(bb, g);
            assertTrue(ch.ok(), g.r.key() + ": " + ch.why());
            assertEquals(List.of(new Step.AwaitPaths(NeedChain.firstAnswerWaitS())), ch.steps(), g.r.key() + ": " + ch.why());
        }
        for (Goals.Goal g : gs.subList(1, 6)) assertNull(g.parked, g.r.key() + " is not parked");
        assertTrue(port.log.stream().noneMatch(l -> l.contains("has no way")), port.log.toString());
        assertTrue(port.told.stream().noneMatch(l -> l.contains(BatchWay.NO_WAY)), port.told.toString());
    }

    /**
     * The descent asked again: from the next cell (the fork keeps "no progress" for its own start only) a complete path, or from
     * the same cell past pathCost.firstAnswerWaitS Baritone's estimate - either way the trip runs its descent.
     */
    @Test
    void theDescentAskedAgain_theTripRunsItsDescent() {
        Z77PlaybookTest.data();
        int waitS = NeedChain.firstAnswerWaitS();
        PathPrice.Answer est = PricedAnswers.afterWait(fork(IPricedPath.NO_PROGRESS), (long) waitS * GameTimes.TPS, waitS, 40.0);
        assertEquals(PathPrice.Kind.ESTIMATED, est.kind(), est.toString());
        PathPrice.Answer before = PricedAnswers.afterWait(fork(IPricedPath.NO_PROGRESS), (long) waitS * GameTimes.TPS - 1, waitS, 40.0);
        assertEquals(PathPrice.Kind.COMPUTING, before.kind(), "before the wait: still not known");
        for (PathPrice.Answer a : List.of(est, PathPrice.Answer.priced(new PathPrice.Walk(48 * 0.6)))) {
            World w = dh(a);
            Playbook pb = Playbook.derive(DH_ORDERS, Playbook.facts(w, null));
            NeedChain.Legs legs = NeedChain.resolvePlaybookLegs(pb, w);
            Chain pc = legs.chain();
            assertTrue(pc.ok(), a.kind() + ": " + pc.why());
            assertTrue(pc.steps().get(0) instanceof Step.ToStrip, a.kind() + ": the descent first: " + pc.steps());
        }
    }

    /** The same facts with the search run to its end (UNREACHABLE): a final no-way still ends every order that needs the descent. */
    @Test
    void aFinalNoWayStillEndsTheOrdersThatNeedIt() {
        Z77PlaybookTest.data();
        World w = dh(fork(IPathCost.Answer.UNREACHABLE));
        Playbook pb = Playbook.derive(DH_ORDERS, Playbook.facts(w, null));
        NeedChain.Legs legs = NeedChain.resolvePlaybookLegs(pb, w);
        assertFalse(legs.chain().ok(), legs.chain().why());
        assertFalse(NeedChain.waitsHere(legs.chain().missing()), legs.chain().missing());
        assertEquals(0, legs.failedLeg(), "the descent: " + legs.chain().why());
        assertTrue(legs.chain().why().contains("descent to y 16: unreachable (Baritone: no path: the complete search found none)"), legs.chain().why());

        FakePort port = new FakePort();
        List<Goals.Goal> gs = new ArrayList<>();
        Blackboard bb = Z77PlaybookTest.batchBoard(port, new Clock.Fake(10_000_000), () -> w, gs);
        gs.get(0).parked = "test: the torches are done (DH 04:38:08)";
        for (Goals.Goal g : gs.subList(1, 6)) {
            if (g.parked != null) continue;                     // ended by another order's leg (BatchWay)
            Chain ch = Goals.chain(bb, g);
            assertFalse(ch.ok(), g.r.key() + ": " + ch.why());
            assertFalse(NeedChain.waitsHere(ch.missing()), g.r.key() + ": a verdict, not a wait: " + ch.missing());
            g.parked = "test: its job parked it (" + ch.why() + ")";
        }
        assertTrue(port.log.stream().anyMatch(l -> l.contains("has no way: it ends the order")), port.log.toString());
    }
}
