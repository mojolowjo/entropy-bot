package io.github.mojolowjo.entropybot.aa.core;

import io.github.mojolowjo.entropybot.aa.Rung;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** aa385: the way back into the areas (a stored spot, a costed return leg before any brain task outside). */
class WayBackTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    void at(int x, int y, int z, boolean inside) {
        bb.in.areaKnown = true;
        bb.in.pos = new int[]{x, y, z};
        bb.in.inArea = inside;
        WayBack.observe(bb);
    }

    @Test
    void outsideOptionsStoreTheWayAndOtherTasksReturnFirst() {
        at(-63, 64, -22, true);
        RunJob hunt = RunJob.of("j", TaskKind.FOOD, "goto -77 92 -71 then hunt 8 pig out", "needs");
        assertEquals(Status.RUNNING, hunt.tick(bb));
        assertEquals("goto -77 92 -71 then hunt 8 pig out", port.started.get(0).chain(), "a hunt out goes out on purpose: no leg");
        at(-75, 96, -96, false);
        port.fail(1, "stopped at step 2 (hunt 8 pig out): I see no pig");
        assertEquals(Status.FAILURE, hunt.tick(bb));
        RunJob wait = RunJob.of("w", TaskKind.WAIT, "wait 60", "idle");
        assertEquals(Status.RUNNING, wait.tick(bb));
        assertEquals("goto -63 64 -22", port.started.get(1).chain(), "the return leg before the wait");
        assertTrue(String.join("|", port.log).contains("WAIT options: return to my areas 163 (I am outside at -75 96 -96; back to -63 64 -22, 82 blocks x 2 outside); way back stored by \"goto -77 92 -71 then hunt 8 pig out\""), port.log.toString());
        RunJob explore = RunJob.of("e", TaskKind.EXPLORE, "explore 5", "want");
        assertEquals(Status.RUNNING, explore.tick(bb));
        assertEquals("explore 5", port.started.get(2).chain());
    }

    @Test
    void anOutsideRungReturnsInsteadOfAnInfeasiblePlan() {
        at(0, 64, 0, true);
        at(40, 70, 0, false);
        port.resolveTo = "goto 40 70 0 then place crafting_table 41 70 0";
        Rung r = new Rung("needs.tableFurnace", TaskKind.TABLE_FURNACE, b -> true, b -> null, b -> "goal_furnace");
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("goto 0 64 0", port.started.get(0).chain());
        assertEquals(0, port.resolveCalls, "no plan from outside");
        assertNull(r.probe(bb));
    }

    @Test
    void noWayBackKnownOrOwnerWorkMeansNoLeg() {
        bb.in.areaKnown = true;
        bb.in.pos = new int[]{5, 5, 5};
        bb.in.inArea = false;
        assertNull(WayBack.leg(bb, "wait 60", "idle"), "never stood inside");
        at(0, 0, 0, true);
        at(5, 5, 5, false);
        assertNull(WayBack.leg(bb, "come", "owner"));
        assertTrue(WayBack.goesOut("hunt 4 cow out"));
        assertFalse(WayBack.goesOut("hunt 4 cow"));
        assertTrue(WayBack.goesOut("explore 5"));
    }
}
