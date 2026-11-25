package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.FakePaths;
import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.PlanNodes;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-own-aside-worker (worlds run 4 on job-d6 596d10a0, 7 games: w003, w007, w010, w055, w057, w058, w063): the order's own
 * held -d amount counted twice on the planner worker. Goals.ownAsk asks only the rest (16 oak_log -d, 13 held: 3) and the
 * tick's OwnAsk.resolve takes the 13 in the bag off it, but PlannerWorker.resolve started the resolve from the snapshot's
 * whole live bag (NeedChain.initialBag reads offThread's bag first), so the 3 asked were "in the bag" and the order parked
 * "no way to get it: oak_log is in the bag: 0.0 s"; the deposit then went short. Here: the worker's own chain is the
 * tick's chain, the bag it starts from is the runner's craft bag (the bag less keptFromUse), and its result passes the
 * port's live check.
 */
class OwnAskWorkerAsideTest {
    static final String OAK = "minecraft:oak_log";

    @BeforeEach
    void on() {
        PlanNodes.forgetMine();
        PlanNodes.on(true);
        D6WorkerTest.data();
    }

    @AfterEach
    void off() {
        PlanNodes.defaultOn();
        PlanNodes.forgetMine();
        TestData.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    /** The live World: 13 oak_log in the bag, all of them the order's own held -d amount (16 asked), no tree known. */
    static NeedChain.World live() {
        NeedChain.Lookup none = (word, skip) -> List.of();
        return FakePaths.paths(new NeedChain.World(Map.of(OAK, 13, "minecraft:dirt", 4), false, false, none, null, null, null, 0));
    }

    /** Goals.ownAsk's answer for 16 oak_log -d with 13 held: 3 asked, 13 aside. */
    static final BatchWay.OwnAsk ASK = new BatchWay.OwnAsk(OAK, List.of(OAK), 3, false, Map.of(OAK, 13));

    /** The worker's own chain, asked again with the facts it read until the live check holds (as the port's fetch rounds). */
    static PlannerWorker.Done settled(NeedChain.World w) {
        PlannerWorker.Done d = PlannerWorker.resolve(new PlannerWorker.Job("own o1", 1, null, Set.of(), PlannerSnapshot.take(w, List.of(), null, 1, 0), ASK));
        for (int round = 2; round < 2 + PlannerPort.ROUNDS; round++) {
            assertNull(d.error(), String.valueOf(d.error()));
            List<String> ch = PlannerPort.check(d, w, new HashMap<>());
            if (ch.isEmpty()) return d;
            assertTrue(ch.stream().allMatch(c -> c.startsWith(PlannerPort.MISS)), "only fetches, never the bag dropped: " + ch);
            d = PlannerWorker.resolve(new PlannerWorker.Job("own o1", round, null, Set.of(), PlannerSnapshot.take(w, d.read().facts().keySet(), null, round, 0), ASK));
        }
        fail("the worker's own chain never held on the live World: " + PlannerPort.check(d, w, new HashMap<>()));
        return d;
    }

    @Test
    void theWorkerPricesTheRest_notTheHeldAmount() {
        NeedChain.World w = live();
        PlannerWorker.Done d = settled(w);
        NeedChain.Chain c = d.legs().chain();
        assertFalse(c.why().contains("oak_log is in the bag"), "the 13 held are not the 3 more asked: " + c.why());
        assertFalse(c.steps().isEmpty(), "3 more oak_log are gathered: " + c.why());
        NeedChain.Chain tick = ASK.resolve(w);
        assertEquals(tick.why(), c.why(), "one answer whoever resolves");
        assertEquals(String.valueOf(tick.steps()), String.valueOf(c.steps()));
    }

    @Test
    void theWorkersStartBagIsTheRunnersCraftBag() {
        NeedChain.World w = live();
        PlannerSnapshot snap = PlannerSnapshot.take(w, List.of(), null, 1, 0);
        // the runner's craft bag (Crafting.planBag): the live bag less keptFromUse (the order's own held 13)
        Map<String, Integer> planBag = new java.util.LinkedHashMap<>(NeedChain.liveBag(w));
        planBag.computeIfPresent(OAK, (k, v) -> v - 13 > 0 ? v - 13 : null);
        assertEquals(planBag, ASK.bagLess(snap.bag()), "the worker never prices from an amount the runner holds");
        assertEquals(13, snap.bag().get(OAK), "the snapshot's bag itself stays whole");
        PlannerWorker.Done d = settled(w);
        Integer read = d.read().bag().get(OAK);
        assertTrue(read == null || read == 0, "the resolve read oak_log as the runner has it free (none): " + d.read().bag());
        // all 16 held: the ask is met (0), the bag holds none free - nothing gathered, nothing in the bag counted
        BatchWay.OwnAsk all = new BatchWay.OwnAsk(OAK, List.of(OAK), 0, false, Map.of(OAK, 13));
        assertFalse(all.bagLess(snap.bag()).containsKey(OAK), "a word held whole is not in the start bag (as liveBag)");
    }

    @Test
    void throughThePort_theOwnResultIsApplied_neverDroppedAsBagChanged() throws InterruptedException {
        NeedChain.World w = live();
        List<String> log = new ArrayList<>();
        PlannerWorker worker = new PlannerWorker();
        PlannerPort port = new PlannerPort(worker, () -> 0, log::add);
        NeedChain.Chain c = port.own("own o1", ASK, w);
        long until = System.nanoTime() + 30_000_000_000L;
        while (NeedChain.thinking(c)) {
            if (System.nanoTime() > until) fail("the planner did not answer within 30 s: " + c.why() + " " + log);
            long seen = worker.landed();
            while (worker.landed() == seen && worker.busy("own o1") && System.nanoTime() < until) Thread.sleep(2);
            c = port.own("own o1", ASK, w);
        }
        assertFalse(c.why().contains("oak_log is in the bag"), c.why());
        assertTrue(log.stream().noneMatch(l -> l.contains("re-planned while thinking: bag")), "an own result is never dropped as bag changed: " + log);
        assertTrue(log.stream().noneMatch(l -> l.contains("this answer is taken")), "no answer taken against the facts: " + log);
        // asked again on the same facts: the applied result is kept (no new worker round)
        NeedChain.Chain again = port.own("own o1", ASK, w);
        assertFalse(NeedChain.thinking(again), again.why());
    }
}
