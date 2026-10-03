package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The tool choice (cheapest first) and noTool's ladder; the walking map, walk distances and spots (buildGrid ... planWalk). */
class ToolsGridTest {
    // ---- tools ----

    @Test
    void toolTiers() {
        assertEquals(1, Tools.toolTier("minecraft:wooden_pickaxe"));
        assertEquals(2, Tools.toolTier("minecraft:stone_pickaxe"));
        assertEquals(3, Tools.toolTier("leafscopperbackport:copper_pickaxe"));
        assertEquals(3, Tools.toolTier("minecraft:golden_shovel"));
        assertEquals(4, Tools.toolTier("minecraft:iron_pickaxe"));
        assertEquals(5, Tools.toolTier("minecraft:diamond_pickaxe"));
        assertEquals(6, Tools.toolTier("minecraft:netherite_pickaxe"));
        assertEquals(4, Tools.toolTier("mekanism:atomic_disassembler"), "unknown tools count as iron");
        assertEquals(4, Tools.toolTier("minecraft:redstone_iron_x"), "only at the start of the name");
    }

    @Test
    void theCheapestToolThatDoesTheJob() {
        List<Tools.Slot> inv = List.of(
                new Tools.Slot(0, "minecraft:diamond_pickaxe", true, 8),
                new Tools.Slot(1, "minecraft:iron_pickaxe", true, 6),
                new Tools.Slot(2, "minecraft:stone_pickaxe", true, 4),
                new Tools.Slot(3, "minecraft:diamond_sword", true, 1.5f),
                new Tools.Slot(4, "minecraft:stone_shovel", false, 1));
        assertEquals(2, Tools.choose(inv, true), "stone pickaxes wear out first");
        assertEquals(2, Tools.choose(inv, false));
        // a block only iron can harvest
        List<Tools.Slot> diamondOre = List.of(
                new Tools.Slot(0, "minecraft:diamond_pickaxe", true, 8),
                new Tools.Slot(1, "minecraft:iron_pickaxe", true, 6),
                new Tools.Slot(2, "minecraft:stone_pickaxe", false, 4));
        assertEquals(1, Tools.choose(diamondOre, true));
        // the fastest of equals
        assertEquals(7, Tools.choose(List.of(new Tools.Slot(5, "minecraft:iron_axe", true, 2), new Tools.Slot(7, "minecraft:iron_pickaxe", true, 6)), true));
        // swords never; no tool for a block that drops anyway is fine
        assertEquals(-1, Tools.choose(List.of(new Tools.Slot(3, "minecraft:iron_sword", true, 15)), false));
        assertTrue(Tools.okWithout(false));
        assertFalse(Tools.okWithout(true));
        // not a tool for it (speed 1): none
        assertEquals(-1, Tools.choose(List.of(new Tools.Slot(0, "minecraft:stone_pickaxe", false, 1)), false));
    }

    @Test
    void noToolLadder() {
        ClearJob j = ClearJob.start(new ClearJob.Options().box(ClearBox.of(0, 0, 0, 0, 0, 0)), null, null);
        assertEquals("needs a better tool (diamond_ore)", Tools.noTool(j, true, "diamond_ore").skip());
        j.lastPick = "leafscopperbackport:copper_pickaxe";
        Tools.NoTool a = Tools.noTool(j, false, "stone");
        assertEquals(List.of("minecraft:stone_pickaxe 3", "leafscopperbackport:copper_pickaxe 3"), a.craft());
        j.lastPick = "minecraft:stone_pickaxe";
        assertEquals(List.of("minecraft:stone_pickaxe 3"), Tools.noTool(j, false, "stone").craft());
        assertEquals("stopped: out of pickaxes (stone needs one)", Tools.noTool(j, false, "stone").stop());
        assertEquals("stopped: out of pickaxes and I can't make more (no table)", Tools.cantCraftMessage("error: no table"));
        assertEquals("stopped: out of pickaxes and making more failed (missing sticks)", Tools.craftFailedMessage("error: missing sticks"));
        assertEquals("out of pickaxes - making 3 stone_pickaxe, then back to clearing", Tools.craftingWhisper("minecraft:stone_pickaxe 3"));
    }

