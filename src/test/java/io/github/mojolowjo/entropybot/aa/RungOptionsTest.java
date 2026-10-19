package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.RungOptions.SurfaceFacts;
import io.github.mojolowjo.entropybot.aa.RungOptions.ToolsFacts;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.camp.BootstrapPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** S2 (MINING_PLAN 11): the surface and tools option sets, bootstrap tools, a failed tail after the need is met. */
class RungOptionsTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());
    final Options.OptionMemory m = new Options.OptionMemory();
    static final int[] ME = {0, 50, 0};

    @Test
    void surfaceRanksPillarClimbTrailAndSky() {
        SurfaceFacts f = new SurfaceFacts(10, ME, true, 20, true, true, new int[]{30, 70, 0}, 60, new int[]{20, 70, 5}, 25, false);
        Options.Choice c = new RungOptions.Surface().choose(f, m, 0, null, 0, null, null);
        assertEquals("pillar", c.best().id());                  // 10 + 5
        assertEquals("goto 0 60 0", c.best().command());
        assertEquals(List.of("pillar", "climb", "sky", "trail"), c.ranked().stream().map(Options.Option::id).toList());   // 15, 40, 50, 60
        assertTrue(c.why().contains("nothing: the next rung needs open ground itself"), c.why());
        SurfaceFacts noShaft = new SurfaceFacts(10, ME, false, 0, true, false, null, 0, null, -1, true);
        c = new RungOptions.Surface().choose(noShaft, m, 0, null, 0, null, null);
        assertNull(c.best());
        assertTrue(c.why().startsWith("no way up; dropped: climb: no pickaxe, pillar: no open shaft above"), c.why());
    }

    @Test
    void aFailedClimbSwitchesAndNothingIsTriedOnce() {
        SurfaceFacts f = new SurfaceFacts(10, ME, false, 0, true, true, null, 0, new int[]{4, 60, 0}, 6, true);
        bb.fact(RungOptions.SURFACE_FACTS, (Supplier<SurfaceFacts>) () -> f);
        Rung r = new Rung("needs.surface", TaskKind.TABLE_FURNACE, b -> true, b -> null, b -> AaLadder.CLIMB,
                new Options.Picker<>("SURFACE", RungOptions.SURFACE_FACTS, SurfaceFacts.class, new RungOptions.Surface()));
        assertEquals(Status.FAILURE, r.tick(bb), "nothing (0) wins: the rung passes");
        assertTrue(r.reason().contains("passed"), r.reason());
        clock.advance(2 * 60_000 + 1);
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("goto 4 60 0", port.started.get(0).chain(), "nothing was tried: the cheapest real way (sky 12 < climb 40)");
        port.fail(1, "stopped at step 1 (goto 4 60 0): no path to 4 60 0");
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("climb", port.started.get(1).chain());
        assertTrue(String.join("|", port.log).contains("-> switching: climb"), port.log.toString());
    }

    @Test
    void toolsPrefersBootstrapToolsUnlessThePlanIsCheaper() {
        Options.Choice c = new RungOptions.Tools().choose(new ToolsFacts(0, false), m, 0, null, 0, "missing trees", null);
        assertEquals("bootstrap tools", c.best().command());
        assertTrue(c.why().contains("planner: missing trees"), c.why());
        c = new RungOptions.Tools().choose(new ToolsFacts(1, true), m, 0, "cut 3 logs then craft stone_pickaxe 1", 12, null, null);
        assertEquals("planner", c.best().id(), "60 < 150");
        m.failed("bootstrap tools", "stopped at step 3 (chop 6): no trees in my areas", 0);
        c = new RungOptions.Tools().choose(new ToolsFacts(0, false), m, 1, null, 0, "x", null);
        assertEquals("bootstrap", c.best().command());
        assertTrue(new RungOptions.Tools().switchable("stopped at step 9 (scan base): no chests within 16"));
        assertFalse(new RungOptions.Tools().switchable("stopped: your stop"));
    }

    @Test
    void bootstrapToolsStopsAfterTheFurnace() {
        List<String> steps = List.of("setbase 0 64 0", "chop 6", "craft crafting_table 1", "place crafting_table 1 64 0", "craft stone_pickaxe 1",
                "craft furnace 1", "goto 0 64 0", "place furnace 2 64 0", "craft chest 1", "place chest 3 64 0", "gather charcoal 2", "scan base");
        assertEquals(steps.subList(0, 8), BootstrapPlan.cutAfterFurnace(steps));
        assertEquals(List.of("chop 6", "craft stone_pickaxe 1"), BootstrapPlan.cutAfterFurnace(List.of("chop 6", "craft stone_pickaxe 1", "craft chest 1")));
        assertTrue(BootstrapPlan.cutAfterFurnace(List.of("craft chest 1", "scan base")).isEmpty());
    }

    @Test
    void aFailedTailAfterTheNeedIsMetCountsAsDone() {
        bb.in.pickTier = 0;
        Rung r = new Rung("needs.tools", TaskKind.TOOLS, b -> b.in.pickTier < 2, b -> null, b -> "bootstrap");
        assertEquals(Status.RUNNING, r.tick(bb));
        bb.in.pickTier = 2;
        port.fail(1, "stopped at step 12 (scan base): no chests within 16");
        assertEquals(Status.SUCCESS, r.tick(bb));
        assertTrue(String.join("|", port.log).contains("the need is met - counted done"), port.log.toString());
        assertFalse(r.cooldown().cooling(clock.now()));
    }
}
