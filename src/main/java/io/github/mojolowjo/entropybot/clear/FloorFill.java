package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import io.github.mojolowjo.entropybot.storage.StorageRules;

import static io.github.mojolowjo.entropybot.clear.ClearRules.floor;

/**
 * B7e F (2026-10-04): {@code dig ... floor [block]}. After the box is cleared, the layer under it (y1 - 1, the box's
 * x/z footprint) gets a block in every cell that is air or replaceable: a walkway bridged over a cave, never the cave
 * itself. Game-free: the cell choice ({@link #select}), the bridging order ({@link #order}), the block
 * ({@link #chooseBlock}) and the tick loop ({@link Run}) over a {@link ClearWorld}; what it does to the game goes
 * through {@link Run.Body}.
 *
 * <p>Safety: never into or next to water/lava, only cells the guard would lease ({@code allowed}: inside an area,
 * outside protect boxes), only from stand spots the fence allows ({@code standOk}), and on the walkway: the fill stands
 * with its feet above the layer. A bot that finds itself below it (in the cave) first walks back up, or, when no walk
 * gets there, puts down the fewest blocks at its feet that make a step up (counted in the report) - so it never seals
 * itself under the new floor and never ends a dig in the cave.
 */
public final class FloorFill {
    private FloorFill() {}

    /** Without a named block: the first of these it carries (plain stone-like junk, nothing that falls or opens). */
    public static final List<String> FALLBACK = List.of("minecraft:cobbled_deepslate", "minecraft:cobblestone",
            "minecraft:deepslate", "minecraft:tuff", "minecraft:stone", "minecraft:andesite", "minecraft:diorite",
            "minecraft:granite", "minecraft:dirt");

    public static final String LIQUID = "next to water/lava";
    public static final String FENCED = "outside my areas or in a protect box";
    public static final String NO_SUPPORT = "nothing to place it against";
    public static final String NO_STAND = "nowhere to stand within reach";
    public static final String NO_REACH = "couldn't get within reach";
    public static final String DIDNT_STAY = "it didn't stay (3 tries)";

    /** The most step blocks it puts down below the floor to climb out of a cave. */
    public static final int MAX_STEPS = 6;

    /** The layer it fills: y1 - 1. */
    public static int floorY(ClearBox box) {
        return box.y1() - 1;
    }

    // ---- cells ----

    /** A cell that takes a block: air, or no collision without fluid, block entity or built block (grass yes; a torch, a rail no). */
    public static boolean fillable(ClearWorld w, int x, int y, int z) {
        if (w.air(x, y, z)) return true;
        return w.noCollision(x, y, z) && !w.fluid(x, y, z) && !w.blockEntity(x, y, z) && !w.builtBlock(x, y, z);
    }

    /** Water or lava in the cell or in any of its 6 neighbours. */
    public static boolean liquidAround(ClearWorld w, int x, int y, int z) {
        if (w.fluid(x, y, z)) return true;
        for (int[] s : ClearEngine.SIDES6) if (w.fluid(x + s[0], y + s[1], z + s[2])) return true;
        return false;
    }

    /** One of the 6 neighbours can be clicked to put a block into the cell. */
    public static boolean supported(ClearWorld w, Pos c) {
        for (PlaceRules.Side s : PlaceRules.SIDES) {
            if (PlaceRules.clickable(w, c.x() + s.dx(), c.y() + s.dy(), c.z() + s.dz())) return true;
        }
        return false;
    }

    /** What {@link #select} found: the cells to fill (bridging order) and the ones it leaves, with why. */
    public record Selection(List<Pos> todo, Map<Pos, String> skipped) {}

    /**
     * The floor layer's cells that need a block: air or replaceable, not fluid; a cell next to water/lava or one
     * {@code allowed} refuses (null: all allowed) is skipped and listed. Solid cells are left alone.
     */
    public static Selection select(ClearWorld w, ClearBox box, Predicate<Pos> allowed) {
        int y = floorY(box);
        List<Pos> cells = new ArrayList<>();
        Map<Pos, String> skipped = new LinkedHashMap<>();
        for (int x = box.x1(); x <= box.x2(); x++) {
            for (int z = box.z1(); z <= box.z2(); z++) {
                Pos p = new Pos(x, y, z);
                if (!fillable(w, x, y, z)) {
                    if (w.fluid(x, y, z)) skipped.put(p, LIQUID);
                    continue;
                }
                if (liquidAround(w, x, y, z)) skipped.put(p, LIQUID);
                else if (allowed != null && !allowed.test(p)) skipped.put(p, FENCED);
                else cells.add(p);
            }
        }
        return new Selection(order(w, cells), skipped);
    }

