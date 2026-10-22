package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.RungOptions.SurfaceFacts;
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

/** S2 (MINING_PLAN 11): the surface option set, a failed tail after the need is met. */
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
    void aFailedTailAfterTheNeedIsMetCountsAsDone() {
        bb.in.pickTier = 0;
        Need r = new Need("needs.torches", TaskKind.TORCHES, b -> b.in.pickTier < 2, b -> null, b -> java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Explore(1)));
        assertEquals(Status.RUNNING, r.tick(bb));
        bb.in.pickTier = 2;
        port.fail(1, "stopped at step 12 (scan base): no chests within 16");
        assertEquals(Status.SUCCESS, r.tick(bb));
        assertTrue(String.join("|", port.log).contains("the need is met - counted done"), port.log.toString());
    }
}
