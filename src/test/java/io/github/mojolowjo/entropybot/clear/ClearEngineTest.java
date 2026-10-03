package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The clear engine's rules, one function at a time (the bridge's clearableState, pickReachable, sightOf, breakHazard ...). */
class ClearEngineTest {
    static ClearJob job(ClearBox box) {
        return ClearJob.start(new ClearJob.Options().box(box), null, new OreBook.Simple());
    }

    // ---- what may be broken ----

    @Test
    void clearableStateRules() {
        FakeWorld w = new FakeWorld();
        w.set(0, 60, 0, "chest");
        w.set(1, 60, 0, "water");
        w.set(2, 60, 0, "bedrock");
        w.set(3, 60, 0, "oak_planks");
        w.set(4, 60, 0, "obsidian");
        w.avoid.add("obsidian");
        w.set(5, 60, 0, "stone");
        w.set(6, 60, 0, "iron_ore");
        assertFalse(ClearEngine.clearableState(w, 0, 61, 0, false), "air");
        assertFalse(ClearEngine.clearableState(w, 0, 60, 0, true), "a chest, even forced");
        assertFalse(ClearEngine.clearableState(w, 1, 60, 0, false), "water");
        assertFalse(ClearEngine.clearableState(w, 2, 60, 0, true), "bedrock");
        assertFalse(ClearEngine.clearableState(w, 3, 60, 0, false), "planks are built");
        assertTrue(ClearEngine.clearableState(w, 3, 60, 0, true), "... unless forced");
        assertFalse(ClearEngine.clearableState(w, 4, 60, 0, false), "Baritone's avoid list");
        assertTrue(ClearEngine.clearableState(w, 5, 60, 0, false));
        assertTrue(ClearEngine.clearableState(w, 6, 60, 0, false), "ores are breakable (keepOres decides)");
    }

    @Test
    void protectedListMatchesTheSafetyScenario() {
        FakeWorld w = new FakeWorld();
        String[] prot = { "chest", "barrel", "furnace", "white_bed", "oak_sign", "oak_planks", "oak_stairs", "glass", "torch", "wall_torch", "rail",
                "stone_bricks", "crafting_table", "mysticalagriculture:inferium_crop", "sophisticatedstorage:chest", "iron_block",
                "smithing_table", "stripped_oak_log", "waxed_cut_copper", "moss_carpet" };
        String[] free = { "stone", "dirt", "iron_ore", "deepslate_iron_ore", "coal_ore", "diamond_ore", "oritech:nickel_ore", "sand", "gravel",
                "cobblestone", "smooth_basalt", "short_grass", "raw_iron_block", "bone_block" };
        List<String> bad = new ArrayList<>();
        for (String n : prot) { w.set(0, 70, 0, n); if (!w.builtBlock(0, 70, 0)) bad.add(n); }
        for (String n : free) { w.set(0, 70, 0, n); if (w.builtBlock(0, 70, 0)) bad.add(n); }
        assertEquals(List.of(), bad);
        // the id pattern alone (the building blocks without a block entity)
        assertTrue(ClearRules.builtId("oak_planks"));
        assertTrue(ClearRules.builtId("minecraft:wall_torch"));
        assertFalse(ClearRules.builtId("minecraft:smooth_basalt"));
        assertFalse(ClearRules.builtId("raw_iron_block"));
    }

    @Test
    void namesAndFallingBlocks() {
        assertEquals("stone", ClearRules.blockName("minecraft:stone"));
        assertEquals("oritech:nickel_ore", ClearRules.blockName("oritech:nickel_ore"));
        for (String n : new String[] { "gravel", "sand", "red_sand", "suspicious_sand", "white_concrete_powder", "anvil", "chipped_anvil", "mod:gravel" }) {
            assertTrue(ClearRules.falling(n), n);
        }
        for (String n : new String[] { "stone", "sandstone", "gravel_ore", "dirt" }) assertFalse(ClearRules.falling(n), n);
        assertTrue(ClearRules.hazard("lava"));
        assertTrue(ClearRules.hazard("magma_block"));
        assertTrue(ClearRules.hazard("sweet_berry_bush"));
        assertFalse(ClearRules.hazard("stone"));
    }

