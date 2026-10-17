package io.github.mojolowjo.entropybot.vocab;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.33.0: the world's own structures are not someone's base or a build; player builds still are. */
class NaturalStructuresTest {
    static NaturalStructures.Seen seen(Map<String, Integer> b, int villagers, int wet) { return new NaturalStructures.Seen(b, villagers, wet); }

    @Test
    void villageWithLootChestsBedsAndHay() {
        var s = seen(Map.of("oak_planks", 60, "oak_stairs", 20, "chest", 2, "white_bed", 3, "hay_block", 8, "glass_pane", 6, "torch", 4, "dirt_path", 30, "composter", 1), 3, 0);
        assertEquals("a village", NaturalStructures.explains(s));
        assertFalse(ExploreWords.isBase(new ExploreWords.Scan(90, 6, 0, NaturalStructures.explains(s))));
        // no villagers near (they wandered off): the bell or the paths with a job site still say village
        assertEquals("a village", NaturalStructures.explains(seen(Map.of("oak_planks", 40, "bell", 1, "chest", 1), 0, 0)));
    }

    @Test
    void aBaseInAVillageIsStillABase() {
        assertNull(NaturalStructures.explains(seen(Map.of("oak_planks", 60, "bell", 1, "oak_sign", 1), 2, 0)));
        assertNull(NaturalStructures.explains(seen(Map.of("oak_planks", 60, "white_shulker_box", 1), 2, 0)));
    }

    /** review 1: a player's base in or next to a village, a wooden dock and a surface build with fences stay builds. */
    @Test
    void playerBuildsNearOrInsideStructuresStayBuilds() {
        // storage room in a village: 6 chests is more than a village places
        assertNull(NaturalStructures.explains(seen(Map.of("oak_planks", 60, "bell", 1, "dirt_path", 20, "white_bed", 3, "chest", 6), 3, 0)));
        // a cobble/wood house with chests and a villager wandering by: no bell, no job site, no path, no bed = no village
        assertNull(NaturalStructures.explains(seen(Map.of("cobblestone", 40, "oak_planks", 30, "chest", 2, "glass", 6), 1, 0)));
        // a wood-and-glass house in a village: glass blocks, a crafting table, a furnace... more than the village's own
        assertNull(NaturalStructures.explains(seen(Map.of("oak_planks", 60, "bell", 1, "glass", 12, "crafting_table", 1, "furnace", 2, "stone_bricks", 10), 2, 0)));
        // a dock: planks with a few wet posts and a chest is no shipwreck
        assertNull(NaturalStructures.explains(seen(Map.of("oak_planks", 40, "oak_fence", 12, "chest", 1), 0, 6)));
        // fences and planks with 2 cobwebs on the surface (a player's farm shed) is no mineshaft
        assertNull(NaturalStructures.explains(new NaturalStructures.Seen(Map.of("oak_planks", 20, "oak_fence", 10, "rail", 4), 0, 0, 25)));
        // a mineshaft has no chest blocks (its chests ride minecarts): a chest there is a player's
        assertNull(NaturalStructures.explains(seen(Map.of("oak_planks", 12, "oak_fence", 8, "rail", 6, "chest", 1), 0, 0)));
    }

    @Test
    void mineshaft() {
        assertEquals("a mineshaft", NaturalStructures.explains(seen(Map.of("oak_planks", 12, "oak_fence", 8, "rail", 6, "cobweb", 5), 0, 0)));
        assertEquals("a mineshaft", NaturalStructures.explains(seen(Map.of("dark_oak_planks", 9, "dark_oak_fence", 4, "cobweb", 3, "torch", 6), 0, 0)));
        // a player's storage room by the shaft: glass, a furnace, a crafting table, stone bricks... too much left over
        assertNull(NaturalStructures.explains(seen(Map.of("oak_planks", 12, "oak_fence", 8, "rail", 6, "glass", 10, "furnace", 2, "stone_bricks", 20), 0, 0)));
    }

    @Test
    void otherStructures() {
        assertEquals("a ruined portal", NaturalStructures.explains(seen(Map.of("obsidian", 7, "crying_obsidian", 2, "netherrack", 5, "chest", 1, "stone_bricks", 4), 0, 0)));
        assertEquals("a dungeon", NaturalStructures.explains(seen(Map.of("spawner", 1, "chest", 2, "mossy_cobblestone", 20), 0, 0)));
        assertEquals("a shipwreck", NaturalStructures.explains(seen(Map.of("spruce_planks", 30, "spruce_stairs", 10, "chest", 2), 0, 25)));
        assertEquals("a dig site (trail ruins, desert temple, ocean ruins)",
                NaturalStructures.explains(seen(Map.of("suspicious_gravel", 2, "mud_bricks", 30, "red_terracotta", 6), 0, 0)));
        assertNotNull(NaturalStructures.explains(seen(Map.of("reinforced_deepslate", 20, "deepslate_bricks", 80, "sculk_shrieker", 2, "chest", 1), 0, 0)));
        assertTrue(NaturalStructures.names().size() >= 10);
    }

    @Test
    void playerBuildsStayBuilds() {
        // a plain house: no structure clue
        assertNull(NaturalStructures.explains(seen(Map.of("oak_planks", 40, "glass", 8, "chest", 2, "white_bed", 1, "crafting_table", 1), 0, 0)));
        assertTrue(ExploreWords.isBase(new ExploreWords.Scan(50, 3, 0, null)));
        // a nether portal the player built: obsidian alone is no ruined portal
        assertNull(NaturalStructures.explains(seen(Map.of("obsidian", 10, "oak_planks", 20), 0, 0)));
        // a named mob is always a base
        assertTrue(ExploreWords.isBase(new ExploreWords.Scan(50, 3, 1, "a village")));
        assertNull(NaturalStructures.explains(seen(Map.of(), 0, 0)));
        // live: a lone block entity in the wild (a bee nest) was "someone's base"
        assertTrue(NaturalStructures.wildBlockEntity("bee_nest"));
        assertFalse(NaturalStructures.wildBlockEntity("chest"));
    }
}
