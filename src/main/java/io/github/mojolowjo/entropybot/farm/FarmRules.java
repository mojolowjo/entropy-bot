package io.github.mojolowjo.entropybot.farm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The farm's pure rules, ported from the bridge (claude_bridge.js: cropAt, farmCrops, farmCropsNow, squatReach,
 * inSquatRange, farmDrops, dropStandSpot, farmStandSpot, harmlessHand/holdHarmless). Squat Grow grows crops next to
 * a crouching player; Harvest with Ease harvests and replants a ripe crop on a right-click.
 */
public final class FarmRules {
    private FarmRules() {}

    /** Crops within this many blocks of the farm spot. */
    public static final int FARM_R = 6;
    /** Longest twerk in one round, in seconds. */
    public static final int FARM_GROW_S = 60;
    public static final String FARM_ESS = "mysticalagriculture:inferium_essence";
    public static final String FARM_BLOCK = "mysticalagriculture:inferium_block";
    public static final String FARM_PRUD = "mysticalagriculture:prudentium_essence";
    /** Squat Grow's "range" (pack/config/squatgrow-common.yaml): crops this close to the crouching player grow. */
    public static final int SQUAT_R = 3;
    /** How many spots a round twerks at (each up to FARM_GROW_S s) so Squat Grow reaches the whole farm (owner, 2026-10-03). */
    public static final int FARM_SPOTS = 4;
    /** A round that ends with this many free bag slots or fewer puts things away at the base. */
    public static final int FARM_FREE = 4;
    /** How far the harvest right-click reaches (eye to the crop's center). */
    public static final double REACH = 4.3;

    /** A crop: a block with an "age" standing on farmland. Ripe when age >= max. */
    public record Crop(int x, int y, int z, int age, int max) {
        public boolean ripe() { return age >= max; }

        public String key() { return x + " " + y + " " + z; }

        public int[] pos() { return new int[]{x, y, z}; }
    }

    /** The crop at x y z (a block with an "age" on farmland, not a melon or pumpkin stem), else null. */
    public static Crop cropAt(FarmWorld w, int x, int y, int z) {
        if (w.isAir(x, y, z) || !String.valueOf(w.blockId(x, y - 1, z)).contains("farmland")) return null;
        // melon and pumpkin stems have an age too, but a right-click never harvests them
        if (String.valueOf(w.blockId(x, y, z)).endsWith("_stem")) return null;
        FarmWorld.Age a = w.age(x, y, z);
        return a == null ? null : new Crop(x, y, z, a.age(), a.max());
    }

    /** The crops within FARM_R blocks (and 2 up or down) of center. */
    public static List<Crop> farmCrops(FarmWorld w, int[] center) {
        List<Crop> out = new ArrayList<>();
        for (int x = center[0] - FARM_R; x <= center[0] + FARM_R; x++) {
            for (int z = center[2] - FARM_R; z <= center[2] + FARM_R; z++) {
                for (int y = center[1] - 2; y <= center[1] + 2; y++) {
                    Crop c = cropAt(w, x, y, z);
                    if (c != null) out.add(c);
                }
            }
        }
        return out;
    }

    /** This round's crop spots that are ripe (wantRipe) or still growing, as they are now. */
    public static List<Crop> cropsNow(FarmWorld w, List<int[]> spots, boolean wantRipe) {
        List<Crop> out = new ArrayList<>();
        for (int[] s : spots) {
            Crop c = cropAt(w, s[0], s[1], s[2]);
            if (c != null && c.ripe() == wantRipe) out.add(c);
        }
        return out;
    }

    /** How many of these crops a player whose feet are at p makes grow by crouching (Squat Grow's range is a cube). */
    public static int squatReach(int[] p, List<int[]> crops) {
        int n = 0;
        for (int[] c : crops) {
            if (Math.abs(c[0] - p[0]) <= SQUAT_R && Math.abs(c[1] - p[1]) <= SQUAT_R && Math.abs(c[2] - p[2]) <= SQUAT_R) n++;
        }
        return n;
    }

    public static boolean inSquatRange(int[] p, List<int[]> crops) {
        return squatReach(p, crops) == crops.size();
    }

