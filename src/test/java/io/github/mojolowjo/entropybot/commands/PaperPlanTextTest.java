package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaperPlanTextTest {
    @Test
    void reportsAPlan() {
        var r = new PaperPlan.Result("break", "surface", true, "success_to_goal",
                List.of(new int[]{0, 10, 0}, new int[]{0, 11, 0}, new int[]{0, 12, 0}), 4, 2, 12, 1500, null);
        String t = r.text();
        assertTrue(t.startsWith("plan path surface (break): reachable, 3 moves, breaks 4, places 2, highest y 12, 1500 ms"), t);
        assertTrue(t.endsWith("-> 0,12,0 - next: plan go"), t);
        var none = new PaperPlan.Result("walk", "surface", false, "failure", List.of(), 0, 0, 0, 20000, null);
        assertEquals("plan path surface (walk): no way found (failure, 20000 ms)", none.text());
    }
}