    // ---- the walking map ----

    /** A flat floor at y 52 (feet at 53) with a 1-high step, a 2-high wall and a 3-deep pit. */
    static FakeWorld terrain() {
        FakeWorld w = new FakeWorld();
        w.set(3, 53, 0, "stone");                                    // a step up: feet at 54 on it
        w.set(0, 53, 3, "stone"); w.set(0, 54, 3, "stone");          // a wall 2 high
        w.fill(-3, -3, 50, 52, 0, 0, "air");                         // a pit: stand at 50 on its floor
        w.set(5, 53, 0, "lava");                                     // never walked through
        return w;
    }

    @Test
    void walkDistancesStepUpDropAndWalls() {
        FakeWorld w = terrain();
        ClearBox box = ClearBox.of(-4, 50, -4, 6, 56, 6);
        Bot bot = Bot.at(0.5, 53, 0.5);
        ClearGrid g = ClearGrid.build(w, box, bot, null);
        int[] d = g.walkDistances(bot);
        assertEquals(0, d[g.idx(0, 53, 0)]);
        assertEquals(3, d[g.idx(3, 54, 0)], "a 1-block step up");
        assertEquals(-1, d[g.idx(3, 53, 0)], "not inside the step");
        assertEquals(3, d[g.idx(-3, 50, 0)], "a drop of 3 into the pit");
        assertTrue(d[g.idx(0, 55, 3)] < 0, "the 2-high wall can't be climbed");
        assertEquals(-1, d[g.idx(5, 54, 0)], "nothing stands on lava");
        assertTrue(g.stand(4, 53, 0));
        assertFalse(g.open(5, 53, 0) || g.solid(5, 53, 0), "lava is neither open nor solid");
        assertEquals(-1, g.idx(1000, 0, 0));
    }

    @Test
    void gridTargetsNotesAndOnlyList() {
        FakeWorld w = new FakeWorld();
        w.set(1, 53, 0, "dirt"); w.set(2, 53, 0, "oak_planks"); w.set(3, 53, 0, "iron_ore"); w.set(4, 53, 0, "chest");
        ClearBox box = ClearBox.of(1, 53, 0, 4, 53, 0);
        ClearJob j = ClearJob.start(new ClearJob.Options().box(box), null, null);
        ClearGrid g = ClearGrid.build(w, box, Bot.at(0.5, 53, 0.5), j);
        assertEquals(List.of(new Pos(1, 53, 0)), g.targets, "the ore is kept, the planks and the chest left");
        assertEquals("oak_planks", j.protectedLeft.get("2 53 0"));
        assertEquals(1, j.protectedCount, "a chest isn't listed as a built block");
        assertEquals(1, j.oresNoted);
        ClearJob only = ClearJob.start(new ClearJob.Options().only(List.of(new Pos(3, 53, 0))).collect(true), null, null);
        assertEquals(List.of(new Pos(3, 53, 0)), ClearGrid.build(w, only.box, Bot.at(0.5, 53, 0.5), only).targets);
    }

