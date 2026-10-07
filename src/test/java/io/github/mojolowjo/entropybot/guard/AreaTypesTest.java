package io.github.mojolowjo.entropybot.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** V1a (0.22.0): the guard per area type (BRAIN_PLAN 1.2), the destroy lease, the colours. */
class AreaTypesTest {
    static final String OW = "minecraft:overworld";
    static final GuardCore.BlockInfo PLAIN = GuardCore.BlockInfo.PLAIN;
    static final GuardCore.BlockInfo CHEST = GuardCore.BlockInfo.of(true, true, false);
    static final GuardCore.BlockInfo PLANKS = GuardCore.BlockInfo.of(true, false, true);

    /** One area of each type side by side, x 0..9 / 20..29 / 40..49 / 60..69, z 0..9, y 60..70. */
    static final String POLICY = "{\"version\":2,\"areas\":["
            + "{\"name\":\"plain\",\"x1\":0,\"z1\":0,\"x2\":9,\"z2\":9,\"y1\":60,\"y2\":70},"
            + "{\"name\":\"pit\",\"type\":\"destroy\",\"x1\":20,\"z1\":0,\"x2\":29,\"z2\":9,\"y1\":60,\"y2\":70},"
            + "{\"name\":\"base\",\"type\":\"main\",\"x1\":40,\"z1\":0,\"x2\":49,\"z2\":9,\"y1\":60,\"y2\":70},"
            + "{\"name\":\"house\",\"type\":\"safe\",\"x1\":60,\"z1\":0,\"x2\":69,\"z2\":9,\"y1\":60,\"y2\":70}],\"protect\":[]}";

    static GuardCore guard() {
        GuardCore g = new GuardCore();
        g.setMode(GuardCore.Mode.STRICT);
        g.tick(1);
        assertTrue(g.setPolicy(Policy.parse(POLICY)).startsWith("ok: 3 areas, 1 protect boxes"));
        return g;
    }

    @Test
    void theTable() {
        for (AreaType t : AreaType.values()) {
            assertTrue(AreaTypeRules.walk(t, AreaTypeRules.Walker.OTHER), "walk in " + t);
            assertEquals(t != AreaType.SAFE, AreaTypeRules.dig(t), "dig in " + t);
            assertEquals(t == AreaType.DESTROY, AreaTypeRules.breakBuilt(t, true), "built in " + t);
            assertFalse(AreaTypeRules.breakBuilt(t, false), "built blocks need the owner's dig <area>: " + t);
            assertEquals(t == AreaType.NEUTRAL || t == AreaType.MAIN, AreaTypeRules.restore(t), "restore in " + t);
            assertEquals(t != AreaType.SAFE, AreaTypeRules.mayStartJob(t), "jobs in " + t);
        }
        // outside every area: only the owner's own order, explore and find walk; nothing digs
        assertTrue(AreaTypeRules.walk(null, AreaTypeRules.Walker.OWNER_ORDER));
        assertTrue(AreaTypeRules.walk(null, AreaTypeRules.Walker.EXPLORE));
        assertTrue(AreaTypeRules.walk(null, AreaTypeRules.Walker.FIND));
        assertFalse(AreaTypeRules.walk(null, AreaTypeRules.Walker.OTHER));
        assertFalse(AreaTypeRules.dig(null));
        assertFalse(AreaTypeRules.breakBuilt(null, true));
        assertTrue(AreaTypeRules.restore(null));
        assertFalse(AreaTypeRules.mayStartJob(null));
    }

    @Test
    void colours() {
        assertEquals("white", AreaType.NEUTRAL.colour);
        assertEquals("red", AreaType.DESTROY.colour);
        assertEquals("blue", AreaType.MAIN.colour);
        assertEquals("green", AreaType.SAFE.colour);
        assertEquals(AreaType.DESTROY, AreaType.of("Destroy"));
        assertNull(AreaType.of("red"));
        assertEquals(AreaType.NEUTRAL, AreaType.orNeutral(null));
    }

    @Test
    void theJsonKeepsTheType() {
        Policy p = Policy.parse(POLICY);
        assertEquals(3, p.areas.size());
        assertEquals(1, p.protect.size(), "a safe area is a protect box");
        assertEquals(AreaType.SAFE, p.protect.get(0).type);
        assertEquals(AreaType.DESTROY, p.typeAt(OW, 25, 65, 5));
        assertEquals(AreaType.SAFE, p.typeAt(OW, 65, 65, 5));
        assertNull(p.typeAt(OW, 100, 65, 5));
        Policy again = Policy.parse(p.toJson().toString());
        assertEquals(AreaType.MAIN, again.areaAt(OW, 45, 65, 5).type);
        assertEquals(AreaType.SAFE, again.protect.get(0).type);
        assertFalse(p.areas.get(0).toJson().has("type"), "neutral writes no type");
        // an old v1 file: protect boxes become safe
        Policy v1 = Policy.parse("{\"areas\":[{\"name\":\"a\",\"x1\":0,\"z1\":0,\"x2\":5,\"z2\":5}],\"protect\":[{\"name\":\"p\",\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":1,\"y2\":1,\"z2\":1}]}");
        assertEquals(AreaType.NEUTRAL, v1.areas.get(0).type);
        assertEquals(AreaType.SAFE, v1.protect.get(0).type);
    }

