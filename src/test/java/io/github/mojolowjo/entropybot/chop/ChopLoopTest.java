package io.github.mojolowjo.entropybot.chop;

import io.github.mojolowjo.entropybot.sense.FailMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.26.4: the TreeChop-aware chop loop, the tree retry order, the give-up text, the FailMemory mappings. */
class ChopLoopTest {
    static final String LOG = "minecraft:oak_log", CH = "treechop:chopped_log";

    static ChopLoop.Round rd(int lb, int la, String ib, String ia, String sb, String sa, int broke, int gained) {
        return new ChopLoop.Round(lb, la, ib, ia, sb, sa, broke, gained);
    }

    /** Runs a fake block sequence: log, then k chopped-log rounds (count in the data), then the fall. */
    static ChopLoop.Next run(ChopLoop.State s, int k, int maxChops) {
        String id = LOG, sig = LOG;
        ChopLoop.Next n = null;
        for (int i = 1; i <= k; i++) {
            String sigNow = CH + "{chops:" + i + "}";
            n = ChopLoop.step(s, rd(5, 5, id, CH, sig, sigNow, 1, 0), true, maxChops, ChopRules.ROUNDS);
            id = CH;
            sig = sigNow;
            if (n != ChopLoop.Next.AGAIN) return n;
        }
        return ChopLoop.step(s, rd(5, 0, id, "minecraft:air", sig, "minecraft:air", 1, 5), true, maxChops, ChopRules.ROUNDS);
    }

    @Test void firstChopIsProgress() {
        ChopLoop.State s = new ChopLoop.State();
        assertEquals(ChopLoop.Next.AGAIN, ChopLoop.step(s, rd(5, 5, LOG, CH, LOG, CH + "{1}", 1, 0), true, 24, 8));
        assertEquals(1, s.chops);
        assertEquals(0, s.stalls);
    }

    @Test void logThenChoppedThenFalls() {
        ChopLoop.State s = new ChopLoop.State();
        assertEquals(ChopLoop.Next.FELLED, run(s, 4, 24));
        assertEquals(4, s.chops);              // the felling break itself leaves no chopped log
        assertEquals(5, s.logsDown);
    }

    @Test void bigTreeManyChopsStillFelled() {
        ChopLoop.State s = new ChopLoop.State();
        assertEquals(ChopLoop.Next.FELLED, run(s, 15, 24));
    }

    @Test void maxChopsCapsTheTree() {
        ChopLoop.State s = new ChopLoop.State();
        assertEquals(ChopLoop.Next.PARTIAL, run(s, 30, 6));
        assertEquals(6, s.chops);
    }

    @Test void maxChopsClamped() {
        ChopLoop.State s = new ChopLoop.State();
        assertEquals(ChopLoop.Next.PARTIAL, run(s, 30, 1));
        assertEquals(ChopLoop.MIN_MAX_CHOPS, s.chops);
    }

    @Test void dataChangeCountsAsChop() {
        assertEquals(1, ChopLoop.chopsIn(rd(5, 5, CH, CH, CH + "{2}", CH + "{3}", 0, 0)));
    }

    @Test void breakOnChoppedLogCounts() {
        assertEquals(3, ChopLoop.chopsIn(rd(5, 5, CH, CH, CH + "{2}", CH + "{2}", 3, 0)));
    }

    @Test void nothingChangedIsNoChop() {
        assertEquals(0, ChopLoop.chopsIn(rd(5, 5, CH, CH, CH + "{2}", CH + "{2}", 0, 0)));
        assertEquals(0, ChopLoop.chopsIn(rd(5, 5, LOG, LOG, LOG, LOG, 0, 0)));
    }

    @Test void untouchedTrunkIsUnreachableAfterThreeRounds() {
        ChopLoop.State s = new ChopLoop.State();
        ChopLoop.Round r = rd(5, 5, LOG, LOG, LOG, LOG, 0, 0);
        assertEquals(ChopLoop.Next.AGAIN, ChopLoop.step(s, r, true, 24, 8));
        assertEquals(ChopLoop.Next.AGAIN, ChopLoop.step(s, r, true, 24, 8));
        assertEquals(ChopLoop.Next.UNREACHABLE, ChopLoop.step(s, r, true, 24, 8));
    }

    @Test void stallAfterChopsIsPartialNotUnreachable() {
        ChopLoop.State s = new ChopLoop.State();
        ChopLoop.step(s, rd(5, 5, LOG, CH, LOG, CH + "{1}", 1, 0), true, 24, 8);
        ChopLoop.Round none = rd(5, 5, CH, CH, CH + "{1}", CH + "{1}", 0, 0);
        ChopLoop.step(s, none, true, 24, 8);
        ChopLoop.step(s, none, true, 24, 8);
        assertEquals(ChopLoop.Next.PARTIAL, ChopLoop.step(s, none, true, 24, 8));
    }

    @Test void withoutTreeChopChoppedLogIsNotProgress() {
        ChopLoop.State s = new ChopLoop.State();
        ChopLoop.Round r = rd(5, 5, LOG, CH, LOG, CH, 1, 0);
        ChopLoop.step(s, r, false, 24, 8);
        ChopLoop.step(s, r, false, 24, 8);
        assertEquals(ChopLoop.Next.UNREACHABLE, ChopLoop.step(s, r, false, 24, 8));
        assertEquals(0, s.chops);
    }

