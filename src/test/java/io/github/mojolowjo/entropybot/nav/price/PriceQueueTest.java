package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.search.goals.GoalBlock;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.world.GridView;
import io.github.mojolowjo.entropybot.sim.SimSearch;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nav step 7: the price queue end to end on a fake world - a price searched, kept and answered from the feet (exact,
 * suffix), a block on the path changing, the re-join after a step off the path, a goal in an unloaded chunk, a withdrawn
 * ask, the running limit and the queue, a stored route used by a fresh queue. The searches run inline; they land at tick().
 */
class PriceQueueTest {

    @BeforeAll
    static void boot() {
        SimSearch.boot();
    }

    private static final BetterBlockPos A = new BetterBlockPos(0, 64, 0);
    private static final GoalBlock G = new GoalBlock(20, 64, 0);
    private static final IPricedPath.Limits LIM = new IPricedPath.Limits(6000, 1200, 16, 4);

    private static IPathCost.Answer landed(PriceQueue q, CompletableFuture<IPathCost.Answer> f) {
        for (int i = 0; i < 20 && !f.isDone(); i++) q.tick();
        assertTrue(f.isDone(), "the search landed");
        return f.join();
    }

    @Test
    void aPriceIsSearchedKeptAndAnsweredFromTheFeet() {
        FakePriceWorld w = new FakePriceWorld(GridView.flat(), A);
        PriceQueue q = new PriceQueue(w, PriceConfig.defaults());
        CompletableFuture<IPathCost.Answer> f = q.price(G, LIM);
        assertFalse(f.isDone(), "a new search: answered when it lands");
        IPathCost.Answer a = landed(q, f);
        assertTrue(a.complete, String.valueOf(a));
        assertEquals(21, a.path.size(), "20 traverses from x 0 to x 20");
        assertEquals(new BetterBlockPos(20, 64, 0), a.path.get(20));
        assertEquals(1, q.searchCounts()[0], "one segment search");
        // the same ask again: kept, exact, no search
        IPathCost.Answer again = q.price(G, LIM).join();
        assertEquals(a.ticks, again.ticks, 1e-9);
        assertEquals(1, q.searchCounts()[0]);
        // the feet moved along the path: the kept rest
        w.start = new BetterBlockPos(5, 64, 0);
        IPathCost.Answer rest = q.price(G, LIM).join();
        assertTrue(rest.complete);
        assertEquals(a.ticks * 15 / 20, rest.ticks, 1e-6, "a flat line: 15 of the 20 equal moves left");
        assertEquals(16, rest.path.size());
        assertEquals(1, q.searchCounts()[0], "still no second search");
        assertNotNull(q.keptPath(G));
        assertEquals(1, q.keptSegments(G).size());
        assertEquals(Boolean.FALSE, q.steered(G), "a level goal: no climb or dig steer");
    }

    @Test
    void aBlockChangedOnThePathDropsTheKeptAnswer() {
        GridView v = GridView.flat();
        FakePriceWorld w = new FakePriceWorld(v, A);
        PriceQueue q = new PriceQueue(w, PriceConfig.defaults());
        double ticks = landed(q, q.price(G, LIM)).ticks;
        v.set(10, 64, 0, Blocks.STONE.defaultBlockState()).set(10, 65, 0, Blocks.STONE.defaultBlockState());
        // D11 (the repair): a change mid-path keeps the path for a re-join from the cell after the changed move, said at once
        assertEquals(0, q.blockChanged(new BetterBlockPos(10, 65, 0), Blocks.STONE.defaultBlockState()), "not dropped: kept for a re-join");
        List<String> drops = q.drainDrops();
        assertTrue(drops.stream().anyMatch(s -> s.contains("block changed at 10 65 0") && s.contains("kept for a re-join")), String.valueOf(drops));
        // the next ask joins the kept path past the wall: round it, dearer
        IPathCost.Answer b = landed(q, q.price(G, LIM));
        assertEquals(1, q.joinCounts()[2], "the join spliced");
        assertTrue(b.complete);
        assertTrue(b.ticks > ticks, b.ticks + " > " + ticks);
        assertFalse(b.path.contains(new BetterBlockPos(10, 64, 0)), "not through the wall's cell");
    }

    @Test
    void aStepOffThePathReJoinsIt() {
        FakePriceWorld w = new FakePriceWorld(GridView.flat(), A);
        PriceQueue q = new PriceQueue(w, PriceConfig.defaults());
        landed(q, q.price(G, LIM));
        w.start = new BetterBlockPos(3, 64, 2);                     // two blocks off the line
        IPathCost.Answer a = landed(q, q.price(G, LIM));
        assertTrue(a.complete, String.valueOf(a));
        assertEquals(new BetterBlockPos(20, 64, 0), a.path.get(a.path.size() - 1));
        long[] j = q.joinCounts();
        assertEquals(1, j[1], "one join search started");
        assertEquals(1, j[2], "and spliced");
    }

