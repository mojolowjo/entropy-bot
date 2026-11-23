package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.PathPrice;
import io.github.mojolowjo.entropybot.aa.PlanNodes;
import io.github.mojolowjo.entropybot.aa.Playbook;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-planner-stick (worlds run 2, the games w079 and w097 that ran out of time standing): planner results did not stick.
 * (a) A result was dropped on facts that had not changed for the plan: the running clock in a held walk's words (w079: 259
 * drops), the held list's reasons, which differ with the order that asks (w079: 104 drops). (b) An answer taken after the
 * drops was dropped at the next ask, 44 ms later (w079: 24 takes that changed nothing). (c) Drop reasons that rotate never
 * reached the take (w097: 126 results in a row dropped).
 */
class PlannerStickTest {
    @BeforeEach
    void on() {
        PlanNodes.forgetMine();
        PlanNodes.on(true);
    }

    @AfterEach
    void off() {
        PlanNodes.defaultOn();
        PlanNodes.forgetMine();
        TestData.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    /** w079 L1949: the same estimated walk, only the hold's clock in its words moved - the same price for a result. */
    @Test
    void anEstimatedWalk_isComparedByItsWalkAndKind_neverByItsWords() {
        PathPrice.Walk walk = new PathPrice.Walk(0.9689993120246848);
        PathPrice.Answer at31 = PathPrice.Answer.estimated(walk, NotKnownHold.words(31 * 20, 60, 16));
        PathPrice.Answer at34 = PathPrice.Answer.estimated(walk, NotKnownHold.words(34 * 20, 60, 16));
        assertNotEquals(at31.reason(), at34.reason(), "the words carry the clock");
        assertTrue(PlannerPort.samePrice(at31, at34), "the same walk, the same kind: the same price");
        assertTrue(PlannerPort.same(new PlanNodes.Fact("price", "k"), at31, at34), "and the same fact for the check");
        assertFalse(PlannerPort.samePrice(at31, PathPrice.Answer.estimated(new PathPrice.Walk(4), at31.reason())), "another estimate is another price");
        assertFalse(PlannerPort.samePrice(at31, PathPrice.Answer.priced(walk)), "an estimate that became a price is a change");
        assertFalse(PlannerPort.samePrice(at31, PathPrice.Answer.computing(null)), "the hold ended: a change");
    }

    /** w079 L1972 / L1976: the held list read by the learner and by another order - the same keys, other words. */
    @Test
    void theHeldList_isComparedWithoutTheWorldFactsAppendedWords_theReasonStillCounts() {
        String own = "collect oak_planks at -40 -47 -118: the walk to collect oak_planks at -40 -47 -118 failed: no path";
        String shared = own + " (a world fact: cobblestone learned it at -22 62 -94)";
        String other = "collect oak_planks at -47 -51 -120: the walk failed: no path";
        PlanNodes.Fact held = new PlanNodes.Fact("held", null);
        assertTrue(PlannerPort.same(held, List.of(own, other), List.of(other, shared)), "the same keys in another order with another order's words");
        assertFalse(PlannerPort.same(held, List.of(own), List.of(own, other)), "a new held key is a change");
        assertFalse(PlannerPort.same(held, List.of(own, other), List.of(own)), "a lifted key is a change");
        assertEquals(Set.of(own, "bare"), PlannerPort.heldLines(List.of(shared, "bare")));
        // review round 2: the planner reads a held reason (the dig column a band's end names) - another reason under the same key is a change
        String band = "dig to y 12 at 4 9";
        assertFalse(PlannerPort.same(held, List.of(band + ": ended short at column 4 9"), List.of(band + ": ended short at column 6 9")), "the same key, another column");
        assertFalse(PlannerPort.same(held, List.of(band + ": ended short at column 4 9"),
                List.of(band + ": ended short at column 6 9 (a world fact: cobblestone learned it at 4 30 9)")), "and so read through the world fact");
    }

    /** Drives the port until an answer is taken after the drops; {@code flip} changes one read fact after each landing (round i). */
    private static NeedChain.Legs untilTaken(PlannerPort port, PlannerWorker worker, Playbook pb, D6WorkerTest.Live live, java.util.function.IntConsumer flip) throws InterruptedException {
        NeedChain.Legs l = null;
        for (int i = 0; i <= PlannerPort.dropsBeforeTake() + 1; i++) {
            long seen = worker.landed();
            l = port.legs("b1", pb, Set.of(), live.world());
            if (!NeedChain.thinking(l.chain())) break;
            D6WorkerTest.awaitLanding(worker, seen);
            flip.accept(i);
        }
        assertFalse(NeedChain.thinking(l.chain()), "taken: " + l.chain().why());
        return l;
    }

    /**
     * Review round 2: the taken answer was resolved before the last change, so it is the stale one by that change (the
     * near tree's walk unreachable in its snapshot, priced live). It stands in at the next ask, a request on the live
     * facts goes out with it, and the result resolved on those facts replaces it one worker round later.
     */
    @Test
    void aTakenAnswer_standsInOnlyUntilAResultOnTheLiveFactsLands() throws InterruptedException {
        D6WorkerTest.data();
        D6WorkerTest.Live live = new D6WorkerTest.Live();
        List<String> log = new ArrayList<>();
        PlannerWorker worker = new PlannerWorker();
        PlannerPort port = new PlannerPort(worker, () -> 0, log::add);
        Playbook pb = Playbook.derive(D6WorkerTest.KIT, Playbook.facts(live.world(), null));
        D6WorkerTest.untilApplied(port, worker, pb, live, 30);
        PathPrice.Answer near = PathPrice.Answer.priced(new PathPrice.Walk(1)), none = PathPrice.Answer.unreachable("no path");
        live.walks.put("12,64,0", none);
        NeedChain.Legs withNone = PlanNodes.old(() -> NeedChain.resolvePlaybookLegs(pb, live.world()));
        live.walks.put("12,64,0", near);
        NeedChain.Legs withNear = PlanNodes.old(() -> NeedChain.resolvePlaybookLegs(pb, live.world()));
        assertFalse(io.github.mojolowjo.entropybot.aa.Deep.equal(withNone, withNear), "the fact that flips changes the plan");
        NeedChain.Legs taken = untilTaken(port, worker, pb, live, i -> live.walks.put("12,64,0", i % 2 == 0 ? none : near));
        NeedChain.Legs now = PlanNodes.old(() -> NeedChain.resolvePlaybookLegs(pb, live.world()));
        assertFalse(io.github.mojolowjo.entropybot.aa.Deep.equal(taken, now), "the taken answer is the stale one by the last change");
        long seen = worker.landed();
        NeedChain.Legs next = port.legs("b1", pb, Set.of(), live.world());
        assertTrue(io.github.mojolowjo.entropybot.aa.Deep.equal(next, taken), "the taken answer stands in, no thinking stand");
        D6WorkerTest.awaitLanding(worker, seen);                                    // and a request on the live facts went out with it
        NeedChain.Legs fresh = port.legs("b1", pb, Set.of(), live.world());
        assertTrue(io.github.mojolowjo.entropybot.aa.Deep.equal(fresh, now), "one worker round later: the resolve on the facts now");
        seen = worker.landed();
        assertTrue(io.github.mojolowjo.entropybot.aa.Deep.equal(port.legs("b1", pb, Set.of(), live.world()), now), "kept as any applied result");
        Thread.sleep(50);
        assertEquals(seen, worker.landed(), "and nothing more is asked of the worker");
    }

    /** Review round 2 (the smaller gap): a take whose difference is the bag stands in at the next ask too, while the bag stays as at the take. */
    @Test
    void aTakenAnswer_whoseDifferenceIsTheBag_standsInAtTheNextAsk() throws InterruptedException {
        D6WorkerTest.data();
        D6WorkerTest.Live live = new D6WorkerTest.Live();
        PlannerWorker worker = new PlannerWorker();
        PlannerPort port = new PlannerPort(worker, () -> 0, x -> {});
        Playbook pb = Playbook.derive(D6WorkerTest.KIT, Playbook.facts(live.world(), null));
        D6WorkerTest.untilApplied(port, worker, pb, live, 30);
        live.bag.put("minecraft:dirt", 4);
        untilTaken(port, worker, pb, live, i -> live.bag.put("minecraft:dirt", 5 + i));
        assertFalse(NeedChain.thinking(port.legs("b1", pb, Set.of(), live.world()).chain()), "the bag as at the take: the taken answer stands in");
        live.bag.put("minecraft:dirt", 99);
        assertTrue(NeedChain.thinking(port.legs("b1", pb, Set.of(), live.world()).chain()), "the bag moved since the take: thinking");
    }

    /** w079 06:02:15.066 -> .110: the answer taken after the drops is the answer at the next ask while the facts stay as they were at the take. */
    @Test
    void aTakenAnswer_isKeptAtTheNextAsk_whileTheFactsStayAsTaken() throws InterruptedException {
        D6WorkerTest.data();
        D6WorkerTest.Live live = new D6WorkerTest.Live();
        List<String> log = new ArrayList<>();
        PlannerWorker worker = new PlannerWorker();
        PlannerPort port = new PlannerPort(worker, () -> 0, log::add);
        Playbook pb = Playbook.derive(D6WorkerTest.KIT, Playbook.facts(live.world(), null));
        D6WorkerTest.untilApplied(port, worker, pb, live, 30);
        live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(9)));
        untilTaken(port, worker, pb, live, i -> live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(10 + i))));
        assertEquals(1, log.stream().filter(x -> x.contains("this answer is taken (rule 7)")).count(), log.toString());
        // the next ask, nothing changed since the take: the taken answer again, no stand
        NeedChain.Legs next = port.legs("b1", pb, Set.of(), live.world());
        assertFalse(NeedChain.thinking(next.chain()), "kept, no stand: " + next.chain().why());
        assertTrue(port.probe().contains("kept 1"), port.probe());
        // a fact that moves after the take is a change as ever: the request goes again
        live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(77)));
        assertTrue(NeedChain.thinking(port.legs("b1", pb, Set.of(), live.world()).chain()), "a fact changed since the take: asked anew");
    }

    /** w097 L989: the drop reasons rotate (one fact, then another) - the drops in a row are counted whatever the fact, and the answer is taken. */
    @Test
    void reasonsThatRotate_stillReachTheTake() throws InterruptedException {
        D6WorkerTest.data();
        D6WorkerTest.Live live = new D6WorkerTest.Live();
        List<String> log = new ArrayList<>();
        PlannerWorker worker = new PlannerWorker();
        PlannerPort port = new PlannerPort(worker, () -> 0, log::add);
        Playbook pb = Playbook.derive(D6WorkerTest.KIT, Playbook.facts(live.world(), null));
        D6WorkerTest.untilApplied(port, worker, pb, live, 30);
        live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(9)));
        int n = PlannerPort.dropsBeforeTake();
        NeedChain.Legs l = null;
        for (int i = 0; i <= n + 1; i++) {
            long seen = worker.landed();
            l = port.legs("b1", pb, Set.of(), live.world());
            if (!NeedChain.thinking(l.chain())) break;
            D6WorkerTest.awaitLanding(worker, seen);
            // one fact a round, the two in turn: a walk's price, then what is seen of the far tree
            if (i % 2 == 0) live.trees.set(1, new Spot(25, 64, 0, 25, "minecraft:oak_log", 7 + i));
            else live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(10 + i)));
        }
        Set<String> reasons = new java.util.TreeSet<>();
        for (String x : log) {
            int at = x.indexOf("re-planned while thinking: ");
            if (at >= 0) reasons.add(PlannerPort.reason(x.substring(at + "re-planned while thinking: ".length())));
        }
        assertTrue(reasons.size() >= 2, "the drop reasons rotated: " + reasons);
        assertFalse(NeedChain.thinking(l.chain()), "taken after " + n + " drops whatever their reasons: " + l.chain().why() + " | " + log);
        assertEquals(1, log.stream().filter(x -> x.contains("the facts kept changing " + n + " times") && x.contains("this answer is taken (rule 7)")).count(), log.toString());
    }
}
