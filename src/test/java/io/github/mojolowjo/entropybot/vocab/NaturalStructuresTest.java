package io.github.mojolowjo.entropybot.vocab;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The world's own structures come from the game's structure data (a fake source here), never from a block palette. */
class NaturalStructuresTest {
    @AfterEach
    void clear() { NaturalStructures.use(null); }

    @Test
    void noStructureDataSaysSoAndExplainsNothing() {
        assertFalse(NaturalStructures.known());
        assertNull(NaturalStructures.explains("minecraft:overworld", 0, 64, 0, 16));
    }

    @Test
    void theNamesOfTheStructuresAtTheSpotExplainIt() {
        NaturalStructures.use((dim, x, y, z, r) -> dim.equals("minecraft:overworld") && x == 10 && r == 16 ? List.of("village_plains", "mineshaft") : List.of());
        assertTrue(NaturalStructures.known());
        assertEquals("a village_plains and a mineshaft", NaturalStructures.explains("minecraft:overworld", 10, 64, 0, 16));
        assertNull(NaturalStructures.explains("minecraft:overworld", 99, 64, 0, 16), "none there");
        assertNull(NaturalStructures.explains("minecraft:the_nether", 10, 64, 0, 16));
    }

    @Test
    void aFailedReadReachesTheCaller() {
        NaturalStructures.use((dim, x, y, z, r) -> { throw new IllegalStateException("no server level " + dim); });
        assertThrows(IllegalStateException.class, () -> NaturalStructures.explains("minecraft:overworld", 0, 0, 0, 8));
    }
}