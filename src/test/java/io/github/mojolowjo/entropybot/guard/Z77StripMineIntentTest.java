package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.commands.ProbeLines;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 mineintent (run DB 01:08:24: "travel may place at 25 5 5 (allowed x3 (travel))" inside the strip of 01:07:02-01:10:12):
 * a strip's searches are priced as MINE, the intent before it back at its end; the refuse probe is off by default and says
 * each refused cell once per search.
 */
class Z77StripMineIntentTest {
    /** The job intent as PolicyRuntime holds it (intent, by). */
    private static final class Holder implements StripSearch.Store {
        Intent intent = Intent.TRAVEL;
        String by = "default";
        @Override public Intent intent() { return intent; }
        @Override public String by() { return by; }
        @Override public void set(Intent i, String b) { intent = i; by = b; }
        /** What a search context built now carries (CostAdvisorHook.begin -> intentNow, no scoped intent). */
        Intent searchContext() { return intent; }
    }

    @Test
    void aStripAfterAWalkSearchesAsMineAndPutsTheWalksIntentBack() {
        StripSearch ss = new StripSearch();
        Holder pr = new Holder();
        pr.set(Intent.TRAVEL, "to strip");                  // the walk to the strip's start
        assertEquals(Intent.TRAVEL, pr.searchContext());
        ss.begin(pr, false);                                 // StripStep.start
        assertEquals(Intent.MINE, pr.searchContext(), "the strip's search context carries MINE");
        assertEquals(StripSearch.BY, pr.by());
        assertTrue(ss.running());

        // a reflex leg during the strip sets TRAVEL and puts back what it found (BaritoneCar); a next segment holds MINE
        pr.set(Intent.TRAVEL, "reflex");
        pr.set(Intent.MINE, StripSearch.BY);
        pr.set(Intent.TRAVEL, "something else");
        ss.hold(pr, false);                                  // the next segment / a running tick not held
        assertEquals(Intent.MINE, pr.searchContext(), "each next segment searches as MINE");

        ss.end(pr);                                          // the strip ended
        assertEquals(Intent.TRAVEL, pr.intent(), "after the strip the intent is back");
        assertEquals("to strip", pr.by());
        assertFalse(ss.running());
        ss.end(pr);                                          // a second end (status end, then cancel) changes nothing
        assertEquals("to strip", pr.by());
        ss.hold(pr, false);                                  // no strip: nothing held
        assertEquals(Intent.TRAVEL, pr.intent());
    }

    @Test
    void anIntentSetByAnotherJobDuringTheStripIsKept() {
        StripSearch ss = new StripSearch();
        Holder pr = new Holder();
        ss.begin(pr, false);
        pr.set(Intent.BUILD, "build");
        ss.end(pr);
        assertEquals(Intent.BUILD, pr.intent(), "not the strip's own: kept");
        assertEquals("build", pr.by());
    }

    @Test
    void theRefuseProbeIsOffByDefault() {
        assertFalse(ProbeLines.on(), "debug.walkProbe off by default");
        assertFalse(ProbeLines.stepOn(), "debug.stepProbe off by default");
        assertFalse(ProbeLines.stripRefuseOn(), "the strip refuse probe off by default");
        assertFalse(StripSearch.INSTANCE.probing(), "the mod's strip search does not probe by default");
        assertNull(StripSearch.INSTANCE.forSearch(), "no said set for a search with the probe off");

        StripSearch ss = new StripSearch();
        Holder pr = new Holder();
        ss.begin(pr, ProbeLines.stripRefuseOn());
        assertFalse(ss.probing());
        assertNull(ss.refuse(null, 35, -20, 18, "minecraft:gravel", Intent.MINE, "break", "falling", "a falling block above", 1e6),
                "probe off: no line");

        Tuning.set("debug.stepProbe", 1);
        try {
            assertTrue(ProbeLines.stripRefuseOn());
        } finally {
            Tuning.set("debug.stepProbe", 0);
        }
        Tuning.set("debug.walkProbe", 1);
        try {
            assertTrue(ProbeLines.stripRefuseOn(), "the strip probe (debug.walkProbe) turns it on too");
        } finally {
            Tuning.set("debug.walkProbe", 0);
        }
        assertFalse(ProbeLines.stripRefuseOn());
    }

