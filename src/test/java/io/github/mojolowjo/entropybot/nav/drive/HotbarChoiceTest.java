package io.github.mojolowjo.entropybot.nav.drive;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Nav step 8: the hand's choice for a break and a place. */
class HotbarChoiceTest {
    static HotbarChoice.Slot s(int i, int n, float speed, boolean correct, boolean full) {
        return new HotbarChoice.Slot(i, n, speed, correct, full);
    }

    @Test
    void theFastestToolThatGetsTheDrops() {
        // stone: the hand gets nothing, a wooden pickaxe (2) and a stone pickaxe (4) do, a shovel is fast-ish but wrong
        List<HotbarChoice.Slot> bag = List.of(s(0, 0, 1f, false, false), s(2, 1, 2f, true, false), s(14, 1, 4f, true, false), s(3, 1, 1f, false, false));
        assertEquals(14, HotbarChoice.tool(bag, 0));
    }

    @Test
    void aCorrectToolBeatsAFasterWrongOne() {
        List<HotbarChoice.Slot> bag = List.of(s(0, 1, 6f, false, false), s(5, 1, 2f, true, false));
        assertEquals(5, HotbarChoice.tool(bag, 0));
    }

    @Test
    void theHandStaysWhenNothingIsBetter() {
        // dirt: everything gets the drops, nothing is faster than a hand but a shovel; no shovel
        List<HotbarChoice.Slot> bag = List.of(s(4, 0, 1f, true, false), s(1, 64, 1f, true, true), s(7, 1, 1f, true, false));
        assertEquals(-1, HotbarChoice.tool(bag, 4));
        assertEquals(-1, HotbarChoice.tool(List.of(s(2, 1, 4f, true, false)), 2), "already in the hand");
    }

    @Test
    void aHotbarToolWinsATieWithTheBag() {
        List<HotbarChoice.Slot> bag = List.of(s(0, 0, 1f, false, false), s(20, 1, 4f, true, false), s(6, 1, 4f, true, false));
        assertEquals(6, HotbarChoice.tool(bag, 0));
    }

    @Test
    void blocks() {
        assertEquals(3, HotbarChoice.block(List.of(s(3, 2, 1f, true, true), s(4, 64, 1f, true, true)), 3), "the selected block stays");
        assertEquals(30, HotbarChoice.block(List.of(s(0, 1, 1f, true, false), s(4, 10, 1f, true, true), s(30, 64, 1f, true, true)), 0), "the most items");
        assertEquals(4, HotbarChoice.block(List.of(s(4, 64, 1f, true, true), s(30, 64, 1f, true, true)), 0), "a tie: the hotbar");
        assertEquals(-1, HotbarChoice.block(List.of(s(0, 1, 1f, true, false)), 0), "no full block");
    }
}
