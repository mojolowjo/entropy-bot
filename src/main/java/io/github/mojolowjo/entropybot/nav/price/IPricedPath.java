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
 * Entropy Bot 2026-11-27: the fork's IPricedPath (fork-only class, from the fork's source at dd8235b7)
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.search.goals.Goal;
import net.minecraft.core.BlockPos;

import java.util.concurrent.CompletableFuture;

/**
 * Entropy z46: a PRICED PATH - one search, priced and then walked. {@link #price} runs Baritone's own search for a goal the
 * way PathingBehavior runs it: its pathfinder (PathingBehavior#createPathfinder, built on the game thread), a
 * CalculationContext for another thread, primaryTimeoutMS / failureTimeoutMS for the first segment and
 * planAheadPrimaryTimeoutMS / planAheadFailureTimeoutMS for each next one, each next segment searched from the last one's
 * end with the previous segment favoured, one segment at a time across ticks on Baritone's executor, until a segment ends in
 * the goal. The future completes on the game thread with the path's cost in ticks (complete, its cells along) and the
 * segments are kept for the goal; or noPath with Baritone's reason (unreachable, no progress, budget = maxSegments,
 * chunks not loaded = the goal's own chunk is not loaded in Baritone's world view, error).
 * {@link #walk} offers the kept segments to Baritone's PathingBehavior in place of its next searches when the bot still
 * stands at their start and their cells are unchanged; else Baritone searches fresh. Game thread only.
 * Entropy z57: what is kept and for how long is {@link PathMemory}'s rule (a kept path stays known while the feet are on
 * one of its cells and nothing it depends on changed; the price is then its remaining cost and the walk walks that suffix).
 */
public interface IPricedPath {

    /** A segment search ended where it started (or found nothing to walk): Baritone found no way on from there. */
    String NO_PROGRESS = "no progress";

    /**
     * Entropy z57 (run P: up to 4 searches in flight): {@link Limits#maxRunning} searches run already; this ask starts
     * none. Not a path answer: the path is not known yet (rule 15), ask again.
     */
    String QUEUED = "queued";

    /**
     * Entropy z57 (owner 2026-10-25: "while doing stuff it should not forget the path that was already planned"): how long
     * the memory keeps answers and how many searches run at once. {@code keepTicks}: a complete path's long safety age
     * (it is dropped earlier when a fact it depends on changes: {@link PathMemory}); {@code noPathKeepTicks}: a no-path
     * answer's keep (from its own start cell only); {@code maxSegments}: segment searches one price may chain;
     * {@code maxRunning}: searches in flight at once (past them an ask answers {@link #QUEUED}). A running search is
     * never cancelled because the bot moved (z57 review 2): every ask joins it and is answered from the feet when it lands.
     * Entropy z58 (run Q): {@code reserveForRunning} of the {@code maxRunning} slots only the running step's goal may use
     * (at most maxRunning - 1); {@code researchMinTicks}: the re-search wait ({@link #RESEARCH_WAIT}); {@code queueKeepTicks}:
     * a queued ask not asked again within it leaves the queue (nobody wants it any more). Queued asks are started by the
     * fork in priority order when a slot frees: the running step's goal, a goal not answered yet in this order, a re-search
     * after a kept path was dropped, the rest; a running re-search or rest search is cancelled for a higher waiting one when
     * no slot is free for it (never the running step's).
     */
    record Limits(int keepTicks, int noPathKeepTicks, int maxSegments, int maxRunning, int reserveForRunning, int researchMinTicks,
                  int queueKeepTicks) {
        public Limits {
            keepTicks = Math.max(1, keepTicks);
            noPathKeepTicks = Math.max(1, noPathKeepTicks);
            maxSegments = Math.max(1, maxSegments);
            maxRunning = Math.max(1, maxRunning);
            reserveForRunning = Math.max(0, Math.min(reserveForRunning, maxRunning - 1));
            researchMinTicks = Math.max(0, researchMinTicks);
            queueKeepTicks = Math.max(1, queueKeepTicks);
        }

        /** z57's limits: no slot reserved, no re-search wait, a queued ask kept for the no-path keep. */
        public Limits(int keepTicks, int noPathKeepTicks, int maxSegments, int maxRunning) {
            this(keepTicks, noPathKeepTicks, maxSegments, maxRunning, 0, 0, noPathKeepTicks);
        }
    }