    // ---- sight ----

    @Test
    void sightOfSeesOpenFacesOnly() {
        FakeWorld w = new FakeWorld();
        // standing on the stone at y 52, the eye at 54.62: the floor block 2 east is visible from above
        double ex = 0.5, ey = 53 + Bot.EYE, ez = 0.5;
        ClearEngine.Sight s = ClearEngine.sightOf(w, ex, ey, ez, 2, 52, 0);
        assertNotNull(s);
        assertEquals(ClearWorld.Face.UP, s.face());
        // a buried block can't be seen: all its faces are against full blocks
        assertNull(ClearEngine.sightOf(w, ex, ey, ez, 2, 51, 0));
        // a wall in the way
        w.set(2, 53, 0, "stone"); w.set(2, 54, 0, "stone"); w.set(3, 53, 0, "stone");
        assertNotNull(ClearEngine.sightOf(w, ex, ey, ez, 2, 53, 0), "the wall itself");
        assertNull(ClearEngine.sightOf(w, ex, ey, ez, 3, 53, 0), "a block behind the wall");
        // a torch doesn't block the ray, nor does it hide a face
        w.set(1, 53, 0, "torch");
        assertNotNull(ClearEngine.sightOf(w, ex, ey, ez, 2, 53, 0));
    }

    @Test
    void nextToLiquidIsAboveAndBesideNotBelow() {
        FakeWorld w = new FakeWorld();
        w.set(0, 50, 0, "water");
        assertTrue(ClearEngine.nextToLiquid(w, 0, 49, 0), "water above");
        assertTrue(ClearEngine.nextToLiquid(w, 1, 50, 0), "beside");
        assertFalse(ClearEngine.nextToLiquid(w, 0, 51, 0), "below it doesn't count");
        w.set(5, 50, 5, "lava");
        assertTrue(ClearEngine.nextToLiquid(w, 5, 50, 4));
    }

    @Test
    void breakHazards() {
        FakeWorld w = new FakeWorld();
        Bot bot = Bot.at(0.5, 53, 0.5);
        ClearJob j = job(ClearBox.of(-3, 50, -3, 3, 56, 3));
        w.set(2, 53, 0, "stone"); w.set(2, 54, 0, "water");
        assertEquals(new ClearEngine.Hazard("next to water/lava", null), ClearEngine.breakHazard(w, bot, j, 2, 53, 0));
        assertNull(ClearEngine.breakHazard(w, bot, j, 0, 52, 0), "its footing, with stone under it");
        w.set(0, 51, 0, "air");
        assertEquals("I'm standing on it", ClearEngine.breakHazard(w, bot, j, 0, 52, 0).temp());
        w.set(0, 55, 0, "stone"); w.set(0, 56, 0, "gravel");
        assertEquals("sand/gravel would fall on me", ClearEngine.breakHazard(w, bot, j, 0, 55, 0).temp());
        assertNull(ClearEngine.breakHazard(w, Bot.at(2.5, 53, 2.5), j, 0, 55, 0), "not over its head: fine");
        w.set(0, 51, 0, "stone");
        j.minStandY = 53;
        assertEquals("I'd drop below the walkway", ClearEngine.breakHazard(w, bot, j, 0, 52, 0).temp());
    }

    // ---- the next block ----