    /**
     * Bridging ranks: 0 for a cell with a clickable neighbour now, k for one next to (sideways) a rank k-1 cell; a cell
     * no chain reaches gets {@link Integer#MAX_VALUE}.
     */
    public static Map<Pos, Integer> ranks(ClearWorld w, List<Pos> cells) {
        Set<Pos> set = new HashSet<>(cells);
        Map<Pos, Integer> rank = new HashMap<>();
        ArrayDeque<Pos> q = new ArrayDeque<>();
        for (Pos c : cells) {
            if (supported(w, c)) {
                rank.put(c, 0);
                q.add(c);
            }
        }
        int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        while (!q.isEmpty()) {
            Pos c = q.poll();
            int r = rank.get(c);
            for (int[] d : dirs) {
                Pos n = new Pos(c.x() + d[0], c.y(), c.z() + d[1]);
                if (set.contains(n) && !rank.containsKey(n)) {
                    rank.put(n, r + 1);
                    q.add(n);
                }
            }
        }
        for (Pos c : cells) rank.putIfAbsent(c, Integer.MAX_VALUE);
        return rank;
    }

    /** The cells in bridging order: the ones touching something solid first, then outward; x, then z within a rank. */
    public static List<Pos> order(ClearWorld w, List<Pos> cells) {
        Map<Pos, Integer> rank = ranks(w, cells);
        List<Pos> out = new ArrayList<>(cells);
        out.sort(Comparator.<Pos>comparingInt(rank::get).thenComparingInt(Pos::x).thenComparingInt(Pos::z));
        return out;
    }

    // ---- the block ----

    /** The block to place: the named one while it has any (nothing else then), else the first of {@link #FALLBACK} it carries; null: none. */
    public static String chooseBlock(String named, Map<String, Integer> inv) {
        if (named != null) return inv.getOrDefault(named, 0) > 0 ? named : null;
        for (String id : FALLBACK) if (inv.getOrDefault(id, 0) > 0) return id;
        return null;
    }

    private static final java.util.regex.Pattern VALUABLE_BLOCK = java.util.regex.Pattern.compile(
            "(_ore$|ancient_debris|:(iron|gold|diamond|emerald|netherite|lapis|redstone|coal|copper|raw_iron|raw_gold|raw_copper|amethyst|quartz)_block$|_ingot$|_nugget$)");
    private static final java.util.regex.Pattern OPENS = java.util.regex.Pattern.compile(
            "(chest|barrel|furnace|smoker|shulker_box|_bed$|_sign$|hopper|spawner|crafting_table|dispenser|dropper|beacon|lectern|"
                    + "jukebox|enchanting_table|brewing_stand|campfire|_banner$|_skull$|_head$|crafter|vault|trial_spawner)");

    /** Why a named block may not become the floor (by id; the game adds BlockItem, full block, block entity), or null. */
    public static String namedProblem(String id) {
        String full = ClearRules.fullId(id);
        if (StorageRules.VALUABLE.matcher(full).find() || VALUABLE_BLOCK.matcher(full).find()) return "it's valuable";
        if (ClearRules.falling(ClearRules.blockName(full))) return "it falls";
        if (OPENS.matcher(full).find()) return "it's a container or machine";
        return null;
    }

    // ---- the report ----

    /**
     * The dig's end message: the clear's own report (its "broke N blocks" made the total over the rounds) plus the
     * fill's ("filled N floor cells...").
     */
    public static String endMessage(String clearMsg, int totalBroken, String fillReport) {
        String m = clearMsg == null ? "ok: done" : clearMsg.replaceFirst(" - broke \\d+ blocks", " - broke " + totalBroken + " blocks");
        return m + "; " + fillReport;
    }