    @Test
    void bestSpotRules() {
        FakeWorld w = new FakeWorld();
        // the target on the floor 4 east; a spot just next to it is closest by walking
        Pos t = new Pos(4, 52, 0);
        ClearBox box = ClearBox.of(4, 52, 0, 4, 52, 0);
        ClearJob j = ClearJob.start(new ClearJob.Options().box(box), null, null);
        Bot bot = Bot.at(0.5, 53, 0.5);
        ClearGrid g = ClearGrid.build(w, box, bot, j);
        int[] d = g.walkDistances(bot);
        j.sightBudget = ClearGrid.SIGHT_BUDGET;
        ClearGrid.Spot s = ClearGrid.bestSpotFor(w, g, d, t, bot, j);
        assertNotNull(s);
        assertFalse(s.x() == 4 && s.z() == 0 && s.y() == 53, "never on the block itself");
        assertEquals(53, s.y());
        assertTrue(s.cost() < 1000);
        assertTrue(j.sightBudget < ClearGrid.SIGHT_BUDGET, "spends the sight budget");
        // a spot it failed to reach recently is avoided for 10 blocks
        j.badSpots.put(s.key(), 0);
        j.sightBudget = ClearGrid.SIGHT_BUDGET;
        ClearGrid.Spot s2 = ClearGrid.bestSpotFor(w, g, d, t, bot, j);
        assertNotEquals(s.key(), s2.key());
        j.broken = 10;
        assertEquals(s.key(), ClearGrid.bestSpotFor(w, g, d, t, bot, j).key());
        // a vein clear stays on the walkway
        j.minStandY = 54;
        assertNull(ClearGrid.bestSpotFor(w, g, d, t, bot, j), "no spot at 54 or above here");
        // no budget left: nothing
        j.minStandY = null;
        j.sightBudget = 0;
        assertNull(ClearGrid.bestSpotFor(w, g, d, t, bot, j));
    }

    @Test
    void notUnderSandAndNotInWater() {
        FakeWorld w = new FakeWorld();
        // a block in a 1-wide shaft's wall with gravel on top: never stand under it, and never in water
        Pos t = new Pos(0, 56, 0);
        w.set(0, 56, 0, "stone"); w.set(0, 57, 0, "gravel");
        w.set(1, 53, 0, "water");
        ClearBox box = ClearBox.of(0, 56, 0, 0, 56, 0);
        ClearJob j = ClearJob.start(new ClearJob.Options().box(box), null, null);
        Bot bot = Bot.at(0.5, 53, 1.5);
        ClearGrid g = ClearGrid.build(w, box, bot, j);
        int[] d = g.walkDistances(bot);
        j.sightBudget = 1000;
        ClearGrid.Spot s = ClearGrid.bestSpotFor(w, g, d, t, bot, j);
        assertNotNull(s);
        assertFalse(s.x() == 0 && s.z() == 0, "not under the gravel");
        assertFalse(s.x() == 1 && s.z() == 0 && s.y() == 53, "not in the water");
    }

    @Test
    void planWalkPicksTheNearestReachableAndRemembersTheRest() {
        FakeWorld w = new FakeWorld();
        // three blocks on the floor: one near, one far, one beside water
        w.set(6, 53, 0, "dirt");
        w.set(9, 53, 0, "dirt");
        w.set(9, 53, 4, "dirt"); w.set(9, 53, 5, "water");
        ClearBox box = ClearBox.of(6, 53, 0, 9, 53, 4);
        ClearJob j = ClearJob.start(new ClearJob.Options().box(box), null, null);
        Bot bot = Bot.at(0.5, 53, 0.5);
        j.broken = 7;
        ClearGrid.Plan p = ClearGrid.planWalk(w, bot, j);
        assertNotNull(p);
        assertEquals(new Pos(6, 53, 0), p.target(), "the nearest exposed block with a spot");
        assertEquals(new Pos(2, 53, 0), p.spot().pos(), "the nearest spot that sees it");
        assertEquals(2, p.spot().cost());
        assertEquals("next to water/lava", j.skip.get("9 53 4"));
        assertEquals(3, j.lastScanLeft, "every clearable block in the box counts as left");
        assertEquals(7, j.brokenAtScan);
        // a buried block (no open side) is never planned for; nowhere to stand close enough is remembered
        FakeWorld v = new FakeWorld();
        ClearJob k = ClearJob.start(new ClearJob.Options().box(ClearBox.of(30, 40, 0, 30, 40, 0)), null, null);
        assertNull(ClearGrid.planWalk(v, bot, k));
        assertTrue(k.noSpot.isEmpty());
        v.fill(30, 30, 41, 52, 0, 0, "air");                         // a deep narrow shaft above it
        v.fill(30, 30, 53, 60, 0, 0, "air");
        assertNull(ClearGrid.planWalk(v, bot, k));
        assertTrue(k.noSpot.contains("30 40 0"), "out of reach: the shaft is too deep to stand in sight of it");
    }
}
