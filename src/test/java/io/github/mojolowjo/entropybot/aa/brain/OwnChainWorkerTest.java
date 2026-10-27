package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.TickLaps;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.commands.PlannerPort;
import io.github.mojolowjo.entropybot.commands.PlannerWorker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 D6 (run DT 16:25:09-21: the one-leg trip's own chain, NeedChain.resolve on the client tick, 2.1-2.2 s at each
 * landing): with the planner worker installed the one-leg trip's own chain is asked of the worker; while it thinks the
 * order stands ("not priced yet: the planner waits on its answer", rule 22); its landing is taken by the next ask - never a
 * resolve on the client tick.
 */
class OwnChainWorkerTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());

    @Test
    void aOneLegTripsOwnChain_isAskedOfTheWorker_andALandingIsNotResolvedOnTheTick() throws InterruptedException {
        TestData d = TestData.planner().tag("wooden_pickaxe", "minecraft:pickaxes").install();
        try {
            NeedChain.Spot ore = new NeedChain.Spot(4, 60, -2, 8, "minecraft:coal_ore", 20);
            bb.fact(NeedChain.WORLD_FACT, (java.util.function.Supplier<NeedChain.World>) () -> io.github.mojolowjo.entropybot.aa.FakePaths.paths(new NeedChain.World(Map.of("minecraft:wooden_pickaxe", 1), false,
                    w -> w.equals("minecraft:coal") ? ore : null)));
            AtomicInteger onWorker = new AtomicInteger();
            Thread test = Thread.currentThread();
            List<String> owns = new java.util.concurrent.CopyOnWriteArrayList<>();
            PlannerWorker worker = new PlannerWorker(j -> {
                if (Thread.currentThread() != test) onWorker.incrementAndGet();
                if (j.own() != null) owns.add(j.own().what());
                return PlannerWorker.resolve(j);
            });
            List<String> log = new ArrayList<>();
            bb.fact(BatchWay.PLANNER_FACT, new PlannerPort(worker, () -> 0, log::add));
            // a plain item order (a trip order; its playbook one leg: the order's own chain)
            Thing.Result coal = new Thing.Result("coal", "minecraft:coal", false, List.of("minecraft:coal"), List.of("minecraft:coal"), 0, null, "coal", null);
            Goals.Goal g = Goals.add(bb, coal, 16, 0, clock.now());
            assertTrue(BatchWay.tripOrder(g));

            TickLaps.AA.begin(true);
            NeedChain.Chain first = Goals.chain(bb, g);
            assertTrue(first.why().startsWith("trip: stand (not priced yet: the planner waits on its answer"), "the stand while the worker thinks: " + first.why());
            assertTrue(first.steps().stream().anyMatch(s -> s instanceof io.github.mojolowjo.entropybot.aa.moves.Step.AwaitPaths), "it stands (rule 22)");
            NeedChain.Chain c = first;
            long until = System.nanoTime() + 30_000_000_000L;
            while (c.why().startsWith("trip: stand") && System.nanoTime() < until) {
                Thread.sleep(2);
                c = Goals.chain(bb, g);
            }
            assertTrue(c.why().startsWith("coal <- dig coal seen 8 m"), "the worker's own chain, applied: " + c.why());
            String line = TickLaps.AA.line(1, -1);
            assertTrue(line.contains("| resolves 0 = "), "no resolve on the client tick: " + line);
            assertTrue(onWorker.get() > 0 && !owns.isEmpty(), "resolved on the worker as an own chain: " + owns);
            // kept while its key holds: the next asks are the kept chain, not a new request
            int asked = onWorker.get();
            assertTrue(Goals.chain(bb, g).why().startsWith("coal <- dig coal"));
            assertEquals(asked, onWorker.get(), "the kept own chain: no new worker round");
        } finally {
            TestData.clear();
        }
    }
}