    @Test
    void picksHighestFirstThenNearestAndItsFootingLast() {
        FakeWorld w = new FakeWorld();
        // a 3x3 floor around the bot (y 52) and two blocks on it
        w.set(1, 53, 1, "dirt");
        w.set(-1, 53, 0, "dirt");
        Bot bot = Bot.at(0.5, 53, 0.5);
        ClearJob j = job(ClearBox.of(-1, 52, -1, 1, 53, 1));
        List<ClearEngine.Target> order = new ArrayList<>();
        for (ClearEngine.Target t; (t = ClearEngine.pickReachable(w, bot, j)) != null;) {
            order.add(t);
            w.set(t.x(), t.y(), t.z(), "air");
            if (t.under()) break;
        }
        assertEquals(53, order.get(0).y());
        assertEquals("-1 53 0", order.get(0).key(), "of the two on top, the nearer first");
        assertEquals(53, order.get(1).y());
        ClearEngine.Target last = order.get(order.size() - 1);
        assertTrue(last.under(), "its footing last");
        assertEquals("0 52 0", last.key());
        assertEquals(11, order.size(), "both blocks and the whole floor");
        // between the floor blocks: nearest first
        for (int i = 3; i < order.size() - 1; i++) assertTrue(order.get(i - 1).d2() <= order.get(i).d2() + 1e-9);
    }

    @Test
    void picksSkipOnlyListSkipsAndFails() {
        FakeWorld w = new FakeWorld();
        Bot bot = Bot.at(0.5, 53, 0.5);
        ClearJob j = ClearJob.start(new ClearJob.Options().only(List.of(new Pos(1, 52, 0), new Pos(-1, 52, 0))), null, null);
        assertEquals(ClearBox.of(-1, 52, 0, 1, 52, 0), j.box);
        ClearEngine.Target t = ClearEngine.pickReachable(w, bot, j);
        assertNotNull(t);
        assertTrue(t.key().equals("1 52 0") || t.key().equals("-1 52 0"));
        j.skip.put("1 52 0", "x");
        ClearEngine.failTarget(j, new Pos(-1, 52, 0), ClearEngine.COULD_NOT_REACH);
        assertNull(ClearEngine.pickReachable(w, bot, j), "skipped, and failed within the last 10 blocks");
        j.broken = 10;
        assertEquals("-1 52 0", ClearEngine.pickReachable(w, bot, j).key(), "a failed block is tried again 10 blocks later");
        for (int i = 0; i < 3; i++) ClearEngine.failTarget(j, new Pos(-1, 52, 0), "x");
        j.broken = 100;
        assertTrue(ClearEngine.clearFailed(j, "-1 52 0"), "4 fails: given up");
        assertEquals(4, j.consecFails);
        assertFalse(ClearEngine.retryRound(j), "the hopeless ones stay");
        assertTrue(j.retried);
    }

    @Test
    void retryRoundForgetsSoftFailsOnce() {
        ClearJob j = job(ClearBox.of(0, 0, 0, 1, 1, 1));
        ClearEngine.failTarget(j, new Pos(0, 0, 0), "a");
        ClearEngine.failTarget(j, new Pos(1, 1, 1), "b");
        for (int i = 0; i < 3; i++) ClearEngine.failTarget(j, new Pos(1, 1, 1), "b");
        assertTrue(ClearEngine.retryRound(j));
        assertEquals(List.of("1 1 1"), new ArrayList<>(j.fails.keySet()));
        ClearEngine.failTarget(j, new Pos(0, 0, 0), "a");
        assertFalse(ClearEngine.retryRound(j), "only once per job");
    }

