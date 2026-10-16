package io.github.mojolowjo.entropybot.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.30.6: a house in a main area is never a dig-out's way (Box.onShell, Policy.houseCell). */
class HouseShellTest {
    static final String OW = "minecraft:overworld";
    static final Policy P = Policy.parse("{\"version\":2,\"areas\":["
            + "{\"name\":\"house\",\"type\":\"main\",\"x1\":0,\"z1\":0,\"x2\":6,\"z2\":6,\"y1\":64,\"y2\":68},"
            + "{\"name\":\"tall\",\"type\":\"main\",\"x1\":20,\"z1\":0,\"x2\":26,\"z2\":6},"
            + "{\"name\":\"pit\",\"type\":\"neutral\",\"x1\":40,\"z1\":0,\"x2\":46,\"z2\":6,\"y1\":64,\"y2\":68}],\"protect\":[]}");

    @Test
    void shellIsTheBoxFaces() {
        Box b = new Box("house", OW, 0, 64, 0, 6, 68, 6);
        assertTrue(b.onShell(OW, 0, 66, 3));      // west wall
        assertTrue(b.onShell(OW, 6, 66, 3));      // east wall
        assertTrue(b.onShell(OW, 3, 66, 6));      // south wall
        assertTrue(b.onShell(OW, 3, 64, 3));      // floor
        assertTrue(b.onShell(OW, 3, 68, 3));      // roof
        assertFalse(b.onShell(OW, 3, 66, 3));     // the room
        assertFalse(b.onShell(OW, 7, 66, 3));     // outside
        assertFalse(b.onShell(OW, 3, 63, 3));     // under the floor
        assertFalse(b.onShell("minecraft:the_nether", 0, 66, 3));
    }

    @Test
    void heightlessBoxHasNoShell() {
        Box b = Box.fromJson(com.google.gson.JsonParser.parseString("{\"x1\":20,\"z1\":0,\"x2\":26,\"z2\":6}").getAsJsonObject(), OW);
        assertFalse(b.onShell(OW, 20, 10, 3));
        assertFalse(b.onShell(OW, 23, 10, 3));
        assertFalse(b.onShell(OW, 23, -64, 3));
    }

    @Test
    void roundBoxHasNoShell() {
        assertFalse(Box.round("near", OW, 0, 0, 5, 60, 70).onShell(OW, 5, 65, 0));
    }

    @Test
    void houseCellOnlyInMainAreas() {
        assertTrue(P.houseCell(OW, 0, 66, 3, false));        // a wall of the house
        assertFalse(P.houseCell(OW, 3, 66, 3, false));       // the room, natural block
        assertTrue(P.houseCell(OW, 3, 66, 3, true));         // the room, my own block (a station)
        assertTrue(P.houseCell(OW, 3, 60, 3, true));         // a pillar under the floor (my own, in the columns)
        assertFalse(P.houseCell(OW, 3, 60, 3, false));       // natural stone under the house
        assertFalse(P.houseCell(OW, 20, 100, 3, false));     // a heightless main area's edge: terrain, no shell
        assertTrue(P.houseCell(OW, 20, 100, 3, true));       // ... but my own block there is the house
        assertFalse(P.houseCell(OW, 40, 66, 3, false));      // a neutral area's edge
        assertFalse(P.houseCell(OW, 40, 66, 3, true));       // my own block in a neutral area (a shelter): still mine to open
        assertFalse(P.houseCell(OW, 99, 66, 99, true));      // outside every area
    }
}
