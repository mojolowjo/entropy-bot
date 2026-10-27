package io.github.mojolowjo.entropybot.baritone;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** B7e (E1): the safety net's rules (the sim's "safety" run, ported): owned or not, the restore list, the "set" save hack. */
class SafetyNetTest {

    @Test
    void theRestoreListLeavesBreakingAndPlacingToTheForkProcesses() {
        // 0.39.z8 (owner 2026-10-22, "remove the old job habit"): allowBreak / allowPlace / allowBreakAnyway are each
        // fork process's own for its run; "stop" never flips them
        // 0.39.z77 hotbar: allowInventory is the fork run lease's too (Baritone reaches into the bag for its tool)
        assertEquals(List.of("buildIgnoreExisting"), SafetyRules.RESTORE_FALSE);
        assertFalse(SafetyRules.RESTORE_FALSE.contains("allowInventory") || SafetyRules.RESTORE_RESET.contains("allowInventory")
                || SafetyRules.RESTORE_FALSE_IF_PRESENT.contains("allowInventory"), "no stop restore turns the lease's allowInventory off");
        assertEquals(List.of("lavaCellCost"), SafetyRules.RESTORE_RESET);
        assertEquals(List.of("backfill", "allowBreakNextToWater", "allowBreakNextToLava", "pathUnderWater"),SafetyRules.RESTORE_FALSE_IF_PRESENT);
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
