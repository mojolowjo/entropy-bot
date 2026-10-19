package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.Cooldown;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** S1 (MINING_PLAN 12): the idle bands, the 30 s cooldown for a planner-null option, lighting once per night. */
class IdleBandsTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    @Test
    void bandsEnterBelowAndLeaveAtTheTarget() {
        AaScorer s = new AaScorer();
        Inputs in = new Inputs();
        in.pickTier = 2;
        in.wood = 40;
        in.coal = 4;
        in.ironIngots = 20;
        assertEquals(-1, s.score(in)[TaskKind.STOCK_WOOD.ordinal()], "40 logs: the band is not on");
        assertEquals(100.0 * 28 / 32, s.score(in)[TaskKind.STOCK_COAL.ordinal()], 0.01);
        assertEquals(-1, s.score(in)[TaskKind.STOCK_IRON.ordinal()]);
        in.wood = 10;
        s.score(in);
        in.wood = 50;
        assertTrue(s.score(in)[TaskKind.STOCK_WOOD.ordinal()] > 0, "on until 64");
        in.wood = 64;
        assertEquals(-1, s.score(in)[TaskKind.STOCK_WOOD.ordinal()]);
        in.freeSlots = 5;
        assertEquals(-1, s.score(in)[TaskKind.DEPOSIT.ordinal()], "no base chests");
        in.baseChests = true;
        assertTrue(s.score(in)[TaskKind.DEPOSIT.ordinal()] > 50);
        in.pickTier = 1;
        assertEquals(-1, s.score(in)[TaskKind.STOCK_IRON.ordinal()], "no stone pickaxe: no iron");
        in.night = true;
        in.torches = 8;
        assertEquals(AaScorer.LIGHT_SCORE, s.score(in)[TaskKind.LIGHT.ordinal()]);
    }

    @Test
    void aPlannerNullOptionGivesTheNextOneItsTurn() {
        AaTree tree = AaTree.build(new AaScorer());
        bb.in.pickTier = 2;            // every rung met
        bb.in.pickaxes = 1;
        bb.in.pickPct = 100;
        bb.in.areaKnown = bb.in.tableKnown = bb.in.furnaceKnown = bb.in.baseKnown = bb.in.tableInBag = true;
        bb.in.torches = 64;
        bb.in.coal = 32;
        bb.in.cobble = 0;
        bb.in.pickTier = 3;
        port.resolveTo = null;         // the planner never has a chain: the old idle starved here
        for (int i = 0; i < 12; i++) tree.tick(bb);
        assertTrue(port.started.stream().anyMatch(t -> t.chain().equals("wait 60")), "the floor runs instead of the same option failing every tick: " + port.log);
    }

    @Test
    void lightingRunsOncePerNight() {
        var light = AaTree.oncePerNight(io.github.mojolowjo.entropybot.aa.core.RunJob.of("l", TaskKind.LIGHT, "light here 8", "idle"));
        bb.in.night = true;
        assertEquals(Status.RUNNING, light.tick(bb));
        port.done(1, "done");
        assertEquals(Status.SUCCESS, light.tick(bb));
        assertEquals(Status.FAILURE, light.tick(bb));
        bb.in.night = false;
        AaTree.build(new AaScorer()).tick(bb);          // the tree clears the flag by day (the node is not ticked then)
        assertFalse(bb.in.litTonight);
        bb.in.night = true;
        assertEquals(Status.RUNNING, light.tick(bb), "the next night lights again");
        assertTrue(Cooldown.NO_RUN_MS <= 30_000);
    }
}
