package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whole clears driven offline (ClearDriver) over the sim's worlds: the zone clear on the hillside ("hill", "bumpy",
 * "lowtools") and the dig checks of the "safety" scenario. The expected reports are the bridge's own, taken from
 * runs of test/sim.js.
 */
class ClearRunTest {
    static final Set<String> NEXT_TO_WATER = Set.of("-6 55 145", "-5 54 145", "-5 55 144", "-5 55 146", "-4 55 145");

    static ClearDriver hillDriver(boolean bumpy, int pickDur) {
        return new ClearDriver(SimWorlds.hill(bumpy), SimWorlds.home()).simInventory(pickDur);
    }

    @Test
    void hill() {
        ClearDriver d = hillDriver(false, 100000);
        assertEquals(2871, SimWorlds.countLeft(d.w));
        ClearJob job = ClearJob.start(new ClearJob.Options(), SimWorlds.ZONE, d.ores);
        assertEquals("started: clearing the zone (zone -26 53 164 to -2 58 140 (25x6x25)), top down, one block at a time", job.startedMessage(SimWorlds.ZONE_TEXT));
        String msg = d.run(job);
        assertEquals("ok: finished clearing the zone - broke 2864 blocks; 5 left, e.g. -6 55 145 next to water/lava, -5 54 145 next to water/lava, "
                + "-5 55 144 next to water/lava; 1 ores left in place for you (PM \"ores\")", msg);
        // left: the chest, the 5 blocks next to the water, the iron ore (listed); the water itself
        assertEquals(7, SimWorlds.countLeft(d.w));
        assertEquals(NEXT_TO_WATER, job.skip.keySet());
        assertEquals("chest", d.w.get(-20, 54, 158));
        assertEquals("water", d.w.get(-5, 55, 145));
        assertEquals("iron_ore", d.w.get(-15, 55, 150));
        assertEquals("iron_ore", d.ores.ores.get("-15 55 150"));
        assertEquals(0, d.toolBreaks);
        assertEquals(0, job.fails.size());
    }

    @Test
    void bumpy() {
        ClearDriver d = hillDriver(true, 100000);
        assertEquals(2863, SimWorlds.countLeft(d.w));
        String msg = d.run(ClearJob.start(new ClearJob.Options(), SimWorlds.ZONE, d.ores));
        assertTrue(msg.startsWith("ok: finished clearing the zone - broke 2856 blocks; 5 left, e.g. "), msg);
        assertTrue(msg.endsWith("; 1 ores left in place for you (PM \"ores\")"), msg);
        assertEquals(7, SimWorlds.countLeft(d.w));
    }

    /** "lowtools": pickaxes that last 40 blocks; making more fails (the sim has no EMI), so the clear stops. */
    @Test
    void lowtools() {
        ClearDriver d = hillDriver(false, 40);
        d.crafter = craft -> "error: I don't know an item called " + craft.replaceFirst(" \\d+$", "");
        String msg = d.run(ClearJob.start(new ClearJob.Options(), SimWorlds.ZONE, d.ores));
        assertEquals(2, d.toolBreaks);
        assertEquals(List.of("minecraft:stone_pickaxe 3 -> error: I don't know an item called minecraft:stone_pickaxe",
                "leafscopperbackport:copper_pickaxe 3 -> error: I don't know an item called leafscopperbackport:copper_pickaxe"), d.crafts);
        assertEquals("stopped: out of pickaxes and I can't make more (I don't know an item called leafscopperbackport:copper_pickaxe) - broke 305 blocks; "
                + "2564 left; 1 ores left in place for you (PM \"ores\")", msg);
    }

    /** lowtools with a crafter that works: 3 stone pickaxes at a time, twice; the third time it stops. */
    @Test
    void lowtoolsCraftingPickaxes() {
        ClearDriver d = hillDriver(false, 40);
        d.craftedDur = 30;
        d.crafter = craft -> "started: crafting " + craft;
        String msg = d.run(ClearJob.start(new ClearJob.Options(), SimWorlds.ZONE, d.ores));
        assertEquals(List.of("minecraft:stone_pickaxe 3 -> started: crafting minecraft:stone_pickaxe 3",
                "minecraft:stone_pickaxe 3 -> started: crafting minecraft:stone_pickaxe 3"), d.crafts, "stone first, again stone (the last pick was stone)");
        assertEquals(8, d.toolBreaks, "2 copper and 6 stone pickaxes worn out");
        assertTrue(msg.startsWith("stopped: out of pickaxes (stone needs one) - broke "), msg);
    }

