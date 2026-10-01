package io.github.mojolowjo.entropybot.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuardCoreTest {
    static final String OW = "minecraft:overworld";
    static final GuardCore.BlockInfo PLAIN = GuardCore.BlockInfo.PLAIN;
    static final GuardCore.BlockInfo CHEST = GuardCore.BlockInfo.of(true, true, false);
    static final GuardCore.BlockInfo PLANKS = GuardCore.BlockInfo.of(true, false, true);

    static final String POLICY = "{\"areas\":[{\"name\":\"map\",\"x1\":-272,\"z1\":-64,\"x2\":223,\"z2\":431}],"
            + "\"protect\":[{\"name\":\"base\",\"x1\":-40,\"y1\":45,\"z1\":170,\"x2\":-10,\"y2\":80,\"z2\":200}]}";

    private GuardCore withPolicy() {
        GuardCore g = new GuardCore();
        g.tick(1);
        assertTrue(g.setPolicy(Policy.parse(POLICY)).startsWith("ok"));
        return g;
    }

    private String lease(GuardCore g, boolean place) {
        String id = g.lease("tok", "stripmine", new Box(null, OW, -46, 16, 395, -44, 17, 398), place, false);
        assertFalse(id.startsWith("error"), id);
        return id;
    }

    @Test
    void failsClosedWithNoPolicy() {
        GuardCore g = new GuardCore();
        GuardCore.Verdict v = g.check(OW, 0, 16, 300, "break", PLAIN);
        assertFalse(v.allowed());
        assertEquals("no areas set", v.reason());
        assertFalse(g.checkBoxes(OW, 0, 16, 300, "go").allowed());
        assertTrue(g.lease("tok", "dig", new Box(null, OW, 0, 10, 0, 1, 11, 1), false, false).startsWith("error: no areas"));
    }

    @Test
    void floorBeatsEverything() {
        GuardCore g = withPolicy();
        lease(g, true);
        assertEquals("block entity (a chest, machine, bed...)", g.check(OW, -45, 16, 396, "break", CHEST).reason());
        assertEquals("built block", g.check(OW, -45, 16, 396, "break", PLANKS).reason());
        assertTrue(g.check(OW, -45, 16, 396, "break", PLANKS).floor());
        assertEquals("protected (base)", g.check(OW, -26, 53, 187, "break", PLAIN).reason());
        assertEquals("protected (base)", g.check(OW, -26, 53, 187, "place", PLAIN).reason());
        assertTrue(g.checkBoxes(OW, -26, 53, 187, "go").allowed(), "walking through a protect box is fine");
        assertEquals("no break in minecraft:the_nether", g.check("minecraft:the_nether", 0, 64, 0, "break", PLAIN).reason());
        assertFalse(g.checkBoxes("minecraft:the_end", 0, 64, 0, "go").allowed());
        assertEquals(GuardCore.BlockInfo.UNKNOWN.known(), false);
        assertEquals("protected blocks not loaded yet", g.check(OW, -45, 16, 396, "break", GuardCore.BlockInfo.UNKNOWN).reason());
    }

    @Test
    void leaseAllowsBreakingOnlyInItsBox() {
        GuardCore g = withPolicy();
        assertEquals("no lease here", g.check(OW, -45, 16, 396, "break", PLAIN).reason());
        String id = lease(g, false);
        assertTrue(g.check(OW, -45, 16, 396, "break", PLAIN).allowed());
        assertEquals("no lease here", g.check(OW, -45, 18, 396, "break", PLAIN).reason());   // above the box
        assertEquals("no place lease here", g.check(OW, -45, 16, 396, "place", PLAIN).reason());  // a dig lease does not allow placing
        g.release(id);
        assertEquals("no lease here", g.check(OW, -45, 16, 396, "break", PLAIN).reason());
        lease(g, true);
        assertTrue(g.check(OW, -45, 16, 396, "place", PLAIN).allowed());
    }

    @Test
    void leasesAreBoundedAndInsideAreas() {
        GuardCore g = withPolicy();
        assertTrue(g.lease("tok", "dig", new Box(null, OW, 300, 60, 0, 301, 61, 1), false, false).startsWith("error: that box is not inside"));
        assertTrue(g.lease("tok", "dig", new Box(null, "minecraft:the_nether", 0, 60, 0, 1, 61, 1), false, false).startsWith("error: no digging in"));
        assertTrue(g.lease("tok", "dig", new Box(null, OW, 0, 0, 0, 20, 20, 20), false, false).contains("the most a lease may cover is 4096"));
        assertTrue(g.lease("tok", "dig", new Box(null, OW, 0, 0, 0, 15, 15, 15), false, false).startsWith("L"), "4096 blocks is fine");
        assertTrue(g.lease("tok", "dig", new Box(null, OW, 0, 0, 0, 4, 4, 4), false, true).contains("the most a lease may cover is 64"));
        assertTrue(g.lease("tok", "dig", new Box(null, OW, 0, 0, 0, 3, 3, 3), false, true).startsWith("L"), "a 64-block force lease is fine");
        assertTrue(g.lease("tok", "dig", new Box(null, OW, 0, Box.ALL_Y_MIN, 0, 1, Box.ALL_Y_MAX, 1), false, false).contains("needs y1 and y2"));
        assertTrue(g.lease("", "dig", new Box(null, OW, 0, 0, 0, 1, 1, 1), false, false).startsWith("error: no token"));
    }

    @Test
    void forceLeaseBreaksBuiltBlocksButNeverBlockEntities() {
        GuardCore g = withPolicy();
        String id = g.lease("tok", "dig force", new Box(null, OW, -45, 16, 395, -44, 17, 398), false, true);
        assertTrue(id.startsWith("L"), id);
        assertTrue(g.check(OW, -45, 16, 396, "break", PLANKS).allowed());
        assertEquals("block entity (a chest, machine, bed...)", g.check(OW, -45, 16, 396, "break", CHEST).reason());
        assertEquals("built block", g.check(OW, -45, 18, 396, "break", PLANKS).reason());  // outside the force box
    }

    @Test
    void logModeRecordsButNeverLowersTheFloor() {
        GuardCore g = withPolicy();
        g.setMode(GuardCore.Mode.LOG);
        GuardCore.Verdict v = g.check(OW, -45, 16, 396, "break", PLAIN);
        assertTrue(v.allowed());
        assertTrue(v.wouldVeto());
        assertEquals("no lease here", v.reason());
        assertFalse(g.check(OW, -45, 16, 396, "break", CHEST).allowed(), "a chest is still refused in log mode");
        assertFalse(g.check(OW, -26, 53, 187, "break", PLAIN).allowed(), "a protect box is still refused in log mode");
        assertFalse(g.check("minecraft:the_nether", 0, 64, 0, "place", PLAIN).allowed(), "the Nether is still refused in log mode");
        assertEquals(1, g.log().wouldVetoes());
        assertEquals(3, g.log().vetoes());
        assertTrue(g.log().recent(10).contains("\"enforced\":false"));
    }

    @Test
    void policyChangeEndsLeasesThatFallOutside() {
        GuardCore g = withPolicy();
        String id = lease(g, false);
        assertEquals(1, g.leases().size());
        String r = g.setPolicy(Policy.parse("{\"areas\":[{\"name\":\"home\",\"x1\":-60,\"z1\":150,\"x2\":0,\"z2\":220}]}"));
        assertTrue(r.contains("ended 1 leases"), r);
        assertTrue(g.leases().isEmpty());
        assertEquals("outside every area", g.check(OW, -45, 16, 396, "break", PLAIN).reason());
        assertFalse(g.checkBoxes(OW, -45, 16, 396, "go").allowed());
        assertTrue(g.checkBoxes(OW, -26, 53, 187, "go").allowed());
        assertNull(g.leases().get(id));
    }

    @Test
    void leasesExpireWithoutAHeartbeatAndReleaseAllByOwner() {
        GuardCore g = withPolicy();
        String id = lease(g, false);
        g.tick(50);
        assertEquals(1, g.leases().size());
        assertEquals(1, g.heartbeat("tok"));
        g.tick(140);
        assertEquals(1, g.leases().size(), "renewed at 50, alive at 140");
        g.tick(151);
        assertTrue(g.leases().isEmpty(), "dead 101 ticks after the last heartbeat");
        assertNull(g.leases().get(id));
        lease(g, false);
        g.lease("other", "x", new Box(null, OW, 0, 10, 0, 1, 11, 1), false, false);
        g.releaseAll("tok");
        assertEquals(1, g.leases().size());
    }

    @Test
    void policyParsingIsStrict() {
        assertThrows(IllegalArgumentException.class, () -> Policy.parse("not json"));
        assertThrows(IllegalArgumentException.class, () -> Policy.parse("{\"areas\":[{\"x1\":1}]}"));
        assertThrows(IllegalArgumentException.class, () -> Policy.parse("{\"areas\":[{\"dim\":\"minecraft:the_nether\",\"x1\":0,\"z1\":0,\"x2\":1,\"z2\":1}]}"));
        Policy p = Policy.parse("{\"areas\":[{\"name\":\"a\",\"x1\":5,\"z1\":5,\"x2\":-5,\"z2\":-5}]}");
        assertEquals(-5, p.areas.get(0).x1);
        assertTrue(p.areas.get(0).allY());
        assertTrue(p.toJson().toString().contains("\"name\":\"a\""));
        assertFalse(p.toJson().toString().contains("y1"), "all-y boxes serialise without y");
    }

    @Test
    void statusIsJson() {
        GuardCore g = withPolicy();
        String s = g.status(3548, true, true).toString();
        assertTrue(s.contains("\"mode\":\"strict\""));
        assertTrue(s.contains("\"floorBlocks\":3548"));
        assertTrue(s.contains("minecraft:the_end"));
    }
}
