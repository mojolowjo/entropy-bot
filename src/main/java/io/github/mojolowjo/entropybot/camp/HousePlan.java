package io.github.mojolowjo.entropybot.camp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 0.28.2 (trial fix package 2, C): {@code house <area> [block]}, a chain of existing commands that turns an area box
 * into a small house: floor, walls, roof, a 1x2 door gap on the side facing the camp (else south), and inside along the
 * back wall a chest, a crafting table and a furnace, the base marked at the inside centre, food marked at the chest, and
 * a {@code scan base}. Each step is a normal command, so a failed one stops the chain with its own reason; a rerun
 * skips what stands ({@code build} ignores standing blocks, a station inside is not placed again). Pure (JUnit:
 * {@code HousePlanTest}).
 *
 * <p>Loader notes: none (no game classes). The game side is {@code commands.CampCommands.house}.
 */
public final class HousePlan {
    private HousePlan() {}

    /** The smallest area: a 5x5 inside with 1-thick walls, floor and roof. */
    public static final int MIN_SIDE = 7, MIN_HEIGHT = 5;

    public static final String TOO_SMALL = "error: a 5x5 inside needs a 7x7 area 5 high: area x z x2 z2 house main y1 y2 - next: that, then house house";

    /**
     * What the planner looks at. box: x1 y1 z1 x2 y2 z2 (the area, any corner order); hasHeights: the area was given
     * y1..y2; inv: id -&gt; count; standing: id -&gt; true when that station already stands inside the house (chest,
     * crafting_table, furnace); camp: the camp mark (x y z) or null (the door faces it).
     */
    public record Facts(String area, int[] box, boolean hasHeights, String block, Map<String, Integer> inv,
                        Map<String, Boolean> standing, int[] camp) {}

    /** The chain, the counts named up front, or why not. */
    public record Plan(List<String> steps, String summary, String err) {}

    public static final String[] STATIONS = {"chest", "crafting_table", "furnace"};
    /** standing key: the chosen block already stands in the house's shell (a rerun). */
    public static final String SHELL = "shell";
    /** A box dig over this many cells asks for confirm: the house splits it per layer instead. */
    public static final int DIG_MAX = 1000;