    static String shortId(String id) {
        return id == null ? "" : ClearRules.blockName(id);
    }

    // ---- the run ----

    /** One fill, tick by tick. {@link #tick} answers true once it has ended; {@link #report} says how. */
    public static final class Run {
        /** What the fill does to the game (the Minecraft side, or a test's fake). */
        public interface Body {
            Bot bot();

            long now();

            /** The bag: item id -> count. */
            Map<String, Integer> inventory();

            /** Puts block item id into the cell (holding it, with the cell's place lease): "ok: ..." or "error: ...". */
            String place(Pos cell, String id);

            void walkBlock(int x, int y, int z);

            boolean walkIdle();

            void cancelWalk();

            void status(String s);

            void log(String s);
        }

        final ClearWorld w;
        final ClearBox box;
        final int fy;
        final String named;
        final Predicate<Pos> allowed;
        final ClearJob.StandCheck standOk;
        final boolean climbOnly;
        final LinkedHashSet<Pos> todo = new LinkedHashSet<>();
        Map<Pos, Integer> rank = new HashMap<>();
        /** cells it couldn't fill and why, in the order it gave up on them */
        public final Map<Pos, String> failed = new LinkedHashMap<>();
        private final Set<Pos> clicked = new HashSet<>(), stepClicked = new HashSet<>(), badSteps = new HashSet<>();
        private final Map<Pos, Integer> tries = new HashMap<>(), walkFails = new HashMap<>();
        private final Set<String> badSpots = new HashSet<>();
        /** totals carried from earlier rounds of the same dig */
        private int carriedFilled, carriedSteps;
        int filled, steps, noBlocks, consecWalkFails, climbWalkFails;
        String endNote, lastBlock;
        private String phase = "pick";
        private Pos target;
        private boolean targetStep, climbing;
        private ClearGrid.Spot spot;
        private long phaseStart, lastTick = -1, moveTick;
        private double mx, my, mz;
        private boolean done;

        /**
         * @param named     the block the owner named (full id), or null for {@link #FALLBACK}
         * @param allowed   cells the guard would lease for placing (null: all)
         * @param standOk   where it may stand (null: anywhere)
         * @param climbOnly only get back up onto the walkway (before the clear), never fill
         */
        public Run(ClearWorld w, ClearBox box, String named, Predicate<Pos> allowed, ClearJob.StandCheck standOk, boolean climbOnly) {
            this.w = w;
            this.box = box;
            this.fy = floorY(box);
            this.named = named;
            this.allowed = allowed;
            this.standOk = standOk;
            this.climbOnly = climbOnly;
            if (!climbOnly) {
                Selection s = select(w, box, allowed);
                todo.addAll(s.todo());
                failed.putAll(s.skipped());
            }
        }

        /** A later round of the same dig: its counts go on from the earlier round's. */
        public Run carry(Run prev) {
            if (prev != null) {
                carriedFilled = prev.filledTotal();
                carriedSteps = prev.stepsTotal();
                lastBlock = prev.lastBlock;
            }
            return this;
        }

        /** Floor cells filled this round. */
        public int filled() { return filled; }

        public int filledTotal() { return carriedFilled + filled; }

        public int stepsTotal() { return carriedSteps + steps; }

        /** Cells still to do (none once it ended, except those it ran out of blocks for). */
        public int left() { return todo.size(); }

        public boolean done() { return done; }

        public String phase() { return phase; }

        /** "filled N floor cells", the step blocks, running out of blocks, and up to 5 cells it couldn't fill (then "+N more"). */
        public String report() {
            int n = filledTotal(), st = stepsTotal();
            StringBuilder sb = new StringBuilder("filled ").append(n).append(" floor cell").append(n == 1 ? "" : "s");
            if (lastBlock != null && n > 0) sb.append(" with ").append(shortId(lastBlock));
            if (st > 0) sb.append("; put ").append(st).append(" block").append(st == 1 ? "" : "s").append(" below it to climb out of the cave");
            if (noBlocks > 0) {
                sb.append("; ran out of ").append(named != null ? shortId(named) : "blocks").append(" - ").append(noBlocks)
                  .append(" floor cell").append(noBlocks == 1 ? "" : "s").append(" left");
            }
            if (!failed.isEmpty()) {
                List<String> ex = new ArrayList<>();
                for (Map.Entry<Pos, String> e : failed.entrySet()) {
                    if (ex.size() >= 5) break;
                    ex.add(e.getKey().key() + " (" + e.getValue() + ")");
                }
                sb.append("; couldn't fill ").append(failed.size()).append(": ").append(String.join(", ", ex));
                if (failed.size() > 5) sb.append(", +").append(failed.size() - 5).append(" more");
            }
            if (endNote != null) sb.append("; ").append(endNote);
            return sb.toString();
        }

