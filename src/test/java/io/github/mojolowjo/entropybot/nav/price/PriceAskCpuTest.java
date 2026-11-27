package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.price.IPathCost;
import io.github.mojolowjo.entropybot.nav.price.IPricedPath;
import io.github.mojolowjo.entropybot.nav.search.goals.GoalBlock;
import io.github.mojolowjo.entropybot.nav.search.goals.GoalGetToBlock;
import io.github.mojolowjo.entropybot.nav.search.goals.GoalYLevel;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Entropy CPU items 2 and 3: the price ask's timed-out answer (rule 15) and the share radius. */
public class PriceAskCpuTest {

    @Test
    public void aTimedOutAskAnswersNotKnown() {
        IPricedPath.Step s = PriceQueue.timedOut(IPricedPath.Step.of(false, false, false, IPathCost.Stop.TIME, 0, 16), IPathCost.Stop.TIME, 2000);
        assertFalse(s.done());
        assertFalse(s.next());
        assertEquals("not known: the search ran out of its 2000 ms (priceAskFailureTimeoutMS)", s.reason());
        assertNotEquals(IPathCost.Answer.UNREACHABLE, s.reason());
        // a path with no progress, stopped by the clock: not known too
        IPricedPath.Step np = PriceQueue.timedOut(IPricedPath.Step.of(true, false, false, IPathCost.Stop.TIME, 0, 16), IPathCost.Stop.TIME, 1500);
        assertTrue(np.reason().startsWith(IPricedPath.TIMED_OUT));
    }

    @Test
    public void otherStopsKeepTheirAnswers() {
        IPricedPath.Step ex = IPricedPath.Step.of(false, false, false, IPathCost.Stop.EXHAUSTED, 0, 16);
        assertSame(ex, PriceQueue.timedOut(ex, IPathCost.Stop.EXHAUSTED, 2000));
        assertEquals(IPathCost.Answer.UNREACHABLE, ex.reason());
        IPricedPath.Step next = IPricedPath.Step.of(true, false, true, IPathCost.Stop.TIME, 1, 16);
        assertSame(next, PriceQueue.timedOut(next, IPathCost.Stop.TIME, 2000), "a timed-out segment that progressed searches on");
        IPricedPath.Step budget = IPricedPath.Step.of(true, false, true, IPathCost.Stop.TIME, 16, 16);
        assertSame(budget, PriceQueue.timedOut(budget, IPathCost.Stop.TIME, 2000));
    }

    @Test
    public void goalsWithinTheRadiusAreNear() {
        GoalBlock a = new GoalBlock(10, 64, 10);
        assertTrue(PriceQueue.near(a, new GoalBlock(12, 62, 8), 2));
        assertFalse(PriceQueue.near(a, new GoalBlock(13, 64, 10), 2));
        assertFalse(PriceQueue.near(a, new GoalBlock(10, 64, 10), 0), "radius 0 = off");
        assertFalse(PriceQueue.near(a, new GoalGetToBlock(new BlockPos(10, 64, 10)), 2), "another kind of goal");
        assertFalse(PriceQueue.near(new GoalYLevel(64), new GoalYLevel(64), 2), "no single cell");
    }
}
