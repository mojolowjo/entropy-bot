package io.github.mojolowjo.entropybot.sim;

import io.github.mojolowjo.entropybot.aa.FakePaths;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * sim step 2: the step actors - what each step kind changes in the fake world and how long it takes. A kind with no
 * actor answers {@link Result#NOT_SIMULATED} (the game ends there, said); an actor that cannot act answers a failure with
 * its reason (no block there, the bag lacks the input), as the game's step would.
 *
 * <p>Times: a walk is the tests' walk price of the straight metres (Baritone's search joins in step 3); a dig is the
 * game's rule, hardness x 1.5 s by hand, divided by the speed of the best tool of the block's kind in the bag; a craft is
 * {@link #CRAFT_S} a batch.
 */
final class SimSteps {
    private SimSteps() {}

    static final double CRAFT_S = 0.3;           // measured (runs JBK to JBS, 102 bag crafts): a craft batch's seconds, median
    static final double TABLE_CRAFT_S = 0.9;     // measured (32 table crafts: pickaxe, furnace): the table's menu opens first
    static final double CHOP_EXTRA_S = 1.2;      // measured (52 cuts, 4.17 s a log against the dig rule's 3.0): reaching, the drop picked up
    static final double DIG_EXTRA_S = 0.4;       // measured (17 cobblestone collects, 1.5 s a block against the rule's 1.1)
    static final double TAKE_EXTRA_S = 0.65;     // measured (24 takes of a crafting table, 4.4 s against the rule's 3.75)
    static final double SMELT_SETUP_S = 8.7;     // measured (15 smelts of 1 charcoal, 17.9 to 18.8 s): the furnace filled and emptied round the 10 s
    static final double HAND_X = 1.5;            // structural: the game's dig seconds per hardness with the right tool or by hand on a block that needs none
    static final int REACH = 4;                  // sim: blocks the hands reach from the feet

    record Result(boolean ok, double seconds, String text, boolean notSimulated) {
        static final Result NOT_SIMULATED = new Result(false, 0, "not simulated", true);
        static Result ok(double s, String text) { return new Result(true, s, text, false); }
        static Result fail(double s, String text) { return new Result(false, s, text, false); }
    }

    static Result apply(SimGame g, Step s) {
        if (s instanceof Step.Walk w) return walk(g, w);
        // a chop takes the WHOLE tree whatever the number (StepHands: the fork's ChopProcess; the count is checked between trees)
        if (s instanceof Step.ChopTree c) return dig(g, c.at(), c.logs(), item -> c.log() == null ? ItemWorth.isIn(item, "minecraft:logs") : item.equals(full(c.log())), "log", true);
        if (s instanceof Step.Collect c) return dig(g, c.at(), c.n(), item -> item.equals(full(c.item())), c.item(), false);
        if (s instanceof Step.Craft c) return craft(g, c);
        if (s instanceof Step.PlaceNear p) return placeNear(g, full(p.item()));
        if (s instanceof Step.TakeNear t) return takeNear(g, full(t.item()));
        if (s instanceof Step.TakeStation t) return take(g, full(t.item()), new int[]{t.x(), t.y(), t.z()});
        if (s instanceof Step.ToStation t) return walk(g, new Step.Walk(t.x(), t.y(), t.z(), 2));     // sim: stands within 2 of the station
        if (s instanceof Step.Smelt m) return smelt(g, m);
        if (s instanceof Step.Wait w) return Result.ok(w.seconds(), "waited");
        if (s instanceof Step.AwaitPaths) return Result.ok(0, "paths: the sim's walks are priced at once");
        if (s instanceof Step.Climb) return climb(g, null);
        if (s instanceof Step.ToSurface t) return climb(g, new int[]{t.x(), t.y(), t.z()});       // the game: done there, or as soon as the feet stand under open sky
        if (s instanceof Step.Explore e) return explore(g, e);
        return Result.NOT_SIMULATED;
    }

    /**
     * Slice 4: an explore on a map with no edge. The bot walks to the nearest spot of a sight-wide grid it has not looked
     * from, and looks; it ends when a source of {@code until} shows that was not in sight at its start, or its minutes run
     * out. The game's explore is the fork's (nearest chunk not seen); this stands in for it: no terrain, no search.
     * A map with an edge (a scenario's cube, a hand-built one): not simulated - what lies past the edge is not known.
     */
    private static Result explore(SimGame g, Step.Explore e) {
        if (!g.world.endless) return Result.NOT_SIMULATED;
        String[] parts = e.until() == null ? null : e.until().split("\\|");
        java.util.function.Predicate<String> gives = item -> parts != null && SimGame.matches(item, parts) != null;
        java.util.Set<Long> before = new java.util.HashSet<>();
        if (parts != null) for (SimWorld.Group gr : g.world.groups(g.feet, g.sight, gives)) for (int[] c : gr.cells()) before.add(SimWorld.cell(c));
        double secs = 0, limit = e.minutes() * 60.0;       // structural: seconds a minute
        int hop = g.sight;
        g.looked.add(Math.floorDiv(g.feet[0] + hop / 2, hop) + " " + Math.floorDiv(g.feet[2] + hop / 2, hop));
        while (secs < limit) {
            int ci = Math.floorDiv(g.feet[0] + hop / 2, hop), cj = Math.floorDiv(g.feet[2] + hop / 2, hop);
            int[] best = null;
            for (int i = ci - 6; i <= ci + 6; i++) for (int j = cj - 6; j <= cj + 6; j++) {       // sim: the grid spots tried round the feet
                if (g.looked.contains(i + " " + j)) continue;
                int[] p = {i * hop, g.feet[1], j * hop};
                if (best == null || SimWorld.dist(p, g.feet) < SimWorld.dist(best, g.feet)) best = p;
            }
            if (best == null) return Result.fail(secs, "explore: every spot within " + 6 * hop + " blocks was looked from");
            secs += FakePaths.walkS(SimWorld.dist(best, g.feet));
            g.feet[0] = best[0];
            g.feet[2] = best[2];
            g.looked.add(Math.floorDiv(best[0] + hop / 2, hop) + " " + Math.floorDiv(best[2] + hop / 2, hop));
            String snap = stand(g);
            if (snap != null) return Result.fail(secs, snap);
            if (parts != null) for (SimWorld.Group gr : g.world.groups(g.feet, g.sight, gives)) for (int[] c : gr.cells())
                if (!before.contains(SimWorld.cell(c))) return Result.ok(secs, "explore: " + e.until() + " seen at " + c[0] + " " + c[1] + " " + c[2] + " from " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2]);
        }
        return Result.ok(secs, "explored " + e.minutes() + " min: no new " + e.until() + " seen; at " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2]);
    }

    static String full(String id) { return id.contains(":") ? id : "minecraft:" + id; }

    /** The feet go to the cell, or to {@code near} short of it on the straight line; with no open way there, by a dug way. */
    private static Result walk(SimGame g, Step.Walk w) {
        Way way = go(g, new int[]{w.x(), w.y(), w.z()}, Math.max(0, w.near()), w.near() == 0);
        String at = "at " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2] + way.said();
        return way.fail() != null ? Result.fail(way.secs(), way.fail()) : Result.ok(way.secs(), at);
    }

    /** One move's outcome: its seconds, the blocks it dug on the way, why it failed (null: it did not). */
    record Way(double secs, int dug, String fail) {
        String said() { return dug > 0 ? " (dug a way: " + dug + " blocks)" : ""; }
    }

    static final double STEP_M = 1.4;            // structural: the metres of one stair step (a block along, a block up or down)

    /**
     * Slice 4b: the feet go toward {@code to} until within {@code within} of it. An open way (no block broken) or an end
     * past the open-way check's look: the straight line, as before. No open way: a dug way - a stair, one block along and
     * at most one up or down a step, each block in it dug at the game's dig time and kept ({@link #dugPath}: the cheapest
     * such way). Stands in for the fork's path with breaks (step 3): no placed block, no falling sand, no water.
     */
    static Way go(SimGame g, int[] to, int within, boolean exactY) {
        int[] was = g.feet.clone();
        double d = SimWorld.dist(g.feet, to), line = Math.max(0, d - within);
        if (d <= within || line <= 0) return new Way(0, 0, null);
        java.util.Set<Long> from = g.world.reach(g.feet);
        double f = line / d;
        g.feet[0] += (int) Math.round((to[0] - g.feet[0]) * f);
        g.feet[2] += (int) Math.round((to[2] - g.feet[2]) * f);
        if (exactY) g.feet[1] = to[1];
        String snap = stand(g);
        double secs = 0;
        if (snap == null && (SimWorld.pastReach(was, g.feet) || from.contains(SimWorld.cell(g.feet)))) {
            secs = FakePaths.walkS(line);                           // the open part of the way; what is left of it is dug from here
            if (arrived(g.feet, to, within)) return new Way(secs, 0, null);
        } else if (snap != null && SimWorld.pastReach(was, g.feet)) {
            return new Way(FakePaths.walkS(line), 0, snap);
        } else {
            System.arraycopy(was, 0, g.feet, 0, 3);
        }
        int dug = 0;
        for (int plan = 0; plan < WAY_PLANS; plan++) {
            if (arrived(g.feet, to, within)) return new Way(secs, dug, null);
            List<int[]> path = dugPath(g, to, within);
            if (path == null) return new Way(secs, dug, "no dug way to " + to[0] + " " + to[1] + " " + to[2] + " within " + WAY_MARGIN + " blocks round the line from " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2]
                    + " (the sim digs only: no block placed, no ground not known, no chest or station of mine dug)");
            for (int[] next : path) {
                int dy = next[1] - g.feet[1];
                List<int[]> cells = stepCells(g.feet, next);
                for (int[] c : cells) {
                    String b = g.world.at(c[0], c[1], c[2]);
                    if (b.equals(SimWorld.AIR)) continue;
                    secs += digS(g, b) + DIG_EXTRA_S;
                    g.world.set(c[0], c[1], c[2], SimWorld.AIR);
                    if (SimWorld.drop(b) != null) g.give(SimWorld.drop(b), 1);     // leaves give nothing
                    dug++;
                }
                secs += FakePaths.walkS(dy == 0 ? 1 : STEP_M);
                System.arraycopy(next, 0, g.feet, 0, 3);
                String fell = stand(g);                             // a floor an earlier step of this way took: the feet land, the way is planned again
                if (fell != null) return new Way(secs, dug, fell);
                if (g.feet[1] != next[1]) break;
            }
        }
        if (arrived(g.feet, to, within)) return new Way(secs, dug, null);
        return new Way(secs, dug, "the dug way did not arrive in " + WAY_PLANS + " plans; at " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2] + " for " + to[0] + " " + to[1] + " " + to[2]);
    }

    /** The move is at its end: in the cell itself (within 0), else within the asked blocks and a half more than a block (a block on a ledge over the feet counts). */
    static boolean arrived(int[] c, int[] to, int within) { return SimWorld.dist(c, to) <= (within == 0 ? 0.5 : within + NEAR_SLACK); }

    static final double NEAR_SLACK = 1.5;        // sim: the blocks past "near" that still count as there (the feet are a block under the hands)
    static final int WAY_PLANS = 6;              // sim: the times one dug way may be planned again after a fall
    static final int WAY_MARGIN = 10;            // sim: the blocks round the feet and the end the dug way's search looks in
    static final int WAY_BACK = 12;              // sim: the steps back a way is checked for having opened its next floor
    static final int WAY_DROP = 3;               // sim: the blocks a step off an edge may drop (as the open-way check's)
    static final int WAY_NODES = 60_000;         // sim: the cells one search may settle

    /** The cells a step from {@code from} to the neighbouring {@code next} needs open: the feet and head there, and the room to step up or down. */
    private static List<int[]> stepCells(int[] from, int[] next) {
        if (next[0] == from[0] && next[2] == from[2]) return List.of(new int[]{from[0], from[1] - 1, from[2]});      // straight down: the block under the feet
        if (next[1] < from[1] - 1) return List.of(new int[]{next[0], from[1], next[2]}, new int[]{next[0], from[1] + 1, next[2]});      // a drop: the step off the edge
        List<int[]> cs = new ArrayList<>(List.of(new int[]{next[0], next[1], next[2]}, new int[]{next[0], next[1] + 1, next[2]}));
        if (next[1] < from[1]) cs.add(new int[]{next[0], next[1] + 2, next[2]});
        if (next[1] > from[1]) cs.add(new int[]{from[0], from[1] + 2, from[2]});
        return cs;
    }

    /**
     * The cheapest dug way from the feet to within {@code within} of {@code to} (a cheapest-first search over feet cells):
     * a step goes one block to a side and at most one up or down, onto ground that is there (never a placed block), or off
     * an edge and down at most {@link #WAY_DROP}, or straight down through the block under the feet; its
     * price is the walk and the dig time of the blocks in it. Not dug: ground not known, a chest, a station of mine, the
     * floor of the end cell. The steps in order, null: none in the look ({@link #WAY_MARGIN}, {@link #WAY_NODES}).
     */
    private static List<int[]> dugPath(SimGame g, int[] to, int within) {
        int[] lo = new int[3], hi = new int[3];
        for (int k = 0; k < 3; k++) { lo[k] = Math.min(g.feet[k], to[k]) - WAY_MARGIN; hi[k] = Math.max(g.feet[k], to[k]) + WAY_MARGIN; }
        Map<String, Double> price = new HashMap<>();
        Map<Long, Double> best = new HashMap<>();
        Map<Long, int[]> came = new HashMap<>();
        java.util.PriorityQueue<double[]> open = new java.util.PriorityQueue<>(java.util.Comparator.comparingDouble(a -> a[0]));
        open.add(new double[]{0, g.feet[0], g.feet[1], g.feet[2], 0});
        best.put(SimWorld.cell(g.feet), 0.0);
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int settled = 0;
        while (!open.isEmpty() && settled++ < WAY_NODES) {
            double[] top = open.poll();
            int[] c = {(int) top[1], (int) top[2], (int) top[3]};
            if (top[4] > best.get(SimWorld.cell(c))) continue;
            if (arrived(c, to, within)) {
                java.util.LinkedList<int[]> path = new java.util.LinkedList<>();
                for (int[] p = c; p != null && !(p[0] == g.feet[0] && p[1] == g.feet[1] && p[2] == g.feet[2]); p = came.get(SimWorld.cell(p))) path.addFirst(p);
                return path;
            }
            {
                // straight down: the block under the feet dug, the feet land on what is under it (through open cells, at most WAY_DROP)
                String under = g.world.at(c[0], c[1] - 1, c[2]);
                boolean mine = under.equals(Scenario.NOT_LOADED) || under.equals("minecraft:chest") || g.mine.contains(c[0] + " " + (c[1] - 1) + " " + c[2])
                        || within == 0 && c[0] == to[0] && c[1] == to[1] && c[2] == to[2];
                int land = Integer.MIN_VALUE;
                if (!mine && !under.equals(SimWorld.AIR)) for (int k = 2; k <= WAY_DROP + 2; k++) {
                    String b = g.world.at(c[0], c[1] - k, c[2]);
                    if (b.equals(SimWorld.AIR)) continue;
                    if (!b.equals(Scenario.NOT_LOADED)) land = c[1] - k + 1;
                    break;
                }
                if (land != Integer.MIN_VALUE && land >= lo[1]) {
                    int[] n = {c[0], land, c[2]};
                    double total = top[4] + FakePaths.walkS(STEP_M) + price.computeIfAbsent(under, k -> digS(g, k) + DIG_EXTRA_S);
                    if (total < best.getOrDefault(SimWorld.cell(n), Double.MAX_VALUE)) {
                        best.put(SimWorld.cell(n), total);
                        came.put(SimWorld.cell(n), c);
                        open.add(new double[]{total + ahead(n, to, within), n[0], n[1], n[2], total});
                    }
                }
            }            for (int[] s : sides) for (int dy = -1; dy <= 1; dy++) {
                int[] n = {c[0] + s[0], c[1] + dy, c[2] + s[1]};
                if (n[0] < lo[0] || n[0] > hi[0] || n[1] < lo[1] || n[1] > hi[1] || n[2] < lo[2] || n[2] > hi[2]) continue;
                String floor = g.world.at(n[0], n[1] - 1, n[2]);
                if (floor.equals(Scenario.NOT_LOADED)) continue;
                if (floor.equals(SimWorld.AIR)) {
                    // no ground there: a step off the edge and a drop of at most WAY_DROP through open cells onto ground
                    if (dy != 0) continue;
                    int land = Integer.MIN_VALUE;
                    for (int k = 2; k <= WAY_DROP + 1; k++) {
                        String b = g.world.at(n[0], n[1] - k, n[2]);
                        if (b.equals(SimWorld.AIR)) continue;
                        if (!b.equals(Scenario.NOT_LOADED)) land = n[1] - k + 1;
                        break;
                    }
                    if (land == Integer.MIN_VALUE || land < lo[1]) continue;
                    n = new int[]{n[0], land, n[2]};
                }
                double cost = FakePaths.walkS(dy == 0 ? 1 : STEP_M);
                boolean ok = true;
                for (int[] d : stepCells(c, n)) {
                    String b = g.world.at(d[0], d[1], d[2]);
                    if (b.equals(SimWorld.AIR)) continue;
                    if (b.equals(Scenario.NOT_LOADED) || b.equals("minecraft:chest") || g.mine.contains(d[0] + " " + d[1] + " " + d[2])
                            || within == 0 && d[0] == to[0] && d[1] == to[1] - 1 && d[2] == to[2]) { ok = false; break; }
                    cost += price.computeIfAbsent(b, k -> digS(g, k) + DIG_EXTRA_S);
                }
                // the way must not stand on a cell its own last steps opened (their feet, head and step room): the search reads
                // the blocks as they are now
                int fy = n[1] - 1;
                int[] a = c;
                for (int back = 0; ok && a != null && back < WAY_BACK; back++, a = came.get(SimWorld.cell(a)))
                    if (a[0] == n[0] && a[2] == n[2] && fy >= a[1] && fy <= a[1] + 2) ok = false;
                if (!ok) continue;
                long key = SimWorld.cell(n);
                double total = top[4] + cost;
                if (total >= best.getOrDefault(key, Double.MAX_VALUE)) continue;
                best.put(key, total);
                came.put(key, c);
                open.add(new double[]{total + ahead(n, to, within), n[0], n[1], n[2], total});
            }
        }
        return null;
    }

    /** The least a way from {@code n} can still cost: the walk of the straight line left (the search looks toward the end first). */
    private static double ahead(int[] n, int[] to, int within) { return FakePaths.walkS(Math.max(0, SimWorld.dist(n, to) - within) / STEP_M); }

    /**
     * Slice 4b: the climb. Under open sky: nothing to do. Else to the nearest cell under open sky ({@link SimWorld#skyCell}),
     * by the open way when there is one (the stair the bot dug down), else a dug stair up.
     * <p>Placing (2026-11-16; the cloud game: -d cobblestone 32 -> 18 -> 9 over two climbs): the game's climb pillars up on
     * blocks from the bag. ASSUMED until Baritone's own search runs here: one block a level risen, as far as the bag has
     * filler to spare ({@link SimGame#spare}: the mod's reserves over the fork's count) - the blocks leave the bag and are
     * said; the way and its time stay the dug stair's, the placed blocks are not put in the world.
     */
    private static Result climb(SimGame g, int[] planned) {
        if (g.world.below(g.feet[0], g.feet[1], g.feet[2]) == 0) return Result.ok(0, "nothing over the feet, no hole round them: at the surface");
        // the planned cell while it still is a cell to stand in on the surface (a dig since may have taken its ground), else as now
        int[] top = planned != null && g.world.standable(planned[0], planned[1], planned[2]) && g.world.below(planned[0], planned[1], planned[2]) == 0 ? planned : g.world.ringTop(g.feet);
        if (top == null || g.world.below(top[0], top[1], top[2]) > 0) top = g.world.skyCell(g.feet, SKY_R);
        if (top == null) return Result.fail(0, "climb: no cell under open sky within " + SKY_R + " blocks of " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2]);
        int rise = Math.max(0, top[1] - g.feet[1]);
        Way way = go(g, top, 0, true);
        if (way.fail() != null) return Result.fail(way.secs(), "climb: " + way.fail());
        return Result.ok(way.secs(), "on the surface at " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2] + way.said() + placed(g, rise));
    }

    /** The climb's placed filler: up to {@code rise} blocks out of the bag, the most spare kind first; "" when none. */
    private static String placed(SimGame g, int rise) {
        java.util.Map<String, Integer> out = new java.util.TreeMap<>();
        for (int left = rise; left > 0; left--) {
            String best = null;
            int most = 0;
            for (String id : g.bag.keySet()) { int sp = g.spare(id); if (sp > most) { most = sp; best = id; } }
            if (best == null) break;
            g.give(best, -1);
            out.merge(best.replaceFirst("^minecraft:", ""), 1, Integer::sum);
        }
        if (out.isEmpty()) return rise > 0 ? " (placed nothing: no filler to spare in the bag)" : "";
        List<String> said = new ArrayList<>();
        for (var e : out.entrySet()) said.add(e.getValue() + " " + e.getKey());
        return " (placed " + String.join(", ", said) + ")";
    }

    static final int SKY_R = 24;                 // sim: how far round the feet the climb looks for open sky

    /**
     * After a straight-line move on real terrain: the feet go to the standable cell of their column nearest their height
     * (the body is not simulated: no search, no climb cost - step 3). null: standing; else why not (no such cell: said).
     */
    static String stand(SimGame g) {
        if (g.world.standable(g.feet[0], g.feet[1], g.feet[2])) return null;
        Integer y = g.world.standY(g.feet[0], g.feet[1], g.feet[2]);
        if (y == null) return "walk: no cell to stand in the column " + g.feet[0] + " " + g.feet[2] + " (the map's edge or all solid)";
        g.feet[1] = y;
        return null;
    }

    /**
     * Digs until {@code n} more of the item are in the bag: the blocks giving it in sight, the group at {@code at} first
     * (else the nearest), each block walked to within reach. Ends short with the count when no more is in sight.
     */
    private static Result dig(SimGame g, int[] at, int n, java.util.function.Predicate<String> gives, String what, boolean wholeGroup) {
        double secs = 0;
        int got = 0;
        int dugWay = 0;
        while (got < n) {
            List<SimWorld.Group> groups = g.world.groups(g.feet, Step.Collect.MINE_SIGHT + REACH, gives);
            SimWorld.Group pick = groups.isEmpty() ? null : groups.get(0);
            // the named spot's own group first, in sight or not (the fork's chop / the mine goes to the spot the plan priced)
            if (at != null) for (SimWorld.Group gr : g.world.groups(at, 1, gives)) if (gr.cells().stream().anyMatch(c -> c[0] == at[0] && c[1] == at[1] && c[2] == at[2])) {
                List<int[]> whole = new ArrayList<>();
                for (SimWorld.Group far : g.world.groups(at, g.sight, gives)) if (far.block().equals(gr.block()) && far.cells().stream().anyMatch(c -> c[0] == at[0] && c[1] == at[1] && c[2] == at[2])) whole = far.cells();
                whole.sort(java.util.Comparator.comparingDouble(c -> SimWorld.dist(c, g.feet)));
                pick = new SimWorld.Group(gr.block(), whole);
                groups = List.of(pick);
                break;
            }
            if (groups.isEmpty()) break;
            int[] c = pick.cells().get(0);
            double d = SimWorld.dist(c, g.feet);
            if (d > REACH) {
                int had = g.bag.getOrDefault(SimWorld.drop(pick.block()), 0);
                Way way = go(g, c, REACH - 1, false);
                secs += way.secs();
                dugWay += way.dug();
                if (way.fail() != null) return Result.fail(secs, way.fail());
                got += g.bag.getOrDefault(SimWorld.drop(pick.block()), 0) - had;       // the way itself went through the wanted block
                if (!pick.block().equals(g.world.at(c[0], c[1], c[2]))) continue;
            }
            for (int[] cell : wholeGroup ? pick.cells() : List.of(c)) {
                secs += digS(g, pick.block()) + (wholeGroup ? CHOP_EXTRA_S : DIG_EXTRA_S);
                g.world.set(cell[0], cell[1], cell[2], SimWorld.AIR);
                g.give(SimWorld.drop(pick.block()), 1);
                got++;
            }
            String fell = stand(g);                                 // the block under the feet went: the feet land
            if (fell != null) return Result.fail(secs, fell);
        }
        String way = dugWay > 0 ? " (dug a way: " + dugWay + " blocks)" : "";
        if (got < n) return Result.fail(secs, "got " + got + " of " + n + " " + what + ": no more in sight of " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2] + way);
        return Result.ok(secs, "got " + got + " " + what + way);
    }

    /** The game's dig time: hardness x 1.5 s over the speed of the bag's best tool of the block's kind (1: none). */
    static double digS(SimGame g, String block) {
        double hard = ItemWorth.facts(block).hardness();
        ItemWorth.Role role = ItemWorth.isIn(block, "minecraft:logs") || ItemWorth.plank(block) ? ItemWorth.Role.AXE
                : ItemWorth.stoneLike(block) && !ItemWorth.isIn(block, "minecraft:mineable/shovel") || ItemWorth.isIn(block, "c:ores") ? ItemWorth.Role.PICKAXE
                : ItemWorth.Role.SHOVEL;
        double speed = 1;
        for (String item : g.bag.keySet()) {
            ItemWorth.Facts f = ItemWorth.facts(item);
            if (f.role() == role && f.digSpeed() > speed) speed = f.digSpeed();
        }
        return hard * HAND_X / speed;
    }

    /** The recipe's batches: the first recipe the bag can pay in full; the table one needs a table in reach. */
    private static Result craft(SimGame g, Step.Craft c) {
        String item = full(c.item());
        if (c.table() && !g.stationNear("minecraft:crafting_table")) return Result.fail(0, "craft " + c.item() + ": no crafting table in reach of " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2]);
        String lacks = null;
        for (ItemWorth.Recipe r : ItemWorth.recipes(item)) {
            if (!ItemWorth.CRAFT.equals(r.kind())) continue;
            int batches = (c.n() + r.outCount() - 1) / r.outCount();
            Map<String, Integer> pay = pay(g.bag, r, batches);
            if (pay == null) { lacks = r.inputs().toString(); continue; }
            for (var e : pay.entrySet()) g.take(e.getKey(), e.getValue());
            g.give(item, batches * r.outCount());
            return Result.ok(batches * (c.table() ? TABLE_CRAFT_S : CRAFT_S), "made " + batches * r.outCount() + " " + c.item() + " from " + pay);
        }
        return Result.fail(0, "craft " + c.item() + ": the bag lacks the inputs " + (lacks == null ? "(no craft recipe known)" : lacks) + "; bag " + g.bag);
    }

    static final double PLACE_S = 0.6;           // measured (47 places of a table or furnace near me, median)
    static final double SMELT_S = 10;            // structural: the game's 200 ticks a furnace item
    static final int SMELT_TICKS = 200;          // structural: the same in ticks, against the fuel table's burn ticks

    /** The item's block goes to the nearest free cell within 3 of the feet with ground under it (the fork picks the cell in the game). */
    private static Result placeNear(SimGame g, String item) {
        if (g.bag.getOrDefault(item, 0) < 1) return Result.fail(0, "place: no " + item + " in the bag " + g.bag);
        int[] best = null;
        for (int dy = -1; dy <= 1; dy++) for (int dz = -3; dz <= 3; dz++) for (int dx = -3; dx <= 3; dx++) {
            int[] c = {g.feet[0] + dx, g.feet[1] + dy, g.feet[2] + dz};
            if (dx == 0 && dz == 0) continue;
            if (!g.world.open(c[0], c[1], c[2])) continue;
            String under = g.world.at(c[0], c[1] - 1, c[2]);
            if (under.equals(SimWorld.AIR) || under.equals(Scenario.NOT_LOADED)) continue;
            if (best == null || SimWorld.dist(c, g.feet) < SimWorld.dist(best, g.feet)) best = c;
        }
        double roomS = 0;
        if (best == null) {
            // boxed in (the bottom of a dug shaft): room is dug for it - the block beside the feet. ASSUMED: the game's place is
            // the fork's and its cell is Baritone's pick; whether it breaks a block for the cell is not checked against the game
            for (int[] s : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int[] c = {g.feet[0] + s[0], g.feet[1], g.feet[2] + s[1]};
                String b = g.world.at(c[0], c[1], c[2]), under = g.world.at(c[0], c[1] - 1, c[2]);
                if (b.equals(Scenario.NOT_LOADED) || b.equals("minecraft:chest") || g.mine.contains(c[0] + " " + c[1] + " " + c[2]) || under.equals(SimWorld.AIR) || under.equals(Scenario.NOT_LOADED)) continue;
                roomS = digS(g, b) + DIG_EXTRA_S;
                g.world.set(c[0], c[1], c[2], SimWorld.AIR);
                if (SimWorld.drop(b) != null) g.give(SimWorld.drop(b), 1);
                best = c;
                break;
            }
        }
        if (best == null) return Result.fail(0, "place " + item + ": no cell within 3 blocks of " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2]);
        g.take(item, 1);
        g.world.set(best[0], best[1], best[2], item);
        g.mine.add(best[0] + " " + best[1] + " " + best[2]);
        return Result.ok(PLACE_S + roomS, "placed at " + best[0] + " " + best[1] + " " + best[2] + (roomS > 0 ? " (dug room for it)" : ""));
    }

    /** My station of the item in reach goes back to the bag. */
    private static Result takeNear(SimGame g, String item) {
        for (String cell : g.mine) {
            String[] p = cell.split(" ");
            int[] c = {Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
            if (item.equals(g.world.at(c[0], c[1], c[2])) && SimWorld.dist(c, g.feet) <= REACH + 1) return take(g, item, c);
        }
        return Result.fail(0, "take: no " + item + " of mine in reach of " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2]);
    }

    private static Result take(SimGame g, String item, int[] c) {
        if (!item.equals(g.world.at(c[0], c[1], c[2]))) return Result.fail(0, "take: no " + item + " at " + c[0] + " " + c[1] + " " + c[2] + " (" + g.world.at(c[0], c[1], c[2]) + ")");
        if (SimWorld.dist(c, g.feet) > REACH + 1) return Result.fail(0, "take: " + item + " at " + c[0] + " " + c[1] + " " + c[2] + " is out of reach of " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2]);
        double s = digS(g, item) + TAKE_EXTRA_S;
        g.world.set(c[0], c[1], c[2], SimWorld.AIR);
        g.mine.remove(c[0] + " " + c[1] + " " + c[2]);
        g.give(item, 1);
        return Result.ok(s, "took it from " + c[0] + " " + c[1] + " " + c[2]);
    }

    /**
     * The furnace in reach turns the recipe's input into {@code n} of the item, 10 s each, on fuel from the bag. The fuel
     * is the game's own rule (CraftPlanner.pickFuel) on the bag less the input.
     */
    private static Result smelt(SimGame g, Step.Smelt m) {
        String item = full(m.item());
        if (!g.stationNear("minecraft:furnace")) return Result.fail(0, "smelt " + m.item() + ": no furnace in reach of " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2]);
        for (ItemWorth.Recipe r : ItemWorth.recipes(item)) {
            if (!ItemWorth.SMELT.equals(r.kind())) continue;
            Map<String, Integer> pay = pay(g.bag, r, m.n());
            if (pay == null) continue;
            Map<String, Integer> left = new HashMap<>(g.bag);
            for (var e : pay.entrySet()) left.merge(e.getKey(), -e.getValue(), Integer::sum);
            var fuel = io.github.mojolowjo.entropybot.craft.CraftPlanner.pickFuel(left, m.n());       // the game's own fuel rule
            if (fuel == null || left.getOrDefault(full(fuel.id()), 0) < fuel.count()) return Result.fail(0, "smelt " + m.item() + ": nothing in the bag burns for " + m.n() + " (CraftPlanner.pickFuel: " + fuel + "); bag " + g.bag);
            Map<String, Integer> burn = Map.of(full(fuel.id()), fuel.count());
            for (var e : pay.entrySet()) g.take(e.getKey(), e.getValue());
            for (var e : burn.entrySet()) g.take(e.getKey(), e.getValue());
            g.give(item, m.n());
            return Result.ok(SMELT_SETUP_S + m.n() * SMELT_S, "smelted " + m.n() + " " + m.item() + " from " + pay + " on " + burn);
        }
        return Result.fail(0, "smelt " + m.item() + ": the bag lacks the input; bag " + g.bag);
    }

    /** What {@code batches} of the recipe take from the bag (each slot one of its options), null: the bag cannot pay. */
    static Map<String, Integer> pay(Map<String, Integer> bag, ItemWorth.Recipe r, int batches) {
        Map<String, Integer> left = new HashMap<>(bag), pay = new HashMap<>();
        for (int b = 0; b < batches; b++)
            for (List<String> slot : r.options()) {
                String use = null;
                for (String opt : slot) {
                    for (String have : new ArrayList<>(left.keySet())) {
                        if (left.get(have) <= 0) continue;
                        if (opt.startsWith("#") ? ItemWorth.isIn(have, opt.substring(1)) : have.equals(full(opt))) { use = have; break; }
                    }
                    if (use != null) break;
                }
                if (use == null) return null;
                left.merge(use, -1, Integer::sum);
                pay.merge(use, 1, Integer::sum);
            }
        return pay;
    }
}