    @Test
    void aGoalInAnUnloadedChunkIsNotKnownUntilAChunkLoads() {
        GridView v = GridView.flat().liveWithin(16);
        FakePriceWorld w = new FakePriceWorld(v, A);
        PriceQueue q = new PriceQueue(w, PriceConfig.defaults());
        GoalBlock far = new GoalBlock(40, 64, 0);
        IPathCost.Answer a = q.price(far, LIM).join();
        assertEquals(IPathCost.Answer.CHUNKS, a.reason);
        assertEquals(0, q.searchCounts()[0], "no search");
        assertEquals(IPathCost.Answer.CHUNKS, q.price(far, LIM).join().reason, "kept: no search at every ask");
        assertEquals(0, q.searchCounts()[0]);
        v.liveWithin(256);
        assertEquals(IPathCost.Answer.CHUNKS, q.price(far, LIM).join().reason, "loaded now, but kept until a chunk-load event says so");
        q.chunkLoaded(2, 0);
        assertFalse(q.price(far, LIM).isDone(), "a chunk loaded: searched again");
    }

    @Test
    void aWithdrawnAskIsAnsweredWithdrawn() {
        FakePriceWorld w = new FakePriceWorld(GridView.flat(), A);
        w.hold = true;
        PriceQueue q = new PriceQueue(w, PriceConfig.defaults());
        CompletableFuture<IPathCost.Answer> f = q.price(G, LIM);
        assertEquals(1, q.pending());
        assertTrue(q.cancel(G));
        assertEquals(IPricedPath.WITHDRAWN, f.join().reason);
        assertEquals(0, q.pending());
        assertFalse(q.cancel(G), "nothing left to withdraw");
    }

    @Test
    void pastTheRunningLimitAnAskIsQueuedAndStartedByTheTick() {
        FakePriceWorld w = new FakePriceWorld(GridView.flat(), A);
        w.hold = true;
        PriceQueue q = new PriceQueue(w, PriceConfig.defaults());
        IPricedPath.Limits one = new IPricedPath.Limits(6000, 1200, 16, 1);
        GoalBlock other = new GoalBlock(0, 64, 20);
        CompletableFuture<IPathCost.Answer> f = q.price(G, one);
        assertEquals(IPricedPath.QUEUED, q.price(other, one).join().reason);
        w.runHeld();
        q.tick();                                                   // G lands; the queue starts the other goal
        assertTrue(f.join().complete);
        assertEquals(1, q.pending(), "the queued goal's search runs");
        w.runHeld();
        q.tick();
        assertEquals(1, q.queueLandings(), "its landing is counted for the re-price");
        assertTrue(q.price(other, one).join().complete, "kept: answered at once");
    }

    @Test
    void aSavedRouteIsTheKeptPathOfAFreshQueue(@TempDir java.nio.file.Path dir) {
        FakePriceWorld w = new FakePriceWorld(GridView.flat(), A);
        w.routesDir = dir;
        PriceQueue q = new PriceQueue(w, PriceConfig.defaults());
        double ticks = landed(q, q.price(G, LIM)).ticks;
        assertFalse(q.walkEnded(G), "the feet are not in the goal: nothing saved");
        w.start = new BetterBlockPos(20, 64, 0);
        assertTrue(q.walkEnded(G));
        assertEquals(1, q.routeList().size());
        assertTrue(java.nio.file.Files.exists(dir.resolve(RouteStore.FILE)));
        FakePriceWorld w2 = new FakePriceWorld(GridView.flat(), A);
        w2.routesDir = dir;
        PriceQueue fresh = new PriceQueue(w2, PriceConfig.defaults());
        CompletableFuture<IPathCost.Answer> f = fresh.price(G, LIM);
        assertTrue(f.isDone(), "the stored route answers at once");
        assertEquals(ticks, f.join().ticks, 1e-9);
        assertEquals(0, fresh.searchCounts()[0], "no search");
        assertEquals(1, fresh.routeCounts()[1], "reused");
        assertTrue(fresh.forget(0));
        assertEquals(0, fresh.routeList().size());
    }

    /** The stamp: what the price used besides the blocks; a hungry bot cannot sprint, so the kept sprinting price goes. */
    @Test
    void whatThePriceUsedChangingDropsIt() {
        FakePriceWorld w = new FakePriceWorld(GridView.flat(), A);
        PriceQueue q = new PriceQueue(w, PriceConfig.defaults());
        landed(q, q.price(G, LIM));
        w.body = new io.github.mojolowjo.entropybot.nav.rules.BodySnapshot(java.util.Collections.nCopies(9, net.minecraft.world.item.ItemStack.EMPTY),
                0, -1, -1, 0, 1f, 5, false, true, false);
        assertFalse(q.price(G, LIM).isDone(), "searched again");
        assertTrue(q.drainDrops().stream().anyMatch(s -> s.contains("canSprint changed (true -> false)")));
    }
}
