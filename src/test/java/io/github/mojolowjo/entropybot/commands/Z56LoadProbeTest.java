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
        // D11: the re-join counts (a fork with them), left out for a fork without
        String joins = new ProbeLines.LoadFacts(60, "w", 0, 0, 0, new long[]{4, 4, 100}, 0, 0, null, 1, 2, 0, 0, new long[]{3, 3, 2, 1}).line(1);
        assertTrue(joins.contains("nodes opened +100; re-joins +3 (join searches started +3 landed +2 failed +1)"), joins);
        // D11 fix: the failure kinds and the re-joins queued for a slot (a fork with the 9 counts)
        String kinds = new ProbeLines.LoadFacts(60, "w", 0, 0, 0, new long[]{4, 4, 100}, 0, 0, null, 1, 2, 0, 0, new long[]{9, 6, 2, 4, 2, 1, 0, 5, 1}).line(1);
        assertTrue(kinds.contains("re-joins +9 (join searches started +6 landed +2 failed +4: budget +2 no path +1 off the path +0 other +1; queued for a slot +5)"), kinds);
        assertTrue(new ProbeLines.LoadFacts(60, "w", 0, 0, 0, new long[]{4, 4, 100}, 0, 0, null, 1, 2, 0, 0, new long[]{-1}).line(1).contains("re-joins not readable in this fork jar"));
        assertFalse(old.contains("re-joins"), "no join facts given (the old constructor): nothing said");
        // D11 build 2: the route-memory counts, and a fork jar without them said
        String routes = new ProbeLines.LoadFacts(60, "w", 0, 0, 0, new long[]{4, 4, 100}, 0, 0, null, 1, 2, 0, 0, new long[]{-1}, new long[]{5, 3, 1, 1, 2}).line(1);
        assertTrue(routes.contains("routes asked +5 reused +3 check failed: changed +1 not loaded +1; saved +2"), routes);
        assertTrue(new ProbeLines.LoadFacts(60, "w", 0, 0, 0, new long[]{4, 4, 100}, 0, 0, null, 1, 2, 0, 0, new long[]{-1}, new long[]{-1}).line(1).contains("routes not readable in this fork jar"));
        // CPU job: the fork's cpu counts, and rule 7 when the jar lacks them
        String cpu = new ProbeLines.LoadFacts(60, "w", 0, 0, 0, new long[]{4, 4, 100}, 0, 0, null, 1, 2, 0, 0, new long[]{-1}, new long[]{-1}, new long[]{1, 2, 3, 40, 5, 60, 70}).line(1);
        assertTrue(cpu.contains("cpu: asks timed out +1, shared +2, joined +3; fluid cache hits +40 fills +5; advisor hits +60 of +70 asks"), cpu);
        assertTrue(new ProbeLines.LoadFacts(60, "w", 0, 0, 0, new long[]{4, 4, 100}, 0, 0, null, 1, 2, 0, 0, new long[]{-1}, new long[]{-1}, new long[]{-1}).line(1).contains("cpu counts not readable in this fork jar"));
        assertNull(ProbeLines.cpuCounts(new Object()), "no cpuCounts method: null");
        // a fork object without routeCounts: {-1}, never a silent zero
        assertNull(ProbeLines.routeCounts(new Object()));
    }

    @Test
    void theGuardSectionNamesItsLapsAndTheRest() {
        String l = ProbeLines.inside(new String[]{"guard", "steps", "aa tick", "jobs", "hotbar"},
                new long[]{100_000, 20_000, 30_000_000, 46_800_000, 10_000}, 5, 80.0);
        assertEquals("inside guard+commands: guard 0.1, aa tick 30.0, jobs 46.8 | unaccounted 3.1 ms (see probe aatick for the aa tick)", l);
        assertEquals("inside guard+commands: no lap over 0.05 | unaccounted 1.0 ms (see probe aatick for the aa tick)",
                ProbeLines.inside(new String[0], new long[0], 0, 1.0));
    }

    @Test
    void theSlowTickLineSplitsTheMod() {
        String l = ProbeLines.slowTick(77, 180.4, 50, 150.2, new double[]{140, 2, 1, 5, 2, 0.2}, "job crafting furnace | path events none | priced paths landed 1");
        assertEquals("t=77 | client tick 180.4 ms (over debug.slowTickMs 50) | mod 150.2 ms: guard+commands (aa) 140.0, reflexes 2.0, probes+knowledge 1.0, chunk pass 5.0, recorder 2.0,"
                + " rest 0.2 | outside the mod 30.2 ms (vanilla, Baritone's tick, other mods) | job crafting furnace | path events none | priced paths landed 1", l);
    }
}
