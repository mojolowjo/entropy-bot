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

    static final double CRAFT_S = 0.3;           // sim: a craft batch's seconds (the planner's scenarios price it so)
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
        if (s instanceof Step.Climb) {
            for (int y = g.feet[1] + 2; y <= g.world.box[4]; y++) if (!g.world.open(g.feet[0], y, g.feet[2])) return Result.NOT_SIMULATED;   // under something: slice 4
            return Result.ok(0, "nothing over the feet: at the surface");
        }
        return Result.NOT_SIMULATED;
    }

    static String full(String id) { return id.contains(":") ? id : "minecraft:" + id; }

    /** The feet go to the cell, or to {@code near} short of it on the straight line. */
    private static Result walk(SimGame g, Step.Walk w) {
        int[] to = {w.x(), w.y(), w.z()};
        java.util.Set<Long> from = g.world.reach(g.feet);
        double d = SimWorld.dist(g.feet, to);
        double go = Math.max(0, d - Math.max(0, w.near()));
        if (d > 0 && go > 0) {
            double f = go / d;
            g.feet[0] += (int) Math.round((to[0] - g.feet[0]) * f);
            g.feet[2] += (int) Math.round((to[2] - g.feet[2]) * f);
            if (w.near() == 0) g.feet[1] = to[1];
        }
        String snap = stand(g);
        if (snap != null) return Result.fail(FakePaths.walkS(go), snap);
        if (!from.contains(SimWorld.cell(g.feet))) return Result.ok(FakePaths.walkS(go), "at " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2] + THROUGH);
        return Result.ok(FakePaths.walkS(go), "at " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2]);
    }

    /**
     * After a straight-line move on real terrain: the feet go to the standable cell of their column nearest their height
     * (the body is not simulated: no search, no climb cost - step 3). null: standing; else why not (no such cell: said).
     */
    /** Said on a move whose end has no open way from its start: the game's body would dig, pillar or fail there. */
    static final String THROUGH = " (THROUGH BLOCKS: no open way there - the sim's straight line; in the game a dig, a pillar or no path)";

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
        boolean through = false;
        while (got < n) {
            List<SimWorld.Group> groups = g.world.groups(g.feet, Step.Collect.MINE_SIGHT + REACH, gives);
            SimWorld.Group pick = groups.isEmpty() ? null : groups.get(0);
            // the named spot's own group first, in sight or not (the fork's chop / the mine goes to the spot the plan priced)
            if (at != null) for (SimWorld.Group gr : g.world.groups(at, 1, gives)) if (gr.cells().stream().anyMatch(c -> c[0] == at[0] && c[1] == at[1] && c[2] == at[2])) {
                List<int[]> whole = new ArrayList<>();
                for (SimWorld.Group far : g.world.groups(at, SimGame.SIGHT, gives)) if (far.block().equals(gr.block()) && far.cells().stream().anyMatch(c -> c[0] == at[0] && c[1] == at[1] && c[2] == at[2])) whole = far.cells();
                whole.sort(java.util.Comparator.comparingDouble(c -> SimWorld.dist(c, g.feet)));
                pick = new SimWorld.Group(gr.block(), whole);
                groups = List.of(pick);
                break;
            }
            if (groups.isEmpty()) break;
            int[] c = pick.cells().get(0);
            double d = SimWorld.dist(c, g.feet);
            if (d > REACH) {
                java.util.Set<Long> from = g.world.reach(g.feet);
                double go = d - REACH + 1, f = go / d;
                g.feet[0] += (int) Math.round((c[0] - g.feet[0]) * f);
                g.feet[2] += (int) Math.round((c[2] - g.feet[2]) * f);
                secs += FakePaths.walkS(go);
                String snap = stand(g);
                if (snap != null) return Result.fail(secs, snap);
                through |= !from.contains(SimWorld.cell(g.feet));
            }
            for (int[] cell : wholeGroup ? pick.cells() : List.of(c)) {
                secs += digS(g, pick.block());
                g.world.set(cell[0], cell[1], cell[2], SimWorld.AIR);
                g.give(SimWorld.drop(pick.block()), 1);
                got++;
            }
        }
        if (got < n) return Result.fail(secs, "got " + got + " of " + n + " " + what + ": no more in sight of " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2]);
        return Result.ok(secs, "got " + got + " " + what + (through ? THROUGH : ""));
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
            return Result.ok(batches * CRAFT_S, "made " + batches * r.outCount() + " " + c.item() + " from " + pay);
        }
        return Result.fail(0, "craft " + c.item() + ": the bag lacks the inputs " + (lacks == null ? "(no craft recipe known)" : lacks) + "; bag " + g.bag);
    }

    static final double PLACE_S = 0.15;          // sim: a block placed (the planner's scenarios price it so)
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
        if (best == null) return Result.fail(0, "place " + item + ": no cell within 3 blocks of " + g.feet[0] + " " + g.feet[1] + " " + g.feet[2]);
        g.take(item, 1);
        g.world.set(best[0], best[1], best[2], item);
        g.mine.add(best[0] + " " + best[1] + " " + best[2]);
        return Result.ok(PLACE_S, "placed at " + best[0] + " " + best[1] + " " + best[2]);
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
        double s = digS(g, item);
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
            return Result.ok(m.n() * SMELT_S, "smelted " + m.n() + " " + m.item() + " from " + pay + " on " + burn);
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
