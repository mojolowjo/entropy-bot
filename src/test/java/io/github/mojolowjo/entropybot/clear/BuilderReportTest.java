package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.clear.BuilderReport.End;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.29.0: the end texts of "dig ... via baritone" and the step's watch (gave up = paused or 60 s without a change). */
class BuilderReportTest {
    static final ClearBox BOX = ClearBox.of(0, 50, 0, 3, 52, 3);
    static final String LABEL = "digging 0 50 0 to 3 52 3";

    static FakeWorld empty() {
        FakeWorld w = new FakeWorld();
        w.fill(0, 3, 50, 52, 0, 3, "air");
        return w;
    }

    static BaritoneMask.Leftover left(FakeWorld w) {
        return new BaritoneMask(BOX, false, true, null).leftover(w);
    }

    @Test
    void doneWhenNothingBreakableIsLeft() {
        String t = BuilderReport.text(LABEL, 48, left(empty()), End.IDLE, 0);
        assertEquals("done: " + LABEL + " - broke 48 blocks", t);
    }

    @Test
    void doneNamesWhatItLeftOnPurpose() {
        FakeWorld w = empty();
        w.set(0, 50, 0, "chest");
        w.set(1, 50, 0, "oak_planks");
        w.set(2, 50, 2, "iron_ore");
        String t = BuilderReport.text(LABEL, 1, left(w), End.IDLE, 0);
        assertTrue(t.startsWith("done: " + LABEL + " - broke 1 block (left 2 protected, e.g. 0 50 0, 1 ore left in place for you)"), t);
    }

    @Test
    void gaveUpListsTheUnreachable() {
        FakeWorld w = empty();
        w.set(2, 51, 1, "stone");
        w.set(3, 51, 1, "dirt");
        String t = BuilderReport.text(LABEL, 30, left(w), End.PAUSED, 0);
        assertTrue(t.startsWith("stopped: " + LABEL + " - gave up (Baritone found no way to reach the rest) - broke 30 blocks; 2 left (2 unreachable, e.g. 2 51 1)"), t);
        assertTrue(t.contains(" - next: dig the rest again from closer"), t);
        assertTrue(t.contains("2 51 1"));
    }

    @Test
    void stalledSaysSixtySeconds() {
        FakeWorld w = empty();
        w.set(2, 51, 1, "stone");
        String t = BuilderReport.text(LABEL, 0, left(w), End.STALLED, 0);
        assertTrue(t.contains("gave up (no block broken for 60 s)"), t);
        assertTrue(t.contains("broke 0 blocks; 1 left (1 unreachable"), t);
    }

    @Test
    void waterLeftIsBlockedByWater() {
        FakeWorld w = empty();
        w.set(1, 50, 1, "stone");
        w.set(1, 51, 1, "water");
        String t = BuilderReport.text(LABEL, 12, left(w), End.IDLE, 0);
        assertTrue(t.startsWith(ClearEngine.BLOCKED_BY + "water at 1 51 1 - broke 12 blocks; 1 left (1 next to water/lava, e.g. 1 50 1)"), t);
        assertTrue(ClearEngine.blockedByLiquid(t));
        assertTrue(t.endsWith("next: say the same dig with water to seal it off first"), t);
    }

    @Test
    void lavaSaysLava() {
        FakeWorld w = empty();
        w.set(1, 50, 1, "stone");
        w.set(2, 50, 1, "lava");
        assertTrue(BuilderReport.text(LABEL, 0, left(w), End.PAUSED, 0).startsWith("blocked by lava at 2 50 1"));
    }

    @Test
    void maskErrorsAreNamed() {
        String t = BuilderReport.text(LABEL, 5, left(empty()), End.IDLE, 3);
        assertTrue(t.startsWith("done: "), t);
        assertTrue(t.contains("the mask failed 3 times"), t);
    }

    @Test
    void doneNeverOnGaveUp() {
        String t = BuilderReport.text(LABEL, 48, left(empty()), End.PAUSED, 0);
        assertTrue(t.startsWith("stopped: "), t);
        assertTrue(t.contains("0 left"), t);
    }

    @Test
    void noToolUsesPlainDigsText() {
        assertEquals("stopped: " + LABEL + " - needs a pickaxe for stone (broke 54 blocks) - next: craft wooden_pickaxe 1",
                BuilderReport.noTool(LABEL, 54, "stone"));
        assertEquals("stopped: " + LABEL + " - needs a better pickaxe for obsidian (broke 1 block) - next: craft stone_pickaxe 1 (iron_pickaxe for diamond, gold, redstone)",
                BuilderReport.noTool(LABEL, 1, "obsidian"));
        assertTrue(DigWhy.pickaxeText("deepslate").startsWith("needs a pickaxe for deepslate"));
    }

    @Test
    void watch() {
        assertEquals(End.IDLE, BuilderReport.watch(false, false, 0));
        assertEquals(End.PAUSED, BuilderReport.watch(true, true, 0));
        assertEquals(End.STALLED, BuilderReport.watch(true, false, 1200));
        assertNull(BuilderReport.watch(true, false, 1199));
        assertTrue(BuilderReport.gaveUp(End.PAUSED));
        assertTrue(BuilderReport.gaveUp(End.STALLED));
        assertFalse(BuilderReport.gaveUp(End.IDLE));
    }
}