    @Test
    void onTheProbeSaysEachRefusedCellOncePerSearchAndStopsAtTheStripsEnd() {
        StripSearch ss = new StripSearch();
        Holder pr = new Holder();
        ss.begin(pr, true);
        assertTrue(ss.probing());
        ss.focus(new int[]{35, -20, 18});                    // the strip's next cell (Baritone's goal)
        StripSearch.Said one = ss.forSearch();
        assertNotNull(one);
        String l = ss.refuse(one, 35, -20, 18, "minecraft:gravel", Intent.TRAVEL, "break", "travel", "a falling block above", 1e6);
        assertEquals("probe strip refuse: 35 -20 18 minecraft:gravel: travel break travel (a falling block above) cost inf", l);
        assertNull(ss.refuse(one, 35, -20, 18, "minecraft:gravel", Intent.TRAVEL, "break", "travel", "a falling block above", 1e6),
                "once per (cell, rule) per search");
        assertNotNull(ss.refuse(one, 35, -19, 18, "minecraft:gravel", Intent.TRAVEL, "break", "travel", "a falling block above", 1e6), "the head cell is its own");
        StripSearch.Said two = ss.forSearch();
        assertNotEquals(one, two, "two searches never share a said set");
        assertNotNull(ss.refuse(two, 35, -20, 18, "minecraft:gravel", Intent.MINE, "break", "water", "digging down from in the water", 1e6),
                "a new search says the cell again");

        // the cap: (cell, rule) pairs at the next cell, each its own line, up to debug.stripRefuseMaxLines
        for (int i = 0; i < StripSearch.maxLines() - 2; i++) assertNotNull(ss.refuse(one, 35, -20, 18, "minecraft:stone", Intent.MINE, "break", "x" + i, null, 1e6));
        assertEquals("probe strip refuse: " + StripSearch.maxLines() + " lines this search, the rest not printed",
                ss.refuse(one, 36, -20, 18, "minecraft:stone", Intent.MINE, "break", "x", null, 1e6));
        assertNull(ss.refuse(one, 34, -20, 18, "minecraft:stone", Intent.MINE, "break", "x", null, 1e6));

        ss.end(pr);
        assertFalse(ss.probing(), "the strip's end stops the probe");
        assertNull(ss.forSearch());
    }

    /**
     * 0.39.z77 strip 4 (run DF 03:41:45-03:51:46: 4,867 "probe strip refuse" lines for 564 cells - mine place floor (next to
     * lava) x 2166, mine break water x 1384, mine break lava x 1317 - no search past the cap, each of ~100 searches said its
     * ~40-58 far cells again): a per-cell line only for the next cell and its neighbours; every (cell, rule) refused once
     * per search counted into one summary line, said when the next search starts or the strip ends.
     */
    @Test
    void runDF_farRefusalsAreSummedPerSearch_theNextCellsNeighboursPrinted() {
        StripSearch ss = new StripSearch();
        Holder pr = new Holder();
        ss.begin(pr, true);
        List<String> said = new ArrayList<>();
        assertNotNull(ss.forSearch(said::add), "the probe on: a said set (no next cell known yet)");
        ss.focus(new int[]{30, 16, 6});                      // the south strip's goal at 03:44:54
        StripSearch.Said one = ss.forSearch(said::add);
        assertNotNull(one);
        assertTrue(said.isEmpty(), "the search before refused nothing: no summary");
        // far cells under the strip (DF 03:44:31.8 "30 0 3", "28 -2 6"): counted, not printed
        assertNull(ss.refuse(one, 30, 0, 3, "minecraft:stone", Intent.MINE, "break", "lava", "the break opens lava", 1e6));
        assertNull(ss.refuse(one, 28, -2, 6, "minecraft:stone", Intent.MINE, "place", "floor", "next to lava", 1e6));
        assertNull(ss.refuse(one, 28, -2, 6, "minecraft:stone", Intent.MINE, "break", "lava", "the break opens lava", 1e6), "the same cell, another rule: counted");
        assertNull(ss.refuse(one, 28, -2, 6, "minecraft:stone", Intent.MINE, "place", "floor", "next to lava", 1e6), "the same (cell, rule): once");
        assertNull(ss.refuse(one, 33, 16, 6, "minecraft:water", Intent.MINE, "break", "water", "digging down from in the water", 1e6), "3 off on x: not a neighbour");
        assertNull(ss.refuse(one, 30, 19, 6, "minecraft:stone", Intent.MINE, "break", "water", "digging down from in the water", 1e6), "2 over the head: not a neighbour");
        // the next cell's head and a neighbour under the feet: printed
        assertEquals("probe strip refuse: 30 17 7 minecraft:stone: mine break lava (the break opens lava) cost inf",
                ss.refuse(one, 30, 17, 7, "minecraft:stone", Intent.MINE, "break", "lava", "the break opens lava", 1e6));
        assertNotNull(ss.refuse(one, 31, 15, 5, "minecraft:stone", Intent.MINE, "place", "floor", "next to lava", 1e6));

        StripSearch.Said two = ss.forSearch(said::add);      // the next search: the last one's summary
        assertEquals(List.of("probe strip refuse: 6 cells refused: lava x 3, floor x 2, water x 2 (this search; 2 printed: the next cell 30 16 6 and its neighbours)"), said);
        assertNull(ss.refuse(two, 30, 0, 3, "minecraft:stone", Intent.MINE, "break", "lava", "the break opens lava", 1e6), "a far cell: counted in this search");
        assertEquals(List.of("probe strip refuse: 1 cells refused: lava x 1 (this search; 0 printed: the next cell 30 16 6 and its neighbours)"), ss.end(pr),
                "the strip's end says the last search's summary");
        assertTrue(ss.end(pr).isEmpty(), "a second end: nothing");

        // no next cell known (the strip's start, before Baritone names a goal cell): every refusal summed, none printed
        ss.begin(pr, true);
        StripSearch.Said three = ss.forSearch(said::add);
        assertNull(ss.refuse(three, 30, 16, 6, "minecraft:stone", Intent.MINE, "break", "lava", "the break opens lava", 1e6));
        assertEquals(List.of("probe strip refuse: 1 cells refused: lava x 1 (this search; 0 printed: the next cell not known)"), ss.end(pr));
    }
}