    /**
     * Entropy z58 (run Q 02:29:32-02:33:13: "in flight 4" all run long, every other ask "queued", the crafting walks
     * "computing" for minutes while the bot explored): a goal whose last answer was dropped because the bot moved off it
     * (left the kept path, or the start of a kept no-path answer) is searched again at most once per
     * {@link Limits#researchMinTicks}; until then its ask answers this. Not a path answer: not known yet (rule 15).
     */
    String RESEARCH_WAIT = "re-search waiting (the bot moved off the last path)";

    /**
     * Entropy z58 (D3 pricing queue, priorities): the goal of the step that runs now or runs next (its walk), null for none;
     * its searches go first and may use the {@link Limits#reserveForRunning} slots. The mod says it when it changes.
     */
    default void runningGoal(Goal goal) {}

    /**
     * Entropy z58: a new order started - every goal counts as "never answered in this order" again (the second priority
     * class) and the queue of asks is emptied (the new order asks again what it needs).
     */
    default void orderStarted() {}

    /**
     * Entropy z58: answers kept for queued asks the fork started itself (no asker's future lands for them) since the game
     * started - the mod re-prices when it moves; 0 from a fork without a queue.
     */
    default long queueLandings() {
        return 0;
    }

    /**
     * Entropy z58 (Opus Design ruling: rule 18 stays exact, so the bot stands still while first answers compute): searches
     * running plus asks queued for goals not answered yet in this order (the second priority class); -1 from a fork that
     * does not count them (not known: the mod does not wait on it).
     */
    default int firstAnswersPending() {
        return -1;
    }

    /**
     * lease probe (2026-10-28, run ER: a search stopped on its budget is "not known", never "no path"): the last landed
     * price search for a goal, read-only. {@code stop}: the A* stop of its last segment (GOAL, EXHAUSTED, TIME, NODES,
     * COST, CHUNKS, CANCELLED, ERROR); {@code answer}: the price answer's reason (null when complete); {@code exhausted}:
     * the graph was exhausted (the only true "no path"); {@code budget}: it stopped on a budget (time, nodes, cost, or the
     * segment count) = "not known"; {@code start}: the price's start cell (the feet it was asked from), {@code segStart}
     * the last segment's start. Reset when a new search for the goal starts; null when none landed or it was forgotten.
     */
    record SearchCalc(String stop, String answer, int nodesOpened, BlockPos best, BlockPos start, BlockPos segStart, Goal goal,
                      int segments, boolean exhausted, boolean budget) {
        /** Pure: budget = a time / nodes / cost stop, or the price ran out of segments. */
        public static boolean budgetStop(IPathCost.Stop stop, String answer) {
            return stop == IPathCost.Stop.TIME || stop == IPathCost.Stop.NODES || stop == IPathCost.Stop.COST
                    || IPathCost.Answer.BUDGET.equals(answer);
        }

        public String line() {
            return "stop " + stop + (answer == null ? "" : " (" + answer + ")") + ", " + (exhausted ? "graph exhausted" : budget ? "budget: not known" : "neither")
                    + ", nodes " + nodesOpened + ", best " + best + ", from " + start + ", segment " + segments + " from " + segStart + ", goal " + goal;
        }
    }

    /** lease probe: see {@link SearchCalc}. Null: none kept for this goal. */
    default SearchCalc lastCalc(Goal goal) {
        return null;
    }

