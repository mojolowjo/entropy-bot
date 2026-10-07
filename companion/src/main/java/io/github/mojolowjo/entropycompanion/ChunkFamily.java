package io.github.mojolowjo.entropycompanion;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Companion 0.3.0: a COPY of the Entropy Bot's surface.SurfaceFamily (the index order is the file format; keep in step).
 * The bot's text: 0.19.3: the ground family of a block for the dashboard's RTS view (pure, JUnit on id strings). The family list's order
 * is part of the surface file format ({@code entropybot\surface\...}): index = position, never reorder, only append.
 * Tags come through a predicate on the tag's path ({@code "logs"}, {@code "leaves"}, {@code "sand"}, {@code "dirt"},
 * {@code "base_stone_overworld"}, {@code "wool"}, {@code "planks"}, {@code "crops"}: vanilla {@code BlockTags}), so this
 * class needs no loader. Loader notes: none (plain Java); the game side ({@code SurfaceExport}) asks
 * {@code BlockState.is(TagKey)} (vanilla, the same on NeoForge and Fabric).
 */
public final class ChunkFamily {
    public static final List<String> FAMILIES = List.of("other", "grass", "dirt", "stone", "deepslate", "sand", "gravel", "water", "lava", "snow",
            "ice", "log", "leaves", "planks", "ore", "clay", "mud", "path", "farmland", "bedrock", "netherrack", "end_stone", "glass", "wool",
            "terracotta", "concrete", "moss", "mushroom", "crop");

    public static final int OTHER = 0, GRASS = 1, DIRT = 2, STONE = 3, DEEPSLATE = 4, SAND = 5, GRAVEL = 6, WATER = 7, LAVA = 8, SNOW = 9, ICE = 10,
            LOG = 11, LEAVES = 12, PLANKS = 13, ORE = 14, CLAY = 15, MUD = 16, PATH = 17, FARMLAND = 18, BEDROCK = 19, NETHERRACK = 20, END_STONE = 21,
            GLASS = 22, WOOL = 23, TERRACOTTA = 24, CONCRETE = 25, MOSS = 26, MUSHROOM = 27, CROP = 28;

    /** The tag paths the predicate is asked about. */
    public static final List<String> TAGS = List.of("logs", "leaves", "sand", "dirt", "base_stone_overworld", "wool", "planks", "crops");

    private static final Set<String> WATERS = Set.of("water", "bubble_column", "seagrass", "tall_seagrass", "kelp", "kelp_plant");
    private static final Set<String> CROPS = Set.of("wheat", "carrots", "potatoes", "beetroots", "melon_stem", "pumpkin_stem", "torchflower_crop",
            "pitcher_crop", "nether_wart", "sweet_berry_bush", "cocoa");
    private static final Set<String> STONES = Set.of("stone", "granite", "diorite", "andesite", "tuff", "calcite", "cobblestone", "mossy_cobblestone",
            "smooth_stone", "dripstone_block", "basalt", "smooth_basalt", "blackstone", "obsidian");
    private static final Set<String> DIRTS = Set.of("dirt", "coarse_dirt", "rooted_dirt", "podzol", "mycelium");

    private ChunkFamily() {}

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
        if (t.test("logs") || p.endsWith("_log") || p.endsWith("_wood") || p.endsWith("_stem") && !p.contains("mushroom") && !CROPS.contains(p)
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
        if (t.test("crops") || CROPS.contains(p)) return CROP;
        if (p.equals("gravel")) return GRAVEL;
        if (t.test("sand") || p.endsWith("sand") || p.endsWith("sandstone")) return SAND;
        if (p.contains("deepslate")) return DEEPSLATE;
        if (p.equals("netherrack")) return NETHERRACK;
        if (p.startsWith("end_stone")) return END_STONE;
        if (t.test("dirt") || DIRTS.contains(p)) return DIRT;
        if (t.test("base_stone_overworld") || STONES.contains(p)) return STONE;
        return OTHER;
    }
}
