package io.github.mojolowjo.entropybot.scout;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ScoutRulesTest {

    @Test
    void parsesDirectionsPointsAndOptions() {
        ScoutRules.Parsed p = ScoutRules.parse("north");
        assertEquals(ScoutRules.Kind.RUN, p.kind());
        assertEquals(0, p.dx());
        assertEquals(-1, p.dz());
        assertEquals(64, p.blocks());
        assertEquals(5, p.minutes());
        p = ScoutRules.parse("east 500 10m from me");
        assertEquals(1, p.dx());
        assertEquals(256, p.blocks());
        assertEquals(10, p.minutes());
        assertTrue(p.fromMe());
        p = ScoutRules.parse("120 -40");
        assertTrue(p.point());
        assertEquals(120, p.dx());
        assertEquals(-40, p.dz());
        assertEquals(ScoutRules.Kind.STATUS, ScoutRules.parse("status").kind());
        assertEquals(ScoutRules.Kind.ERROR, ScoutRules.parse("up").kind());
        assertEquals(ScoutRules.Kind.ERROR, ScoutRules.parse("north lots").kind());
        assertEquals(ScoutRules.Kind.ERROR, ScoutRules.parse("").kind());
    }

    @Test
    void compassWords() {
        assertEquals("north", ScoutRules.compass(0, -10));
        assertEquals("south", ScoutRules.compass(0, 10));
        assertEquals("east", ScoutRules.compass(10, 0));
        assertEquals("west", ScoutRules.compass(-10, 0));
        assertEquals("north-east", ScoutRules.compass(10, -10));
        assertEquals("south-west", ScoutRules.compass(-7, 8));
        assertEquals("here", ScoutRules.compass(0, 0));
    }

    @Test
    void legsStopAtTheAreaEdge() {
        ScoutRules.Plan plan = ScoutRules.legs(0, 0, ScoutRules.parse("north 100"), (x, z) -> true);
        assertEquals(4, plan.legs().size());
        assertArrayEquals(new int[]{0, -32}, plan.legs().get(0));
        assertArrayEquals(new int[]{0, -100}, plan.legs().get(3));
        assertNull(plan.cutAt());
        plan = ScoutRules.legs(0, 0, ScoutRules.parse("north 100"), (x, z) -> z > -70);
        assertEquals(2, plan.legs().size());
        assertArrayEquals(new int[]{0, -96}, plan.cutAt());
        plan = ScoutRules.legs(0, 0, ScoutRules.parse("30 40"), (x, z) -> true);   // 50 to the point
        assertEquals(2, plan.legs().size());
        assertArrayEquals(new int[]{30, 40}, plan.legs().get(1));
        assertEquals("toward 30 40", plan.dirText());
        assertTrue(ScoutRules.legs(0, 0, ScoutRules.parse("west"), (x, z) -> false).legs().isEmpty());
    }

    @Test
    void reportNamesWhatWasPassed() {
        ScoutRules.Plan plan = ScoutRules.legs(0, 0, ScoutRules.parse("north 64"), (x, z) -> true);
        List<ScoutRules.Spot> pois = List.of(new ScoutRules.Spot("cave", 3, 40, -48, 7),
                new ScoutRules.Spot("village", 200, 70, 200, 8));          // far off the path: left out
        List<ScoutRules.Spot> ores = List.of(new ScoutRules.Spot("iron_ore", 2, 30, -20, -1),
                new ScoutRules.Spot("iron_ore", 3, 31, -21, -1), new ScoutRules.Spot("iron_ore", 4, 31, -22, -1));
        Map<String, Integer> mobs = new TreeMap<>(Map.of("zombie", 2));
        String r = ScoutRules.report(plan, new int[]{0, 64, 0}, new int[]{0, -64}, pois, ores, mobs, null, null);
        assertEquals("scouted 64 north: cave at 3 40 -48 (48 blocks, poi 7), 3 iron_ore near 2 30 -20, 2 zombie", r);
        r = ScoutRules.report(plan, new int[]{0, 64, 0}, new int[]{0, -30}, List.of(), List.of(), Map.of(), "0 -32 (water)", null);
        assertEquals("scouted 30 north: nothing to note; no path past 0 -32 (water)", r);
    }

    @Test
    void nearestFindsPoiOrOre() {
        List<ScoutRules.Spot> pois = List.of(new ScoutRules.Spot("spawner", 30, 20, -30, 14));
        List<ScoutRules.Spot> ores = List.of(new ScoutRules.Spot("iron_ore", -10, 64, 0, -1), new ScoutRules.Spot("iron_ore", -100, 64, 0, -1));
        assertEquals("nearest iron: iron_ore at -10 64 0, 10 blocks west", ScoutRules.nearest("iron", pois, ores, new int[]{0, 64, 0}));
        assertTrue(ScoutRules.nearest("spawner", pois, ores, new int[]{0, 20, 0}).contains("(poi 14)"));
        assertTrue(ScoutRules.nearest("spawner", pois, ores, new int[]{0, 20, 0}).endsWith("north-east"));
        assertTrue(ScoutRules.nearest("village", pois, ores, new int[]{0, 0, 0}).startsWith("I know no village"));
    }
}
