package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.guard.Policy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T1-T3 (2026-10-04): the night of Oct 3-4, a 3x3 tunnel area (x -110..8591, z 853..855, y -46..-44) in strict mode.
 * The table's place lease (a 3x3x3) never fit it; the clear chased a drop to 260 -48 852 (2 below the floor, 1 out).
 */
class TunnelNightTest {
    static final String OW = "minecraft:overworld";
    static final Box TUNNEL = new Box("tunnel", OW, -110, -46, 853, 8591, -44, 855);

    static GuardCore tunnelGuard() {
        GuardCore g = new GuardCore();
        g.setPolicy(new Policy(List.of(TUNNEL), List.of()));
        g.setMode(GuardCore.Mode.STRICT);
        return g;
    }

    @Test
    void aBlockItemsPlaceLeaseIsTheCellAlone() {
        GuardCore g = tunnelGuard();
        LeaseSet l = new LeaseSet(g, "tok", OW, null);
        assertNull(l.placeLease(244, -46, 854, "placing crafting_table at 244 -46 854"), "the table's cell fits the tunnel");
        assertTrue(g.checkBoxes(OW, 244, -46, 854, "place").allowed());
        assertFalse(g.checkBoxes(OW, 244, -46, 855, "place").allowed(), "only the cell is leased");
        assertNull(l.placeLease(244, -46, 855, "the side cell"), "the 3-pickaxe trip's cell too");
        String r = new LeaseSet(g, "tok", OW, null).placeLease(244, -47, 854, "the floor");
        assertTrue(r.startsWith("error: the guard refused: that box is not inside one of my areas"), r);
    }

    @Test
    void aBucketLeasesTheClickedBlockToo() {
        PlaceRules.Side floor = PlaceRules.SIDES.get(0);           // clicking the block under the cell
        assertEquals(ClearBox.of(5, 9, 5, 5, 10, 5), PlaceRules.placeLeaseBox(5, 10, 5, floor, false));
        assertEquals(ClearBox.of(5, 10, 5, 5, 10, 5), PlaceRules.placeLeaseBox(5, 10, 5, floor, true));
        assertEquals(ClearBox.of(5, 10, 5, 5, 10, 5), PlaceRules.placeLeaseBox(5, 10, 5));
        PlaceRules.Side east = PlaceRules.SIDES.get(1);            // the neighbour at x+1
        assertEquals(ClearBox.of(5, 10, 5, 6, 10, 5), PlaceRules.placeLeaseBox(5, 10, 5, east, false));
        // in the tunnel a bucket on the floor can't be leased (the floor is outside), a block item can
        LeaseSet l = new LeaseSet(tunnelGuard(), "tok", OW, null);
        assertNotNull(l.placeLease(PlaceRules.placeLeaseBox(244, -46, 854, floor, false), "water"));
        assertNull(l.placeLease(PlaceRules.placeLeaseBox(244, -46, 854, floor, true), "a block"));
    }

    @Test
    void cellLeasableFollowsAreasAndProtectBoxes() {
        Policy p = new Policy(List.of(TUNNEL), List.of(new Box("door", OW, 300, -46, 853, 300, -44, 855)));
        assertTrue(GuardCore.cellLeasable(p, OW, 244, -46, 854));
        assertFalse(GuardCore.cellLeasable(p, OW, 244, -47, 854));
        assertFalse(GuardCore.cellLeasable(p, OW, 300, -45, 854), "a protect box");
        assertFalse(GuardCore.cellLeasable(p, "minecraft:the_nether", 244, -46, 854));
    }

    /** Stone everywhere around, the tunnel dug out x 0..20, y 53..55, z 0..2. */
    static FakeWorld tunnel() {
        FakeWorld w = new FakeWorld();
        w.fill(-6, 26, 53, 60, -6, 8, "stone");
        w.fill(0, 20, 53, 55, 0, 2, "air");
        return w;
    }

