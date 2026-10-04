package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static io.github.mojolowjo.entropybot.clear.ClearRules.CLEAR_REACH;
import static io.github.mojolowjo.entropybot.clear.ClearRules.floor;

/**
 * Where to walk when nothing is left in reach (the bridge's buildGrid, walkDistances, bestSpotFor, planWalk):
 * an open/solid map of the box plus a margin around it and the bot, the walking distance to every spot the bot
 * can reach without breaking or placing, and the nearest spot from which it could see and reach a block.
 */
public final class ClearGrid {
    public final int ax, bx, ay, by, az, bz, ny, nz;
    final boolean[] open, solid, wet;
    /** every block in the box that still needs clearing */
    public final List<Pos> targets = new ArrayList<>();

    private ClearGrid(int ax, int bx, int ay, int by, int az, int bz) {
        this.ax = ax; this.bx = bx; this.ay = ay; this.by = by; this.az = az; this.bz = bz;
        this.ny = by - ay + 1;
        this.nz = bz - az + 1;
        int n = (bx - ax + 1) * ny * nz;
        open = new boolean[n];
        solid = new boolean[n];
        wet = new boolean[n];
    }

    /**
     * buildGrid: the box plus 5 around it sideways and 3 up and down (and the bot's surroundings). Notes the ores
     * it leaves (keepOres) and the built blocks; {@code job} may be null (no "only" list, nothing noted).
     */
    public static ClearGrid build(ClearWorld w, ClearBox box, Bot bot, ClearJob job) {
        int px = floor(bot.x()), py = floor(bot.y()), pz = floor(bot.z());
        ClearGrid g = new ClearGrid(Math.min(box.x1() - 5, px - 2), Math.max(box.x2() + 5, px + 2),
                Math.min(box.y1() - 3, py - 3), Math.max(box.y2() + 3, py + 3),
                Math.min(box.z1() - 5, pz - 2), Math.max(box.z2() + 5, pz + 2));
        int i = 0;
        for (int x = g.ax; x <= g.bx; x++) {
            for (int y = g.ay; y <= g.by; y++) {
                for (int z = g.az; z <= g.bz; z++, i++) {
                    if (w.air(x, y, z)) {
                        g.open[i] = true;
                        continue;
                    }
                    boolean empty = w.noCollision(x, y, z), hazard = ClearRules.hazard(w.name(x, y, z));
                    g.open[i] = empty && !hazard;
                    g.solid[i] = !empty && !hazard;
                    g.wet[i] = w.fluid(x, y, z);
                    if (box.contains(x, y, z) && (job == null || job.wanted(Pos.key(x, y, z)))) {
                        if (ClearEngine.clearable(w, job, x, y, z)) {
                            if (job != null && job.keepOres && w.ore(x, y, z)) ClearEngine.noteOre(job, w, x, y, z);
                            else g.targets.add(new Pos(x, y, z));
                        } else if (job != null && !w.blockEntity(x, y, z) && w.builtBlock(x, y, z)) {
                            ClearEngine.noteProtected(job, w, x, y, z);
                        }
                    }
                }
            }
        }
        return g;
    }

    public int idx(int x, int y, int z) {
        if (x < ax || x > bx || y < ay || y > by || z < az || z > bz) return -1;
        return ((x - ax) * ny + (y - ay)) * nz + (z - az);
    }

    public boolean open(int x, int y, int z) {
        int i = idx(x, y, z);
        return i >= 0 && open[i];
    }

    public boolean solid(int x, int y, int z) {
        int i = idx(x, y, z);
        return i >= 0 && solid[i];
    }

    boolean wet(int x, int y, int z) {
        int i = idx(x, y, z);
        return i >= 0 && wet[i];
    }

    /** The bot fits with its feet in x y z and something solid under it. */
    public boolean stand(int x, int y, int z) {
        return open(x, y, z) && open(x, y + 1, z) && solid(x, y - 1, z);
    }

