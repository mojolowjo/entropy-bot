package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.39.z56 item 3 (owner 2026-10-24: very low frame rates at times): the load probe's pure formatter and window. */
class Z56LoadProbeTest {
    @Test
    void theWindowAveragesAndResets() {
        ProbeLines.Load w = new ProbeLines.Load();
        w.tick(2, 1);
        w.tick(10, 7);
        w.frame(16);
        w.frame(250);
        String l = w.takeLine();
        assertEquals("client tick avg 6.0 max 10.0 ms (2 ticks) | mod avg 4.0 max 7.0 ms (67% of the tick time) | frames 2, longest 250 ms", l);
        assertEquals("client tick: none measured | frames 0, longest 0 ms", w.takeLine(), "reset after each line");
    }

    @Test
    void theLoadLineNamesEveryFact() {
        String l = new ProbeLines.LoadFacts(9, "client tick avg 6.0 max 10.0 ms (20 ticks)", 3, 2, 1, new long[]{4, 4, 120000}, 2, 1,
                new long[]{17, 340, 52428800}, 900, 4096, 3, 41).line(2000);
        assertEquals("t=2000 | fps 9 | client tick avg 6.0 max 10.0 ms (20 ticks) | priced paths started +3 landed +2 in flight 1; fork segments started +4 landed +4, nodes opened +120000"
                + " | Baritone path calcs started +2 failed +1 | recorder queue 17, lines written +340, disk 50.0 MB | heap 900/4096 MB | GC +3 in 41 ms", l);
        String old = new ProbeLines.LoadFacts(60, "w", 0, 0, 0, new long[]{-1, -1, -1}, 0, 0, null, 1, 2, 0, 0).line(1);
        assertTrue(old.contains("fork counts not in this jar") && old.contains("recorder off"), old);
    }

    @Test
    void theSlowTickLineSplitsTheMod() {
        String l = ProbeLines.slowTick(77, 180.4, 50, 150.2, new double[]{140, 2, 1, 5, 2, 0.2}, "job crafting furnace | path events none | priced paths landed 1");
        assertEquals("t=77 | client tick 180.4 ms (over debug.slowTickMs 50) | mod 150.2 ms: guard+commands (aa) 140.0, reflexes 2.0, probes+knowledge 1.0, chunk pass 5.0, recorder 2.0,"
                + " rest 0.2 | outside the mod 30.2 ms (vanilla, Baritone's tick, other mods) | job crafting furnace | path events none | priced paths landed 1", l);
    }
}
