package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.chop.ChopLoop;
import io.github.mojolowjo.entropybot.guard.Litter.Context;
import io.github.mojolowjo.entropybot.guard.Litter.Facts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** aa-litter: the context table, the cleanup and reuse seconds, the keep-or-take-down price, the tall tree. */
class LitterTest {
    // skyVisible, nearBuildDist, caveOpen, overFluid, ownBox
    static Facts f(boolean sky, int near, boolean cave, boolean fluid, boolean own) { return new Facts(sky, near, cave, fluid, own); }

    @Test void contextTable() {
        assertEquals(Context.SURFACE, Litter.contextOf(f(true, 500, false, false, false)));          // under the sky
        assertEquals(Context.SURFACE, Litter.contextOf(f(false, 16, false, false, false)));           // within 16 of a build or edge
        assertEquals(Context.SEALED, Litter.contextOf(f(false, 17, false, false, false)));            // 17 away, underground
        assertEquals(Context.CAVE, Litter.contextOf(f(false, 40, true, false, false)));
        assertEquals(Context.SURFACE, Litter.contextOf(f(false, 3, true, false, false)));             // a cave mouth beside a build is on show
        assertEquals(Context.OVER_FLUID, Litter.contextOf(f(true, 3, false, true, false)));
        assertEquals(Context.EXEMPT, Litter.contextOf(f(true, 0, false, true, true)));                // own box beats everything
        assertEquals(Context.SURFACE, Litter.contextOf(Facts.UNKNOWN));                               // unknown fails to the dearest
        assertEquals(Context.SURFACE, Litter.contextOf(null));
        assertEquals(Context.SEALED, Litter.contextOf(f(false, Litter.FAR, false, false, false)));    // read, no build known
    }

    @Test void perBlockSeconds() {
        assertEquals(30, Litter.perBlock(Context.SURFACE));
        assertEquals(5, Litter.perBlock(Context.CAVE));
        assertEquals(2, Litter.perBlock(Context.SEALED));
        assertEquals(0, Litter.perBlock(Context.EXEMPT));
    }

    @Test void cleanupIsBreakTimePlusHalfSecondPlusWalk() {
        assertEquals(5 * (0.6 + 0.5) + 4, Litter.cleanupS(5, 2, 4), 1e-9);       // stone pickaxe, 5 blocks, 4 s back
        assertEquals(2 * (7.5 + 0.5), Litter.cleanupS(2, 0, 0), 1e-9);           // by hand
        assertEquals(0, Litter.cleanupS(0, 2, 10));
    }

    @Test void reuseIsCrossingsTimesTimeSaved() {
        assertEquals(60, Litter.reuseS(1, 0, 0, 0), 1e-9);                       // the return leg, way around unknown = 60 s
        assertEquals((1 + 0.5 * 4) * 20, Litter.reuseS(1, 0.5, 4, 20), 1e-9);    // + revisits = confidence x ore left
        assertEquals(0, Litter.reuseS(0, 0, 5, 30), 1e-9);                       // no crossings: nothing
        assertEquals(0, Litter.reuseS(-3, -1, -1, 30), 1e-9);                    // floored at 0
    }

    @Test void surfaceScaffoldIsTakenDown() {
        // 6 blocks in the open: litter 180 s, cleanup 6 x 1.1 + 3 = 9.6 s; reuse 60 s is capped at the cleanup
        Litter.Price p = Litter.price(f(true, 50, false, false, false), 6, 2, 3, Litter.reuseS(1, 0, 0, 0));
        assertEquals(Context.SURFACE, p.ctx());
        assertEquals(9.6, p.cleanupS(), 1e-9);
        assertEquals(9.6, p.reuseS(), 1e-9);                                      // capped at the cleanup
        assertEquals(180 - 9.6, p.litterS(), 1e-9);
        assertFalse(p.keep());
        assertEquals(9.6, p.priceS(), 1e-9);                                      // min(litter, cleanup)
        assertEquals("litter 170 s vs cleanup 10 s, reuse 10 s (surface, take down)", p.why());
    }

    @Test void sealedScaffoldWithReuseIsKept() {
        // 5 blocks sealed: litter 10 s, cleanup 5 x 1.1 + 3 = 8.5 s, reuse (60) capped at 8.5 -> litter net 1.5 < cleanup
        Litter.Price p = Litter.price(f(false, 40, false, false, false), 5, 2, 3, 60);
        assertEquals(Context.SEALED, p.ctx());
        assertEquals(1.5, p.litterS(), 1e-9);
        assertTrue(p.keep());
        assertEquals(1.5, p.priceS(), 1e-9);
    }

    @Test void sealedScaffoldWithoutReuseIsStillCheapToLeave() {
        // 3 blocks sealed, no reuse: litter 6 s < cleanup 3 x 1.1 + 3 = 6.3 s
        Litter.Price p = Litter.price(f(false, 40, false, false, false), 3, 2, 3, 0);
        assertTrue(p.keep());
        assertEquals(6, p.priceS(), 1e-9);
        // 4 blocks: litter 8 s vs cleanup 7.4 s -> take down, price = the cleanup
        Litter.Price q = Litter.price(f(false, 40, false, false, false), 4, 2, 3, 0);
        assertFalse(q.keep());
        assertEquals(7.4, q.priceS(), 1e-9);
    }

