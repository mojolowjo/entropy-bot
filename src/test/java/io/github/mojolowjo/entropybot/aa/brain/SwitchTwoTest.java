package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.RunJob;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** aa-switch2 (r2-iron on 0.38.b2: switches 20, pointless 19): the sequences replayed. */
class SwitchTwoTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Tracer tracer = new Tracer();
    final Blackboard bb = new Blackboard(clock, port, tracer);
    final double[] s = new double[3];

    Desire d(int i, String id, TaskKind k) {
        return new Desire(id, Desire.Kind.PROGRESS, RunJob.of(id + ".job", k, "do " + id, "brain"), b -> s[i]).cost(0);
    }

    /** 17 of 19: STOCK_IRON refused ("123 steps"), the next start counted it as a live switch. */
    @Test
    void aRefusedStartIsNoSwitch() {
        Brain br = new Brain("brain", "t", d(0, "idle.stock_iron", TaskKind.STOCK_IRON), d(1, "idle.wait", TaskKind.WAIT));
        s[0] = 12;
        s[1] = 3;
        for (int round = 0; round < 5; round++) {
            port.refuse = "error: that is 123 steps with the routines in it, the most I run in one chain is 100";
            br.tick(bb);                                  // iron refused, wait starts
            port.refuse = null;
            clock.advance(62_000);
        }
        assertEquals(0, tracer.counts.switches, tracer.lastSwitchText(clock.now()));
        assertEquals(0, tracer.counts.pointless);
    }

    /** night.sleep picked at 60, "wanted -1" half a second later: a flicker, no cancel. */
    @Test
    void aMomentaryMinusOneKeepsTheTask() {
        Brain br = new Brain("brain", "t", d(0, "night.sleep", TaskKind.BASE_BED), d(1, "idle", TaskKind.WAIT));
        s[0] = 60;
        s[1] = 12;
        br.tick(bb);
        s[0] = -1;
        clock.advance(500);
        assertEquals(Status.RUNNING, br.tick(bb));
        clock.advance(500);
        s[0] = 35;                                        // back
        br.tick(bb);
        s[0] = -1;
        for (int i = 0; i < 9; i++) {                     // 4.5 s at -1: still under the 5 s
            clock.advance(500);
            br.tick(bb);
        }
        assertEquals(1, port.started.size(), port.log.toString());
        assertEquals(0, tracer.counts.pointless);
        assertEquals(0, port.cancelled.size());
    }

    /** A score that stays <= 0 for 5 s does end the task (the day-check is kept). */
    @Test
    void aSustainedZeroEndsAfterFiveSeconds() {
        Brain br = new Brain("brain", "t", d(0, "needs.food", TaskKind.FOOD), d(1, "idle", TaskKind.WAIT));
        s[0] = 39;
        s[1] = 12;
        br.tick(bb);
        s[0] = -1;
        for (int i = 0; i < 10; i++) {                    // the first -1 tick starts the 5 s
            clock.advance(500);
            br.tick(bb);
        }
        assertEquals(0, port.cancelled.size(), "4.5 s at -1");
        clock.advance(500);
        br.tick(bb);
        assertEquals(1, port.cancelled.size(), port.log.toString());
        assertEquals("do idle", port.started.get(port.started.size() - 1).chain());
    }

    /** An unmet own check keeps the task however long the score is -1; met, it ends at once as done (not pointless). */
    @Test
    void aMetCheckEndsAtOnceAsDone() {
        boolean[] met = {false};
        Desire food = d(0, "needs.food", TaskKind.FOOD).met(b -> met[0]);
        Brain br = new Brain("brain", "t", food, d(1, "idle", TaskKind.WAIT));
        s[0] = 39;
        s[1] = 12;
        br.tick(bb);
        s[0] = -1;
        for (int i = 0; i < 20; i++) {                    // unmet own check: never cancelled however long
            clock.advance(500);
            br.tick(bb);
        }
        assertEquals(0, port.cancelled.size());
        met[0] = true;
        clock.advance(500);
        br.tick(bb);
        assertEquals(1, port.cancelled.size());
        assertEquals(0, tracer.counts.pointless, tracer.lastSwitchText(clock.now()));
    }

    /** The staircase to a deep band fits one chain: a leg of STAIR_LEG levels, no place mine, re-decided at its bottom. */
    @Test
    void aDeepStaircaseIsALeg() {
        List<String> dd = new ArrayList<>();
        // at Y 70, best band Y 16: 54 levels down (108 steps: refused as one chain)
        var f = new Discovery.Facts(-1, 1, false, true, null, 100, 70, 200, 16, 0.002, 0.4, false);
        var strip = Discovery.rank(f, "iron", 3, dd).stream().filter(o -> o.kind() == Discovery.Kind.STRIP).findFirst().orElseThrow();
        String[] steps = strip.command().split(" then ");
        assertEquals(2 * Discovery.STAIR_LEG, steps.length, strip.command());
        assertFalse(strip.command().contains("place mine"), strip.command());
        assertTrue(strip.why().contains("this leg " + Discovery.STAIR_LEG + " of "), strip.why());
        // a shallow band: the whole way and the mine, as before
        f = new Discovery.Facts(-1, 1, false, true, null, 100, 30, 200, 16, 0.002, 0.4, false);
        strip = Discovery.rank(f, "iron", 3, dd).stream().filter(o -> o.kind() == Discovery.Kind.STRIP).findFirst().orElseThrow();
        assertTrue(strip.command().contains("place mine"), strip.command());
    }
}
