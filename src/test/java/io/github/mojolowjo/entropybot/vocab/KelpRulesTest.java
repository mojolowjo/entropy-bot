package io.github.mojolowjo.entropybot.vocab;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KelpRulesTest {
    @Test
    void parsesTheForms() {
        assertEquals(new KelpRules.Parsed(8, 10, false), KelpRules.parse("kelp"));
        assertEquals(new KelpRules.Parsed(5, 10, false), KelpRules.parse("kelp 5"));
        assertEquals(new KelpRules.Parsed(5, 3, true), KelpRules.parse("KELP 5 3m cook"));
        assertEquals(new KelpRules.Parsed(8, 10, true), KelpRules.parse("kelp cook"));
        assertNull(KelpRules.parse("kelp 0"));
        assertNull(KelpRules.parse("kelp 5 99m"));
        assertNull(KelpRules.parse("kelp cook 5"));
        assertNull(KelpRules.parse("cobblestone 5"));
        assertTrue(KelpRules.isKelp("kelp 3"));
        assertFalse(KelpRules.isKelp("kelp_block 3"));
        assertFalse(KelpRules.isKelp(""));
    }

    @Test
    void countsKelpAndDried() {
        assertEquals(7, KelpRules.have(Map.of("minecraft:kelp", 4, "minecraft:dried_kelp", 3)));
        assertTrue(KelpRules.done(Map.of("minecraft:kelp", 8), 8));
        assertFalse(KelpRules.done(Map.of("minecraft:kelp", 7), 8));
    }

    @Test
    void columnsLeaveTheBaseAndCutOneAboveIt() {
        List<int[]> blocks = new ArrayList<>();
        for (int y = 40; y <= 45; y++) blocks.add(new int[]{10, y, 20});     // 6 tall
        blocks.add(new int[]{12, 41, 20});                                   // a lone sprout
        List<KelpRules.Column> cols = KelpRules.columns(blocks);
        assertEquals(2, cols.size());
        KelpRules.Column tall = cols.stream().filter(c -> c.x() == 10).findFirst().orElseThrow();
        assertEquals(5, tall.yield());
        assertArrayEquals(new int[]{10, 41, 20}, tall.cut());
        assertEquals(0, cols.stream().filter(c -> c.x() == 12).findFirst().orElseThrow().yield());
        assertEquals("dig 10 41 20 10 41 20 water", KelpRules.digLine(tall));
    }

    @Test
    void picksATallEnoughColumnInsideTheAreas() {
        List<KelpRules.Column> cols = List.of(new KelpRules.Column(1, 1, 40, 40), new KelpRules.Column(5, 0, 40, 43), new KelpRules.Column(-3, 0, 40, 41),
                new KelpRules.Column(60, 0, 40, 50));
        KelpRules.Column c = KelpRules.pick(cols, 0, 42, 0, cell -> true);
        assertEquals(5, c.x(), "the taller one beats a slightly nearer short one");
        assertEquals(-3, KelpRules.pick(cols, 0, 42, 0, cell -> cell[0] < 0).x(), "only the allowed cell");
        assertNull(KelpRules.pick(cols, 0, 42, 0, cell -> false));
        assertNull(KelpRules.pick(List.of(new KelpRules.Column(1, 1, 40, 40)), 0, 42, 0, null), "a single block yields nothing");
        assertNull(KelpRules.pick(List.of(new KelpRules.Column(60, 0, 40, 50)), 0, 42, 0, null), "out of range");
    }

    @Test
    void breathBeforeADive() {
        assertTrue(KelpRules.mayDive(false, 0, 300));
        assertTrue(KelpRules.mayDive(true, 300, 300));
        assertTrue(KelpRules.mayDive(true, 200, 300));
        assertFalse(KelpRules.mayDive(true, 199, 300));
    }
}