    /** What one finished segment search means for the price: done, search the next segment, or no path (reason). */
    record Step(boolean done, boolean next, String reason) {
        public static final Step DONE = new Step(true, false, null);
        public static final Step NEXT = new Step(false, true, null);

        public static Step none(String reason) {
            return new Step(false, false, reason);
        }

        /**
         * The rule from one segment search to the next step (pure, unit-tested).
         *
         * @param hasPath    the search gave a path (to the goal or a segment toward it)
         * @param inGoal     that path ends in the goal
         * @param progressed that path ends somewhere other than where the segment started
         * @param stop       why the search stopped
         * @param segments   segments kept so far, this one included
         */
        public static Step of(boolean hasPath, boolean inGoal, boolean progressed, IPathCost.Stop stop, int segments, int maxSegments) {
            if (stop == IPathCost.Stop.ERROR || stop == IPathCost.Stop.CANCELLED) {
                return none(IPathCost.Answer.ERROR);
            }
            if (hasPath && inGoal) {
                return DONE;
            }
            if (!hasPath || !progressed) {
                // Entropy z48 (runs I/J: 45 / 61 "no progress" from spots near unloaded land): a search stopped by
                // pathingMaxChunkBorderFetch (its frontier ran into unloaded chunks) is "chunks not loaded", not "no progress"
                if (stop == IPathCost.Stop.CHUNKS) {
                    return none(IPathCost.Answer.CHUNKS);
                }
                return none(stop == IPathCost.Stop.EXHAUSTED ? IPathCost.Answer.UNREACHABLE : NO_PROGRESS);
            }
            return segments >= maxSegments ? none(IPathCost.Answer.BUDGET) : NEXT;
        }
    }

    /**
     * The full path's answer for this goal: done at once when a kept one is valid, else when the running (or new) search
     * ends. Completes on the game thread.
     *
     * @param keepTicks   game ticks the kept path stays valid (it also goes when the bot moves off its start)
     * @param maxSegments the segment searches one price may chain at most (past them: noPath "budget")
     */
    CompletableFuture<IPathCost.Answer> price(Goal goal, int keepTicks, int maxSegments);

    /**
     * Entropy z57: the price through the path memory ({@link PathMemory}): the goal satisfied at the start -> 0 at once; a
     * kept path with the feet on one of its cells -> its remaining cost at once (exact or suffix, no search); a kept no-path
     * answer from this very cell -> it; a search for this goal running (from anywhere) -> joined; {@code limits.maxRunning}
     * running -> {@link #QUEUED} at once; else a new search. A future done at return is a memory answer (or queued /
     * chunks not loaded). Rule 18 (z57 review 2): a search's future never completes with the answer from the search's own
     * start - when it lands, the memory is asked again from the feet at that moment (exact, suffix, or dropped and searched
     * again, whose landing answers in turn), so every asker gets the price from where the bot stands.
     */
    default CompletableFuture<IPathCost.Answer> price(Goal goal, Limits limits) {
        return price(goal, limits.keepTicks(), limits.maxSegments());
    }

    /**
     * Entropy z57 (the mod's load probe): {asks, answered 0 at the goal, exact, suffix, kept no-path, joined in flight,
     * searches started, queued, cancelled, kept answers dropped, answered "not loaded" (a watched cell's chunk)} since the
     * game started; {-1} from a fork without them. z57 review 2: [11] stamps built. Entropy z58: [12..15] searches started
     * per class (running step, first answer, re-search, rest), [16..19] queued per class, [20] re-search cancelled for
     * priority, [21] rest cancelled for priority, [22] re-search waits answered, [23] queued asks that left the queue (not
     * asked again within queueKeepTicks); "cancelled" [8] counts every cancel for priority.
     */
    default long[] askCounts() {
        return new long[]{-1};
    }

    /**
     * Entropy z57 (rule 7, the mod's "probe reuse:" line): one line per kept answer dropped since the last call - the goal
     * and why (left the path at x y z, block changed at x y z (before -> after), what the price used changed, its age, the
     * world). Empty from a fork without them.
     */
    default java.util.List<String> drainDrops() {
        return java.util.List.of();
    }

