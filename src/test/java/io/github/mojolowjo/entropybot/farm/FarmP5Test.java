package io.github.mojolowjo.entropybot.farm;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.gather.GatherSources;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P5: farm settings (mode detection), the plant layout, seeds, cooking and the vanilla round. */
class FarmP5Test {

    // ---- settings ----

    @Test
    void autoModeFollowsTheModList() {
        FarmSettings auto = FarmSettings.DEFAULT;
        // {hwe, squat} -> {modded, twerk}
        boolean[][] table = {{true, true, true, true}, {true, false, true, false}, {false, true, false, false}, {false, false, false, false}};
        for (boolean[] row : table) {
            FarmSettings.Effective e = auto.resolve(row[0], row[1]);
            assertEquals(row[2], e.modded(), "hwe " + row[0] + " squat " + row[1]);
            assertEquals(row[3], e.twerk(), "hwe " + row[0] + " squat " + row[1]);
            assertTrue(e.modeWhy().startsWith("auto: Harvest with Ease is " + (row[0] ? "loaded" : "not loaded")), e.modeWhy());
        }
        assertEquals("auto: vanilla mode doesn't crouch", auto.resolve(false, true).growWhy());
    }

    @Test
    void setModesWinAndWarn() {
        FarmSettings.Effective e = new FarmSettings("vanilla", "auto").resolve(true, true);
        assertFalse(e.modded());
        assertFalse(e.twerk(), "auto grow follows the vanilla mode");
        e = new FarmSettings("modded", "off").resolve(false, true);
        assertTrue(e.modded());
        assertFalse(e.twerk());
        assertTrue(e.modeWhy().contains("Harvest with Ease is not loaded"), e.modeWhy());
        e = new FarmSettings("vanilla", "on").resolve(true, false);
        assertTrue(e.twerk());
        assertTrue(e.growWhy().contains("Squat Grow is not loaded"));
    }

    @Test
    void settingsParseAndSave() {
        FarmSettings s = FarmSettings.DEFAULT;
        FarmSettings.Change c = s.command("mode vanilla");
        assertEquals(new FarmSettings("vanilla", "auto"), c.set());
        assertEquals("ok: farm mode is now vanilla", c.reply());
        c = c.set().command("grow twerk off");
        assertEquals(new FarmSettings("vanilla", "off"), c.set());
        assertEquals(new FarmSettings("auto", "on"), s.command("grow on").set());
        assertNull(s.command("mode sideways").set());
        assertEquals(FarmSettings.USAGE, s.command("mode sideways").reply());
        assertNull(s.command("here").reply(), "not a settings command");
        assertNull(s.command("").reply());
        JsonObject o = new FarmSettings("modded", "off").toJson();
        assertEquals(new FarmSettings("modded", "off"), FarmSettings.fromJson(o));
        assertEquals(FarmSettings.DEFAULT, FarmSettings.fromJson(null));
        o.addProperty("mode", "weird");
        assertEquals("auto", FarmSettings.fromJson(o).mode());
        String st = FarmSettings.DEFAULT.statusText(FarmSettings.DEFAULT.resolve(false, false));
        assertTrue(st.startsWith("farm: mode vanilla"), st);
        assertTrue(st.contains("Harvest with Ease is not loaded"), st);
    }

    @Test
    void farmCommandSettingsAreInstant() {
        for (String s : List.of("mode vanilla", "grow twerk off", "status", "here", "compact off")) assertTrue(FarmCommand.instant(s), s);
        assertFalse(FarmCommand.instant("plant wheat here 4"));
        assertFalse(FarmCommand.instant(""));
    }

    // ---- farm plant: parsing, seeds ----

