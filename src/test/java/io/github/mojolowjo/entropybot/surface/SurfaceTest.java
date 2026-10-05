package io.github.mojolowjo.entropybot.surface;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class SurfaceTest {
    private static final Predicate<String> NO_TAGS = t -> false;

    private static int fam(String id) { return SurfaceFamily.of(id, NO_TAGS); }

    @Test
    void familiesByIdAndOrder() {
        assertEquals(29, SurfaceFamily.FAMILIES.size());
        assertEquals("crop", SurfaceFamily.FAMILIES.get(SurfaceFamily.CROP));
        assertEquals("concrete", SurfaceFamily.FAMILIES.get(SurfaceFamily.CONCRETE));
        Map<String, Integer> want = Map.ofEntries(
                Map.entry("minecraft:grass_block", SurfaceFamily.GRASS), Map.entry("minecraft:dirt", SurfaceFamily.DIRT),
                Map.entry("minecraft:coarse_dirt", SurfaceFamily.DIRT), Map.entry("minecraft:stone", SurfaceFamily.STONE),
                Map.entry("minecraft:andesite", SurfaceFamily.STONE), Map.entry("minecraft:deepslate", SurfaceFamily.DEEPSLATE),
                Map.entry("minecraft:deepslate_iron_ore", SurfaceFamily.ORE), Map.entry("minecraft:iron_ore", SurfaceFamily.ORE),
                Map.entry("oritech:nickel_ore", SurfaceFamily.ORE), Map.entry("minecraft:sand", SurfaceFamily.SAND),
                Map.entry("minecraft:red_sand", SurfaceFamily.SAND), Map.entry("minecraft:gravel", SurfaceFamily.GRAVEL),
                Map.entry("minecraft:water", SurfaceFamily.WATER), Map.entry("minecraft:seagrass", SurfaceFamily.WATER),
                Map.entry("minecraft:lava", SurfaceFamily.LAVA), Map.entry("minecraft:snow_block", SurfaceFamily.SNOW),
                Map.entry("minecraft:packed_ice", SurfaceFamily.ICE), Map.entry("minecraft:oak_log", SurfaceFamily.LOG),
                Map.entry("minecraft:crimson_stem", SurfaceFamily.LOG), Map.entry("minecraft:birch_leaves", SurfaceFamily.LEAVES),
                Map.entry("minecraft:spruce_planks", SurfaceFamily.PLANKS), Map.entry("minecraft:clay", SurfaceFamily.CLAY),
                Map.entry("minecraft:mud", SurfaceFamily.MUD), Map.entry("minecraft:dirt_path", SurfaceFamily.PATH),
                Map.entry("minecraft:farmland", SurfaceFamily.FARMLAND), Map.entry("minecraft:bedrock", SurfaceFamily.BEDROCK),
                Map.entry("minecraft:netherrack", SurfaceFamily.NETHERRACK), Map.entry("minecraft:end_stone", SurfaceFamily.END_STONE),
                Map.entry("minecraft:glass", SurfaceFamily.GLASS), Map.entry("minecraft:white_stained_glass", SurfaceFamily.GLASS),
                Map.entry("minecraft:red_wool", SurfaceFamily.WOOL), Map.entry("minecraft:orange_terracotta", SurfaceFamily.TERRACOTTA),
                Map.entry("minecraft:blue_concrete", SurfaceFamily.CONCRETE), Map.entry("minecraft:blue_concrete_powder", SurfaceFamily.CONCRETE),
                Map.entry("minecraft:moss_block", SurfaceFamily.MOSS), Map.entry("minecraft:red_mushroom_block", SurfaceFamily.MUSHROOM),
                Map.entry("minecraft:mushroom_stem", SurfaceFamily.MUSHROOM), Map.entry("minecraft:wheat", SurfaceFamily.CROP),
                Map.entry("minecraft:pumpkin_stem", SurfaceFamily.CROP), Map.entry("minecraft:furnace", SurfaceFamily.OTHER),
                Map.entry("stone", SurfaceFamily.STONE));
        want.forEach((id, f) -> assertEquals(SurfaceFamily.FAMILIES.get(f), SurfaceFamily.FAMILIES.get(fam(id)), id));
    }

    @Test
    void familiesByTag() {
        assertEquals(SurfaceFamily.LOG, SurfaceFamily.of("mod:weird_trunk", t -> t.equals("logs")));
        assertEquals(SurfaceFamily.CROP, SurfaceFamily.of("mysticalagriculture:inferium_crop", t -> t.equals("crops")));
        assertEquals(SurfaceFamily.STONE, SurfaceFamily.of("mod:slate", t -> t.equals("base_stone_overworld")));
        assertEquals(SurfaceFamily.DIRT, SurfaceFamily.of("mod:loam", t -> t.equals("dirt")));
        assertEquals(SurfaceFamily.OTHER, SurfaceFamily.of("mod:x", t -> { throw new IllegalStateException(); }));
        assertEquals(SurfaceFamily.OTHER, SurfaceFamily.of(null, null));
    }

    @Test
    void indexLayout() {
        assertEquals(0, SurfaceColumns.index(0, 0));
        assertEquals(15, SurfaceColumns.index(15, 0));
        assertEquals(16, SurfaceColumns.index(0, 1));
        assertEquals(255, SurfaceColumns.index(-1, -1));
        assertEquals(17, SurfaceColumns.index(-31, 17));
        assertEquals("-2.5.json", SurfaceColumns.fileName(-2, 5));
        assertEquals("minecraft_overworld", SurfaceColumns.dimFolder("minecraft:overworld"));
        assertEquals("mod_a_b", SurfaceColumns.dimFolder("mod:a/b"));
    }

    /** A fake world: ground at y 64 stone everywhere, with a few special columns (local coords in chunk -2 5). */
    private static final class Fake implements SurfaceColumns.Source {
        final int ox = -32, oz = 80;

        int lx(int x) { return x - ox; }

        int lz(int z) { return z - oz; }

        @Override
        public int kind(int x, int y, int z) {
            int a = lx(x), b = lz(z);
            if (a == 15 && b == 15) throw new IllegalStateException("boom");
            if (a == 9 && b == 9) return SurfaceColumns.AIR;                        // void column: unknown
            if (a == 1 && b == 0) {                                                // tree: leaves 70..72, log 65..69
                if (y >= 70 && y <= 72) return SurfaceColumns.LEAVES;
                if (y >= 65 && y <= 69) return SurfaceColumns.GROUND;
            }
            if (a == 2 && b == 0) {                                                // under the canopy: leaves 71, grass plant 65
                if (y == 71) return SurfaceColumns.LEAVES;
                if (y == 65) return SurfaceColumns.PLANT;
            }
            if (a == 3 && b == 0 && y == 65) return SurfaceColumns.PLANT;          // snow layer
            if (a == 4 && b == 0 && y >= 62 && y <= 63) return SurfaceColumns.GROUND; // (handled by top: water surface 63)
            return y <= 64 ? SurfaceColumns.GROUND : SurfaceColumns.AIR;
        }

        @Override
        public int family(int x, int y, int z) {
            int a = lx(x), b = lz(z);
            if (a == 1 && b == 0 && y >= 65) return SurfaceFamily.LOG;
            if (a == 4 && b == 0) return SurfaceFamily.WATER;
            return SurfaceFamily.STONE;
        }

        @Override
        public int top(int x, int z) { return 80; }
    }

    @Test
    void scanColumns() {
        int[] errors = {0};
        SurfaceColumns s = SurfaceColumns.scan(new Fake(), -2, 5, -64, (x, z, t) -> errors[0]++);
        assertEquals(64, s.g[0]);
        assertEquals(SurfaceFamily.STONE, s.f[0]);
        assertEquals(-1, s.c[0]);
        assertEquals(0, s.l[0]);
        assertEquals(69, s.g[1], "trunk top is the ground");
        assertEquals(1, s.l[1]);
        assertEquals(72, s.c[1], "highest leaves");
        assertEquals(64, s.g[2], "ground under the leaves, grass skipped");
        assertEquals(71, s.c[2]);
        assertEquals(64, s.g[3], "snow layer skipped");
        assertEquals(64, s.g[4]);
        assertEquals(SurfaceFamily.WATER, s.f[4]);
        assertEquals(-1, s.g[9 * 16 + 9], "unknown");
        assertEquals(-1, s.g[255], "a throwing column is unknown");
        assertEquals(1, errors[0]);
    }

    @Test
    void waterSurface() {
        SurfaceColumns.Source sea = new SurfaceColumns.Source() {
            public int kind(int x, int y, int z) { return y <= 62 ? SurfaceColumns.GROUND : SurfaceColumns.AIR; }

            public int family(int x, int y, int z) { return y >= 50 ? SurfaceFamily.WATER : SurfaceFamily.SAND; }

            public int top(int x, int z) { return 70; }
        };
        SurfaceColumns s = SurfaceColumns.scan(sea, 0, 0, -64, null);
        assertEquals(62, s.g[7]);
        assertEquals(SurfaceFamily.WATER, s.f[7]);
    }

    @Test
    void json() {
        SurfaceColumns s = SurfaceColumns.scan(new Fake(), -2, 5, -64, null);
        JsonObject o = JsonParser.parseString(s.toJson("minecraft:overworld", -2, 5, 1790000000000L)).getAsJsonObject();
        assertEquals(1, o.get("v").getAsInt());
        assertEquals("minecraft:overworld", o.get("dim").getAsString());
        assertEquals(-2, o.get("cx").getAsInt());
        assertEquals(5, o.get("cz").getAsInt());
        assertEquals(1790000000000L, o.get("t").getAsLong());
        for (String k : List.of("g", "f", "c", "l")) assertEquals(256, o.getAsJsonArray(k).size(), k);
        assertEquals(72, o.getAsJsonArray("c").get(1).getAsInt());
        assertEquals(1, o.getAsJsonArray("l").get(1).getAsInt());
    }

    @Test
    void scheduleNearestFirstTwoPerTick() {
        SurfaceSchedule q = new SurfaceSchedule();
        q.loaded(5, 5, 0);
        q.loaded(1, 0, 0);
        q.loaded(0, 0, 0);
        q.loaded(-3, 0, 0);
        List<long[]> a = q.take(0, 0, 0, SurfaceSchedule.PER_TICK);
        assertEquals(2, a.size());
        assertArrayEquals(new long[]{0, 0}, a.get(0));
        assertArrayEquals(new long[]{1, 0}, a.get(1));
        List<long[]> b = q.take(0, 0, 0, 2);
        assertArrayEquals(new long[]{-3, 0}, b.get(0));
        assertArrayEquals(new long[]{5, 5}, b.get(1));
        assertEquals(0, q.pending());
    }

    @Test
    void scheduleDebounce() {
        SurfaceSchedule q = new SurfaceSchedule();
        q.loaded(0, 0, 1000);
        assertEquals(1, q.take(0, 0, 1000, 2).size());
        q.changed(0, 0, 1500);
        assertTrue(q.take(0, 0, 2999, 2).isEmpty(), "within 2 s of the last write");
        q.changed(0, 0, 2500);
        assertEquals(1, q.pending());
        assertEquals(1, q.take(0, 0, 3000, 2).size());
        q.changed(7, 7, 4000);                            // never written: at once
        assertEquals(1, q.take(0, 0, 4000, 2).size());
    }

    @Test
    void scheduleUnloadCancels() {
        SurfaceSchedule q = new SurfaceSchedule();
        q.loaded(2, 2, 0);
        q.loaded(3, 3, 0);
        q.unloaded(2, 2);
        assertFalse(q.isPending(2, 2));
        List<long[]> a = q.take(0, 0, 0, 2);
        assertEquals(1, a.size());
        assertArrayEquals(new long[]{3, 3}, a.get(0));
        q.clear();
        assertEquals(0, q.pending());
    }

    @Test
    void settingsAndQuiet() {
        assertEquals(Boolean.FALSE, SurfaceSchedule.parseOn("{\"on\":false}"));
        assertNull(SurfaceSchedule.parseOn("{bad"));
        assertNull(SurfaceSchedule.parseOn(null));
        assertFalse(SurfaceSchedule.quiet(true, true, 30_000, -1, 3));
        assertTrue(SurfaceSchedule.quiet(true, true, 61_000, -1, 0));
        assertTrue(SurfaceSchedule.quiet(true, true, 600_000, 70_000, 2));
        assertFalse(SurfaceSchedule.quiet(true, true, 600_000, 70_000, 0));
        assertFalse(SurfaceSchedule.quiet(false, true, 600_000, -1, 2));
    }
}
