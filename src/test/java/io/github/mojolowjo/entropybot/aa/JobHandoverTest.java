package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.cave.CaveWalk;
import io.github.mojolowjo.entropybot.sense.CaveIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-handover (run JBB 08:37:57): the tunnel ended on cave air (a found fact), the re-price "kept the running pick" of the
 * ended step and the order stopped; the new cave's record had no passages measured and priced "0 s".
 */
class JobHandoverTest {

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    private static Goals.Goal running(Blackboard bb) {
        ScenariosTest.world("wooden", "stone");
        bb.fact(NeedChain.WORLD_FACT, (Supplier<World>) () -> Z62RunSTest.world(Map.of("minecraft:stone_pickaxe", 1), Z62RunSTest.band("east")));
        Goals.Goal g = Goals.draft(DebugTreeTest.item("raw_iron"), 3, 0, bb.clock.now());
        var ch = Goals.chain(bb, g);
        assertTrue(ch != null && ch.ok() && !ch.steps().isEmpty(), ch == null ? "no chain" : ch.why());
        // the running pick priced the same as the new way: within replan.switchMarginS, the old code kept it
        g.pricedWay = true;
        g.pricedExplore = false;
        g.pricedS = ch.seconds();
        g.pricedAtMs = bb.clock.now();
        return g;
    }

    @Test
    void anEndedStepWithAFoundFactTakesTheNewWay() {
        Clock.Fake clock = new Clock.Fake(10_000_000);
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(clock, port, new Tracer());
        Goals.Goal g = running(bb);
        Step tunnel = new Step.Explore(5);
        FactChange.Fact f = FactChange.found("cave air at -129 13 -42: the cave step takes over");
        Steps.Routine r = Goals.replan(bb, g, tunnel, f, f.text(), new Steps.Remaining(List.of(tunnel), null, Map.of()));
        assertNotNull(r.steps(), "the order goes on: " + r.why());
        assertFalse(r.steps().isEmpty());
        assertTrue(port.log.stream().anyMatch(l -> l.contains(Goals.ENDED_TAKES)), port.log.toString());
        assertTrue(port.log.stream().noneMatch(l -> l.contains("kept the running pick")), port.log.toString());
    }

    @Test
    void aRunningStepStillKeepsItsPickWithinTheMargin() {
        Clock.Fake clock = new Clock.Fake(10_000_000);
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(clock, port, new Tracer());
        Goals.Goal g = running(bb);
        FactChange.Fact f = FactChange.found("cave known at -129 13 -42");
        Steps.Routine r = Goals.replan(bb, g, new Step.Explore(5), f, f.text());
        assertNull(r.steps(), "the running step goes on");
        assertTrue(r.why().startsWith("kept the running pick"), r.why());
    }

    @Test
    void anUnmeasuredRecordIsPricedByItsCellsAndTheBandDensity() {
        long cells = 29228;
        double minutes = cells / (double) CaveIndex.CROSS_SECTION / Unit.walkBps() / 60.0;
        var d = CaveWalk.Density.flat(0.001, NeedChain.UNMEASURED + 13);
        CaveWay cw = new CaveWay(-129, 13, -42, 4.2, "minecraft:deepslate_iron_ore", "iron", 29.2, Double.NaN, null, minutes, List.of())
                .withDensity(d);
        var in = NeedChain.unmeasuredWalk(cw, 3);
        assertNotNull(in);
        double walk = cells / (double) CaveIndex.CROSS_SECTION * Tuning.get("cave.passageSecPerCell");
        assertEquals(walk * 3 / (cells * 0.001), in.seconds(), 1e-3);
        assertTrue(in.seconds() > 0, "never 0 s");
        assertTrue(in.line().contains("(unmeasured: priced by its cells at y 13)"), in.line());
        // a measured record (passages known) is not this price
        assertNull(NeedChain.unmeasuredWalk(cw.withPassages(List.of()), 3));
        // a record with no cells known is not either
        assertNull(NeedChain.unmeasuredWalk(new CaveWay(-129, 13, -42, 4.2, "o", "iron", Double.NaN, Double.NaN, null, -1, List.of()).withDensity(d), 3));
    }
}
