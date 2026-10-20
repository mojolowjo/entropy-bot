package io.github.mojolowjo.entropybot.aa.door;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AA_STEP2 3: a restore in the slot is AA's own work and never refuses an AA start. */
class DoorSlotTest {
    @Test
    void aRestoreInTheSlotDoesNotRefuseAnAaStart() {
        assertEquals(Door.Slot.HALT_RESTORE, Door.aaSlot(false, true, true), "putting back blocks: halted, then AA starts");
        assertEquals(Door.Slot.FREE, Door.aaSlot(false, false, false));
        assertEquals(Door.Slot.BUSY, Door.aaSlot(false, true, false), "the owner's job stays");
        assertEquals(Door.Slot.BUSY, Door.aaSlot(true, true, true), "a chain runs: busy");
    }
}
