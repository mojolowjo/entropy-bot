package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.RungOptions.SurfaceFacts;
import io.github.mojolowjo.entropybot.aa.RungOptions.ToolsFacts;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** S2 (MINING_PLAN 11): the surface and tools option sets, the tools set, a failed tail after the need is met. */
class RungOptionsTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());
    final Options.OptionMemory m = new Options.OptionMemory();
    static final int[] ME = {0, 50, 0};

    @Test
    void surfaceRanksPillarClimbAndSky() {
        SurfaceFacts f = new SurfaceFacts(10, ME, true, 20, true, true, new int[]{20, 70, 5}, 25, false);
        Options.Choice c = new RungOptions.Surface().choose(f, m, 0, null, 0, null, null);
        assertEquals("pillar", c.best().id());                  // 10 + 5
        assertEquals("goto 0 60 0", c.best().command());
        assertEquals(List.of("pillar", "climb", "sky"), c.ranked().stream().map(Options.Option::id).toList());   // 15, 40, 50
        assertTrue(c.why().contains("nothing: the next rung needs open ground itself"), c.why());
        SurfaceFacts noShaft = new SurfaceFacts(10, ME, false, 0, true, false, null, -1, true);
        c = new RungOptions.Surface().choose(noShaft, m, 0, null, 0, null, null);
        assertNull(c.best());
        assertTrue(c.why().startsWith("no way up; dropped: climb: no pickaxe, pillar: no open shaft above"), c.why());
    }

    @Test
    void toolsFromNothingIsTheChainRowNotAFixedJob() {
        io.github.mojolowjo.entropybot.aa.brain.TestData.planner().install();
        try {
            NeedChain.World w = new NeedChain.World(java.util.Map.of(), false, new int[]{1, 64, 1},
                    word -> word.equals("log") ? new NeedChain.Spot(30, 64, 0, 30, null) : null);
            Options.Choice c = new RungOptions.Tools().choose(new ToolsFacts(0, false, true, false, 0, 0, 0, false, w), m, 0, null, 0, "missing cobblestone", null);
            assertEquals("chain stone_pickaxe", c.best().id(), c.why());
            assertTrue(c.best().typed());
            assertTrue(c.best().why().contains("tree seen 30 m"), c.best().why());
            assertTrue(c.why().contains("planner: missing cobblestone"), c.why());
        } finally {
            io.github.mojolowjo.entropybot.aa.brain.TestData.clear();
        }
    }

    @Test
    void aFailedChainIsHeldUnderItsKeyAndTheNextPickShowsWhy() {
        io.github.mojolowjo.entropybot.aa.brain.TestData.planner().install();
        try {
            NeedChain.World w = new NeedChain.World(java.util.Map.of(), false, new int[]{1, 64, 1}, word -> null);
            m.failed("chain stone_pickaxe", "stopped at step 1 (explore 5): timeout", 0);
            Options.Choice c = new RungOptions.Tools().choose(new ToolsFacts(0, false, true, false, 0, 0, 0, false, w), m, 1, null, 0, "x", null);
            assertNull(c.best());
            assertTrue(c.why().contains("chain stone_pickaxe: "), c.why());
        } finally {
            io.github.mojolowjo.entropybot.aa.brain.TestData.clear();
        }
    }
    @Test
    void aFailedTailAfterTheNeedIsMetCountsAsDone() {
        bb.in.pickTier = 0;
        Need r = new Need("needs.tools", TaskKind.TOOLS, b -> b.in.pickTier < 2, b -> null, b -> java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Explore(1)));
        assertEquals(Status.RUNNING, r.tick(bb));
        bb.in.pickTier = 2;
        port.fail(1, "stopped at step 12 (scan base): no chests within 16");
        assertEquals(Status.SUCCESS, r.tick(bb));
        assertTrue(String.join("|", port.log).contains("the need is met - counted done"), port.log.toString());
    }
}
