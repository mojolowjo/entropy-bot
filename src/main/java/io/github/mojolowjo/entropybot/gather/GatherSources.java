package io.github.mojolowjo.entropybot.gather;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P2 (docs/SURVIVAL_PLAN.md): where a raw item comes from - the source table of {@code gather}. An item with a source
 * is a leaf of the gather plan; everything else is crafted or smelted from leaves (GatherPlan). Rules, in order:
 * the owner's override (commands.json "gatherSources", {@code gather source <item> <command>}), ore drops (strip mine
 * when a mine is marked, else the ore in view, else a cave), logs (chop), crops (a farm round), sand/gravel/clay
 * ({@code mine <block>}), and the items with no way (stone, dirt, Nether things) say what to do instead.
 * Pure Java: JUnit drives it.
 */
public final class GatherSources {
    private GatherSources() {}

    public enum Kind { OVERRIDE, ORE, LOG, CROP, BLOCK, NONE }

    /**
     * One item's source. ore: the family word ("iron", "nickel") and the ore block id; log: the wood type or null;
     * block: the block {@code mine} digs and how many items one block gives; none: the hint after "next: ".
     */
    public record Source(Kind kind, String item, String family, String block, int perBlock, String wood, String hint, String command) {
        static Source none(String item, String hint) { return new Source(Kind.NONE, item, null, null, 1, null, hint, null); }
    }

    /** Ore drops of vanilla: item (no namespace) -> family. Modded "ns:raw_x" are family x (see {@link #resolve}). */
    static final Map<String, String> ORE_DROPS = new LinkedHashMap<>();
    static {
        for (String m : List.of("iron", "copper", "gold")) {
            ORE_DROPS.put("raw_" + m, m);
            ORE_DROPS.put(m + "_ore", m);
            ORE_DROPS.put("deepslate_" + m + "_ore", m);
        }
        for (String m : List.of("coal", "diamond", "emerald", "redstone")) {
            ORE_DROPS.put(m, m);
            ORE_DROPS.put(m + "_ore", m);
            ORE_DROPS.put("deepslate_" + m + "_ore", m);
        }
        ORE_DROPS.put("lapis_lazuli", "lapis");
        ORE_DROPS.put("lapis_ore", "lapis");
        ORE_DROPS.put("deepslate_lapis_ore", "lapis");
    }

    /** Ore block of a family ("lapis" -> lapis_ore). */
    static String oreBlock(String ns, String family) {
        return ns + ":" + family + "_ore";
    }

    static final Pattern LOG = Pattern.compile("^minecraft:(?:stripped_)?([a-z_]+?)_(log|wood)$");
    static final Pattern NETHER_WOOD = Pattern.compile("^(crimson|warped)$");
    static final Pattern CROP = Pattern.compile("^minecraft:(wheat|wheat_seeds|carrot|potato|beetroot|beetroot_seeds)$|^mysticalagriculture:(?!(prudentium|tertium|imperium|supremium|insanium)_)[a-z_]+_essence$");
    static final Pattern MEAT = Pattern.compile("^minecraft:(beef|porkchop|chicken|mutton|rabbit|cod|salmon|tropical_fish|leather|rabbit_hide|feather)$");
    /** Blocks the "mine" verb may take (MineRules.EXTRA): the item, the block, items per block. */
    static final Map<String, Object[]> BLOCKS = Map.of(
            "minecraft:sand", new Object[]{"sand", 1},
            "minecraft:red_sand", new Object[]{"red_sand", 1},
            "minecraft:gravel", new Object[]{"gravel", 1},
            "minecraft:clay", new Object[]{"clay", 1},
            "minecraft:clay_ball", new Object[]{"clay", 4});
    static final Pattern NO_WAY = Pattern.compile("^minecraft:(cobblestone|stone|dirt|cobbled_deepslate|deepslate|andesite|diorite|granite|tuff|netherrack|soul_sand|blackstone|end_stone)$");
    static final Pattern NETHER = Pattern.compile("^minecraft:(quartz|nether_quartz_ore|glowstone_dust|glowstone|blaze_rod|ancient_debris|netherite_scrap|nether_wart|magma_cream|ghast_tear|crimson_.*|warped_.*|shroomlight)$");