    public static Plan plan(Facts f) {
        if (!f.hasHeights() || f.box() == null) return new Plan(List.of(), null, TOO_SMALL);
        int[] b = f.box();
        int x1 = Math.min(b[0], b[3]), x2 = Math.max(b[0], b[3]);
        int y1 = Math.min(b[1], b[4]), y2 = Math.max(b[1], b[4]);
        int z1 = Math.min(b[2], b[5]), z2 = Math.max(b[2], b[5]);
        if (x2 - x1 + 1 < MIN_SIDE || z2 - z1 + 1 < MIN_SIDE || y2 - y1 + 1 < MIN_HEIGHT) return new Plan(List.of(), null, TOO_SMALL);
        String area = f.area(), blk = f.block();
        int cx = (x1 + x2) / 2, cz = (z1 + z2) / 2, iy = y1 + 1;
        // the door side: toward the camp, else south (+z)
        char side = doorSide(cx, cz, f.camp());
        int[] door = switch (side) {
            case 'n' -> new int[]{cx, z1};
            case 'e' -> new int[]{x2, cz};
            case 'w' -> new int[]{x1, cz};
            default -> new int[]{cx, z2};
        };
        // the stations against the back wall (opposite the door), side by side at the middle
        int[][] st = new int[3][];
        for (int i = 0; i < 3; i++) {
            int o = i - 1;
            st[i] = switch (side) {
                case 'n' -> new int[]{cx + o, iy, z2 - 1};
                case 'e' -> new int[]{x1 + 1, iy, cz + o};
                case 'w' -> new int[]{x2 - 1, iy, cz + o};
                default -> new int[]{cx + o, iy, z1 + 1};
            };
        }
        int need = need(x1, y1, z1, x2, y2, z2);
        int have = f.inv().getOrDefault(blk, 0);
        List<String> out = new ArrayList<>();
        String b6 = shortId(blk);
        out.add("build floor " + b6 + " " + area);
        // 0.30.2 (trial 8 hillside): clear the whole box above the floor (wall line, interior, roof line) before the
        // walls, so the builder and the rim placer find free wall cells and somewhere to stand. No force: dig ... force
        // is capped at 64 blocks (DigCommands), and without it the hill's snow and dirt go while the walls and roof
        // a former run built (planks) stay. A box with nothing left ends "ok: done ... broke 0 blocks" (the chain goes on).
        // review c: on a rerun (the chosen block already stands in the shell, standing "shell") only the inside is dug,
        // so a cobblestone shell (not a protected block) is never taken down; a box over 1000 cells goes per layer
        boolean rerun = Boolean.TRUE.equals(f.standing().get(SHELL));
        int dx1 = rerun ? x1 + 1 : x1, dx2 = rerun ? x2 - 1 : x2, dz1 = rerun ? z1 + 1 : z1, dz2 = rerun ? z2 - 1 : z2, dy2 = rerun ? y2 - 1 : y2;
        if (dy2 >= iy) {
            long vol = (long) (dx2 - dx1 + 1) * (dy2 - iy + 1) * (dz2 - dz1 + 1);
            if (vol <= DIG_MAX) out.add("dig " + dx1 + " " + iy + " " + dz1 + " " + dx2 + " " + dy2 + " " + dz2);
            else for (int y = dy2; y >= iy; y--) out.add("dig " + dx1 + " " + y + " " + dz1 + " " + dx2 + " " + y + " " + dz2);
        }
        // the door's two cells are left out of the walls (the door dig below cuts them once; a rerun leaves the gap)
        out.add("build walls " + b6 + " " + area + " door " + door[0] + " " + door[1]);
        out.add("build roof " + b6 + " " + area);
        String d = door[0] + " " + iy + " " + door[1] + " " + door[0] + " " + (iy + 1) + " " + door[1];
        out.add("dig " + d + " force");
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String s = STATIONS[i];
            if (Boolean.TRUE.equals(f.standing().get(s))) continue;
            if (f.inv().getOrDefault("minecraft:" + s, 0) <= 0) {
                out.add("craft " + s + " 1");
                missing.add(s);
            }
            out.add("place " + s + " " + fmt(st[i]));
        }
        out.add("place base " + cx + " " + iy + " " + cz);
        out.add("place food " + fmt(st[0]));
        out.add("scan base");
        String summary = "house in " + area + ": " + out.size() + " steps, needs about " + need + " " + b6 + " (you have " + have + ")"
                + (have < need ? " - gather " + b6 + " " + (need - have) + " if it runs out" : "")
                + ", door on the " + sideName(side) + " side"
                + (missing.isEmpty() ? "" : ", crafts " + String.join(", ", missing) + " first");
        return new Plan(out, summary, null);
    }

    /** Blocks for floor + walls + roof (the shell) less the two door cells. */
    public static int need(int x1, int y1, int z1, int x2, int y2, int z2) {
        return BuildCount.need("shell", x1, y1, z1, x2, y2, z2) - 2;
    }

    /** n/e/s/w toward the camp from the house centre (the larger axis wins); south when no camp. */
    static char doorSide(int cx, int cz, int[] camp) {
        if (camp == null) return 's';
        int dx = camp[0] - cx, dz = camp[2] - cz;
        if (dx == 0 && dz == 0) return 's';
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? 'e' : 'w';
        return dz > 0 ? 's' : 'n';
    }

    static String sideName(char s) {
        return switch (s) {
            case 'n' -> "north";
            case 'e' -> "east";
            case 'w' -> "west";
            default -> "south";
        };
    }

    /** The block for the house: the one asked for, else planks when it carries more planks than cobblestone. */
    public static String chooseBlock(String asked, Map<String, Integer> inv) {
        return chooseBlock(asked, inv, Map.of());
    }

    /**
     * 0.30.2 (trial 8: plank walls, then cobblestone walls on the rerun): with nothing asked, the block that already
     * stands most in the house's shape (built blocks the caller counted: id -> cells) is kept; the bag decides only when
     * nothing stands. Ties go to the bag's choice when it is among them, else the first by name.
     */
    public static String chooseBlock(String asked, Map<String, Integer> inv, Map<String, Integer> placed) {
        if ((asked == null || asked.isBlank()) && placed != null && !placed.isEmpty()) {
            String bag = chooseBlock(null, inv, null);
            int top = placed.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            if (top > 0) {
                if (placed.getOrDefault(bag, 0) == top) return bag;
                return placed.entrySet().stream().filter(e -> e.getValue() == top).map(Map.Entry::getKey).sorted().findFirst().get();
            }
        }
        if (asked != null && !asked.isBlank()) return asked.contains(":") ? asked : "minecraft:" + asked;
        String bestPlank = null;
        int planks = 0;
        for (Map.Entry<String, Integer> e : inv.entrySet()) {
            if (e.getKey().endsWith("_planks") && e.getValue() > planks) {
                planks = e.getValue();
                bestPlank = e.getKey();
            }
        }
        int cobble = inv.getOrDefault("minecraft:cobblestone", 0);
        return bestPlank != null && planks > cobble ? bestPlank : "minecraft:cobblestone";
    }

    /**
     * 0.30.2: block id {@code id} is station {@code word} (crafting_table, furnace, chest) whatever mod replaced it:
     * the part after the namespace and any path ("visualworkbench:minecraft/crafting_table") ends with the word.
     */
    public static boolean isStation(String id, String word) {
        if (id == null || word == null) return false;
        String s = id.substring(id.indexOf(':') + 1);
        s = s.substring(s.lastIndexOf('/') + 1);
        if (s.startsWith("ender_")) return false;                  // an ender chest is no storage of the base
        return s.equals(word) || s.endsWith("_" + word);
    }

    private static final java.util.regex.Pattern NOT_BUILDING = java.util.regex.Pattern.compile(
            "torch|door|glass|pane|ladder|sign|stairs|slab|fence|wall_|button|pressure_plate|carpet|bed|lantern|rail");

    /** review d: a full building block by name (the caller checks the full collision shape): no torches, doors, glass... */
    public static boolean buildingBlock(String id) {
        return id != null && !NOT_BUILDING.matcher(id).find();
    }

    static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    static String fmt(int[] p) {
        return p[0] + " " + p[1] + " " + p[2];
    }
}
