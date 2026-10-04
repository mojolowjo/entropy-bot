package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayList;
import java.util.List;

/**
 * B7d D1: the placing rules (the bridge's placeAt and seq "place" step, plus what the mod adds: walking into reach and
 * the crafting table it puts down for itself when its pickaxes run out far from a table, TO-LOOK-AT-LATER 18a), and
 * the lease slicing (the bridge's leaseSlices). Game-free.
 */
public final class PlaceRules {
    private PlaceRules() {}

    /** A neighbour to click: its offset from the cell and the face of the neighbour that points at the cell. */
    public record Side(int dx, int dy, int dz, ClearWorld.Face face) {}

    /** The floor first, then the walls, then the ceiling (placeAt's order). */
    public static final List<Side> SIDES = List.of(
            new Side(0, -1, 0, ClearWorld.Face.UP), new Side(1, 0, 0, ClearWorld.Face.WEST), new Side(-1, 0, 0, ClearWorld.Face.EAST),
            new Side(0, 0, 1, ClearWorld.Face.NORTH), new Side(0, 0, -1, ClearWorld.Face.SOUTH), new Side(0, 1, 0, ClearWorld.Face.DOWN));

    /** Reach from the eye to the clicked point (placeAt's 4.5). */
    public static final double PLACE_REACH = 4.5;

    /** A neighbour may be clicked: a full block, no block entity, nothing that opens on a click (tables, benches). */
    public static boolean clickable(ClearWorld w, int x, int y, int z) {
        if (!w.fullBlock(x, y, z) || w.blockEntity(x, y, z)) return false;
        return !w.name(x, y, z).matches(".*(crafting|table|bench).*");
    }

    /** The cell takes a block: air, or something without collision that isn't a fluid source we'd care about (grass). */
    public static boolean free(ClearWorld w, int x, int y, int z) {
        return w.air(x, y, z) || (w.noCollision(x, y, z) && !w.fluid(x, y, z) && !w.blockEntity(x, y, z));
    }

    /** placeAt's choice: the first neighbour (in SIDES order) to click from an eye at ex ey ez, or null (nothing in reach). */
    public static Side placeSide(ClearWorld w, int x, int y, int z, double ex, double ey, double ez) {
        for (Side s : SIDES) {
            if (!clickable(w, x + s.dx(), y + s.dy(), z + s.dz())) continue;
            double hx = x + 0.5 + s.dx() * 0.5, hy = y + 0.5 + s.dy() * 0.5, hz = z + 0.5 + s.dz() * 0.5;
            double dx = hx - ex, dy = hy - ey, dz = hz - ez;
            if (dx * dx + dy * dy + dz * dz > PLACE_REACH * PLACE_REACH) continue;
            return s;
        }
        return null;
    }

    /** Standing with the feet at sx sy sz would put the bot's body in the cell (it can't place a block into itself). */
    public static boolean bodyIn(int sx, int sy, int sz, Pos cell) {
        return sx == cell.x() && sz == cell.z() && (sy == cell.y() || sy + 1 == cell.y());
    }

    /**
     * Where to walk so the cell can be placed: the reachable stand spot (fewest steps, ClearGrid.walkDistances) from
     * which a neighbour is in reach, never one whose body would be in the cell; spots it can't walk to count after the
     * others (Baritone may find a way). Null when there is none within 5 blocks.
     */
    public static ClearGrid.Spot standFor(ClearWorld w, Pos cell, Bot bot) {
        ClearBox box = new ClearBox(cell.x(), cell.y(), cell.z(), cell.x(), cell.y(), cell.z());
        ClearGrid g = ClearGrid.build(w, box, bot, null);
        int[] dist = g.walkDistances(bot);
        ClearGrid.Spot best = null;
        for (int x = cell.x() - 4; x <= cell.x() + 4; x++) {
            for (int z = cell.z() - 4; z <= cell.z() + 4; z++) {
                for (int y = cell.y() - 4; y <= cell.y() + 3; y++) {
                    if (bodyIn(x, y, z, cell)) continue;
                    int i = g.idx(x, y, z);
                    if (i < 0) continue;
                    double cost;
                    if (dist[i] >= 0) cost = dist[i];
                    else if (g.stand(x, y, z)) cost = 1000 + Math.sqrt((x - bot.x()) * (x - bot.x()) + (y - bot.y()) * (y - bot.y()) + (z - bot.z()) * (z - bot.z()));
                    else continue;
                    if (best != null && cost >= best.cost()) continue;
                    double ex = x + 0.5, ey = y + Bot.EYE, ez = z + 0.5;
                    if (placeSide(w, cell.x(), cell.y(), cell.z(), ex, ey, ez) == null) continue;
                    best = new ClearGrid.Spot(x, y, z, cost, ex, ey, ez);
                }
            }
        }
        return best;
    }

    // ---- the bot's own crafting table (TO-LOOK-AT-LATER 18a) ----

    /** How far a table may be for the pickaxe craft to walk to it rather than put one down. */
    public static final int TABLE_NEAR = 24;

    /** What to do for a pickaxe craft that needs a table. */
    public enum TableChoice {
        /** a table within {@link #TABLE_NEAR}: the craft walks there */
        USE_NEAR,
        /** put down the table it carries, craft, pick it up again */
        PLACE_CARRIED,
        /** craft a table from its logs or planks first, then as PLACE_CARRIED */
        CRAFT_AND_PLACE,
        /** no wood: the craft walks to the far table (or says there is none) */
        WALK_FAR
    }

