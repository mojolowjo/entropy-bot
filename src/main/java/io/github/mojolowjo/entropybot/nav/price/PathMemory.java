/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Entropy Bot 2026-11-27: the fork's PathMemory (fork-only class, from the fork's source at dd8235b7)
 */

package io.github.mojolowjo.entropybot.nav.price;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Entropy z57 (owner 2026-10-25: "while doing stuff it should not forget the path that was already planned"; run P
 * 23:46:34-38: "-33 70 -55 -> -36 64 -60" priced 10 times from one cell, 795 ticks each, while the bot crafted at the
 * table; 180 of 406 priced paths were exact repeats): the priced path's MEMORY - per goal, the kept answer, and for each
 * ask whether it needs a search. A kept complete path stays known until a fact it depends on changes, not a clock:
 * <ul>
 * <li>the feet on cell i of the kept path, or on a valid position of its move i (mid-fall, a diagonal's corner: as
 *     PathExecutor accepts): the price is the same path's remaining cost from cell i (its own per-move costs as the search
 *     priced them; no search) - exact at i = 0, a suffix past it (rule 18: the suffix of a complete path is complete);</li>
 * <li>dropped, with the reason in words (rule 7), when: the feet are on no cell and no move position of it (left the
 *     path); a WATCHED cell of a remaining move (every cell the move depends on: its valid positions with the floor below
 *     and the head above each, its planned breaks and placement) changed by the caller's classification ({@link Blocks}:
 *     Baritone's own predicates, so a crop growing does not drop a path and a closed gate does), except the move's own
 *     planned work reaching its expected result (a planned break now broken, a planned place now placed); what the price
 *     used changed ({@link Stamp}); the world or dimension changed; the long safety age passed;</li>
 * <li>a watched cell of a remaining move that is not loaded now: the answer is "chunks not loaded" (rule 15: not known),
 *     the kept path stays for when the chunk is back (its cells are compared again then);</li>
 * <li>a no-path answer (unreachable, no progress) is kept for its own start cell only and for its short keep (the mod's
 *     pricedPath.keepTicks): it depends on every block the search met, which nothing here tracks; a "chunks not loaded"
 *     answer is not a no-path answer (rule 15: not known): it is kept only until the next chunk loads
 *     ({@link #chunkLoaded}), so the next ask after a load searches (and a bot standing still does not search again at
 *     every re-price into the same unloaded border);</li>
 * <li>the goal already satisfied at the start: 0, no search;</li>
 * <li>one search per goal in flight: every ask while it runs joins it, wherever the feet are (review 2: never cancelled
 *     because the bot moved, so a walking bot's search lands); the caller answers each waiting ask from the feet when it
 *     lands (exact / suffix / dropped and searched again: rule 18); past {@link IPricedPath.Limits#maxRunning} searches
 *     the ask is queued (rule 15: "not known yet", never "none").</li>
 * <li>Entropy z58 (run Q 02:28-02:35: "in flight 4" all run long, "queued +16", the crafting walks "computing" while the
 *     bot explored and the only priced way won): the queue is ordered by {@link Prio} (the running step's goal, a goal not
 *     answered yet in this order, a re-search after a drop for a fact, the rest), {@link IPricedPath.Limits#reserveForRunning}
 *     slots are the running step's only, the caller starts queued asks by {@link #nextToStart} and cancels a running
 *     re-search / rest search for a higher waiting class by {@link #cancelForPriority}; a goal dropped because the bot moved
 *     off its answer is searched again at most once per {@link IPricedPath.Limits#researchMinTicks} (WAIT: "computing").</li>
 * </ul>
 * A dropped memory is "not known" until the new search lands (rule 15). Game thread only (not synchronized).
 * Pure (JUnit: PathMemoryTest, PricedPathBuildTest): K the goal (by equals), R a running search's handle, S what the
 * caller reads at a cell.
 */
public final class PathMemory<K, R, S> {

    /** One block position. */
    public record Cell(int x, int y, int z) {
        public Cell up(int dy) {
            return new Cell(x, y + dy, z);
        }

        public String text() {
            return x + " " + y + " " + z;
        }

        double dist(Cell o) {
            double dx = x - o.x, dy = y - o.y, dz = z - o.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /**
     * What the caller reads at a cell and how two reads compare. {@link #same}: equal by the caller's classification (and,
     * for a cell a move plans to break, the block too: its break time is that block's); {@link #notLoaded}: the cell's chunk
     * is not loaded (never "air"); {@link #broken} / {@link #placed}: the expected result of a planned break / place.
     */
    public interface Blocks<S> {
        S read(Cell c);

        boolean same(S was, S now, boolean breakPlanned);

        boolean notLoaded(S s);

        boolean broken(S s);

        boolean placed(S s);
    }

    /**
     * What a price used besides the blocks, as named values in three groups: {@code all} (every price: sprinting, the rules
     * epoch, the movement settings and enchantments CalculationContext reads), {@code breaking} (compared only for a path
     * that breaks blocks), {@code placing} (only for one that places). Insertion order is the order a difference is named.
     */
    public record Stamp(Map<String, Object> all, Map<String, Object> breaking, Map<String, Object> placing) {
        public Stamp {
            all = Collections.unmodifiableMap(new LinkedHashMap<>(all));
            breaking = Collections.unmodifiableMap(new LinkedHashMap<>(breaking));
            placing = Collections.unmodifiableMap(new LinkedHashMap<>(placing));
        }

        /** Why {@code now} is not what this price used, or null: breaking compared only when it breaks, placing when it places. */
        public String diff(Stamp now, boolean breaks, boolean places) {
            if (now == null) {
                return "what the price used could not be read now";
            }
            String d = diff(all, now.all, "");
            if (d == null && breaks) {
                d = diff(breaking, now.breaking, " (the path breaks blocks)");
            }
            if (d == null && places) {
                d = diff(placing, now.placing, " (the path places blocks)");
            }
            return d;
        }

        private static String diff(Map<String, Object> was, Map<String, Object> now, String why) {
            for (Map.Entry<String, Object> e : was.entrySet()) {
                Object n = now.get(e.getKey());
                if (!Objects.equals(e.getValue(), n)) {
                    return e.getKey() + " changed (" + e.getValue() + " -> " + n + ")" + why;
                }
            }
            for (String k : now.keySet()) {
                if (!was.containsKey(k)) {
                    return k + " changed (none -> " + now.get(k) + ")" + why;
                }
            }
            return null;
        }
    }

    /** One watched cell: the last move that depends on it, the read when its segment landed, planned break / place. */
    public record Watch<S>(int lastMove, S was, boolean breaks, boolean places) {}

    /**
     * A complete path as kept: its cells start first (one per position; a segment's end is the next one's start, once),
     * {@code cum[i]} the search's cost in ticks from the start to cell i ({@code cum[0]} = 0, the last = the price),
     * {@code valid.get(i)} the positions where the bot may stand during move i (cell i to i+1), the watched cells.
     */
    public static final class Path<S> {
        final List<Cell> cells;
        final double[] cum;
        final List<Set<Cell>> valid;
        final Map<Cell, Watch<S>> watch;
        final boolean breaks, places;

        public Path(List<Cell> cells, double[] cum, List<Set<Cell>> valid, Map<Cell, Watch<S>> watch) {
            if (cells.isEmpty() || cum.length != cells.size() || valid.size() != cells.size() - 1) {
                throw new IllegalArgumentException("a kept path needs one cost per cell and one move per step: " + cells.size()
                        + " cells, " + cum.length + " costs, " + valid.size() + " moves");
            }
            this.cells = List.copyOf(cells);
            this.cum = cum.clone();
            List<Set<Cell>> v = new ArrayList<>();
            for (Set<Cell> s : valid) {
                v.add(Set.copyOf(s));
            }
            this.valid = List.copyOf(v);
            this.watch = Map.copyOf(watch);
            boolean b = false, p = false;
            for (Watch<S> w : watch.values()) {
                b |= w.breaks();
                p |= w.places();
            }
            this.breaks = b;
            this.places = p;
        }

        public int size() {
            return cells.size();
        }

        public double total() {
            return cum[cum.length - 1];
        }

        /** D11: the search's cost from the start to cell i (ticks). */
        public double cum(int i) {
            return cum[i];
        }

        /** D11: the cells, start first (read-only). */
        public List<Cell> cells() {
            return cells;
        }

        /** The remaining cost from cell i: the same path's moves after it. */
        public double rest(int i) {
            return total() - cum[i];
        }

        public List<Cell> cells(int from) {
            return cells.subList(from, cells.size());
        }

        public Map<Cell, Watch<S>> watched() {
            return watch;
        }

        /**
         * The feet's index: a cell of the path (at or after {@code from} first), else a valid position of move j (the bot
         * between cell j and j + 1, as PathExecutor accepts), else -1.
         */
        public int indexOf(Cell c, int from) {
            int f = Math.max(0, Math.min(from, cells.size() - 1));
            for (int i = f; i < cells.size(); i++) {
                if (cells.get(i).equals(c)) {
                    return i;
                }
            }
            for (int i = 0; i < f; i++) {
                if (cells.get(i).equals(c)) {
                    return i;
                }
            }
            for (int j = Math.min(f, valid.size()); j < valid.size(); j++) {
                if (valid.get(j).contains(c)) {
                    return j;
                }
            }
            for (int j = 0; j < Math.min(f, valid.size()); j++) {
                if (valid.get(j).contains(c)) {
                    return j;
                }
            }
            return -1;
        }

        /**
         * The first watched cell of a move from {@code from} on that changed unexpectedly ("x y z (before -> after)"), or
         * {@link #NOT_LOADED_MARK} + the cell when one is not loaded now, or null.
         */
        String changed(int from, Blocks<S> b) {
            String notLoaded = null;
            for (Map.Entry<Cell, Watch<S>> e : watch.entrySet()) {
                Watch<S> w = e.getValue();
                if (w.lastMove() < from) {
                    continue;
                }
                S now = b.read(e.getKey());
                String why = judge(e.getKey(), w, now, b);
                if (why == null) {
                    continue;
                }
                if (why.startsWith(NOT_LOADED_MARK)) {
                    notLoaded = why;
                } else {
                    return why;
                }
            }
            return notLoaded;
        }

        /**
         * D11 (the repair): the LAST move among the moves from {@code from} on whose watched cell changed unexpectedly (-1:
         * none) - the join re-joins the path at the first cell after it, so every changed stretch is bypassed at once.
         */
        int changedMove(int from, Blocks<S> b) {
            int last = -1;
            for (Map.Entry<Cell, Watch<S>> e : watch.entrySet()) {
                Watch<S> w = e.getValue();
                if (w.lastMove() < from) {
                    continue;
                }
                String why = judge(e.getKey(), w, b.read(e.getKey()), b);
                if (why != null && !why.startsWith(NOT_LOADED_MARK)) {
                    last = Math.max(last, w.lastMove());
                }
            }
            return last;
        }
    }

    static final String NOT_LOADED_MARK = "\u0000not loaded: ";

    /** Null: unchanged or the planned work's expected result; a not-loaded mark; else "x y z (before -> after)". */
    static <S> String judge(Cell c, Watch<S> w, S now, Blocks<S> b) {
        if (b.notLoaded(now)) {
            return NOT_LOADED_MARK + c.text();
        }
        if (b.same(w.was(), now, w.breaks())) {
            return null;
        }
        if (w.breaks() && b.broken(now) || w.places() && b.placed(now)) {
            return null;
        }
        return c.text() + " (" + w.was() + " -> " + now + ")";
    }

    /**
     * Review 2 item 1 (rule 18): the future an asker gets from a search (started or joined). The search's own answer is
     * from ITS start; when it lands, {@code reask} asks the memory again from the feet at that moment (exact / suffix from
     * the kept path just landed, or dropped and searched again, whose landing answers in turn). {@code passThrough}: answers
     * handed on as they are (an error: nothing to re-ask). Runs where {@code landed} completes (the game thread).
     */
    public static <A> java.util.concurrent.CompletableFuture<A> fromFeet(java.util.concurrent.CompletableFuture<A> landed,
                                                                       java.util.function.Predicate<A> passThrough,
                                                                       java.util.function.Supplier<java.util.concurrent.CompletableFuture<A>> reask) {
        return landed.thenCompose(a -> a == null || passThrough.test(a) ? java.util.concurrent.CompletableFuture.completedFuture(a) : reask.get());
    }

    /**
     * How an ask was answered (z58: WAIT = the re-search wait, {@link IPricedPath#RESEARCH_WAIT}). Entropy D11 (the owner's
     * idea 2026-10-27 / 2026-10-28, "re-price only the part that changed"; run FA: 230 of 330 kept answers dropped because the
     * feet LEFT the path, 4 because a block changed - each then a whole new search): REJOIN - the kept path is kept and the
     * caller searches a short JOIN from the feet to its remaining cells ({@link Decision#cells} from {@link Decision#index});
     * the spliced path lands as the goal's kept path. A changed block mid-path re-joins from the first cell after the
     * changed move (the repair).
     */
    public enum Kind { ZERO, EXACT, SUFFIX, KEPT_NO_PATH, NOT_LOADED, JOIN, QUEUED, START, WAIT, REJOIN }

    /**
     * Entropy z58 (D3 pricing queue; run Q 02:29:32: every slot held by re-searches of alternatives while the crafting walks
     * stayed "computing"): a search's priority class, first first - the running step's goal ({@link #runningGoal}), a goal
     * not answered yet in this order, a re-search after a kept answer was dropped for a fact (left it, a block changed, what
     * the price used changed), the rest (an answer that aged out, a chunk loaded). The class only orders searches; which way
     * wins is the caller's scorer (rule 1).
     */
    public enum Prio { RUNNING, FIRST, RESEARCH, REST }

    /**
     * One ask's decision: {@code answer} for ZERO / EXACT / SUFFIX / KEPT_NO_PATH / NOT_LOADED (a path's cells from
     * {@code index} on are {@link #cells}); {@code join} the running search to wait on (JOIN: the caller answers from the
     * feet when it lands); {@code dropped} why a kept answer was dropped (null: none was, or none kept) - D11: for REJOIN,
     * why the kept path is re-joined (left the path at ..., block changed at ...; nothing dropped), from {@code index}.
     */
    public record Decision<R>(Kind kind, IPathCost.Answer answer, int index, List<Cell> cells, R join, String dropped) {}

    /** A walk's view: the kept path and the feet's index on it, or why not (none kept, no path, dropped, not loaded). */
    public record Walk<S>(Path<S> path, int index, Object extra, String why) {}

    private static final class Kept<S> {
        final IPathCost.Answer answer;
        final Path<S> path;          // null: no path
        final Cell start;
        final long atTick;
        final Object world;
        final Stamp stamp;
        final Object extra;
        final long landing;
        int progress;
        /** D11: the block hook found a changed cell of move m: the next ask re-joins from cell m + 1 (-1: none marked). */
        int repairFrom = -1;

        Kept(IPathCost.Answer answer, Path<S> path, Cell start, long atTick, Object world, Stamp stamp, Object extra, long landing) {
            this.answer = answer;
            this.path = path;
            this.start = start;
            this.atTick = atTick;
            this.world = world;
            this.stamp = stamp;
            this.extra = extra;
            this.landing = landing;
        }
    }

    /** z58 review 2: a running search keeps the class it started with (an orderStarted does not move it). */
    private record Running<R>(Cell start, R handle, Prio prio) {}

    /**
     * The world as one ask sees it. Review 2 item 3: {@code stamp} and {@code blocks} are read only when a kept answer for
     * the goal is there to compare against (the caller's suppliers build them then; a ZERO answer or a first ask builds none).
     */
    public record Now<S>(Cell feet, boolean inGoal, Object world, java.util.function.Supplier<Stamp> stamp, long tick,
                         java.util.function.Supplier<Blocks<S>> blocks) {}

    private final Map<K, Kept<S>> kept = new HashMap<>();
    /** z58: insertion order = start order (cancelForPriority takes the newest of the lowest class). */
    private final Map<K, Running<R>> running = new LinkedHashMap<>();
    /** The goals whose kept path watches a cell (the block-update hook's index). */
    private final Map<Cell, Set<K>> watchers = new HashMap<>();
    private long landings;
    /**
     * Counts since the start (see the index constants). D11: [11] re-joins asked, [12] joins failed (dropped after); D11 fix
     * (run FF): [13] re-joins queued for a slot.
     */
    private final long[] counts = new long[14];
    /** structural: drop lines kept for the probe between two reads (older ones are counted, not kept) */
    static final int MAX_DROPS = 64;
    private final ArrayDeque<String> drops = new ArrayDeque<>();
    private long dropsLost;

    public static final int ASKS = 0, ZERO = 1, EXACT = 2, SUFFIX = 3, KEPT_NO_PATH = 4, JOINED = 5, STARTED = 6, QUEUED = 7, // structural: count slots
            CANCELLED = 8, DROPPED = 9, NOT_LOADED = 10, REJOINS = 11, JOINS_FAILED = 12, JOINS_QUEUED = 13;

    /**
     * Entropy z58: {@link #classCounts} indices - searches started per class (+ Prio ordinal), queued per class (+ ordinal),
     * re-search / rest searches cancelled for a higher class, re-search waits answered, queued asks that left the queue.
     */
    public static final int C_STARTED = 0, C_QUEUED = 4, C_CANCELLED_RESEARCH = 8, C_CANCELLED_REST = 9, C_WAITS = 10, C_LEFT_QUEUE = 11; // structural: count slots
    private final long[] classCounts = new long[12];

    /** Entropy z58: the running step's goal (null: none). */
    private K runningGoal;

    /**
     * Entropy z60 item 1 (run R 03:34:54-03:35:34: the order's first answers were asked in the chain's order, far and near
     * alike): the straight-line distance from the feet to a goal, used ONLY to order queued first answers - the nearest
     * starts first (rule 18: never a price; the scorer still picks the way, rule 1). Null (or NaN / infinite for a goal with
     * no position): the order asked, as z58.
     */
    private java.util.function.ToDoubleFunction<K> distance;

    /** Entropy z60 item 1: how far a goal is from the feet now, for the first answers' order only (null: by ask order). */
    public void distance(java.util.function.ToDoubleFunction<K> d) {
        distance = d;
    }

    /** z60 item 1: the goal's distance for ordering, +infinity when unknown (a goal with no position sorts last in its class). */
    private double dist(K goal) {
        if (distance == null) return Double.POSITIVE_INFINITY;
        double d = distance.applyAsDouble(goal);
        return Double.isNaN(d) ? Double.POSITIVE_INFINITY : d;
    }

    /**
     * z60 item 1: does queued ask {@code a} (class {@code p}, asked first at {@code af}) go before {@code b} (asked at
     * {@code bf}) of the same class: for first answers the nearer first, then the one asked first; other classes by ask order.
     */
    private boolean before(K a, long af, K b, long bf, Prio p) {
        if (p == Prio.FIRST && distance != null) {
            double da = dist(a), db = dist(b);
            if (da != db) return da < db;
        }
        return af < bf;
    }
    /** Entropy z58: goals with an answer landed since the order started (not in it: class FIRST). */
    private final Set<K> answered = new HashSet<>();
    /** Entropy z58: goals whose kept answer was dropped for a fact (class RESEARCH until their next landing). */
    private final Set<K> reSearch = new HashSet<>();
    /** Entropy z58 item 2: per goal, the tick its answer was dropped because the bot moved off it (the re-search wait). */
    private final Map<K, Long> leftAt = new HashMap<>();
    /** D11: per goal, the tick its kept path was last re-joined (a join per re-search wait at most; not cleared by a start). */
    private final Map<K, Long> rejoinedAt = new HashMap<>();
    /**
     * D11 fix (run FF: 3,036 priced paths started in 25 minutes, 2,196 of them re-joins - every REJOIN answer started its join
     * search at once, past the slots and the queue): per goal queued for a slot, the kept path's cell the re-join aims from.
     * The pump's start of that goal is the join ({@link #takeQueuedRejoin}), never a whole search.
     */
    private final Map<K, Integer> queuedJoin = new HashMap<>();
    /** How the last {@link #check} dropped a kept answer. */
    private enum DropKind { MOVED, FACT, STALE }
    private DropKind lastDrop;

    /** Entropy z58: one queued ask - first and last asked, and the tick before which it may not start (the re-search wait). */
    private static final class Waiting {
        final long first;
        long last;
        long notBefore = Long.MIN_VALUE;

        Waiting(long first) {
            this.first = first;
            this.last = first;
        }
    }

    /** Entropy z58: the queued asks, the oldest first. */
    private final Map<K, Waiting> waiting = new LinkedHashMap<>();

    /** One ask for {@code goal}: memory, join, queue or start (the caller then registers the search with {@link #started}). */
    public Decision<R> ask(K goal, Now<S> now, IPricedPath.Limits lim) {
        counts[ASKS]++;
        if (now.inGoal()) {
            counts[ZERO]++;
            waiting.remove(goal);
            return new Decision<>(Kind.ZERO, IPathCost.Answer.complete(0), 0, List.of(now.feet()), null, null);
        }
        String dropped = null;
        Kept<S> k = kept.get(goal);
        if (k != null) {
            String why = check(k, now, lim);
            if (why != null && why.startsWith(NOT_LOADED_MARK)) {
                counts[NOT_LOADED]++;
                waiting.remove(goal);
                return new Decision<>(Kind.NOT_LOADED, IPathCost.Answer.none(IPathCost.Answer.CHUNKS), k.progress, List.of(), null, null);
            }
            if (why == null && k.path == null) {
                counts[KEPT_NO_PATH]++;
                waiting.remove(goal);
                return new Decision<>(Kind.KEPT_NO_PATH, k.answer, 0, List.of(), null, null);
            }
            if (why == null) {
                int i = k.progress;
                counts[i == 0 ? EXACT : SUFFIX]++;
                waiting.remove(goal);
                return new Decision<>(i == 0 ? Kind.EXACT : Kind.SUFFIX, IPathCost.Answer.complete(k.path.rest(i)), i,
                        k.path.cells(i), null, null);
            }
            // D11: a complete path the feet LEFT, or one with a changed block ahead, is kept and re-joined - a short search from
            // the feet to its remaining cells (from the first cell after the last changed move), the spliced path lands as the
            // kept path; never while a search for the goal runs (joined below), never inside the re-search wait (WAIT below)
            int from = rejoinFrom(k, now);
            if (from >= 0) {
                Running<R> r0 = running.get(goal);
                if (r0 != null) {                                 // the join (or a whole search) runs: joined, the path kept
                    counts[JOINED]++;
                    return new Decision<>(Kind.JOIN, null, 0, List.of(), r0.handle(), null);
                }
                Prio p = prio(goal);
                if (queuedJoin.containsKey(goal) && waiting.containsKey(goal)) {
                    // D11 fix: queued for a slot already - still queued (asked again: it stays wanted), its aim refreshed
                    queuedJoin.put(goal, from);
                    queue(goal, now.tick());
                    counts[QUEUED]++;
                    classCounts[C_QUEUED + p.ordinal()]++;
                    return new Decision<>(Kind.QUEUED, null, from, List.of(), null, null);
                }
                Long left = leftAt.get(goal), joined = rejoinedAt.get(goal);
                long since = left == null ? Long.MAX_VALUE : now.tick() - left;
                if (joined != null) since = Math.min(since, now.tick() - joined);
                if (since < lim.researchMinTicks() && p != Prio.RUNNING) {
                    // inside the re-search wait: not known yet, the path kept; NOT queued (a queued ask would start a whole
                    // search when due, the very search the re-join replaces) - the next ask past the wait re-joins
                    classCounts[C_WAITS]++;
                    return new Decision<>(Kind.WAIT, IPathCost.Answer.none(IPricedPath.RESEARCH_WAIT), 0, List.of(), null, null);
                }
                counts[REJOINS]++;
                reSearch.add(goal);
                p = prio(goal);                                   // RESEARCH now (unless the running step's or a first answer)
                rejoinedAt.put(goal, now.tick());                 // one re-join per re-search wait (a bot walking away re-joins once, not per landing)
                // D11 fix (run FF): a join is a search - it takes a slot of its class through the same gate as a whole search
                // (z58 item 1: a free slot, no due queued ask ahead of it); none free: queued, started by the pump as a JOIN
                Waiting mine = waiting.get(goal);
                if (canStart(p, lim) && !aheadOf(goal, p, mine == null ? now.tick() : mine.first, now.tick(), lim)) {
                    waiting.remove(goal);
                    return new Decision<>(Kind.REJOIN, null, from, k.path.cells(from), null, why);   // dropped = why the re-join (rule 7), nothing dropped
                }
                queuedJoin.put(goal, from);
                queue(goal, now.tick());
                counts[QUEUED]++;
                counts[JOINS_QUEUED]++;
                classCounts[C_QUEUED + p.ordinal()]++;
                return new Decision<>(Kind.QUEUED, null, from, List.of(), null, why);
            }
            dropped = why;
            drop(goal, why);
            // z58: a drop for a fact makes the next search a re-search; one because the bot moved starts the re-search wait
            if (lastDrop != DropKind.STALE) {
                reSearch.add(goal);
            }
            if (lastDrop == DropKind.MOVED) {
                leftAt.put(goal, now.tick());
            }
        }
        // review 2 item 4: a running search is never cancelled because the bot moved - every later ask joins it, it lands
        // and each waiting ask is answered from the feet at that moment (the caller re-asks the memory: exact / suffix /
        // dropped and searched again; rule 18)
        Running<R> r = running.get(goal);
        if (r != null) {
            counts[JOINED]++;
            return new Decision<>(Kind.JOIN, null, 0, List.of(), r.handle(), dropped);
        }
        // z58 item 2 (run Q: a landing judged from the feet found them off the path, dropped it and searched again at once,
        // holding the slot for ever while the bot explored): the re-search waits lim.researchMinTicks after that drop; until
        // then the ask is "computing" with the reason (rule 15), never the old price (rule 18) - the fork starts it when due
        // z58 review 1: never for the running step's own walk (its price must not wait behind its own re-search)
        Prio p = prio(goal);
        Long left = leftAt.get(goal);
        if (left != null && p != Prio.RUNNING && now.tick() - left < lim.researchMinTicks()) {
            Waiting w = queue(goal, now.tick());
            w.notBefore = left + lim.researchMinTicks();
            classCounts[C_WAITS]++;
            return new Decision<>(Kind.WAIT, IPathCost.Answer.none(IPricedPath.RESEARCH_WAIT), 0, List.of(), null, dropped);
        }
        // z58 item 1: a free slot for this class, and no queued ask ahead of it (a higher class, or the same class asked first)
        Waiting mine = waiting.get(goal);
        if (canStart(p, lim) && !aheadOf(goal, p, mine == null ? now.tick() : mine.first, now.tick(), lim)) {
            return new Decision<>(Kind.START, null, 0, List.of(), null, dropped);
        }
        queue(goal, now.tick());
        counts[QUEUED]++;
        classCounts[C_QUEUED + p.ordinal()]++;
        return new Decision<>(Kind.QUEUED, null, 0, List.of(), null, dropped);
    }

    /**
     * D11: where a re-join of the kept path {@code k} aims after {@link #check} said why it does not stand: the feet LEFT a
     * complete path (MOVED) - its cells from the last progress; a changed block ahead (the FACT "block changed", or the block
     * hook's mark) - from the first cell after the last changed move. -1: no re-join (no path, a stamp or world change, the
     * age, or the change at the path's very end: a whole search then).
     */
    private int rejoinFrom(Kept<S> k, Now<S> now) {
        if (k.path == null || lastDrop == DropKind.STALE) {
            return -1;
        }
        int last = k.path.size() - 1;
        // what the price used changed (sprinting, the tools, the throwaway blocks): the kept costs are off - no re-join
        if (k.stamp != null && k.stamp.diff(now.stamp().get(), k.path.breaks, k.path.places) != null) {
            return -1;
        }
        if (lastDrop == DropKind.MOVED) {
            int from = k.repairFrom >= 0 ? k.repairFrom : k.progress;
            return from < last ? from : -1;
        }
        int m = k.repairFrom >= 0 ? k.repairFrom - 1 : k.path.changedMove(k.progress, now.blocks().get());
        if (m < 0) {
            return -1;
        }
        int from = Math.max(k.progress, m + 1);
        return from < last ? from : -1;
    }

    /**
     * D11: the join search for {@code goal} (started under it, {@link #started}) found no way back to the kept path, or stopped
     * on its budget: the kept path is dropped (said: the drop's reason and the join's), the next search is a whole one after
     * the re-search wait. False: the handle is not the goal's running search (a cancelled join: nothing dropped).
     */
    public boolean rejoinFailed(K goal, R handle, String why, long tick) {
        Running<R> r = running.get(goal);
        if (r == null || r.handle() != handle) {
            return false;
        }
        running.remove(goal);
        counts[JOINS_FAILED]++;
        Kept<S> k = kept.get(goal);
        if (k != null) {
            drop(goal, "the re-join of the kept path found no way (" + why + "): a whole search next");
        }
        reSearch.add(goal);
        leftAt.put(goal, tick);
        return true;
    }

    /**
     * D11 fix (run FF): the queued goal {@code goal} is to be started as a RE-JOIN of its kept path from this cell index (the
     * pump's {@link QueuePump.Starter#start} reads it right before starting, and starts the join search instead of a whole
     * one); -1: a whole search (nothing queued as a join, or the kept path is gone). The mark is taken (read once).
     */
    public int takeQueuedRejoin(K goal) {
        Integer from = queuedJoin.remove(goal);
        Kept<S> k = kept.get(goal);
        return from == null || k == null || k.path == null || from >= k.path.size() - 1 ? -1 : from;
    }

    /**
     * D11 fix: a join search cancelled for a higher class ({@link #cancelForPriority} queued its goal again) is started again as
     * a join from {@code from}, not as a whole search - while the goal is queued and its path kept.
     */
    public void queueRejoin(K goal, int from) {
        if (from >= 0 && waiting.containsKey(goal) && kept.get(goal) != null && kept.get(goal).path != null) {
            queuedJoin.put(goal, from);
        }
    }

    /** D11: the goal's kept path (null: none kept, or a no-path answer): the caller's join search aims at its cells. */
    public Path<S> path(K goal) {
        Kept<S> k = kept.get(goal);
        return k == null ? null : k.path;
    }

    /** The search for {@code goal} from {@code start} runs now (after a START decision, or {@link #nextToStart}). */
    public void started(K goal, Cell start, R handle) {
        Prio p = prio(goal);
        classCounts[C_STARTED + p.ordinal()]++;
        running.put(goal, new Running<>(start, handle, p));
        counts[STARTED]++;
        waiting.remove(goal);
        queuedJoin.remove(goal);
        leftAt.remove(goal);
    }

    /** Entropy z58: the goal's priority class now. */
    public Prio prio(K goal) {
        if (runningGoal != null && runningGoal.equals(goal)) {
            return Prio.RUNNING;
        }
        if (!answered.contains(goal)) {
            return Prio.FIRST;
        }
        return reSearch.contains(goal) ? Prio.RESEARCH : Prio.REST;
    }

    /** Entropy z58: the running step's goal (null: none). */
    public void runningGoal(K goal) {
        runningGoal = goal;
    }

    /** Entropy z58: a new order - no goal is answered in it yet, and the queue is emptied (the order asks again). */
    public void orderStarted() {
        answered.clear();
        waiting.clear();
        queuedJoin.clear();
        leftAt.clear();                              // review 5: no queued ask is left to wait
    }

    /**
     * z58 review 2: a running search's class - the running step's when its goal is the running goal now (never cancelled),
     * else the class it started with: a search of the old order still in flight keeps its old class after orderStarted.
     */
    Prio runPrio(K goal) {
        if (runningGoal != null && runningGoal.equals(goal)) {
            return Prio.RUNNING;
        }
        Running<R> r = running.get(goal);
        return r == null ? prio(goal) : r.prio();
    }

    /** A queued ask may start now: past its re-search wait, or the running step's goal (review 1: it never waits). */
    private boolean due(K goal, Waiting w, long tick) {
        return w.notBefore <= tick || prio(goal) == Prio.RUNNING;
    }

    /**
     * Entropy z58: a search of class {@code p} may start now - fewer than maxRunning run, and for any class but the running
     * step's, fewer than maxRunning - reserveForRunning searches of the other classes run (the reserved slots are the
     * running step's only).
     */
    boolean canStart(Prio p, IPricedPath.Limits lim) {
        if (running.size() >= lim.maxRunning()) {
            return false;
        }
        if (p == Prio.RUNNING) {
            return true;
        }
        int others = 0;
        for (K g : running.keySet()) {
            if (runPrio(g) != Prio.RUNNING) {
                others++;
            }
        }
        return others < lim.maxRunning() - lim.reserveForRunning();
    }

    /** A queued ask that may start now and ranks before ({@code p}, {@code first}): a higher class, or the same class asked first. */
    private boolean aheadOf(K goal, Prio p, long first, long tick, IPricedPath.Limits lim) {
        for (Map.Entry<K, Waiting> e : waiting.entrySet()) {
            Waiting w = e.getValue();
            if (e.getKey().equals(goal) || !due(e.getKey(), w, tick) || tick - w.last > lim.queueKeepTicks() || running.containsKey(e.getKey())) {
                continue;
            }
            Prio q = prio(e.getKey());
            if (q.ordinal() < p.ordinal() || q == p && before(e.getKey(), w.first, goal, first, p)) {   // z60 item 1: nearer first answers first
                return true;
            }
        }
        return false;
    }

    private Waiting queue(K goal, long tick) {
        Waiting w = waiting.get(goal);
        if (w == null) {
            w = new Waiting(tick);
            waiting.put(goal, w);
        } else {
            w.last = Math.max(w.last, tick);
        }
        return w;
    }

    /** Queued asks not asked again within lim.queueKeepTicks leave the queue (counted: nobody wants them now). */
    private void prune(long tick, IPricedPath.Limits lim) {
        Iterator<Map.Entry<K, Waiting>> it = waiting.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<K, Waiting> e = it.next();
            if (tick - e.getValue().last > lim.queueKeepTicks() || running.containsKey(e.getKey())) {
                if (!running.containsKey(e.getKey())) {
                    classCounts[C_LEFT_QUEUE]++;
                    leftAt.remove(e.getKey());       // review 5: not searched again - its wait goes with it
                    rejoinedAt.remove(e.getKey());   // D11: the re-join's wait too
                }
                queuedJoin.remove(e.getKey());
                it.remove();
            }
        }
        // review 5: a re-search wait that is over and was never asked again is forgotten (the guard reads younger ones only)
        leftAt.values().removeIf(left -> tick - left >= lim.researchMinTicks());
        rejoinedAt.values().removeIf(at -> tick - at >= lim.researchMinTicks());     // D11: the same for the re-join's wait
    }

    /**
     * Entropy z58 item 1: the queued goal to start now (the caller starts its search and calls {@link #started}), null when
     * none may: the highest class first, then the one asked first, among those due (past the re-search wait) with a free slot
     * for their class.
     */
    public K nextToStart(long tick, IPricedPath.Limits lim) {
        prune(tick, lim);
        K best = null;
        Prio bp = null;
        long bf = 0;
        for (Map.Entry<K, Waiting> e : waiting.entrySet()) {
            Waiting w = e.getValue();
            if (!due(e.getKey(), w, tick)) {
                continue;
            }
            Prio q = prio(e.getKey());
            if (!canStart(q, lim)) {
                continue;
            }
            if (best == null || q.ordinal() < bp.ordinal() || q == bp && before(e.getKey(), w.first, best, bf, q)) {   // z60 item 1
                best = e.getKey();
                bp = q;
                bf = w.first;
            }
        }
        return best;
    }

    /**
     * Entropy z58 item 1: a running search to cancel for a higher class, or null - when a queued ask is due and no slot is free
     * for its class, the running re-search or rest search of the lowest class below it (the newest of them) goes; a running
     * step's search or a first answer's is never cancelled. The cancelled goal is queued again (asked first now) and counted;
     * its search's landing is ignored from now on (the caller cancels the search and answers its askers "queued").
     */
    public R cancelForPriority(long tick, IPricedPath.Limits lim) {
        prune(tick, lim);
        Prio want = null;
        for (Map.Entry<K, Waiting> e : waiting.entrySet()) {
            if (!due(e.getKey(), e.getValue(), tick)) {
                continue;
            }
            Prio q = prio(e.getKey());
            if (!canStart(q, lim) && (want == null || q.ordinal() < want.ordinal())) {
                want = q;
            }
        }
        if (want == null) {
            return null;
        }
        K victim = null;
        Prio vp = null;
        for (K g : running.keySet()) {               // insertion order: the last one of the lowest class is the newest
            Prio q = runPrio(g);
            if ((q == Prio.RESEARCH || q == Prio.REST) && q.ordinal() > want.ordinal() && (vp == null || q.ordinal() >= vp.ordinal())) {
                victim = g;
                vp = q;
            }
        }
        if (victim == null) {
            return null;
        }
        R handle = running.remove(victim).handle();
        counts[CANCELLED]++;
        classCounts[vp == Prio.RESEARCH ? C_CANCELLED_RESEARCH : C_CANCELLED_REST]++;
        queue(victim, tick);
        return handle;
    }

    /**
     * Entropy z58 (Opus Design: the bot stands still while first answers are pending): searches running plus asks queued of
     * class {@code p} now (FIRST: goals not answered yet in this order whose answer is on its way).
     */
    public int pending(Prio p) {
        int n = 0;
        for (K g : running.keySet()) {
            if (runPrio(g) == p) {
                n++;
            }
        }
        for (K g : waiting.keySet()) {
            if (!running.containsKey(g) && prio(g) == p) {
                n++;
            }
        }
        return n;
    }

    /** Entropy z58: the queue's counts since the start (see the C_ indices). */
    public long[] classCounts() {
        return classCounts.clone();
    }

    /**
     * Entropy J3-fork (z76 part 2, IPricedPath#cancel): the ask for {@code goal} is withdrawn - its running search leaves the
     * running list (its slot is free now, its landing is ignored: {@link #landed} finds no running entry) and the goal
     * leaves the queue; a kept answer stays. Returns the running search's handle (the caller cancels it and answers its
     * askers), or null when none ran; {@code queued[0]} says whether a queued ask was dropped.
     */
    public R withdraw(K goal, boolean[] queued) {
        boolean q = waiting.remove(goal) != null;
        queuedJoin.remove(goal);
        leftAt.remove(goal);
        if (queued != null && queued.length > 0) {
            queued[0] = q;
        }
        Running<R> r = running.remove(goal);
        return r == null ? null : r.handle();
    }

    /** Entropy z58: the goal leaves the queue (the caller found it needs no search: the bot stands in it). */
    public void unqueue(K goal) {
        waiting.remove(goal);
        queuedJoin.remove(goal);
    }

    /** Entropy z58: asks queued now (a test and the probe read it). */
    public int queuedCount() {
        return waiting.size();
    }

    /** Entropy CPU item 3: a near goal's kept path the feet are on: that goal, the feet's index, its remaining price and cells. */
    public record Share<K>(K goal, int index, IPathCost.Answer answer, List<Cell> cells) {}

    /**
     * Entropy CPU item 3: for an ask of {@code goal} with no kept answer and no search, another goal's kept PATH that is
     * {@code near} it and still stands for the feet ({@link #check}: not stale, feet on it, no stamp or block change) - its
     * remaining price (SUFFIX). Read-only: a kept answer that does not stand is left as it is (its own next ask drops it).
     * Null: none.
     */
    public Share<K> shareKept(K goal, Now<S> now, IPricedPath.Limits lim, java.util.function.BiPredicate<K, K> near) {
        for (Map.Entry<K, Kept<S>> e : kept.entrySet()) {
            K other = e.getKey();
            Kept<S> k = e.getValue();
            if (other.equals(goal) || k.path == null || !near.test(goal, other)) {
                continue;
            }
            int before = k.progress;
            DropKind drop = lastDrop;
            if (check(k, now, lim) == null) {
                int i = k.progress;
                return new Share<>(other, i, IPathCost.Answer.complete(k.path.rest(i)), k.path.cells(i));
            }
            k.progress = before;                                  // read-only for a kept answer that does not stand
            lastDrop = drop;
        }
        return null;
    }

    /** Entropy CPU item 3: the running search of a goal {@code near} this one (null: none), joined instead of a second search. */
    public R runningNear(K goal, java.util.function.BiPredicate<K, K> near) {
        for (Map.Entry<K, Running<R>> e : running.entrySet()) {
            if (!e.getKey().equals(goal) && near.test(goal, e.getKey())) {
                return e.getValue().handle();
            }
        }
        return null;
    }

    /** D11 build 2: a search (or join) for the goal runs now. */
    public boolean isRunning(K goal) {
        return running.containsKey(goal);
    }

    /** D11 build 2: an answer (a path or a no-path) is kept for the goal now. */
    public boolean hasKept(K goal) {
        return kept.containsKey(goal);
    }

    /** Running searches now. */
    public int runningCount() {
        return running.size();
    }

    /** The running searches' handles (a copy). */
    public Collection<R> runningHandles() {
        List<R> out = new ArrayList<>();
        for (Running<R> r : running.values()) {
            out.add(r.handle());
        }
        return out;
    }

    /**
     * A search landed: its answer is kept for the goal (a path's cells, costs and watched cells in {@code path}; null for no
     * path) when it is still this goal's running search; a cancelled one is ignored. Returns true when kept.
     */
    public boolean landed(K goal, R handle, IPathCost.Answer answer, Path<S> path, Cell start, Object world, Stamp stamp,
                          long tick, Object extra) {
        Running<R> r = running.get(goal);
        if (r == null || r.handle() != handle) {
            return false;
        }
        running.remove(goal);
        keep(goal, answer, path, start, world, stamp, tick, extra);
        return true;
    }

    /** An answer kept without a search (the goal's own chunk not loaded), or a landed one. */
    public void keep(K goal, IPathCost.Answer answer, Path<S> path, Cell start, Object world, Stamp stamp, long tick, Object extra) {
        unwatch(goal);
        answered.add(goal);                          // z58: answered in this order; its next search is a re-search only after a drop
        reSearch.remove(goal);
        waiting.remove(goal);
        kept.put(goal, new Kept<>(answer, path, start, tick, world, stamp, extra, ++landings));
        if (path != null) {
            for (Cell c : path.watch.keySet()) {
                watchers.computeIfAbsent(c, x -> new HashSet<>()).add(goal);
            }
        }
    }

    /** The kept answer's landing number (a new one each time an answer is kept for any goal), -1 when none is kept. */
    public long landing(K goal) {
        Kept<S> k = kept.get(goal);
        return k == null ? -1 : k.landing;
    }

    /**
     * Kept answers whose world is gone or whose age passed are dropped (said); called when a search lands so the memory
     * stays the size of the goals asked lately.
     */
    public void sweep(Object world, long tick, IPricedPath.Limits lim) {
        List<Map.Entry<K, String>> out = new ArrayList<>();
        for (Map.Entry<K, Kept<S>> e : kept.entrySet()) {
            String why = staleness(e.getValue(), world, tick, lim);
            if (why != null) {
                out.add(Map.entry(e.getKey(), why));
            }
        }
        for (Map.Entry<K, String> e : out) {
            drop(e.getKey(), e.getValue());
        }
    }

    /**
     * The walk to {@code goal} from here: the kept path and the feet's index on it when it is still valid (nothing is
     * counted as an ask), else why not (a stale one is dropped, said; a not-loaded one is kept).
     */
    public Walk<S> walk(K goal, Now<S> now, IPricedPath.Limits lim) {
        Kept<S> k = kept.get(goal);
        if (k == null) {
            return new Walk<>(null, -1, null, null);
        }
        if (k.path == null) {
            return new Walk<>(null, -1, k.extra, "the price found no path (" + k.answer + ")");
        }
        String why = check(k, now, lim);
        if (why != null && why.startsWith(NOT_LOADED_MARK)) {
            return new Walk<>(null, -1, k.extra, "a cell of the kept path is not loaded (" + why.substring(NOT_LOADED_MARK.length()) + ")");
        }
        if (why != null && rejoinFrom(k, now) >= 0) {
            // D11: the walk offers nothing (Baritone searches its own way), the kept path stays for the ask's re-join
            return new Walk<>(null, -1, k.extra, why + " - the kept path is kept for a re-join (the price's short search); this walk searches");
        }
        if (why != null) {
            drop(goal, why);
            return new Walk<>(null, -1, k.extra, why);
        }
        return new Walk<>(k.path, k.progress, k.extra, null);
    }

    /**
     * A block update at {@code c} (Baritone's onBlockChange): the goals whose kept path watches {@code c} (an index, no scan
     * of every path) are judged for that cell - a remaining move's cell (from the feet's index on) that changed unexpectedly
     * drops the path at once (said). A path the feet are not on is left for the next ask ("left the path"); a not-loaded
     * read changes nothing. Returns the paths dropped.
     */
    public int blockChanged(Cell c, S now, Cell feet, Blocks<S> b) {
        Set<K> goals = watchers.get(c);
        if (goals == null || goals.isEmpty()) {
            return 0;
        }
        List<Map.Entry<K, String>> out = new ArrayList<>();
        for (K g : goals) {
            Kept<S> k = kept.get(g);
            if (k == null || k.path == null) {
                continue;
            }
            Watch<S> w = k.path.watch.get(c);
            int from = feet == null ? -1 : k.path.indexOf(feet, k.progress);
            if (w == null || from < 0 || w.lastMove() < from) {
                continue;
            }
            String why = judge(c, w, now, b);
            if (why != null && !why.startsWith(NOT_LOADED_MARK)) {
                // D11 (the repair): the change at the path's very end drops it (a whole search); any other marks the kept path
                // for a re-join from the first cell after the changed move at the next ask - the path stays
                if (w.lastMove() + 1 < k.path.size() - 1) {
                    k.repairFrom = Math.max(k.repairFrom, w.lastMove() + 1);
                    reSearch.add(g);
                    drops.addLast("to " + g + ": block changed at " + why + " (block update): kept for a re-join from its cell " + k.repairFrom + " at the next ask");
                    continue;
                }
                out.add(Map.entry(g, "block changed at " + why + " (block update)"));
            }
        }
        for (Map.Entry<K, String> e : out) {
            drop(e.getKey(), e.getValue());
            reSearch.add(e.getKey());                // z58: dropped for a fact - its next search is a re-search
        }
        return out.size();
    }

    /**
     * Review 2 item 2 (rule 15): a chunk loaded - every kept "chunks not loaded" answer is dropped (said), so the next ask
     * searches. Returns the answers dropped.
     */
    public int chunkLoaded(String where) {
        List<K> out = new ArrayList<>();
        for (Map.Entry<K, Kept<S>> e : kept.entrySet()) {
            IPathCost.Answer a = e.getValue().answer;
            if (a.noPath && IPathCost.Answer.CHUNKS.equals(a.reason)) {
                out.add(e.getKey());
            }
        }
        for (K g : out) {
            drop(g, "a chunk loaded (" + where + "): the \"chunks not loaded\" answer is asked again");
        }
        return out.size();
    }

    /** The kept answer's extra (the caller's segments) while the goal's answer is kept, else null. */
    public Object extra(K goal) {
        Kept<S> k = kept.get(goal);
        return k == null ? null : k.extra;
    }

    /**
     * {asks, zero, exact, suffix, kept no-path, joined, started, queued, cancelled (always 0 since review 2: nothing is
     * cancelled for a move), dropped, not loaded} since the start.
     */
    public long[] counts() {
        return counts.clone();
    }

    /** Cells watched by any kept path (the hook's index size; a test reads it). */
    public int watchedCells() {
        return watchers.size();
    }

    /** The drop lines since the last call (older ones past {@link #MAX_DROPS} are counted in a last line). */
    public List<String> drainDrops() {
        if (drops.isEmpty() && dropsLost == 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>(drops);
        if (dropsLost > 0) {
            out.add(dropsLost + " older drop line(s) not kept (more than " + MAX_DROPS + " between two reads)");
        }
        drops.clear();
        dropsLost = 0;
        return Collections.unmodifiableList(out);
    }

    /** Every kept answer and running search is forgotten (the caller cancels the searches it holds). */
    public void forget() {
        kept.clear();
        running.clear();
        watchers.clear();
        waiting.clear();
        queuedJoin.clear();
        answered.clear();
        reSearch.clear();
        leftAt.clear();
        rejoinedAt.clear();
    }

    private void unwatch(K goal) {
        Kept<S> k = kept.get(goal);
        if (k == null || k.path == null) {
            return;
        }
        for (Cell c : k.path.watch.keySet()) {
            Set<K> s = watchers.get(c);
            if (s != null) {
                s.remove(goal);
                if (s.isEmpty()) {
                    watchers.remove(c);
                }
            }
        }
    }

    private void drop(K goal, String why) {
        unwatch(goal);
        kept.remove(goal);
        queuedJoin.remove(goal);                     // D11 fix: no path to re-join - a queued start is a whole search
        counts[DROPPED]++;
        if (drops.size() >= MAX_DROPS) {
            drops.pollFirst();
            dropsLost++;
        }
        drops.addLast("to " + goal + ": " + why);
    }

    private static <S> String staleness(Kept<S> k, Object world, long tick, IPricedPath.Limits lim) {
        if (k.world != world) {
            return "the world or dimension changed";
        }
        long age = tick - k.atTick;
        int keep = k.path == null ? lim.noPathKeepTicks() : lim.keepTicks();
        if (age > keep) {
            return (k.path == null ? "the no-path answer (" + k.answer + ")" : "the kept path") + " is " + age
                    + " ticks old, kept at most " + keep + (k.path == null ? " (pricedPath.keepTicks)" : " (pathCost.maxKeepS)");
        }
        return null;
    }

    /**
     * Null: the kept answer stands for this ask (k.progress set to the feet's index); a not-loaded mark (kept, not known
     * now); else why it is dropped.
     */
    private String check(Kept<S> k, Now<S> now, IPricedPath.Limits lim) {
        String stale = staleness(k, now.world(), now.tick(), lim);
        if (stale != null) {
            lastDrop = DropKind.STALE;
            return stale;
        }
        if (k.path == null) {
            if (!k.start.equals(now.feet())) {
                lastDrop = DropKind.MOVED;
                return "the bot left the no-path answer's start " + k.start.text() + " (now at " + now.feet().text() + ")";
            }
            lastDrop = DropKind.FACT;
            return k.stamp == null ? null : k.stamp.diff(now.stamp().get(), true, true);
        }
        int i = k.path.indexOf(now.feet(), k.progress);
        if (i < 0) {
            lastDrop = DropKind.MOVED;
            return "left the path at " + now.feet().text() + " (on no cell and no move position of it)";
        }
        lastDrop = DropKind.FACT;
        String used = k.stamp == null ? null : k.stamp.diff(now.stamp().get(), k.path.breaks, k.path.places);
        if (used != null) {
            return used;
        }
        String cell = k.path.changed(i, now.blocks().get());
        if (cell != null && cell.startsWith(NOT_LOADED_MARK)) {
            k.progress = i;
            return cell;
        }
        if (cell != null) {
            return "block changed at " + cell;
        }
        k.progress = i;
        return null;
    }
}
