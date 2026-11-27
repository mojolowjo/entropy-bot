package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.price.IPathCost;
import io.github.mojolowjo.entropybot.nav.price.IPricedPath;
import io.github.mojolowjo.entropybot.nav.price.PathMemory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * J3 review: the askers' futures of a withdrawn ask (IPricedPath.cancel) get WITHDRAWN as it is - never re-asked from the
 * feet, which would start the search the mod just cancelled; an error passes the same way; any other landing is re-asked.
 */
public class PricedPathWithdrawTest {

    private static CompletableFuture<IPathCost.Answer> landedWith(IPathCost.Answer a, AtomicInteger reasks) {
        CompletableFuture<IPathCost.Answer> landed = new CompletableFuture<>();
        CompletableFuture<IPathCost.Answer> asker = PathMemory.fromFeet(landed, PriceQueue::passOn, () -> {
            reasks.incrementAndGet();
            return CompletableFuture.completedFuture(IPathCost.Answer.complete(42));
        });
        landed.complete(a);
        return asker;
    }

    @Test
    public void aWithdrawnAskIsHandedOnNotReasked() {
        AtomicInteger reasks = new AtomicInteger();
        IPathCost.Answer w = IPathCost.Answer.none(IPricedPath.WITHDRAWN);
        assertTrue(PriceQueue.passOn(w));
        assertSame(w, landedWith(w, reasks).join());
        assertEquals(0, reasks.get(), "no new search for a withdrawn ask");
    }

    @Test
    public void anErrorPassesAndAPathIsReaskedFromTheFeet() {
        AtomicInteger reasks = new AtomicInteger();
        IPathCost.Answer err = IPathCost.Answer.none(IPathCost.Answer.ERROR);
        assertSame(err, landedWith(err, reasks).join());
        assertEquals(0, reasks.get());
        IPathCost.Answer q = IPathCost.Answer.none(IPricedPath.QUEUED);
        assertFalse(PriceQueue.passOn(q));
        assertEquals(42, landedWith(q, reasks).join().ticks, 0, "a queued (cancelled for priority) landing is re-asked");
        assertEquals(1, reasks.get());
        landedWith(IPathCost.Answer.complete(100), reasks).join();
        assertEquals(2, reasks.get(), "a landed path is re-asked from the feet (rule 18)");
    }
}
