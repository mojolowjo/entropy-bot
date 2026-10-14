package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.clear.DropSweep.Item;
import io.github.mojolowjo.entropybot.clear.DropSweep.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.29.0: the drop sweep after a dig via baritone: area, order, one walk at a time, the caps, the note. */
class DropSweepTest {
    static final ClearBox BOX = ClearBox.of(0, 50, 0, 3, 52, 3);

    @Test
    void areaIsTheBoxAndOneAround() {
        DropSweep s = new DropSweep(BOX, 0);
        assertTrue(s.covers(-1, 49, 4));
        assertFalse(s.covers(-2, 50, 0));
        assertFalse(s.covers(0, 54, 0));
        List<Item> w = s.wanted(List.of(new Item("a", 5, 50, 0, 3), new Item("b", 4, 50, 0, 2)));
        assertEquals(1, w.size());
        assertEquals("b", w.get(0).id());
    }

    @Test
    void nearestFirstAndEachOnce() {
        DropSweep s = new DropSweep(BOX, 0);
        List<Item> seen = List.of(new Item("far", 3, 50, 3, 5), new Item("near", 0, 50, 0, 1));
        var a = s.tick(0, seen, false, true);
        assertEquals(Kind.WALK, a.kind());
        assertEquals("near", a.item().id());
        assertEquals(Kind.WAIT, s.tick(5, seen, true, false).kind(), "walking, the drop still there");
        // picked up: gone; the next one
        var b = s.tick(10, List.of(seen.get(0)), false, false);
        assertEquals("far", b.item().id());
        // the walk gives up (idle after 15 ticks, drop still there): not tried again
        assertEquals(Kind.WAIT, s.tick(20, List.of(seen.get(0)), true, true).kind());
        assertEquals(Kind.DONE, s.tick(30, List.of(seen.get(0)), true, true).kind());
        assertEquals(2, s.walks);
        assertFalse(s.capped);
    }

    @Test
    void aWalkTimesOut() {
        DropSweep s = new DropSweep(BOX, 0);
        List<Item> seen = List.of(new Item("x", 1, 50, 1, 2));
        s.tick(0, seen, false, false);
        assertEquals(Kind.WAIT, s.tick(DropSweep.WALK_TICKS - 1, seen, true, false).kind());
        assertEquals(Kind.DONE, s.tick(DropSweep.WALK_TICKS, seen, true, false).kind());
    }

    @Test
    void walkCapLeavesTheRest() {
        DropSweep s = new DropSweep(BOX, 0);
        List<Item> seen = new ArrayList<>();
        for (int i = 0; i < 25; i++) seen.add(new Item("i" + i, 1, 50, 1, i));
        long t = 0;
        int walks = 0;
        while (true) {
            var a = s.tick(t, seen, false, true);
            if (a.kind() == Kind.DONE) break;
            if (a.kind() == Kind.WALK) walks++;
            t += 1;
        }
        assertEquals(DropSweep.MAX_WALKS, walks);
        assertTrue(s.capped);
        assertEquals(5, s.left);
        assertEquals("picked up 18 items, left 5 drops", s.note(18));
    }

    @Test
    void timeCap() {
        DropSweep s = new DropSweep(BOX, 100);
        List<Item> seen = List.of(new Item("a", 1, 50, 1, 1), new Item("b", 2, 50, 1, 2));
        s.tick(100, seen, false, true);
        var a = s.tick(100 + DropSweep.MAX_TICKS, seen, false, true);
        assertEquals(Kind.DONE, a.kind());
        assertTrue(s.capped);
        assertEquals(1, s.left);
    }

    @Test
    void nothingThereIsDoneAtOnce() {
        DropSweep s = new DropSweep(BOX, 0);
        assertEquals(Kind.DONE, s.tick(0, List.of(), false, true).kind());
        assertEquals("picked up 0 items", s.note(0));
        assertEquals("picked up 1 item, left 1 drop", DropSweep.text(1, 1));
    }
}