    /** The item's source (overrides: item id -> command, from commands.json). */
    public static Source resolve(String id, Map<String, String> overrides) {
        String item = full(id);
        if (overrides != null && overrides.containsKey(item)) return new Source(Kind.OVERRIDE, item, null, null, 1, null, null, overrides.get(item));
        String ns = item.substring(0, item.indexOf(':')), path = item.substring(item.indexOf(':') + 1);
        if (ns.equals("minecraft") && ORE_DROPS.containsKey(path)) {
            String fam = ORE_DROPS.get(path);
            return new Source(Kind.ORE, item, fam, oreBlock(ns, fam), 1, null, null, null);
        }
        if (!ns.equals("minecraft") && path.matches("^(raw_|deepslate_)?[a-z]+(_ore)?$") && (path.startsWith("raw_") || path.endsWith("_ore"))) {
            String fam = path.replaceFirst("^raw_", "").replaceFirst("^deepslate_", "").replaceFirst("_ore$", "");
            return new Source(Kind.ORE, item, fam, oreBlock(ns, fam), 1, null, null, null);
        }
        if (NETHER.matcher(item).find()) return Source.none(item, "it comes from the Nether, where I don't go - put some in the base chests");
        Matcher lm = LOG.matcher(item);
        if (lm.find()) {
            if (NETHER_WOOD.matcher(lm.group(1)).find()) return Source.none(item, "it comes from the Nether, where I don't go - put some in the base chests");
            return new Source(Kind.LOG, item, null, null, 1, lm.group(1), null, null);
        }
        // P5: raw meat and fish come from animals; there is no hunting (breeding and hunting: later, the owner 2026-10-05)
        if (MEAT.matcher(item).find()) {
            return Source.none(item, "it comes from animals and hunting is off - put some " + shortId(item) + " in the base chests (then gather cooks it)");
        }
        if (CROP.matcher(item).find()) return new Source(Kind.CROP, item, null, null, 1, null, null, null);
        if (BLOCKS.containsKey(item)) {
            Object[] b = BLOCKS.get(item);
            return new Source(Kind.BLOCK, item, null, "minecraft:" + b[0], (Integer) b[1], null, null, null);
        }
        if (NO_WAY.matcher(item).find()) {
            return Source.none(item, "I only dig stone and dirt in a box you give me: \"dig x1 y1 z1 x2 y2 z2\" (or tell me how: gather source "
                    + shortId(item) + " <command with {n}>), or put some in the base chests");
        }
        return null;     // not a raw item: crafted or smelted (or nothing knows it)
    }

    /**
     * The commands that get {@code n} of a leaf, in the order to try them (a failure moves to the next).
     * mineMarked: a "mine" place with a direction is marked. anyWood: chop any log type (a tag asked for "any planks").
     */
    public static List<String> commands(Source s, int n, boolean mineMarked, boolean anyWood) {
        int k = Math.max(1, n);
        List<String> out = new ArrayList<>();
        switch (s.kind()) {
            case OVERRIDE -> out.add(s.command().contains("{n}") ? s.command().replace("{n}", String.valueOf(k)) : s.command());
            case ORE -> {
                if (mineMarked) out.add("mine strip " + s.family() + " " + k);
                out.add("mine " + shortId(s.block()) + " " + k);
                out.add("mine cave " + s.family() + " " + k + " 10m");
            }
            case LOG -> out.add("chop " + Math.max(k, 1) + (anyWood || s.wood() == null ? "" : " " + s.wood()));
            case CROP -> out.add("farm");
            case BLOCK -> out.add("mine " + shortId(s.block()) + " " + (int) Math.ceil(k / (double) s.perBlock()));
            default -> {}
        }
        return out;
    }

    /** The verb word of a source command, for the end line ("mine" -> "mined", "chop" -> "chopped"). */
    public static String doneWord(String command) {
        String v = command == null ? "" : command.trim().toLowerCase(Locale.ROOT).split("\\s+")[0];
        return switch (v) {
            case "mine" -> "mined";
            case "chop" -> "chopped";
            case "farm" -> "farmed";
            case "dig" -> "dug";
            case "get", "rs" -> "took";
            case "craft" -> "crafted";
            case "smelt" -> "smelted";
            default -> "got";
        };
    }

    /** "gather sources [item]": the table, or what one item's chain looks like (with the live plan's line). */
    public static String table(Map<String, String> overrides) {
        StringBuilder sb = new StringBuilder("gather sources: ores (raw_iron, coal, diamond, ...) -> mine strip <ore> at the mine, else mine <ore> in view, else mine cave"
                + "; logs -> chop; wheat, carrots, potatoes, beetroot, essence -> farm; sand, gravel, clay -> mine <block>; anything else -> crafted or smelted from those"
                + "; stone, dirt and Nether items -> none (put some in the base chests)");
        if (overrides != null && !overrides.isEmpty()) {
            List<String> o = new ArrayList<>();
            for (Map.Entry<String, String> e : overrides.entrySet()) o.add(shortId(e.getKey()) + " -> " + e.getValue());
            sb.append("; yours: ").append(String.join(", ", o));
        }
        return sb.toString();
    }

    public static String full(String id) {
        String t = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return t.contains(":") ? t : "minecraft:" + t;
    }

    public static String shortId(String id) {
        return id == null ? "?" : id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