    @Test
    void keepOresNotesThemAndCollectingMinesThem() {
        FakeWorld w = new FakeWorld();
        w.set(1, 53, 0, "iron_ore");
        Bot bot = Bot.at(0.5, 53, 0.5);
        ClearBox box = ClearBox.of(1, 53, 0, 1, 53, 0);
        OreBook.Simple book = new OreBook.Simple();
        ClearJob keep = ClearJob.start(new ClearJob.Options().box(box), null, book);
        assertNull(ClearEngine.pickReachable(w, bot, keep));
        assertEquals("iron_ore", book.ores.get("1 53 0"));
        assertEquals(1, keep.oresNoted);
        ClearEngine.pickReachable(w, bot, keep);
        assertEquals(1, keep.oresNoted, "noted once");
        ClearJob collect = ClearJob.start(new ClearJob.Options().box(box).collect(true), null, book);
        assertFalse(collect.keepOres);
        ClearEngine.Target t = ClearEngine.pickReachable(w, bot, collect);
        assertEquals("1 53 0", t.key());
        assertFalse(ClearEngine.onBroken(collect, t.pos(), false, "minecraft:iron_ore"));
        assertEquals(1, collect.oresMined);
        assertEquals(1, collect.oreTally.get("minecraft:iron_ore"));
        assertTrue(collect.memDirty, "the mined ore left the list");
        assertTrue(book.ores.isEmpty());
        ClearJob noKeep = ClearJob.start(new ClearJob.Options().box(box).keepOres(false), null, null);
        assertFalse(noKeep.keepOres);
        assertFalse(noKeep.collect);
    }

    @Test
    void builtBlocksAreNotedButNotTorches() {
        FakeWorld w = new FakeWorld();
        w.set(1, 53, 0, "oak_planks");
        w.set(-1, 53, 0, "torch");
        w.set(0, 53, 1, "chest");
        Bot bot = Bot.at(0.5, 53, 0.5);
        ClearJob j = job(ClearBox.of(-1, 53, 0, 1, 53, 1));
        assertNull(ClearEngine.pickReachable(w, bot, j));
        assertEquals(1, j.protectedCount);
        assertEquals("oak_planks", j.protectedLeft.get("1 53 0"));
        ClearJob f = ClearJob.start(new ClearJob.Options().box(ClearBox.of(-1, 53, 0, 1, 53, 1)).force(true), null, null);
        ClearEngine.Target t = ClearEngine.pickReachable(w, bot, f);
        assertNotNull(t, "force breaks building blocks");
        assertNotEquals("0 53 1", t.key(), "never a chest");
    }

    @Test
    void aBlockNextToWaterIsSkippedForGood() {
        FakeWorld w = new FakeWorld();
        w.set(1, 53, 0, "dirt");
        w.set(2, 53, 0, "water");
        Bot bot = Bot.at(0.5, 53, 0.5);
        ClearJob j = job(ClearBox.of(1, 53, 0, 1, 53, 0));
        assertNull(ClearEngine.pickReachable(w, bot, j));
        assertEquals("next to water/lava", j.skip.get("1 53 0"));
    }

    /** sim safety, "fix 6": a vein clear never breaks the block under its own feet below the walkway. */
    @Test
    void minStandYKeepsItsFooting() {
        FakeWorld w = SimWorlds.hill(false);
        Bot home = SimWorlds.home();
        int fx = (int) Math.floor(home.x()), fy = (int) Math.floor(home.y()), fz = (int) Math.floor(home.z());
        ClearJob j = ClearJob.start(new ClearJob.Options().only(List.of(new Pos(fx, fy - 1, fz))).minStandY(fy).soft(true).label("test"), null, null);
        assertNull(ClearEngine.pickReachable(w, home, j), "with minStandY the block underfoot is not picked");
        j.minStandY = null;
        ClearEngine.Target t = ClearEngine.pickReachable(w, home, j);
        assertNotNull(t, "(without it, it is)");
        assertTrue(t.under());
        assertEquals("-15 52 167", t.key());
        assertEquals(4.4944, t.d2(), 1e-9);
        assertEquals(new ClearEngine.Sight(ClearWorld.Face.UP, -14.5, 52.5, 167.5), t.sight());
        j.minStandY = fy;
        assertEquals(new ClearEngine.Hazard(null, "I'd drop below the walkway"), ClearEngine.breakHazard(w, home, j, fx, fy - 1, fz));
    }

    // ---- bookkeeping ----

