package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.ToolWear;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AA_ROUND3 1 (Fable Design, aa388): ONE priced option set for "get up out of here", used by the SURFACE rung
 * ({@link RungOptions.Surface} is it), the stuck-under-ground escape (AaRuntime.startEscape; {@link AaEscape#choose} is a
 * thin call) and the way back after an ore step ({@link Replan#chooseNow} prices its pillar/tunnel here). Options:
 * <ul>
 * <li>{@code pillar}: my own open shaft, places x {@link Replan#UP_PLACE}; {@code tunnel}: breaks over the head
 *     ({@link Replan#tunnelUp}: own x1 / foreign x6 x the BreakPolicy multiplier, hazards, the cap) + the tool's dig
 *     seconds and wear ({@link ToolWear}).</li>
 * <li>{@code stairs <dir>} per heading: the cells {@code dig stairs up <dir> n} cuts ({@link #stairCells}), each
 *     priced own/foreign x multiplier + the tool's seconds and wear for the blocks; a heading with a cell outside my
 *     areas or one the policy denies is dropped with where; a heading that failed is held in Failures by its key.</li>
 * <li>{@code trail} (the walk's own trail back), {@code sky} (an open-sky cell near, by distance), {@code nothing}
 *     (the table/furnace rung walks to its own spot), and with no heading facts the old {@code climb} verb row.</li>
 * <li>wait: none fits = no best, the line says why (the caller fails the task with it).</li>
 * </ul>
 * Cost unit: walk-blocks (1 = 1 s of work). Pure (JUnit WayUpTest). Loader notes: none (cells are read in AaRuntime).
 */
public final class WayUp {
    private WayUp() {}

    public static final String STAIRS = "stairs ";
    public static final String[] DIRS = {"north", "south", "east", "west"};

    /** One stair heading: the direction, the steps up, the cells it cuts (bottom up, as read), the first cell outside my areas. */
    public record Heading(String dir, int n, List<Replan.Cell> cells, int[] outsideAt) {}

    /** {dx, dz} for a heading name. */
    public static int[] dir(String d) {
        return switch (d) {
            case "north" -> new int[]{0, -1};
            case "south" -> new int[]{0, 1};
            case "east" -> new int[]{1, 0};
            default -> new int[]{-1, 0};
        };
    }

    /**
     * The cells {@code StairPlan.upSteps(x, y, z, dx, dz, n)} digs: per step i the 2x2 between column i-1 and i at
     * y+i..y+i+1 (the head room over the step before and the new feet and head).
     */
    public static List<int[]> stairCells(int x, int y, int z, int dx, int dz, int n) {
        List<int[]> out = new ArrayList<>();
        for (int i = 1; i <= n; i++)
            for (int k = i - 1; k <= i; k++)
                for (int yy = y + i; yy <= y + i + 1; yy++) out.add(new int[]{x + dx * k, yy, z + dz * k});
        return out;
    }

    /**
     * Finding 1 (aa388: a 6-deep own shaft measured as 1 because every cell of a 1-wide shaft "sees the sky"): the levels
     * up to get out. Under open sky (underDepth 0) it is the hole's step-out level ({@link AaEscape#holeExit}); with
     * ground overhead (or no step-out found) the scan to the first open sky cell ({@code skyLevels}).
     */
    public static int returnLevels(int underDepth, int holeUp, int skyLevels) {
        if (underDepth == 0 && holeUp >= 0) return holeUp;
        return Math.max(0, skyLevels);
    }

    /** The tool part of breaking {@code blocks}: the cheapest way's seconds; HAND allowed only when {@code handOk}. */
    static ToolWear.Priced tool(int blocks, ToolWear.Kit kit, boolean handOk) {
        if (blocks <= 0) return new ToolWear.Priced(ToolWear.Way.USE, 0, "nothing to break");
        ToolWear.Kit k = kit != null ? kit : new ToolWear.Kit(ToolWear.STONE_SPEED, ToolWear.STONE_USES, ToolWear.INF);
        for (ToolWear.Priced p : ToolWear.price(blocks, k)) if (handOk || p.way() != ToolWear.Way.HAND) return p;
        return null;
    }

    /** Pillar or tunnel up the column, with the tool's seconds and wear for its breaks. */
    public static Replan.Way upColumn(List<Replan.Cell> column, int levels, int throwaway, ToolWear.Kit kit) {
        Replan.Way t = Replan.tunnelUp(column, levels, throwaway);
        int breaks = 0;
        for (Replan.Cell c : column) if (!c.open() && !c.water() && !c.lava()) breaks++;
        if (breaks == 0 || kit == null || Double.isInfinite(t.cost())) return t;     // no kit read: the old price
        ToolWear.Priced p = tool(breaks, kit, true);
        if (p == null) return t;
        return new Replan.Way(t.leg(), t.cost() + p.seconds(), t.why() + ", tools " + p.text());
    }

    /** A priced stair heading, or the reason it is dropped (then {@code cost} is infinite). */
    public record StairPrice(double cost, String why, ToolWear.Way way) {}

    public static StairPrice stairs(Heading h, int levels, ToolWear.Kit kit) {
        if (h.outsideAt() != null) return new StairPrice(Double.POSITIVE_INFINITY, "leaves my areas at " + RungOptions.xyz(h.outsideAt()), null);
        if (h.n() < levels) return new StairPrice(Double.POSITIVE_INFINITY, h.n() + " steps reach only " + h.n() + " of " + levels + " up", null);
        double brk = 0, haz = 0;
        int breaks = 0;
        for (Replan.Cell c : h.cells()) {
            if (c.lava()) haz += Replan.LAVA;
            if (c.water()) haz += Replan.WATER;
            if (c.falling()) haz += Replan.FALLING;
            if (c.open() || c.water() || c.lava()) continue;
            if (Double.isInfinite(c.mult()) || Double.isNaN(c.mult())) return new StairPrice(Double.POSITIVE_INFINITY, "a cell may not be broken", null);
            brk += (c.ownDug() ? Replan.OWN_BREAK : Replan.FOREIGN_BREAK) * c.mult();
            breaks++;
        }
        ToolWear.Priced p = tool(breaks, kit, false);
        if (p == null) return new StairPrice(Double.POSITIVE_INFINITY, "no pickaxe and nothing to make one (the dig needs one for stone)", null);
        double cost = brk + haz + p.seconds() + h.n() + Replan.CLIMB_FIX;
        return new StairPrice(cost, h.n() + " up, " + breaks + " breaks " + Math.round(brk) + (haz > 0 ? ", hazards " + Math.round(haz) : "")
                + (breaks > 0 ? ", tools " + p.text() : ""), p.way());
    }

    /** The whole set over the SURFACE facts (the rung, the escape). */
    public static Options.Choice choose(RungOptions.SurfaceFacts f, Options.OptionMemory m, long now) {
        List<Options.Option> rows = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        int d = Math.max(1, f.depth());
        // stairs per heading, or the climb verb without heading facts
        if (f.headings() != null) {
            for (Heading h : f.headings()) {
                String id = STAIRS + h.dir();
                StairPrice p = stairs(h, d, f.kit());
                if (Double.isInfinite(p.cost())) { dropped.add(id + ": " + p.why()); continue; }
                int[] u = dir(h.dir());
                String cmd = ToolWear.prefix(p.way()) + String.join(" then ", stairSteps(f.me()[0], f.me()[1], f.me()[2], u[0], u[1], h.n()));
                RungOptions.add(rows, dropped, m, now, Options.scored(id, "stairs", cmd, p.cost(), p.why(), id, false, m));
            }
            if (f.headings().isEmpty()) dropped.add("stairs: no heading read");
        } else RungOptions.Surface.climbRow(rows, dropped, m, now, f, d);
        // pillar / tunnel
        if (f.column() != null) {
            if (!f.placeAllowed()) dropped.add("pillar: placing not allowed here");
            else {
                Replan.Way w = upColumn(f.column(), d, f.throwaway(), f.kit());
                String id = w.leg() == Replan.Leg.PILLAR ? "pillar" : "tunnel";
                if (Double.isInfinite(w.cost())) dropped.add(id + ": " + w.why());
                else if (f.throwaway() < d) dropped.add(id + ": " + f.throwaway() + " of " + d + " blocks to place");
                else RungOptions.add(rows, dropped, m, now, Options.scored(id, id, "goto " + f.me()[0] + " " + (f.me()[1] + d) + " " + f.me()[2], w.cost(), w.why(), id, false, m));
            }
        } else if (!f.shaftOpen()) dropped.add("pillar: no open shaft above");
        else if (!f.placeAllowed()) dropped.add("pillar: placing not allowed here");
        else if (f.throwaway() < d) dropped.add("pillar: " + f.throwaway() + " of " + d + " blocks to place");
        else RungOptions.add(rows, dropped, m, now, Options.scored("pillar", "pillar", "goto " + f.me()[0] + " " + (f.me()[1] + d) + " " + f.me()[2],
                d * RungOptions.PILLAR_BLOCK + RungOptions.PILLAR_FIX, "an open shaft, " + f.throwaway() + " blocks", "pillar", false, m));
        // trail, sky, nothing
        if (f.trailEnd() == null) dropped.add("trail: no trail back to open sky");
        else RungOptions.add(rows, dropped, m, now, Options.scored("trail", "trail", "goto " + RungOptions.xyz(f.trailEnd()), f.trailLen(), "my trail back, " + Math.round(f.trailLen()) + " long", "trail", false, m));
        if (f.sky() == null) dropped.add("sky: no open-sky cell found near");
        else RungOptions.add(rows, dropped, m, now, Options.scored("sky", "goto", "goto " + RungOptions.xyz(f.sky()), f.skyDist() * RungOptions.SKY_WALK, "open sky " + Math.round(f.skyDist()) + " away", "sky", false, m));
        if (!f.tableFurnaceNext()) dropped.add("nothing: the next rung needs open ground itself");
        else if (f.sky() == null || f.skyDist() > RungOptions.NOTHING_SKY) dropped.add("nothing: no open spot within " + Math.round(RungOptions.NOTHING_SKY) + " for the next rung's walk");
        else RungOptions.add(rows, dropped, m, now, new Options.Option("nothing", "nothing", Options.NOTHING, 0, "the table/furnace rung walks to its own spot", "nothing", false));
        return Options.Choice.of(rows, dropped, "no way up");
    }

    /**
     * The chain steps of {@code dig stairs up <dir> n} (StairPlan.upSteps, same text; WayUpTest checks they match), run
     * as plain steps so the stairs stay inside the running chain (the verb would start a chain of its own).
     */
    public static List<String> stairSteps(int x, int y, int z, int dx, int dz, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            int px = x + dx * (i - 1), pz = z + dz * (i - 1), cx = x + dx * i, cz = z + dz * i;
            out.add("dig " + Math.min(px, cx) + " " + (y + i) + " " + Math.min(pz, cz) + " " + Math.max(px, cx) + " " + (y + i + 1) + " " + Math.max(pz, cz) + " ores via engine");
            out.add("goto " + cx + " " + (y + i) + " " + cz);
        }
        return out;
    }

    /** "stairs east" -> "east"; else null. */
    public static String headingOf(String id) {
        return id != null && id.startsWith(STAIRS) ? id.substring(STAIRS.length()).toLowerCase(Locale.ROOT) : null;
    }
}
