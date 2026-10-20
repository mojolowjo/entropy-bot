package io.github.mojolowjo.entropybot.guard;

import org.junit.jupiter.api.Test;

import java.util.List;

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
    void aBoxTouchingASafeAreaIsFound() {
        Policy p = Policy.parse(POLICY);
        assertEquals("house", p.safeTouching(new Box("dig", OW, 69, 60, 5, 72, 60, 6)).name);
        assertNull(p.safeTouching(new Box("dig", OW, 70, 60, 5, 72, 60, 6)));
        assertNull(p.safeTouching(new Box("dig", "minecraft:the_nether", 65, 60, 5, 66, 60, 6)));
    }

    @Test
    void safeTouchingFindsTheOverlap() {
        String policyJson = "{\"areas\":[],\"protect\":[{\"name\":\"safe1\",\"x1\":0,\"y1\":60,\"z1\":0,\"x2\":9,\"y2\":70,\"z2\":9}]}";
        Policy p = Policy.parse(policyJson);
        Box digOverlap = new Box("dig", OW, 5, 62, 5, 15, 64, 15);
        Box digMiss = new Box("dig", OW, 20, 62, 20, 25, 64, 25);
        assertEquals("safe1", p.safeTouching(digOverlap).name);
        assertNull(p.safeTouching(digMiss));
    }

    @Test
    void safeTouchingRespectsY() {
        String policyJson = "{\"areas\":[],\"protect\":[{\"name\":\"safe1\",\"x1\":0,\"y1\":60,\"z1\":0,\"x2\":9,\"y2\":70,\"z2\":9}]}";
        Policy p = Policy.parse(policyJson);
        Box digAbove = new Box("dig", OW, 0, 80, 0, 9, 85, 9);
        assertNull(p.safeTouching(digAbove));
    }

    @Test
    void safeTouchingReportsTheFirst() {
        String policyJson = "{\"areas\":[],\"protect\":["
                + "{\"name\":\"safe1\",\"x1\":0,\"y1\":60,\"z1\":0,\"x2\":9,\"y2\":70,\"z2\":9},"
                + "{\"name\":\"safe2\",\"x1\":5,\"y1\":60,\"z1\":5,\"x2\":14,\"y2\":70,\"z2\":14}]}";
        Policy p = Policy.parse(policyJson);
        Box digOverlapBoth = new Box("dig", OW, 5, 62, 5, 9, 64, 9);
        assertEquals("safe1", p.safeTouching(digOverlapBoth).name);
    }

    @Test
    void theTable() {
        for (AreaType t : AreaType.values()) {
            boolean prot = t == AreaType.KEEP_OUT || t == AreaType.NO_BREAK;
            for (AreaTypeRules.Walker w : AreaTypeRules.Walker.values()) assertEquals(t != AreaType.KEEP_OUT, AreaTypeRules.walk(t, w), "walk in " + t + " as " + w);
            assertEquals(!prot, AreaTypeRules.dig(t), "dig in " + t);
            assertEquals(t == AreaType.DESTROY, AreaTypeRules.breakBuilt(t, true), "built in " + t);
            assertFalse(AreaTypeRules.breakBuilt(t, false), "built blocks need the owner's dig <area>: " + t);
            assertEquals(!prot && t != AreaType.DESTROY, AreaTypeRules.restore(t), "restore in " + t);
            assertEquals(!prot, AreaTypeRules.mayStartJob(t), "jobs in " + t);
            assertEquals(prot, AreaTypeRules.protects(t), "protect list: " + t);
        }
        // AREAS: no area = the open world: every walker walks, digs with a lease, jobs start
        for (AreaTypeRules.Walker w : AreaTypeRules.Walker.values()) assertTrue(AreaTypeRules.walk(null, w));
        assertTrue(AreaTypeRules.dig(null));
        assertFalse(AreaTypeRules.breakBuilt(null, true));
        assertTrue(AreaTypeRules.restore(null));
        assertTrue(AreaTypeRules.mayStartJob(null));
    }

    @Test
    void wordsOldAndNew() {
        assertEquals(AreaType.WORK, AreaType.of("neutral"));
        assertEquals(AreaType.NO_BREAK, AreaType.of("safe"));
        assertEquals(AreaType.BASE, AreaType.of("main"));
        assertEquals(AreaType.KEEP_OUT, AreaType.of("keep-out"));
        assertEquals(AreaType.KEEP_OUT, AreaType.of("keepout"));
        assertEquals(AreaType.NO_BREAK, AreaType.of("no_break"));
        assertEquals(AreaType.STORAGE, AreaType.of("Storage"));
        assertEquals(AreaType.DANGER, AreaType.of("danger"));
        assertNull(AreaType.of("outside"));
        assertEquals("no-break", AreaType.oldWord("safe"));
        assertNull(AreaType.oldWord("base"));
        assertEquals("keep-out", AreaType.KEEP_OUT.word());
        assertTrue(AreaType.KEEP_OUT.hard && AreaType.NO_BREAK.hard && AreaType.DESTROY.hard);
        assertFalse(AreaType.BASE.hard || AreaType.WORK.hard || AreaType.STORAGE.hard || AreaType.DANGER.hard);
    }

    @Test
    void stackingOrderTheHardestWins() {
        // keep-out > no-break > destroy > base > work > storage > danger
        AreaType[] order = {AreaType.KEEP_OUT, AreaType.NO_BREAK, AreaType.DESTROY, AreaType.BASE, AreaType.WORK, AreaType.STORAGE, AreaType.DANGER};
        for (int i = 0; i < order.length; i++) {
            assertEquals(i, order[i].rank());
            for (int j = i; j < order.length; j++) assertEquals(order[i], AreaType.harder(order[i], order[j]));
        }
        assertEquals(AreaType.DANGER, AreaType.harder(null, AreaType.DANGER));
        assertNull(AreaType.harder(null, null));
        Policy p = Policy.parse("{\"areas\":["
                + "{\"name\":\"camp\",\"type\":\"work\",\"x1\":0,\"z1\":0,\"x2\":99,\"z2\":99},"
                + "{\"name\":\"home\",\"type\":\"base\",\"x1\":10,\"z1\":10,\"x2\":20,\"z2\":20},"
                + "{\"name\":\"swamp\",\"type\":\"danger\",\"x1\":50,\"z1\":50,\"x2\":60,\"z2\":60},"
                + "{\"name\":\"pit\",\"type\":\"keep-out\",\"x1\":15,\"z1\":15,\"x2\":16,\"z2\":16}]}");
        assertEquals(AreaType.WORK, p.typeAt(OW, 5, 64, 5));
        assertEquals(AreaType.BASE, p.typeAt(OW, 12, 64, 12), "base over work");
        assertEquals(AreaType.WORK, p.typeAt(OW, 55, 64, 55), "work over danger");
        assertEquals(AreaType.KEEP_OUT, p.typeAt(OW, 15, 64, 15), "keep-out over everything");
        assertEquals(1, p.keepOut.size());
        assertEquals("pit", p.keepOutAt(OW, 16, 64, 16).name);
        assertNull(p.keepOutAt(OW, 17, 64, 17));
        assertEquals(List.of("pit", "home", "camp"), p.boxesAt(OW, 15, 64, 15).stream().map(b -> b.name).toList());
        assertEquals(15, p.nearestBase(OW, 100, 100)[0]);
        assertNull(p.nearestBase("minecraft:the_nether", 0, 0));
    }

    @Test
    void theNoteRoundTrips() {
        Policy p = Policy.parse("{\"areas\":[{\"name\":\"tom\",\"type\":\"no-break\",\"note\":\"tom's house\",\"x1\":0,\"z1\":0,\"x2\":9,\"z2\":9}]}");
        assertEquals("tom's house", p.protect.get(0).note);
        Policy again = Policy.parse(p.toJson().toString());
        assertEquals("tom's house", again.protect.get(0).note);
        assertNull(again.protect.get(0).withNote("  ").note);
        assertFalse(Policy.parse("{\"areas\":[{\"name\":\"a\",\"x1\":0,\"z1\":0,\"x2\":9,\"z2\":9}]}").areas.get(0).toJson().has("note"));
    }

    @Test
    void oldWordsAreWrittenBackAsTheNewOnes() {
        Policy p = Policy.parse("{\"areas\":[{\"name\":\"a\",\"type\":\"neutral\",\"x1\":0,\"z1\":0,\"x2\":9,\"z2\":9},"
                + "{\"name\":\"b\",\"type\":\"main\",\"x1\":20,\"z1\":0,\"x2\":29,\"z2\":9},"
                + "{\"name\":\"c\",\"type\":\"safe\",\"x1\":40,\"z1\":0,\"x2\":49,\"z2\":9}],"
                + "\"protect\":[{\"name\":\"d\",\"x1\":60,\"y1\":0,\"z1\":0,\"x2\":69,\"y2\":9,\"z2\":9}]}");
        String json = p.toJson().toString();
        assertFalse(json.contains("neutral") || json.contains("main") || json.contains("safe"), json);
        assertTrue(json.contains("\"type\":\"base\"") && json.contains("\"type\":\"no-break\""), json);
        assertEquals(2, p.protect.size());
        assertEquals(AreaType.NO_BREAK, p.protect.get(1).type, "an untyped protect box is no-break");
    }

    @Test
    void colours() {
        assertEquals("white", AreaType.WORK.colour);
        assertEquals("dark red", AreaType.DESTROY.colour);
        assertEquals("blue", AreaType.BASE.colour);
        assertEquals("green", AreaType.NO_BREAK.colour);
        assertEquals(AreaType.DESTROY, AreaType.of("Destroy"));
        assertNull(AreaType.of("red"));
        assertEquals(AreaType.WORK, AreaType.orWork(null));
    }

    @Test
    void theJsonKeepsTheType() {
        Policy p = Policy.parse(POLICY);
        assertEquals(3, p.areas.size());
        assertEquals(1, p.protect.size(), "a safe area is a protect box");
        assertEquals(AreaType.NO_BREAK, p.protect.get(0).type);
        assertEquals(AreaType.DESTROY, p.typeAt(OW, 25, 65, 5));
        assertEquals(AreaType.NO_BREAK, p.typeAt(OW, 65, 65, 5));
        assertNull(p.typeAt(OW, 100, 65, 5));
        Policy again = Policy.parse(p.toJson().toString());
        assertEquals(AreaType.BASE, again.areaAt(OW, 45, 65, 5).type);
        assertEquals(AreaType.NO_BREAK, again.protect.get(0).type);
        assertFalse(p.areas.get(0).toJson().has("type"), "neutral writes no type");
        // an old v1 file: protect boxes become safe
        Policy v1 = Policy.parse("{\"areas\":[{\"name\":\"a\",\"x1\":0,\"z1\":0,\"x2\":5,\"z2\":5}],\"protect\":[{\"name\":\"p\",\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":1,\"y2\":1,\"z2\":1}]}");
        assertEquals(AreaType.WORK, v1.areas.get(0).type);
        assertEquals(AreaType.NO_BREAK, v1.protect.get(0).type);
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
        assertEquals("in house (no-break)", v.reason());
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
    void everyWalkerWalksOutsideOnlyKeepOutRefuses() {
        // AREAS: the world is open; the fence only refuses a keep-out box and the floor (a portal)
        String out = "would refuse: outside every area";
        for (AreaTypeRules.Walker w : AreaTypeRules.Walker.values()) {
            assertNull(io.github.mojolowjo.entropybot.commands.FenceRules.goalAllowed(out, true, w), w.name());
            assertNull(io.github.mojolowjo.entropybot.commands.FenceRules.goalAllowed("would refuse: no areas set", true, w), w.name());
            assertEquals("in pit (keep-out)", io.github.mojolowjo.entropybot.commands.FenceRules.goalAllowed("would refuse: in pit (keep-out)", true, w), w.name());
            assertNotNull(io.github.mojolowjo.entropybot.commands.FenceRules.goalAllowed("would refuse: next to a nether_portal (I stay out)", true, w),
                    "the floor holds for everyone");
        }
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
