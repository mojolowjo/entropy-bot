package io.github.mojolowjo.entropybot.cave;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.io.BotFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** S1: caves never target protect boxes, the policy read fail-closed, the mine notes' write back-off. */
class S1CaveTest {
    static final String OW = "minecraft:overworld";
    /** The live base box: -40 170 to -10 200, y -64..80. */
    static final List<Box> BASE = List.of(new Box("base", OW, -40, -64, 170, -10, 80, 200));

    @Test
    void offLimitsIsTheBoxAndTheMineVerbsMargin() {
        assertEquals(0, CaveRules.offLimits(BASE, OW, -30, 52, 184).gap(), "the live ore at -30 52 184 lies inside");
        assertNotNull(CaveRules.offLimits(BASE, OW, -10 + 16, 52, 184), "16 from the box: still too near");
        assertNull(CaveRules.offLimits(BASE, OW, -10 + 17, 52, 184));
        assertNull(CaveRules.offLimits(BASE, "minecraft:the_nether", -30, 52, 184));
        assertEquals("cave_2 lies inside the protected base", CaveRules.protectedCaveText("cave_2", CaveRules.offLimits(BASE, OW, -29, 54, 187)));
        assertEquals("cave_3 lies 5 blocks from the protected base (I keep 16 away)",
                CaveRules.protectedCaveText("cave_3", CaveRules.offLimits(BASE, OW, -5, 54, 187)));
    }

    @Test
    void theSearchNeverOffersAnOreOrADarkSpotInTheBase() {
        CaveTest.FakeCave w = new CaveTest.FakeCave();
        w.air(-35, 52, 184, 40, 53, 184);                  // a tunnel from inside the base out east
        w.set(-30, 54, 184, "minecraft:iron_ore");         // in the base's ceiling (the live case)
        w.set(30, 54, 184, "minecraft:iron_ore");          // well outside
        CaveSearch.OffLimits off = (x, y, z) -> CaveRules.offLimits(BASE, OW, x, y, z) != null;
        var r = CaveSearch.search(w, -33, 52, 184, new HashSet<>(), id -> id.equals("minecraft:iron_ore"), -33, 52, 184, 160, off);
        assertEquals(1, r.ores().size());
        assertEquals(30, r.ores().get(0).x(), "only the ore outside");
        assertNotNull(r.frontier());
        assertTrue(r.frontier().x() > -10 + 16, "the dark spot lies past the margin: " + r.frontier());
        // without the rule the base's ore comes first (what happened live)
        var old = CaveSearch.search(w, -33, 52, 184, new HashSet<>(), id -> id.equals("minecraft:iron_ore"), -33, 52, 184, 160);
        assertEquals(-30, old.ores().get(0).x());
        // the vein pick passes it over too (the refused predicate)
        Map<String, Integer> tried = new HashMap<>();
        var vein = CaveRules.pickVein(old.ores(), tried, o -> off.test(o.x(), o.y(), o.z()), o -> true, new LinkedHashSet<>());
        assertTrue(vein.isEmpty() || vein.get(0).x() == 30, "never the base's ore: " + vein);
    }

    @Test
    void aCaveWhollyInTheBaseHasNothingToOffer() {
        CaveTest.FakeCave w = new CaveTest.FakeCave();
        w.air(-35, 52, 180, -15, 53, 190);                 // a dark room inside the base only
        w.set(-30, 54, 184, "minecraft:iron_ore");
        CaveSearch.OffLimits off = (x, y, z) -> CaveRules.offLimits(BASE, OW, x, y, z) != null;
        var r = CaveSearch.search(w, -29, 52, 187, new HashSet<>(), id -> id.equals("minecraft:iron_ore"), -29, 54, 187, 160, off);
        assertTrue(r.ores().isEmpty());
        assertNull(r.frontier(), "no target: the job says \"no cave here (cave_2 lies inside the protected base)\" and moves");
    }

    @Test
    void thePolicyIsReadFailClosed() {
        JsonObject ok = MineRules.parsePolicy("{\"strict\":true,\"areas\":[{\"name\":\"map\",\"x1\":-272,\"z1\":-64,\"x2\":223,\"z2\":431}],"
                + "\"protect\":[{\"name\":\"base\",\"x1\":-40,\"z1\":170,\"x2\":-10,\"z2\":200,\"y1\":-64,\"y2\":80}]}");
        assertEquals(1, MineRules.policyBoxes(ok, "protect", OW).size());
        assertEquals(0, MineRules.policyBoxes(new JsonObject(), "protect", OW).size(), "no protect boxes set: none");
        assertThrows(MineRules.BadPolicy.class, () -> MineRules.parsePolicy("{not json"));
        assertThrows(MineRules.BadPolicy.class, () -> MineRules.parsePolicy("[1,2]"));
        JsonObject bad = MineRules.parsePolicy("{\"protect\":[{\"name\":\"base\",\"x1\":-40,\"z1\":170,\"x2\":-10}]}");
        MineRules.BadPolicy e = assertThrows(MineRules.BadPolicy.class, () -> MineRules.policyBoxes(bad, "protect", OW));
        assertTrue(e.getMessage().contains("box 1 \"base\""), e.getMessage());
        assertTrue(MineRules.badPolicyText(e).startsWith("I won't mine while box 1 \"base\" in the guard policy's protect list is malformed"));
        assertThrows(MineRules.BadPolicy.class, () -> MineRules.policyBoxes(MineRules.parsePolicy("{\"protect\":5}"), "protect", OW));
    }

    @Test
    void aFailedWriteBacksOff(@TempDir Path dir) throws Exception {
        BotFiles f = new BotFiles(dir);
        MineNotes n = new MineNotes();
        n.load(f, null);
        // ores.json is a folder with something in it: every write fails
        Files.createDirectories(dir.resolve(MineNotes.ORES));
        Files.writeString(dir.resolve(MineNotes.ORES).resolve("x"), "x");
        n.flushIfDue(10);
        n.note(1, 2, 3, "iron_ore", OW, 1);
        n.flushIfDue(200);                                 // due: tried, failed
        long next = n.oresRetryAt();
        assertEquals(200 + MineNotes.FLUSH_AFTER, next, "the next try waits");
        assertNotNull(n.takeWriteError());
        n.flushIfDue(201);
        assertNull(n.takeWriteError(), "not tried again at once");
        n.flushIfDue(next);                                // tried again: fails again, waits twice as long
        assertEquals(next + 2 * MineNotes.FLUSH_AFTER, n.oresRetryAt());
        assertEquals(MineNotes.RETRY_MAX, MineNotes.backoff(40));
        // the folder goes: the next due try writes
        Files.delete(dir.resolve(MineNotes.ORES).resolve("x"));
        Files.delete(dir.resolve(MineNotes.ORES));
        n.flushIfDue(n.oresRetryAt());
        assertEquals(0, n.oresRetryAt());
        assertTrue(Files.isRegularFile(dir.resolve(MineNotes.ORES)));
    }
}
