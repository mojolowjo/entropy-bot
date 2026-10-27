package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.commands.ProbeLines;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent;
import org.junit.jupiter.api.Test;

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
        StripSearch.Said one = ss.forSearch();
        assertNotNull(one);
        String l = ss.refuse(one, 35, -20, 18, "minecraft:gravel", Intent.TRAVEL, "break", "travel", "a falling block above", 1e6);
        assertEquals("probe strip refuse: 35 -20 18 minecraft:gravel: travel break travel (a falling block above) cost inf", l);
        assertNull(ss.refuse(one, 35, -20, 18, "minecraft:gravel", Intent.TRAVEL, "break", "travel", "a falling block above", 1e6),
                "once per cell per search");
        assertNotNull(ss.refuse(one, 35, -19, 18, "minecraft:gravel", Intent.TRAVEL, "break", "travel", "a falling block above", 1e6), "the head cell is its own");
        StripSearch.Said two = ss.forSearch();
        assertNotEquals(one, two, "two searches never share a said set");
        assertNotNull(ss.refuse(two, 35, -20, 18, "minecraft:gravel", Intent.MINE, "break", "water", "digging down from in the water", 1e6),
                "a new search says the cell again");

        for (int i = 0; i < StripSearch.maxLines() - 2; i++) assertNotNull(ss.refuse(one, i, 100, 0, "minecraft:stone", Intent.MINE, "break", "x", null, 1e6));
        assertEquals("probe strip refuse: " + StripSearch.maxLines() + " lines this search, the rest not printed",
                ss.refuse(one, 0, 101, 0, "minecraft:stone", Intent.MINE, "break", "x", null, 1e6));
        assertNull(ss.refuse(one, 1, 101, 0, "minecraft:stone", Intent.MINE, "break", "x", null, 1e6));

        ss.end(pr);
        assertFalse(ss.probing(), "the strip's end stops the probe");
        assertNull(ss.forSearch());
    }
}
