package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.guard.DrownRisk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WaterWaysTest {
    static WaterWays.Wet wet(int oreDepth, int air, int[] floor, int[] place, int[] dry) {
        return new WaterWays.Wet(true, oreDepth, air, 2, floor, floor == null ? -1 : 3, 4, place, place == null ? -1 : 3, 4, dry, dry == null ? -1 : 6, 10);
    }

    @Test void cellRiskGrowsWithDepthAndFallsWithAir() {
        assertTrue(DrownRisk.cellRisk(10, 1, 300) > DrownRisk.cellRisk(2, 1, 300));
        assertTrue(DrownRisk.cellRisk(4, 1, 100) > DrownRisk.cellRisk(4, 1, 300));
        assertTrue(Double.isInfinite(DrownRisk.breakMult(30, 60, 2)));
        assertTrue(DrownRisk.placeMult(1, 300) < DrownRisk.breakMult(1, 300, 2));
    }

    @Test void floatingIsTheDearestWay() {
        var w = wet(4, 300, new int[]{0, 0, 0}, new int[]{1, 0, 0}, new int[]{2, 0, 0});
        WaterWays.Priced fl = WaterWays.price(WaterWays.Way.FLOAT, w);
        // floating 25x submerged: a stone pickaxe at full air is nearly fatal at 4 under, priced or dropped
        if (fl != null) {
            for (WaterWays.Way x : List.of(WaterWays.Way.STAND, WaterWays.Way.PLACE, WaterWays.Way.DRY))
                assertTrue(WaterWays.price(x, w).seconds() < fl.seconds(), x.name());
        }
        assertTrue(WaterWays.price(WaterWays.Way.DRY, w).seconds() < WaterWays.price(WaterWays.Way.STAND, w).seconds());
    }

    @Test void placeNeedsABlockAndLowAirDropsTheWetWays() {
        var none = new WaterWays.Wet(true, 4, 300, 2, null, -1, 0, new int[]{1, 0, 0}, 3, 4, null, -1, 0);
        assertNull(WaterWays.price(WaterWays.Way.PLACE, none));
        var low = wet(8, 20, new int[]{0, 0, 0}, null, new int[]{2, 0, 0});
        assertNull(WaterWays.price(WaterWays.Way.STAND, low));
        assertNotNull(WaterWays.price(WaterWays.Way.DRY, low));
    }

    @Test void ironOptionsOffersTheWaterRowsAndTheirCommands() {
        var w = wet(4, 300, new int[]{5, 40, 5}, new int[]{6, 39, 5}, null);
        var ore = new IronOptions.Ore(5, 40, 6, 8, true, true, false, 20, false, null, false, 0, null, w);
        var f = new IronOptions.Facts(List.of(ore), 44, true, 10, true, -1, false, "no", -1, false, false, 10);
        var c = IronOptions.choose(f, new IronOptions.Memory(), 0);
        assertNotEquals(IronOptions.Way.WALK_KNOWN, c.way(), c.why());
        assertTrue(c.ranked().stream().anyMatch(s -> s.way() == IronOptions.Way.WATER_STAND));
        var place = c.ranked().stream().filter(s -> s.way() == IronOptions.Way.WATER_PLACE).findFirst().orElseThrow();
        var one = new IronOptions.Choice(place.way(), place.target(), IronOptions.Smelt.CAMP, c.ranked(), c.dropped(), place.stand(), place.place());
        assertEquals("place cobblestone 6 39 5 then goto 6 40 5 then mine iron 1 at 5 40 6 dig", one.command(3));
        var stand = c.ranked().stream().filter(s -> s.way() == IronOptions.Way.WATER_STAND).findFirst().orElseThrow();
        assertEquals("goto 5 40 5 then mine iron 1 at 5 40 6 dig",
                new IronOptions.Choice(stand.way(), stand.target(), IronOptions.Smelt.CAMP, c.ranked(), c.dropped(), stand.stand(), null).command(3));
    }

    @Test void swimWatchEntersThenStalls() {
        SwimWatch sw = new SwimWatch();
        assertEquals(SwimWatch.Event.NONE, sw.tick(false, true, 1, 0));
        assertEquals(SwimWatch.Event.ENTERED, sw.tick(true, true, 1, 1000));
        assertEquals(SwimWatch.Event.NONE, sw.tick(true, true, 1, 15_000));
        assertEquals(SwimWatch.Event.NONE, sw.tick(true, true, 2, 18_000));        // progress resets the clock
        assertEquals(SwimWatch.Event.NONE, sw.tick(true, true, 2, 37_000));
        assertEquals(SwimWatch.Event.STALLED, sw.tick(true, true, 2, 38_000));
        assertEquals(SwimWatch.Event.NONE, sw.tick(true, false, 2, 80_000));       // nothing running
    }
}
