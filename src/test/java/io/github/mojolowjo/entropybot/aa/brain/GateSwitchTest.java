package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** aa-gate (normal3: 17 pointless switches): a met desire ends as "done:", the shelter hand-over is deliberate. */
class GateSwitchTest {
    @Test
    void metEndsAsDone() {
        Clock.Fake clock = new Clock.Fake(10_000_000);
        FakePort port = new FakePort();
        Blackboard bb = new Blackboard(clock, port, new Tracer());
        boolean[] met = {false};
        double[] v = {40};
        Brain br = new Brain("brain", "t",
                new Desire("needs.tools", Desire.Kind.UPKEEP, RunJob.of("t.job", TaskKind.TOOLS, "goal stone tools", "brain"), b -> v[0]).met(b -> met[0]));
        br.tick(bb);
        v[0] = -1;
        met[0] = true;
        clock.advance(1_000);
        br.tick(bb);
        assertFalse(port.cancelled.isEmpty());
        assertTrue(port.cancelled.get(0).contains("done: its own check is met"), port.cancelled.get(0));
    }

    @Test
    void deliberateEnds() {
        assertTrue(Tracer.justified("done: its own check is met: needs.tools"));
        assertTrue(Tracer.justified("sheltered: work under ground is cheaper now"));
        assertFalse(Tracer.justified("its reason is gone: x"));
    }
}