    /**
     * walkDistances: steps from the bot to every spot it can reach without breaking or placing (flat moves,
     * 1-block step-ups with head room, drops of up to 3), by grid index; -1 where it can't.
     */
    public int[] walkDistances(Bot bot) {
        int[] dist = new int[open.length];
        Arrays.fill(dist, -1);
        int sx = floor(bot.x()), sy = floor(bot.y() + 0.01), sz = floor(bot.z());
        int i = idx(sx, sy, sz);
        if (i < 0) return dist;
        int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        int[] queue = new int[open.length * 3];
        int head = 0, tail = 0;
        dist[i] = 0;
        queue[tail++] = sx; queue[tail++] = sy; queue[tail++] = sz;
        while (head < tail) {
            int cx = queue[head++], cy = queue[head++], cz = queue[head++];
            int d = dist[idx(cx, cy, cz)];
            for (int[] dir : dirs) {
                int nx = cx + dir[0], nz = cz + dir[1];
                Integer ny = null;
                if (stand(nx, cy, nz)) {
                    ny = cy;
                } else if (open(cx, cy + 2, cz) && stand(nx, cy + 1, nz)) {
                    ny = cy + 1;
                } else if (open(nx, cy, nz) && open(nx, cy + 1, nz)) {
                    for (int dy = 1; dy <= 3 && ny == null; dy++) {
                        if (!open(nx, cy - dy, nz)) break;
                        if (stand(nx, cy - dy, nz)) ny = cy - dy;
                    }
                }
                if (ny == null) continue;
                int j = idx(nx, ny, nz);
                if (j < 0 || dist[j] >= 0) continue;
                dist[j] = d + 1;
                queue[tail++] = nx; queue[tail++] = ny; queue[tail++] = nz;
            }
        }
        return dist;
    }

    /** A spot to stand on, what getting there costs (steps; 1000+ for spots Baritone may reach some other way) and its eye. */
    public record Spot(int x, int y, int z, double cost, double eyeX, double eyeY, double eyeZ) {
        public String key() { return Pos.key(x, y, z); }
        public Pos pos() { return new Pos(x, y, z); }
    }

    /**
     * bestSpotFor: the nearest spot (by walking) from which the bot could see and reach t. Spots it can't walk to
     * count too, after all others (Baritone might get there another way). Never on t, nor under it when sand or
     * gravel sits on top; not in water; not a spot it failed to get to within the last 10 blocks; never below the
     * walkway (minStandY). Spends the job's sight budget (24 tries at most here).
     */
    public static Spot bestSpotFor(ClearWorld w, ClearGrid g, int[] dist, Pos t, Bot bot, ClearJob job) {
        double r = CLEAR_REACH - 0.3;
        boolean fallAbove = ClearRules.falling(w.name(t.x(), t.y() + 1, t.z()));
        List<Spot> cands = new ArrayList<>();
        for (int x = t.x() - 5; x <= t.x() + 5; x++) {
            for (int z = t.z() - 5; z <= t.z() + 5; z++) {
                for (int y = t.y() - 5; y <= t.y() + 3; y++) {
                    // never stand on it, nor under it when sand/gravel sits on top
                    if (x == t.x() && z == t.z() && (y == t.y() + 1 || (fallAbove && y < t.y()))) continue;
                    // a vein clear stays on the walkway: from a hole 2 deep the bot can't climb back out
                    if (job.minStandY != null && y < job.minStandY) continue;
                    double ex = x + 0.5, ey = y + Bot.EYE, ez = z + 0.5;
                    if (ClearEngine.eyeDistSq(ex, ey, ez, t.x(), t.y(), t.z()) > r * r) continue;
                    int i = g.idx(x, y, z);
                    if (i < 0 || g.wet[i] || g.wet(x, y + 1, z)) continue;     // not standing in water
                    Integer bad = job.badSpots.get(Pos.key(x, y, z));
                    if (bad != null && job.broken - bad < 10) continue;      // failed to get there recently
                    double d;
                    if (dist[i] >= 0) d = dist[i];
                    else if (g.stand(x, y, z)) {
                        double dx = x - bot.x(), dy = y - bot.y(), dz = z - bot.z();
                        d = 1000 + Math.sqrt(dx * dx + dy * dy + dz * dz);
                    } else continue;
                    cands.add(new Spot(x, y, z, d, ex, ey, ez));
                }
            }
        }
        cands.sort(Comparator.comparingDouble(Spot::cost));
        // in tunnels the nearest spots often face rock, so try a good few; the plan-wide budget keeps a
        // hopeless plan from freezing the game
        for (int i = 0; i < cands.size() && i < 24 && job.sightBudget > 0; i++) {
            job.sightBudget--;
            Spot s = cands.get(i);
            if (ClearEngine.sightOf(w, s.eyeX(), s.eyeY(), s.eyeZ(), t.x(), t.y(), t.z()) != null) return s;
        }
        return null;
    }

