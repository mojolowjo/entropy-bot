package io.github.mojolowjo.entropybot.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.39.m2: a reflex goal gives back the job's goal / hold it replaced. */
class EngineSlotTest {
    @Test void theJobsGoalComesBackAfterTheReflex() {
        EngineSlot<String> s = new EngineSlot<>();
        s.override("job walk");
        s.overrideReflex("swim up");
        assertTrue(s.reflexGoal());
        assertEquals("swim up", s.goal());
        s.overrideReflex("flee");          // a second reflex keeps the first save
        s.endReflex();
        assertEquals(EngineSlot.Mode.OVERRIDE, s.mode());
        assertEquals("job walk", s.goal());
        assertFalse(s.reflexGoal());
    }

    @Test void aHoldComesBackToo() {
        EngineSlot<String> s = new EngineSlot<>();
        s.hold();
        s.overrideReflex("escape lava");
        s.endReflex();
        assertEquals(EngineSlot.Mode.HOLD, s.mode());
    }

    @Test void whatIsAskedDuringTheReflexIsTheStateAfterIt() {
        EngineSlot<String> s = new EngineSlot<>();
        s.override("job walk");
        s.overrideReflex("swim up");
        s.release();                       // a fight settled meanwhile
        assertEquals("swim up", s.goal(), "the reflex keeps the wheel");
        s.endReflex();
        assertEquals(EngineSlot.Mode.NONE, s.mode());
    }

    @Test void baritonesCancelDropsEverything() {
        EngineSlot<String> s = new EngineSlot<>();
        s.override("job walk");
        s.overrideReflex("swim up");
        s.lostControl();
        s.endReflex();
        assertEquals(EngineSlot.Mode.NONE, s.mode());
        assertNull(s.goal());
    }
}
