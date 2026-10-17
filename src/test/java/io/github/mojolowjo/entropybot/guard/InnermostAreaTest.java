package io.github.mojolowjo.entropybot.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.33.0: HERE names the innermost area (the house, not the camp around it). */
class InnermostAreaTest {
    static final String OW = "minecraft:overworld";
    static final Policy P = Policy.parse("{\"version\":2,\"areas\":["
            + "{\"name\":\"camp\",\"type\":\"neutral\",\"x1\":-40,\"z1\":-40,\"x2\":40,\"z2\":40},"
            + "{\"name\":\"house\",\"type\":\"main\",\"x1\":0,\"z1\":0,\"x2\":6,\"z2\":6,\"y1\":64,\"y2\":68}],\"protect\":[]}");

    @Test
    void houseOverCamp() {
        assertEquals("house", P.innermostAreaAt(OW, 3, 66, 3).name);
        assertEquals("camp", P.innermostAreaAt(OW, 3, 80, 3).name);
        assertEquals("camp", P.innermostAreaAt(OW, 20, 66, 3).name);
        assertNull(P.innermostAreaAt(OW, 99, 66, 3));
        assertNull(P.innermostAreaAt("minecraft:the_nether", 3, 66, 3));
    }
}
