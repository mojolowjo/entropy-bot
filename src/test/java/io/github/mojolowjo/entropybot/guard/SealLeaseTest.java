package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.clear.LeaseSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Water plan: placing into water or air within one block of the job box is allowed (the ring around a tunnel exactly as
 * wide as its area lies outside the area), never inside a protect box, never replacing anything but water or air.
 */
class SealLeaseTest {
    static final String OW = "minecraft:overworld";
    /** The tunnel area: 3 wide (z 853..855), y -46..-44; a protect box at x 400..410. */
    static final String POLICY = "{\"areas\":[{\"name\":\"tunnel\",\"x1\":-110,\"y1\":-46,\"z1\":853,\"x2\":8591,\"y2\":-44,\"z2\":855}],"
            + "\"protect\":[{\"name\":\"farm\",\"x1\":400,\"y1\":-60,\"z1\":840,\"x2\":410,\"y2\":-30,\"z2\":870}]}";
    static final GuardCore.BlockInfo WATER = GuardCore.BlockInfo.placing(true), STONE = GuardCore.BlockInfo.placing(false);

    static GuardCore guard() {
        GuardCore g = new GuardCore();
        g.setMode(GuardCore.Mode.STRICT);
        g.tick(1);
        assertTrue(g.setPolicy(Policy.parse(POLICY)).startsWith("ok"));
        return g;
    }

    @Test
    void aSealNeedsABreakLeaseNextToIt() {
        GuardCore g = guard();
        Box ring = new Box(null, OW, 377, -45, 856, 377, -45, 856);
        assertTrue(g.sealLease("tok", "seal", ring).startsWith("error: 377 -45 856 is not within a block of my dig"));
        assertFalse(g.check(OW, 377, -45, 856, "place", WATER).allowed(), "outside the area: refused without a seal");
        String dig = g.lease("tok", "dig", new Box(null, OW, 376, -46, 853, 378, -44, 855), false, false);
        assertFalse(dig.startsWith("error"), dig);
        assertTrue(g.sealLease("other", "seal", ring).startsWith("error"), "another owner's dig doesn't count");
        String id = g.sealLease("tok", "seal", ring);
        assertFalse(id.startsWith("error"), id);
        assertTrue(g.check(OW, 377, -45, 856, "place", WATER).allowed(), "into water: fine");
        GuardCore.Verdict v = g.check(OW, 377, -45, 856, "place", STONE);
        assertFalse(v.allowed());
        assertEquals("a seal only fills water or air", v.reason());
        assertTrue(v.floor());
        assertFalse(g.check(OW, 377, -45, 856, "break", GuardCore.BlockInfo.PLAIN).allowed(), "a seal never breaks");
        assertFalse(g.check(OW, 377, -45, 857, "place", WATER).allowed(), "only its own cell");
    }

    @Test
    void neverInAProtectBoxNorTwoBlocksOut() {
        GuardCore g = guard();
        assertFalse(g.lease("tok", "dig", new Box(null, OW, 395, -46, 853, 399, -44, 855), false, false).startsWith("error"));
        assertTrue(g.sealLease("tok", "seal", new Box(null, OW, 400, -45, 856, 400, -45, 856)).contains("protect box"));
        assertTrue(g.sealLease("tok", "seal", new Box(null, OW, 397, -45, 857, 397, -45, 857)).contains("not within a block"));
        assertTrue(g.sealLease("tok", "seal", new Box(null, OW, 397, -45, 856, 397, -46, 856)).startsWith("error"), "one block only");
    }

    @Test
    void theLeaseSetTakesTheDigNextToTheSealItself() {
        GuardCore g = guard();
        LeaseSet ls = new LeaseSet(g, "tok", OW, null);
        ClearBox dig = ClearBox.of(300, -46, 853, 400, -44, 855);
        // inside the area: the plain cell lease
        assertNull(ls.sealLease(350, -45, 854, dig, "seal"));
        // the ring, outside the 3-wide area: a seal next to a break lease of the dig's part beside it
        assertNull(ls.sealLease(350, -43, 856, dig, "seal"), "the ring's corner above and beside");
        assertTrue(g.check(OW, 350, -43, 856, "place", WATER).allowed());
        assertFalse(g.check(OW, 350, -43, 856, "place", STONE).allowed());
        assertTrue(ls.sealLease(350, -45, 858, dig, "seal").startsWith("error"), "two out: refused");
        ls.releaseAll();
        assertTrue(g.leases().isEmpty());
        assertFalse(g.check(OW, 350, -43, 856, "place", WATER).allowed(), "released");
    }
}
