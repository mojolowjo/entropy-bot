package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.guard.BreakPolicy.Action;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Ask;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.BlockFacts;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Fluid;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.RuleSet;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Snapshot;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Verdict;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Where;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The cost table of docs/MINING_PLAN.md 3.3 and the floor of 3.1, row by row. */
class BreakPolicyTest {
    static final String OW = "minecraft:overworld";
    static final String POLICY = "{\"areas\":[{\"name\":\"camp\",\"type\":\"main\",\"x1\":-20,\"z1\":-20,\"x2\":20,\"z2\":20,\"y1\":50,\"y2\":90},"
            + "{\"name\":\"pit\",\"type\":\"neutral\",\"x1\":100,\"z1\":100,\"x2\":140,\"z2\":140,\"y1\":0,\"y2\":120},"
            + "{\"name\":\"quarry\",\"type\":\"destroy\",\"x1\":200,\"z1\":200,\"x2\":240,\"z2\":240,\"y1\":0,\"y2\":120}],"
            + "\"protect\":[{\"name\":\"shrine\",\"x1\":300,\"y1\":40,\"z1\":300,\"x2\":310,\"y2\":80,\"z2\":310}]}";

    static final Snapshot SP = Snapshot.of(Policy.parse(POLICY), false);
    static final Snapshot MP = Snapshot.of(Policy.parse(POLICY), true);

    static Verdict brk(Snapshot s, Intent in, int x, int y, int z, BlockFacts f, Where me) {
        return BreakPolicy.judge(s, new Ask(Action.BREAK, in, OW, x, y, z, f, me));
    }

    static Verdict brk(Snapshot s, Intent in, int x, int y, int z, BlockFacts f) { return brk(s, in, x, y, z, f, Where.DRY); }

    static Verdict place(Snapshot s, Intent in, int x, int y, int z, BlockFacts f) {
        return BreakPolicy.judge(s, new Ask(Action.PLACE, in, OW, x, y, z, f, Where.DRY));
    }

    // ---- rule sets by place

    @Test
    void ruleSetFollowsTheAreaAndTheConnection() {
        assertSame(RuleSet.MAIN, SP.ruleSetAt(OW, 0, 60, 0));
        assertSame(RuleSet.NEUTRAL, SP.ruleSetAt(OW, 120, 60, 120));
        assertSame(RuleSet.DESTROY, SP.ruleSetAt(OW, 220, 60, 220));
        assertSame(RuleSet.SAFE, SP.ruleSetAt(OW, 305, 60, 305), "a protect box is safe");
        assertSame(RuleSet.OUTSIDE_SINGLEPLAYER, SP.ruleSetAt(OW, 1000, 60, 1000));
        assertSame(RuleSet.OUTSIDE_MULTIPLAYER, MP.ruleSetAt(OW, 1000, 60, 1000), "a server starts expensive outside, no toggle");
        assertSame(RuleSet.OUTSIDE_SINGLEPLAYER, SP.ruleSetAt(OW, 0, 95, 0), "above the camp box = outside");
    }

    @Test
    void presetsMatchTheTable() {
        assertEquals(BreakPolicy.Natural.NEVER, RuleSet.SAFE.breakNatural());
        assertFalse(RuleSet.MAIN.useResources(), "ores and logs in the camp stay (owner's default)");
        assertEquals(BreakPolicy.Natural.TRAVEL_EXPENSIVE, RuleSet.MAIN.breakNatural());
        assertTrue(RuleSet.NEUTRAL.useResources());
        assertFalse(RuleSet.DESTROY.restore());
        assertEquals(6, RuleSet.OUTSIDE_SINGLEPLAYER.travelMultiplier(), "travel x6");
        assertEquals(10, RuleSet.OUTSIDE_MULTIPLAYER.travelMultiplier());
        assertEquals(24, BreakPolicy.Costs.TRAVEL_CAP);
        assertNull(RuleSet.preset(null));
        assertTrue(RuleSet.NEUTRAL.describe().startsWith("neutral: natural lenient, resources yes"));
    }

    // ---- the floor

