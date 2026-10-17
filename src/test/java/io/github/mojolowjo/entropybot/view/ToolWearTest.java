package io.github.mojolowjo.entropybot.view;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.33.0: tool breaks and nearly-broken tools reach NEW. */
class ToolWearTest {
    static ToolWear.Worn pick(int left) { return new ToolWear.Worn("iron_pickaxe", left, 250); }

    @Test
    void lowOnceThenBroke() {
        ToolWear w = new ToolWear();
        assertEquals(List.of(), w.update(Map.of(0, pick(100))));
        assertEquals(List.of("iron pickaxe about to break (10 uses left)"), w.update(Map.of(0, pick(10))));
        assertEquals(List.of(), w.update(Map.of(0, pick(9))));
        assertEquals(List.of(), w.update(Map.of(0, pick(1))));
        assertEquals(List.of("iron pickaxe broke"), w.update(Map.of()));
    }

    @Test
    void movedOrPutAwayHealthyIsNotABreak() {
        ToolWear w = new ToolWear();
        w.update(Map.of(0, pick(2)));
        assertEquals(List.of(), w.update(Map.of(5, pick(2))));   // moved to another slot
        ToolWear h = new ToolWear();
        h.update(Map.of(0, pick(200)));
        assertEquals(List.of(), h.update(Map.of()));            // a healthy tool dropped or stored
    }

    @Test
    void repairedWarnsAgainLater() {
        ToolWear w = new ToolWear();
        w.update(Map.of(0, pick(5)));
        w.update(Map.of(0, pick(200)));
        assertEquals(1, w.update(Map.of(0, pick(5))).size());
    }
}
