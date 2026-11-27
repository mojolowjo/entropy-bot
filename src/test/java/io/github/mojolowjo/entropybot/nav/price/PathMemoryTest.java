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
 * Entropy Bot 2026-11-27: the fork's PathMemoryTest (fork-only class, from the fork's source at dd8235b7)
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.price.IPathCost;
import io.github.mojolowjo.entropybot.nav.price.IPricedPath;
import io.github.mojolowjo.entropybot.nav.price.PathMemory;
import io.github.mojolowjo.entropybot.nav.price.PathMemory.Cell;
import io.github.mojolowjo.entropybot.nav.price.PathMemory.Decision;
import io.github.mojolowjo.entropybot.nav.price.PathMemory.Kind;
import io.github.mojolowjo.entropybot.nav.price.PathMemory.Stamp;
import io.github.mojolowjo.entropybot.nav.price.PathMemory.Watch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Entropy z57 (owner 2026-10-25: "while doing stuff it should not forget the path that was already planned"; run P 23:46:34-38:
 * "-33 70 -55 -> -36 64 -60" priced 10 times from one cell; "-31 16 -48 -> -31 16 -48" priced 7 times, 0 ticks): the
 * priced path's memory with hand-made fakes - a 5-cell path along x at y 64 on a stone floor, moves costing 10, 20, 5 and
 * 15 ticks (cum 0, 10, 30, 35, 50), each move watching floor, feet and head of its two cells, goal "G", cells read as
 * words ("unloaded" = its chunk is not loaded). The real segments and Baritone's classification: PricedPathBuildTest.
 */
public class PathMemoryTest {

    private static final String GOAL = "G";
    private static final Object WORLD = new Object();
    private static final IPricedPath.Limits LIM = new IPricedPath.Limits(6000, 1200, 16, 4);

    private final Map<Cell, String> world = new HashMap<>();
    private final PathMemory<String, Object, String> m = new PathMemory<>();
    private long tick = 100;
    private Stamp stamp = stamp(Map.of(), Map.of(), Map.of());
    private Object level = WORLD;
    private int stampReads;

    static Stamp stamp(Map<String, Object> all, Map<String, Object> br, Map<String, Object> pl) {
        Map<String, Object> a = new LinkedHashMap<>(Map.of("canSprint", true, "rules epoch", 7L));
        a.putAll(all);
        Map<String, Object> b = new LinkedHashMap<>(Map.of("tools in the bag", "wooden_pickaxe"));
        b.putAll(br);
        Map<String, Object> p = new LinkedHashMap<>(Map.of("throwaway items in the bag", "cobblestone, oak_planks"));
        p.putAll(pl);
        return new Stamp(a, b, p);
    }

    /** Words: equal or not; "unloaded" not loaded; "air" a break's result; anything else solid a place's result. */
    private PathMemory.Blocks<String> blocks() {
        return new PathMemory.Blocks<>() {
            @Override public String read(Cell c) { return at(c); }
            @Override public boolean same(String was, String now, boolean breakPlanned) { return was.equals(now); }
            @Override public boolean notLoaded(String s) { return "unloaded".equals(s); }
            @Override public boolean broken(String s) { return "air".equals(s); }
            @Override public boolean placed(String s) { return !"air".equals(s) && !"unloaded".equals(s); }
        };
    }

    private static Cell c(int x) {
        return new Cell(x, 64, 0);
    }

    private String at(Cell c) {
        return world.getOrDefault(c, c.y() < 64 ? "stone" : "air");
    }

    private PathMemory.Now<String> now(Cell feet) {
        Stamp s = stamp;
        return new PathMemory.Now<>(feet, feet.equals(c(4)), level, () -> { stampReads++; return s; }, tick, this::blocks);
    }

    private Decision<Object> ask(Cell feet) {
        return m.ask(GOAL, now(feet), LIM);
    }

    /** The hand path: cells x 0..4, costs 10, 20, 5, 15; move i watches floor/feet/head of cells i and i + 1; reads now. */
    private PathMemory.Path<String> path(Set<Cell> breaks, Set<Cell> places) {
        List<Cell> cells = new ArrayList<>();
        List<Set<Cell>> valid = new ArrayList<>();
        Map<Cell, Watch<String>> watch = new HashMap<>();
        for (int x = 0; x <= 4; x++) {
            cells.add(c(x));
        }
        for (int i = 0; i < 4; i++) {
            valid.add(Set.of(c(i), c(i + 1)));
            for (Cell base : List.of(c(i), c(i + 1))) {
                for (int dy = -1; dy <= 1; dy++) {
                    Cell w = base.up(dy);
                    Watch<String> old = watch.get(w);
                    watch.put(w, new Watch<>(i, old != null ? old.was() : at(w), breaks.contains(w), places.contains(w)));
                }
            }
        }
        return new PathMemory.Path<>(cells, new double[]{0, 10, 30, 35, 50}, valid, watch);
    }

