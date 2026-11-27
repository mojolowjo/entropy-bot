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
 * Entropy Bot 2026-11-27: the fork's PricedPath (behavior, 1651 lines), its pricing half: the asks, the queue, the segment
 * searches and their landings, the re-join, cancel and forget, the probe counts. Split out: PriceRun (one running price),
 * KeptAnswers (the memory glue and the cell reads), SavedRoutes (the route store). Left in the fork until our pilot
 * drives (step 9): the walk (walk, the offers to PathingBehavior, the first-segment walk, the dig steer). The searches run
 * on our AStarPathFinder over a PriceWorld instead of Baritone's PathingBehavior and executor.
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.price.PathBuild.Seen;
import io.github.mojolowjo.entropybot.nav.price.PathBuild.SegInfo;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.search.AStarPathFinder;
import io.github.mojolowjo.entropybot.nav.search.AbstractNodeCostSearch;
import io.github.mojolowjo.entropybot.nav.search.Favoring;
import io.github.mojolowjo.entropybot.nav.search.Path;
import io.github.mojolowjo.entropybot.nav.search.PathCalculationResult;
import io.github.mojolowjo.entropybot.nav.search.Stop;
import io.github.mojolowjo.entropybot.nav.search.goals.ClimbGoal;
import io.github.mojolowjo.entropybot.nav.search.goals.Goal;
import io.github.mojolowjo.entropybot.nav.search.goals.GoalXZ;
import io.github.mojolowjo.entropybot.nav.search.goals.HasGoalPos;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The priced path ({@link IPricedPath}): our planner's search for a goal, priced before anything walks, kept while it
 * holds. One segment search runs at a time per goal on the world's executor; the next one is built on the game thread
 * ({@link #tick}) when the last lands, the previous segment favoured, as Baritone plans ahead. What is kept and when an ask
 * searches is {@link PathMemory}'s rule (a kept path stays known while the feet are on one of its cells and nothing it
 * depends on changed; the price is then its remaining cost). A running search is joined by every ask and each asker is
 * answered from the feet when it lands (rule 18). Queued asks start from the memory's priority queue once a tick
 * ({@link QueuePump}). A bot that left a kept path gets a short JOIN search back onto it (D11). A stored route to the goal
 * becomes its kept path ({@link SavedRoutes}). Game thread but the A*s.
 * <p>
 * Pure but for block states (JUnit: PriceQueueTest on a fake world; the fork's PricedPath tests ported). Loader notes: none
 * here; the live PriceWorld (ticks, block updates, chunk loads) is the game adapter.
 */
public final class PriceQueue implements IPricedPath {

    private final PriceWorld world;
    private final PriceConfig config;
    private final KeptAnswers kept;
    private final PathMemory<Goal, PriceRun, Seen> memory;
    private final SavedRoutes routes;
    private final KeptAnswers.SteerMemory steerMemory = new KeptAnswers.SteerMemory();
    private IPricedPath.Limits limits = new IPricedPath.Limits(1200, 1200, 16, Integer.MAX_VALUE); // structural: until the first ask says
    private long tick;

    public PriceQueue(PriceWorld world, PriceConfig config) {
        this.world = world;
        this.config = config;
        this.kept = new KeptAnswers(world);
        this.memory = kept.memory;
        this.routes = new SavedRoutes(world, kept, config);
    }

    private void gameThread(String what) {
        if (!world.onGameThread()) {
            throw new IllegalStateException("IPricedPath." + what + " runs on the game thread only");
        }
    }

    /** Ticks since this queue was made (the memory's clock). */
    public long tickCount() {
        return tick;
    }

    @Override
    public CompletableFuture<IPathCost.Answer> price(Goal goal, int keepTicks, int maxSegments) {
        return price(goal, new IPricedPath.Limits(keepTicks, keepTicks, maxSegments, Integer.MAX_VALUE));
    }

    @Override
    public CompletableFuture<IPathCost.Answer> price(Goal goal, IPricedPath.Limits lim) {
        gameThread("price");
        BetterBlockPos start = world.start();
        if (goal == null || lim == null || start == null || world.world() == null) {
            return CompletableFuture.completedFuture(IPathCost.Answer.none(IPathCost.Answer.ERROR));
        }
        limits = lim;
        if (!memory.hasKept(goal) && !memory.isRunning(goal) && !goal.isInGoal(start)) {
            routes.fromRoute(goal, start, tick);                                // D11 build 2: a stored route becomes the kept path
        }
        if (!memory.hasKept(goal) && !memory.isRunning(goal) && !goal.isInGoal(start)) {
            // Entropy CPU item 3: a near goal's kept path the feet are on answers (SUFFIX); a near goal's running search is joined
            int radius = config.shareRadius();
            if (radius > 0) {
                java.util.function.BiPredicate<Goal, Goal> near = (a, b) -> near(a, b, radius);
                PathMemory.Share<Goal> s = memory.shareKept(goal, kept.now(goal, start, tick), lim, near);
                if (s != null) {
                    answersShared++;
                    world.debug("Entropy priced path: " + goal + " shares the kept path of " + s.goal() + " (priceAskShareRadius " + radius + ")");
                    return CompletableFuture.completedFuture(s.answer().withPath(positions(s.cells())));
                }
                PriceRun near0 = memory.runningNear(goal, near);
                if (near0 != null) {
                    asksJoinedNear++;
                    world.debug("Entropy priced path: " + goal + " joins the running search of " + near0.goal + " (priceAskShareRadius " + radius + ")");
                    return fromFeet(near0, goal, lim);
                }
            }
        }
        PathMemory.Decision<PriceRun> d = memory.ask(goal, kept.now(goal, start, tick), lim);
        if (d.dropped() != null) {
            world.debug("Entropy priced path: the kept answer to " + goal + " was dropped: " + d.dropped());
        }
        switch (d.kind()) {
            case ZERO:
            case EXACT:
            case SUFFIX:
                return CompletableFuture.completedFuture(d.answer().withPath(positions(d.cells())));
            case KEPT_NO_PATH:
            case NOT_LOADED:
                return CompletableFuture.completedFuture(d.answer());
            case JOIN:
                return fromFeet(d.join(), goal, lim);
            case QUEUED:
                return CompletableFuture.completedFuture(IPathCost.Answer.none(QUEUED));
            case WAIT:
                return CompletableFuture.completedFuture(d.answer());    // z58 item 2: the re-search wait (not known yet)
            case REJOIN: {
                // Entropy D11: the feet left the kept path (or a block ahead changed): a short bounded search back onto its
                // remaining cells, spliced to the kept suffix when it lands; failing that, the whole search as before
                PriceRun j = startJoin(goal, lim, start, d.index());
                return j == null ? CompletableFuture.completedFuture(IPathCost.Answer.none(IPathCost.Answer.CHUNKS)) : fromFeet(j, goal, lim);
            }
            default:
                break;
        }
        PriceRun r = startRun(goal, lim, start);
        if (r == null) {
            return CompletableFuture.completedFuture(IPathCost.Answer.none(IPathCost.Answer.CHUNKS));
        }
        return fromFeet(r, goal, lim);
    }

    /**
     * A new search for {@code goal} from {@code start} (registered with the memory), or null when the goal's own chunk is not
     * loaded (that answer is kept, no search). Entropy z58: also the start of a queued ask ({@link #pumpQueue}).
     */
    private PriceRun startRun(Goal goal, IPricedPath.Limits lim, BetterBlockPos start) {
        PriceWorld.Priced w = world.context();
        kept.stampBuilds++;
        if (goal instanceof HasGoalPos rp && !w.context().bsi.worldContainsLoadedChunk(rp.getGoalPos().getX(), rp.getGoalPos().getZ())) {
            // the goal's own chunk is not loaded (Baritone's pathing would simplify the goal to GoalXZ). Review 2 item 2
            // (rule 15): not a no-path answer - kept only until the next chunk loads (chunkLoaded)
            IPathCost.Answer a = IPathCost.Answer.none(IPathCost.Answer.CHUNKS);
            memory.keep(goal, a, null, PathBuild.cell(start), world.world(), w.stamp(), tick,
                    new SegInfo(List.of(), new int[0], new int[0], "the goal's own chunk is not loaded in the world view (no search)", false));
            return null;
        }
        PriceRun r = new PriceRun(goal, w.context(), start, lim.maxSegments(), PathBuild.cell(start), world.world(), w.stamp());
        memory.started(goal, r.startCell, r);
        startSegment(r, start, null);
        return r;
    }

    /** structural: nodes one join search may open (a join is short by nature; past this it is no join and the whole search runs) */
    static final int JOIN_NODES = 2000;
    /** Entropy D11 (the probe): join searches started, landed and spliced, failed, and the failures by kind. */
    private long joinsStarted, joinsLanded, joinsFailed, joinsBudget, joinsNoPath, joinsOffPath, joinsOther;

    /**
     * Entropy D11 (the re-join): the JOIN search for {@code goal} from {@code start} - one segment, {@link #JOIN_NODES} at
     * most, toward any remaining cell of the kept path from {@code index} ({@link GoalOnPath}); registered with the memory as
     * the goal's running search. No kept path or segments to join: the whole search as before.
     */
    private PriceRun startJoin(Goal goal, IPricedPath.Limits lim, BetterBlockPos start, int index) {
        PathMemory.Path<Seen> keptPath = memory.path(goal);
        SegInfo info = memory.extra(goal) instanceof SegInfo s ? s : null;
        if (keptPath == null || info == null || info.segments().isEmpty() || index < 0 || index >= keptPath.size()) {
            return startRun(goal, lim, start);
        }
        PriceWorld.Priced w = world.context();
        kept.stampBuilds++;
        PriceRun r = new PriceRun(goal, w.context(), start, 1, PathBuild.cell(start), world.world(), w.stamp());
        r.join = true;
        r.joinIndex = index;
        r.joinGoal = new GoalOnPath(keptPath.cells(index));
        r.keptPath = keptPath;
        r.keptInfo = info;
        memory.started(goal, r.startCell, r);
        joinsStarted++;
        world.debug("Entropy priced path: re-join to " + goal + ": " + r.joinGoal + " from " + start);
        startSegment(r, start, null);
        return r;
    }

    @Override
    public long[] joinCounts() {
        long[] c = memory.counts();
        return new long[]{c[PathMemory.REJOINS], joinsStarted, joinsLanded, joinsFailed, joinsBudget, joinsNoPath, joinsOffPath, c[PathMemory.JOINS_QUEUED], joinsOther};
    }

    /**
     * Entropy z58 item 1: once a tick, the queue - every queued ask that may start (highest class first: the running step's
     * goal, a first answer, a re-search, the rest) starts; while a due one has no slot for its class, a running re-search /
     * rest search is cancelled for it (never the running step's). A queued start has no asker: its landing is counted
     * ({@link #queueLandings}) so the mod re-prices.
     */
    private void pumpQueue() {
        if (world.start() == null || world.world() == null) {
            return;
        }
        queueLanded += QueuePump.pump(memory, tick, limits, new QueuePump.Starter<Goal, PriceRun>() {
            @Override
            public boolean inGoal(Goal g) {
                return g.isInGoal(world.start());                         // the mod's next ask answers 0 (no search)
            }

            @Override
            public boolean start(Goal g) {
                // D11 fix (run FF): a goal queued as a re-join starts its JOIN search here, never the whole search it replaces
                BetterBlockPos start = world.start();
                int from = memory.takeQueuedRejoin(g);
                PriceRun r = from >= 0 ? startJoin(g, limits, start, from) : startRun(g, limits, start);
                if (r != null) {
                    r.fromQueue = true;
                }
                return r != null;
            }

            @Override
            public void cancel(PriceRun victim) {
                if (victim.finder != null) {
                    victim.finder.cancel();
                }
                segEnded++;                                             // J3-fork: it never lands (the probe's true running count)
                if (victim.join) {
                    memory.queueRejoin(victim.goal, victim.joinIndex);  // D11 fix: a cancelled join is started again as a join
                }
                world.debug("Entropy priced path: the search to " + victim.goal + " was cancelled for a higher-priority ask (it is queued again)");
            }

            @Override
            public void answerQueued(PriceRun r) {
                r.out.complete(IPathCost.Answer.none(QUEUED));  // askers re-ask (fromFeet): queued again, never a price (rule 15)
            }
        });
    }

    /** Entropy z58: landings of searches the queue started (no asker waits on them), for the mod's re-price. */
    private long queueLanded;

    @Override
    public long queueLandings() {
        return queueLanded;
    }

    @Override
    public int firstAnswersPending() {
        gameThread("firstAnswersPending");
        return memory.pending(PathMemory.Prio.FIRST);
    }

    @Override
    public void runningGoal(Goal goal) {
        gameThread("runningGoal");
        memory.runningGoal(goal);
    }

    @Override
    public void orderStarted() {
        gameThread("orderStarted");
        memory.orderStarted();
    }

    /**
     * Entropy z57 review 2 item 1 (rule 18): an asker's future of a running search - when the search lands (its answer is
     * from its own start), the memory is asked again from the feet at that moment. An error or a withdrawn ask passes as it is.
     */
    private CompletableFuture<IPathCost.Answer> fromFeet(PriceRun r, Goal goal, IPricedPath.Limits lim) {
        return PathMemory.fromFeet(r.out, PriceQueue::passOn, () -> price(goal, lim));
    }

    /**
     * Entropy CPU item 3 (pure, unit-tested): two goals of the same kind whose cells are within {@code radius} blocks on each
     * axis share answers; a goal with no single cell never does.
     */
    static boolean near(Goal a, Goal b, int radius) {
        if (radius <= 0 || a == null || b == null || a.getClass() != b.getClass()
                || !(a instanceof HasGoalPos pa) || !(b instanceof HasGoalPos pb)) {
            return false;
        }
        BlockPos x = pa.getGoalPos(), y = pb.getGoalPos();
        return Math.abs(x.getX() - y.getX()) <= radius && Math.abs(x.getY() - y.getY()) <= radius && Math.abs(x.getZ() - y.getZ()) <= radius;
    }

    /** J3 review (pure, unit-tested): a landed answer handed on as it is, not re-asked from the feet - an error or a withdrawn ask. */
    static boolean passOn(IPathCost.Answer a) {
        return IPathCost.Answer.ERROR.equals(a.reason) || WITHDRAWN.equals(a.reason);
    }

    static List<BlockPos> positions(List<PathMemory.Cell> cells) {
        List<BlockPos> out = new ArrayList<>(cells.size());
        for (PathMemory.Cell c : cells) {
            out.add(new BetterBlockPos(c.x(), c.y(), c.z()));
        }
        return out;
    }

    /**
     * Baritone's PathingBehavior.createPathfinder: a goal whose chunk is not loaded searched as its x z
     * (simplifyUnloadedYCoord), the previous segment favoured, the real start the feet when within one block on the same y.
     * The fork's Favoring also avoided mobs and spawners (settings avoidance, off by default): not here yet (no mob view).
     */
    private AStarPathFinder createPathfinder(BetterBlockPos start, Goal goal, Path previous, SearchContext context) {
        Goal transformed = goal;
        if (config.simplifyUnloadedYCoord() && goal instanceof HasGoalPos rp) {
            BlockPos pos = rp.getGoalPos();
            if (!context.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
                transformed = new GoalXZ(pos.getX(), pos.getZ());
            }
        }
        Favoring favoring = new Favoring(previous == null ? null : previous.positions(), context.config.search().backtrackCostFavoringCoefficient());
        BetterBlockPos feet = world.feet();
        BetterBlockPos realStart = start;
        if (feet != null && feet.getY() == start.getY() && Math.abs(feet.getX() - start.getX()) <= 1 && Math.abs(feet.getZ() - start.getZ()) <= 1) {
            realStart = feet;
        }
        return new AStarPathFinder(realStart, start.getX(), start.getY(), start.getZ(), transformed, favoring, context);
    }

    /** One segment search: built on the game thread, run on the world's executor. */
    private void startSegment(PriceRun r, BetterBlockPos from, Path previous) {
        // z47: a goal below this segment's start is searched with the dig-down cost in its heuristic (DigDownGoal); z63: a goal
        // above it with the per-level climb cost (ClimbGoal); r.steered = either one. D11: a join aims at the kept path's cells
        Goal searchGoal = r.join ? r.joinGoal : ClimbGoal.steer(r.goal, from, r.context);
        if (!r.join && searchGoal != r.goal) {
            r.steered = true;
            world.debug("Entropy priced path: " + searchGoal);
        }
        AStarPathFinder finder = createPathfinder(from, searchGoal, previous, r.context);
        finder.costQuery(r.join ? JOIN_NODES : Integer.MAX_VALUE, Double.POSITIVE_INFINITY); // no extra budget but a join's node cap
        boolean first = previous == null;
        // Entropy CPU item 2: price asks have their own limits; the walk's own searches keep NavConfig's timeouts
        long primary = config.primaryTimeoutMs();
        long failure = config.failureTimeoutMs();
        r.failureMs = failure;
        r.finder = finder;
        r.segStart = from;
        if (first) {
            r.firstStart = from;
            lastCalcs.remove(r.goal);     // lease probe: a new search for the goal: the last one's detail is not this one's
        }
        r.search = CompletableFuture.supplyAsync(() -> finder.calculate(primary, failure), world.executor());
        segStarted++;
    }

    /** Entropy z56 item 3 (load probe): segment searches started / landed and nodes the landed ones opened. */
    private long segStarted, segLanded, nodesOpened;
    /** J3-fork: segment searches ended without landing. */
    private long segEnded;

    @Override
    public long[] searchCounts() {
        return new long[]{segStarted, segLanded, nodesOpened, segEnded};
    }

    /**
     * Entropy J3-fork (z76 part 2): the mod withdraws its ask - the goal's running search is cancelled (its slot free now,
     * its landing ignored, counted as ended), its askers answered {@link IPricedPath#WITHDRAWN}, the goal out of the queue.
     */
    @Override
    public boolean cancel(Goal goal) {
        gameThread("cancel");
        if (goal == null) {
            return false;
        }
        boolean[] queued = new boolean[1];
        PriceRun r = memory.withdraw(goal, queued);
        if (r != null) {
            if (r.finder != null) {
                r.finder.cancel();
            }
            segEnded++;
            r.out.complete(IPathCost.Answer.none(WITHDRAWN));
            world.debug("Entropy priced path: the ask for " + goal + " was withdrawn; its search cancelled");
        }
        return r != null || queued[0];
    }

    @Override
    public long[] askCounts() {
        long[] m = memory.counts();
        long[] q = memory.classCounts();
        long[] out = java.util.Arrays.copyOf(m, m.length + 1 + q.length);
        out[m.length] = kept.stampBuilds;                               // z57 review 2 item 3: stamps built
        System.arraycopy(q, 0, out, m.length + 1, q.length);            // z58: per class started / queued, cancels, waits, left
        return out;
    }

    /**
     * Entropy z57 review 2 item 2 (rule 15): a chunk loaded (after it is populated): the kept "chunks not loaded" answers go,
     * so the next ask searches; while no chunk loads they stand.
     */
    public void chunkLoaded(int chunkX, int chunkZ) {
        memory.chunkLoaded("chunk " + chunkX + " " + chunkZ);
    }

    /** A block update at {@code pos} (the game's packet, its own new state): a kept path whose remaining move needs it goes. */
    public int blockChanged(BetterBlockPos pos, BlockState state) {
        return kept.blockChanged(pos, state);
    }

    @Override
    public List<String> drainDrops() {
        List<String> m = memory.drainDrops();
        List<String> r = routes.drainLines();
        if (r.isEmpty()) {
            return m;
        }
        List<String> out = new ArrayList<>(m);
        out.addAll(r);
        return out;
    }

    @Override
    public long[] routeCounts() {
        return routes.counts();
    }

    @Override
    public List<String> routeList() {
        gameThread("routeList");
        return routes.list();
    }

    @Override
    public boolean forget(int index) {
        gameThread("forget(index)");
        return routes.forget(index);
    }

    /**
     * D11 build 2: the walk to {@code goal} ended (the walk driver says it): saved as a route when the feet stand in the goal
     * at its kept path's end. The fork watched CustomGoalProcess for this; our driver will call it (step 9).
     */
    public boolean walkEnded(Goal goal) {
        gameThread("walkEnded");
        return routes.walkEnded(goal, tick);
    }

    @Override
    public long landingOf(Goal goal) {
        return memory.landing(goal);
    }

    /** One game tick: the landed segment searches are handled (done, the next segment, or no path), then the queue. */
    public void tick() {
        gameThread("tick");
        tick++;
        List<PriceRun> landed = new ArrayList<>();
        for (PriceRun r : memory.runningHandles()) {
            if (r.search != null && r.search.isDone()) {
                landed.add(r);
            }
        }
        for (PriceRun r : landed) {
            land(r);
        }
        pumpQueue();
    }

    /**
     * Entropy CPU item 2 (pure, unit-tested; rule 15): a segment stopped by the clock with no path or no progress is not
     * known, never "unreachable" or "no progress": "not known: the search ran out of its N ms (priceAskFailureTimeoutMS)".
     */
    static IPricedPath.Step timedOut(IPricedPath.Step step, IPathCost.Stop stop, long failureMs) {
        if (stop != IPathCost.Stop.TIME || step.done() || step.next()) {
            return step;
        }
        if (!IPricedPath.NO_PROGRESS.equals(step.reason()) && !IPathCost.Answer.UNREACHABLE.equals(step.reason())) {
            return step;
        }
        return IPricedPath.Step.none(IPricedPath.TIMED_OUT + " " + failureMs + " ms (priceAskFailureTimeoutMS)");
    }

    /** Entropy CPU job (the probe): price asks timed out, answers shared, asks joined to a near goal's search. */
    private long asksTimedOut, answersShared, asksJoinedNear;

    /**
     * The fork's eight: {timed out, shared, joined near, fluid cache hits, fills, advisor cache hits, advisor asks, dig steers};
     * our search keeps no fluid or advisor cache counters and the dig steer is the walk's: those are -1 (not counted).
     */
    @Override
    public long[] cpuCounts() {
        return new long[]{asksTimedOut, answersShared, asksJoinedNear, -1, -1, -1, -1, -1};
    }

    /** Our search's stop as the price's (ERROR is the price's own: an exception). */
    static IPathCost.Stop stopOf(PathCalculationResult res, Stop searchStop) {
        if (res.getType() == PathCalculationResult.Type.EXCEPTION) {
            return IPathCost.Stop.ERROR;
        }
        if (res.getType() == PathCalculationResult.Type.CANCELLATION) {
            return IPathCost.Stop.CANCELLED;
        }
        return IPathCost.Stop.valueOf(searchStop.name());
    }

    /** Game thread: one segment landed: done, the next segment, or no path. */
    private void land(PriceRun r) {
        PathCalculationResult res;
        try {
            res = r.search.join();
        } catch (RuntimeException e) {
            world.say("Entropy priced path: the segment search threw " + e); // said, never a quiet "no path"
            segEnded++;
            memory.landed(r.goal, r, IPathCost.Answer.none(IPathCost.Answer.ERROR), null, r.startCell, r.world, r.stamp, tick,
                    new SegInfo(List.of(), new int[0], new int[0], "segment " + (r.segments.size() + 1) + " from " + r.segStart + ": the search threw " + e, r.steered));
            r.out.complete(IPathCost.Answer.none(IPathCost.Answer.ERROR));
            return;
        }
        segLanded++;
        nodesOpened += r.finder.nodesOpened();
        IPathCost.Stop stop = stopOf(res, r.finder.stop());
        if (res.getType() == PathCalculationResult.Type.EXCEPTION) {
            world.say("Entropy priced path: the segment search to " + r.goal + " threw " + res.failure()); // never a quiet "no path"
        }
        Optional<Path> path = res.getPath();
        if (r.join) {
            landJoin(r, stop, path);
            return;
        }
        boolean inGoal = path.isPresent() && r.goal.isInGoal(path.get().getDest());
        boolean progressed = path.isPresent() && !path.get().getDest().equals(r.segStart);
        if (path.isPresent() && progressed) {
            AStarPathFinder f = r.finder;
            double[] g = PathBuild.along(f::nodeCost, path.get());
            r.segments.add(path.get());
            r.along.add(g);
            r.cost += g[g.length - 1];
            // review 4: this segment's watched cells read now, when it lands (later changes show at the next ask's re-read)
            r.snaps.add(PathBuild.snapshot(path.get(), kept::read));
        }
        IPricedPath.Step step = timedOut(IPricedPath.Step.of(path.isPresent(), inGoal, progressed, stop, r.segments.size(), r.maxSegments), stop, r.failureMs);
        if (step.reason() != null && step.reason().startsWith(IPricedPath.TIMED_OUT)) {
            asksTimedOut++;
        }
        if (!step.next()) keepCalc(r, stop, step.reason());     // lease probe, read-only
        if (step.next()) {
            Path last = r.segments.get(r.segments.size() - 1);
            startSegment(r, last.getDest(), last);
            return;
        }
        if (step.done()) {
            finish(r, null, null);
        } else {
            // Entropy z54 (probe): the failing segment's numbers, kept with the answer (IPricedPath#failure)
            BetterBlockPos best = r.finder.bestNode();
            String detail = "segment " + (r.segments.size() + 1) + " of at most " + r.maxSegments + " from " + r.segStart
                    + ": stop " + stop + ", path " + (path.isPresent() ? "to " + path.get().getDest() + (progressed ? "" : " (its own start)") : "none")
                    + ", nodes opened " + r.finder.nodesOpened() + ", known " + r.finder.nodesKnown()
                    + ", best node " + (best == null ? "none" : best + " (" + Math.round(Math.sqrt(best.distSqr(r.segStart))) + " blocks from the segment start)")
                    + ", moves refused at unloaded chunks " + r.finder.emptyChunkHits()
                    + (res.failure() == null ? "" : ", " + res.failure());
            finish(r, IPathCost.Answer.none(step.reason()), detail);
        }
    }

    /**
     * Entropy D11: a join search landed. A path ending on a remaining cell of the kept path: the kept segments from that cell
     * on follow the join's segment and the run finishes as a complete price. Anything else: the memory drops the kept path
     * (said) and the askers get REJOIN_FAILED (not known; the whole search follows after the re-search wait).
     */
    private void landJoin(PriceRun r, IPathCost.Stop stop, Optional<Path> path) {
        boolean onPath = path.isPresent() && r.joinGoal.isInGoal(path.get().getDest()) && !path.get().getDest().equals(r.segStart);
        int j = onPath ? r.keptPath.indexOf(PathBuild.cell(path.get().getDest()), r.joinIndex) : -1;
        if (j < 0 || j >= r.keptPath.size() - 1 && !r.goal.isInGoal(path.get().getDest())) {
            joinsFailed++;
            // D11 fix review (rule 15): a budget stop is NODES (or the node cap reached), TIME or COST; "no path" only when the
            // search EXHAUSTED its nodes; a path that landed off the kept cells is its own kind; anything else is "other"
            boolean budget = stop == IPathCost.Stop.NODES || stop == IPathCost.Stop.TIME || stop == IPathCost.Stop.COST || r.finder.nodesOpened() >= JOIN_NODES;
            if (budget) {
                joinsBudget++;
            } else if (path.isPresent()) {
                joinsOffPath++;
            } else if (stop == IPathCost.Stop.EXHAUSTED) {
                joinsNoPath++;
            } else {
                joinsOther++;
            }
            String ended = path.isPresent() ? "the join ended at " + path.get().getDest() + (onPath ? " (not a kept cell from " + r.joinIndex + " on)" : " (off the kept path)")
                    : stop == IPathCost.Stop.EXHAUSTED ? "no path (the search ran out of cells)" : budget ? "no path within the budget" : "no path answered (not known: " + stop + ")";
            String why = "stop " + stop + ", " + ended + ", nodes opened " + r.finder.nodesOpened() + " of " + JOIN_NODES;
            keepCalc(r, stop, IPricedPath.REJOIN_FAILED);
            memory.rejoinFailed(r.goal, r, why, tick);
            world.debug("Entropy priced path: the re-join to " + r.goal + " failed: " + why);
            r.out.complete(IPathCost.Answer.none(IPricedPath.REJOIN_FAILED));
            return;
        }
        AStarPathFinder f = r.finder;
        double[] g = PathBuild.along(f::nodeCost, path.get());
        r.segments.add(path.get());
        r.along.add(g);
        r.snaps.add(PathBuild.snapshot(path.get(), kept::read));
        double joinCost = g[g.length - 1];
        for (Path seg : r.keptInfo.suffix(j)) {
            r.segments.add(seg);
            r.along.add(PathBuild.alongKept(r.keptPath, seg, j));
            r.snaps.add(PathBuild.snapshot(seg, kept::read));
        }
        r.cost = joinCost + r.keptPath.rest(j);
        r.steered = r.keptInfo.steered();
        joinsLanded++;
        keepCalc(r, stop, null);
        world.debug("Entropy priced path: re-joined the kept path to " + r.goal + " at its cell " + j + " (join " + Math.round(joinCost) + " ticks, rest " + Math.round(r.keptPath.rest(j)) + ")");
        finish(r, null, null);
    }

    // lease probe (run ER): the last landed search per goal, read-only; bounded (structural: the planner prices a few
    // dozen goals at once), cleared by forget()
    private final Map<Goal, IPricedPath.SearchCalc> lastCalcs = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Goal, IPricedPath.SearchCalc> e) {
            return size() > 256;
        }
    });

    private void keepCalc(PriceRun r, IPathCost.Stop stop, String answer) {
        lastCalcs.put(r.goal, new IPricedPath.SearchCalc(String.valueOf(stop), answer, r.finder.nodesOpened(), r.finder.bestNode(),
                r.firstStart, r.segStart, r.goal, r.segments.size() + (answer == null ? 0 : 1),
                stop == IPathCost.Stop.EXHAUSTED, IPricedPath.SearchCalc.budgetStop(stop, answer)));
    }

    @Override
    public IPricedPath.SearchCalc lastCalc(Goal goal) {
        return goal == null ? null : lastCalcs.get(goal);
    }

    /** A finished price: a complete one (a == null) or no path (a, failure); kept by the memory when still current. */
    private void finish(PriceRun r, IPathCost.Answer a, String failure) {
        PathMemory.Path<Seen> path = null;
        SegInfo info;
        if (a == null) {
            PathBuild.Built<Seen> b = PathBuild.build(r.segments, r.along, r.snaps);
            path = b.path();
            a = IPathCost.Answer.complete(r.cost).withPath(positions(b.cells()));
            info = new SegInfo(List.copyOf(r.segments), b.segOf(), b.posOf(), null, r.steered);
        } else {
            info = new SegInfo(List.of(), new int[0], new int[0], failure, r.steered);
        }
        boolean keptIt = memory.landed(r.goal, r, a, path, r.startCell, r.world, r.stamp, tick, info);
        memory.sweep(world.world(), tick, limits);
        if (keptIt && r.fromQueue) {
            queueLanded++;                                              // z58: the mod re-prices (nobody's future answers it)
        }
        steerMemory.record(r.goal, r.steered);                            // z56 review 2: the price's search goal fact
        r.out.complete(a);
    }

    /** z56 review 2: whether the goal's last price used a steered search goal (null: never priced); the walk reads it. */
    public Boolean steered(Goal goal) {
        return steerMemory.get(goal);
    }

    /** Entropy job-seg: the goal's running price search (not a re-join), null for none. */
    private PriceRun runningRun(Goal goal) {
        for (PriceRun r : memory.runningHandles()) {
            if (!r.join && r.goal.equals(goal)) {
                return r;
            }
        }
        return null;
    }

    /** Entropy job-seg: a segment's cost in ticks (its steps' costs, as Baritone's executor sums them). */
    private static double ticksOf(Path p) {
        double t = 0;
        for (io.github.mojolowjo.entropybot.nav.search.Step m : p.steps()) {
            t += m.cost();
        }
        return t;
    }

    /**
     * Entropy job-seg (read only): the goal's running search's first segment (its first landed one, or its best-so-far
     * path) or the kept path's first segment; null when neither, or no node past the start yet (rule 15).
     */
    @Override
    public IPricedPath.FirstSegment firstSegment(Goal goal) {
        gameThread("firstSegment");
        PriceRun r = runningRun(goal);
        SegInfo keptInfo = memory.extra(goal) instanceof SegInfo s && !s.segments().isEmpty() ? s : null;
        IPricedPath.SearchState state = IPricedPath.SearchState.of(r != null, keptInfo != null);
        if (r != null) {
            if (!r.segments.isEmpty()) {
                Path p = r.segments.get(0);
                return IPricedPath.FirstSegment.of(p.positions(), ticksOf(p), goal, state);
            }
            AbstractNodeCostSearch.Partial part = r.finder == null ? null : r.finder.partialSoFar().orElse(null);
            return part == null ? null : IPricedPath.FirstSegment.of(part.cells(), part.cum()[part.cum().length - 1], goal, state);
        }
        if (keptInfo != null) {
            Path p = keptInfo.segments().get(0);
            return IPricedPath.FirstSegment.of(p.positions(), ticksOf(p), goal, state);
        }
        return null;
    }

    @Override
    public int pending() {
        return memory.runningCount();
    }

    @Override
    public List<Path> keptSegments(Goal goal) {
        return memory.extra(goal) instanceof SegInfo s ? s.segments() : List.of();
    }

    @Override
    public String failure(Goal goal) {
        return memory.extra(goal) instanceof SegInfo s ? s.failure() : null;
    }

    /** The goal's kept path as the memory holds it (null: none); for the walk and the checks. */
    public PathMemory.Path<Seen> keptPath(Goal goal) {
        return memory.path(goal);
    }

    @Override
    public void forget() {
        lastCalcs.clear();
        for (PriceRun r : memory.runningHandles()) {
            if (r.finder != null) {
                r.finder.cancel();
            }
            segEnded++;                                                 // J3-fork: dropped, never lands
            r.out.complete(IPathCost.Answer.none(IPathCost.Answer.ERROR));
        }
        memory.forget();
        steerMemory.clear();
    }
}
