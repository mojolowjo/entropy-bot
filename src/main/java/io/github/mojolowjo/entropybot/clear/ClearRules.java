package io.github.mojolowjo.entropybot.clear;

import java.util.regex.Pattern;

/** The clear engine's constants and name rules (the bridge's CLEAR_REACH, HAZARD_RE, FALLING_RE, PROTECT_RE). */
public final class ClearRules {
    private ClearRules() {}

    /** Eye to block centre. Vanilla's reach; the server allows ~5.5 to the block's edge. */
    public static final double CLEAR_REACH = 4.5;

    /** Cells the walking map treats as neither open nor solid (never walked through or stood on). */
    public static final Pattern HAZARD_RE = Pattern.compile("lava|fire|magma|cobweb|sweet_berry|powder_snow|cactus|wither_rose");

    /**
     * Blocks that fall. The bridge tests the description id ("block.minecraft.gravel"); here the block name
     * ("gravel", "red_sand", "mod:x_sand"), so a name with no prefix matches too.
     */
    public static final Pattern FALLING_RE = Pattern.compile("(^|[:._])(gravel|sand|concrete_powder)$|anvil$");

    /**
     * Building blocks that don't occur in the wild, by block id: the same list as the bridge and the guard
     * (guard.Guard.PROTECT_RE, which is package-private there). {@link McClearWorld} uses the guard's registry-built
     * set and falls back on this until that is built, as the bridge does.
     */
    public static final Pattern PROTECT_RE = Pattern.compile(String.join("|",
            "_planks$", "_stairs$", "_slab$", "_fence$", "_fence_gate$", "_door$", "_trapdoor$", "glass", "_pane$", "_wool$", "_carpet$",
            ":bricks$", "_bricks$", "_wall$", "_button$", "_pressure_plate$", "_concrete$", "_concrete_powder$", "glazed_terracotta$",
            ":(polished|chiseled|cut)_", ":smooth_(stone|sandstone|red_sandstone|quartz)", "_sapling$", ":potted_", ":flower_pot$", "candle",
            ":(waxed_)?((exposed|weathered|oxidized)_)?(cut|chiseled)_", ":waxed_", ":stripped_", "_wood$", "_hyphae$", "_tiles$", "_froglight$",
            ":(iron|gold|diamond|emerald|netherite|lapis|redstone|coal|copper)_block$",
            ":(quartz_block|quartz_pillar|dark_prismarine|smithing_table|stonecutter|grindstone|loom|cartography_table|fletching_table|"
                    + "composter|cauldron|water_cauldron|lava_cauldron|powder_snow_cauldron|anvil|chipped_anvil|damaged_anvil|lodestone|"
                    + "respawn_anchor|lightning_rod|dried_kelp_block|honey_block|slime_block|packed_mud|nether_wart|copper_bulb|copper_grate)$",
            ":(ladder|rail|powered_rail|detector_rail|activator_rail|torch|wall_torch|soul_torch|soul_wall_torch|redstone_torch|"
                    + "redstone_wall_torch|lantern|soul_lantern|farmland|scaffolding|iron_bars|chain|lever|hay_block|bookshelf|crafting_table|"
                    + "end_rod|sea_lantern|carved_pumpkin|jack_o_lantern|tnt|target|note_block|redstone_wire|repeater|piston|sticky_piston|"
                    + "observer|redstone_lamp)$",
            ":(wheat|carrots|potatoes|beetroots|melon_stem|pumpkin_stem|attached_melon_stem|attached_pumpkin_stem|cocoa|sweet_berry_bush)$",
            "_crop$", "farmland$"));

    /** A collision shape no taller than a carpet is walked over, not a wall (moss carpet, rails). */
    public static final double THIN = 0.1875;

    /** "minecraft:stone" -> "stone"; "oritech:nickel_ore" stays. */
    public static String blockName(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    /** "stone" -> "minecraft:stone". */
    public static String fullId(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    /**
     * P3 (chop): TreeChop's partly chopped log ("treechop:chopped_log") has a block entity (its chop count) but is a tree's
     * log, not someone's machine: the guard's floor, the protected list and the clear engine treat it as a plain log.
     */
    public static boolean choppedLog(String id) {
        return id != null && id.endsWith(":chopped_log");
    }

    /** A building block by id (PROTECT_RE); the registry walk adds every block entity and skips ores. */
    public static boolean builtId(String id) {
        return PROTECT_RE.matcher(fullId(id)).find();
    }

    public static boolean falling(String name) {
        return FALLING_RE.matcher(name).find();
    }

    public static boolean hazard(String name) {
        return HAZARD_RE.matcher(name).find();
    }

    static int floor(double v) {
        return (int) Math.floor(v);
    }
}