    @Test
    void plantArgs() {
        FarmPlant.Args a = FarmPlant.parse("wheat here 4");
        assertEquals("wheat", a.crop());
        assertTrue(a.here());
        assertEquals(4, a.r());
        a = FarmPlant.parse("carrot 10 -5 2 3");
        assertArrayEquals(new int[]{2, -5, 10, 3}, a.box(), "sorted corners");
        assertNull(FarmPlant.parse("potato").box());
        assertEquals(FarmPlant.USAGE, FarmPlant.parse("wheat 1 2 3").error());
        assertEquals(FarmPlant.USAGE, FarmPlant.parse("").error());
        assertNotNull(FarmPlant.parse("wheat here 40").error());
        assertNotNull(FarmPlant.parse("wheat 0 0 40 0").error());
        assertEquals(FarmPlant.USAGE, FarmPlant.parse("wheat here x").error());
    }

    @Test
    void seedMapping() {
        assertEquals("minecraft:wheat_seeds", FarmPlant.seedFor("wheat"));
        assertEquals("minecraft:carrot", FarmPlant.seedFor("carrots"));
        assertEquals("minecraft:potato", FarmPlant.seedFor("potato"));
        assertEquals("minecraft:beetroot_seeds", FarmPlant.seedFor("beetroot"));
        assertEquals("mysticalagriculture:inferium_seeds", FarmPlant.seedFor("mysticalagriculture:inferium_seeds"));
        assertEquals("minecraft:melon_seeds", FarmPlant.seedFor("melon_seeds"));
        assertNull(FarmPlant.seedFor("dirt"));
        assertEquals("wheat", FarmPlant.cropName("minecraft:wheat_seeds"));
        assertEquals("inferium", FarmPlant.cropName("mysticalagriculture:inferium_seeds"));
        assertEquals("carrot", FarmPlant.cropName("minecraft:carrot"));
        assertEquals("minecraft:wheat_seeds", FarmPlant.replantSeed("minecraft:wheat"));
        assertEquals("minecraft:potato", FarmPlant.replantSeed("minecraft:potatoes"));
        assertEquals("mysticalagriculture:inferium_seeds", FarmPlant.replantSeed("mysticalagriculture:inferium_crop"));
        assertNull(FarmPlant.replantSeed("minecraft:stone"));
    }

    @Test
    void report() {
        assertEquals("planted 40 wheat (tilled 42, watered 3), 12 left: no seeds", FarmPlant.report("wheat", 40, 42, 3, 12, "no seeds"));
        assertEquals("planted 8 wheat (tilled 8, watered 1)", FarmPlant.report("wheat", 8, 8, 1, 0, null));
    }

    // ---- the layout ----

    /** A flat field of grass at y 63, with some cells set. */
    static FarmPlant.Ground field(Map<String, FarmPlant.Kind> special) {
        return (x, z) -> new FarmPlant.Cell(x, 63, z, special.getOrDefault(x + " " + z, FarmPlant.Kind.TILL));
    }

    @Test
    void latticeCoversEveryCellWithinFour() {
        assertEquals(List.of(4), FarmPlant.lattice(0, 8));
        assertEquals(List.of(4, 13), FarmPlant.lattice(0, 17));
        assertEquals(List.of(4, 13, 20), FarmPlant.lattice(0, 20));
        assertEquals(List.of(1), FarmPlant.lattice(0, 2));
        for (int hi = 0; hi < 33; hi++) {
            List<Integer> l = FarmPlant.lattice(0, hi);
            for (int v = 0; v <= hi; v++) {
                int vv = v;
                assertTrue(l.stream().anyMatch(p -> Math.abs(p - vv) <= FarmPlant.WATER_R), "cell " + v + " of 0.." + hi + ": " + l);
            }
        }
    }

    @Test
    void nineByNineGetsOneWaterInTheMiddle() {
        FarmPlant.Layout l = FarmPlant.plan(-4, -4, 4, 4, field(Map.of()), 1);
        assertEquals(1, l.water().size());
        assertEquals(0, l.water().get(0).x());
        assertEquals(0, l.water().get(0).z());
        assertEquals(80, l.plant().size(), "every cell but the water");
        assertEquals(0, l.dry());
        assertTrue(l.plant().stream().noneMatch(c -> c.x() == 0 && c.z() == 0));
    }