    @Test
    void walkBreakPlacePerType() {
        GuardCore g = guard();
        // walking: every type, safe too
        for (int x : new int[]{5, 25, 45, 65}) assertTrue(g.checkBoxes(OW, x, 65, 5, "go").allowed(), "go at " + x);
        assertFalse(g.checkBoxes(OW, 100, 65, 5, "go").allowed(), "outside every area: the fence (the owner's goto passes in FenceRules)");
        // safe: never break or place, even with a lease around it
        GuardCore.Verdict v = g.check(OW, 65, 65, 5, "break", PLAIN);
        assertFalse(v.allowed());
        assertTrue(v.floor());
        assertEquals("in house (safe)", v.reason());
        assertFalse(g.check(OW, 65, 65, 5, "place", PLAIN).allowed());
        // neutral, destroy, main: natural blocks with a lease
        for (int x : new int[]{0, 20, 40}) {
            String id = g.lease("tok", "dig", new Box(null, OW, x, 60, 0, x + 9, 70, 9), true, false);
            assertTrue(id.startsWith("L"), id);
            assertTrue(g.check(OW, x + 5, 65, 5, "break", PLAIN).allowed(), "natural at " + x);
            assertFalse(g.check(OW, x + 5, 65, 5, "break", PLANKS).allowed(), "built blocks need a destroy lease at " + x);
            assertFalse(g.check(OW, x + 5, 65, 5, "break", CHEST).allowed(), "containers never at " + x);
        }
    }

    @Test
    void theOwnersOwnGotoWalksOutside() {
        var C = io.github.mojolowjo.entropybot.commands.FenceRules.class;
        String out = "would refuse: outside every area";
        assertNotNull(io.github.mojolowjo.entropybot.commands.FenceRules.goalAllowed(out, true, AreaTypeRules.Walker.OTHER));
        assertNull(io.github.mojolowjo.entropybot.commands.FenceRules.goalAllowed(out, true, AreaTypeRules.Walker.OWNER_ORDER));
        assertNull(io.github.mojolowjo.entropybot.commands.FenceRules.goalAllowed("would refuse: no areas set", true, AreaTypeRules.Walker.OWNER_ORDER));
        assertNotNull(io.github.mojolowjo.entropybot.commands.FenceRules.goalAllowed("would refuse: next to a nether_portal (I stay out)", true, AreaTypeRules.Walker.OWNER_ORDER),
                "the floor holds for the owner too");
        assertNotNull(C);
    }

    @Test
    void theDestroyLease() {
        GuardCore g = guard();
        Box inPit = new Box("dig pit", OW, 20, 60, 0, 29, 70, 9);
        assertTrue(g.destroyLease("tok", "dig pit", inPit, "pit", false).startsWith("error: built blocks go only with the owner's own dig"));
        assertTrue(g.destroyLease("tok", "dig plain", new Box(null, OW, 0, 60, 0, 9, 70, 9), "plain", true).startsWith("error: that box is not inside the destroy area"));
        assertTrue(g.destroyLease("tok", "dig base", new Box(null, OW, 40, 60, 0, 49, 70, 9), "base", true).startsWith("error"), "main is never destroyed");
        assertTrue(g.destroyLease("tok", "dig", new Box(null, OW, 18, 60, 0, 29, 70, 9), "pit", true).startsWith("error"), "it must lie inside the area");
        assertTrue(g.log().wouldVetoes() + g.log().vetoes() >= 4, "refusals are logged");
        String id = g.destroyLease("tok", "dig pit", inPit, "pit", true);
        assertTrue(id.startsWith("L"), id);
        assertTrue(g.leases().get(id).destroy);
        assertEquals("pit", g.leases().get(id).toJson().get("destroy").getAsString());
        assertTrue(g.check(OW, 25, 65, 5, "break", PLANKS).allowed(), "built blocks go under a destroy lease");
        assertTrue(g.check(OW, 25, 65, 5, "break", PLAIN).allowed());
        assertFalse(g.check(OW, 25, 65, 5, "break", CHEST).allowed(), "never a container");
        assertFalse(g.check(OW, 25, 65, 5, "place", PLAIN).allowed(), "a destroy lease only breaks");
        // the area changes type: a new policy keeps the lease box but a fresh destroy lease is refused
        g.release(id);
        g.setPolicy(Policy.parse(POLICY.replace("\"destroy\"", "\"neutral\"")));
        assertTrue(g.destroyLease("tok", "dig pit", inPit, "pit", true).startsWith("error"));
    }
}
