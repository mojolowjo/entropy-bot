package io.github.mojolowjo.entropybot.recorder;

import io.github.mojolowjo.entropybot.recorder.Recorder.Change;
import io.github.mojolowjo.entropybot.recorder.Recorder.TrailPoint;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** When an incident is due, the state-only filter, and what an incident's text looks like. */
class IncidentTest {
    static final long T0 = 1_759_600_000_000L;

    @Test
    void failuresAreJobEndsThatAreNotOkOrAPlainStop() {
        assertFalse(IncidentText.isFailure(null));
        assertFalse(IncidentText.isFailure(""));
        assertFalse(IncidentText.isFailure("ok: arrived"));
        assertFalse(IncidentText.isFailure("ok"));
        assertFalse(IncidentText.isFailure("stopped"), "the owner's stop");
        assertFalse(IncidentText.isFailure(IncidentText.REPLACED));
        assertFalse(IncidentText.isFailure(IncidentText.RELOADED));
        assertTrue(IncidentText.isFailure("error: couldn't get there (stuck at 260 -48 852)"));
        assertTrue(IncidentText.isFailure("stopped: the bot died"));
        assertTrue(IncidentText.isFailure("stopped: I am outside my areas at 1 2 3 - area add ..."));
        assertTrue(IncidentText.isFailure("couldn't reach the mine: stuck at 1 2 3 on the way to the corridor end"));
    }

    @Test
    void theTargetIsTheFirstCoordinatesInTheText() {
        assertArrayEquals(new int[]{260, -48, 852}, IncidentText.coords("error: stuck at 260 -48 852 after 3 tries"));
        assertArrayEquals(new int[]{-1, 2, -3}, IncidentText.coords("walking to -1 2 -3"));
        assertNull(IncidentText.coords("error: no pickaxe"));
        assertNull(IncidentText.coords(null));
    }

    @Test
    void aGuardStreakFiresOnceThenCoolsDown() {
        IncidentText.GuardStreak g = new IncidentText.GuardStreak(5, 60_000, 300_000);
        for (int i = 0; i < 4; i++) assertFalse(g.refused(T0 + i * 1000));
        assertTrue(g.refused(T0 + 4000), "the fifth within a minute");
        for (int i = 0; i < 5; i++) assertFalse(g.refused(T0 + 10_000 + i * 1000), "cooling down");
        IncidentText.GuardStreak slow = new IncidentText.GuardStreak(5, 60_000, 300_000);
        for (int i = 0; i < 10; i++) assertFalse(slow.refused(T0 + i * 20_000), "spread out: never 5 in a minute");
        assertTrue(g.count() <= 5);
        for (int i = 0; i < 4; i++) g.refused(T0 + 400_000 + i);
        assertTrue(g.refused(T0 + 400_010), "after the cool-down it fires again");
    }

    @Test
    void stateOnlyFlipsAreFilteredUnlessStatesAreOn() {
        // a furnace lighting up: the same block, another state
        assertFalse(ChangeFilter.keep(false, true, false, 0, 0, 0, 0, 4));
        assertTrue(ChangeFilter.keep(false, true, true, 0, 0, 0, 0, 4));
        // stone -> air is always kept
        assertTrue(ChangeFilter.keep(false, false, false, 0, 0, 0, 0, 4));
        // nothing changed at all
        assertFalse(ChangeFilter.keep(true, true, true, 0, 0, 0, 0, 4));
        // the range, in chunks around the bot's chunk
        assertTrue(ChangeFilter.keep(false, false, false, 10, -10, 14, -6, 4));
        assertFalse(ChangeFilter.keep(false, false, false, 10, -10, 15, -10, 4));
        assertFalse(ChangeFilter.keep(false, false, false, 10, -10, 10, -15, 4));
    }

    @Test
    void theTextShowsTheBoxLayersTheTrailAndTheChanges() {
        String ow = "minecraft:overworld";
        // a 3x2x3 box: bottom layer stone with one lava, top layer air with one unknown
        String[] ids = new String[18];
        for (int i = 0; i < 9; i++) ids[i] = "minecraft:stone";
        ids[4] = "minecraft:lava";
        for (int i = 9; i < 18; i++) ids[i] = "minecraft:air";
        ids[17] = null;
        IncidentText.Box box = new IncidentText.Box("around the bot", 10, 64, 20, 12, 65, 22, ids);
        String t = IncidentText.render(T0, "error: stuck at 11 64 21", "seq: dig", ow, new int[]{11, 65, 21}, 1, List.of(box),
                List.of(new TrailPoint(T0 - 5000, ow, 11, 65, 21, "start seq: dig")),
                List.of(new Change(T0 - 1000, ow, 11, 64, 21, "minecraft:stone", "minecraft:lava", false)), ZoneId.of("UTC"));
        assertTrue(t.startsWith("incident 2025-10-04 "), t);
        assertTrue(t.contains("reason: error: stuck at 11 64 21\n"));
        assertTrue(t.contains("job: seq: dig\n"));
        assertTrue(t.contains("box around the bot: 10 64 20 .. 12 65 22 (half 1)\n"));
        assertTrue(t.contains("legend: .=air ?=not loaded 0=stone 1=lava\n"), t);
        assertTrue(t.contains("y=65\n  z=20      ...\n  z=21      ...\n  z=22      ..?\n"), t);
        assertTrue(t.contains("y=64\n  z=20      000\n  z=21      010\n"), t);
        assertTrue(t.contains("start seq: dig"));
        assertTrue(t.contains("11 64 21 stone -> lava\n"), t);
        assertTrue(t.indexOf("y=65") < t.indexOf("y=64"), "top layer first");
    }
}
