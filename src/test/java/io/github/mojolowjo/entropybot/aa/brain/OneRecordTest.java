package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.CoalOptions;
import io.github.mojolowjo.entropybot.aa.Rung;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.Cooldown;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Review 2 (4): one failure record (nested brain, coal memory) and a rung's no-task failure rests NO_RUN_MS. */
class OneRecordTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    @Test
    void aNoTaskRungFailureRestsTheRung() {
        Rung r = new Rung("needs.table", TaskKind.TABLE_FURNACE, b -> true, b -> "no base chests scanned", b -> "goal table");
        Brain brain = new Brain("brain", "test", new Desire("needs.table", Desire.Kind.UPKEEP, r, b -> 50));
        brain.tick(bb);
        assertEquals(0, port.started.size());
        String held = brain.failures.held("needs.table", null, clock.now());
        assertNotNull(held, "rested, not tried again next tick");
        assertEquals(0, brain.failures.fails("needs.table", null), "a rest, not a counted failure");
        clock.advance(Cooldown.NO_RUN_MS + 1);
        assertNull(brain.failures.held("needs.table", null, clock.now()));
    }

    @Test
    void nestedBrainAndCoalMemoryShareTheOneRecord() {
        Brain inner = new Brain("idle.options", "inner").nested();
        Brain outer = new Brain("brain", "outer", new Desire("idle", Desire.Kind.UPKEEP, inner, b -> -1));
        assertSame(outer.failures, inner.failures);
        outer.tick(bb);
        assertSame(outer.failures, bb.fact(Brain.FAILURES_FACT));
        CoalOptions.memory(bb).held.failed("charcoal", "no furnace", clock.now());
        assertTrue(outer.failures.blocked(CoalOptions.MEM_DESIRE, "charcoal", clock.now()));
        assertEquals(1, outer.heldLines(clock.now()).size(), "one held list, no duplicate from the nested brain");
    }
}
