package io.github.mojolowjo.entropybot.vocab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 0.33.0 (fresh-world runs: a village was "someone's base" for its loot chests, beds and hay bales; mineshaft planks and
 * fences "looked like a build"): the world's own structures, told apart from player builds by their blocks. The client is
 * never sent the chunk's structure references (they live on the server), so this works from what a scan saw: block ids
 * (path, no namespace) with counts, the villagers/golems near, and how many of the built blocks are waterlogged.
 *
 * <p>A scan is natural when a structure's clue blocks are there AND nothing is left over: no player-only marker (signs,
 * shulker boxes, hoppers, redstone, an ender chest, concrete...) and fewer than {@link ExploreWords#BASE_BUILT} built
 * blocks outside that structure's palette. Containers count as explained only up to what the found structure places
 * itself (a village 3, a mineshaft none: its chests ride minecarts), so a player's storage in or next to a village stays a
 * base; a villager alone is no village. Plain Java; loader API: none.
 */
public final class NaturalStructures {
    private NaturalStructures() {}

    /** What a scan saw. blocks: id path to count (built blocks and the clue blocks); wet: built blocks holding water. */
    /** open: built blocks under the open sky (-1: not known); a mineshaft is underground. */
    public record Seen(Map<String, Integer> blocks, int villagers, int wet, int open) {
        public Seen(Map<String, Integer> blocks, int villagers, int wet) { this(blocks, villagers, wet, -1); }
    }

    /** Containers: a structure explains only as many as it places itself ({@link Kind#chests}); more are a player's storage. */
    static boolean container(String id) {
        return id.equals("chest") || id.equals("trapped_chest") || id.equals("barrel");
    }

    static int built(Seen s) { return sum(s, id -> !neutral(id)); }

    static boolean underground(Seen s) { return s.open() < 0 || s.open() * 4 <= built(s); }

    /** The village's own blocks (plains, desert, savanna, taiga, snowy): wood of the biome, cobble, panes, beds, crops, job sites. */
    static boolean villageBlock(String id) {
        return wood(id) || id.contains("cobblestone") || id.equals("glass_pane") || id.endsWith("_bed") || id.endsWith("_wool") || id.endsWith("_carpet")
                || id.equals("hay_block") || id.equals("dirt_path") || id.equals("farmland") || id.equals("wheat") || id.equals("carrots")
                || id.equals("potatoes") || id.equals("beetroots") || JOB_SITES.contains(id) || id.equals("bell") || id.equals("chest")
                || id.contains("terracotta") || id.contains("sandstone") || id.equals("smooth_stone") || id.equals("smooth_stone_slab")
                || id.equals("bricks") || id.equals("ladder") || id.equals("flower_pot") || id.startsWith("potted_") || id.endsWith("_pressure_plate")
                || id.equals("bookshelf") || id.equals("packed_ice") || id.equals("snow_block") || id.equals("ice") || id.startsWith("stripped_")
                || id.equals("water_cauldron") || id.equals("lava_cauldron") || id.equals("iron_bars") || id.equals("cobblestone_wall")
                || id.endsWith("_button") || id.equals("lantern") || id.equals("melon") || id.equals("pumpkin") || id.equals("anvil");
    }

    /** Blocks no world structure places: one of them means a player was here. */
    public static boolean playerMarker(String id) {
        return id.endsWith("_sign") || id.equals("sign") || id.endsWith("shulker_box") || id.equals("ender_chest") || id.equals("hopper")
                || id.equals("beacon") || id.equals("enchanting_table") || id.equals("repeater") || id.equals("comparator") || id.equals("observer")
                || id.equals("piston") || id.equals("sticky_piston") || id.equals("dropper") || id.equals("daylight_detector") || id.equals("note_block")
                || id.equals("jukebox") || id.endsWith("_concrete") || id.equals("crafter") || id.equals("respawn_anchor") || id.equals("lodestone")
                || id.equals("redstone_lamp") || id.equals("conduit") || id.equals("end_crystal")
                || id.equals("diamond_block") || id.equals("emerald_block") || id.equals("netherite_block") || id.equals("quartz_block");
    }

    /** Never counted either way: light anyone may have put up (torches in a mineshaft are not a base). */
    static boolean neutral(String id) {
        return id.equals("torch") || id.equals("wall_torch") || id.equals("lantern") || id.equals("soul_torch") || id.equals("soul_wall_torch");
    }

    /** chests: how many containers (chests, barrels) the structure places itself in one scan. */
    record Kind(String name, Predicate<Seen> clue, Predicate<String> palette, int chests) {
        Kind(String name, Predicate<Seen> clue, Predicate<String> palette) { this(name, clue, palette, 2); }
    }

    static int n(Seen s, String id) { return s.blocks().getOrDefault(id, 0); }

    static int sum(Seen s, Predicate<String> p) {
        int t = 0;
        for (var e : s.blocks().entrySet()) if (p.test(e.getKey())) t += e.getValue();
        return t;
    }

    static boolean wood(String id) {
        return id.endsWith("_planks") || id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stairs") || id.endsWith("_slab")
                || id.endsWith("_fence") || id.endsWith("_fence_gate") || id.endsWith("_trapdoor") || id.endsWith("_door");
    }

    static final Set<String> JOB_SITES = Set.of("composter", "smoker", "blast_furnace", "barrel", "cartography_table", "fletching_table",
            "grindstone", "lectern", "loom", "smithing_table", "stonecutter", "brewing_stand", "cauldron", "water_cauldron");

    static boolean stoneBrick(String id) {
        return id.contains("stone_brick") || id.equals("chiseled_stone_bricks") || id.contains("cobblestone") || id.equals("stone_button")
                || id.startsWith("infested_");
    }

    static final List<Kind> KINDS = List.of(
            new Kind("a village",
                    // review: a villager alone is not a village (a player's base next to one): its bell, job sites, paths, beds
                    s -> n(s, "bell") > 0 || (n(s, "dirt_path") >= 3 && sum(s, JOB_SITES::contains) > 0)
                            || (s.villagers() > 0 && (sum(s, JOB_SITES::contains) > 0 || n(s, "dirt_path") >= 3 || sum(s, id -> id.endsWith("_bed")) > 0)),
                    NaturalStructures::villageBlock, 3),
            new Kind("a mineshaft",
                    // review: the corridor's own set underground: fence posts under planks, and rails or cobwebs
                    s -> underground(s) && sum(s, id -> id.endsWith("_fence")) >= 2 && sum(s, id -> id.endsWith("_planks")) >= 2
                            && (n(s, "rail") >= 2 || n(s, "cobweb") >= 3),
                    id -> id.endsWith("_planks") || id.endsWith("_fence") || id.equals("rail") || id.equals("cobweb") || id.equals("spawner") || id.endsWith("_log"), 0),
            new Kind("a ruined portal",
                    s -> n(s, "obsidian") + n(s, "crying_obsidian") >= 2 && n(s, "netherrack") + n(s, "magma_block") + n(s, "gold_block") > 0,
                    id -> id.equals("obsidian") || id.equals("crying_obsidian") || id.equals("netherrack") || id.equals("magma_block") || id.equals("gold_block")
                            || id.equals("chest") || id.equals("iron_bars") || id.contains("blackstone") || stoneBrick(id) || id.contains("sandstone")
                            || id.equals("lava") || id.equals("fire") || id.equals("vine")),
            new Kind("a dungeon", s -> n(s, "spawner") > 0,
                    id -> id.equals("spawner") || id.equals("chest") || id.contains("cobblestone")),
            new Kind("a stronghold", s -> n(s, "end_portal_frame") > 0 || (n(s, "iron_door") > 0 && sum(s, NaturalStructures::stoneBrick) >= 6),
                    id -> stoneBrick(id) || id.equals("iron_bars") || id.equals("iron_door") || id.equals("oak_door") || id.equals("bookshelf") || id.equals("ladder")
                            || id.equals("chest") || id.startsWith("oak_") || id.equals("end_portal_frame") || id.equals("spawner") || id.equals("smooth_stone_slab")
                            || id.equals("stone_slab") || id.equals("stone_brick_slab") || id.equals("cobweb") || id.equals("water")),
            new Kind("an ancient city", s -> n(s, "reinforced_deepslate") + n(s, "sculk_shrieker") + n(s, "sculk_sensor") + n(s, "sculk_catalyst") > 0,
                    id -> id.contains("deepslate") || id.contains("sculk") || id.contains("soul_") || id.endsWith("candle") || id.endsWith("_wool")
                            || id.endsWith("_carpet") || id.equals("chest") || id.startsWith("dark_oak_") || id.contains("basalt") || id.contains("skull")
                            || id.contains("head") || id.equals("ladder") || id.equals("redstone_lamp") || id.equals("comparator") || id.equals("note_block")
                            || id.equals("packed_ice") || id.equals("blue_ice") || id.equals("snow_block") || id.equals("glass") || id.equals("glass_pane")
                            || id.equals("light_blue_wool") || id.equals("gray_wool") || id.equals("chiseled_tuff") || id.equals("tuff_bricks")),
            new Kind("a dig site (trail ruins, desert temple, ocean ruins)",
                    s -> n(s, "suspicious_sand") + n(s, "suspicious_gravel") > 0 || (n(s, "orange_terracotta") > 0 && n(s, "blue_terracotta") > 0 && n(s, "tnt") > 0),
                    id -> id.contains("terracotta") || id.contains("mud_brick") || id.equals("packed_mud") || id.contains("sandstone") || id.equals("chest")
                            || id.startsWith("suspicious_") || id.equals("decorated_pot") || stoneBrick(id) || id.equals("tnt") || id.equals("stone_pressure_plate")
                            || id.contains("prismarine") || id.endsWith("_wool") || id.equals("gravel") || id.equals("coarse_dirt") || id.equals("magma_block")
                            || id.equals("sand") || id.contains("_log") || id.contains("_planks")),
            new Kind("a jungle temple", s -> n(s, "tripwire_hook") > 0 && n(s, "dispenser") > 0,
                    id -> id.contains("cobblestone") || id.contains("stone_brick") || id.equals("dispenser") || id.equals("tripwire_hook") || id.equals("tripwire")
                            || id.equals("redstone_wire") || id.equals("repeater") || id.equals("sticky_piston") || id.equals("lever") || id.equals("chest")
                            || id.equals("vine")),
            new Kind("an ocean monument", s -> sum(s, id -> id.contains("prismarine") || id.equals("sea_lantern")) >= 6,
                    id -> id.contains("prismarine") || id.equals("sea_lantern") || id.equals("wet_sponge") || id.equals("gold_block")),
            new Kind("a trial chamber", s -> n(s, "trial_spawner") + n(s, "vault") > 0,
                    id -> id.contains("tuff") || id.contains("copper") || id.equals("trial_spawner") || id.equals("vault") || id.equals("chest")
                            || id.equals("barrel") || id.equals("decorated_pot") || id.equals("crafter") || id.equals("dispenser") || id.equals("hopper")
                            || id.contains("candle") || id.endsWith("_wool") || id.contains("bricks") || id.contains("bed") || id.equals("heavy_core")),
            new Kind("an igloo", s -> n(s, "snow_block") >= 6 && n(s, "white_carpet") + n(s, "red_bed") > 0,
                    id -> id.equals("snow_block") || id.equals("packed_ice") || id.equals("ice") || id.equals("white_carpet") || id.equals("red_bed")
                            || id.equals("furnace") || id.equals("crafting_table") || id.equals("redstone_torch") || id.equals("chest") || stoneBrick(id)
                            || id.equals("brewing_stand") || id.equals("cauldron") || id.contains("spruce") || id.contains("lever")),
            new Kind("a witch hut", s -> n(s, "cauldron") > 0 && sum(s, id -> id.startsWith("spruce_")) >= 6 && n(s, "potted_red_mushroom") + n(s, "crafting_table") > 0,
                    id -> id.startsWith("spruce_") || id.startsWith("oak_") || id.equals("cauldron") || id.equals("crafting_table") || id.startsWith("potted_")
                            || id.equals("flower_pot") || id.equals("glass_pane")),
            // review: sunk = most of its wood holds water (a dock's few wet posts are not), and a ship has its loot chest
            new Kind("a shipwreck", s -> s.wet() >= 6 && s.wet() * 2 >= sum(s, NaturalStructures::wood) && n(s, "chest") > 0,
                    id -> wood(id) || id.equals("chest") || id.equals("iron_bars"), 3));

    /** Every structure name, for the docs and tests. */
    public static List<String> names() {
        List<String> l = new ArrayList<>();
        for (Kind k : KINDS) l.add(k.name);
        return l;
    }

    /**
     * The world's own structure(s) that explain everything the scan saw ("a village", "a mineshaft and a dungeon"), or null:
     * no structure's clue is there, a player-only marker is, or {@link ExploreWords#BASE_BUILT} or more blocks are left
     * that no found structure would place.
     */
    public static String explains(Seen s) {
        if (s == null || s.blocks() == null) return null;
        List<Kind> found = new ArrayList<>();
        for (Kind k : KINDS) if (k.clue.test(s)) found.add(k);
        if (found.isEmpty()) return null;
        // a marker is a player's unless a found structure (not a village: its palette is everything) places it itself
        for (String raw : s.blocks().keySet()) {
            String id = raw.toLowerCase(Locale.ROOT);
            if (!playerMarker(id)) continue;
            boolean placed = false;
            for (Kind k : found) if (k.palette.test(id)) { placed = true; break; }
            if (!placed) return null;
        }
        // review: containers are explained only up to what the found structures place themselves
        int allow = 0;
        for (Kind k : found) if (sum(s, id -> container(id) && k.palette.test(id)) > 0) allow += k.chests;
        if (sum(s, NaturalStructures::container) > allow) return null;
        int left = 0;
        for (var e : s.blocks().entrySet()) {
            String id = e.getKey().toLowerCase(Locale.ROOT);
            if (neutral(id)) continue;
            boolean ok = false;
            for (Kind k : found) if (k.palette.test(id)) { ok = true; break; }
            if (!ok) left += e.getValue();
        }
        if (left >= ExploreWords.BASE_BUILT) return null;
        List<String> names = new ArrayList<>();
        for (Kind k : found) names.add(k.name);
        return String.join(" and ", names);
    }

    /** 0.33.0: block entities that grow in the wild (a bee nest in a tree, sculk, a lone spawner): never a base by themselves. */
    public static boolean wildBlockEntity(String id) {
        return id.equals("bee_nest") || id.equals("sculk_sensor") || id.equals("sculk_shrieker")
                || id.equals("sculk_catalyst") || id.startsWith("suspicious_") || id.equals("spawner") || id.equals("trial_spawner") || id.equals("vault");
    }

    /** Clue blocks a scan should count even though they are not "built" blocks (cobwebs, obsidian, netherrack...). */
    public static boolean clue(String id) {
        return id.equals("cobweb") || id.equals("obsidian") || id.equals("crying_obsidian") || id.equals("netherrack") || id.equals("magma_block")
                || id.equals("gold_block") || id.equals("spawner") || id.equals("end_portal_frame") || id.equals("reinforced_deepslate")
                || id.startsWith("sculk") || id.startsWith("suspicious_") || id.equals("dirt_path") || id.equals("hay_block") || id.equals("bell")
                || id.equals("rail") || id.equals("tnt") || id.equals("tripwire_hook") || id.equals("dispenser") || id.contains("prismarine")
                || id.equals("sea_lantern") || id.equals("trial_spawner") || id.equals("vault") || id.equals("snow_block") || id.contains("terracotta")
                || id.equals("cauldron") || id.equals("iron_door");
    }
}
