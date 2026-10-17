package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OwnStationsTest {
    @AfterEach
    void reset() { OwnStations.mine = null; }

    @Test
    void stations() {
        assertTrue(OwnStations.station("minecraft:crafting_table"));
        assertTrue(OwnStations.station("furnace"));
        assertTrue(OwnStations.station("minecraft:chest"));
        assertFalse(OwnStations.station("minecraft:stone"));
        assertFalse(OwnStations.station("othermod:furnace"));
        assertFalse(OwnStations.station(null));
    }

    @Test
    void mineHook() {
        assertFalse(OwnStations.mineAt(1, 2, 3));
        OwnStations.mine = (x, y, z) -> x == 1 && y == 2 && z == 3;
        assertTrue(OwnStations.mineAt(1, 2, 3));
        assertFalse(OwnStations.mineAt(1, 2, 4));
        OwnStations.mine = (x, y, z) -> { throw new IllegalStateException("x"); };
        assertFalse(OwnStations.mineAt(1, 2, 3));
    }
}