    /**
     * @param tableDist the distance to the table the craft would use (null: none known)
     * @param carriesTable the bot carries a crafting_table item
     * @param hasWood the bot carries logs or planks (4 planks make a table)
     */
    public static TableChoice tableChoice(Double tableDist, boolean carriesTable, boolean hasWood) {
        if (tableDist != null && tableDist <= TABLE_NEAR) return TableChoice.USE_NEAR;
        if (carriesTable) return TableChoice.PLACE_CARRIED;
        if (hasWood) return TableChoice.CRAFT_AND_PLACE;
        return TableChoice.WALK_FAR;
    }

    /** Logs or planks (any wood): ids ending _log, _planks, _stem, _hyphae (not stripped_ wood blocks? those count too). */
    public static boolean wood(String id) {
        return id != null && id.matches(".*(_log|_planks|_stem|_hyphae|_wood)$");
    }

    /**
     * Where to put the table down: a free cell next to the bot (its own feet level first, the 4 sides, then the
     * diagonals, then 2 away) with a clickable block under it, not where the bot stands, not next to water or lava,
     * outside {@code avoid} (the box being cleared) when there is such a cell, else inside it. Null when none fits.
     */
    public static Pos tableSpot(ClearWorld w, Bot bot, ClearBox avoid) {
        int bx = (int) Math.floor(bot.x()), by = (int) Math.floor(bot.y() + 0.01), bz = (int) Math.floor(bot.z());
        int[][] offs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 },
                { 2, 0 }, { -2, 0 }, { 0, 2 }, { 0, -2 } };
        List<Pos> inside = new ArrayList<>();
        for (int[] o : offs) {
            int x = bx + o[0], z = bz + o[1];
            // the bot's own body: its feet cell may overlap a neighbour when it stands near an edge
            if (Math.abs(x + 0.5 - bot.x()) < 0.8 && Math.abs(z + 0.5 - bot.z()) < 0.8) continue;
            if (!free(w, x, by, z) || !clickable(w, x, by - 1, z) || ClearEngine.nextToLiquid(w, x, by, z)) continue;
            Pos p = new Pos(x, by, z);
            if (avoid != null && avoid.contains(x, by, z)) {
                inside.add(p);
                continue;
            }
            return p;
        }
        return inside.isEmpty() ? null : inside.get(0);
    }

    /** The label of the clear that takes the table back. */
    public static String pickupLabel(Pos p) {
        return "picking up my crafting table at " + p.key();
    }

    // ---- leases ----

    /** The most one lease may cover (GuardCore.MAX_LEASE_VOLUME), and a force lease (MAX_FORCE_VOLUME). */
    public static final long LEASE_MAX = 4096, FORCE_MAX = 64;

    /** leaseSlices: a box cut in halves along its longest side until each part fits in one lease. */
    public static List<ClearBox> leaseSlices(ClearBox b, long max) {
        List<ClearBox> out = new ArrayList<>();
        slice(b, max, out);
        return out;
    }

    private static void slice(ClearBox b, long max, List<ClearBox> out) {
        int dx = b.x2() - b.x1() + 1, dy = b.y2() - b.y1() + 1, dz = b.z2() - b.z1() + 1;
        if ((long) dx * dy * dz <= max) {
            out.add(b);
            return;
        }
        ClearBox a, c;
        if (dy >= dx && dy >= dz) {
            int m = b.y1() + dy / 2 - 1;
            a = new ClearBox(b.x1(), b.y1(), b.z1(), b.x2(), m, b.z2());
            c = new ClearBox(b.x1(), m + 1, b.z1(), b.x2(), b.y2(), b.z2());
        } else if (dx >= dz) {
            int m = b.x1() + dx / 2 - 1;
            a = new ClearBox(b.x1(), b.y1(), b.z1(), m, b.y2(), b.z2());
            c = new ClearBox(m + 1, b.y1(), b.z1(), b.x2(), b.y2(), b.z2());
        } else {
            int m = b.z1() + dz / 2 - 1;
            a = new ClearBox(b.x1(), b.y1(), b.z1(), b.x2(), b.y2(), m);
            c = new ClearBox(b.x1(), b.y1(), m + 1, b.x2(), b.y2(), b.z2());
        }
        slice(a, max, out);
        slice(c, max, out);
    }

    /** The place lease around one cell (the guard checks the clicked block and the cell): the 3x3x3 box. */
    public static ClearBox placeLeaseBox(int x, int y, int z) {
        return new ClearBox(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1);
    }

    // ---- 18c: pickaxes before a big dig ----

    /** A dig bigger than this restocks its stone pickaxes first. */
    public static final long BIG_DIG = 300;
    public static final int STONE_PICKS_DEFAULT = 3;

    /** How many stone pickaxes to top up to before a dig of this volume: 0 = none (small dig, or enough already). */
    public static int picksToRestock(long volume, int have, Integer supplies) {
        if (volume <= BIG_DIG) return 0;
        int want = supplies != null && supplies > 0 ? supplies : STONE_PICKS_DEFAULT;
        return have >= want ? 0 : want;
    }
}