    @Test
    void withoutABucketOnlyCellsNearWaterArePlanted() {
        // water at the west edge: cells x <= -4+4 = 0 are wet
        Map<String, FarmPlant.Kind> sp = new HashMap<>();
        for (int z = -4; z <= 4; z++) sp.put("-4 " + z, FarmPlant.Kind.WATER);
        FarmPlant.Layout l = FarmPlant.plan(-4, -4, 4, 4, field(sp), 0);
        assertTrue(l.water().isEmpty());
        assertEquals(9 * 4, l.plant().size(), "x -3..0");
        assertEquals(9 * 4, l.dry(), "x 1..4");
        assertEquals(1, l.waterWanted());
        assertTrue(l.plant().stream().allMatch(c -> c.x() <= 0));
        // no water anywhere, no bucket: nothing
        FarmPlant.Layout none = FarmPlant.plan(0, 0, 8, 8, field(Map.of()), 0);
        assertTrue(none.plant().isEmpty());
        assertEquals(81, none.dry());
    }

    @Test
    void existingWaterMeansNoNewSource() {
        FarmPlant.Layout l = FarmPlant.plan(-4, -4, 4, 4, field(Map.of("0 0", FarmPlant.Kind.WATER)), 3);
        assertTrue(l.water().isEmpty());
        assertEquals(80, l.plant().size());
    }

    @Test
    void blockedCentreMovesTheWaterNextToIt() {
        FarmPlant.Layout l = FarmPlant.plan(-4, -4, 4, 4, field(Map.of("0 0", FarmPlant.Kind.BLOCKED)), 1);
        assertEquals(1, l.water().size());
        FarmPlant.Cell w = l.water().get(0);
        assertTrue(Math.abs(w.x()) <= 1 && Math.abs(w.z()) <= 1 && !(w.x() == 0 && w.z() == 0));
        assertEquals(1, l.blocked());
    }

    @Test
    void bigFieldWantsMoreBucketsThanItHas() {
        FarmPlant.Layout l = FarmPlant.plan(0, 0, 17, 8, field(Map.of()), 1);
        assertEquals(2, l.waterWanted());
        assertEquals(1, l.water().size());
        assertTrue(l.dry() > 0);
        // planted crops are left alone and counted
        FarmPlant.Layout p = FarmPlant.plan(-4, -4, 4, 4, field(Map.of("1 1", FarmPlant.Kind.PLANTED)), 1);
        assertEquals(1, p.planted());
        assertEquals(79, p.plant().size());
    }

    @Test
    void waterOnALowerLevelDoesNotCount() {
        FarmPlant.Ground g = (x, z) -> x == 0 && z == 0 ? new FarmPlant.Cell(0, 62, 0, FarmPlant.Kind.WATER) : new FarmPlant.Cell(x, 63, z, FarmPlant.Kind.TILL);
        FarmPlant.Layout l = FarmPlant.plan(-2, -2, 2, 2, g, 0);
        assertTrue(l.plant().isEmpty(), "water one below the farmland doesn't wet it");
    }

    // ---- cooking ----

    @Test
    void cookMapsRawToCooked() {
        assertEquals("minecraft:cooked_beef 8", Cooking.smeltText("beef 8"));
        assertEquals("minecraft:baked_potato", Cooking.smeltText("potato"));
        assertEquals("cooked_beef 2", Cooking.smeltText("cooked_beef 2"));
        assertEquals("minecraft:cooked_porkchop 3", Cooking.smeltText("minecraft:porkchop 3"));
        assertNull(Cooking.smeltText("  "));
    }

