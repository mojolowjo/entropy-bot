package io.github.mojolowjo.entropybot.farm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * P5 "farm plant &lt;crop&gt; [x1 z1 x2 z2 | here &lt;r&gt;]", the game-free part: the arguments, the seed of a crop name,
 * the seed a broken crop block gives back (vanilla replant), and the layout: which cells get a water source, which are
 * tilled and planted, and which stay dry. The vanilla rule (FarmBlock.isNearWater): farmland is wet with water within 4
 * blocks each way (x and z, same level or one up). The owner (2026-10-05): water only from a carried bucket, inside the
 * box. One source covers 4 each way, so the sources go on a lattice 9 apart, starting 4 in from the box's edge.
 */
public final class FarmPlant {
    private FarmPlant() {}

    /** Farmland within this many blocks (each way) of water stays wet. */
    public static final int WATER_R = 4;
    /** The largest box side. */
    public static final int MAX_SIDE = 33;
    public static final String USAGE = "usage: farm plant <crop> [x1 z1 x2 z2 | here <r>] (crop: wheat, carrot, potato, beetroot, or a seed id)";

    /** The parsed words: crop, then a box (x1 z1 x2 z2), or here with a radius, or neither (the farm's own box). */
    public record Args(String crop, int[] box, boolean here, int r, String error) {
        static Args err(String e) { return new Args(null, null, false, 0, e); }
    }