    @Test
    void floorHoldsForEveryIntent() {
        for (Intent in : new Intent[]{Intent.TRAVEL, Intent.MINE, Intent.DIG_OUT, Intent.BUILD}) {
            assertEquals("block entity (a chest, machine, bed...)", brk(SP, in, 1000, 60, 1000, BlockFacts.STONE.withBlockEntity(true)).reason(), in.name());
            assertEquals("built block", brk(SP, in, 1000, 60, 1000, BlockFacts.STONE.withBuilt(true)).reason(), in.name());
            assertEquals("unbreakable", brk(SP, in, 1000, 60, 1000, BlockFacts.STONE.withUnbreakable(true)).reason(), in.name());
            assertEquals("next to lava", brk(SP, in, 1000, 60, 1000, BlockFacts.STONE.withSides(Fluid.LAVA)).reason(), in.name());
            assertEquals("next to lava", brk(SP, in, 1000, 60, 1000, BlockFacts.STONE.withAbove(Fluid.LAVA)).reason(), in.name());
            assertEquals("not loaded", brk(SP, in, 1000, 60, 1000, BlockFacts.UNKNOWN).reason(), in.name());
            Verdict safe = brk(SP, in, 305, 60, 305, BlockFacts.STONE);
            assertFalse(safe.allowed());
            assertTrue(safe.floor(), "a protect box is floor");
            assertTrue(brk(SP, in, 1000, 60, 1000, BlockFacts.STONE.withSides(Fluid.LAVA)).floor());
        }
        assertEquals("no breaking in minecraft:the_nether", BreakPolicy.judge(SP, new Ask(Action.BREAK, Intent.MINE, "minecraft:the_nether", 0, 60, 0, BlockFacts.STONE, Where.DRY)).reason());
        assertFalse(BreakPolicy.judge(null, new Ask(Action.BREAK, Intent.TRAVEL, OW, 0, 60, 0, BlockFacts.STONE, Where.DRY)).allowed(), "no snapshot: fail closed");
        assertFalse(BreakPolicy.judge(SP, null).allowed());
    }

    // ---- TRAVEL

