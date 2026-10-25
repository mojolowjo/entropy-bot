package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z66 review 1 (Opus Design, rule 6): the step start halts only AA's own put-back (the afterJob tail, Job.aaPutBack),
 * never a restore the owner started ("restore now", the take down of placed blocks).
 */
class Z66ReviewTest {

    /** A job slot: one job running (or none), AA's put-back or the owner's restore; finish ends it unless it is stuck. */
    static final class FakeSlot implements RestoreHalt.Slot {
        boolean running = true, aa, chain, stuck;
        final List<String> finished = new ArrayList<>(), logs = new ArrayList<>();

        @Override public boolean chainRunning() { return chain; }
        @Override public boolean jobRunning() { return running; }
        @Override public boolean aaPutBack() { return aa; }
        @Override public String status() { return "putting back blocks I broke on the way (0 of 5)"; }
        @Override public int pending() { return 63; }
        @Override public void finish(String msg) { finished.add(msg); if (!stuck) running = false; }
        @Override public void log(String line) { logs.add(line); }
    }

    @Test
    void aasOwnPutBackIsHaltedForTheStepWithTheLine() {
        FakeSlot s = new FakeSlot();
        s.aa = true;
        assertNull(RestoreHalt.haltFor("explore 5 until deepslate_iron_ore|iron_ore", s), "halted: the step starts");
        assertEquals(List.of(RestoreHalt.STOPPED), s.finished);
        assertEquals(List.of("steps: restore halted for step \"explore 5 until deepslate_iron_ore|iron_ore\" (putting back blocks I broke on the way (0 of 5); 63 wait in the ledger)"), s.logs);
    }

    @Test
    void theOwnersRestoreIsNeverTouched() {
        FakeSlot s = new FakeSlot();
        s.aa = false;                                    // Jobs.startRestore: "restore now" or the take down
        assertNull(RestoreHalt.haltFor("collect 3 raw_iron", s), "not AA's: nothing done here (stepJob answers busy as before)");
        assertTrue(s.finished.isEmpty(), s.finished.toString());
        assertTrue(s.running);
        assertTrue(s.logs.isEmpty());
    }

    @Test
    void noJobOrAChainIsNotHaltedAndAStuckRestoreIsSaid() {
        FakeSlot none = new FakeSlot();
        none.running = false;
        none.aa = true;
        assertNull(RestoreHalt.haltFor("x", none));
        assertTrue(none.finished.isEmpty());
        FakeSlot chain = new FakeSlot();
        chain.aa = true;
        chain.chain = true;
        assertNull(RestoreHalt.haltFor("x", chain));
        assertTrue(chain.finished.isEmpty(), "a chain holds the slot: busy elsewhere, never halted here");
        FakeSlot stuck = new FakeSlot();
        stuck.aa = true;
        stuck.stuck = true;
        String r = RestoreHalt.haltFor("x", stuck);
        assertNotNull(r);
        assertTrue(r.startsWith("error: busy: the restore did not stop for step x"), r);
    }
}