    /** One search from x 0 that lands with the hand path. */
    private Object searchAndLand(Set<Cell> breaks, Set<Cell> places) {
        Decision<Object> d = ask(c(0));
        assertEquals(Kind.START, d.kind());
        Object run = new Object();
        m.started(GOAL, c(0), run);
        assertTrue(m.landed(GOAL, run, IPathCost.Answer.complete(50), path(breaks, places), c(0), level, stamp, tick, "segments"));
        return run;
    }

    private long count(int i) {
        return m.counts()[i];
    }

    @Test
    public void theSameGoalAskedTenTimesFromOneCellIsOneSearch() {
        // run P 23:46:34-38: 10 prices of one walk from one cell while crafting
        searchAndLand(Set.of(), Set.of());
        for (int i = 0; i < 9; i++) {
            tick += 5;
            Decision<Object> d = ask(c(0));
            assertEquals(Kind.EXACT, d.kind());
            assertEquals(50, d.answer().ticks, 1e-9);
            assertTrue(d.answer().complete);
        }
        assertEquals(1, count(PathMemory.STARTED));
        assertEquals(9, count(PathMemory.EXACT));
        assertEquals(10, count(PathMemory.ASKS));
    }

    @Test
    public void walkingAlongThePathSearchesNothingAndThePriceFallsByTheWalkedMoves() {
        searchAndLand(Set.of(), Set.of());
        double[] rest = {50, 40, 20, 15};          // 50 - 10, - 20, - 5 (hand)
        for (int x = 1; x <= 3; x++) {
            Decision<Object> d = ask(c(x));
            assertEquals(Kind.SUFFIX, d.kind());
            assertEquals(rest[x], d.answer().ticks, 1e-9);
            assertEquals(c(x), d.cells().get(0));
            assertEquals(5 - x, d.cells().size());
        }
        assertEquals(1, count(PathMemory.STARTED));
        assertEquals(3, count(PathMemory.SUFFIX));
        PathMemory.Walk<String> w = m.walk(GOAL, now(c(3)), LIM);
        assertNotNull(w.path());
        assertEquals(3, w.index());
        assertEquals("segments", w.extra());
    }

    // ---- Entropy D11: the re-join (run FA: 230 of 330 drops were "left the path", each a whole new search) ----

    /** A cell beside the path (off every cell and move position). */
    private static Cell off(int x) {
        return new Cell(x, 64, 1);
    }

    @Test
    public void leavingThePath_keepsItAndAsksAJoin_neverADrop() {
        searchAndLand(Set.of(), Set.of());
        ask(c(1));                                                   // walked to cell 1: progress 1
        Decision<Object> d = ask(off(1));
        assertEquals(Kind.REJOIN, d.kind());
        assertEquals(1, d.index(), "from the last progress");
        assertEquals(List.of(c(1), c(2), c(3), c(4)), d.cells());
        assertNull(d.answer());
        assertEquals("left the path at 1 64 1 (on no cell and no move position of it)", d.dropped(), "the re-join's why (rule 7)");
        assertEquals(0, count(PathMemory.DROPPED), "nothing dropped");
        assertNotNull(m.path(GOAL), "the kept path stays");
        assertEquals(1, count(PathMemory.REJOINS));
        // the join search runs under the goal: a second ask joins it, the path still kept
        Object join = new Object();
        m.started(GOAL, off(1), join);
        Decision<Object> d2 = ask(off(2));
        assertEquals(Kind.JOIN, d2.kind());
        assertSame(join, d2.join());
        assertNotNull(m.path(GOAL));
        // the join landed with the spliced path (the caller built it): exact from the new start
        List<Cell> cells = List.of(off(1), c(1), c(2), c(3), c(4));
        List<Set<Cell>> valid = new ArrayList<>();
        for (int i = 0; i < 4; i++) valid.add(Set.of(cells.get(i), cells.get(i + 1)));
        PathMemory.Path<String> spliced = new PathMemory.Path<>(cells, new double[]{0, 8, 28, 33, 48}, valid, Map.of());
        assertTrue(m.landed(GOAL, join, IPathCost.Answer.complete(48), spliced, off(1), level, stamp, tick, "spliced"));
        Decision<Object> d3 = ask(off(1));
        assertEquals(Kind.EXACT, d3.kind());
        assertEquals(48, d3.answer().ticks, 1e-9);
        assertEquals(0, count(PathMemory.DROPPED));
    }