    @Test
    void cheapestPickaxeFirst() {
        ClearDriver d = new ClearDriver(new FakeWorld(), Bot.at(0.5, 53, 0.5));
        d.items[0] = new ClearDriver.Item("minecraft:iron_pickaxe", 6, 1000);
        d.items[12] = new ClearDriver.Item("minecraft:stone_pickaxe", 4, 2);
        d.items[13] = new ClearDriver.Item("minecraft:diamond_pickaxe", 8, 1000);
        String msg = d.clear(new ClearJob.Options().box(ClearBox.of(-1, 52, -1, 1, 52, 1)).label("digging the floor"));
        assertEquals("ok: done digging the floor - broke 9 blocks", msg);
        assertEquals(1, d.toolBreaks, "the stone pickaxe went first");
        assertEquals(1000 - 7, d.items[0].dur, "then iron, before diamond");
        assertEquals(1000, d.items[13].dur);
    }

    /** The "safety" scenario's clear checks: built blocks left alone and reported, corridors, dig ... force. */
    @Test
    void safetyDigs() {
        FakeWorld w = SimWorlds.hill(false);
        ClearDriver d = new ClearDriver(w, SimWorlds.home()).simInventory(100000);
        // the clear engine leaves built blocks alone and says so
        w.set(-11, 52, 170, "oak_planks");
        w.set(-10, 52, 171, "glass");
        String st = d.clear(new ClearJob.Options().box(ClearBox.of(-12, 52, 169, -10, 52, 171)).label("digging -12 52 169 to -10 52 171"));
        assertEquals("ok: done digging -12 52 169 to -10 52 171 - broke 7 blocks; left 2 built blocks alone (e.g. -11 52 170 oak_planks)", st);
        assertEquals("oak_planks", w.get(-11, 52, 170));
        assertEquals("glass", w.get(-10, 52, 171));
        assertEquals("air", w.get(-12, 52, 169));
        assertEquals("air", w.get(-10, 52, 169));
        Bot home = SimWorlds.home();
        d.moveTo(home.x(), home.y(), home.z());
        d.fall();
        // a corridor (mustFinish) over moss carpet is fine
        w.set(-12, 53, 169, "moss_carpet");
        w.set(-12, 54, 169, "stone");
        st = d.clear(new ClearJob.Options().box(ClearBox.of(-12, 53, 169, -12, 54, 169)).mustFinish(true).label("test corridor"));
        assertEquals("ok: done test corridor - broke 1 blocks; left 1 built block alone (e.g. -12 53 169 moss_carpet)", st);
        assertEquals("moss_carpet", w.get(-12, 53, 169));
        // planks block it, and the message says how to clear them
        w.set(-12, 53, 171, "oak_planks");
        w.set(-12, 54, 171, "stone");
        st = d.clear(new ClearJob.Options().box(ClearBox.of(-12, 53, 171, -12, 54, 171)).mustFinish(true).label("test corridor"));
        assertEquals("stopped: the mine corridor is blocked at -12 53 171 (oak_planks: a built block I don't break - if it's a mineshaft, "
                + "PM dig -12 53 171 -12 53 171 force) - broke 1 blocks; left 1 built block alone (e.g. -12 53 171 oak_planks)", st);
        // dig ... force breaks the planks
        ClearJob force = ClearJob.start(new ClearJob.Options().box(ClearBox.of(-12, 53, 171, -12, 53, 171)).force(true)
                .label("digging -12 53 171 to -12 53 171 (force)"), null, d.ores);
        assertEquals("started: digging -12 53 171 to -12 53 171 (force), top down, one block at a time", force.startedMessage(""));
        st = d.run(force);
        assertEquals("ok: done digging -12 53 171 to -12 53 171 (force) - broke 1 blocks", st);
        assertEquals("air", w.get(-12, 53, 171));
    }

    @Test
    void aSoftJobGivesUpWithoutFailing() {
        // nothing reachable: 8 failed tries in a row would stop a normal clear; a soft one reports "ok: gave up"
        ClearJob j = ClearJob.start(new ClearJob.Options().box(ClearBox.of(0, 52, 0, 0, 52, 0)).soft(true).label("mining 1 ore blocks next to branch 9"), null, null);
        j.consecFails = ClearEngine.MAX_CONSEC_FAILS;
        assertEquals("ok: gave up mining 1 ore blocks next to branch 9 (stuck - 8 tries in a row where I couldn't reach anything) - broke 0 blocks",
                ClearEngine.finishMessage(new FakeWorld(), j, ClearEngine.STUCK));
    }
}