        private boolean end() {
            done = true;
            return true;
        }

        private void fail(Pos c, String why) {
            if (todo.remove(c)) failed.put(c, why);
        }

        /** One tick; true once the fill has ended. */
        public boolean tick(Body b) {
            if (done) return true;
            long now = b.now();
            // a reflex (a fight, a meal) held the job: the walk it was on is stale
            if (lastTick >= 0 && now - lastTick > 5 && phase.equals("walk")) {
                b.cancelWalk();
                phase = "pick";
                target = null;
            }
            lastTick = now;
            switch (phase) {
                case "check": return check(b, now);
                case "wait":
                    if (now - phaseStart >= 10) phase = "pick";
                    return false;
                case "walk": return walk(b, now);
                default: return pick(b, now);
            }
        }

        // ---- pick ----

        private boolean pick(Body b, long now) {
            Bot bot = b.bot();
            tidy();
            double far = box.distTo(bot.x(), bot.y(), bot.z());
            if (far > 24) {
                for (Pos c : new ArrayList<>(todo)) fail(c, "too far away (" + Math.round(far) + " blocks)");
                return end();
            }
            if (feet(bot) <= fy) return climb(b, bot, now);
            if (climbOnly || todo.isEmpty()) return end();
            String id = chooseBlock(named, b.inventory());
            if (id == null) {
                noBlocks = todo.size();
                return end();
            }
            List<Pos> sup = new ArrayList<>();
            for (Pos c : todo) if (supported(w, c)) sup.add(c);
            if (sup.isEmpty()) {
                for (Pos c : new ArrayList<>(todo)) fail(c, NO_SUPPORT);
                return end();
            }
            // from where it stands: the lowest rank, then the nearest
            Pos best = null;
            double bestD = 0;
            int bestR = 0;
            for (Pos c : sup) {
                if (!placeableFrom(bot.x(), bot.y(), bot.z(), bot.eyeY(), c)) continue;
                int r = rank.getOrDefault(c, Integer.MAX_VALUE);
                double d = ClearEngine.eyeDistSq(bot.x(), bot.eyeY(), bot.z(), c.x(), c.y(), c.z());
                if (best == null || r < bestR || (r == bestR && d < bestD)) {
                    best = c;
                    bestR = r;
                    bestD = d;
                }
            }
            if (best != null) return placeNow(b, best, id, false, now);
            // walk: the cheapest stand spot on the walkway among the nearest supported cells
            sup.sort(Comparator.<Pos>comparingInt(c -> rank.getOrDefault(c, Integer.MAX_VALUE))
                    .thenComparingDouble(c -> sq(c.x() + 0.5 - bot.x()) + sq(c.z() + 0.5 - bot.z())));
            ClearGrid g = grid(bot);
            int[] dist = g.walkDistances(bot);
            ClearGrid.Spot bestSpot = null;
            Pos forCell = null;
            for (int i = 0; i < sup.size() && i < 16; i++) {
                ClearGrid.Spot s = standFor(g, dist, sup.get(i), bot);
                if (s != null && (bestSpot == null || s.cost() < bestSpot.cost())) {
                    bestSpot = s;
                    forCell = sup.get(i);
                }
            }
            if (bestSpot == null) {
                // nowhere to stand for any cell it could place now: the rest has nothing to be placed against either
                for (Pos c : sup) fail(c, NO_STAND);
                for (Pos c : new ArrayList<>(todo)) fail(c, NO_SUPPORT);
                return end();
            }
            target = forCell;
            targetStep = false;
            climbing = false;
            startWalk(b, bestSpot, now);
            b.status("filling the floor: walking to " + bestSpot.key() + " to place " + forCell.key());
            return false;
        }