    /** Where to walk next: the block and the spot to reach it from. */
    public record Plan(Pos target, Spot spot) {}

    /** planWalk's sight budget per plan. */
    public static final int SIGHT_BUDGET = 400;

    /**
     * planWalk: the exposed block with the nearest reachable spot (checks the nearest 40 blocks, more only if
     * none of those work). Sets the job's scan count ("~N left") and skips blocks next to water or lava for good;
     * a block with nowhere to stand is remembered for the report. Null when nothing is left to walk to.
     */
    public static Plan planWalk(ClearWorld w, Bot bot, ClearJob job) {
        ClearGrid g = build(w, job.box, bot, job);
        int[] dist = g.walkDistances(bot);
        job.sightBudget = SIGHT_BUDGET;
        job.lastScanLeft = g.targets.size();
        job.brokenAtScan = job.broken;
        double px = bot.x(), py = bot.y(), pz = bot.z();
        List<Pos> list = new ArrayList<>();
        List<Double> d2s = new ArrayList<>();
        for (Pos t : g.targets) {
            String key = t.key();
            if (job.skip.containsKey(key) || ClearEngine.clearFailed(job, key)) continue;
            boolean exposed = false;
            for (int n = 0; n < 6 && !exposed; n++) {
                int[] s = ClearEngine.SIDES6[n];
                if (!g.solid(t.x() + s[0], t.y() + s[1], t.z() + s[2])) exposed = true;
            }
            if (!exposed) continue;
            list.add(t);
            double dx = t.x() + 0.5 - px, dy = t.y() + 0.5 - py, dz = t.z() + 0.5 - pz;
            d2s.add(dx * dx + dy * dy + dz * dz);
        }
        Integer[] order = new Integer[list.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(d2s::get));
        Plan best = null;
        for (int i = 0; i < order.length; i++) {
            if ((best != null && i >= 40) || job.sightBudget <= 0) break;
            Pos t = list.get(order[i]);
            if (ClearEngine.nextToLiquid(w, t.x(), t.y(), t.z())) {
                job.skip.put(t.key(), ClearEngine.NEXT_TO_LIQUID);
                continue;
            }
            Spot spot = bestSpotFor(w, g, dist, t, bot, job);
            if (spot == null) job.noSpot.add(t.key());
            if (spot != null && (best == null || spot.cost() < best.spot().cost())) best = new Plan(t, spot);
        }
        return best;
    }

    /** How many sight checks {@link #entryProblem} may spend. */
    public static final int ENTRY_BUDGET = 600;

