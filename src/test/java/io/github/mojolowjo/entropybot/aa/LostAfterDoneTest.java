package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.LineDeposits;
import io.github.mojolowjo.entropybot.aa.brain.Only;
import io.github.mojolowjo.entropybot.aa.brain.Thing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * lost-after-done (tester-2 w064, run 3; to-do/run3-no-causes-2026-11-24.md): cobblestone 32 -d was met and noted at
 * 23:20:30, a creeper killed the bot at 23:20:37 and the bag went from 35 to 0; the met order had left the goals, nothing
 * opened it again, and the line's end (line 2123) deposited spruce_log 16 and torch 4 with nothing said of the 32.
 */
class LostAfterDoneTest {
    @AfterEach
    void clear() {
        Only.set("off");
        io.github.mojolowjo.entropybot.aa.brain.BatchWay.clear();
    }

    @Test
    void aMetOrderWhoseAmountLeftTheBag_isOpenAgain_andTheLineWaitsForIt() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal cobble = s.order("cobblestone", 32, "b1", true);
        s.order("oak_log", 16, "b1", true);
        s.bag.put("cobblestone", 32);
        s.bag.put("oak_log", 3);                 // w064: 35 in the bag at the death
        s.tick();
        assertNull(Goals.find(s.bb, "cobblestone"), "met and taken off the goals: " + s.said);
        assertTrue(String.join("\n", s.said).contains("cobblestone done: its deposit items noted (cobblestone 32"), String.join("\n", s.said));
        s.bag.clear();                           // the death: the whole bag lost
        s.tick();
        assertSame(cobble, Goals.find(s.bb, "cobblestone"), "the met order is among the goals again: " + s.said);
        assertFalse(cobble.met);
        assertTrue(String.join("\n", s.said).contains("cobblestone was done, and the bag holds 0 of its 32 for the line's deposit now: open again"), String.join("\n", s.said));
        // the logs come: the line does not end without the cobblestone
        s.bag.put("oak_log", 16);
        s.tick();
        assertTrue(s.due.isEmpty(), "the line waits for the cobblestone: " + s.said);
        s.bag.put("cobblestone", 32);
        s.tick();
        assertEquals(1, s.due.size(), String.join("\n", s.said));
        assertEquals(Map.of("cobblestone", 32, "oak_log", 16), s.due.get(0));
    }

    @Test
    void aMetOrderStillInTheBag_staysDone() {
        OrderLineSim s = new OrderLineSim();
        s.order("cobblestone", 32, "b1", true);
        s.order("oak_log", 16, "b1", true);
        s.bag.put("cobblestone", 32);
        s.tick();
        s.tick();
        s.tick();
        assertNull(Goals.find(s.bb, "cobblestone"), String.join("\n", s.said));
        assertFalse(String.join("\n", s.said).contains("open again"), String.join("\n", s.said));
    }

    @Test
    void theLinesEnd_namesEveryShortfall() {
        LineDeposits d = new LineDeposits();
        Goals.Goal a = Goals.draft(Thing.resolve("cobblestone", OrderLineSim.CATALOG), 32, 0, 0);
        Goals.Goal b = Goals.draft(Thing.resolve("oak_log", OrderLineSim.CATALOG), 16, 0, 0);
        Goals.Goal c = Goals.draft(Thing.resolve("stick", OrderLineSim.CATALOG), 4, 0, 0);
        d.add(a, "b1", true);
        d.add(b, "b1", true);
        d.add(c, "b1", true);
        Map<String, Integer> bag = new HashMap<>(Map.of("oak_log", 16, "stick", 2));
        d.weigh(a, "stopped", bag, "3 64 -5");
        d.weigh(b, "done", bag, "3 64 -5");
        LineDeposits.Out out = d.weigh(c, "done", bag, "3 64 -5");
        assertEquals(LineDeposits.Kind.DUE, out.kind(), out.text());
        assertTrue(out.text().endsWith("; short of what was ordered: cobblestone 0 of 32, stick 2 of 4"), out.text());
    }
}