        /** Drops cells that are solid now (counting the ones it clicked) and cells liquid reached since. */
        private void tidy() {
            for (Pos c : new ArrayList<>(todo)) {
                if (!fillable(w, c.x(), c.y(), c.z())) {
                    todo.remove(c);
                    if (w.fluid(c.x(), c.y(), c.z())) failed.put(c, LIQUID);
                    else if (clicked.contains(c)) filled++;
                } else if (liquidAround(w, c.x(), c.y(), c.z())) {
                    fail(c, LIQUID);
                }
            }
            if (rank.isEmpty() || rank.size() != todo.size()) rank = ranks(w, new ArrayList<>(todo));
        }

        private boolean placeNow(Body b, Pos c, String id, boolean step, long now) {
            String r;
            try {
                r = b.place(c, id);
            } catch (RuntimeException e) {
                r = "error: " + e;
            }
            target = c;
            targetStep = step;
            if (r != null && r.startsWith("ok")) {
                (step ? stepClicked : clicked).add(c);
                lastBlock = id;
                phase = "check";
                phaseStart = now;
                b.status("filling the floor: placing " + shortId(id) + " at " + c.key() + (step ? " (a step to climb out)" : ""));
                return false;
            }
            String why = r == null ? "no answer" : r.replaceFirst("^error: ", "");
            b.log("couldn't place at " + c.key() + ": " + why);
            if (why.contains("the guard refused")) {
                if (step) badSteps.add(c);
                else fail(c, FENCED);
                phase = "pick";
                return false;
            }
            if (tries.merge(c, 1, Integer::sum) >= 3) {
                if (step) badSteps.add(c);
                else fail(c, why);
            }
            phase = "wait";
            phaseStart = now;
            return false;
        }

        /** Placed blocks show up a few ticks later; 3 tries a cell. */
        private boolean check(Body b, long now) {
            if (now - phaseStart < 6) return false;
            Pos c = target;
            target = null;
            phase = "pick";
            if (c == null) return false;
            if (!fillable(w, c.x(), c.y(), c.z())) {
                if (targetStep) {
                    steps++;
                    b.log("put a step at " + c.key() + " to climb out of the cave");
                } else if (todo.remove(c)) {
                    filled++;
                }
                consecWalkFails = 0;
                return false;
            }
            if (tries.merge(c, 1, Integer::sum) >= 3) {
                if (targetStep) badSteps.add(c);
                else fail(c, DIDNT_STAY);
            }
            return false;
        }

        // ---- walking ----

        private void startWalk(Body b, ClearGrid.Spot s, long now) {
            spot = s;
            b.walkBlock(s.x(), s.y(), s.z());
            Bot bot = b.bot();
            phase = "walk";
            phaseStart = now;
            mx = bot.x();
            my = bot.y();
            mz = bot.z();
            moveTick = now;
        }

        private boolean walk(Body b, long now) {
            long el = now - phaseStart;
            Bot bot = b.bot();
            Pos t = target;
            // in reach on the way (and on the walkway, unless this is a step): place it now
            if (t != null && el > 0 && el % 10 == 0 && (targetStep || feet(bot) > fy) && placeableFrom(bot.x(), bot.y(), bot.z(), bot.eyeY(), t)) {
                b.cancelWalk();
                String id = chooseBlock(named, b.inventory());
                if (id != null) return placeNow(b, t, id, targetStep, now);
            }
            if (el < 20) return false;
            boolean idle = b.walkIdle(), stuck = false;
            if (!idle && now - moveTick >= 160) {
                stuck = Math.abs(bot.x() - mx) + Math.abs(bot.y() - my) + Math.abs(bot.z() - mz) < 0.5;
                mx = bot.x();
                my = bot.y();
                mz = bot.z();
                moveTick = now;
            }
            if (!idle && !stuck && el < 20L * 30) return false;
            if (!idle) b.cancelWalk();
            phase = "pick";
            boolean there = spot != null && Math.abs(bot.x() - spot.x() - 0.5) + Math.abs(bot.z() - spot.z() - 0.5) + Math.abs(bot.y() - spot.y()) <= 1.5;
            if (!there && spot != null) badSpots.add(spot.key());
            if (climbing) {
                climbing = false;
                if (feet(bot) <= fy) climbWalkFails++;
                target = null;
                return false;
            }
            if (t == null) return false;
            target = null;
            boolean ok = (targetStep || feet(bot) > fy) && placeableFrom(bot.x(), bot.y(), bot.z(), bot.eyeY(), t);
            if (ok) return false;                    // the next pick places it from here
            if (targetStep) {
                badSteps.add(t);
                return false;
            }
            consecWalkFails++;
            if (walkFails.merge(t, 1, Integer::sum) >= 2) fail(t, NO_REACH);
            if (consecWalkFails >= 4) {
                for (Pos c : new ArrayList<>(todo)) fail(c, NO_REACH);
                return end();
            }
            return false;
        }

