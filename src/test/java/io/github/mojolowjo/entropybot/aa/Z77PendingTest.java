package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 pending (run BV 13:59:36, owner's seed, 84 s into the order: "trip b1: leg 4/4 (at band [...]) has no way: it
 * ends the orders iron_helmet, ..." - each armour order "no way to get it: playbook: at band: iron_ingot ends in an explore
 * (no known source for lava_bucket) | dropped: cobblestone: walk: path not known yet (computing); ...; stick 9 m: walk: path
 * not known yet (computing; queued ...)": the cheaper ways had no answer yet). A leg is "no way" only when every one of its
 * ways has a final answer (rule 15: an unknown is never a verdict); a way still computing or queued makes the leg "not
 * priced yet" - the trip stands (rule 22), said, nobody parked.
 * <p>BV's shape on Z77NoWayTest's kit: ONE table in the bag (the pickaxe's band leg places it), so the armour's band leg 5/5
 * needs a table from logs. A tree 20 m up here: its walk computing is BV's pending way; the other way, an explore for logs,
 * is no way under ground. The walk answered UNREACHABLE: no way, the armour orders end (as Z77NoWayTest). Answered with a
 * price: the leg is priced (the logs met before going down).
 */
class Z77PendingTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    static final Spot TREE = ScenariosTest.tree(20, 8);

    /** The kit world with one table and a tree 20 m whose walk answers {@code tree}; every other walk priced as Z77PlaybookTest's. */
    static World bv(PathPrice.Answer tree) {
        return Z77PlaybookTest.world(Z77NoWayTest.oneTable(), Map.of("oak_log", List.of(TREE)))
                .withPath((x, y, z, d) -> y >= 60 && x >= 15 ? tree : Z77PlaybookTest.PATHS.price(x, y, z, d));
    }

    static final PathPrice.Answer COMPUTING = PathPrice.Answer.computing("queued: pathCost.maxPending 4 searches in flight");

    /** The planner: the band leg's verdict waits on the tree's walk - not priced yet, no failed leg; the trip stands, said. */
    @Test
    void aLegWhoseWaysStillCompute_isNotPricedYet_theTripStands() {
        Z77PlaybookTest.data();
        World w = bv(COMPUTING);
        Playbook pb = Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(w, null));
        assertTrue(pb.ok(), pb.why());
        NeedChain.Legs legs = NeedChain.resolvePlaybookLegs(pb, w);
        Chain pc = legs.chain();
        assertFalse(pc.ok(), pc.why());
        assertTrue(pc.missing().startsWith(NeedChain.NOT_PRICED + "leg 5/5 (" + pb.legs().get(4).text() + ")" + NeedChain.WAITS_ON), pc.missing());
        assertTrue(pc.missing().contains(PathPrice.NOT_KNOWN + " yet (computing; queued"), "says what it waits for: " + pc.missing());
        assertTrue(NeedChain.waitsHere(pc.missing()), pc.missing());
        assertEquals(-1, legs.failedLeg(), "a wait is no leg's no-way");
        assertNull(legs.failed(pb));
        BatchWay.Shaped s = BatchWay.shape("b1", 6, pb, pc, legs.starts());
        assertEquals(List.of(new Step.AwaitPaths(NeedChain.firstAnswerWaitS())), s.chain().steps(), s.line());
        assertFalse(s.settled(), "priced again when the answer lands");
        assertTrue(s.line().contains("stand: " + NeedChain.NOT_PRICED + "leg 5/5"), s.line());
    }

    /** The batch: every order stands on BV's facts; nobody is parked, no leg ends anyone, the owner hears what the trip waits for. */
    @Test
    void bvsFacts_theTripWaits_nobodyParked() {
        Z77PlaybookTest.data();
        World w = bv(COMPUTING);
        FakePort port = new FakePort();
        List<Goals.Goal> gs = new ArrayList<>();
        Blackboard bb = Z77PlaybookTest.batchBoard(port, new Clock.Fake(10_000_000), () -> w, gs);
        for (Goals.Goal g : List.of(gs.get(1), gs.get(2))) {          // the pickaxe, then the helmet (a leg-5 order) asks
            Chain ch = Goals.chain(bb, g);
            assertTrue(ch.ok(), ch.why());
            assertEquals(List.of(new Step.AwaitPaths(NeedChain.firstAnswerWaitS())), ch.steps(), g.r.key() + ": " + ch.why());
        }
        for (Goals.Goal g : gs) assertNull(g.parked, g.r.key() + " is not parked");
        assertTrue(port.log.stream().noneMatch(l -> l.contains("has no way")), port.log.toString());
        assertTrue(port.told.stream().noneMatch(l -> l.contains(BatchWay.NO_WAY)), port.told.toString());
        assertTrue(port.told.stream().anyMatch(l -> l.startsWith("trip b1 (6 orders): stand: " + NeedChain.NOT_PRICED + "leg 5/5")), port.told.toString());
    }

    /** The same facts with the tree's walk answered UNREACHABLE: every way final - the leg's no-way ends the armour orders, as now. */
    @Test
    void theSameFactsAnsweredUnreachable_isNoWay() {
        Z77PlaybookTest.data();
        World w = bv(PathPrice.Answer.unreachable("no path: Baritone's search made no progress (test)"));
        Playbook pb = Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(w, null));
        NeedChain.Legs legs = NeedChain.resolvePlaybookLegs(pb, w);
        assertFalse(legs.chain().ok(), legs.chain().why());
        assertFalse(NeedChain.waitsHere(legs.chain().missing()), legs.chain().missing());
        assertEquals(4, legs.failedLeg(), "the armour's band leg: " + legs.chain().why());
        FakePort port = new FakePort();
        List<Goals.Goal> gs = new ArrayList<>();
        Blackboard bb = Z77PlaybookTest.batchBoard(port, new Clock.Fake(10_000_000), () -> w, gs);
        Chain ch = Goals.chain(bb, gs.get(1));
        assertTrue(ch.ok() && ch.steps().stream().anyMatch(s -> s instanceof Step.Craft c && c.item().contains("iron_pickaxe")), ch.why());
        assertNull(gs.get(0).parked);
        assertNull(gs.get(1).parked);
        for (Goals.Goal a : gs.subList(2, 6)) {
            assertNotNull(a.parked, a.r.key() + " ends");
            assertTrue(a.parked.startsWith(BatchWay.NO_WAY + legs.chain().why()), a.parked);
        }
    }

    /** The walk answered with a price: the leg is priced (the logs met before going down), the trip stands as one chain. */
    @Test
    void theSameFactsAnsweredPriced_theLegIsPriced() {
        Z77PlaybookTest.data();
        World w = bv(PathPrice.Answer.priced(new PathPrice.Walk(20 / Unit.WALK_BPS)));
        Playbook pb = Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(w, null));
        Chain pc = NeedChain.resolvePlaybook(pb, w);
        assertTrue(pc.ok() && !pc.explore(), pc.why());
    }
}