    @Test
    public void aFailedJoin_dropsThePath_andTheNextSearchWaits() {
        searchAndLand(Set.of(), Set.of());
        Decision<Object> d = ask(off(0));
        assertEquals(Kind.REJOIN, d.kind());
        Object join = new Object();
        m.started(GOAL, off(0), join);
        assertFalse(m.rejoinFailed(GOAL, new Object(), "x", tick), "another handle: not the running join");
        assertTrue(m.rejoinFailed(GOAL, join, "no path within the node cap", tick));
        assertNull(m.path(GOAL), "dropped");
        assertEquals(1, count(PathMemory.DROPPED));
        assertEquals(1, count(PathMemory.JOINS_FAILED));
        assertTrue(m.drainDrops().get(0).contains("the re-join of the kept path found no way (no path within the node cap)"));
        tick += 5;
        IPricedPath.Limits waits = new IPricedPath.Limits(6000, 1200, 16, 4, 0, 100, 1200);     // a re-search wait of 100 ticks (LIM has none)
        assertEquals(Kind.WAIT, m.ask(GOAL, now(off(0)), waits).kind(), "inside the re-search wait: not known");
        tick += 100;
        assertEquals(Kind.START, m.ask(GOAL, now(off(0)), waits).kind(), "the wait over: the whole search");
    }

    @Test
    public void oneReJoinPerReSearchWait_notOnePerLanding() {
        // a bot walking away from a kept path re-joins once; inside the wait the next ask off the path is "not known", the
        // path kept (was the loop risk: a join lands, the feet moved again, a join again, every landing)
        IPricedPath.Limits waits = new IPricedPath.Limits(6000, 1200, 16, 4, 0, 100, 1200);
        searchAndLand(Set.of(), Set.of());
        assertEquals(Kind.REJOIN, m.ask(GOAL, now(off(0)), waits).kind());
        Object join = new Object();
        m.started(GOAL, off(0), join);
        assertTrue(m.landed(GOAL, join, IPathCost.Answer.complete(50), path(Set.of(), Set.of()), c(0), level, stamp, tick, "spliced"));
        tick += 30;
        assertEquals(Kind.WAIT, m.ask(GOAL, now(off(1)), waits).kind(), "off the new path again inside the wait: not known, kept");
        assertNotNull(m.path(GOAL));
        tick += 100;
        assertEquals(Kind.REJOIN, m.ask(GOAL, now(off(1)), waits).kind());
    }

    @Test
    public void aReJoinWithNoFreeSlot_isQueued_andStartsAsAJoinWhenASlotFrees() {
        // D11 fix (run FF: 3,036 priced paths in 25 minutes, 2,196 re-joins - every REJOIN started its join at once past the
        // slots): a join is a search and takes a slot through the gate; none free: queued, and the pump's start is the JOIN
        IPricedPath.Limits one = new IPricedPath.Limits(6000, 1200, 16, 1, 0, 0, 1200);     // one slot, no re-search wait
        searchAndLand(Set.of(), Set.of());
        assertEquals(Kind.SUFFIX, m.ask(GOAL, now(c(1)), one).kind());   // walked to cell 1
        Object other = new Object();
        assertEquals(Kind.START, m.ask("H", now(c(0)), one).kind());
        m.started("H", c(0), other);                                 // the one slot is taken
        Decision<Object> d = m.ask(GOAL, now(off(1)), one);
        assertEquals(Kind.QUEUED, d.kind(), "no slot: queued, never a join past the slots");
        assertEquals(1, d.index(), "the re-join's aim kept with the queued ask");
        assertEquals("left the path at 1 64 1 (on no cell and no move position of it)", d.dropped(), "the re-join's why (rule 7)");
        assertEquals(1, count(PathMemory.REJOINS));
        assertEquals(1, count(PathMemory.JOINS_QUEUED));
        assertEquals(0, count(PathMemory.DROPPED));
        assertNotNull(m.path(GOAL), "the kept path stays");
        tick += 5;
        assertEquals(Kind.QUEUED, m.ask(GOAL, now(off(1)), one).kind(), "asked again: still queued (wanted)");
        assertEquals(1, count(PathMemory.REJOINS), "one re-join asked, not one per ask");
        assertNull(m.nextToStart(tick, one), "no slot yet");
        // the other search lands: the slot frees, the queued goal starts - as a join from its aim, read once
        assertTrue(m.landed("H", other, IPathCost.Answer.complete(50), path(Set.of(), Set.of()), c(0), level, stamp, tick, "s"));
        assertEquals(GOAL, m.nextToStart(tick, one));
        assertEquals(1, m.takeQueuedRejoin(GOAL));
        assertEquals(-1, m.takeQueuedRejoin(GOAL), "taken once");
        Object join = new Object();
        m.started(GOAL, off(1), join);
        assertEquals(Kind.JOIN, m.ask(GOAL, now(off(1)), one).kind());
        assertEquals(0, m.queuedCount());
    }