    @Test
    void aBlockTheServerKeepsPuttingBackIsSkipped() {
        ClearJob j = job(ClearBox.of(0, 0, 0, 0, 0, 0));
        Pos p = new Pos(0, 0, 0);
        ClearEngine.onBroken(j, p, false, null);
        ClearEngine.onBroken(j, p, false, null);
        assertFalse(j.skip.containsKey("0 0 0"));
        ClearEngine.onBroken(j, p, false, null);
        assertEquals("the server keeps putting it back", j.skip.get("0 0 0"));
        ClearJob g = job(ClearBox.of(0, 0, 0, 0, 0, 0));
        for (int i = 0; i < 5; i++) ClearEngine.onBroken(g, p, true, null);
        assertTrue(g.skip.isEmpty(), "sand or gravel refilling the spot");
        assertEquals(5, g.broken);
        for (int i = 5; i < 249; i++) assertFalse(ClearEngine.onBroken(g, new Pos(i, 0, 0), false, null));
        assertTrue(ClearEngine.onBroken(g, new Pos(300, 0, 0), false, null), "every 250");
        assertEquals("clearing: 250 blocks broken so far", ClearEngine.milestoneText(g));
    }

    @Test
    void breakTimes() {
        assertTrue(ClearEngine.tooSlowToBreak(0));
        assertTrue(ClearEngine.tooSlowToBreak(1.0 / 601));
        assertFalse(ClearEngine.tooSlowToBreak(1.0 / 600));
        assertEquals(30 + 30, ClearEngine.breakLimit(1.0 / 30));
    }

    @Test
    void torchesGoOnDugOutDarkSpotsInReach() {
        FakeWorld w = new FakeWorld();
        Bot bot = Bot.at(0.5, 53, 0.5);
        Pos far = new Pos(9, 53, 0), lit = new Pos(1, 53, 0), notDug = new Pos(0, 52, 2), ok = new Pos(-2, 53, 0);
        ClearJob j = ClearJob.start(new ClearJob.Options().box(ClearBox.of(-3, 52, -3, 9, 54, 3)).torches(List.of(far, notDug, lit, ok)), null, null);
        assertEquals(ClearBox.of(-4, 51, -4, 10, 55, 4), j.torchLeaseBox());
        w.lit.add(FakeWorld.k(1, 53, 0));
        assertNull(ClearEngine.nextTorch(w, bot, j, true), "the lit spot gets none");
        assertEquals(1, j.torchesSkipped);
        assertEquals(ok, ClearEngine.nextTorch(w, bot, j, true));
        assertEquals(List.of(far, notDug), j.torches);
        assertNull(ClearEngine.nextTorch(w, bot, j, false));
        assertTrue(j.torches.isEmpty(), "no torches: the list is dropped");
        assertNull(job(ClearBox.of(0, 0, 0, 0, 0, 0)).torchLeaseBox());
    }

    @Test
    void jobBasics() {
        ClearJob z = ClearJob.start(new ClearJob.Options(), SimWorlds.ZONE, null);
        assertTrue(z.zone);
        assertEquals("started: clearing the zone (" + SimWorlds.ZONE_TEXT + "), top down, one block at a time", z.startedMessage(SimWorlds.ZONE_TEXT));
        assertTrue(z.keepOres);
        assertEquals("clearing the zone: starting (0 broken)", z.status("starting"));
        z.lastScanLeft = 10;
        z.broken = 4;
        z.brokenAtScan = 1;
        assertEquals("clearing the zone: x (4 broken, ~7 left)", z.status("x"));
        ClearJob d = ClearJob.start(new ClearJob.Options().box(ClearBox.of(1, 2, 3, 4, 5, 6)).label("digging 1 2 3 to 4 5 6"), null, null);
        assertEquals("started: digging 1 2 3 to 4 5 6, top down, one block at a time", d.startedMessage("zone"));
        assertThrows(IllegalArgumentException.class, () -> ClearJob.start(new ClearJob.Options(), null, null));
    }

    // ---- the end ----

