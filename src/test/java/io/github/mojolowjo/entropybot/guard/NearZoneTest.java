package io.github.mojolowjo.entropybot.guard;

import org.junit.jupiter.api.Test;

import java.util.List;

import io.github.mojolowjo.entropybot.guard.GuardCore.Verdict;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.21.2: the near-me zone's rule. AREAS (0.39.0): the zone is a fact for AA, never a permission: the guard does not read
 * it, the world is open anyway, and the protect boxes and the floor win everywhere.
 */
class NearZoneTest {
    static final String OW = "minecraft:overworld";

    static GuardCore strict(List<Box> areas, List<Box> protect) {
        GuardCore g = new GuardCore();
        g.setPolicy(new Policy(areas, protect));
        g.setMode(GuardCore.Mode.STRICT);
        return g;
    }

    @Test
    void pointInZoneRadiusAndHeight() {
        int[] owner = {100, 64, 200};
        assertTrue(NearZone.inZone(owner, OW, 16, OW, 116, 64, 200), "16 east: on the edge (r)");
        assertTrue(NearZone.inZone(owner, OW, 16, OW, 100, 64, 184), "16 north: on the edge");
        assertFalse(NearZone.inZone(owner, OW, 16, OW, 117, 64, 200), "17 east (r + 1)");
        assertFalse(NearZone.inZone(owner, OW, 16, OW, 116, 64, 184), "the old square's corner is outside the circle");
        assertFalse(NearZone.inZone(owner, OW, 16, OW, 114, 64, 214), "diagonal at r * 1.2 (about 19.2) is outside");
        assertFalse(NearZone.inZone(owner, OW, 16, OW, 112, 64, 212), "diagonal at about 17 is outside");
        assertTrue(NearZone.inZone(owner, OW, 16, OW, 111, 64, 211), "diagonal at about 15.6 is inside");
        assertTrue(NearZone.inZone(owner, OW, 16, OW, 108, 64, 208), "diagonal at r * 0.7 (about 11.3) is inside");
        assertTrue(NearZone.inZone(owner, OW, 16, OW, 89, 64, 200), "r * 0.7 west is inside");
        assertTrue(NearZone.inZone(owner, OW, 16, OW, 100, 48, 200), "16 down");
        assertFalse(NearZone.inZone(owner, OW, 16, OW, 100, 47, 200), "17 down");
        assertFalse(NearZone.inZone(owner, OW, 16, OW, 100, 81, 200), "17 up");
        assertFalse(NearZone.inZone(null, OW, 16, OW, 100, 64, 200), "owner unknown: no zone");
        assertFalse(NearZone.inZone(owner, OW, 16, "minecraft:the_nether", 100, 64, 200), "another dimension");
        assertNull(NearZone.boxAt("minecraft:the_nether", 0, 64, 0, 16), "no zone in the Nether");
        assertNull(NearZone.boxAt("minecraft:the_end", 0, 64, 0, 16), "no zone in the End");
    }

