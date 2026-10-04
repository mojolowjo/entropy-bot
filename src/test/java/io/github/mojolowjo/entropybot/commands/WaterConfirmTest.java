package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.clear.WaterPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Water plan: a dig stopped by a large body of water asks; "confirm" runs it again with "large"; anything else cancels. */
class WaterConfirmTest {
    static final String O = "mojolowjo";

    @Test
    void aLargeBodyOfWaterAsksAndConfirmRunsTheDigWithLarge() {
        ConfirmGate c = new ConfirmGate(null);
        String line = WaterPlan.largeLine("dig 247 -46 853 310 -44 855 floor junk drop water");
        String q = c.offer(O, line, "seal a large body of water (65+ source blocks) at 377 -45 854 and dig on", 1000);
        assertTrue(q.startsWith("confirm? seal a large body of water"), q);
        assertTrue(q.contains("say \"confirm\" within 30 s"), q);
        ConfirmGate.Gate g = c.gate(O, "confirm", false, 5000);
        assertNull(g.reply());
        assertEquals("dig 247 -46 853 310 -44 855 floor junk drop water large", g.line());
        // anything else cancels
        c.offer(O, line, "x", 1000);
        assertNull(c.gate(O, "status", false, 2000).reply());
        assertTrue(c.gate(O, "confirm", false, 3000).reply().startsWith("error: nothing to confirm"));
        // too late
        c.offer(O, line, "x", 1000);
        assertTrue(c.gate(O, "confirm", false, 1000 + ConfirmGate.WINDOW_MS + 1).reply().startsWith("error: too late"));
    }
}