        // ---- getting back up onto the walkway ----

        /**
         * Below the floor layer (in the cave or the hole): walk to a walkway spot when a walk gets there; else put down the
         * one block at its feet that lets it climb highest (a step), from where it stands or a spot it can walk to; at most
         * {@link #MAX_STEPS}. Nothing helps: it says where it is stuck and ends.
         */
        private boolean climb(Body b, Bot bot, long now) {
            ClearGrid g = grid(bot);
            int[] dist = g.walkDistances(bot);
            ClearGrid.Spot up = climbable(g, dist);
            if (up != null && climbWalkFails < 2) {
                target = null;
                climbing = true;
                startWalk(b, up, now);
                b.status("filling the floor: climbing back up to " + up.key());
                return false;
            }
            String id = chooseBlock(named, b.inventory());
            if (id == null) {
                endNote = "I'm below the floor at " + key(bot) + " and have no blocks to climb out";
                noBlocks = todo.size();
                return end();
            }
            if (steps >= MAX_STEPS) {
                endNote = "I'm below the floor at " + key(bot) + " - " + MAX_STEPS + " steps didn't get me out";
                return end();
            }
            Step st = chooseStep(g, dist, bot);
            if (st == null) {
                endNote = "I'm below the floor at " + key(bot) + " and can't climb out (no step I may place)";
                return end();
            }
            if (st.from() == null) return placeNow(b, st.cell(), id, true, now);
            target = st.cell();
            targetStep = true;
            climbing = false;
            startWalk(b, st.from(), now);
            b.status("filling the floor: going to " + st.from().key() + " to put a step at " + st.cell().key());
            return false;
        }

        /** The nearest walkway spot (feet on the floor layer inside the footprint, or higher in the box) a walk reaches. */
        private ClearGrid.Spot climbable(ClearGrid g, int[] dist) {
            ClearGrid.Spot best = null;
            for (int x = box.x1(); x <= box.x2(); x++) {
                for (int z = box.z1(); z <= box.z2(); z++) {
                    for (int y = fy + 1; y <= box.y2(); y++) {
                        int i = g.idx(x, y, z);
                        if (i < 0 || dist[i] < 0 || !g.stand(x, y, z) || g.wet(x, y, z)) continue;
                        if (standOk != null && !standOk.ok(x, y, z)) continue;
                        if (badSpots.contains(Pos.key(x, y, z))) continue;
                        if (best == null || dist[i] < best.cost()) best = new ClearGrid.Spot(x, y, z, dist[i], x + 0.5, y + Bot.EYE, z + 0.5);
                    }
                }
            }
            return best;
        }

        /** A step: the cell, and the spot to place it from (null: from where it stands). */
        record Step(Pos cell, ClearGrid.Spot from) {}

