package io.github.mojolowjo.entropybot.farm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The bridge's sim 'farm' scenario (test/sim.js) and the farm's pure rules. */
class FarmTest {
    static final int FX = 45, FZ = 200;
    static final String ESS = FarmRules.FARM_ESS, BLK = FarmRules.FARM_BLOCK;

    /** 8 crops on farmland around a water block at 45 52 200, the east row ripe already; the bot 4 south. */
    static FakeWorld farmWorld() {
        FakeWorld w = new FakeWorld();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) {
                w.set(FX, 52, FZ, "minecraft:water");
                continue;
            }
            w.crop(FX + dx, 53, FZ + dz, dx == 1 ? 7 : 2);
        }
        w.at(FX + 0.5, 53, FZ + 4.5);
        return w;
    }

    static boolean allAges(FakeWorld w, int age) {
        for (int[] a : w.ages.values()) if (a[0] != age) return false;
        return true;
    }

    static boolean farmIntact(FakeWorld w) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            if (!w.block(FX + dx, 53, FZ + dz).equals("mysticalagriculture:inferium_crop") || !w.block(FX + dx, 52, FZ + dz).equals("minecraft:farmland")) return false;
        }
        return true;
    }

    static void ripen(FakeWorld w) {
        for (int[] a : w.ages.values()) a[0] = 7;
    }

    static int gainedIn(String status) {
        Matcher m = Pattern.compile("\\+(\\d+) inferium essence").matcher(status);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    @Test
    void simFarmScenario() {
        FakeWorld w = farmWorld();
        StubCrafter crafter = StubCrafter.standard();

        FarmCommand.Reply r = FarmCommand.handle("here", null, w);
        assertEquals("ok: remembered the farm at 45 53 200, 8 crops", r.text());
        FarmSpot farm = r.save();
        assertNotNull(farm);
        r = FarmCommand.handle("compact off", farm, w);
        assertTrue(r.text().startsWith("ok: farm compacting is now off"), r.text());
        farm = r.save();

        // round 1: cobblestone in hand and a sword in the bag; out of reach for the twerk at first. One drop floats in
        // the water hole in the middle, one in a puddle 4 east of the farm, and the crops' drops land late
        w.give(1, "minecraft:cobblestone", 20);
        w.give(20, "minecraft:iron_sword", 1);
        w.selected = 1;
        w.drop(ESS, FX + 0.5, 52.9, FZ + 0.5, 1);
        w.set(FX + 4, 52, FZ, "minecraft:water");
        w.drop(ESS, FX + 4.5, 52.9, FZ + 0.5, 1);
        FarmSim sim = new FarmSim(w, crafter);
        sim.lateDrops = 30;
        r = FarmCommand.handle("", farm, w);
        assertNull(r.text());
        String st1 = sim.run(r.start(), 6000);
        assertTrue(st1.startsWith("ok: done farming"), st1);
        assertTrue(sim.twerkTicks > 0 && sim.twerkOut == 0, "it twerked only where Squat Grow reaches every crop: " + sim.twerkTicks + "/" + sim.twerkOut);
        assertTrue(st1.contains("harvested 8 crops") && allAges(w, 0), "all 8 harvested and replanted: " + st1);
        assertTrue(sim.clicks.size() >= 8);
        for (Object[] c : sim.clicks) {
            assertEquals(7, c[0], "every click on a ripe crop");
            assertTrue(c[1] != null && ((String) c[1]).endsWith("_sword"), "every click with a sword: " + c[1]);
        }
        assertTrue(w.live.stream().noneMatch(d -> d.alive), "the drops were picked up");
        assertTrue(w.count(ESS) > 0 && gainedIn(st1) == w.count(ESS), st1 + " / " + w.count(ESS));
        assertTrue(farmIntact(w) && sim.placed.isEmpty(), "the farm is intact, nothing placed");
        assertTrue(st1.contains("(twerked "), st1);

        // round 2: no sword, cobblestone in hand; compacting into blocks
        for (int i = 0; i < 36; i++) if (w.ids[i].endsWith("_sword")) w.give(i, "", 0);
        w.give(1, "minecraft:cobblestone", 20);
        w.selected = 1;
        w.add(ESS, 20);
        farm = FarmCommand.handle("compact block", farm, w).save();
        ripen(w);
        sim = new FarmSim(w, crafter);
        int essBefore = w.count(ESS);
        String st2 = sim.run(FarmCommand.handle("", farm, w).start(), 6000);
        assertTrue(st2.startsWith("ok: done farming"), st2);
        assertTrue(sim.clicks.size() >= 8 && sim.clicks.stream().allMatch(c -> c[1] == null), "no sword: an empty hand, never the cobblestone");
        assertTrue(allAges(w, 0), "round 2 harvested all 8");
        assertTrue(w.count(BLK) > 0 && st2.matches(".*made \\d+ inferium blocks?.*") && w.count(ESS) < 9,
                "the essence went into blocks: " + w.count(BLK) + " blocks, " + w.count(ESS) + " essence (had " + essBefore + ") " + st2);
        assertEquals(w.count(ESS) + 9 * w.count(BLK) - essBefore, gainedIn(st2), st2);
        assertTrue(farmIntact(w) && sim.placed.isEmpty());

        // round 3: no sword and no free slot: it refuses to click at all
        for (int i = 0; i < 36; i++) if (w.ids[i].isEmpty()) w.give(i, "minecraft:dirt", 64);
        w.selected = 1;
        ripen(w);
        farm = FarmCommand.handle("compact off", farm, w).save();
        sim = new FarmSim(w, crafter);
        String st3 = sim.run(FarmCommand.handle("", farm, w).start(), 6000);
        assertEquals("error: I can't free my hand for the harvest (no sword, no empty slot) - give me a sword (while farming)", st3);
        assertTrue(sim.clicks.isEmpty());
        assertTrue(farmIntact(w) && sim.placed.isEmpty());

        // a round that ends with the bag nearly full puts things away at the base chests
        for (int i = 0; i < 36; i++) w.give(i, i < 32 ? "minecraft:dirt" : "", i < 32 ? 64 : 0);
        w.give(0, "leafscopperbackport:copper_sword", 1);
        w.selected = 0;
        ripen(w);
        w.at(FX + 0.5, 53, FZ + 3.5);
        sim = new FarmSim(w, crafter);
        String st4 = sim.run(FarmCommand.handle("", farm, w).start(), 8000);
        assertTrue(st4.matches("ok: done farming; harvested 8 crops.*; put away \\d+ items"), st4);
        assertTrue(FarmRules.freeSlots(w.slots()) > 4, "free slots after the deposit");
        assertTrue(farmIntact(w) && sim.placed.isEmpty());
    }

    // ---- the command ----

    @Test
    void commandTexts() {
        FakeWorld w = farmWorld();
        assertEquals("error: I don't know a farm yet - stand me next to the crops and PM farm", FarmCommand.handle("compact", null, w).text());
        FarmSpot f = new FarmSpot(45, 53, 200, "minecraft:overworld", null);
        assertEquals("farm compacting: block (PM \"farm compact block|prudentium|off\")", FarmCommand.handle("compact", f, w).text());
        FarmCommand.Reply r = FarmCommand.handle("compact prudentium", f, w);
        assertEquals("ok: farm compacting is now prudentium (needs an infusion crystal in my inventory or a base chest)", r.text());
        assertEquals("prudentium", r.save().compact());
        assertEquals("block", FarmCommand.handle("compact blocks", f, w).save().compact());
        assertEquals("usage: farm [here | compact block|prudentium|off]", FarmCommand.handle("now", f, w).text());
        assertTrue(FarmCommand.instant("compact off") && FarmCommand.instant("here") && !FarmCommand.instant("") && !FarmCommand.instant("hereabouts"));

        FakeWorld empty = new FakeWorld();
        empty.at(0.5, 53, 0.5);
        assertEquals("error: no crops on farmland within 6 blocks of me", FarmCommand.handle("here", f, empty).text());
        assertEquals("error: I don't know a farm - stand me next to the crops and PM farm (or \"farm here\")", FarmCommand.handle("", null, empty).text());

        FarmSpot nether = new FarmSpot(45, 53, 200, "minecraft:the_nether", "block");
        assertEquals("error: the farm is in minecraft:the_nether", FarmCommand.handle("", nether, w).text());

        // "farm" with no farm known but crops by it: remembers it and starts, with the note
        r = FarmCommand.handle("", null, w);
        assertEquals("remembered the farm at 45 53 200, 8 crops", r.start().note());
        assertEquals("block", r.save().compact());
        assertEquals("farming", r.start().label());
        // the bot stands 4 from the spot: no walk first; 6 away: walk near it first
        assertEquals(List.of("farmgrow", "farmharvest", "farmgather", "farmcompact", "farmdone", "farmdeposit"),
                r.start().steps().stream().map(PlanStep::type).toList());
        w.at(FX + 0.5, 53, FZ + 6.5);
        List<PlanStep> steps = FarmCommand.handle("", f, w).start().steps();
        assertEquals("walk", steps.get(0).type());
        assertTrue(steps.get(0).near());
        assertArrayEquals(new int[]{45, 53, 200}, steps.get(0).pos());
        // "farm here" keeps the compact mode
        assertEquals("off", FarmCommand.handle("here", f.withCompact("off"), farmWorld()).save().compact());
    }

    @Test
    void farmSpotJson() {
        FarmSpot f = new FarmSpot(1, 2, 3, "minecraft:overworld", "prudentium");
        assertEquals(f, FarmSpot.fromJson(f.toJson()));
        FarmSpot g = FarmSpot.fromJson(new FarmSpot(1, 2, 3, null, null).toJson());
        assertEquals("block", g.mode());
        assertNull(FarmSpot.fromJson(null));
    }

    // ---- crops ----

    @Test
    void cropsRipeAndUnripe() {
        FakeWorld w = new FakeWorld();
        w.crop(0, 53, 0, 7);
        w.crop(1, 53, 0, 3);
        w.set(2, 52, 0, "minecraft:farmland");
        w.set(2, 53, 0, "minecraft:melon_stem");
        w.ages.put("2 53 0", new int[]{7, 7});
        w.set(3, 53, 0, "minecraft:wheat");                 // an age but no farmland under it
        w.ages.put("3 53 0", new int[]{7, 7});
        w.set(4, 52, 0, "minecraft:farmland");
        w.set(4, 53, 0, "minecraft:dead_bush");             // on farmland but no age
        w.set(5, 52, 0, "minecraft:farmland");              // bare farmland
        assertTrue(FarmRules.cropAt(w, 0, 53, 0).ripe());
        assertFalse(FarmRules.cropAt(w, 1, 53, 0).ripe());
        assertNull(FarmRules.cropAt(w, 2, 53, 0), "stems are never harvested");
        assertNull(FarmRules.cropAt(w, 3, 53, 0));
        assertNull(FarmRules.cropAt(w, 4, 53, 0));
        assertNull(FarmRules.cropAt(w, 5, 53, 0));
        assertEquals(2, FarmRules.farmCrops(w, new int[]{0, 53, 0}).size());
        List<int[]> spots = List.of(new int[]{0, 53, 0}, new int[]{1, 53, 0});
        assertEquals(1, FarmRules.cropsNow(w, spots, true).size());
        assertEquals(1, FarmRules.cropsNow(w, spots, false).size());
        // FARM_R: 6 out counts, 7 doesn't; 2 up or down
        w.crop(6, 55, 0, 1);
        w.crop(7, 53, 0, 1);
        w.crop(0, 56, 1, 1);
        assertEquals(3, FarmRules.farmCrops(w, new int[]{0, 53, 0}).size());
    }

    @Test
    void squatReachAndStandSpot() {
        List<int[]> crops = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) crops.add(new int[]{FX + dx, 53, FZ + dz});
        assertEquals(8, FarmRules.squatReach(new int[]{FX, 53, FZ + 2}, crops));
        assertEquals(3, FarmRules.squatReach(new int[]{FX, 53, FZ + 4}, crops));
        assertTrue(FarmRules.inSquatRange(new int[]{FX + 2, 54, FZ}, crops));
        assertFalse(FarmRules.inSquatRange(new int[]{FX + 3, 53, FZ}, crops));

        FakeWorld w = farmWorld();
        FarmRules.Stand s = FarmRules.farmStandSpot(w, crops);
        // reaches all 8, not on a crop, nearest to the bot (4 south)
        assertEquals(8, s.n());
        assertEquals(0, s.onCrop());
        assertEquals("45 53 202", s.fmt());
        // walls everywhere off the farm: the only free cells reaching all are the crops' own
        FakeWorld walled = farmWorld();
        for (int x = FX - 5; x <= FX + 5; x++) for (int z = FZ - 5; z <= FZ + 5; z++) {
            if (Math.abs(x - FX) <= 1 && Math.abs(z - FZ) <= 1) continue;
            walled.set(x, 53, z, "minecraft:stone");
            walled.set(x, 54, z, "minecraft:stone");
        }
        s = FarmRules.farmStandSpot(walled, crops);
        assertEquals(1, s.onCrop());
        assertEquals("45 53 201", s.fmt(), "the crop cell nearest the bot");
        assertEquals(8, s.n());
    }

    @Test
    void dropStandSpots() {
        FakeWorld w = farmWorld();
        w.at(FX + 0.5, 53, FZ + 4.5);
        FarmWorld.Drop onCrop = new FarmWorld.Drop("1", ESS, FX + 1.5, 53.2, FZ + 1.5);
        assertArrayEquals(new int[]{46, 53, 201}, FarmRules.dropStandSpot(w, onCrop, false));
        // second try: not its own block, the free one next to it nearest to the bot
        assertArrayEquals(new int[]{45, 53, 202}, FarmRules.dropStandSpot(w, onCrop, true));
        // in the water hole: a side cell
        FarmWorld.Drop inWater = new FarmWorld.Drop("2", ESS, FX + 0.5, 52.9, FZ + 0.5);
        assertArrayEquals(new int[]{45, 53, 201}, FarmRules.dropStandSpot(w, inWater, false));
        // nowhere to stand around it
        FakeWorld box = new FakeWorld();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 52; y <= 55; y++) box.set(x, y, z, "minecraft:stone");
        box.set(0, 53, 0, "minecraft:air");
        box.set(0, 54, 0, "minecraft:air");
        assertNull(FarmRules.dropStandSpot(box, new FarmWorld.Drop("3", ESS, 0.5, 53, 0.5), true));
    }

    @Test
    void farmDropsBox() {
        FakeWorld w = farmWorld();
        FarmSpot f = new FarmSpot(FX, 53, FZ, null, null);
        w.drop(ESS, FX + 7.5, 53, FZ + 0.5, 1);     // 7 off center x: in
        w.drop(ESS, FX + 8.5, 53, FZ + 0.5, 1);     // 8: out
        w.drop(ESS, FX + 0.5, 57, FZ + 0.5, 1);     // 4 up: out
        w.drop(ESS, FX - 6.5, 50, FZ - 6.5, 1);     // 3 down, -7: in
        assertEquals(2, FarmRules.farmDrops(w, f).size());
    }

    @Test
    void harmlessHand() {
        List<String> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) slots.add("minecraft:dirt");
        assertEquals(FarmRules.Hand.HELD, FarmRules.harmlessHand(with(slots, 3, ""), 3));
        assertEquals(FarmRules.Hand.HELD, FarmRules.harmlessHand(with(slots, 3, "minecraft:iron_sword"), 3));
        assertEquals(new FarmRules.Hand("select", 5), FarmRules.harmlessHand(with(slots, 5, "minecraft:stone_sword"), 0));
        assertEquals(new FarmRules.Hand("swap", 20), FarmRules.harmlessHand(with(slots, 20, "leafscopperbackport:copper_sword"), 0));
        assertEquals(new FarmRules.Hand("select", 7), FarmRules.harmlessHand(with(with(slots, 7, ""), 30, ""), 0), "an empty hotbar slot before a bag slot");
        assertEquals(new FarmRules.Hand("swap", 30), FarmRules.harmlessHand(with(slots, 30, ""), 0));
        assertNull(FarmRules.harmlessHand(slots, 0));
    }

    static List<String> with(List<String> s, int i, String id) {
        List<String> out = new ArrayList<>(s);
        out.set(i, id);
        return out;
    }

    // ---- the round's pieces ----

    @Test
    void reportTexts() {
        assertEquals("harvested 8 crops (twerked 12 s), +14 inferium essence", FarmRound.report(8, 12, 0, false, 14, 0, 0, false, 0));
        assertEquals("harvested 6 crops (twerked 60 s), 2 still growing (I couldn't stand in reach of them all), +9 inferium essence; made 1 inferium block; 3 drops I couldn't reach",
                FarmRound.report(6, 60, 2, true, 9, 1, 0, false, 3));
        assertEquals("harvested 8 crops, +40 inferium essence; made 4 inferium blocks; made 1 prudentium essence; my inventory is full, so some drops stay on the ground",
                FarmRound.report(8, 0, 0, false, 40, 4, 1, true, 5));
        assertEquals("harvested 0 crops, 3 still growing, +0 inferium essence", FarmRound.report(0, 0, 3, false, 0, 0, 0, false, 0));
    }

    @Test
    void depositRuleAndNotes() {
        FakeWorld w = farmWorld();
        FarmSpot f = new FarmSpot(FX, 53, FZ, null, "off");
        for (int i = 0; i < 31; i++) w.give(i, "minecraft:dirt", 64);
        FarmRound round = new FarmRound(f, "farming", w.inventory());
        FarmRound.Tick t = round.step("farmdeposit", w, 0, 0);
        assertEquals("next", t.result());
        assertTrue(t.effects().isEmpty(), "5 free slots: no deposit");
        w.give(31, "minecraft:dirt", 64);
        t = round.step("farmdeposit", w, 0, 0);
        assertEquals(List.of(new FarmRound.Deposit()), t.effects(), "4 free slots: deposit");

        round.step("farmdone", w, 0, 0);
        assertEquals("harvested 0 crops, +0 inferium essence; put away 12 items", round.noteAfterDeposit("put away 12 items"));
        assertEquals("harvested 0 crops, +0 inferium essence", round.noteAfterDeposit("harvested 0 crops, +0 inferium essence"));
        assertEquals("harvested 0 crops, +0 inferium essence; my bag is nearly full, but I know no base chests - PM scan base",
                FarmRound.depositFailed("harvested 0 crops, +0 inferium essence", "error: I know no base chests - PM scan base"));
    }

    @Test
    void compactStepModes() {
        FakeWorld w = farmWorld();
        w.give(0, ESS, 40);
        Map<String, Integer> have = w.inventory();
        FarmRound.Tick t = new FarmRound(new FarmSpot(FX, 53, FZ, null, null), "farming", have).step("farmcompact", w, 0, 0);
        assertEquals(List.of(new FarmRound.Craft(BLK, 4, true)), t.effects());
        t = new FarmRound(new FarmSpot(FX, 53, FZ, null, "prudentium"), "farming", have).step("farmcompact", w, 0, 0);
        assertEquals(List.of(new FarmRound.Craft(FarmRules.FARM_PRUD, 10, true)), t.effects());
        t = new FarmRound(new FarmSpot(FX, 53, FZ, null, "off"), "farming", have).step("farmcompact", w, 0, 0);
        assertTrue(t.effects().isEmpty());
        w.give(0, ESS, 8);
        t = new FarmRound(new FarmSpot(FX, 53, FZ, null, "block"), "farming", have).step("farmcompact", w, 0, 0);
        assertTrue(t.effects().isEmpty(), "fewer than 9: no craft");
    }

    @Test
    void growWithoutCropsAndTimeout() {
        FakeWorld w = new FakeWorld();
        w.at(0.5, 53, 0.5);
        FarmRound round = new FarmRound(new FarmSpot(0, 53, 0, null, null), "farming", w.inventory());
        assertEquals("no crops at the farm (0 53 0) - PM \"farm here\" next to them", round.step("farmgrow", w, 0, 0).result());

        // crops that never grow: it twerks 60 s, then moves on and says how many are still growing
        FakeWorld g = farmWorld();
        g.at(FX + 0.5, 53, FZ + 2.5);
        round = new FarmRound(new FarmSpot(FX, 53, FZ, null, "off"), "farming", g.inventory());
        FarmRound.Tick t = round.step("farmgrow", g, 0, 0);
        assertEquals("wait", t.result());
        assertEquals("farming - twerking so they grow (3/8 ripe)", t.status());
        t = round.step("farmgrow", g, 1199, 1199);
        assertEquals("wait", t.result());
        t = round.step("farmgrow", g, 1200, 1200);
        assertEquals("next", t.result());
        assertEquals(List.of(new FarmRound.Sneak(false)), t.effects());
        assertEquals(60, round.grewS);
        assertEquals(5, round.unripe);
    }

    @Test
    void harvestWalksToFarCropsAndStopsAfterThreeClicks() {
        FakeWorld w = farmWorld();
        ripen(w);
        w.at(FX + 0.5, 53, FZ + 7.5);                                   // too far to reach any
        FarmRound round = new FarmRound(new FarmSpot(FX, 53, FZ, null, "off"), "farming", w.inventory());
        assertEquals("next", round.step("farmgrow", w, 0, 0).result());   // all ripe: no twerk
        FarmRound.Tick t = round.step("farmharvest", w, 0, 10);
        assertEquals("wait", t.result());
        assertTrue(t.effects().contains(new FarmRound.Walk(45, 53, 201, 1)), "next to the nearest crop: " + t.effects());
        // while walking (Baritone busy) it waits, then clicks once it is close
        w.pathing = true;
        assertTrue(round.step("farmharvest", w, 100, 110).effects().stream().noneMatch(e -> e instanceof FarmRound.Use));
        w.pathing = false;
        w.at(FX + 0.5, 53, FZ + 2.5);
        t = round.step("farmharvest", w, 102, 112);
        FarmRound.Use u = (FarmRound.Use) t.effects().stream().filter(e -> e instanceof FarmRound.Use).findFirst().orElseThrow();
        assertEquals(FarmRules.Hand.HELD, u.hand());
        assertEquals("farming - harvesting (1)", t.status());
        // a crop that stays ripe gets 3 clicks (20 ticks apart), then it is left alone
        int clicksOnIt = 1;
        for (long tick = 114; tick < 400; tick += 2) {
            t = round.step("farmharvest", w, tick - 10, tick);
            for (FarmRound.Effect e : t.effects()) if (e instanceof FarmRound.Use v && v.x() == u.x() && v.z() == u.z()) clicksOnIt++;
            if (t.result().equals("next")) break;
        }
        assertEquals(3, clicksOnIt);
        assertEquals("next", t.result(), "nothing harvestable left");
        assertEquals(8, round.harvested(), "each crop counts once");
    }

    @Test
    void gatherFullBag() {
        FakeWorld w = farmWorld();
        for (int i = 0; i < 36; i++) w.give(i, "minecraft:dirt", 64);
        w.drop(ESS, FX + 1.5, 53.2, FZ + 1.5, 1);
        FarmRound round = new FarmRound(new FarmSpot(FX, 53, FZ, null, "off"), "farming", w.inventory());
        assertEquals("wait", round.step("farmgather", w, 10, 10).result());
        assertEquals("next", round.step("farmgather", w, 20, 20).result());
        round.step("farmdone", w, 0, 0);
        assertTrue(round.noteAfterDeposit(null).endsWith("; my inventory is full, so some drops stay on the ground"));
    }

    @Test
    void gatherGivesUpAfterTwoTries() {
        FakeWorld w = farmWorld();
        FakeWorld.LiveDrop d = w.drop(ESS, FX + 4.5, 52.9, FZ + 0.5, 1);
        w.set(FX + 4, 52, FZ, "minecraft:water");
        FarmRound round = new FarmRound(new FarmSpot(FX, 53, FZ, null, "off"), "farming", w.inventory());
        FarmRound.Tick t = round.step("farmgather", w, 20, 20);
        assertEquals("farming - picking up the drops", t.status());
        FarmRound.Walk first = (FarmRound.Walk) t.effects().get(0);
        assertEquals(0, first.range());
        assertTrue(w.standable(first.x(), first.y(), first.z()));
        assertEquals("wait", round.step("farmgather", w, 119, 119).result(), "5 s per try");
        t = round.step("farmgather", w, 120, 120);
        assertEquals(1, t.effects().size(), "a second try");
        round.step("farmgather", w, 220, 220);                         // tries used up: nothing more to fetch, look once more
        assertEquals("wait", round.step("farmgather", w, 222, 222).result());
        assertEquals("next", round.step("farmgather", w, 237, 237).result());
        round.step("farmdone", w, 0, 0);
        assertTrue(d.alive);
        assertTrue(round.noteAfterDeposit(null).endsWith("; 1 drops I couldn't reach"), round.noteAfterDeposit(null));
    }
}