    @Test
    public void aQueuedReJoinWhosePathWasDropped_startsAsAWholeSearch() {
        IPricedPath.Limits one = new IPricedPath.Limits(6000, 1200, 16, 1, 0, 0, 1200);
        searchAndLand(Set.of(), Set.of());
        Object other = new Object();
        assertEquals(Kind.START, m.ask("H", now(c(0)), one).kind());
        m.started("H", c(0), other);
        assertEquals(Kind.QUEUED, m.ask(GOAL, now(off(0)), one).kind());
        level = new Object();                                        // the world changed: the kept path is stale
        assertEquals(Kind.QUEUED, m.ask(GOAL, now(off(0)), one).kind());
        assertNull(m.path(GOAL));
        assertEquals(1, count(PathMemory.DROPPED));
        assertEquals(-1, m.takeQueuedRejoin(GOAL), "nothing to re-join: a whole search when it starts");
    }

    @Test
    public void aChangedBlockMidPath_rejoinsFromTheCellAfterTheChangedMove() {
        searchAndLand(Set.of(), Set.of());
        world.put(c(2).up(-1), "air");                               // the floor of cell 2: moves 1 and 2 watch it (lastMove 2)
        Decision<Object> d = ask(c(0));
        assertEquals(Kind.REJOIN, d.kind());
        assertEquals(3, d.index(), "the first cell after move 2");
        assertEquals(List.of(c(3), c(4)), d.cells());
        assertEquals(0, count(PathMemory.DROPPED));
    }

    @Test
    public void aChangeAtThePathsEnd_isAWholeSearch() {
        searchAndLand(Set.of(), Set.of());
        world.put(c(4).up(-1), "air");                               // the goal cell's floor: move 3, the last
        Decision<Object> d = ask(c(0));
        assertEquals(Kind.START, d.kind());
        assertEquals(1, count(PathMemory.DROPPED));
    }

    @Test
    public void theBlockHook_marksTheRepair_theNextAskRejoins() {
        searchAndLand(Set.of(), Set.of());
        world.put(c(2).up(-1), "air");
        assertEquals(0, m.blockChanged(c(2).up(-1), "air", c(0), blocks()), "marked, not dropped");
        assertNotNull(m.path(GOAL));
        assertTrue(m.drainDrops().get(0).contains("kept for a re-join from its cell 3"));
        Decision<Object> d = ask(c(0));
        assertEquals(Kind.REJOIN, d.kind());
        assertEquals(3, d.index());
    }

    @Test
    public void aWalkWhileTheJoinIsDue_offersNothingAndDropsNothing() {
        searchAndLand(Set.of(), Set.of());
        PathMemory.Walk<String> w = m.walk(GOAL, now(off(0)), LIM);
        assertNull(w.path());
        assertTrue(w.why().contains("kept for a re-join"), w.why());
        assertNotNull(m.path(GOAL));
        assertEquals(0, count(PathMemory.DROPPED));
    }

    @Test
    public void aStampChange_isNoRejoin() {
        searchAndLand(Set.of(), Set.of());
        stamp = stamp(Map.of("canSprint", false), Map.of(), Map.of());
        Decision<Object> d = ask(off(0));
        assertEquals(Kind.START, d.kind(), "what the price used changed: a whole search");
        assertEquals(1, count(PathMemory.DROPPED));
    }

    @Test
    public void theGoalCellItselfAnswersZeroWithoutASearch() {
        // run P: "-31 16 -48 -> -31 16 -48" priced 7 times
        for (int i = 0; i < 7; i++) {
            Decision<Object> d = ask(c(4));
            assertEquals(Kind.ZERO, d.kind());
            assertEquals(0, d.answer().ticks, 1e-9);
        }
        assertEquals(0, count(PathMemory.STARTED));
        assertEquals(7, count(PathMemory.ZERO));
    }

    @Test
    public void twoAsksAtOnceAreOneSearch() {
        ask(c(0));
        Object run = new Object();
        m.started(GOAL, c(0), run);
        Decision<Object> second = ask(c(0));
        assertEquals(Kind.JOIN, second.kind());
        assertSame(run, second.join());
        assertEquals(1, count(PathMemory.STARTED));
        assertEquals(1, count(PathMemory.JOINED));
    }

