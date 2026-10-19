package io.github.mojolowjo.entropybot.aa.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RunLedgerTest {
    private static RunLedger.Row row(String world) {
        return new RunLedger.Row("0.37.8", world, "easy", 4.5, 6.0, -1, 12, 3, 1, 2, 0, 0, 1, 31.04);
    }

    @Test void lineHasOneFieldPerHeaderColumn() {
        String l = RunLedger.line(row("w,1\nx"));
        assertEquals(RunLedger.HEADER.split(",").length, l.split(",", -1).length);
        assertEquals("0.37.8,w;1 x,easy,4.5,6.0,-1,12,3,1,2,0,0,1,31.0", l);
    }

    @Test void headerOnlyOnNewFile() {
        assertTrue(RunLedger.append(false, row("a")).startsWith(RunLedger.HEADER + "\n"));
        assertEquals(RunLedger.line(row("a")) + "\n", RunLedger.append(true, row("a")));
    }

    @Test void lastRowsSkipHeaderAndKeepOrder() {
        StringBuilder f = new StringBuilder(RunLedger.HEADER + "\n");
        for (int i = 0; i < 7; i++) f.append(RunLedger.line(row("w" + i))).append("\n");
        List<String> l = RunLedger.last(f.toString(), 5);
        assertEquals(5, l.size());
        assertTrue(l.get(0).contains(",w2,"));
        assertTrue(l.get(4).contains(",w6,"));
        assertTrue(RunLedger.statsText("", 5).startsWith("runs: none"));
    }

    @Test void marksKeepTheFirstTimeAndCountFoodEdges() {
        var m = new RunLedger.Marks();
        m.tick(60_000, 1, false, false, 20);
        assertEquals(-1, m.stoneMin());
        m.tick(120_000, 2, true, false, 5);
        m.tick(180_000, 3, true, true, 4);     // still low: not a new event
        m.tick(240_000, 3, true, true, 18);
        m.tick(300_000, 2, true, true, 3);     // a second event; the first times stay
        assertEquals(2.0, m.stoneMin(), 1e-9);
        assertEquals(3.0, m.tableFurnaceMin(), 1e-9);
        assertEquals(3.0, m.ironMin(), 1e-9);
        assertEquals(2, m.foodEvents());
    }
}

