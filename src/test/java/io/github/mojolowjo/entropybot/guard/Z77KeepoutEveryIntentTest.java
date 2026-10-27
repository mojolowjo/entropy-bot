package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Snapshot;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 keepout (J2, review of 673b344d: under MINE the cost hook returned NaN and never asked the policy, so a strip's
 * search could path through a keep-out and be vetoed only at the click): CostAdvisorHook asks BreakPolicy.pathProtect
 * before its intent switch, like pathStructure; a keep-out or no-break cell is refused for every intent and the strip's
 * refuse probe names it. Control: a plain cell outside every protect box stays open (MINE keeps its own price).
 */
class Z77KeepoutEveryIntentTest {
    static final String OW = "minecraft:overworld";
    static final String POLICY = "{\"areas\":["
            + "{\"name\":\"neighbour\",\"type\":\"keep-out\",\"x1\":0,\"z1\":0,\"x2\":10,\"z2\":10},"
            + "{\"name\":\"garden\",\"type\":\"no-break\",\"x1\":50,\"z1\":0,\"x2\":60,\"z2\":10},"
            + "{\"name\":\"camp\",\"x1\":100,\"z1\":0,\"x2\":140,\"z2\":40}]}";
    static final Snapshot SP = Snapshot.of(Policy.parse(POLICY), false);

    /** What CostAdvisorHook.refused writes for a refused break during a strip's search (its probe on). */
    static String probeLine(StripSearch ss, StripSearch.Said said, int x, int y, int z, Intent in, Verdict v) {
        return ss.refuse(said, x, y, z, "minecraft:stone", in, "break", v.rule(), v.reason(), 1e6);
    }

    @Test
    void aStripUnderMineRefusesTheNextCellInsideAKeepOutAndTheProbeNamesIt() {
        StripSearch ss = new StripSearch();
        ss.begin(new StripSearch.Store() {
            Intent i = Intent.TRAVEL; String by = "default";
            @Override public Intent intent() { return i; }
            @Override public String by() { return by; }
            @Override public void set(Intent n, String b) { i = n; by = b; }
        }, true);
        ss.focus(new int[]{5, -20, 5});                     // the strip's next cell (0.39.z77 strip 4: only it and its neighbours print)
        StripSearch.Said said = ss.forSearch();

        Verdict ko = BreakPolicy.pathProtect(SP, OW, 5, -20, 5);
        assertFalse(ko.allowed(), "keep-out: refused");
        assertTrue(Double.isInfinite(ko.multiplier()), "priced as never through");
        assertEquals("keep-out", ko.rule());
        assertEquals("in neighbour (keep-out)", ko.reason());
        assertEquals("probe strip refuse: 5 -20 5 minecraft:stone: mine break keep-out (in neighbour (keep-out)) cost inf",
                probeLine(ss, said, 5, -20, 5, Intent.MINE, ko));

        Verdict nb = BreakPolicy.pathProtect(SP, OW, 55, -20, 5);
        assertFalse(nb.allowed(), "no-break: refused");
        assertEquals("no-break", nb.rule());
        ss.focus(new int[]{55, -20, 5});
        assertEquals("probe strip refuse: 55 -20 5 minecraft:stone: mine break no-break (in garden (no-break)) cost inf",
                probeLine(ss, ss.forSearch(), 55, -20, 5, Intent.MINE, nb));
        ss.end(new StripSearch.Store() {
            @Override public Intent intent() { return Intent.MINE; }
            @Override public String by() { return StripSearch.BY; }
            @Override public void set(Intent n, String b) {}
        });
    }

    @Test
    void theAnswerIsTheSameForEveryIntentAsTravelsJudge() {
        // pathProtect takes no intent; TRAVEL's own judge refuses the same cells, so MINE now matches it
        for (Intent in : new Intent[]{Intent.TRAVEL, Intent.MINE, Intent.DIG_OUT, Intent.BUILD, Intent.RESTORE}) {
            Verdict j = BreakPolicy.judge(SP, new BreakPolicy.Ask(BreakPolicy.Action.BREAK, in, OW, 5, -20, 5, BreakPolicy.BlockFacts.STONE, BreakPolicy.Where.DRY));
            assertFalse(j.allowed(), in + ": the policy refuses the keep-out cell");
        }
        assertEquals(BreakPolicy.judge(SP, new BreakPolicy.Ask(BreakPolicy.Action.BREAK, Intent.TRAVEL, OW, 5, -20, 5, BreakPolicy.BlockFacts.STONE, BreakPolicy.Where.DRY)).reason(),
                BreakPolicy.pathProtect(SP, OW, 5, -20, 5).reason(), "the same reason travel's judge gives");
    }

    @Test
    void controlAPlainCellOutsideEveryProtectBoxStaysOpen() {
        Verdict out = BreakPolicy.pathProtect(SP, OW, 500, -20, 500);
        assertTrue(out.allowed(), "outside every area: open, the hook goes on to MINE's own price (NaN)");
        assertEquals(1, out.multiplier(), 1e-9, "no price of its own");
        assertTrue(BreakPolicy.pathProtect(SP, OW, 120, -20, 20).allowed(), "inside a work area: open");
        assertTrue(BreakPolicy.pathProtect(SP, "minecraft:the_nether", 5, -20, 5).allowed(), "the keep-out's footprint in another dimension: open");
        assertFalse(BreakPolicy.pathProtect(null, OW, 500, -20, 500).allowed(), "no snapshot: refused, never open");
    }
}