    @Test
    public void aRunningSearchIsNeverCancelledBecauseTheBotMoved() {
        // review 2 item 4: a search cancelled at every new cell of a walk would never land - every ask joins it
        ask(c(0));
        Object run = new Object();
        m.started(GOAL, c(0), run);
        assertSame(run, ask(new Cell(5, 64, 3)).join());
        assertSame(run, ask(new Cell(40, 64, 0)).join());
        assertEquals(1, count(PathMemory.STARTED));
        assertEquals(2, count(PathMemory.JOINED));
        assertEquals(0, count(PathMemory.CANCELLED));
    }

    @Test
    public void theBotWalksSixCellsBeforeTheLandingAndGetsTheSuffixFromItsFeet() {
        // review 2 item 1 (rule 18): a 10-cell path, every move 10 ticks; the search starts at x 0, the bot walks to x 6
        // while it runs; the landed answer (90 from x 0) is never the feet's price: the asker gets the suffix from x 6 (30)
        List<Cell> cells = new ArrayList<>();
        List<Set<Cell>> valid = new ArrayList<>();
        double[] cum = new double[10];
        for (int x = 0; x < 10; x++) {
            cells.add(c(x));
            cum[x] = 10 * x;
            if (x > 0) valid.add(Set.of(c(x - 1), c(x)));
        }
        PathMemory.Path<String> ten = new PathMemory.Path<>(cells, cum, valid, Map.of());
        Cell[] feet = {c(0)};
        java.util.function.Supplier<PathMemory.Now<String>> nowAt = () ->
                new PathMemory.Now<>(feet[0], feet[0].equals(c(9)), level, () -> stamp, tick, this::blocks);
        assertEquals(Kind.START, m.ask(GOAL, nowAt.get(), LIM).kind());
        Object run = new Object();
        m.started(GOAL, c(0), run);
        java.util.concurrent.CompletableFuture<IPathCost.Answer> landed = new java.util.concurrent.CompletableFuture<>();
        java.util.function.Supplier<java.util.concurrent.CompletableFuture<IPathCost.Answer>> reask =
                () -> java.util.concurrent.CompletableFuture.completedFuture(m.ask(GOAL, nowAt.get(), LIM).answer());
        java.util.concurrent.CompletableFuture<IPathCost.Answer> starter = PathMemory.fromFeet(landed, a -> false, reask);
        feet[0] = c(6);
        assertEquals(Kind.JOIN, m.ask(GOAL, nowAt.get(), LIM).kind());
        java.util.concurrent.CompletableFuture<IPathCost.Answer> joiner = PathMemory.fromFeet(landed, a -> false, reask);
        assertTrue(m.landed(GOAL, run, IPathCost.Answer.complete(90), ten, c(0), level, stamp, tick, null));
        landed.complete(IPathCost.Answer.complete(90));
        assertEquals(30, starter.join().ticks, 1e-9);
        assertEquals(30, joiner.join().ticks, 1e-9);
        assertEquals(2, count(PathMemory.SUFFIX));
        assertEquals(1, count(PathMemory.STARTED));
        // an error passes as it is (nothing to re-ask)
        java.util.concurrent.CompletableFuture<IPathCost.Answer> err = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<IPathCost.Answer> passed = PathMemory.fromFeet(err, a -> IPathCost.Answer.ERROR.equals(a.reason), reask);
        err.complete(IPathCost.Answer.none(IPathCost.Answer.ERROR));
        assertEquals(IPathCost.Answer.ERROR, passed.join().reason);
    }

    @Test
    public void theStampIsBuiltOnlyWhenAKeptAnswerIsComparedAgainst() {
        // review 2 item 3: a ZERO answer and a first ask build none; an ask against a kept path builds one
        ask(c(4));
        ask(c(0));
        assertEquals(0, stampReads);
        Object run = new Object();
        m.started(GOAL, c(0), run);
        ask(c(0));                                             // joins: nothing kept to compare
        assertEquals(0, stampReads);
        m.landed(GOAL, run, IPathCost.Answer.complete(50), path(Set.of(), Set.of()), c(0), level, stamp, tick, null);
        assertEquals(Kind.EXACT, ask(c(0)).kind());
        assertEquals(1, stampReads);
    }