    /**
     * Entropy z57 review 9: the landing number of the goal's kept answer (a new number each time a search lands or an
     * answer is kept), -1 when none is kept: a memory answer belongs to that landing (its cells are that path's tail).
     */
    default long landingOf(Goal goal) {
        return -1;
    }

    /**
     * Searches running now: the fork's own count of prices with a segment search in flight (one segment at a time per
     * price, so it is also the segment searches running). J3-fork: the true running count for the mod's probe - equal to
     * {@code searchCounts()[0] - [1] - [3]}.
     */
    int pending();

    /**
     * Entropy J3-fork (z76 part 2): the answer to the askers of a withdrawn ask ({@link #cancel}). Not a path answer: the
     * path is not known (rule 15); asking again starts a new search.
     */
    String WITHDRAWN = "withdrawn (the ask was cancelled)";

    /**
     * Entropy J3-fork (z76 part 2): the mod withdraws its ask for {@code goal} (the way was dropped, or the bot moved off the
     * start it was asked from). The goal's running search is cancelled - its A* stops at its next node, its slot is free at
     * once, its landing is ignored - and the goal leaves the queue; every asker's future completes with {@link #WITHDRAWN}.
     * A kept answer for the goal stays kept (it is the memory's, not an ask). Any priority class, the running step's too
     * (the caller decides). Game thread. Returns true when a running search or a queued ask was dropped; false for an
     * unknown goal or a fork without it.
     */
    default boolean cancel(Goal goal) {
        return false;
    }

    /**
     * Entropy z54 (probe, run M 19:00:59: the walk ended short of a complete priced path): the kept segments of the goal's
     * price, start first (read-only); empty when none is kept (no price yet, no path, expired, or walked).
     */
    default java.util.List<io.github.mojolowjo.entropybot.nav.search.Path> keptSegments(Goal goal) {
        return java.util.List.of();
    }

    /**
     * Entropy z54 (probe, run M: 38 "no progress" answers): the last no-path answer's detail for the goal - which segment,
     * its start, why the search stopped, nodes opened and known, the best node reached, moves refused at unloaded chunks;
     * null when no no-path answer is kept for it.
     */
    default String failure(Goal goal) {
        return null;
    }

    /**
     * Entropy z56 item 3 (the mod's load probe: the owner saw very low frame rates at times): {segment searches started,
     * segment searches landed, nodes opened by the landed searches} since the game started. Read only (counters the
     * game thread bumps); {-1, -1, -1} from a fork without them.
     * Entropy J3-fork (run AL: the probe's "segment searches running" = [0] - [1] read 73 while at most 4 ran - 70 searches
     * cancelled for priority never land): [3] segment searches that ended without landing (cancelled for priority,
     * withdrawn by {@link #cancel}, or dropped by {@link #forget}); running now = [0] - [1] - [3] = {@link #pending()}.
     * [1] stays landings only (the first-answer wait's progress fact).
     */
    default long[] searchCounts() {
        return new long[]{-1, -1, -1};
    }

    /**
     * Entropy D11 (the re-join): the join search found no way back to the kept path (or stopped on its budget): the kept path
     * is dropped and the askers are answered this - not a path answer (rule 15: not known); the next ask searches whole after
     * the re-search wait.
     */
    String REJOIN_FAILED = "the re-join search found no way back to the kept path (a whole search next)";

    /**
     * Entropy D11 (the mod's probe): {re-joins asked, join searches started, joins landed and spliced, joins failed} since the
     * game started; {-1} from a fork without the re-join. D11 fix (run FF): five more - failed on the budget (nodes, time,
     * cost), failed with no path (the search exhausted), landed off the kept path, re-joins queued for a slot, failed for
     * another reason (chunks, cancelled, error: not known) - a fork without them gives the four alone.
     */
    default long[] joinCounts() {
        return new long[]{-1};
    }

