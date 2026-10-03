package io.github.mojolowjo.entropybot.map;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Minecraft-free parts of the terrain map (docs/BOT_PLAN.md B7b): the paper-map shading, colours as
 * java.awt ARGB, where a block lands in its 512x512 region, and the names of the files. Shading follows
 * vanilla {@code MapItem.update} at scale 0 (one block a pixel), so the map looks like a paper map.
 */
public final class MapMath {
    public static final int REGION = 512;               // blocks a side of one region (32x32 chunks)
    public static final int LOW = 0, NORMAL = 1, HIGH = 2, LOWEST = 3;
    static final int[] MODIFIER = { 180, 220, 255, 135 }; // MapColor.Brightness, by id
    public static final int WATER_DEPTH_MAX = 10;       // deeper water shades no darker
    private static final Pattern FILE = Pattern.compile("^(-?\\d{1,7})\\.(-?\\d{1,7})\\.png$");

    private MapMath() {}

    /**
     * Brightness of a land column from its height and the height of the column to its north (z-1):
     * higher than its northern neighbour is brighter, lower is darker, with vanilla's checkerboard dither.
     */
    public static int land(int y, int yNorth, int x, int z) {
        double d = (y - yNorth) * 4.0 / 5.0 + (((x + z) & 1) - 0.5) * 0.4;
        if (d > 0.6) return HIGH;
        if (d < -0.6) return LOW;
        return NORMAL;
    }

    /** Brightness of a water column from its depth (water blocks from the top down, 1 = a puddle). */
    public static int water(int depth, int x, int z) {
        double d = Math.min(depth, WATER_DEPTH_MAX) * 0.1 + ((x + z) & 1) * 0.2;
        if (d < 0.5) return HIGH;
        if (d > 0.9) return LOW;
        return NORMAL;
    }

    /**
     * An opaque ARGB colour (java.awt order) from a MapColor's {@code col} (0xRRGGBB) and a brightness id.
     * The same numbers as {@code MapColor.calculateRGBColor}, which packs them the other way round
     * (0xAABBGGRR, for NativeImage).
     */
    public static int argb(int rgb, int brightness) {
        int m = MODIFIER[brightness & 3];
        int r = (rgb >> 16 & 0xFF) * m / 255, g = (rgb >> 8 & 0xFF) * m / 255, b = (rgb & 0xFF) * m / 255;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** NativeImage's 0xAABBGGRR as java.awt's 0xAARRGGBB (swaps red and blue). */
    public static int abgrToArgb(int abgr) {
        return (abgr & 0xFF00FF00) | (abgr & 0xFF) << 16 | (abgr >> 16 & 0xFF);
    }

    /** The region a block coordinate lies in (floor division, so -1 is in region -1). */
    public static int region(int block) {
        return Math.floorDiv(block, REGION);
    }

    /** Where block x z sits in its region's int[] (rows of 512, north row first). */
    public static int index(int x, int z) {
        return Math.floorMod(z, REGION) * REGION + Math.floorMod(x, REGION);
    }

    /**
     * The folder of a dimension: its id with ':' as '_' (minecraft:overworld -> minecraft_overworld); a '/'
     * in the id becomes '_' too, so every dimension is one folder deep.
     */
    public static String dimFolder(String dim) {
        return dim.replace(':', '_').replace('/', '_');
    }

    /** A folder name back to a dimension id when nothing better is known: the first '_' becomes ':'. */
    public static String guessDim(String folder) {
        int i = folder.indexOf('_');
        return i < 0 ? folder : folder.substring(0, i) + ":" + folder.substring(i + 1);
    }

    public static String fileName(int rx, int rz) {
        return rx + "." + rz + ".png";
    }

    /** rx, rz of a tile's file name, null when it is not one. */
    public static int[] parseFileName(String name) {
        Matcher m = FILE.matcher(name);
        if (!m.matches()) return null;
        return new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) };
    }

    /** index.json: {"<dim>":[[rx,rz,lastWriteMs],...],...}. */
    public static String indexJson(Map<String, List<long[]>> tiles) {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, List<long[]>> e : tiles.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append('"').append(e.getKey().replace("\\", "\\\\").replace("\"", "\\\"")).append("\":[");
            for (int i = 0; i < e.getValue().size(); i++) {
                long[] t = e.getValue().get(i);
                if (i > 0) b.append(',');
                b.append('[').append(t[0]).append(',').append(t[1]).append(',').append(t[2]).append(']');
            }
            b.append(']');
        }
        return b.append('}').toString();
    }
}
