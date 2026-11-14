package io.github.mojolowjo.entropybot.sim;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * sim step 2 (to-do/simulator-plan.md): the fake world's blocks. A base (a hand-built ground, or a {@link Scenario}'s
 * cube) with the changes the steps made on top. A cell the base does not hold is {@link Scenario#NOT_LOADED}: not known,
 * never air (rule 15). Block ids only (no block states yet: slice 3 reads a scenario's states down to their ids).
 */
final class SimWorld {
    static final String AIR = "minecraft:air";

    /** The base: the block id at a cell, {@link Scenario#NOT_LOADED} where it holds none. */
    interface Base { String at(int x, int y, int z); }

    private final Base base;
    private final Map<Long, String> changed = new HashMap<>();
    /** The box the sight scan covers (the base's known cells): x0 y0 z0 x1 y1 z1, both ends in. */
    final int[] box;

    SimWorld(Base base, int[] box) {
        this.base = base;
        this.box = box.clone();
    }

    /** A flat ground: {@code ground} at {@code y} and below (to y - 3), air over it, known within {@code r} of 0 0. */
    static SimWorld flat(int y, String ground, int r) {
        return new SimWorld((x, yy, z) -> Math.abs(x) > r || Math.abs(z) > r || yy < y - 3 || yy > y + 24 ? Scenario.NOT_LOADED : yy <= y ? ground : AIR,
                new int[]{-r, y - 3, -r, r, y + 24, r});
    }

    /**
     * Slice 3: a scenario's cube as the base - each cell its block's id (the state dropped: "minecraft:spruce_log[axis=y]"
     * is a spruce_log), a cell with no chunk and every cell outside the cube {@link Scenario#NOT_LOADED}.
     */
    static SimWorld of(Scenario s) {
        String[] ids = new String[s.size()];
        Map<String, String> seen = new HashMap<>();
        for (int y = 0; y < s.sy; y++) for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++) {
            String st = s.blockAt(s.x0 + x, s.y0 + y, s.z0 + z);
            ids[(y * s.sz + z) * s.sx + x] = seen.computeIfAbsent(st, t -> t.indexOf('[') < 0 ? t : t.substring(0, t.indexOf('[')));
        }
        return new SimWorld((x, y, z) -> {
            int i = s.index(x, y, z);
            return i < 0 ? Scenario.NOT_LOADED : ids[i];
        }, new int[]{s.x0, s.y0, s.z0, s.x0 + s.sx - 1, s.y0 + s.sy - 1, s.z0 + s.sz - 1});
    }

    /** A cell the body can stand in: it and the one over it open, something under it (not air, not unknown). */
    boolean standable(int x, int y, int z) {
        String under = at(x, y - 1, z);
        return open(x, y, z) && open(x, y + 1, z) && !AIR.equals(under) && !Scenario.NOT_LOADED.equals(under);
    }

    /** The standable cell of the column x z nearest {@code y}; null: none in the box (the walk's end is then said, never guessed). */
    Integer standY(int x, int y, int z) {
        for (int d = 0; d <= box[4] - box[1]; d++) {
            if (y + d <= box[4] && standable(x, y + d, z)) return y + d;
            if (d > 0 && y - d >= box[1] && standable(x, y - d, z)) return y - d;
        }
        return null;
    }

    /**
     * The cells the body reaches from {@code feet} with no block broken or placed: steps to the four sides, one up (head
     * room over the start) or up to three down. A rough stand-in for the fork's no-break search (step 3 replaces it): it
     * answers "is there an open way", not what the way costs.
     */
    Set<Long> reach(int[] feet) {
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> open = new ArrayDeque<>();
        if (!standable(feet[0], feet[1], feet[2])) return seen;
        open.add(feet.clone());
        seen.add(key(feet[0], feet[1], feet[2]));
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty()) {
            int[] c = open.poll();
            for (int[] s : sides) {
                int x = c[0] + s[0], z = c[2] + s[1];
                if (x < box[0] || x > box[3] || z < box[2] || z > box[5]) continue;
                for (int dy = 1; dy >= -3; dy--) {
                    int y = c[1] + dy;
                    if (dy == 1 && !open(c[0], c[1] + 2, c[2])) continue;          // no head room to step up
                    if (dy < 0 && !open(x, y + 2, z)) break;                        // the drop's column is closed
                    if (!standable(x, y, z)) continue;
                    if (seen.add(key(x, y, z))) open.add(new int[]{x, y, z});
                    break;
                }
            }
        }
        return seen;
    }

    static long cell(int[] c) { return key(c[0], c[1], c[2]); }

    private static long key(int x, int y, int z) { return ((long) (x + 30_000_000) << 38) | ((long) (z + 30_000_000) << 12) | (y + 2048); }   // structural: packs a cell

    String at(int x, int y, int z) {
        String c = changed.get(key(x, y, z));
        return c != null ? c : base.at(x, y, z);
    }

    void set(int x, int y, int z, String id) { changed.put(key(x, y, z), id); }

    /** A column of {@code id} from y0 up, {@code n} high (a tree's trunk). */
    void column(int x, int y0, int z, int n, String id) { for (int i = 0; i < n; i++) set(x, y0 + i, z, id); }

    boolean open(int x, int y, int z) { return AIR.equals(at(x, y, z)); }

    /**
     * The item a dug block gives, null: nothing. Hand data for now (the tests' item data has no loot tables): an ore its
     * ItemWorth drop, stone cobblestone, grass dirt, leaves nothing, any other block itself.
     */
    static String drop(String block) {
        if (block == null || block.equals(AIR) || block.equals(Scenario.NOT_LOADED)) return null;
        if (block.equals("minecraft:stone")) return "minecraft:cobblestone";
        if (block.equals("minecraft:grass_block")) return "minecraft:dirt";
        if (ItemWorth.hasTag(block, "minecraft:leaves")) return null;
        for (String item : ORE_ITEMS) if (block.equals(ItemWorth.oreDropping(item))) return item;
        return block;
    }

    private static final List<String> ORE_ITEMS = List.of("minecraft:raw_iron", "minecraft:raw_copper", "minecraft:raw_gold", "minecraft:coal",
            "minecraft:diamond", "minecraft:redstone", "minecraft:lapis_lazuli", "minecraft:emerald");

    /** One group of touching blocks of one id: its cells, nearest the asker first. */
    record Group(String block, List<int[]> cells) {}

    /**
     * The groups of touching blocks (26 neighbours) whose drop passes {@code gives}, within {@code sight} of {@code feet}
     * on each axis and inside the box. Each group's cells are sorted nearest the feet first.
     */
    List<Group> groups(int[] feet, int sight, java.util.function.Predicate<String> gives) {
        List<Group> out = new ArrayList<>();
        Set<Long> done = new HashSet<>();
        int x0 = Math.max(box[0], feet[0] - sight), y0 = Math.max(box[1], feet[1] - sight), z0 = Math.max(box[2], feet[2] - sight);
        int x1 = Math.min(box[3], feet[0] + sight), y1 = Math.min(box[4], feet[1] + sight), z1 = Math.min(box[5], feet[2] + sight);
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            if (done.contains(key(x, y, z))) continue;
            String id = at(x, y, z);
            String d = drop(id);
            if (d == null || !gives.test(d)) continue;
            List<int[]> cells = new ArrayList<>();
            ArrayDeque<int[]> open = new ArrayDeque<>();
            open.add(new int[]{x, y, z});
            done.add(key(x, y, z));
            while (!open.isEmpty()) {
                int[] c = open.poll();
                cells.add(c);
                for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = c[0] + dx, ny = c[1] + dy, nz = c[2] + dz;
                    if (nx < x0 || nx > x1 || ny < y0 || ny > y1 || nz < z0 || nz > z1) continue;
                    if (done.contains(key(nx, ny, nz)) || !id.equals(at(nx, ny, nz))) continue;
                    done.add(key(nx, ny, nz));
                    open.add(new int[]{nx, ny, nz});
                }
            }
            cells.sort(java.util.Comparator.comparingDouble(c -> dist(c, feet)));
            out.add(new Group(id, cells));
        }
        out.sort(java.util.Comparator.comparingDouble(g -> dist(g.cells().get(0), feet)));
        return out;
    }

    static double dist(int[] a, int[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