    @Test
    void theTableGoesAtTheSideOfTheWalkwayInsideTheArea() {
        FakeWorld w = tunnel();
        Bot bot = Bot.at(5.5, 53, 1.5);                              // the middle of the walkway
        // the first cell in order (x+1, on the walkway's middle line) has no wall; the sides stand against the rock
        assertEquals(0, PlaceRules.wallSides(w, 6, 53, 1));
        assertEquals(1, PlaceRules.wallSides(w, 5, 53, 2));
        assertEquals(new Pos(5, 53, 2), PlaceRules.tableSpot(w, bot, null), "at the side of the walkway");
        // the fence: only cells inside the area (here: x <= 5 only)
        Pos fenced = PlaceRules.tableSpot(w, bot, null, c -> c.x() <= 5);
        assertTrue(fenced.x() <= 5 && fenced.z() != 1, "inside and at the side: " + fenced);
        assertNull(PlaceRules.tableSpot(w, bot, null, c -> false), "nowhere allowed: none");
    }

    @Test
    void standSpotsAndDropsStayInsideTheAreasWithTheFenceOn() {
        ClearJob j = ClearJob.start(new ClearJob.Options().box(ClearBox.of(247, -46, 853, 310, -44, 855)), null, null);
        assertTrue(j.dropWanted(260, -48, 852), "the night: a drop 2 below the floor and 1 out was chased");
        Policy p = new Policy(List.of(TUNNEL), List.of());
        j.standOk = (x, y, z) -> p.areaAt(OW, x, y, z) != null;
        assertFalse(j.dropWanted(260, -48, 852), "with the fence on it stays where it lies");
        assertTrue(j.dropWanted(260, -46, 854));
        assertFalse(j.dropWanted(200, -46, 854), "far from the box, as before");

        // the clear's walk: the nearest spot that sees the face, and never one the fence refuses
        FakeWorld w = tunnel();
        Bot bot = Bot.at(2.5, 53, 1.5);
        ClearJob dig = ClearJob.start(new ClearJob.Options().box(ClearBox.of(21, 53, 0, 24, 55, 2)), null, null);
        ClearGrid.Plan free = ClearGrid.planWalk(w, bot, dig);
        assertNotNull(free);
        ClearGrid.Spot s0 = free.spot();
        ClearJob fenced = ClearJob.start(new ClearJob.Options().box(ClearBox.of(21, 53, 0, 24, 55, 2)), null, null);
        fenced.standOk = (x, y, z) -> !(x == s0.x() && y == s0.y() && z == s0.z());
        ClearGrid.Plan other = ClearGrid.planWalk(w, bot, fenced);
        assertNotNull(other);
        assertNotEquals(s0.key(), other.spot().key(), "the refused spot is never chosen");
        ClearJob nowhere = ClearJob.start(new ClearJob.Options().box(ClearBox.of(21, 53, 0, 24, 55, 2)), null, null);
        nowhere.standOk = (x, y, z) -> false;
        assertNull(ClearGrid.planWalk(w, bot, nowhere), "no spot inside: nothing to walk to");
        // placing: the stand spot obeys it too
        Pos cell = new Pos(10, 53, 1);
        ClearGrid.Spot ps = PlaceRules.standFor(w, cell, Bot.at(2.5, 53, 1.5), (x, y, z) -> x <= 8);
        assertNotNull(ps);
        assertTrue(ps.x() <= 8, "stand spot inside: " + ps);
        assertNull(PlaceRules.standFor(w, cell, Bot.at(2.5, 53, 1.5), (x, y, z) -> false));
    }

    @Test
    void torchLeaseIsTheBoxNotAShell() {
        ClearJob j = ClearJob.start(new ClearJob.Options().box(ClearBox.of(247, -46, 853, 310, -44, 855))
                .torches(List.of(new Pos(250, -46, 854))), null, null);
        LeaseSet l = new LeaseSet(tunnelGuard(), "tok", OW, null);
        assertNull(l.take(j.torchLeaseTask(), j.torchLeaseBox(), true, false), "fits the tunnel area");
    }
}
