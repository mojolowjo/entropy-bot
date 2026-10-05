package io.github.mojolowjo.entropybot.routewalk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripLogTest {
    static TripLog.Trip trip(int n, String mode, boolean ok, double s, long nodes, int segments, double stopped) {
        return new TripLog.Trip("2026-10-04 20:00:00", "1004-200000", n, "base", "farm", mode, mode, ok, s, 41.2, 2,
                segments, stopped, 0.4, nodes, nodes * 20, 15, 0, nodes < 0 ? 0 : 2, false, ok ? "ok" : "error: no path, stuck");
    }

    @Test
    void headerAndRowHaveTheSameColumns() {
        String row = TripLog.row(trip(1, "goal", true, 40.5, 3000, 2, 1.25));
        assertEquals(TripLog.HEADER.split(",").length, TripLog.split(row).size());
        assertEquals("2026-10-04 20:00:00,1004-200000,1,base,farm,goal,goal,1,40.50,41.20,2,2,1.25,0.40,3000,60000,15,0,2,0,ok", row);
    }

    @Test
    void commasAndQuotesAreQuoted() {
        TripLog.Trip t = new TripLog.Trip("t", "x", 2, "base", "farm", "legs", "plain (no plan: \"x\", y)", false, 1, 2, 0, 0, 0,
                -1, -1, -1, 0, 1, 0, true, "error: no path to 1 2 3 - stuck at 4 5 6, 10 blocks short\nmore");
        String row = TripLog.row(t);
        List<String> f = TripLog.split(row);
        assertEquals(TripLog.HEADER.split(",").length, f.size());
        assertEquals("plain (no plan: \"x\", y)", f.get(6));
        assertEquals("error: no path to 1 2 3 - stuck at 4 5 6, 10 blocks short more", f.get(20));
        assertTrue(!row.contains("\n"));
        assertEquals("-1.00", f.get(13));
        assertEquals("1", f.get(19));
    }

    @Test
    void summaryComparesWithPlain() {
        List<TripLog.Trip> t = new ArrayList<>();
        t.add(trip(1, "goal", true, 42, 2000, 2, 1));
        t.add(trip(2, "legs", true, 50, 1000, 4, 3));
        t.add(trip(3, "plain", true, 40, 5000, 3, 2));
        t.add(trip(4, "goal", true, 42, 2000, 2, 1));
        t.add(trip(5, "legs", false, 0, -1, 0, 0));
        t.add(trip(6, "plain", true, 40, 5000, 3, 2));
        Map<String, TripLog.ModeSummary> m = TripLog.byMode(t);
        assertEquals(2, m.get("goal").ok());
        assertEquals(42, m.get("goal").seconds(), 1e-9);
        assertEquals(2000, m.get("goal").nodes(), 1e-9);
        assertEquals(1, m.get("legs").ok());
        assertEquals(2, m.get("legs").trips());
        String s = TripLog.summary(t);
        String[] lines = s.split("\n");
        assertEquals(3, lines.length, s);
        assertTrue(lines[0].startsWith("goal: 2/2 ok, 42.0 s"), lines[0]);
        assertTrue(lines[0].contains("vs plain: time +5%, nodes -60%, segments -1.0"), lines[0]);
        assertTrue(lines[1].contains("1 more failed"), lines[1]);
        assertTrue(lines[2].startsWith("plain: 2/2 ok"), lines[2]);
        assertTrue(!lines[2].contains("vs plain"));
    }

    @Test
    void nodesUnknownWithoutDebugLines() {
        List<TripLog.Trip> t = List.of(trip(1, "plain", true, 10, -1, 1, 0));
        assertEquals(-1, TripLog.byMode(t).get("plain").nodes(), 1e-9);
        assertTrue(TripLog.summary(t).contains("nodes ?"));
        assertEquals("route test: no trips recorded", TripLog.summary(List.of()));
    }
}