    @Test
    void boxesInTheCircleGoByTheirCorners() {
        Box c = NearZone.boxAt(OW, 0, 64, 0, 16);
        assertTrue(c.round);
        assertTrue(new Box("t", OW, -11, 60, -11, 11, 70, 11).inside(c), "corners at about 15.6");
        assertFalse(new Box("t", OW, -12, 60, -12, 12, 70, 12).inside(c), "fits the square, not the circle (corners at about 17)");
        assertFalse(new Box("t", OW, 10, 60, 0, 16, 70, 13).inside(c), "one corner out");
        assertTrue(new Box("t", OW, 0, 60, 0, 16, 70, 0).inside(c), "a strip to the edge");
        assertFalse(new Box("t", OW, 0, 60, -2, 16, 70, 0).inside(c), "(16, -2) is just outside");
        assertFalse(new Box("t", OW, -2, 40, -2, 2, 70, 2).inside(c), "y range below the zone");
        assertFalse(new Box("t", OW, -2, 60, -2, 2, 81, 2).inside(c), "y range above the zone");
        // the gap the walking fence uses
        assertEquals(0, c.gap(16, 64, 0));
        assertEquals(1, c.gap(17, 64, 0));
        assertEquals(1, c.gap(12, 64, 12), "about 16.97 away: ceil(distance - r) = 1");
        assertEquals(4, c.gap(14, 64, 14), "about 19.8 away: 4");
        assertEquals(3, c.gap(0, 83, 0), "3 above the top");
        // JSON round trip keeps the shape
        Box j = Box.fromJson(c.toJson(), OW);
        assertTrue(j.round);
        assertEquals(16, j.r);
        assertFalse(j.contains(OW, 16, 64, 16));
        assertTrue(j.contains(OW, 16, 64, 0));
        // the miner's clipping: strips that each lie inside the circle and cover exactly its columns
        java.util.List<int[]> strips = c.clip(new int[]{-40, 60, -40, 40, 70, 40});
        int cols = 0;
        for (int[] s : strips) {
            assertTrue(new Box("s", OW, s[0], s[1], s[2], s[3], s[4], s[5]).inside(c), java.util.Arrays.toString(s));
            cols += (s[3] - s[0] + 1) * (s[5] - s[2] + 1);
        }
        int expect = 0;
        for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) if (x * x + z * z <= 256) expect++;
        assertEquals(expect, cols, "every column of the circle, none outside");
        assertTrue(c.clip(new int[]{17, 60, 17, 30, 70, 30}).isEmpty());
        // an ordinary rectangle is unchanged
        Box rect = new Box("a", OW, 0, -64, 0, 10, 300, 10);
        assertEquals(1, rect.clip(new int[]{-5, 60, -5, 5, 70, 5}).size());
        assertTrue(new Box("t", OW, 0, 60, 0, 10, 70, 10).inside(rect));
        assertTrue(rect.columnsInside(0, 0, 10, 10));
    }

    @Test
    void radiusBounds() {
        assertNull(NearZone.checkRadius(4));
        assertNull(NearZone.checkRadius(64));
        assertNotNull(NearZone.checkRadius(3));
        assertNotNull(NearZone.checkRadius(65));
        NearZone z = new NearZone();
        assertTrue(z.on());
        assertEquals(16, z.radius());
        z.set(true, 999);
        assertEquals(16, z.radius(), "out of bounds is ignored");
        z.set(true, 24);
        assertEquals(24, z.radius());
    }

    @Test
    void updateFollowsTheOwnerAndGoesOffWhenUnknown() {
        NearZone z = new NearZone();
        Box b = z.update(OW, new int[]{0, 70, 0}, "view", 1000);
        assertEquals(-16, b.x1);
        assertEquals(54, b.y1);
        assertEquals(86, b.y2);
        assertTrue(z.describe(1000).contains("around you at 0 70 0 via view"), z.describe(1000));
        b = z.update(OW, new int[]{50, 70, 0}, "companion", 2000);
        assertEquals(34, b.x1, "it moved with the owner");
        assertNull(z.update(OW, null, null, 3000), "unknown: no zone (no guessing)");
        assertNull(z.box());
        assertEquals(7000, z.unknownForMs(10_000));
        assertTrue(z.describe(10_000).contains("I don't know where you are"));
        z.set(false, 16);
        assertNull(z.update(OW, new int[]{0, 70, 0}, "view", 11_000), "off: never a zone");
        assertEquals(0, z.unknownForMs(20_000));
        assertTrue(z.describe(20_000).startsWith("near me: off"));
        z.set(true, 16);
        z.error("boom");
        assertEquals(1, z.errors());
        assertNull(z.box());
        assertTrue(z.describe(30_000).contains("1 errors"));
    }

    @Test
    void theZoneIsAFactNotAPermission() {
        // AREAS: the guard's zone holds the owner's spot for AA's area.nearOwner fact; the policy never changes
        Box area = new Box("base", OW, 0, -64, 0, 50, 300, 50);
        GuardCore g = strict(List.of(area), List.of());
        g.near.update(OW, new int[]{200, 64, 200}, "view", 1000);
        assertNotNull(g.near.box());
        assertEquals(1, g.policy().areas.size(), "the zone never joins the policy");
        assertTrue(g.status(0, false, false).getAsJsonObject("near").has("box"), "status still reports it");
        // the world is open with or without the zone: walking and leases work everywhere outside the protect boxes
        assertTrue(g.checkBoxes(OW, 230, 64, 200, "go").allowed(), "30 blocks from the owner, no area: fine");
        assertTrue(g.checkBoxes(OW, 1000, 64, 1000, "go").allowed());
        String id = g.lease("job", "dig", new Box("dig", OW, 204, 63, 204, 206, 65, 206), false, false);
        assertTrue(id.startsWith("L"), id);
        assertFalse(g.leases().get(id).near, "no lease is granted 'through the zone' any more");
        assertTrue(g.checkBoxes(OW, 205, 64, 205, "break").allowed());
        assertFalse(g.checkBoxes(OW, 210, 64, 210, "break").allowed(), "the lease rule still holds");
        assertTrue(GuardCore.cellLeasable(g.policy(), OW, 100, 64, 100));
    }

    @Test
    void protectBoxesAndTheFloorAlwaysWin() {
        Box keep = new Box("keep", OW, -4, 60, -4, 4, 70, 4);
        GuardCore g = strict(List.of(), List.of(keep));
        g.near.update(OW, new int[]{0, 64, 0}, "view", 1000);
        Verdict v = g.checkBoxes(OW, 0, 64, 0, "break");
        assertFalse(v.allowed());
        assertTrue(v.floor());
        assertTrue(v.reason().contains("in keep (no-break)"));
        assertTrue(g.checkBoxes(OW, 0, 64, 0, "go").allowed(), "a no-break box is walked through");
        String id = g.lease("j", "t", new Box("t", OW, 10, 64, 10, 11, 65, 11), false, false);
        assertTrue(id.startsWith("L"));
        assertFalse(g.check(OW, 10, 64, 10, "break", GuardCore.BlockInfo.of(true, true, false)).allowed(), "a chest in the zone");
        assertFalse(g.check(OW, 10, 64, 10, "break", GuardCore.BlockInfo.of(true, false, true)).allowed(), "a built block in the zone");
        assertTrue(g.check(OW, 10, 64, 10, "break", GuardCore.BlockInfo.PLAIN).allowed());
        assertTrue(g.lease("j", "t", new Box("t", OW, 3, 64, 3, 6, 65, 6), false, false).startsWith("error: that box touches keep (no-break)"));
        assertFalse(g.checkBoxes("minecraft:the_nether", 0, 64, 0, "go").allowed());
    }

    @Test
    void keepOutRefusesTheWalkToo() {
        Box pit = new Box("pit", OW, -4, 60, -4, 4, 70, 4).withType(AreaType.KEEP_OUT);
        GuardCore g = strict(List.of(), List.of(pit));
        Verdict v = g.checkBoxes(OW, 0, 64, 0, "go");
        assertFalse(v.allowed());
        assertTrue(v.floor(), "a keep-out walk veto is the floor: every mode");
        assertEquals("in pit (keep-out)", v.reason());
        assertEquals("in pit (keep-out)", g.checkBoxes(OW, 0, 64, 0, "break").reason());
        assertTrue(g.checkBoxes(OW, 5, 64, 0, "go").allowed(), "next to it is fine");
        g.setMode(GuardCore.Mode.LOG);
        assertFalse(g.checkBoxes(OW, 0, 64, 0, "go").allowed(), "log mode too");
    }

    @Test
    void logModeStaysLogMode() {
        GuardCore g = new GuardCore();
        g.setPolicy(new Policy(List.of(), List.of()));
        g.setMode(GuardCore.Mode.LOG);
        Verdict v = g.checkBoxes(OW, 0, 64, 0, "break");
        assertTrue(v.allowed());
        assertTrue(v.wouldVeto(), "log mode only notes, as before");
        assertEquals("no lease here", v.reason());
    }
}