    @Test void withoutTreeChopFewerLogsIsProgress() {
        ChopLoop.State s = new ChopLoop.State();
        assertEquals(ChopLoop.Next.AGAIN, ChopLoop.step(s, rd(5, 3, LOG, LOG, LOG, LOG, 2, 2), false, 24, 8));
        assertEquals(ChopLoop.Next.FELLED, ChopLoop.step(s, rd(3, 0, LOG, "minecraft:air", LOG, null, 3, 3), false, 24, 8));
        assertEquals(5, s.logsDown);
    }

    @Test void withoutTreeChopRoundCap() {
        ChopLoop.State s = new ChopLoop.State();
        ChopLoop.Next n = null;
        for (int i = 0; i < 8; i++) n = ChopLoop.step(s, rd(20 - i, 19 - i, LOG, LOG, LOG, LOG, 1, 1), false, 24, 8);
        assertEquals(ChopLoop.Next.PARTIAL, n);
    }

    @Test void gainedLogsCountAsProgress() {
        ChopLoop.State s = new ChopLoop.State();
        ChopLoop.Round r = rd(5, 5, LOG, LOG, LOG, LOG, 0, 2);
        for (int i = 0; i < 3; i++) ChopLoop.step(s, r, true, 24, 8);
        assertEquals(0, s.stalls);
    }

    @Test void orderReachableFirstThenNearest() {
        List<ChopLoop.Cand> o = ChopLoop.order(List.of(
                new ChopLoop.Cand(new int[]{1, 64, 1}, 2, false),
                new ChopLoop.Cand(new int[]{9, 64, 9}, 162, true),
                new ChopLoop.Cand(new int[]{4, 64, 4}, 32, true)));
        assertEquals(4, o.get(0).base()[0]);
        assertEquals(9, o.get(1).base()[0]);
        assertEquals(1, o.get(2).base()[0]);
    }

    @Test void orderAllUnreachableKeepsDistance() {
        List<ChopLoop.Cand> o = ChopLoop.order(List.of(
                new ChopLoop.Cand(new int[]{3, 0, 0}, 9, false),
                new ChopLoop.Cand(new int[]{1, 0, 0}, 1, false)));
        assertEquals(1, o.get(0).base()[0]);
    }

    @Test void reachFailuresGoOnToMaxTrees() {
        assertNull(ChopLoop.giveUp(3, 3, 8, "1 2 3"));
        assertNull(ChopLoop.giveUp(7, 7, 8, "1 2 3"));
        assertNotNull(ChopLoop.giveUp(8, 8, 8, "1 2 3"));
    }

    @Test void otherFailuresStopAtThree() {
        assertNotNull(ChopLoop.giveUp(3, 1, 8, null));
        assertNull(ChopLoop.giveUp(2, 1, 8, null));
    }

    @Test void maxTreesClamped() {
        assertNotNull(ChopLoop.giveUp(3, 3, 1, null));
        assertNull(ChopLoop.giveUp(19, 19, 50, null));
        assertNotNull(ChopLoop.giveUp(20, 20, 50, null));
    }

    @Test void giveUpTextNamesCountAndNearest() {
        assertEquals("tried 8 trees in a row and none came down - nearest reachable tree at 10 64 -3", ChopLoop.giveUp(8, 8, 8, "10 64 -3"));
        assertTrue(ChopLoop.giveUp(8, 8, 8, null).endsWith("no tree with a free spot next to its trunk"));
    }

    @Test void stopReasonUsesGiveUp() {
        ChopRules.Args a = ChopRules.parse("4");
        assertNull(ChopRules.stopReason(a, 0, 0, 0, 100, 5, 5, 8, null));
        assertTrue(ChopRules.stopReason(a, 0, 0, 0, 100, 8, 8, 8, null).startsWith("tried 8 trees"));
        assertEquals("", ChopRules.stopReason(a, 4, 1, 0, 100, 8, 8, 8, null));
    }

    @Test void endTextCarriesFelledNote() {
        String t = ChopRules.endText(2, java.util.Map.of(LOG, 11), 2, "", List.of(ChopLoop.felledNote(2, 9)), null);
        assertEquals("ok: chopped 2 trees, 11 oak_log, replanted 2; felled 2 trees (TreeChop: 9 chops)", t);
        assertEquals("felled 1 tree (TreeChop: 1 chop)", ChopLoop.felledNote(1, 1));
    }

    @Test void failMemoryChoppedNothingIsNoPath() {
        assertEquals(FailMemory.Reason.NO_PATH, FailMemory.classify("error: chopped nothing - tried 8 trees in a row and none came down; no axe, and no crafting table near me to make one - chopped by hand"));
    }

    @Test void failMemoryTrunkIsNoPath() {
        assertEquals(FailMemory.Reason.NO_PATH, FailMemory.classify("ok: chopped 1 tree, 4 oak_log; tree at 1 2 3: skipped - I couldn't get at its trunk"));
    }

    @Test void failMemoryNoTreesIsNoPath() {
        assertEquals(FailMemory.Reason.NO_PATH, FailMemory.classify("error: chopped nothing - no trees in my areas"));
    }

    @Test void failMemoryAxeNoteAloneIsNoClass() {
        assertNull(FailMemory.classify("ok: chopped 3 trees, 14 oak_log, replanted 3; no axe, and no crafting table near me to make one - chopped by hand"));
    }

    @Test void failMemoryOutsideStillWins() {
        assertEquals(FailMemory.Reason.OUTSIDE, FailMemory.classify("error: chopped nothing - I'm outside my areas"));
    }

    @Test void brainKeysHaveDefaults() {
        assertEquals(24, ChopLoop.DEFAULT_MAX_CHOPS);      // AA_CLEANUP 3: the BrainConfig keys went, these defaults stay
        assertEquals(8, ChopLoop.DEFAULT_MAX_TREES);
    }
}
