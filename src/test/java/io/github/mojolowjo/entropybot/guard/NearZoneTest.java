package io.github.mojolowjo.entropybot.guard;

import org.junit.jupiter.api.Test;

import java.util.List;

import io.github.mojolowjo.entropybot.guard.GuardCore.Verdict;

import static org.junit.jupiter.api.Assertions.*;

/** 0.21.2: the near-me zone's rule, and how the guard counts it as one more area (only adding permission). */
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
        assertTrue(NearZone.inZone(owner, OW, 16, OW, 116, 64, 184), "the corner of the square");
        assertFalse(NearZone.inZone(owner, OW, 16, OW, 117, 64, 200), "17 blocks east");
        assertTrue(NearZone.inZone(owner, OW, 16, OW, 100, 48, 200), "16 down");
        assertFalse(NearZone.inZone(owner, OW, 16, OW, 100, 47, 200), "17 down");
        assertFalse(NearZone.inZone(owner, OW, 16, OW, 100, 81, 200), "17 up");
        assertFalse(NearZone.inZone(null, OW, 16, OW, 100, 64, 200), "owner unknown: no zone");
        assertFalse(NearZone.inZone(owner, OW, 16, "minecraft:the_nether", 100, 64, 200), "another dimension");
        assertNull(NearZone.boxAt("minecraft:the_nether", 0, 64, 0, 16), "no zone in the Nether");
        assertNull(NearZone.boxAt("minecraft:the_end", 0, 64, 0, 16), "no zone in the End");
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
    void zoneAllowsWorkOutsideTheAreasInStrictMode() {
        Box area = new Box("base", OW, 0, -64, 0, 50, 300, 50);
        GuardCore g = strict(List.of(area), List.of());
        assertFalse(g.checkBoxes(OW, 200, 64, 200, "go").allowed(), "outside the areas, no zone");
        g.setNearZone(NearZone.boxAt(OW, 200, 64, 200, 16));
        assertTrue(g.checkBoxes(OW, 210, 64, 210, "go").allowed(), "walking near the owner");
        Verdict v = g.checkBoxes(OW, 230, 64, 200, "go");
        assertFalse(v.allowed(), "30 blocks away");
        assertTrue(v.reason().contains("more than 16 blocks from you"), v.reason());
        assertFalse(g.checkBoxes(OW, 205, 64, 205, "break").allowed(), "the lease rule still holds");
        String id = g.lease("job", "dig", new Box("dig", OW, 204, 63, 204, 206, 65, 206), false, false);
        assertTrue(id.startsWith("L"), id);
        assertTrue(g.leases().get(id).near, "granted through the zone");
        assertTrue(g.checkBoxes(OW, 205, 64, 205, "break").allowed());
        assertTrue(g.lease("job", "far", new Box("far", OW, 230, 63, 200, 231, 64, 200), false, false).contains("or within 16 blocks of you"));
        assertTrue(g.lease("job", "deep", new Box("deep", OW, 204, 40, 204, 205, 41, 205), false, false).startsWith("error"), "below the zone's height");
        // the owner walks off: the zone moves, the dig's own lease stays good inside its box only
        g.setNearZone(NearZone.boxAt(OW, 400, 64, 400, 16));
        assertTrue(g.checkBoxes(OW, 205, 64, 205, "break").allowed(), "a dig started next to the owner finishes");
        assertFalse(g.checkBoxes(OW, 210, 64, 210, "break").allowed(), "but nothing past its box");
        assertFalse(g.checkBoxes(OW, 210, 64, 210, "go").allowed(), "walking there is outside again");
        // a new policy keeps the near lease
        g.setPolicy(new Policy(List.of(area), List.of()));
        assertTrue(g.leases().containsKey(id));
        assertEquals(1, g.basePolicy().areas.size(), "the base policy never holds the zone");
        assertEquals(2, g.policy().areas.size());
        assertEquals(1, g.status(0, false, false).getAsJsonArray("areas").size(), "status lists the owner's areas");
        assertTrue(g.status(0, false, false).getAsJsonObject("near").has("box"));
    }

    @Test
    void zoneWithNoAreasAndTheEmptyCase() {
        GuardCore g = strict(List.of(), List.of());
        assertEquals("no areas set", g.checkBoxes(OW, 0, 64, 0, "break").reason());
        assertEquals("error: no areas set and I can't see where you are (near me: 16 blocks)", g.lease("j", "t", new Box("t", OW, 0, 64, 0, 1, 65, 1), false, false));
        g.setNearZone(NearZone.boxAt(OW, 0, 64, 0, 16));
        String id = g.lease("j", "t", new Box("t", OW, 0, 64, 0, 1, 65, 1), true, false);
        assertTrue(id.startsWith("L"), id);
        assertTrue(g.checkBoxes(OW, 1, 65, 1, "place").allowed());
        g.setNearZone(null);
        assertTrue(GuardCore.cellLeasable(g.policy(), OW, 100, 64, 100) == false);
        assertEquals(0, g.policy().areas.size(), "no zone: back to nothing");
    }

    @Test
    void protectBoxesAndTheFloorAlwaysWin() {
        Box keep = new Box("keep", OW, -4, 60, -4, 4, 70, 4);
        GuardCore g = strict(List.of(), List.of(keep));
        g.setNearZone(NearZone.boxAt(OW, 0, 64, 0, 16));
        Verdict v = g.checkBoxes(OW, 0, 64, 0, "break");
        assertFalse(v.allowed());
        assertTrue(v.floor());
        assertTrue(v.reason().contains("protected (keep)"));
        String id = g.lease("j", "t", new Box("t", OW, 10, 64, 10, 11, 65, 11), false, false);
        assertTrue(id.startsWith("L"));
        assertFalse(g.check(OW, 10, 64, 10, "break", GuardCore.BlockInfo.of(true, true, false)).allowed(), "a chest in the zone");
        assertFalse(g.check(OW, 10, 64, 10, "break", GuardCore.BlockInfo.of(true, false, true)).allowed(), "a built block in the zone");
        assertTrue(g.check(OW, 10, 64, 10, "break", GuardCore.BlockInfo.PLAIN).allowed());
        g.setNearZone(new Box(NearZone.NAME, "minecraft:the_nether", -16, 48, -16, 16, 80, 16));
        assertNull(g.nearBox(), "a zone in a denied dimension is dropped");
        assertFalse(g.checkBoxes("minecraft:the_nether", 0, 64, 0, "go").allowed());
    }

    @Test
    void logModeStaysLogMode() {
        GuardCore g = new GuardCore();
        g.setPolicy(new Policy(List.of(), List.of()));
        g.setMode(GuardCore.Mode.LOG);
        Verdict v = g.checkBoxes(OW, 0, 64, 0, "break");
        assertTrue(v.allowed());
        assertTrue(v.wouldVeto(), "log mode only notes, as before");
    }
}