    @Test
    public void aChunksNotLoadedAnswerStandsOnlyUntilAChunkLoads() {
        // review 2 item 2 (rule 15): "chunks not loaded" is not a no-path answer; the next ask after a chunk load searches
        ask(c(0));
        m.started(GOAL, c(0), "r");
        assertTrue(m.landed(GOAL, "r", IPathCost.Answer.none(IPathCost.Answer.CHUNKS), null, c(0), level, stamp, tick, null));
        assertEquals(Kind.KEPT_NO_PATH, ask(c(0)).kind());     // no chunk loaded yet: no search at every re-price
        assertEquals(1, m.chunkLoaded("chunk 3 4"));
        assertEquals(List.of("to G: a chunk loaded (chunk 3 4): the \"chunks not loaded\" answer is asked again"), m.drainDrops());
        assertEquals(Kind.START, ask(c(0)).kind());
        // an unreachable answer is not touched by a chunk load
        m.started(GOAL, c(0), "r2");
        m.landed(GOAL, "r2", IPathCost.Answer.none(IPathCost.Answer.UNREACHABLE), null, c(0), level, stamp, tick, null);
        assertEquals(0, m.chunkLoaded("chunk 3 5"));
    }
    @Test
    public void aJoinedSearchFromBehindIsJudgedWhenItLandsSuffixWhenTheFeetAreOnIt() {
        ask(c(0));
        Object run = new Object();
        m.started(GOAL, c(0), run);
        assertEquals(Kind.JOIN, ask(c(2)).kind());               // the bot walked on while the search ran
        assertTrue(m.landed(GOAL, run, IPathCost.Answer.complete(50), path(Set.of(), Set.of()), c(0), level, stamp, tick, null));
        Decision<Object> d = ask(c(2));
        assertEquals(Kind.SUFFIX, d.kind());
        assertEquals(20, d.answer().ticks, 1e-9);
        assertEquals(1, count(PathMemory.STARTED));
    }

    @Test
    public void pastMaxRunningAnAskIsQueuedNotNone() {
        for (int i = 0; i < 4; i++) {
            m.started("other" + i, c(0), new Object());
        }
        Decision<Object> d = ask(c(0));
        assertEquals(Kind.QUEUED, d.kind());
        assertNull(d.answer());
    }

    @Test
    public void aBlockPlacedOnTheRemainingPathDropsItWithTheCellAndTheBlocks() {
        searchAndLand(Set.of(), Set.of());
        world.put(c(3), "crafting_table");          // run N 21:25:33: a table put on the kept path
        Decision<Object> d = ask(c(1));
        assertEquals(Kind.START, d.kind());
        assertEquals("block changed at 3 64 0 (air -> crafting_table)", d.dropped());
        assertEquals(List.of("to G: block changed at 3 64 0 (air -> crafting_table)"), m.drainDrops());
        assertEquals(List.of(), m.drainDrops());
        assertEquals(0, m.watchedCells());                   // the hook's index forgets the dropped path's cells
    }

    @Test
    public void theBlockUpdateHookDropsAtOnceThroughItsCellIndex() {
        searchAndLand(Set.of(), Set.of());
        assertTrue(m.watchedCells() > 0);
        assertEquals(0, m.blockChanged(new Cell(40, 64, 40), "stone", c(1), blocks()));   // no path watches it
        world.put(c(2).up(-1), "air");               // the floor under cell 2 broken
        // Entropy D11 (the repair): a change mid-path marks the kept path for a re-join from the cell after the changed move;
        // the path stays (was: dropped at once, a whole search)
        assertEquals(0, m.blockChanged(c(2).up(-1), "air", c(1), blocks()));
        assertEquals(List.of("to G: block changed at 2 63 0 (stone -> air) (block update): kept for a re-join from its cell 3 at the next ask"), m.drainDrops());
        Decision<Object> d = ask(c(1));
        assertEquals(Kind.REJOIN, d.kind());
        assertEquals(3, d.index());
        // a change at the path's END drops it at once: a whole search (nothing to re-join past)
        world.put(c(4).up(-1), "air");
        assertEquals(1, m.blockChanged(c(4).up(-1), "air", c(1), blocks()));
    }

    @Test
    public void thePathsOwnWorkAndBlocksBehindTheBotDoNotDropIt() {
        world.put(c(2), "dirt");                      // the path plans to break the dirt at cell 2
        world.put(c(3).up(-1), "air");                // and to place a floor under cell 3
        searchAndLand(Set.of(c(2)), Set.of(c(3).up(-1)));
        world.put(c(2), "air");                       // broken as planned
        world.put(c(3).up(-1), "cobblestone");        // placed as planned
        world.put(c(0).up(1), "torch");               // only move 0 watches it; the feet are at cell 2 (move 2)
        assertEquals(0, m.blockChanged(c(2), "air", c(2), blocks()));
        assertEquals(0, m.blockChanged(c(0).up(1), "torch", c(2), blocks()));
        Decision<Object> d = ask(c(2));
        assertEquals(Kind.SUFFIX, d.kind());
        assertEquals(20, d.answer().ticks, 1e-9);
    }

