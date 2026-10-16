package io.github.mojolowjo.entropybot.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.30.7 (package 8 item 3): the owner's rule: in its own neutral/destroy areas a dig-out may break any block. */
class DigOutAnyTest {
    static final String OW = "minecraft:overworld";
    static final Policy P = Policy.parse("{\"version\":2,\"areas\":["
            + "{\"name\":\"house\",\"type\":\"main\",\"x1\":0,\"z1\":0,\"x2\":6,\"z2\":6,\"y1\":64,\"y2\":68},"
            + "{\"name\":\"arena\",\"type\":\"neutral\",\"x1\":-20,\"z1\":-20,\"x2\":30,\"z2\":30},"
            + "{\"name\":\"pit\",\"type\":\"destroy\",\"x1\":40,\"z1\":0,\"x2\":46,\"z2\":6,\"y1\":60,\"y2\":70},"
            + "{\"name\":\"keep\",\"type\":\"safe\",\"x1\":10,\"z1\":10,\"x2\":12,\"z2\":12}],\"protect\":[]}");

    @Test void neutralAndDestroyAreasAllowAnyBlock() {
        assertTrue(P.digOutAny(OW, -5, 136, 9, false), "the respawn pit in the neutral arena");
        assertTrue(P.digOutAny(OW, -5, 136, 9, true), "even a block the bot placed itself outside any house");
        assertTrue(P.digOutAny(OW, 43, 65, 3, false), "a destroy area");
    }

    @Test void neverOutsideSafeOrHouse() {
        assertFalse(P.digOutAny(OW, 100, 64, 100, false), "outside every area");
        assertFalse(P.digOutAny(OW, 11, 64, 11, false), "a safe area inside the neutral one");
        assertFalse(P.digOutAny(OW, 0, 66, 3, false), "a house wall (the main area's shell; the arena covers it too)");
        assertFalse(P.digOutAny(OW, 3, 70, 3, true), "my own block in a main area's columns");
        assertFalse(P.digOutAny(OW, 43, 75, 3, false), "above the destroy area's height");
        assertFalse(P.digOutAny("minecraft:the_nether", -5, 136, 9, false), "another dimension");
    }

    @Test void aMainAreaAloneAllowsNothingExtra() {
        Policy main = Policy.parse("{\"version\":2,\"areas\":[{\"name\":\"house\",\"type\":\"main\",\"x1\":0,\"z1\":0,\"x2\":6,\"z2\":6,\"y1\":64,\"y2\":68}],\"protect\":[]}");
        assertFalse(main.digOutAny(OW, 3, 66, 3, false), "the room of a main area: not neutral/destroy");
    }
}