        /**
         * The step block: a free cell at the feet level of a spot it can walk to (beside it), at or below the floor layer,
         * that it may place (lease, no liquid), placeable from where it stands or from that spot. Each is tried on a copy
         * of the world with the block in: the best gets it onto the walkway, else highest, then the shortest walk. Null
         * when none raises it.
         */
        Step chooseStep(ClearGrid g, int[] dist, Bot bot) {
            int curMax = maxFeet(g, dist);
            int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
            Step best = null;
            boolean bestUp = false;
            int bestMax = curMax, bestCost = Integer.MAX_VALUE;
            Set<Pos> seen = new HashSet<>();
            for (int x = g.ax; x <= g.bx; x++) {
                for (int y = g.ay; y <= Math.min(g.by, fy); y++) {
                    for (int z = g.az; z <= g.bz; z++) {
                        int i = g.idx(x, y, z);
                        if (dist[i] < 0 || dist[i] > 16) continue;
                        if (standOk != null && !standOk.ok(x, y, z)) continue;
                        for (int[] d : dirs) {
                            Pos c = new Pos(x + d[0], y, z + d[1]);
                            if (!seen.add(c) || badSteps.contains(c) || c.y() > fy) continue;
                            if (!fillable(w, c.x(), c.y(), c.z()) || liquidAround(w, c.x(), c.y(), c.z())) continue;
                            if (allowed != null && !allowed.test(c)) continue;
                            if (!supported(w, c)) continue;
                            // from here, else from the spot beside it
                            ClearGrid.Spot from = null;
                            double ox = bot.x(), oy = bot.y(), oz = bot.z();
                            int cost = 0;
                            if (!placeableFrom(ox, oy, oz, bot.eyeY(), c)) {
                                if (!placeableFrom(x + 0.5, y, z + 0.5, y + Bot.EYE, c)) continue;
                                from = new ClearGrid.Spot(x, y, z, dist[i], x + 0.5, y + Bot.EYE, z + 0.5);
                                ox = x + 0.5;
                                oy = y;
                                oz = z + 0.5;
                                cost = dist[i];
                            }
                            Overlay o = new Overlay(w, c);
                            Bot after = Bot.at(ox, oy, oz);
                            ClearGrid g2 = ClearGrid.build(o, around(after), after, null);
                            int[] d2 = g2.walkDistances(after);
                            boolean up = new Run(o, box, named, allowed, standOk, true).climbableIn(g2, d2);
                            int mx2 = maxFeetIn(g2, d2);
                            if (!up && mx2 <= curMax) continue;
                            boolean better = best == null || (up && !bestUp) || (up == bestUp && (mx2 > bestMax || (mx2 == bestMax && cost < bestCost)));
                            if (!better) continue;
                            best = new Step(c, from);
                            bestUp = up;
                            bestMax = mx2;
                            bestCost = cost;
                        }
                    }
                }
            }
            return best;
        }

        private boolean climbableIn(ClearGrid g, int[] dist) {
            return climbable(g, dist) != null;
        }

        private int maxFeet(ClearGrid g, int[] dist) {
            return maxFeetIn(g, dist);
        }

        private int maxFeetIn(ClearGrid g, int[] dist) {
            int m = Integer.MIN_VALUE;
            for (int x = g.ax; x <= g.bx; x++) {
                for (int y = g.ay; y <= g.by; y++) {
                    for (int z = g.az; z <= g.bz; z++) {
                        int i = g.idx(x, y, z);
                        if (dist[i] >= 0 && y > m && (standOk == null || standOk.ok(x, y, z))) m = y;
                    }
                }
            }
            return m;
        }

        // ---- reach and stand spots ----

        /** From feet x y z (eye ey) a neighbour of c is in reach, and the bot's body isn't in c. */
        boolean placeableFrom(double x, double y, double z, double ey, Pos c) {
            if (bodyHits(x, y, z, c)) return false;
            return PlaceRules.placeSide(w, c.x(), c.y(), c.z(), x, ey, z) != null;
        }

        /** The stand spot for a floor cell: on the walkway (feet above the layer), the fence's, not in water, cheapest walk. */
        ClearGrid.Spot standFor(ClearGrid g, int[] dist, Pos c, Bot bot) {
            ClearGrid.Spot best = null;
            for (int x = c.x() - 4; x <= c.x() + 4; x++) {
                for (int z = c.z() - 4; z <= c.z() + 4; z++) {
                    for (int y = fy + 1; y <= fy + 3; y++) {
                        int i = g.idx(x, y, z);
                        if (i < 0 || !g.stand(x, y, z) || g.wet(x, y, z) || g.wet(x, y + 1, z)) continue;
                        if (standOk != null && !standOk.ok(x, y, z)) continue;
                        if (badSpots.contains(Pos.key(x, y, z))) continue;
                        double cost = dist[i] >= 0 ? dist[i] : 1000 + Math.sqrt(sq(x - bot.x()) + sq(y - bot.y()) + sq(z - bot.z()));
                        if (best != null && cost >= best.cost()) continue;
                        if (!placeableFrom(x + 0.5, y, z + 0.5, y + Bot.EYE, c)) continue;
                        best = new ClearGrid.Spot(x, y, z, cost, x + 0.5, y + Bot.EYE, z + 0.5);
                    }
                }
            }
            return best;
        }