    public static long distSq(int[] a, int[] b) {
        long dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    /** A spot to stand on: x y z, how many crops it reaches (n), whether it is a crop's own cell, its distSq to the bot. */
    public record Stand(int x, int y, int z, int n, int onCrop, long d) {
        public int[] pos() { return new int[]{x, y, z}; }

        public String fmt() { return x + " " + y + " " + z; }
    }

    /**
     * Where to crouch: the free spot that reaches the most crops (all of them on a farm up to 7 wide), then one that
     * isn't a crop's own cell (standing among them is fine, but the path there may cross the farm), then the nearest
     * to the bot. Null when no standable cell reaches any.
     */
    public static Stand farmStandSpot(FarmWorld w, List<int[]> crops) { return farmStandSpot(w, crops, Set.of()); }

    /**
     * As above, never one of {@code skip} ("x y z": spots the bot couldn't get to this round). Any crop's cell counts
     * as "on a crop" for the tie-break, not only the crops asked about (0.8.2).
     */
    public static Stand farmStandSpot(FarmWorld w, List<int[]> crops, Set<String> skip) {
        int[] me = w.here();
        int x1 = Integer.MAX_VALUE, x2 = Integer.MIN_VALUE, y1 = Integer.MAX_VALUE, y2 = Integer.MIN_VALUE, z1 = Integer.MAX_VALUE, z2 = Integer.MIN_VALUE;
        Set<String> cells = new HashSet<>();
        for (int[] c : crops) {
            x1 = Math.min(x1, c[0]); x2 = Math.max(x2, c[0]);
            y1 = Math.min(y1, c[1]); y2 = Math.max(y2, c[1]);
            z1 = Math.min(z1, c[2]); z2 = Math.max(z2, c[2]);
            cells.add(c[0] + " " + c[1] + " " + c[2]);
        }
        Stand best = null;
        for (int x = x1 - SQUAT_R; x <= x2 + SQUAT_R; x++) {
            for (int z = z1 - SQUAT_R; z <= z2 + SQUAT_R; z++) {
                // the bridge's range: from one below the highest crop to one above the lowest
                for (int y = y2 - 1; y <= y1 + 1; y++) {
                    int[] p = {x, y, z};
                    int n = squatReach(p, crops);
                    if (n == 0 || (best != null && n < best.n())) continue;
                    if (!w.standable(x, y, z) || skip.contains(x + " " + y + " " + z)) continue;
                    int onCrop = cells.contains(x + " " + y + " " + z) || cropAt(w, x, y, z) != null ? 1 : 0;
                    long d = distSq(me, p);
                    if (best == null || n > best.n()
                            || (n == best.n() && (onCrop < best.onCrop() || (onCrop == best.onCrop() && d < best.d())))) {
                        best = new Stand(x, y, z, n, onCrop, d);
                    }
                }
            }
        }
        return best;
    }

    /** The farm's item drops: those within FARM_R + 1 of the farm spot (x and z, from its block's center) and 3 up or down. */
    public static List<FarmWorld.Drop> farmDrops(FarmWorld w, FarmSpot f) {
        List<FarmWorld.Drop> out = new ArrayList<>();
        for (FarmWorld.Drop e : w.drops()) {
            if (Math.abs(e.x() - f.x() - 0.5) > FARM_R + 1 || Math.abs(e.z() - f.z() - 0.5) > FARM_R + 1 || Math.abs(e.y() - f.y()) > 3) continue;
            out.add(e);
        }
        return out;
    }

    /** The drop's distance to the bot (entity to entity, like e.distanceTo(player)). */
    public static double dropDist(FarmWorld w, FarmWorld.Drop e) {
        double[] p = w.pos();
        double dx = e.x() - p[0], dy = e.y() - p[1], dz = e.z() - p[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /**
     * Where to stand to pick up a drop: its own block if the bot can stand there (not on the second try, notOwn),
     * else the free block next to it that is nearest to the bot (a level up or down counts 4 more). Null: walk near it.
     */
    public static int[] dropStandSpot(FarmWorld w, FarmWorld.Drop e, boolean notOwn) {
        int[] me = w.here();
        int x = (int) Math.floor(e.x()), y = (int) Math.floor(e.y() + 0.5), z = (int) Math.floor(e.z());
        if (!notOwn && w.standable(x, y, z)) return new int[]{x, y, z};
        int[] best = null;
        long bestD = 0;
        for (int[] s : SIDES) {
            for (int dy = -1; dy <= 1; dy++) {
                int[] c = {x + s[0], y + dy, z + s[1]};
                if (!w.standable(c[0], c[1], c[2])) continue;
                long d = distSq(me, c) + (dy != 0 ? 4 : 0);
                if (best == null || d < bestD) {
                    best = c;
                    bestD = d;
                }
            }
        }
        return best;
    }

    /** How the harvest gets a harmless main hand: "held" (it has one), "select" a hotbar slot, or "swap" a bag slot into the hand. */
    public record Hand(String kind, int slot) {
        public static final Hand HELD = new Hand("held", -1);
    }

    /** True for a sword or nothing: anything else would be used on the crop (a block placed, seeds or bone meal used up). */
    public static boolean harmless(String id) {
        return id == null || id.isEmpty() || id.equals("minecraft:air") || id.endsWith("_sword");
    }

    /**
     * The bridge's holdHarmless as a decision: the hand is fine as it is; else a sword (selected in the hotbar, or
     * swapped in from the bag); else an empty hotbar slot; else the held item swapped into an empty bag slot. Null:
     * no sword and no empty slot.
     */
    public static Hand harmlessHand(List<String> slots, int selected) {
        if (harmless(slots.get(selected))) return Hand.HELD;
        for (int i = 0; i < 36 && i < slots.size(); i++) {
            String id = slots.get(i);
            if (id != null && id.endsWith("_sword")) return i < 9 ? new Hand("select", i) : new Hand("swap", i);
        }
        for (int i = 0; i < 9 && i < slots.size(); i++) if (empty(slots.get(i))) return new Hand("select", i);
        for (int i = 9; i < 36 && i < slots.size(); i++) if (empty(slots.get(i))) return new Hand("swap", i);
        return null;
    }

    static boolean empty(String id) { return id == null || id.isEmpty() || id.equals("minecraft:air"); }

    /** The bag's empty slots (of 36). */
    public static int freeSlots(List<String> slots) {
        int n = 0;
        for (int i = 0; i < 36 && i < slots.size(); i++) if (empty(slots.get(i))) n++;
        return n;
    }

    /** "farm here": the farm spot for these crops (their average x and z, the first crop's y). */
    public static FarmSpot spotFor(List<Crop> crops, String dim, String compact) {
        long sx = 0, sz = 0;
        for (Crop c : crops) {
            sx += c.x();
            sz += c.z();
        }
        return new FarmSpot((int) Math.round((double) sx / crops.size()), crops.get(0).y(), (int) Math.round((double) sz / crops.size()), dim, compact);
    }

    public static List<int[]> positions(List<Crop> crops) {
        List<int[]> out = new ArrayList<>();
        for (Crop c : crops) out.add(c.pos());
        return out;
    }
}