    @Test void caveBetweenTheTwo() {
        Litter.Price p = Litter.price(f(false, 40, true, false, false), 4, 2, 0, 0);
        assertEquals(20, p.litterS(), 1e-9);
        assertEquals(4.4, p.cleanupS(), 1e-9);
        assertFalse(p.keep());
        assertEquals(4.4, p.priceS(), 1e-9);
    }

    @Test void bridgeOverFluidIsCleanupOnlyUnlessReuseCoversIt() {
        Litter.Price gone = Litter.price(f(true, 3, false, true, false), 8, 2, 5, 0);
        assertEquals(Context.OVER_FLUID, gone.ctx());
        assertEquals(8 * 1.1 + 5, gone.cleanupS(), 1e-9);
        assertEquals(gone.cleanupS(), gone.priceS(), 1e-9);                       // the price is the cleanup, no litter number
        assertFalse(gone.keep());
        // the way around is 1000 s and a revisit is likely: the reuse covers the whole cleanup
        double reuse = Litter.reuseS(1, 0.8, 5, 1000);
        Litter.Price kept = Litter.price(f(true, 3, false, true, false), 8, 2, 5, reuse);
        assertTrue(kept.keep());
        assertEquals(0, kept.priceS(), 1e-9);
        assertTrue(kept.why().startsWith("bridge over fluid"));
    }

    @Test void ownBoxIsExempt() {
        Litter.Price p = Litter.price(f(true, 0, false, false, true), 10, 2, 5, 0);
        assertEquals(Context.EXEMPT, p.ctx());
        assertEquals(0, p.priceS());
        assertTrue(p.keep());
        assertEquals("litter 0 s (my own box)", p.why());
    }

    @Test void nothingPlacedCostsNothing() {
        Litter.Price p = Litter.price(f(true, 0, false, false, false), 0, 2, 5, 60);
        assertEquals(0, p.priceS());
        assertEquals("litter 0 s (nothing placed)", p.why());
    }

    @Test void siteFoldsTheReuseTerms() {
        var site = new Litter.Site(40, 2, 3, 1, 0.5, 4, 20, false);
        Litter.Price p = site.price(f(false, 40, false, false, false), 5);
        assertEquals((1 + 0.5 * 4) * 20, 60.0, 1e-9);
        assertEquals(Math.min(60, 5 * 1.1 + 3), p.reuseS(), 1e-9);
    }

    @Test void tallTreeSkipsTopLogsWhenThePillarCostsMore() {
        // top log 9 above the base: pillar 4; 4 blocks in the open: litter 120 s vs cleanup 4.4 + 3 s; placing 2.4 s; the top logs (4 x 4 s = 16 s)
        var tt = Litter.tallTree(f(true, -1, false, false, false), 9, 4, 2, 3);
        assertEquals(4, tt.pillar());
        assertEquals(Litter.TreeWay.ALL, tt.way());                                // 2.4 + 7.4 = 9.8 s <= 16 s: pillar and take it down
        assertEquals(2.4 + 7.4, tt.pillarS(), 1e-9);
        // one top log only: skipping it (4 s) is cheaper than 9.8 s of scaffold
        var one = Litter.tallTree(f(true, -1, false, false, false), 9, 1, 2, 3);
        assertEquals(Litter.TreeWay.SKIP_TOP, one.way());
        assertEquals(4, one.skipS(), 1e-9);
        assertTrue(one.why().startsWith("skip the top 1 logs"));
    }

    @Test void shortTreeNeedsNoPillar() {
        var tt = Litter.tallTree(f(true, -1, false, false, false), 5, 0, 2, 3);
        assertEquals(0, tt.pillar());
        assertEquals(Litter.TreeWay.ALL, tt.way());
        assertEquals(0, tt.price().priceS());
    }

    @Test void edgeDistanceInsideAndOutsideABox() {
        Box b = new Box("work", "minecraft:overworld", 0, -64, 0, 100, 320, 100);
        List<Box> one = List.of(b);
        assertEquals(30, Litter.nearestBoxGap(one, "minecraft:overworld", 30, 70));               // inside: the way out to the nearest side
        assertEquals(0, Litter.nearestBoxGap(one, "minecraft:overworld", 100, 50));               // on the edge
        assertEquals(7, Litter.nearestBoxGap(one, "minecraft:overworld", 107, 50));               // outside: the gap
        assertEquals(Litter.FAR, Litter.nearestBoxGap(one, "minecraft:the_nether", 30, 30));      // other dimension
        assertEquals(Litter.FAR, Litter.nearestBoxGap(List.of(), "minecraft:overworld", 1, 1));
    }

    @Test void chopOrderPricesTheScaffoldInSeconds() {
        // two reachable real trees: the nearer one is tall and its scaffold adds 30 s, so the farther plain one comes first
        var near = new ChopLoop.Cand(new int[]{10, 64, 0}, 100, true, false, 30);                 // 10 blocks = 2.3 s + 30 s
        var far = new ChopLoop.Cand(new int[]{30, 64, 0}, 900, true, false, 0);                   // 30 blocks = 7 s
        var out = ChopLoop.order(List.of(near, far));
        assertSame(far, out.get(0));
        // without the extra the nearer comes first, as before
        var near0 = new ChopLoop.Cand(new int[]{10, 64, 0}, 100, true);
        assertSame(near0, ChopLoop.order(List.of(far, near0)).get(0));
    }
}