        /** The walking map: the floor layer and the box, plus ClearGrid's margin and the bot. */
        private ClearGrid grid(Bot bot) {
            return ClearGrid.build(w, new ClearBox(box.x1(), fy, box.z1(), box.x2(), box.y2(), box.z2()), bot, null);
        }

        /** A small map around a spot (the step's what-if). */
        private ClearBox around(Bot b) {
            int x = floor(b.x()), y = floor(b.y()), z = floor(b.z());
            int x1 = Math.max(box.x1(), x - 12), x2 = Math.min(box.x2(), x + 12);
            if (x1 > x2) { x1 = x; x2 = x; }
            int z1 = Math.max(box.z1(), z - 12), z2 = Math.min(box.z2(), z + 12);
            if (z1 > z2) { z1 = z; z2 = z; }
            return new ClearBox(x1, Math.min(fy, y), z1, x2, Math.max(box.y2(), y), z2);
        }

        private static int feet(Bot b) {
            return floor(b.y() + 0.01);
        }

        private static String key(Bot b) {
            return Pos.key(floor(b.x()), feet(b), floor(b.z()));
        }
    }

    /** The bot's body (0.6 wide, 1.8 tall, feet at x y z) overlaps cell c. */
    public static boolean bodyHits(double x, double y, double z, Pos c) {
        double e = 1e-3;
        return x + 0.3 > c.x() + e && x - 0.3 < c.x() + 1 - e && y + 1.8 > c.y() + e && y < c.y() + 1 - e
                && z + 0.3 > c.z() + e && z - 0.3 < c.z() + 1 - e;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** The world with one more solid block (a what-if for the climb). */
    static final class Overlay implements ClearWorld {
        final ClearWorld w;
        final Pos c;

        Overlay(ClearWorld w, Pos c) {
            this.w = w;
            this.c = c;
        }

        private boolean at(int x, int y, int z) { return x == c.x() && y == c.y() && z == c.z(); }

        @Override public String id(int x, int y, int z) { return at(x, y, z) ? "minecraft:cobbled_deepslate" : w.id(x, y, z); }
        @Override public String name(int x, int y, int z) { return at(x, y, z) ? "cobbled_deepslate" : w.name(x, y, z); }
        @Override public boolean air(int x, int y, int z) { return !at(x, y, z) && w.air(x, y, z); }
        @Override public boolean fluid(int x, int y, int z) { return !at(x, y, z) && w.fluid(x, y, z); }
        @Override public boolean blockEntity(int x, int y, int z) { return !at(x, y, z) && w.blockEntity(x, y, z); }
        @Override public boolean unbreakable(int x, int y, int z) { return !at(x, y, z) && w.unbreakable(x, y, z); }
        @Override public boolean builtBlock(int x, int y, int z) { return !at(x, y, z) && w.builtBlock(x, y, z); }
        @Override public boolean avoided(int x, int y, int z) { return !at(x, y, z) && w.avoided(x, y, z); }
        @Override public boolean ore(int x, int y, int z) { return !at(x, y, z) && w.ore(x, y, z); }
        @Override public boolean noCollision(int x, int y, int z) { return !at(x, y, z) && w.noCollision(x, y, z); }
        @Override public boolean fullBlock(int x, int y, int z) { return at(x, y, z) || w.fullBlock(x, y, z); }
        @Override public double collisionHeight(int x, int y, int z) { return at(x, y, z) ? 1 : w.collisionHeight(x, y, z); }
        @Override public int blockLight(int x, int y, int z) { return w.blockLight(x, y, z); }
        @Override public Hit clip(double fx, double fy, double fz, double tx, double ty, double tz) { return w.clip(fx, fy, fz, tx, ty, tz); }
    }
}
