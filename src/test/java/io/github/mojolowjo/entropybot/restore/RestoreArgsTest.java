package io.github.mojolowjo.entropybot.restore;

import io.github.mojolowjo.entropybot.clear.LeaseSet;
import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.guard.Policy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P1: the verb's parsing, the build spotter, and the guard's restore lease. */
class RestoreArgsTest {
    static final String OW = "minecraft:overworld";

    @Test
    void parsing() {
        assertEquals(RestoreArgs.Kind.STATUS, RestoreArgs.parse("").kind());
        assertEquals(RestoreArgs.Kind.STATUS, RestoreArgs.parse(" Status ").kind());
        RestoreArgs.Cmd now = RestoreArgs.parse("now");
        assertEquals(RestoreArgs.Kind.NOW, now.kind());
        assertEquals(RestorePlan.NOW_DEFAULT, now.n());
        assertEquals(12, RestoreArgs.parse("now 12").n());
        assertEquals(RestoreArgs.Kind.USAGE, RestoreArgs.parse("now 500").kind());
        assertTrue(RestoreArgs.parse("now 500").error().startsWith("error: the radius"));
        assertEquals(RestoreArgs.Kind.FORGET, RestoreArgs.parse("forget 3").kind());
        assertEquals(3, RestoreArgs.parse("forget 3").n());
        assertEquals(RestoreArgs.Kind.USAGE, RestoreArgs.parse("forget all").kind(), "all needs confirm");
        assertEquals(RestoreArgs.Kind.FORGET_ALL, RestoreArgs.parse("forget all confirm").kind());
        assertEquals(RestoreArgs.Kind.USAGE, RestoreArgs.parse("forget 0").kind());
        RestoreArgs.Cmd ig = RestoreArgs.parse("ignore 10 -64 -20");
        assertArrayEquals(new int[]{10, -64, -20}, ig.pos());
        assertEquals("manual", RestoreArgs.parse("mode manual").mode());
        assertEquals(RestoreArgs.Kind.USAGE, RestoreArgs.parse("mode sometimes").kind());
        assertEquals(RestoreArgs.USAGE, RestoreArgs.parse("banana").error());
        assertTrue(RestoreArgs.isJob("now 8"));
        assertFalse(RestoreArgs.isJob("status"));
    }

    @Test
    void aClusterOfBuiltBlocksBecomesASuggestedBox() {
        List<int[]> built = new ArrayList<>();
        for (int i = 0; i < 6; i++) built.add(new int[]{100 + i, 64, -30});      // a wall of 6 planks
        built.add(new int[]{90, 64, -30});                                       // a stray block far off
        BuildSpotter.Hint h = BuildSpotter.spot(built);
        assertNotNull(h);
        assertEquals(6, h.count());
        assertArrayEquals(new int[]{98, 62, -32, 107, 66, -28}, h.box());
        assertEquals("build_102_m30", h.name());
        assertEquals("looks like a build at 102 64 -30 - protect it? (protect build_102_m30 98 62 -32 107 66 -28)", h.whisper());
        assertTrue(h.name().matches("^[a-z0-9_-]{1,24}$"), "a name protect accepts");
        assertNull(BuildSpotter.spot(built.subList(0, 5)), "five is not a build");
        assertTrue(BuildSpotter.ownKind("minecraft:wall_torch"));
        assertFalse(BuildSpotter.ownKind("minecraft:oak_planks"));
    }

    static GuardCore guard() {
        GuardCore g = new GuardCore();
        g.setMode(GuardCore.Mode.STRICT);
        g.tick(1);
        g.setPolicy(Policy.parse("{\"areas\":[{\"name\":\"a\",\"x1\":0,\"z1\":0,\"x2\":100,\"z2\":100}],"
                + "\"protect\":[{\"name\":\"base\",\"x1\":50,\"y1\":0,\"z1\":50,\"x2\":60,\"y2\":100,\"z2\":60}]}"));
        return g;
    }

    @Test
    void aRestoreLeaseIsAllowedInStrictModeButNeverInAProtectBox() {
        GuardCore g = guard();
        GuardCore.BlockInfo air = GuardCore.BlockInfo.placing(true), stone = GuardCore.BlockInfo.placing(false);
        // inside an area, no other lease: refused until the restore lease
        assertFalse(g.check(OW, 10, 40, 10, "place", air).allowed());
        String id = g.restoreLease("tok", "restore", new Box(null, OW, 10, 40, 10, 10, 40, 10));
        assertFalse(id.startsWith("error"), id);
        assertTrue(g.check(OW, 10, 40, 10, "place", air).allowed());
        assertFalse(g.check(OW, 10, 40, 10, "break", GuardCore.BlockInfo.PLAIN).allowed(), "a restore lease never breaks");
        // just outside the areas: still allowed, but only into air or water
        String out = g.restoreLease("tok", "restore", new Box(null, OW, 150, 40, 10, 150, 40, 10));
        assertFalse(out.startsWith("error"), out);
        assertTrue(g.check(OW, 150, 40, 10, "place", air).allowed());
        assertFalse(g.check(OW, 150, 40, 10, "place", stone).allowed(), "it only fills air or water");
        // never a protect box, never the nether, one block only
        assertTrue(g.restoreLease("tok", "restore", new Box(null, OW, 55, 40, 55, 55, 40, 55)).startsWith("error: 55 40 55 is in a protect box (base)"));
        assertTrue(g.restoreLease("tok", "restore", new Box(null, "minecraft:the_nether", 1, 1, 1, 1, 1, 1)).startsWith("error"));
        assertTrue(g.restoreLease("tok", "restore", new Box(null, OW, 1, 1, 1, 2, 1, 1)).startsWith("error"));
    }

    @Test
    void theLeaseSetTakesItOncePerCell() {
        GuardCore g = guard();
        LeaseSet ls = new LeaseSet(g, "tok", OW, null);
        assertNull(ls.restoreLease(10, 40, 10, "putting back"));
        assertNull(ls.restoreLease(10, 40, 10, "putting back"));
        assertEquals(1, ls.ids.size());
        assertEquals("error: the guard refused: 55 40 55 is in a protect box (base)", ls.restoreLease(55, 40, 55, "putting back"));
        g.setMode(GuardCore.Mode.LOG);
        assertNotNull(ls.restoreLease(56, 40, 56, "putting back"), "a protect box refuses in log mode too");
        ls.releaseAll();
        assertTrue(g.leases().isEmpty());
    }
}