    /**
     * Entropy D11 build 2 (route memory, the mod's load probe): {asked, reused, check failed: a cell changed, check failed: a
     * cell not loaded, saved} since the game started. Asked: asks with no kept answer that found a stored route for the goal;
     * reused: a route loaded as the kept path (its cells held, or the feet within re-join reach); changed: a watched cell
     * changed (loaded for the re-join repair) or a move no longer possible (a whole search); not loaded: rule 15, not known,
     * a normal search; saved: routes written after a walk ended in its goal. {-1} from a fork without the store.
     */
    default long[] routeCounts() {
        return new long[]{-1};
    }

    /**
     * Entropy CPU job (the mod's probe line): {price asks timed out (priceAskFailureTimeoutMS / priceAskPrimaryTimeoutMS with
     * no progress), answers shared from a near goal's kept path, asks joined to a near goal's running search, fluid cache
     * hits, fluid cache fills, advisor break cache hits, advisor break asks made} since the game started; {-1} from a fork
     * without them. Entropy job-steer: [7] walks steered by {@code walk(goal, true)} with no recorded price (a fork without
     * it gives seven).
     */
    default long[] cpuCounts() {
        return new long[]{-1};
    }

    /**
     * Entropy job-seg: the state of the search a {@link FirstSegment} was read from. RUNNING: the price search still runs
     * (the segment is its best-so-far path, or its first landed segment); LANDED: the price landed complete (the segment is
     * its kept path's first segment); NONE: no search and no kept path (never handed out: {@link #firstSegment} is null).
     */
    enum SearchState {
        RUNNING, LANDED, NONE;

        /** Pure: a running search wins over a kept landing (the running one is the newer). */
        public static SearchState of(boolean running, boolean landed) {
            return running ? RUNNING : landed ? LANDED : NONE;
        }
    }

    /**
     * Entropy job-seg: a price search's first walkable segment: its cells (start first), its cost in ticks from its start
     * (an estimate while RUNNING: the best-so-far cost, never the price), whether its last cell is in the goal, the state.
     */
    record FirstSegment(java.util.List<BlockPos> cells, double ticks, boolean reachesGoal, SearchState state) {
        /** Pure (unit-tested): null (rule 15: not known) for the state NONE or fewer than two cells (no node past the start). */
        public static FirstSegment of(java.util.List<? extends BlockPos> cells, double ticks, Goal goal, SearchState state) {
            if (state == null || state == SearchState.NONE || cells == null || cells.size() < 2) {
                return null;
            }
            BlockPos end = cells.get(cells.size() - 1);
            return new FirstSegment(java.util.List.copyOf(cells), ticks, goal != null && goal.isInGoal(end), state);
        }

        public String line() {
            return cells.size() + " cells, " + Math.round(ticks) + " ticks";
        }
    }

    /**
     * Entropy job-seg: the first segment of the goal's running price search (its best-so-far path, or its first landed
     * segment) or of its kept complete path; null when no search for the goal runs and none is kept, or the search has no
     * node past its start yet (rule 15: not known). Game thread. Null from an older fork.
     */
    default FirstSegment firstSegment(Goal goal) {
        return null;
    }

    /** Entropy CPU item 2: the start of a timed-out price ask's reason ("not known: the search ran out of its N ms"). */
    String TIMED_OUT = "not known: the search ran out of its";

    /** Entropy D11 build 2: the stored routes of this world and dimension, one line each, oldest first (index = forget's). */
    default java.util.List<String> routeList() {
        return java.util.List.of();
    }

    /** Entropy D11 build 2: forgets stored route {@code index} of {@link #routeList} (the file is rewritten); false: none. */
    default boolean forget(int index) {
        return false;
    }

    /** Every kept path, offer and running search is dropped (running searches are cancelled). */
    void forget();
}
