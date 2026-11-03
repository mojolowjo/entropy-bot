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
        assertSame(RuleSet.BASE, SP.ruleSetAt(OW, 0, 60, 0));
        assertSame(RuleSet.WORK, SP.ruleSetAt(OW, 120, 60, 120));
        assertSame(RuleSet.DESTROY, SP.ruleSetAt(OW, 220, 60, 220));
        assertSame(RuleSet.NO_BREAK, SP.ruleSetAt(OW, 305, 60, 305), "a protect box is safe");
        assertSame(RuleSet.OUTSIDE_SINGLEPLAYER, SP.ruleSetAt(OW, 1000, 60, 1000));
        assertSame(RuleSet.OUTSIDE_MULTIPLAYER, MP.ruleSetAt(OW, 1000, 60, 1000), "a server starts expensive outside, no toggle");
        assertSame(RuleSet.OUTSIDE_SINGLEPLAYER, SP.ruleSetAt(OW, 0, 95, 0), "above the camp box = outside");
    }

    @Test
    void presetsMatchTheTable() {
        assertEquals(BreakPolicy.Natural.NEVER, RuleSet.NO_BREAK.breakNatural());
        assertFalse(RuleSet.BASE.useResources(), "ores and logs in the camp stay (owner's default)");
        assertEquals(BreakPolicy.Natural.TRAVEL_EXPENSIVE, RuleSet.BASE.breakNatural());
        assertTrue(RuleSet.WORK.useResources());
        assertFalse(RuleSet.DESTROY.restore());
        assertEquals(6, RuleSet.OUTSIDE_SINGLEPLAYER.travelMultiplier(), "travel x6");
        assertEquals(10, RuleSet.OUTSIDE_MULTIPLAYER.travelMultiplier());
        assertEquals(24, BreakPolicy.Costs.travelCapV());
        assertNull(RuleSet.preset(null));
        assertTrue(RuleSet.WORK.describe().startsWith("work: natural lenient, resources yes"));
    }

    // ---- the floor

    @Test
    void floorHoldsForEveryIntent() {
        for (Intent in : new Intent[]{Intent.TRAVEL, Intent.MINE, Intent.DIG_OUT, Intent.BUILD}) {
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
        assertEquals("a falling block above", brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withDepth(1).withFallingAbove(true)).reason(), "on the surface");
        assertEquals("a liquid is not broken", brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withInCell(Fluid.WATER)).reason());
    }

    @Test
    void travelCapGrowsThenDenies_surfaceBreaksOnly() {
        // surface (owner 2026-11-02): the cap is the surface layer's; a cave's walls (STONE: depth 10) are never capped
        BlockFacts top = BlockFacts.STONE.withDepth(2);
        assertEquals(48, brk(SP, Intent.TRAVEL, 1000, 60, 1000, top, new Where(false, 0)).multiplier(), 1e-9, "x6 travel, x8 surface");
        assertEquals(96, brk(SP, Intent.TRAVEL, 1000, 60, 1000, top, new Where(false, 12)).multiplier(), 1e-9, "halfway: double");
        Verdict at = brk(SP, Intent.TRAVEL, 1000, 60, 1000, top, new Where(false, 24));
        assertFalse(at.allowed());
        assertEquals("travel break cap 24 (surface breaks) reached on this job", at.reason());
        assertTrue(brk(SP, Intent.MINE, 1000, 60, 1000, top, new Where(false, 24)).allowed(), "the cap is travel's only");
        assertTrue(brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE, new Where(false, 24)).allowed(), "under ground: no cap");
        assertEquals(6, brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE, new Where(false, 24)).multiplier(), 1e-9, "and no growth");
        // the cap grows with the distance walked on the job: 4 per 50 blocks, never under the owner's 24
        Where far = new Where(false, 24).withWalked(1000);
        assertTrue(brk(SP, Intent.TRAVEL, 1000, 60, 1000, top, far).allowed(), "1000 blocks walked: the cap is 80");
        assertEquals("travel break cap 80 (surface breaks) reached on this job", brk(SP, Intent.TRAVEL, 1000, 60, 1000, top, far.withBreaks(80)).reason());
        assertEquals(24, BreakPolicy.Costs.capFor(24, 299, 4, 50), "under 300 blocks the default holds");
        assertEquals(24, BreakPolicy.Costs.capFor(24, 0, 4, 0), "a zero divisor: the default");
    }

    @Test
    void surfaceLayerIsDearAndKeepsItsGravelRule_underGroundGravelCosts() {
        // surface (owner 2026-11-02, run HT): the top 4 blocks (or a depth not known) cost x8 to break or place for a walk
        assertEquals(6, brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withDepth(4)).multiplier(), 1e-9, "depth 4: under ground");
        Verdict top = brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withDepth(3));
        assertEquals(48, top.multiplier(), 1e-9);
        assertEquals("travel+surface", top.rule());
        Verdict unknown = brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withDepth(BlockFacts.DEPTH_UNKNOWN));
        assertEquals(48, unknown.multiplier(), 1e-9, "not known = priced as the surface (rule 15)");
        assertEquals("travel+surface(depth not known)", unknown.rule());
        // gravel above: refused on the surface, x3 under ground (the way out of the cave in run HT)
        assertEquals("a falling block above", brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withDepth(1).withFallingAbove(true)).reason());
        Verdict under = brk(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withFallingAbove(true));
        assertTrue(under.allowed());
        assertEquals(18, under.multiplier(), 1e-9, "x6 travel, x3 falling");
        assertEquals("travel+falling", under.rule());
        assertEquals("a falling block above", brk(SP, Intent.DIG_OUT, 1000, 60, 1000, BlockFacts.STONE.withFallingAbove(true)).reason(), "dig-out keeps its refusal");
        // a BUILD's path: the same prices, no cap
        assertEquals(48, brk(SP, Intent.BUILD, 1000, 60, 1000, BlockFacts.STONE.withDepth(1), new Where(false, 24)).multiplier(), 1e-9);
        // placing: a walk's placement on the surface x3 x8, under ground x3, a refill as before
        assertEquals(24, place(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withDepth(1)).multiplier(), 1e-9);
        assertEquals("travel+surface", place(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withDepth(1)).rule());
        assertEquals(3, place(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE).multiplier(), 1e-9);
        assertEquals("travel+refill", place(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withDepth(1).withDug(true)).rule());
        assertEquals(1, place(SP, Intent.MINE, 1000, 60, 1000, BlockFacts.STONE.withDepth(1)).multiplier(), 1e-9, "a mine's placement: not a walk's");
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
        assertEquals("no resources taken in base", v.reason());
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
    void outsidePlacesUnderEveryIntent() {
        // 0.39.s8 owner: "Place anything outside": every real intent may place outside (SP and MP); keep-out and
        // no-break (a protect box) still deny
        for (Intent in : Intent.values()) {
            if (in == Intent.NONE) continue;    // walking only: a route-map context, not an intent to act
            assertTrue(place(SP, in, 1000, 60, 1000, BlockFacts.STONE).allowed(), "SP outside " + in);
            assertTrue(place(MP, in, 1000, 60, 1000, BlockFacts.STONE).allowed(), "MP outside " + in);
            assertFalse(place(SP, in, 305, 60, 305, BlockFacts.STONE).allowed(), "no-break box " + in);
        }
        assertEquals(BreakPolicy.PlaceRule.NEVER, RuleSet.KEEP_OUT.place());
        assertEquals(BreakPolicy.PlaceRule.NEVER, RuleSet.NO_BREAK.place());
    }

    @Test
    void placing() {
        // 0.39.s8 owner: "Place anything outside": a new block outside is allowed, priced (travel x3)
        assertTrue(place(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE).allowed());
        assertTrue(place(SP, Intent.DIG_OUT, 1000, 60, 1000, BlockFacts.STONE).allowed());
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

    /** The owner's rule (aa372): a place into a cell the bot dug itself costs about nothing, wherever it is. */
    @Test
    void refillingMyOwnDugCellIsNearlyFree() {
        BlockFacts dug = BlockFacts.STONE.withDug(true);
        Verdict out = place(SP, Intent.TRAVEL, 1000, 60, 1000, dug);
        assertTrue(out.allowed(), "outside every area a refill passes the torches-only rule");
        assertEquals(BreakPolicy.Costs.refill(), out.multiplier());
        assertEquals("travel+refill", out.rule());
        assertEquals(BreakPolicy.Costs.refill(), place(SP, Intent.MINE, 120, 60, 120, dug).multiplier());
        assertEquals("dig_out+refill", place(SP, Intent.DIG_OUT, 1000, 60, 1000, dug).rule());
        assertEquals("build+refill", place(MP, Intent.BUILD, 1000, 60, 1000, dug).rule(), "on a server too");
        assertTrue(BreakPolicy.Costs.refill() < 0.1 && BreakPolicy.Costs.refill() > 0, "near zero, never zero (A* still needs an order)");
        // the floor stays the floor
        assertEquals("next to lava", place(SP, Intent.TRAVEL, 1000, 60, 1000, dug.withBelow(Fluid.LAVA)).reason());
        assertTrue(place(SP, Intent.TRAVEL, 305, 60, 305, dug).floor(), "a protect box: nothing placed, dug or not");
        assertEquals("walking only", place(SP, Intent.NONE, 1000, 60, 1000, dug).reason());
        // a new block in travel costs x3, torch or not (0.39.s8: anything outside)
        assertEquals(3, place(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE.withTorch(true)).multiplier());
        assertEquals(3, place(SP, Intent.TRAVEL, 1000, 60, 1000, BlockFacts.STONE).multiplier());
        assertFalse(BlockFacts.STONE.dugByMe(), "the 12-argument facts are not dug");
    }

    // ---- the facts-free part

    @Test
    void boxesAnswerWithoutFacts() {
        assertTrue(BreakPolicy.boxes(SP, Intent.TRAVEL, OW, 1000, 60, 1000).allowed());
        Verdict safe = BreakPolicy.boxes(SP, Intent.TRAVEL, OW, 305, 60, 305);
        assertFalse(safe.allowed());
        assertTrue(safe.floor());
        assertEquals("in shrine (no-break)", safe.reason());
        assertFalse(BreakPolicy.boxes(null, Intent.TRAVEL, OW, 0, 0, 0).allowed());
        // a safe area is a protect box in the parsed policy: floor, named
        assertEquals("in s (no-break)", BreakPolicy.boxes(Snapshot.of(Policy.parse("{\"areas\":[{\"name\":\"s\",\"type\":\"safe\",\"x1\":0,\"z1\":0,\"x2\":9,\"z2\":9}]}"), false),
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
    }
}
