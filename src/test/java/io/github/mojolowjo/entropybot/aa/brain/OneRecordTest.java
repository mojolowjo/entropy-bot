package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.Need;
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
        Need r = new Need("needs.table", TaskKind.TABLE_FURNACE, b -> true, b -> "no base chests scanned", b -> java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Obtain("table", 1)));
        Brain brain = new Brain("brain", "test", new Desire("needs.table", Desire.Kind.UPKEEP, r, b -> 50));
        brain.tick(bb);
        assertEquals(0, port.started.size());
        String held = brain.failures.held("needs.table", null, clock.now());
        assertNotNull(held, "rested, not tried again next tick");
        assertEquals(0, brain.failures.fails("needs.table", null), "a rest, not a counted failure");
        clock.advance(Cooldown.noRunMsV() + 1);
        assertNull(brain.failures.held("needs.table", null, clock.now()));
    }

    @Test
    void nestedBrainAndAnOptionMemoryShareTheOneRecord() {
        Brain inner = new Brain("idle.options", "inner").nested();
        Brain outer = new Brain("brain", "outer", new Desire("idle", Desire.Kind.UPKEEP, inner, b -> -1));
        assertSame(outer.failures, inner.failures);
        outer.tick(bb);
        assertSame(outer.failures, bb.fact(Brain.FAILURES_FACT));
        var held = new io.github.mojolowjo.entropybot.aa.Options.OptionMemory();
        held.bind(outer.failures, "chain");
        held.failed("charcoal", "no furnace", clock.now());
        assertTrue(outer.failures.blocked("chain", "charcoal", clock.now()));
        assertEquals(1, outer.heldLines(clock.now()).size(), "one held list, no duplicate from the nested brain");
    }
}