    @Test
    void rawMeatHasNoGatherSource() {
        GatherSources.Source s = GatherSources.resolve("beef", null);
        assertEquals(GatherSources.Kind.NONE, s.kind());
        assertTrue(s.hint().contains("hunting is off"), s.hint());
        assertEquals(GatherSources.Kind.CROP, GatherSources.resolve("potato", null).kind(), "crops stay farmable");
        assertNull(GatherSources.resolve("cooked_beef", null), "cooked: smelted from the raw item");
    }

    // ---- the vanilla round ----

    @Test
    void vanillaRoundBreaksAndReplants() {
        FakeWorld w = new FakeWorld();
        for (int dx = -1; dx <= 1; dx++) {
            w.set(dx, 52, 0, "minecraft:farmland");
            w.set(dx, 53, 0, "minecraft:wheat");
            w.ages.put(FakeWorld.k(dx, 53, 0), new int[]{dx == 1 ? 3 : 7, 7});
        }
        w.at(0.5, 53, 2.5);
        FarmSpot f = new FarmSpot(0, 53, 0, null, "off");
        FarmCommand.Reply r = FarmCommand.handle("", f, w, new FarmSettings("vanilla", "auto").resolve(true, true));
        assertEquals(List.of("farmgrow", "farmharvest", "farmgather", "farmreplant", "farmcompact", "farmdone", "farmdeposit"),
                r.start().steps().stream().map(PlanStep::type).toList());
        FarmRound round = r.start().round();
        assertFalse(round.twerk);
        // grow: no crouching
        FarmRound.Tick t = round.step("farmgrow", w, 0, 100);
        assertEquals("next", t.result());
        assertTrue(t.effects().stream().noneMatch(e -> e instanceof FarmRound.Sneak s && s.down()));
        // harvest: a Break for each ripe crop in reach, never for the unripe one
        int breaks = 0;
        for (int i = 0; i < 20; i++) {
            t = round.step("farmharvest", w, i * 2, 200 + i * 30);
            for (FarmRound.Effect e : t.effects()) {
                if (e instanceof FarmRound.Break b) {
                    assertNotEquals(1, b.x(), "the unripe one stays");
                    w.blocks.remove(FakeWorld.k(b.x(), b.y(), b.z()));
                    w.ages.remove(FakeWorld.k(b.x(), b.y(), b.z()));
                    breaks++;
                }
                assertFalse(e instanceof FarmRound.Use, "no right-click in vanilla mode");
            }
            if (t.result().equals("next")) break;
        }
        assertEquals(2, breaks);
        // replant: one seed short
        w.add("minecraft:wheat_seeds", 1);
        int plants = 0;
        for (int i = 0; i < 40; i++) {
            t = round.step("farmreplant", w, i * 2, 2000 + i * 30);
            for (FarmRound.Effect e : t.effects()) {
                if (e instanceof FarmRound.Plant p) {
                    assertEquals("minecraft:wheat_seeds", p.item());
                    assertEquals(52, p.y(), "the farmland is clicked");
                    w.set(p.x(), 53, p.z(), "minecraft:wheat");
                    w.ages.put(FakeWorld.k(p.x(), 53, p.z()), new int[]{0, 7});
                    w.remove("minecraft:wheat_seeds", 1);
                    plants++;
                }
            }
            if (t.result().equals("next")) break;
        }
        assertEquals("next", t.result());
        assertEquals(1, plants);
        assertEquals(1, round.replanted());
        assertTrue(round.replantNote().contains("replanted 1, 1 not (no wheat seeds)"), round.replantNote());
    }

    @Test
    void moddedRoundKeepsTheOldSteps() {
        FarmSpot f = new FarmSpot(FarmTest.FX, 53, FarmTest.FZ, null, "off");
        FarmCommand.Reply r = FarmCommand.handle("", f, FarmTest.farmWorld(), FarmSettings.DEFAULT.resolve(true, true));
        assertEquals(FarmRound.STEPS, r.start().steps().stream().map(PlanStep::type).toList());
        assertEquals("", r.start().round().replantNote());
    }
}