    /**
     * S1: why a clear got stuck, in plain words, when the cause is that no spot the bot can walk to (without breaking or
     * placing) sees any block it still has to clear: " - I can't get into the box: no spot I can walk to sees into it
     * (a wall 2 high at -1 53 124?) - break one block for a step, or dig a box one wider". The guess in brackets is the
     * column next to the walkable spot nearest the box, toward the box (a wall it can't step up, or a drop). "" when a
     * walkable spot does see a block (then the cause is something else) or nothing is left.
     */
    public static String entryProblem(ClearWorld w, Bot bot, ClearJob job) {
        ClearGrid g = build(w, job.box, bot, null);
        int[] dist = g.walkDistances(bot);
        double r = CLEAR_REACH - 0.3;
        List<Pos> exposed = new ArrayList<>();
        for (Pos t : g.targets) {
            // the grid was built without the job (no notes): the job's own rules now
            if (!job.wanted(t.key()) || job.skip.containsKey(t.key()) || !ClearEngine.clearable(w, job, t.x(), t.y(), t.z())
                    || (job.keepOres && w.ore(t.x(), t.y(), t.z()))) continue;
            for (int[] s : ClearEngine.SIDES6) {
                if (!g.solid(t.x() + s[0], t.y() + s[1], t.z() + s[2])) {
                    exposed.add(t);
                    break;
                }
            }
        }
        if (exposed.isEmpty()) return "";
        double bx = bot.x(), by = bot.y(), bz = bot.z();
        exposed.sort(Comparator.comparingDouble(t -> sq(t.x() + 0.5 - bx) + sq(t.y() + 0.5 - by) + sq(t.z() + 0.5 - bz)));
        int budget = ENTRY_BUDGET;
        for (int k = 0; k < exposed.size() && k < 60 && budget > 0; k++) {
            Pos t = exposed.get(k);
            for (int x = t.x() - 5; x <= t.x() + 5 && budget > 0; x++) {
                for (int z = t.z() - 5; z <= t.z() + 5 && budget > 0; z++) {
                    for (int y = t.y() - 5; y <= t.y() + 3 && budget > 0; y++) {
                        int i = g.idx(x, y, z);
                        if (i < 0 || dist[i] < 0) continue;
                        double ex = x + 0.5, ey = y + Bot.EYE, ez = z + 0.5;
                        if (ClearEngine.eyeDistSq(ex, ey, ez, t.x(), t.y(), t.z()) > r * r) continue;
                        budget--;
                        if (ClearEngine.sightOf(w, ex, ey, ez, t.x(), t.y(), t.z()) != null) return "";
                    }
                }
            }
        }
        if (budget <= 0) return "";                 // ran out of looking: no claim either way
        // the walkable spot nearest the box, and what stands between it and the box
        int bestI = -1, sx = 0, sy = 0, sz = 0;
        double bestD = Double.MAX_VALUE;
        for (int x = g.ax; x <= g.bx; x++) {
            for (int y = g.ay; y <= g.by; y++) {
                for (int z = g.az; z <= g.bz; z++) {
                    int i = g.idx(x, y, z);
                    if (dist[i] < 0) continue;
                    double d = job.box.distTo(x + 0.5, y, z + 0.5);
                    if (d < bestD || (d == bestD && dist[i] < dist[bestI])) {
                        bestD = d;
                        bestI = i;
                        sx = x; sy = y; sz = z;
                    }
                }
            }
        }
        String guess = "";
        if (bestI >= 0) {
            int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
            int nx = sx, nz = sz;
            double nd = Double.MAX_VALUE;
            for (int[] dir : dirs) {
                double d = job.box.distTo(sx + dir[0] + 0.5, sy, sz + dir[1] + 0.5);
                if (d < nd) { nd = d; nx = sx + dir[0]; nz = sz + dir[1]; }
            }
            int h = 0;
            while (h < 6 && g.solid(nx, sy + h, nz)) h++;
            if (h >= 2) guess = " (a wall " + h + " high at " + Pos.key(nx, sy, nz) + "?)";
            else if (h == 0 && g.open(nx, sy, nz) && !g.solid(nx, sy - 1, nz)) {
                int depth = 0;
                while (depth < 6 && g.open(nx, sy - 1 - depth, nz)) depth++;
                if (depth >= 2) guess = " (a drop " + depth + " deep at " + Pos.key(nx, sy - 1, nz) + "?)";
            }
        }
        boolean only = job.only != null;
        return " - I can't get " + (only ? "to the blocks" : "into the box") + ": no spot I can walk to sees "
                + (only ? "them" : "into it") + guess + " - break one block for a step" + (only ? "" : ", or dig a box one wider");
    }

    private static double sq(double v) { return v * v; }
}
