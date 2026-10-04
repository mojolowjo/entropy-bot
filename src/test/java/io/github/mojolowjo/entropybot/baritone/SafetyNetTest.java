package io.github.mojolowjo.entropybot.baritone;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** B7e (E1): the safety net's rules (the sim's "safety" run, ported): owned or not, the restore list, the "set" save hack. */
class SafetyNetTest {

    @Test
    void breakingOrPlacingGoesOffOnlyWhenNoJobOwnsIt() {
        assertTrue(SafetyRules.shouldTurnOff(true, false, false, false), "a stray allowBreak (e.g. b set allowBreak true)");
        assertTrue(SafetyRules.shouldTurnOff(false, true, false, false), "a stray allowPlace");
        assertFalse(SafetyRules.shouldTurnOff(false, false, false, false), "nothing on: nothing to do");
        assertFalse(SafetyRules.shouldTurnOff(true, false, true, false), "the mod's mine ... dig owns breaking");
        assertFalse(SafetyRules.shouldTurnOff(false, true, false, true), "the mod's build owns placing");
        assertFalse(SafetyRules.shouldTurnOff(true, true, true, false), "while a mine owns it, placing on too is left alone (as the bridge)");
        assertFalse(SafetyRules.shouldTurnOff(true, true, false, true));
    }

    @Test
    void baritoneIsCancelledOnlyWhenBusyWithNoJob() {
        assertTrue(SafetyRules.cancelBaritone(false, false));
        assertFalse(SafetyRules.cancelBaritone(true, false), "idle: nothing to cancel");
        assertFalse(SafetyRules.cancelBaritone(false, true), "a job's walk goes on (only the settings go back)");
    }

    @Test
    void theRestoreListIsTheBridges() {
        assertEquals(List.of("allowBreak", "allowPlace", "allowInventory", "buildIgnoreExisting"), SafetyRules.RESTORE_FALSE);
        assertEquals(List.of("allowBreakAnyway"), SafetyRules.RESTORE_RESET);
        assertEquals(List.of("backfill"), SafetyRules.RESTORE_FALSE_IF_PRESENT);
    }

    @Test
    void theSetRegex() {
        for (String s : new String[]{"set allowSprint true", "SET allowBreak true", "setting allowSprint", "settings", "  set x 1", "Settings reset"}) {
            assertTrue(SafetyRules.saves(s), s);
        }
        for (String s : new String[]{"sel clear", "settle", "setx 1", "goto 1 2 3", "", "cancel", "mine iron_ore", "reset set"}) {
            assertFalse(SafetyRules.saves(s), s);
        }
        assertFalse(SafetyRules.saves(null));
    }

    @Test
    void aSetRunsWithTheListResetAndPutBackAfter() {
        List<String> log = new ArrayList<>();
        Boolean r = SafetyRules.executeGuarded("set allowSprint true", () -> log.add("reset"), () -> { log.add("execute"); return true; }, () -> log.add("put back"));
        assertTrue(r);
        assertEquals(List.of("reset", "execute", "put back"), log);
        log.clear();
        SafetyRules.executeGuarded("goto 1 2 3", () -> log.add("reset"), () -> { log.add("execute"); return true; }, () -> log.add("put back"));
        assertEquals(List.of("execute"), log, "other commands never touch the list");
    }

    @Test
    void theListComesBackEvenWhenBaritoneThrows() {
        List<String> log = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> SafetyRules.executeGuarded("set x y", () -> log.add("reset"),
                () -> { log.add("execute"); throw new IllegalStateException("bad setting"); }, () -> log.add("put back")));
        assertEquals(List.of("reset", "execute", "put back"), log, "finally: the list is never left reset");
    }

    @Test
    void timing() {
        assertTrue(SafetyRules.protectedDue(7, false), "not put yet: at once");
        assertFalse(SafetyRules.protectedDue(7, true));
        assertTrue(SafetyRules.protectedDue(400, true), "again every 200 ticks");
        assertTrue(SafetyRules.netDue(300));
        assertFalse(SafetyRules.netDue(200));
        assertEquals("protected blocks: hash, 1234", SafetyRules.kindLine("hash", 1234));
    }
}
