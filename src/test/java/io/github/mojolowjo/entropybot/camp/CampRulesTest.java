package io.github.mojolowjo.entropybot.camp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** C4 + C5 (0.20.1): tool tiers, day/night triggers, the light grid, junk rules. */
@org.junit.jupiter.api.extension.ExtendWith(io.github.mojolowjo.entropybot.aa.brain.VanillaData.class)
class CampRulesTest {
    @Test
    void toolTiers() {
        assertEquals(0, io.github.mojolowjo.entropybot.aa.brain.MiningTiers.toolTier("minecraft:stone_pickaxe", "axe"));
        assertEquals(2, io.github.mojolowjo.entropybot.aa.brain.MiningTiers.toolTier("minecraft:stone_axe", "axe"));
        assertEquals(3, io.github.mojolowjo.entropybot.aa.brain.MiningTiers.toolTier("minecraft:iron_pickaxe", "pickaxe"));
        assertEquals(1, io.github.mojolowjo.entropybot.aa.brain.MiningTiers.toolTier("minecraft:wooden_sword", "sword"));
    }

    @Test
    void dayAndNight() {
        assertFalse(DayNight.night(1000));
        assertTrue(DayNight.night(13000));
        assertTrue(DayNight.night(24000 * 3 + 20000));
        assertFalse(DayNight.night(23500));
        assertFalse(DayNight.night(-1));
        assertEquals("n0", DayNight.key(13000));
        assertEquals("d1", DayNight.key(23600));       // dawn belongs to the next day
        assertEquals("d1", DayNight.key(25000));
        assertTrue(DayNight.due("night", 13000, ""));
        assertFalse(DayNight.due("night", 14000, "n0"), "once per night");
        assertTrue(DayNight.due("night", 24000 + 13000, "n0"));
        assertFalse(DayNight.due("day", 13000, ""));
        assertTrue(DayNight.due("day", 24000 + 100, "d0"));
        assertFalse(DayNight.due("day", 24000 + 100, "d1"));
        assertFalse(DayNight.due("night", -1, ""));
        assertEquals("06:00", DayNight.clock(0));
        assertEquals("18:00", DayNight.clock(12000));
        assertEquals("00:30", DayNight.clock(18500));
    }

    @Test
    void lightGrid() {
        int[] b = LightGrid.parse("here 8", new int[]{100, 200});
        assertArrayEquals(new int[]{92, 192, 108, 208}, b);
        List<int[]> s = LightGrid.spots(b[0], b[1], b[2], b[3]);
        assertEquals(9, s.size());                     // 16 wide: 3 x 3 spots 6 apart, margin 2
        assertArrayEquals(new int[]{94, 194}, s.get(0));
        assertArrayEquals(new int[]{106, 206}, s.get(8));
        assertArrayEquals(new int[]{0, 0, 30, 12}, LightGrid.parse("30 12 0 0", new int[]{0, 0}));
        assertNull(LightGrid.parse("0 0 500 500", new int[]{0, 0}));
        assertNull(LightGrid.parse("nonsense", new int[]{0, 0}));
        assertArrayEquals(new int[]{-32, -32, 32, 32}, LightGrid.parse("here 99", new int[]{0, 0}));
        assertEquals("placed 14 torches, 3 spots skipped (water/air)", LightGrid.report(14, 3, "water/air"));
        assertEquals("placed 1 torch", LightGrid.report(1, 0, ""));
    }

    @Test
    void junkRules() {
        Set<String> junk = new LinkedHashSet<>(List.of("minecraft:dirt", "minecraft:cobblestone", "minecraft:gravel"));
        assertFalse(junk.contains("minecraft:string"), "string is kept");
        List<String> refused = new ArrayList<>();
        Set<String> j2 = JunkRules.add(junk, List.of("sand", "diamond_pickaxe", "iron_ore", "torch"), refused);
        assertTrue(j2.contains("minecraft:sand"));
        assertEquals(List.of("diamond_pickaxe", "iron_ore", "torch"), refused);
        assertFalse(JunkRules.remove(j2, List.of("dirt")).contains("minecraft:dirt"));
        assertTrue(JunkRules.listText(junk, "drop").contains("dirt"));
    }

}
