package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.door.Door;

/**
 * 0.39.z66 item 1 + review 1 (rule 6): a typed step starting while AA's OWN put-back holds the job slot halts it - the
 * restore tail Jobs.restoreGate starts after a job (RestoreLive.afterJob, Job.aaPutBack), below every desire, its blocks
 * kept in the ledger (RestoreRun.stopRest moves the rest to its left list; only a block put back / taken down / gone is
 * dropped from the ledger). An owner-started restore ("restore now", the take down of placed blocks: Jobs.startRestore,
 * aaPutBack false) is never halted here: the step answers busy as before. Pure over {@link Slot} (JUnit: Z66ReviewTest).
 * Loader notes: none (no game API).
 */
final class RestoreHalt {
    private RestoreHalt() {}

    /** The job slot as the halt sees it (Commands' jobs and chains in the game; a fake in the test). */
    interface Slot {
        boolean chainRunning();
        boolean jobRunning();
        /** The running job is AA's own put-back (Job.aaPutBack with its restore run still going). */
        boolean aaPutBack();
        String status();
        int pending();
        void finish(String msg);
        void log(String line);
    }

    /** Null: no AA put-back in the slot (or it stopped); else the step's failure (it did not stop - said so). */
    static String haltFor(String label, Slot s) {
        if (Door.aaSlot(s.chainRunning(), s.jobRunning(), s.jobRunning() && s.aaPutBack()) != Door.Slot.HALT_RESTORE) return null;
        s.log("steps: restore halted for step \"" + label + "\" (" + s.status() + "; " + s.pending() + " wait in the ledger)");
        s.finish(STOPPED);
        return s.jobRunning() ? "error: busy: the restore did not stop for step " + label + " (" + s.status() + ")" : null;
    }

    /** The halted put-back's end words. */
    static final String STOPPED = "stopped: the order's step needs the slot (the restore waits)";
}