    @Test
    public void aPlannedBreakThatTurnedIntoSomethingElseDropsIt() {
        // review 3: the exemption is the EXPECTED result (a break -> air), not any change
        world.put(c(2), "dirt");
        searchAndLand(Set.of(c(2)), Set.of());
        world.put(c(2), "water");
        Decision<Object> d = ask(c(1));
        assertEquals("block changed at 2 64 0 (dirt -> water)", d.dropped());
        assertEquals(Kind.REJOIN, d.kind(), "D11: re-joined past the changed move, not dropped");
        assertEquals(3, d.index());
    }

    @Test
    public void anUnloadedCellIsNotKnownNotAirAndThePathComesBackWithTheChunk() {
        // review 3 / rule 15: a cell whose chunk is gone is "chunks not loaded", never "air" or "done"
        world.put(c(2), "dirt");
        searchAndLand(Set.of(c(2)), Set.of());
        world.put(c(2), "unloaded");
        Decision<Object> d = ask(c(1));
        assertEquals(Kind.NOT_LOADED, d.kind());
        assertTrue(d.answer().noPath);
        assertEquals(IPathCost.Answer.CHUNKS, d.answer().reason);
        assertEquals(0, count(PathMemory.STARTED) - 1);       // no new search
        assertEquals(0, m.blockChanged(c(2), "unloaded", c(1), blocks()));
        world.put(c(2), "dirt");                              // the chunk is back, unchanged
        assertEquals(Kind.SUFFIX, ask(c(1)).kind());
    }

    @Test
    public void midMoveOnAValidPositionTheFeetAreStillOnThePath() {
        // review 5: a fall's shaft / a diagonal's corner - PathExecutor's valid positions of the move count as on the path
        List<Cell> cells = List.of(new Cell(0, 70, 0), new Cell(1, 66, 0), new Cell(2, 66, 0));
        List<Set<Cell>> valid = List.of(Set.of(new Cell(0, 70, 0), new Cell(1, 70, 0), new Cell(1, 69, 0), new Cell(1, 68, 0),
                new Cell(1, 67, 0), new Cell(1, 66, 0)), Set.of(new Cell(1, 66, 0), new Cell(2, 66, 0)));
        Map<Cell, Watch<String>> watch = new HashMap<>();
        PathMemory.Path<String> p = new PathMemory.Path<>(cells, new double[]{0, 30, 34.6}, valid, watch);
        ask(new Cell(0, 70, 0));
        m.started(GOAL, new Cell(0, 70, 0), "r");
        m.landed(GOAL, "r", IPathCost.Answer.complete(34.6), p, new Cell(0, 70, 0), level, stamp, tick, null);
        Decision<Object> d = ask(new Cell(1, 68, 0));         // falling through the shaft
        assertEquals(Kind.EXACT, d.kind());                    // move 0: the whole price still ahead
        assertEquals(34.6, d.answer().ticks, 1e-9);
        assertEquals(Kind.SUFFIX, ask(new Cell(1, 66, 0)).kind());
        assertEquals("left the path at 3 66 0 (on no cell and no move position of it)", ask(new Cell(3, 66, 0)).dropped());
    }

    @Test
    public void leavingThePathReJoinsIt() {
        // Entropy D11 (was: leavingThePathDropsIt - a whole search; run FA: 230 such drops in 13 minutes)
        searchAndLand(Set.of(), Set.of());
        Decision<Object> d = ask(new Cell(1, 64, 1));
        assertEquals(Kind.REJOIN, d.kind());
        assertEquals("left the path at 1 64 1 (on no cell and no move position of it)", d.dropped());
        assertEquals(0, count(PathMemory.DROPPED));
    }

    @Test
    public void planksCraftedIntoSticksKeepAWalkThatPlacesNothing() {
        searchAndLand(Set.of(c(2)), Set.of());       // breaks, places nothing
        stamp = stamp(Map.of(), Map.of(), Map.of("throwaway items in the bag", "cobblestone"));
        assertEquals(Kind.EXACT, ask(c(0)).kind());
        assertEquals(1, count(PathMemory.STARTED));
    }

    @Test
    public void aThrowawayChangeDropsAWalkThatPlaces() {
        searchAndLand(Set.of(), Set.of(c(3).up(-1)));
        stamp = stamp(Map.of(), Map.of(), Map.of("throwaway items in the bag", "cobblestone"));
        Decision<Object> d = ask(c(0));
        assertEquals(Kind.START, d.kind());
        assertEquals("throwaway items in the bag changed (cobblestone, oak_planks -> cobblestone) (the path places blocks)", d.dropped());
    }

