package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** job-refusal-fact: worlds run 3, Tester 2 w072 replayed (8,115 refused breaks on rails, two cells). */
class RefusedBreaksTest {
    static final String OW = "minecraft:overworld", RAIL = "minecraft:rail", WHY = "structure block outside my areas";

    @Test
    void w072SaidOncePerCellCountedAndHeld() {
        RefusedBreaks r = new RefusedBreaks();
        int said = 0;
        for (int i = 0; i < 2303; i++) if (r.note(OW, -29, -33, -143, RAIL, WHY)) said++;
        for (int i = 0; i < 5804; i++) if (r.note(OW, -25, -32, -123, RAIL, WHY)) said++;
        assertEquals(2, said, "one line per cell, not 8,115");
        assertTrue(r.refused(OW, -25, -32, -123, RAIL), "the feet cell is held for Baritone's cost");
        assertTrue(r.refused(OW, -25, -32, -123, null), "not read: the refusal is the last thing known");
        assertFalse(r.refused(OW, -25, -31, -123, RAIL));
        assertFalse(r.refused("minecraft:the_nether", -25, -32, -123, RAIL));
        String t = r.take();
        assertTrue(t.contains("minecraft:rail at -25 -32 -123 5804 times"), t);
        assertTrue(t.contains("at -29 -33 -143 2303 times"), t);
        assertNull(r.take(), "the counts are said once");
        r.note(OW, -25, -32, -123, RAIL, WHY);
        assertTrue(r.take().contains("-25 -32 -123 1 time ("));
    }

    @Test
    void aChangedBlockLiftsTheFact() {
        RefusedBreaks r = new RefusedBreaks();
        assertTrue(r.note(OW, 1, 2, 3, "minecraft:oak_door", WHY));
        assertFalse(r.refused(OW, 1, 2, 3, "minecraft:air"));
        assertFalse(r.refused(OW, 1, 2, 3, "minecraft:oak_door"), "lifted");
        assertTrue(r.note(OW, 1, 2, 3, "minecraft:oak_door", WHY), "learned again, said again");
    }

    @Test
    void theStepEndFactCarriesTheRefusal() {
        FactChange.Fact f = new FactChange.Fact("climb up", "no progress 30 s at climb");
        FactChange.Fact g = Steps.withRefused(f, "the guard refused the break of minecraft:rail at -25 -32 -123 5804 times");
        assertEquals("climb up", g.key());
        assertTrue(g.text().endsWith("5804 times"));
        assertSame(g, Steps.withRefused(g, "the guard refused the break of minecraft:rail at -25 -32 -123 5804 times"));
        assertNull(Steps.withRefused(null, "x"));
    }
}
