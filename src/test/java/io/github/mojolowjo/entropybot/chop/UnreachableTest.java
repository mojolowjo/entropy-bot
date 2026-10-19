package io.github.mojolowjo.entropybot.chop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.36.3: an unreachable trunk is remembered 30 min per world, a neighbour cell counts as the same tree. */
class UnreachableTest {
    @Test
    void remembersPerWorldWithATimeout() {
        Unreachable.world("a");
        Unreachable.clear();
        Unreachable.mark(-33, -55, 1000);
        assertTrue(Unreachable.blocked(-33, -55, 2000));
        assertTrue(Unreachable.blocked(-32, -54, 2000), "the next cell is the same trunk");
        assertFalse(Unreachable.blocked(-30, -55, 2000));
        assertFalse(Unreachable.blocked(-33, -55, 1000 + Unreachable.TTL_MS + 1), "forgotten after 30 min");
        Unreachable.world("b");
        assertFalse(Unreachable.blocked(-33, -55, 2000), "another world");
    }
}