    @Test
    void boxBlockedSaysWhatAndHow() {
        FakeWorld w = new FakeWorld();
        ClearBox b = ClearBox.of(0, 60, 0, 0, 61, 0);
        assertNull(ClearEngine.boxBlocked(w, b));
        w.set(0, 60, 0, "moss_carpet");
        assertNull(ClearEngine.boxBlocked(w, b), "a carpet is walked over");
        w.set(0, 61, 0, "water");
        assertEquals("0 61 0 (water)", ClearEngine.boxBlocked(w, b));
        w.set(0, 61, 0, "bedrock");
        assertEquals("0 61 0 (bedrock)", ClearEngine.boxBlocked(w, b));
        w.set(0, 61, 0, "oak_planks");
        assertEquals("0 61 0 (oak_planks: a built block I don't break - if it's a mineshaft, PM dig 0 61 0 0 61 0 force)", ClearEngine.boxBlocked(w, b));
        w.set(0, 61, 0, "chest");
        assertEquals("0 61 0 (chest)", ClearEngine.boxBlocked(w, b), "a chest gets no dig hint");
    }

    @Test
    void finishMessages() {
        FakeWorld w = new FakeWorld();
        ClearJob j = ClearJob.start(new ClearJob.Options().box(ClearBox.of(0, 60, 0, 2, 60, 0)).label("digging x").soft(true).collect(true), null, null);
        j.broken = 3;
        assertEquals("ok: gave up digging x (out of pickaxes) - broke 3 blocks", ClearEngine.finishMessage(w, j, "stopped: out of pickaxes"));
        j.oresMined = 2;
        assertEquals("ok: done digging x - broke 3 blocks; 2 ores mined", ClearEngine.finishMessage(w, j, null));
        // left blocks and examples: skips first, then fails, then nowhere to stand; only ones still there
        ClearJob k = job(ClearBox.of(0, 60, 0, 5, 60, 0));
        w.set(0, 60, 0, "stone"); w.set(1, 60, 0, "stone"); w.set(2, 60, 0, "stone"); w.set(3, 60, 0, "stone");
        k.lastScanLeft = 4;
        k.noSpot.add("3 60 0");
        ClearEngine.failTarget(k, new Pos(2, 60, 0), "couldn't reach it");
        k.skip.put("5 60 0", "next to water/lava");          // gone since: not an example
        k.skip.put("1 60 0", "next to water/lava");
        assertEquals("ok: finished clearing the zone - broke 0 blocks; 4 left, e.g. 1 60 0 next to water/lava, 2 60 0 couldn't reach it, "
                + "3 60 0 out of reach (nowhere to stand close enough)", ClearEngine.finishMessage(w, k, null));
        // built blocks: one, many, 200+
        ClearJob p = job(ClearBox.of(0, 70, 0, 0, 70, 0));
        p.protectedLeft.put("1 2 3", "glass");
        p.protectedCount = 1;
        assertEquals("ok: done clearing the zone - broke 0 blocks; left 1 built block alone (e.g. 1 2 3 glass)", ClearEngine.finishMessage(w, p, null));
        p.protectedCount = 200;
        assertTrue(ClearEngine.finishMessage(w, p, null).endsWith("; left 200+ built blocks alone (e.g. 1 2 3 glass)"));
        // ores showing in the walls are listed at the end
        ClearJob o = job(ClearBox.of(0, 60, 0, 0, 60, 0));
        w.set(0, 60, 0, "air");
        w.set(0, 59, 0, "coal_ore");
        assertEquals("ok: done clearing the zone - broke 0 blocks; 1 ores left in place for you (PM \"ores\")", ClearEngine.finishMessage(w, o, null));
        // a corridor that has to go all the way
        ClearJob c = ClearJob.start(new ClearJob.Options().box(ClearBox.of(0, 80, 0, 0, 80, 0)).mustFinish(true).label("digging the mine corridor (branch 2)"), null, null);
        w.set(0, 80, 0, "bedrock");
        assertEquals("stopped: the mine corridor is blocked at 0 80 0 (bedrock) - broke 0 blocks", ClearEngine.finishMessage(w, c, null));
    }
}