    @Test
    void travelOutsideIsLenientAtTheMultiplier() {
        Verdict v = brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE);
        assertTrue(v.allowed());
        assertEquals(6, v.multiplier());
        assertEquals("travel", v.rule());
        Verdict mp = brk(MP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE);
        assertTrue(mp.allowed(), "a server: natural ground is still travelled through");
        assertEquals(10, mp.multiplier());
        Verdict camp = brk(SP, Intent.TRAVEL, 0, 60, 0, BlockFacts.STONE);
        assertEquals(10, camp.multiplier(), "the camp is expensive");
        assertEquals(6, brk(SP, Intent.TRAVEL, 120, 60, 120, BlockFacts.STONE).multiplier(), "a neutral work area: default");
    }

    @Test
    void travelWaterRules() {
        Verdict side = brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withSides(Fluid.WATER));
        assertTrue(side.allowed(), "next to water: allowed, dearer");
        assertEquals(12, side.multiplier());
        assertEquals("travel+water", side.rule());
        Verdict down = brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withAbove(Fluid.WATER), Where.WET);
        assertFalse(down.allowed());
        assertEquals("digging down from in the water", down.reason());
        assertFalse(down.floor(), "a rule, not the floor");
        Verdict pond = brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withAbove(Fluid.WATER), Where.DRY);
        assertTrue(pond.allowed(), "from dry ground a cell under a pond is a wet side, not a dive (review)");
        assertEquals(12, pond.multiplier());
        assertEquals(20, brk(SP, Intent.TRAVEL, 0, 60, 0, BlockFacts.STONE.withSides(Fluid.WATER)).multiplier(), "camp x10 and water x2");
        // a waterlogged block is not a liquid in the cell (the adapter maps only LiquidBlock there); water above it is a wet side
        assertTrue(brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withAbove(Fluid.WATER).withSides(Fluid.WATER)).allowed());
        assertEquals("a falling block above", brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withFallingAbove(true)).reason());
        assertEquals("a liquid is not broken", brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withInCell(Fluid.WATER)).reason());
    }

    @Test
    void travelCapGrowsThenDenies() {
        assertEquals(6, brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE, new Where(false, 0)).multiplier());
        assertEquals(12, brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE, new Where(false, 12)).multiplier(), 1e-9, "halfway: double");
        Verdict at = brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE, new Where(false, 24));
        assertFalse(at.allowed());
        assertEquals("travel break cap 24 reached on this walk", at.reason());
        assertTrue(brk(SP, Intent.MINE, 1000, 60, 1000, BlockFacts.STONE, new Where(false, 24)).allowed(), "the cap is travel's only");
    }

    @Test
    void travelNeverThroughMyHouseWall() {
        // a house: a main area 5x5x4 inside the camp; its shell is houseCell
        Snapshot s = Snapshot.of(Policy.parse("{\"areas\":[{\"name\":\"house\",\"type\":\"main\",\"x1\":0,\"z1\":0,\"x2\":4,\"z2\":4,\"y1\":60,\"y2\":64}]}"), false);
        Verdict wall = brk(s, Intent.TRAVEL, 0, 62, 2, BlockFacts.STONE);
        assertFalse(wall.allowed());
        assertEquals("house", wall.rule());
        assertTrue(brk(s, Intent.BUILD, 0, 62, 2, BlockFacts.STONE).allowed(), "a build may open the wall");
        assertTrue(brk(s, Intent.TRAVEL, 2, 62, 2, BlockFacts.STONE).allowed(), "inside the house, not the shell");
    }

    // ---- MINE

    @Test
    void mineIsCheapAndPrefersOre() {
        assertEquals(1, brk(SP, Intent.MINE, 1000, 60, 1000, BlockFacts.STONE).multiplier());
        Verdict ore = brk(SP, Intent.MINE, 1000, 60, 1000, BlockFacts.STONE.withOre(true));
        assertEquals(0.5, ore.multiplier());
        assertEquals("mine+ore", ore.rule());
        assertTrue(brk(SP, Intent.MINE, 1000, 60, 1000, BlockFacts.STONE.withSides(Fluid.WATER)).allowed(), "mining next to water is fine (0.36.8)");
        assertEquals(1, brk(SP, Intent.MINE, 1000, 60, 1000, BlockFacts.STONE.withSides(Fluid.WATER)).multiplier());
        assertEquals("digging down from in the water", brk(SP, Intent.MINE, 1000, 60, 1000, BlockFacts.STONE.withAbove(Fluid.WATER), Where.WET).reason());
        assertTrue(brk(SP, Intent.MINE, 1000, 60, 1000, BlockFacts.STONE.withAbove(Fluid.WATER), Where.DRY).allowed(), "a tunnel under a pond from dry ground");
        assertTrue(brk(SP, Intent.MINE, 1000, 60, 1000, BlockFacts.STONE.withFallingAbove(true)).allowed(), "Baritone adds the stack's cost");
    }

    @Test
    void campKeepsItsResources() {
        Verdict v = brk(SP, Intent.MINE, 0, 60, 0, BlockFacts.STONE.withOre(true));
        assertFalse(v.allowed());
        assertEquals("no resources taken in main", v.reason());
        assertEquals("resources", v.rule());
        assertTrue(brk(SP, Intent.MINE, 0, 60, 0, BlockFacts.STONE).allowed(), "stone in the camp may be mined");
        assertTrue(brk(SP, Intent.TRAVEL, 0, 60, 0, BlockFacts.STONE.withOre(true)).allowed(), "travel through an ore is a travel break, not a resource taken");
        assertTrue(brk(SP, Intent.MINE, 120, 60, 120, BlockFacts.STONE.withOre(true)).allowed(), "a neutral area gives its ores");
    }

    // ---- DIG_OUT, BUILD, RESTORE, NONE

    @Test
    void digOutRules() {
        assertEquals(1, brk(SP, Intent.DIG_OUT, 1000, 60, 1000, BlockFacts.STONE).multiplier());
        assertTrue(brk(SP, Intent.DIG_OUT, 1000, 60, 1000, BlockFacts.STONE.withSides(Fluid.WATER), new Where(false, 0)).allowed(), "water next door is fine when dry");
        assertEquals("a block by water while I am in the water", brk(SP, Intent.DIG_OUT, 1000, 60, 1000, BlockFacts.STONE.withSides(Fluid.WATER), new Where(true, 0)).reason());
        assertEquals("digging down from in the water", brk(SP, Intent.DIG_OUT, 1000, 60, 1000, BlockFacts.STONE.withAbove(Fluid.WATER), Where.WET).reason());
        assertTrue(brk(SP, Intent.DIG_OUT, 1000, 60, 1000, BlockFacts.STONE.withAbove(Fluid.WATER), Where.DRY).allowed());
        assertEquals("a falling block above", brk(SP, Intent.DIG_OUT, 1000, 60, 1000, BlockFacts.STONE.withFallingAbove(true)).reason());
    }

    @Test
    void buildRestoreNone() {
        assertEquals(6, brk(SP, Intent.BUILD, 1000, 60, 1000, BlockFacts.STONE).multiplier(), "a build's path digs as travel");
        assertTrue(brk(SP, Intent.BUILD, 1000, 60, 1000, BlockFacts.STONE, new Where(false, 30)).allowed(), "a build's cells are not travel breaks: no cap");
        assertEquals(6, brk(SP, Intent.BUILD, 1000, 60, 1000, BlockFacts.STONE, new Where(false, 30)).multiplier());
        assertEquals("a restore only places", brk(SP, Intent.RESTORE, 1000, 60, 1000, BlockFacts.STONE).reason());
        Verdict none = brk(SP, Intent.NONE, 1000, 60, 1000, BlockFacts.STONE);
        assertEquals("walking only", none.reason());
        assertFalse(BreakPolicy.boxes(SP, Intent.NONE, OW, 1000, 60, 1000).allowed());
    }

    // ---- PLACE

    @Test
    void placing() {
        assertEquals("nothing but torches placed in outside (singleplayer, lenient)", place(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE).reason());
        Verdict torch = place(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withTorch(true));
        assertTrue(torch.allowed());
        assertEquals(3, torch.multiplier(), "travel places dearly");
        assertEquals(1, place(SP, Intent.MINE, 120, 60, 120, BlockFacts.STONE).multiplier(), "a work area places anything at Baritone's cost");
        assertEquals("next to lava", place(SP, Intent.MINE, 120, 60, 120, BlockFacts.STONE.withSides(Fluid.LAVA)).reason());
        assertEquals("next to lava", place(SP, Intent.MINE, 120, 60, 120, BlockFacts.STONE.withBelow(Fluid.LAVA)).reason(), "never a bridge over lava");
        assertEquals("next to lava", place(SP, Intent.MINE, 120, 60, 120, BlockFacts.STONE.withInCell(Fluid.LAVA)).reason());
        assertEquals("walking only", place(SP, Intent.NONE, 120, 60, 120, BlockFacts.STONE).reason());
        assertEquals("not loaded", place(SP, Intent.MINE, 120, 60, 120, BlockFacts.UNKNOWN).reason());
        assertTrue(place(SP, Intent.MINE, 305, 60, 305, BlockFacts.STONE).floor(), "nothing placed in a protect box");
        assertTrue(place(SP, Intent.RESTORE, 1000, 60, 1000, BlockFacts.STONE).allowed(), "the ledger puts back anywhere but the floor");
    }

    // ---- the facts-free part

    @Test
    void boxesAnswerWithoutFacts() {
        assertTrue(BreakPolicy.boxes(SP, Intent.TRAVEL, OW, 1000, 60, 1000).allowed());
        Verdict safe = BreakPolicy.boxes(SP, Intent.TRAVEL, OW, 305, 60, 305);
        assertFalse(safe.allowed());
        assertTrue(safe.floor());
        assertEquals("in shrine (safe)", safe.reason());
        assertFalse(BreakPolicy.boxes(null, Intent.TRAVEL, OW, 0, 0, 0).allowed());
        // a safe area is a protect box in the parsed policy: floor, named
        assertEquals("in s (safe)", BreakPolicy.boxes(Snapshot.of(Policy.parse("{\"areas\":[{\"name\":\"s\",\"type\":\"safe\",\"x1\":0,\"z1\":0,\"x2\":9,\"z2\":9}]}"), false),
                Intent.MINE, OW, 5, 60, 5).reason());
        // a rule set that never breaks (phase 3's nobreak) denies as a rule, not the floor
        Snapshot nb = new Snapshot(Policy.EMPTY, RuleSet.NOBREAK, false, 24);
        Verdict v = BreakPolicy.boxes(nb, Intent.TRAVEL, OW, 5, 60, 5);
        assertEquals("nothing broken or placed in nobreak", v.reason());
        assertFalse(v.floor());
    }

    @Test
    void everyBoxesDenyIsAJudgeDeny() {
        int[][] cells = {{1000, 60, 1000}, {305, 60, 305}, {0, 60, 0}, {120, 60, 120}, {5, 60, 5}};
        Snapshot nb = new Snapshot(Policy.parse(POLICY), RuleSet.NOBREAK, false, 24);
        for (Snapshot s : new Snapshot[]{SP, MP, nb}) for (Intent in : Intent.values()) for (int[] c : cells) {
            if (BreakPolicy.boxes(s, in, OW, c[0], c[1], c[2]).allowed()) continue;
            assertFalse(brk(s, in, c[0], c[1], c[2], BlockFacts.STONE).allowed(), in + " at " + c[0]);
        }
    }

    @Test
    void intentWords() {
        assertEquals(Intent.DIG_OUT, Intent.of("dig-out"));
        assertEquals(Intent.DIG_OUT, Intent.of("digout"));
        assertEquals(Intent.TRAVEL, Intent.of("Travel"));
        assertNull(Intent.of("fly"));
        assertEquals("allowed x6 (travel)", brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE).text());
        assertEquals("denied: built block (floor)", brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withBuilt(true)).text());
    }
}
