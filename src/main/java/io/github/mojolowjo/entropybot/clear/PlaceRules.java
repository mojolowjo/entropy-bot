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
        return !OPENS_ON_CLICK.matcher(w.name(x, y, z)).find();
    }

    /** B7e F: compiled once (the floor fill asks this thousands of times per decision). */
    private static final java.util.regex.Pattern OPENS_ON_CLICK = java.util.regex.Pattern.compile("crafting|table|bench");

    /** The cell takes a block: air, or something without collision that isn't a fluid source we'd care about (grass). */
    public static boolean free(ClearWorld w, int x, int y, int z) {
        return w.air(x, y, z) || (w.noCollision(x, y, z) && !w.fluid(x, y, z) && !w.blockEntity(x, y, z));
    }

    /** placeAt's choice: the first neighbour (in SIDES order) to click from an eye at ex ey ez, or null (nothing in reach). */
    public static Side placeSide(ClearWorld w, int x, int y, int z, double ex, double ey, double ez) {
        return placeSide(w, x, y, z, ex, ey, ez, PLACE_REACH);
    }

    /**
     * 0.20.1 (C4 live test): a stand spot is chosen with this shorter reach, because Baritone ends a walk anywhere on the
     * goal block (seen live: 0.2 off the centre), and a spot right at 4.5 then failed "nothing in reach" after the walk.
     */
    public static final double STAND_REACH = 4.0;

    /** placeSide with the given reach. */
    public static Side placeSide(ClearWorld w, int x, int y, int z, double ex, double ey, double ez, double reach) {
        for (Side s : SIDES) {
            if (!clickable(w, x + s.dx(), y + s.dy(), z + s.dz())) continue;
            double hx = x + 0.5 + s.dx() * 0.5, hy = y + 0.5 + s.dy() * 0.5, hz = z + 0.5 + s.dz() * 0.5;
            double dx = hx - ex, dy = hy - ey, dz = hz - ez;
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
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
        return standFor(w, cell, bot, null);
    }

    /** T3: as above, only spots {@code ok} accepts (the owner's areas while the fence is on; null: any). */
    public static ClearGrid.Spot standFor(ClearWorld w, Pos cell, Bot bot, ClearJob.StandCheck ok) {
        ClearBox box = new ClearBox(cell.x(), cell.y(), cell.z(), cell.x(), cell.y(), cell.z());
        ClearGrid g = ClearGrid.build(w, box, bot, null);
        int[] dist = g.walkDistances(bot);
        ClearGrid.Spot best = null;
        for (int x = cell.x() - 4; x <= cell.x() + 4; x++) {
            for (int z = cell.z() - 4; z <= cell.z() + 4; z++) {
                for (int y = cell.y() - 4; y <= cell.y() + 3; y++) {
                    if (bodyIn(x, y, z, cell)) continue;
                    if (ok != null && !ok.ok(x, y, z)) continue;
                    int i = g.idx(x, y, z);
                    if (i < 0) continue;
                    double cost;
                    if (dist[i] >= 0) cost = dist[i];
                    else if (g.stand(x, y, z)) cost = 1000 + Math.sqrt((x - bot.x()) * (x - bot.x()) + (y - bot.y()) * (y - bot.y()) + (z - bot.z()) * (z - bot.z()));
                    else continue;
                    if (best != null && cost >= best.cost()) continue;
                    double ex = x + 0.5, ey = y + Bot.EYE, ez = z + 0.5;
                    if (placeSide(w, cell.x(), cell.y(), cell.z(), ex, ey, ez, STAND_REACH) == null) continue;
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
        return tableSpot(w, bot, avoid, null);
    }

    /**
     * T2 (2026-10-04): as above, and only cells {@code allowed} accepts (the owner's areas while the fence is on; null:
     * any), and among the fitting cells the one at the side of the walkway: a cell against a wall (more solid sides at
     * its own level) keeps the corridor open even if the table can't be taken back. Outside {@code avoid} still comes
     * first; ties go by the order above.
     */
    public static Pos tableSpot(ClearWorld w, Bot bot, ClearBox avoid, java.util.function.Predicate<Pos> allowed) {
        int bx = (int) Math.floor(bot.x()), by = (int) Math.floor(bot.y() + 0.01), bz = (int) Math.floor(bot.z());
        int[][] offs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 },
                { 2, 0 }, { -2, 0 }, { 0, 2 }, { 0, -2 } };
        Pos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int[] o : offs) {
            int x = bx + o[0], z = bz + o[1];
            // the bot's own body: its feet cell may overlap a neighbour when it stands near an edge
            if (Math.abs(x + 0.5 - bot.x()) < 0.8 && Math.abs(z + 0.5 - bot.z()) < 0.8) continue;
            if (!free(w, x, by, z) || !clickable(w, x, by - 1, z) || ClearEngine.nextToLiquid(w, x, by, z)) continue;
            Pos p = new Pos(x, by, z);
            if (allowed != null && !allowed.test(p)) continue;
            int score = (avoid != null && avoid.contains(x, by, z) ? 10 : 0) + (3 - Math.min(3, wallSides(w, x, by, z)));
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    /** T2: how many of the 4 sides of x y z hold something solid at the same level (a wall it stands against). */
    public static int wallSides(ClearWorld w, int x, int y, int z) {
        int n = 0;
        int[][] sides = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        for (int[] s : sides) if (!free(w, x + s[0], y, z + s[1])) n++;
        return n;
    }

    /** The label of the clear that takes the table back. */
    public static String pickupLabel(Pos p) {
        return "picking up my crafting table at " + p.key();
    }

    /** The only block the table pickup ever breaks. */
    public static final String TABLE_ID = "minecraft:crafting_table";

    /** placeAt's answer when the cell already holds the block (someone else's, as far as the bot knows). */
    public static final String ALREADY_THERE = "ok: already there";

    /** 0.30.7: what stands in a cell the bot wants to place into. */
    public enum InWay { MY_CORPSE, MY_EMPTY_CORPSE, MOB, OTHER }

    /** 0.30.7: a mob in the cell is waited for this long (ticks, 10 s) before the place step gives up. */
    public static final long MOB_WAIT_TICKS = 200;
    /** 0.30.7 (live probe): an emptied corpse the client still lists is waited for this long (90 s; it went in 10-50 s). */
    public static final long EMPTY_CORPSE_WAIT_TICKS = 1800;

    /** 0.30.7: how long a waiting text (entityText "... (waiting)") is waited for. */
    public static long waitTicks(String entityText) {
        return entityText != null && entityText.startsWith("error: my emptied corpse") ? EMPTY_CORPSE_WAIT_TICKS : MOB_WAIT_TICKS;
    }

    /** 0.30.7: the kind of an entity in the way (type id like "corpse:corpse"; mine: owned by the bot; living: a mob). */
    public static InWay inWayKind(String typeId, boolean mine, boolean living) {
        if ("corpse:corpse".equals(typeId) && mine) return InWay.MY_CORPSE;
        if (living && !"corpse:corpse".equals(typeId)) return InWay.MOB;
        return InWay.OTHER;
    }

    /**
     * 0.30.7 (live, shed3: the first death's corpse at 35 131 23 made "place furnace 34 131 23" fail three times with
     * "the game refused ..."): placeAt's error for an entity in the cell. name: the entity's short type ("cow", "boat").
     * A mob's text ends "(waiting)": placeTick waits MOB_WAIT_TICKS for it before the step fails.
     */
    public static String entityText(InWay kind, String name, int x, int y, int z) {
        String at = " at " + x + " " + y + " " + z;
        return switch (kind) {
            case MY_CORPSE -> "error: my corpse is in the way" + at + " - next: death";
            // live probe (shed3): an emptied corpse is gone on the server at once, but the client lists it (and refuses
            // the click) for 10-50 s more: waited for like a mob, longer (EMPTY_CORPSE_WAIT_TICKS)
            case MY_EMPTY_CORPSE -> "error: my emptied corpse is in the way" + at + " (waiting)";
            case MOB -> "error: a " + name + " is in the way" + at + " (waiting)";
            case OTHER -> "error: a " + name + " is in the way" + at + " - next: move it, or place somewhere else";
        };
    }

    /** 0.30.7: what a waiting text names ("a cow", "my emptied corpse"), from entityText's answer. */
    public static String waitedFor(String entityText) {
        return entityText.replaceFirst("^error: ", "").replaceFirst(" is in the way at .*$", "");
    }

    /** 0.30.7: the place step's end after waiting (waitTicks) for what did not move. what: "a cow". */
    public static String mobStayedText(String what, int x, int y, int z) {
        long s = (what.startsWith("my emptied corpse") ? EMPTY_CORPSE_WAIT_TICKS : MOB_WAIT_TICKS) / 20;
        return what + " stayed in the way at " + x + " " + y + " " + z + " for " + s + " s - next: place again when it has moved";
    }

    /**
     * B7d review 4b: a placement counts as the bot's own only when the cell was free (replaceable) just before its click
     * and the click really happened; "already there" never does.
     */
    public static boolean ourClick(boolean freeBefore, String placeResult) {
        return freeBefore && placeResult != null && placeResult.startsWith("ok: ") && !placeResult.equals(ALREADY_THERE);
    }

    /** 4b: the pickup is armed only after our own click on a free cell, with a crafting table right there afterwards. */
    public static boolean armsTable(boolean ourClick, String idAfter) {
        return ourClick && TABLE_ID.equals(idAfter);
    }

    /** 4a: the pickup may break the cell only while it is armed and the block is exactly a crafting table. */
    public static boolean pickupMayBreak(boolean armed, String id) {
        return armed && TABLE_ID.equals(id);
    }

    /** The only block the furnace pickup ever breaks (a furnace "smelt ... here" put down itself). */
    public static final String FURNACE_ID = "minecraft:furnace";

    /** The label of the clear that takes my own furnace back. */
    public static String furnacePickupLabel(Pos p) {
        return "picking up my furnace at " + p.key();
    }

    /** The pickup block of a pickup step: its own id, the crafting table when none is set. */
    public static String pickupBlock(String stepId) {
        return stepId != null ? stepId : TABLE_ID;
    }

    /** "crafting table" / "furnace": what a pickup of this block id is called in notes. */
    public static String pickupName(String blockId) {
        return FURNACE_ID.equals(blockId) ? "furnace" : "crafting table";
    }

    /** Like {@link #armsTable} for any station: our own click on a free cell, exactly the expected block there afterwards. */
    public static boolean armsStation(boolean ourClick, String idAfter, String expected) {
        return ourClick && expected != null && expected.equals(idAfter);
    }

    /** Like {@link #pickupMayBreak} for any station: armed, and the block is exactly the expected one. */
    public static boolean pickupMayBreak(boolean armed, String id, String expected) {
        return armed && expected != null && expected.equals(id);
    }

    /**
     * B7d review 3: a trip step failed and the steps from it up to the clear go. {@code removed.get(0)} is the step that
     * failed; the armed table pickups among the others stay (in order), so the bot still takes its table back.
     */
    public static <T> List<T> keptOnCatch(List<T> removed, java.util.function.Predicate<T> armedPickup) {
        List<T> out = new ArrayList<>();
        for (int i = 1; i < removed.size(); i++) {
            if (armedPickup.test(removed.get(i))) out.add(removed.get(i));
        }
        return out;
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

    /**
     * T1 (2026-10-04): the place lease for one placement is only what the guard checks. A block item goes through
     * BlockItem.place, which checks the cell alone (Guard.vetoPlace on the context's clicked position, already the cell
     * the block goes into); a bucket, fire or egg goes through useItemOn, which checks the cell and the clicked block
     * (Guard.vetoUseOn). The old 3x3x3 never fit a 3-wide area (the tunnel x -110..8591, z 853..855, y -46..-44).
     *
     * @param side the neighbour that gets clicked (null: not known yet, the cell alone)
     * @param blockItem the item in hand is a BlockItem
     */
    public static ClearBox placeLeaseBox(int x, int y, int z, Side side, boolean blockItem) {
        if (blockItem || side == null) return new ClearBox(x, y, z, x, y, z);
        int cx = x + side.dx(), cy = y + side.dy(), cz = z + side.dz();
        return new ClearBox(Math.min(x, cx), Math.min(y, cy), Math.min(z, cz), Math.max(x, cx), Math.max(y, cy), Math.max(z, cz));
    }

    /** The place lease of a block item: the cell alone. */
    public static ClearBox placeLeaseBox(int x, int y, int z) {
        return placeLeaseBox(x, y, z, null, true);
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
