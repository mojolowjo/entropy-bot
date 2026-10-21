package io.github.mojolowjo.entropybot.surface;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 0.19.3: the ground family of a block for the dashboard's RTS view (pure, JUnit on id strings). The family list's order
 * is part of the surface file format ({@code entropybot\surface\...}): index = position, never reorder, only append.
 * Tags come through a predicate on the tag's path ({@code "logs"}, {@code "leaves"}, {@code "sand"}, {@code "dirt"},
 * {@code "base_stone_overworld"}, {@code "wool"}, {@code "planks"}, {@code "crops"}: vanilla {@code BlockTags}), so this
 * class needs no loader. Loader notes: none (plain Java); the game side ({@link SurfaceExport}) asks
 * {@code BlockState.is(TagKey)} (vanilla, the same on NeoForge and Fabric).
 */
public final class SurfaceFamily {
    public static final List<String> FAMILIES = List.of("other", "grass", "dirt", "stone", "deepslate", "sand", "gravel", "water", "lava", "snow",
            "ice", "log", "leaves", "planks", "ore", "clay", "mud", "path", "farmland", "bedrock", "netherrack", "end_stone", "glass", "wool",
            "terracotta", "concrete", "moss", "mushroom", "crop");

    public static final int OTHER = 0; // structural
    public static final int GRASS = 1; // structural
    public static final int DIRT = 2; // structural
    public static final int STONE = 3; // structural
    public static final int DEEPSLATE = 4; // structural
    public static final int SAND = 5; // structural
    public static final int GRAVEL = 6; // structural
    public static final int WATER = 7; // structural
    public static final int LAVA = 8; // structural
    public static final int SNOW = 9; // structural
    public static final int ICE = 10; // structural
    public static final int LOG = 11; // structural
    public static final int LEAVES = 12; // structural
    public static final int PLANKS = 13; // structural
    public static final int ORE = 14; // structural
    public static final int CLAY = 15; // structural
    public static final int MUD = 16; // structural
    public static final int PATH = 17; // structural
    public static final int FARMLAND = 18; // structural
    public static final int BEDROCK = 19; // structural
    public static final int NETHERRACK = 20; // structural
    public static final int END_STONE = 21; // structural
    public static final int GLASS = 22; // structural
    public static final int WOOL = 23; // structural
    public static final int TERRACOTTA = 24; // structural
    public static final int CONCRETE = 25; // structural
    public static final int MOSS = 26; // structural
    public static final int MUSHROOM = 27; // structural
    public static final int CROP = 28; // structural

    /** The tag paths the predicate is asked about. */
    public static final List<String> TAGS = List.of("logs", "leaves", "sand", "dirt", "base_stone_overworld", "wool", "planks", "crops");

    private static final Set<String> WATERS = Set.of("water", "bubble_column", "seagrass", "tall_seagrass", "kelp", "kelp_plant");

    private SurfaceFamily() {}

    /** The family index of the block id ("minecraft:stone" or "stone"); tag: true when the block is in that tag. Never throws. */
    public static int of(String id, Predicate<String> tag) {
        if (id == null) return OTHER;
        Predicate<String> t = tag == null ? s -> false : s -> {
            try {
                return tag.test(s);
            } catch (RuntimeException e) {
                return false;
            }
        };
        String p = id.indexOf(':') >= 0 ? id.substring(id.indexOf(':') + 1) : id;
        if (p.equals("bedrock")) return BEDROCK;
        if (WATERS.contains(p)) return WATER;
        if (p.equals("lava")) return LAVA;
        if (p.contains("_ore") || p.equals("ancient_debris")) return ORE;
        if (t.test("logs") || p.endsWith("_log") || p.endsWith("_wood") || p.endsWith("_stem") && !p.contains("mushroom") && !t.test("crops")
                || p.endsWith("_hyphae")) return LOG;
        if (t.test("leaves") || p.endsWith("_leaves")) return LEAVES;
        if (p.equals("grass_block")) return GRASS;
        if (p.equals("dirt_path")) return PATH;
        if (p.equals("farmland")) return FARMLAND;
        if (p.equals("mud") || p.equals("packed_mud") || p.equals("muddy_mangrove_roots") || p.equals("mud_bricks")) return MUD;
        if (p.equals("clay")) return CLAY;
        if (p.equals("moss_block") || p.equals("moss_carpet")) return MOSS;
        if (p.contains("mushroom")) return MUSHROOM;
        if (p.equals("snow_block") || p.equals("snow") || p.equals("powder_snow")) return SNOW;
        if (p.equals("ice") || p.endsWith("_ice")) return ICE;
        if (p.contains("concrete")) return CONCRETE;
        if (p.contains("terracotta")) return TERRACOTTA;
        if (p.contains("glass")) return GLASS;
        if (t.test("wool") || p.endsWith("_wool")) return WOOL;
        if (t.test("planks") || p.endsWith("_planks")) return PLANKS;
        if (t.test("crops")) return CROP;
        if (p.equals("gravel")) return GRAVEL;
        if (t.test("sand") || p.endsWith("sand") || p.endsWith("sandstone")) return SAND;
        if (p.contains("deepslate")) return DEEPSLATE;
        if (p.equals("netherrack")) return NETHERRACK;
        if (p.startsWith("end_stone")) return END_STONE;
        if (t.test("dirt")) return DIRT;
        if (t.test("base_stone_overworld")) return STONE;
        return OTHER;
    }
}
