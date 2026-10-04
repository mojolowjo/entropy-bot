package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.guard.Policy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B7d D1: the real tick loop ({@link ClearRun}, what the mod's clear step drives) over the sim's worlds. The reports
 * are the bridge's, word for word: the same ones ClearRunTest and StripTest get from ClearDriver's stand-in. Plus the
 * leases ({@link LeaseSet}), the placing rules and the table decision ({@link PlaceRules}), and the honest end reasons.
 */
class ClearRunLoopTest {
    static RunDriver hill(boolean bumpy, int pickDur) {
        return new RunDriver(SimWorlds.hill(bumpy), SimWorlds.home()).simInventory(pickDur);
    }

    @Test
    void hill() {
        RunDriver d = hill(false, 100000);
        ClearJob job = ClearJob.start(new ClearJob.Options(), SimWorlds.ZONE, d.ores);
        String msg = d.run(job);
        assertEquals("ok: finished clearing the zone - broke 2864 blocks; 5 left, e.g. -6 55 145 next to water/lava, -5 54 145 next to water/lava, "
                + "-5 55 144 next to water/lava; 1 ores left in place for you (PM \"ores\")", msg);
        assertEquals(7, SimWorlds.countLeft(d.w));
        assertEquals(ClearRunTest.NEXT_TO_WATER, job.skip.keySet());
        assertEquals("chest", d.w.get(-20, 54, 158));
        assertEquals("iron_ore", d.ores.ores.get("-15 55 150"));
        assertEquals(0, d.toolBreaks);
        assertTrue(d.statuses.stream().anyMatch(s -> s.startsWith("clearing the zone: breaking ")), d.statuses.toString());
    }

    @Test
    void bumpy() {
        RunDriver d = hill(true, 100000);
        String msg = d.run(ClearJob.start(new ClearJob.Options(), SimWorlds.ZONE, d.ores));
        assertTrue(msg.startsWith("ok: finished clearing the zone - broke 2856 blocks; 5 left, e.g. "), msg);
        assertTrue(msg.endsWith("; 1 ores left in place for you (PM \"ores\")"), msg);
        assertEquals(7, SimWorlds.countLeft(d.w));
    }

    @Test
    void lowtools() {
        RunDriver d = hill(false, 40);
        d.crafter = craft -> "error: I don't know an item called " + craft.replaceFirst(" \\d+$", "");
        String msg = d.run(ClearJob.start(new ClearJob.Options(), SimWorlds.ZONE, d.ores));
        assertEquals(2, d.toolBreaks);
        assertEquals(List.of("minecraft:stone_pickaxe 3 -> error: I don't know an item called minecraft:stone_pickaxe",
                "leafscopperbackport:copper_pickaxe 3 -> error: I don't know an item called leafscopperbackport:copper_pickaxe"), d.crafts);
        assertEquals("stopped: out of pickaxes and I can't make more (I don't know an item called leafscopperbackport:copper_pickaxe) - broke 305 blocks; "
                + "2564 left; 1 ores left in place for you (PM \"ores\")", msg);
    }

    @Test
    void lowtoolsCraftingPickaxes() {
        RunDriver d = hill(false, 40);
        d.craftedDur = 30;
        d.crafter = craft -> "started: crafting " + craft;
        String msg = d.run(ClearJob.start(new ClearJob.Options(), SimWorlds.ZONE, d.ores));
        assertEquals(List.of("minecraft:stone_pickaxe 3 -> started: crafting minecraft:stone_pickaxe 3",
                "minecraft:stone_pickaxe 3 -> started: crafting minecraft:stone_pickaxe 3"), d.crafts);
        assertEquals(8, d.toolBreaks);
        assertTrue(msg.startsWith("stopped: out of pickaxes (stone needs one) - broke "), msg);
    }

    @Test
    void cheapestPickaxeFirst() {
        RunDriver d = new RunDriver(new FakeWorld(), Bot.at(0.5, 53, 0.5));
        d.items[0] = new ClearDriver.Item("minecraft:iron_pickaxe", 6, 1000);
        d.items[12] = new ClearDriver.Item("minecraft:stone_pickaxe", 4, 2);
        d.items[13] = new ClearDriver.Item("minecraft:diamond_pickaxe", 8, 1000);
        String msg = d.clear(new ClearJob.Options().box(ClearBox.of(-1, 52, -1, 1, 52, 1)).label("digging the floor"));
        assertEquals("ok: done digging the floor - broke 9 blocks", msg);
        assertEquals(1, d.toolBreaks, "the stone pickaxe went first");
        assertEquals(1000 - 7, d.items[0].dur, "then iron, before diamond");
        assertEquals(1000, d.items[13].dur);
        // package B: with the stone one gone it tried once to make stone pickaxes (no crafter here), then went on with iron
        assertEquals(List.of("stone minecraft:stone_pickaxe 3 (failed)"), d.trips);
    }

    @Test
    void safetyDigs() {
        FakeWorld w = SimWorlds.hill(false);
        RunDriver d = new RunDriver(w, SimWorlds.home()).simInventory(100000);
        w.set(-11, 52, 170, "oak_planks");
        w.set(-10, 52, 171, "glass");
        String st = d.clear(new ClearJob.Options().box(ClearBox.of(-12, 52, 169, -10, 52, 171)).label("digging -12 52 169 to -10 52 171"));
        assertEquals("ok: done digging -12 52 169 to -10 52 171 - broke 7 blocks; left 2 built blocks alone (e.g. -11 52 170 oak_planks)", st);
        Bot home = SimWorlds.home();
        d.moveTo(home.x(), home.y(), home.z());
        d.fall();
        w.set(-12, 53, 169, "moss_carpet");
        w.set(-12, 54, 169, "stone");
        st = d.clear(new ClearJob.Options().box(ClearBox.of(-12, 53, 169, -12, 54, 169)).mustFinish(true).label("test corridor"));
        assertEquals("ok: done test corridor - broke 1 blocks; left 1 built block alone (e.g. -12 53 169 moss_carpet)", st);
        w.set(-12, 53, 171, "oak_planks");
        w.set(-12, 54, 171, "stone");
        st = d.clear(new ClearJob.Options().box(ClearBox.of(-12, 53, 171, -12, 54, 171)).mustFinish(true).label("test corridor"));
        assertEquals("stopped: the mine corridor is blocked at -12 53 171 (oak_planks: a built block I don't break - if it's a mineshaft, "
                + "PM dig -12 53 171 -12 53 171 force) - broke 1 blocks; left 1 built block alone (e.g. -12 53 171 oak_planks)", st);
        st = d.run(ClearJob.start(new ClearJob.Options().box(ClearBox.of(-12, 53, 171, -12, 53, 171)).force(true)
                .label("digging -12 53 171 to -12 53 171 (force)"), null, d.ores));
        assertEquals("ok: done digging -12 53 171 to -12 53 171 (force) - broke 1 blocks", st);
        assertEquals("air", w.get(-12, 53, 171));
    }

    // ---- the strip mine's dig steps through the loop (D2 builds the runs; the clears must report the same) ----

    @Test
    void strip() {
        StripTest.StripRun s = new StripTest.StripRun(SimWorlds.strip(0, false, false), 0, true, true);
        s.setup();
        assertTrue(s.branch(1));
        assertEquals(StripTest.STRIP_RUN1, s.log);
        s.log.clear();
        s.backFromBase(2);
        assertTrue(s.branch(2));
        assertEquals(List.of(
                "ok: done digging the mine corridor (branch 2) - broke 6 blocks; 1 ores mined",
                "ok: done digging branch 2 left - broke 24 blocks",
                "ok: finished digging branch 2 right - broke 16 blocks; 8 left, e.g. 8 41 -6 next to water/lava, "
                        + "9 41 -6 out of reach (nowhere to stand close enough), 10 40 -6 out of reach (nowhere to stand close enough)"), s.log);
        s.log.clear();
        assertTrue(s.branch(3));
        assertEquals(List.of(
                "ok: done digging the mine corridor (branch 3) - broke 7 blocks",
                "ok: done digging branch 3 left - broke 24 blocks",
                "ok: done digging branch 3 right - broke 24 blocks",
                "ok: done mining 1 ore blocks next to branch 3 - broke 1 blocks; 1 ores mined"), s.log);
        assertEquals(Map.of(), s.d.ores.ores);
    }

    @Test
    void strip2() {
        StripTest.StripRun s = new StripTest.StripRun(SimWorlds.strip(0, true, false), 0, true, true);
        s.setup();
        assertTrue(s.branch(1));
        s.log.clear();
        s.backFromBase(2);
        assertFalse(s.branch(2));
        assertEquals(List.of("stopped: the mine corridor is blocked at 0 40 -5 (bedrock) - broke 5 blocks; 1 ores mined"), s.log);
    }

    @Test
    void stripPit() {
        StripTest.StripRun s = new StripTest.StripRun(SimWorlds.strip(0, false, true), 0, true, true);
        s.setup();
        assertTrue(s.branch(1));
        assertEquals("ok: done mining 3 ore blocks next to branch 1 - broke 3 blocks; 3 ores mined", s.log.get(s.log.size() - 1));
        assertTrue(s.d.minY >= 40, "the bot never stood below the branch floor: " + s.d.minY);
    }

    // ---- the honest end reason (TO-LOOK-AT-LATER 18b) ----

    /** The owner's case: the last pickaxe broke, the craft couldn't open a table 74 blocks away: the report says exactly that. */
    @Test
    void outOfPickaxesFarFromATableSaysWhy() {
        RunDriver d = hill(false, 40);
        d.items[3] = null;           // one copper pickaxe only
        ClearJob job = ClearJob.start(new ClearJob.Options().box(ClearBox.of(-26, 53, 140, -2, 58, 164)).label("digging -26 53 140 to -2 58 164"), null, d.ores);
        ClearRun run = new ClearRun(d.w, job);
        d.run = run;
        ClearRun.Out o = null;
        for (int n = 0; n < 2_000_000 && (o == null || o.kind() == ClearRun.Kind.RUN); n++, d.tick++) {
            d.fall();
            o = run.tick(d);
        }
        assertEquals(ClearRun.Kind.CRAFT, o.kind());
        assertEquals(List.of("minecraft:stone_pickaxe 3", "leafscopperbackport:copper_pickaxe 3"), o.crafts());
        run.tripStarted(o.kind());
        ClearRun.Out end = run.resumed(d, "error: could not open the crafting table: error: too far (74.3 blocks), walk closer first");
        assertEquals(ClearRun.Kind.END, end.kind());
        assertTrue(end.text().startsWith("stopped: out of pickaxes and making more failed (could not open the crafting table: error: too far (74.3 blocks), "
                + "walk closer first) - broke " + job.broken + " blocks; "), end.text());
        assertTrue(end.text().contains(" left"), end.text());
        assertEquals(end.text(), run.ended());
    }

    @Test
    void aTripThatEndedStoppedEndsTheClearWithThat() {
        RunDriver d = hill(false, 100000);
        ClearRun run = new ClearRun(d.w, ClearJob.start(new ClearJob.Options(), SimWorlds.ZONE, d.ores));
        run.tripStarted(ClearRun.Kind.DEPOSIT);
        assertEquals("stopped: attacked by zombie", run.resumed(d, "stopped: attacked by zombie").text());
    }

    @Test
    void aDepositThatLeftTheBagFullTurnsDepositsOff() {
        RunDriver d = hill(false, 100000) ;
        ClearRun run = new ClearRun(d.w, ClearJob.start(new ClearJob.Options(), SimWorlds.ZONE, d.ores));
        run.tripStarted(ClearRun.Kind.DEPOSIT);
        assertEquals(ClearRun.Kind.RUN, run.resumed(d, "error: the chest at 1 2 3 did not open").kind());
        assertNull(run.trip());
    }

    @Test
    void stopMidBreakStopsBreaking() {
        RunDriver d = hill(false, 100000);
        d.stopAt = t -> t == 500;
        String r = d.run(ClearJob.start(new ClearJob.Options(), SimWorlds.ZONE, d.ores));
        assertEquals("stopped", r);
        assertFalse(d.run.breaking());
        assertTrue(d.run.job.broken > 0);
    }

    /** A gap of more than 5 ticks (a fight held the job): it chooses again from where it stands, it never hits on blind. */
    @Test
    void aHoldMakesItChooseAgain() {
        RunDriver d = hill(false, 100000);
        ClearRun run = new ClearRun(d.w, ClearJob.start(new ClearJob.Options(), SimWorlds.ZONE, d.ores));
        for (int i = 0; i < 40; i++, d.tick++) {
            d.fall();
            run.tick(d);
        }
        d.tick += 200;            // held
        run.tick(d);
        assertNotEquals("walk", run.phase());
    }

    // ---- leases ----

    static GuardCore guard(boolean strict) {
        GuardCore g = new GuardCore();
        g.setPolicy(new Policy(List.of(new Box("home", "minecraft:overworld", -272, -64, -64, 223, 320, 431)), List.of()));
        g.setMode(strict ? GuardCore.Mode.STRICT : GuardCore.Mode.LOG);
        return g;
    }

    @Test
    void theOwnersDigNeedsTwoLeasesAndStopReleasesThem() {
        GuardCore g = guard(true);
        LeaseSet l = new LeaseSet(g, "tok", "minecraft:overworld", null);
        ClearBox box = ClearBox.of(0, 38, 112, 15, 54, 127);
        assertEquals(4352, box.volume());
        assertEquals(2, PlaceRules.leaseSlices(box, PlaceRules.LEASE_MAX).size());
        assertNull(l.take("digging 0 38 112 to 15 54 127", box, false, false));
        assertEquals(2, l.ids.size());
        assertEquals(2, g.leases().size());
        // every cell of the box is covered
        assertTrue(g.checkBoxes("minecraft:overworld", 0, 38, 112, "break").allowed());
        assertTrue(g.checkBoxes("minecraft:overworld", 15, 54, 127, "break").allowed());
        // the job ends (stop, an error, the end): all of them go, another holder's stay
        LeaseSet other = new LeaseSet(g, "tok", "minecraft:overworld", null);
        assertNull(other.take("someone else", ClearBox.of(50, 60, 50, 51, 61, 51), false, false));
        l.releaseAll();
        assertEquals(1, g.leases().size());
        assertTrue(l.ids.isEmpty());
    }

    @Test
    void strictRefusalIsTheErrorLogModeGoesOn() {
        LeaseSet strict = new LeaseSet(guard(true), "tok", "minecraft:overworld", null);
        assertEquals("error: the guard refused: that box is not inside one of my areas - area add <name> here <r>",
                strict.take("digging", ClearBox.of(1000, 50, 1000, 1001, 51, 1001), false, false));
        assertTrue(strict.ids.isEmpty());
        List<String> logged = new ArrayList<>();
        LeaseSet log = new LeaseSet(guard(false), "tok", "minecraft:overworld", logged::add);
        assertNull(log.take("digging", ClearBox.of(1000, 50, 1000, 1001, 51, 1001), false, false));
        assertNull(log.take("digging again", ClearBox.of(1000, 50, 1000, 1001, 51, 1001), false, false));
        assertEquals(1, logged.stream().filter(s -> s.startsWith("lease refused")).count(), "logged once");
        // a force lease is one box of 64 at most, never sliced
        LeaseSet f = new LeaseSet(guard(true), "tok", "minecraft:overworld", null);
        assertTrue(f.take("force", ClearBox.of(0, 50, 0, 4, 52, 4), false, true).startsWith("error: the guard refused: that box is 75 blocks"));
    }

    @Test
    void deadLeasesAreTakenAgain() {
        GuardCore g = guard(true);
        LeaseSet l = new LeaseSet(g, "tok", "minecraft:overworld", null);
        assertNull(l.take("digging", ClearBox.of(0, 50, 0, 3, 52, 3), false, false));
        assertNull(l.placeLease(1, 53, 1, "placing torch"));
        assertEquals(2, g.leases().size());
        g.tick(500);              // a long trip without a heartbeat: the guard dropped them
        assertTrue(g.leases().isEmpty());
        assertFalse(l.alive());
        assertNull(l.ensure());
        assertTrue(l.alive());
        assertEquals(1, g.leases().size(), "the break lease again; the torch's place lease comes back with the next torch");
        assertNull(l.placeLease(1, 53, 1, "placing torch"));
        assertEquals(2, g.leases().size());
    }

    // ---- placing ----

    @Test
    void placeSideFloorFirstNeverAChestOrATable() {
        FakeWorld w = new FakeWorld();            // stone up to 52
        Bot b = Bot.at(0.5, 53, 0.5);
        PlaceRules.Side s = PlaceRules.placeSide(w, 2, 53, 0, b.x(), b.eyeY(), b.z());
        assertEquals(ClearWorld.Face.UP, s.face(), "the floor first");
        w.set(2, 52, 0, "chest");
        w.set(3, 53, 0, "crafting_table");
        w.set(1, 53, 0, "stone");
        s = PlaceRules.placeSide(w, 2, 53, 0, b.x(), b.eyeY(), b.z());
        assertEquals(new PlaceRules.Side(-1, 0, 0, ClearWorld.Face.EAST), s, "not the chest under it, not the table: the stone beside it");
        assertNull(PlaceRules.placeSide(w, 20, 53, 0, b.x(), b.eyeY(), b.z()), "out of reach");
    }

    @Test
    void standSpotNeverPutsTheBodyInTheCell() {
        FakeWorld w = new FakeWorld();
        Bot far = Bot.at(10.5, 53, 0.5);
        Pos cell = new Pos(0, 53, 0);
        ClearGrid.Spot s = PlaceRules.standFor(w, cell, far);
        assertNotNull(s);
        assertFalse(PlaceRules.bodyIn(s.x(), s.y(), s.z(), cell));
        assertNotNull(PlaceRules.placeSide(w, 0, 53, 0, s.eyeX(), s.eyeY(), s.eyeZ()));
        assertTrue(PlaceRules.bodyIn(0, 53, 0, cell) && PlaceRules.bodyIn(0, 52, 0, cell) && !PlaceRules.bodyIn(0, 54, 0, cell));
    }

    // ---- the bot's own crafting table (18a) ----

    @Test
    void tableChoice() {
        assertEquals(PlaceRules.TableChoice.USE_NEAR, PlaceRules.tableChoice(12.0, false, false));
        assertEquals(PlaceRules.TableChoice.USE_NEAR, PlaceRules.tableChoice(24.0, true, true));
        // the owner's case: the nearest table 74 blocks away, 15 oak logs in the bag
        assertEquals(PlaceRules.TableChoice.CRAFT_AND_PLACE, PlaceRules.tableChoice(74.3, false, true));
        assertEquals(PlaceRules.TableChoice.PLACE_CARRIED, PlaceRules.tableChoice(74.3, true, true));
        assertEquals(PlaceRules.TableChoice.CRAFT_AND_PLACE, PlaceRules.tableChoice(null, false, true));
        assertEquals(PlaceRules.TableChoice.WALK_FAR, PlaceRules.tableChoice(74.3, false, false));
        assertTrue(PlaceRules.wood("minecraft:oak_log") && PlaceRules.wood("minecraft:spruce_planks") && !PlaceRules.wood("minecraft:stick"));
    }

    @Test
    void tableSpotNextToTheBotOutsideTheBox() {
        FakeWorld w = new FakeWorld();
        Bot b = Bot.at(0.5, 53, 0.5);
        Pos p = PlaceRules.tableSpot(w, b, null);
        assertEquals(new Pos(1, 53, 0), p, "a side, at its feet");
        // the box covers x >= 1: a spot outside it
        p = PlaceRules.tableSpot(w, b, ClearBox.of(1, 40, -5, 10, 60, 5));
        assertEquals(new Pos(-1, 53, 0), p);
        // never next to water
        w.set(-1, 54, 0, "water");
        p = PlaceRules.tableSpot(w, b, ClearBox.of(1, 40, -5, 10, 60, 5));
        assertEquals(new Pos(0, 53, 1), p, "not the cell under the water");
        // the bot on a 1x1 pillar: nothing to put it on -> none
        FakeWorld air = new FakeWorld();
        air.fill(-3, 3, 40, 60, -3, 3, "air");
        air.set(0, 52, 0, "stone");
        assertNull(PlaceRules.tableSpot(air, b, null));
        assertEquals("picking up my crafting table at 1 53 0", PlaceRules.pickupLabel(new Pos(1, 53, 0)));
    }

    @Test
    void picksBeforeABigDig() {
        assertEquals(0, PlaceRules.picksToRestock(300, 0, null), "small dig");
        assertEquals(3, PlaceRules.picksToRestock(4352, 0, null));
        assertEquals(3, PlaceRules.picksToRestock(4352, 2, null));
        assertEquals(0, PlaceRules.picksToRestock(4352, 3, null));
        assertEquals(5, PlaceRules.picksToRestock(4352, 3, 5), "supplies say 5");
    }
}
