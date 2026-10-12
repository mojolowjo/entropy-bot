package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PassScheduleTest {
    @Test
    void loadIsDueAndASecondLoadIsAResend() {
        PassSchedule s = new PassSchedule();
        s.loaded(1, 1, 100);
        List<PassSchedule.Item> t = s.take(0, 0, 100, 4);
        assertEquals(List.of(new PassSchedule.Item(1, 1, PassSchedule.Why.LOAD)), t);
        s.loaded(1, 1, 200);
        assertEquals(PassSchedule.Why.RESEND, s.take(0, 0, 200, 4).get(0).why());
        assertEquals(1, s.loads);
        assertEquals(1, s.resends);
    }

    @Test
    void aChangeIsAPatchNotARead() {
        PassSchedule s = new PassSchedule();
        s.loaded(0, 0, 0);
        s.take(0, 0, 0, 4);
        // a patch needs nothing from the schedule: nothing due
        assertEquals(0, s.pending());
        assertTrue(s.take(0, 0, 10_000, 4).isEmpty());
    }

    @Test
    void droppedThenMissedThenOneRescan() {
        PassSchedule s = new PassSchedule();
        s.loaded(0, 0, 0);
        s.take(0, 0, 0, 4);
        assertTrue(s.missed(0, 0, 1000));
        assertTrue(s.missed(0, 0, 1001));        // deduped
        assertEquals(1, s.pending());
        assertEquals(1, s.missedMarks);
        assertEquals(1, s.missedDeduped);
        assertTrue(s.take(0, 0, 1500, 4).isEmpty(), "not before the delay");
        assertEquals(PassSchedule.Why.MISSED, s.take(0, 0, 1000 + PassSchedule.MISSED_DELAY_MS, 4).get(0).why());
        assertFalse(s.missed(9, 9, 0), "an unloaded chunk is not rescanned");
    }

    @Test
    void budgetLeavesTheRestNearestFirst() {
        PassSchedule s = new PassSchedule();
        for (int i = 0; i < 5; i++) s.loaded(i, 0, 0);
        List<PassSchedule.Item> t = s.take(0, 0, 0, 2);
        assertEquals(0, t.get(0).cx());
        assertEquals(1, t.get(1).cx());
        assertEquals(3, s.pending());
        s.requeue(t.get(1), 0);
        assertEquals(4, s.pending());
        assertEquals(1, s.take(0, 0, 0, 1).get(0).cx());
    }

    @Test
    void unloadDropsAndFailedRetries() {
        PassSchedule s = new PassSchedule();
        s.loaded(2, 2, 0);
        s.unloaded(2, 2);
        assertEquals(0, s.pending());
        s.loaded(3, 3, 0);
        PassSchedule.Item it = s.take(0, 0, 0, 1).get(0);
        s.failed(it, 0);
        assertEquals(PassSchedule.Why.RETRY, s.take(0, 0, PassSchedule.MISSED_DELAY_MS, 1).get(0).why());
        assertEquals(PassSchedule.Why.LOAD, it.why());
        s.loaded(4, 4, 0);
        s.loaded(4, 4, 0);       // twice: one entry (dedupe)
        assertEquals(1, s.pending());
    }
}