    @Test
    public void aNewToolDropsAWalkThatBreaksAndAMoveSettingDropsEveryWalk() {
        searchAndLand(Set.of(c(2)), Set.of());
        stamp = stamp(Map.of(), Map.of("tools in the bag", "stone_pickaxe"), Map.of());
        assertTrue(ask(c(0)).dropped().startsWith("tools in the bag changed"));
        stamp = stamp(Map.of(), Map.of(), Map.of());
        tick++;
        m.started(GOAL, c(0), "r2");
        assertTrue(m.landed(GOAL, "r2", IPathCost.Answer.complete(50), path(Set.of(), Set.of()), c(0), level, stamp, tick, null));
        stamp = stamp(Map.of("hasWaterBucket", true), Map.of(), Map.of());
        assertEquals("hasWaterBucket changed (none -> true)", ask(c(0)).dropped());
    }

    @Test
    public void theWorldAndTheSafetyAgeDropIt() {
        searchAndLand(Set.of(), Set.of());
        level = new Object();
        assertEquals("the world or dimension changed", ask(c(0)).dropped());
        level = WORLD;
        tick++;
        m.started(GOAL, c(0), "r2");
        m.landed(GOAL, "r2", IPathCost.Answer.complete(50), path(Set.of(), Set.of()), c(0), level, stamp, tick, null);
        tick += 6000;
        assertEquals(Kind.EXACT, ask(c(0)).kind());
        tick += 1;
        assertEquals("the kept path is 6001 ticks old, kept at most 6000 (pathCost.maxKeepS)", ask(c(0)).dropped());
    }

    @Test
    public void eachKeptAnswerHasItsOwnLandingNumber() {
        assertEquals(-1, m.landing(GOAL));
        searchAndLand(Set.of(), Set.of());
        long first = m.landing(GOAL);
        assertEquals(first, m.landing(GOAL));
        tick++;
        m.started(GOAL, c(0), "r2");
        m.landed(GOAL, "r2", IPathCost.Answer.complete(50), path(Set.of(), Set.of()), c(0), level, stamp, tick, null);
        assertNotEquals(first, m.landing(GOAL));
    }

    @Test
    public void aNoPathAnswerIsKeptFromItsOwnCellOnlyAndForItsShortKeep() {
        ask(c(0));
        m.started(GOAL, c(0), "r");
        IPathCost.Answer none = IPathCost.Answer.none(IPathCost.Answer.UNREACHABLE);
        assertTrue(m.landed(GOAL, "r", none, null, c(0), level, stamp, tick, "detail"));
        Decision<Object> d = ask(c(0));
        assertEquals(Kind.KEPT_NO_PATH, d.kind());
        assertSame(none, d.answer());
        assertEquals("detail", m.extra(GOAL));
        Decision<Object> moved = ask(c(1));
        assertEquals(Kind.START, moved.kind());
        assertEquals("the bot left the no-path answer's start 0 64 0 (now at 1 64 0)", moved.dropped());
    }

    // ---- Entropy CPU item 3: near goals share a kept answer and a running search ----

    private static final java.util.function.BiPredicate<String, String> NEAR = (a, b) -> a.startsWith("G") && b.startsWith("G");

    @Test
    public void aNearGoalReusesTheKeptPathFromTheFeet() {
        searchAndLand(Set.of(), Set.of());
        tick += 5;
        PathMemory.Share<String> s = m.shareKept("G2", now(c(1)), LIM, NEAR);
        assertNotNull(s);
        assertEquals(GOAL, s.goal());
        assertEquals(1, s.index());
        assertEquals(40, s.answer().ticks, 1e-9);                     // the suffix from x 1: 50 - 10
        assertEquals(List.of(c(1), c(2), c(3), c(4)), s.cells());
        assertNull(m.shareKept("H", now(c(1)), LIM, NEAR), "a goal not near shares nothing");
        assertNull(m.shareKept("G2", now(new Cell(9, 64, 9)), LIM, NEAR), "feet off the path share nothing");
        assertEquals(Kind.SUFFIX, ask(c(1)).kind(), "the kept answer itself is left as it was");
        assertEquals(0, count(PathMemory.DROPPED));
    }

    @Test
    public void aNearGoalJoinsTheRunningSearch() {
        ask(c(0));
        Object run = new Object();
        m.started(GOAL, c(0), run);
        assertSame(run, m.runningNear("G2", NEAR));
        assertNull(m.runningNear("H", NEAR));
        assertNull(m.runningNear(GOAL, NEAR), "its own goal is not a near one");
    }
}