    public static Args parse(String rest) {
        String[] w = rest == null ? new String[0] : rest.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (w.length < 1 || w[0].isEmpty()) return Args.err(USAGE);
        String crop = w[0];
        if (w.length == 1) return new Args(crop, null, false, 0, null);
        try {
            if (w[1].equals("here")) {
                if (w.length > 3) return Args.err(USAGE);
                int r = w.length == 3 ? Integer.parseInt(w[2]) : 4;
                if (r < 1 || 2 * r + 1 > MAX_SIDE) return Args.err("error: the radius goes from 1 to " + (MAX_SIDE - 1) / 2);
                return new Args(crop, null, true, r, null);
            }
            if (w.length != 5) return Args.err(USAGE);
            int x1 = Integer.parseInt(w[1]), z1 = Integer.parseInt(w[2]), x2 = Integer.parseInt(w[3]), z2 = Integer.parseInt(w[4]);
            int[] b = {Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2)};
            if (b[2] - b[0] + 1 > MAX_SIDE || b[3] - b[1] + 1 > MAX_SIDE) return Args.err("error: a farm box is at most " + MAX_SIDE + " blocks a side");
            return new Args(crop, b, false, 0, null);
        } catch (NumberFormatException e) {
            return Args.err(USAGE);
        }
    }

    /** The seed item of a crop name ("wheat" -> minecraft:wheat_seeds), a seed id as given, or null. */
    public static String seedFor(String crop) {
        String c = crop == null ? "" : crop.trim().toLowerCase(Locale.ROOT);
        if (c.startsWith("minecraft:")) c = c.substring(10);
        return switch (c) {
            case "wheat", "wheat_seeds", "seeds" -> "minecraft:wheat_seeds";
            case "carrot", "carrots" -> "minecraft:carrot";
            case "potato", "potatoes" -> "minecraft:potato";
            case "beetroot", "beetroots", "beetroot_seeds" -> "minecraft:beetroot_seeds";
            default -> c.contains(":") ? c : c.endsWith("_seeds") ? "minecraft:" + c : null;
        };
    }

    /** The crop's name for the report ("minecraft:wheat_seeds" -> wheat, "mysticalagriculture:inferium_seeds" -> inferium). */
    public static String cropName(String seed) {
        String s = seed.contains(":") ? seed.substring(seed.indexOf(':') + 1) : seed;
        return switch (s) {
            case "wheat_seeds" -> "wheat";
            case "beetroot_seeds" -> "beetroot";
            default -> s.replaceFirst("_seeds$", "");
        };
    }

    /** The seed a broken crop block gives back for the vanilla replant, or null (then the block's own item is asked). */
    public static String replantSeed(String blockId) {
        if (blockId == null) return null;
        return switch (blockId) {
            case "minecraft:wheat" -> "minecraft:wheat_seeds";
            case "minecraft:carrots" -> "minecraft:carrot";
            case "minecraft:potatoes" -> "minecraft:potato";
            case "minecraft:beetroots" -> "minecraft:beetroot_seeds";
            default -> blockId.endsWith("_crop") ? blockId.substring(0, blockId.length() - 5) + "_seeds" : null;
        };
    }

    /** One column of the box as the game sees it: its ground y and what it is. */
    public enum Kind { TILL, FARMLAND, PLANTED, WATER, BLOCKED }

    public record Cell(int x, int y, int z, Kind kind) {
        public String key() { return x + " " + z; }
    }

    /** The column source (the game reads the client level; tests use a map). */
    public interface Ground {
        Cell at(int x, int z);
    }

    /** water: cells to dig out and fill from a bucket; plant: cells to till (if needed) and plant; dry: cells left, no water near. */
    public record Layout(List<Cell> water, List<Cell> plant, int dry, int blocked, int planted, int waterWanted) {}

    /** Lattice positions along one axis lo..hi so every cell is within WATER_R of one. */
    static List<Integer> lattice(int lo, int hi) {
        List<Integer> out = new ArrayList<>();
        int p = lo + WATER_R;
        if (p > hi) p = (lo + hi) / 2;
        while (true) {
            out.add(p);
            if (p + WATER_R >= hi) break;
            p = Math.min(p + 2 * WATER_R + 1, hi);
        }
        return out;
    }

    static boolean near(Cell a, Cell b) {
        return Math.abs(a.x() - b.x()) <= WATER_R && Math.abs(a.z() - b.z()) <= WATER_R && b.y() >= a.y() && b.y() <= a.y() + 1;
    }

    /**
     * The layout of box x1 z1 x2 z2. buckets: water buckets carried (each places one source). A lattice point gets a
     * source when a field cell within reach of it has no water yet; the point itself, else the nearest field cell next
     * to it (the 8 around). Cells are planted in rows (back and forth), the water cells never.
     */
    public static Layout plan(int x1, int z1, int x2, int z2, Ground g, int buckets) {
        List<Cell> field = new ArrayList<>(), water = new ArrayList<>(), added = new ArrayList<>();
        int blocked = 0, planted = 0;
        for (int z = z1; z <= z2; z++) {
            for (int x = x1; x <= x2; x++) {
                Cell c = g.at(x, z);
                if (c == null || c.kind() == Kind.BLOCKED) blocked++;
                else if (c.kind() == Kind.WATER) water.add(c);
                else if (c.kind() == Kind.PLANTED) planted++;
                else field.add(c);
            }
        }
        int wanted = 0;
        Set<String> taken = new HashSet<>();
        for (int pz : lattice(z1, z2)) {
            for (int px : lattice(x1, x2)) {
                boolean needs = false;
                for (Cell c : field) {
                    if (Math.abs(c.x() - px) > WATER_R || Math.abs(c.z() - pz) > WATER_R) continue;
                    if (!wet(c, water) && !wet(c, added)) {
                        needs = true;
                        break;
                    }
                }
                if (!needs) continue;
                wanted++;
                if (added.size() >= buckets) continue;
                Cell spot = null;
                for (int r = 0; r <= 1 && spot == null; r++) {
                    for (int dz = -r; dz <= r && spot == null; dz++) {
                        for (int dx = -r; dx <= r && spot == null; dx++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                            for (Cell c : field) {
                                if (c.x() == px + dx && c.z() == pz + dz && !taken.contains(c.key())) {
                                    spot = c;
                                    break;
                                }
                            }
                        }
                    }
                }
                if (spot != null) {
                    added.add(spot);
                    taken.add(spot.key());
                }
            }
        }
        List<Cell> plant = new ArrayList<>();
        int dry = 0;
        List<Cell> allWater = new ArrayList<>(water);
        allWater.addAll(added);
        // rows back and forth, so the walk between cells stays short
        List<Cell> ordered = new ArrayList<>(field);
        ordered.sort((a, b) -> a.z() != b.z() ? Integer.compare(a.z(), b.z()) : ((a.z() - z1) % 2 == 0 ? Integer.compare(a.x(), b.x()) : Integer.compare(b.x(), a.x())));
        for (Cell c : ordered) {
            if (taken.contains(c.key())) continue;
            if (wet(c, allWater)) plant.add(c);
            else dry++;
        }
        return new Layout(added, plant, dry, blocked, planted, wanted);
    }

    static boolean wet(Cell c, List<Cell> water) {
        for (Cell w : water) if (near(c, w)) return true;
        return false;
    }

    /** The end line: "planted 40 wheat (tilled 42, watered 3), 12 left: no seeds". */
    public static String report(String crop, int planted, int tilled, int watered, int left, String why) {
        return "planted " + planted + " " + crop + " (tilled " + tilled + ", watered " + watered + ")"
                + (left > 0 ? ", " + left + " left: " + (why == null ? "not done" : why) : "");
    }
}
