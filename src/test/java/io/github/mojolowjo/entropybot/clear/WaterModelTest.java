package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Water plan: whole tunnels through water, offline. The careful dig runs (ClearDriver), stops "blocked by water", a
 * water round ({@link WaterPlan#round}) says what to place, the blocks go in one by one, and after every placement and
 * every dig the water model ({@link FluidSim}, with the game's new-source rule) settles: no water (and so no new source)
 * may ever show up in a cell the dig has opened.
 */
class WaterModelTest {
    static final ClearBox TUNNEL = WaterScanTest.TUNNEL;      // x 0..20, y 40..42, z -1..1
    static final ClearBox SIM = ClearBox.of(-4, 34, -9, 26, 50, 9);

    record Result(String end, int rounds, int placed, int water, int sources, List<String> log) {}

    /** Digs TUNNEL from x -1 with water rounds until it ends; checks the dug cells stay dry the whole way. */
    static Result dig(FakeWorld w, boolean large, int blocks) {
        w.fill(-3, -1, 40, 42, -1, 1, "air");
        FluidSim.settle(w, SIM);
        ClearDriver d = new ClearDriver(w, Bot.at(-1.5, 40, 0.5)).simInventory(100000);
        Map<String, Integer> inv = new java.util.HashMap<>(Map.of("minecraft:cobbled_deepslate", blocks));
        Set<Pos> dug = new HashSet<>();
        List<String> log = new ArrayList<>();
        WaterPlan.Tally tally = new WaterPlan.Tally();
        for (int round = 0; round <= WaterPlan.MAX_ROUNDS; round++) {
            ClearJob job = ClearJob.start(new ClearJob.Options().box(TUNNEL).label("digging the tunnel").liquidBlocks(true), null, d.ores);
            String end = d.run(job);
            for (int x = TUNNEL.x1(); x <= TUNNEL.x2(); x++)
                for (int y = TUNNEL.y1(); y <= TUNNEL.y2(); y++)
                    for (int z = TUNNEL.z1(); z <= TUNNEL.z2(); z++) if (w.air(x, y, z)) dug.add(new Pos(x, y, z));
            FluidSim.settle(w, SIM);
            assertDry(w, dug, "after dig " + round);
            log.add(end);
            if (!ClearEngine.blockedByLiquid(end)) return new Result(end, round, tally.placed, tally.water, tally.sources, log);
            Pos at = ClearEngine.liquidBlock(w, job);
            WaterPlan.Round r = WaterPlan.round(new WaterPlan.Inputs(w, TUNNEL, d.bot(), at, large, null, null, inv));
            log.add(r.what() + (r.blocked() != null ? " -> " + r.blocked() : " -> " + r.placements().size() + " blocks"));
            if (r.blocked() != null) return new Result(end + " - " + r.blocked(), round, tally.placed, tally.water, tally.sources, log);
            tally.add(r, null, TUNNEL);
            for (WaterPlan.Placement p : r.placements()) {
                Pos c = p.cell();
                assertTrue(FloorFill.supported(w, c), "a face to click at " + c);
                Pos f = p.from();
                assertFalse(w.fluid(f.x(), f.y(), f.z()) || w.fluid(f.x(), f.y() + 1, f.z()), "never stands in water: " + f);
                boolean there = Math.floor(d.x) == f.x() && Math.floor(d.y + 0.01) == f.y() && Math.floor(d.z) == f.z();
                if (!there) assertTrue(d.walkTo(f.x(), f.y(), f.z()), "walks to " + f + " for " + c);
                assertNotNull(PlaceRules.placeSide(w, c.x(), c.y(), c.z(), d.x, d.y + Bot.EYE, d.z), "in reach from " + f + " for " + c);
                assertFalse(FloorFill.bodyHits(d.x, d.y, d.z, c), "not into itself");
                assertTrue(w.replaceable(c.x(), c.y(), c.z()) || !w.fluid(c.x(), c.y(), c.z()), "only into water or air: " + c);
                w.set(c, ClearRules.blockName(p.block()));
                inv.merge(p.block(), -1, Integer::sum);
                FluidSim.settle(w, SIM);
                assertDry(w, dug, "after placing " + c);
            }
            if (r.drain()) FluidSim.settle(w, SIM);
        }
        return new Result("too many rounds", WaterPlan.MAX_ROUNDS, tally.placed, tally.water, tally.sources, log);
    }

    static void assertDry(FakeWorld w, Set<Pos> dug, String when) {
        for (Pos p : dug) assertFalse(w.fluid(p.x(), p.y(), p.z()), "water got into the dug cell " + p + " " + when);
    }

    @Test
    void aPoolOfSourcesAcrossTheTunnelIsSealedSliceBySlice() {
        // a pool of sources across the path: x 8..10, y 39..43, z -3..3 (105 sources > 64? no: smaller below)
        FakeWorld w = new FakeWorld();
        w.fill(8, 10, 40, 42, -2, 2, "water");           // 45 sources, in the path and its ring
        Result r = dig(w, false, 256);
        assertTrue(r.end().startsWith("ok: done digging the tunnel"), r.log().toString());
        assertTrue(r.rounds() >= 1, r.log().toString());
        assertEquals(45, r.sources(), "every source of the pool was sealed or filled");
        for (int x = TUNNEL.x1(); x <= TUNNEL.x2(); x++)
            for (int y = TUNNEL.y1(); y <= TUNNEL.y2(); y++)
                for (int z = TUNNEL.z1(); z <= TUNNEL.z2(); z++) assertTrue(w.air(x, y, z), "dug out: " + x + " " + y + " " + z);
        // the ring stays as the tunnel's wall where the water was
        assertFalse(w.fluid(9, 41, 2));
        assertFalse(w.air(9, 41, 2));
    }

    @Test
    void aGeodeAboveAndBesideWithFallingWater() {
        // the incident's shape: a pocket of water above and beside the tunnel, some of it falling onto the roof's level
        FakeWorld w = new FakeWorld();
        w.fill(7, 11, 41, 45, 0, 3, "air");
        w.fill(7, 11, 41, 41, 0, 3, "stone");
        w.fill(8, 10, 44, 45, 1, 2, "water");             // 12 sources at the top
        FluidSim.settle(w, SIM);
        assertTrue(w.fluid(9, 43, 1), "water fell and spread below the sources");
        Result r = dig(w, false, 256);
        assertTrue(r.end().startsWith("ok: done digging the tunnel"), r.log().toString());
    }

    @Test
    void waterFlowingInIsPluggedAndDrains() {
        // a source 3 out from the tunnel's side, its stream entering the tunnel's path at x 10
        FakeWorld w = new FakeWorld();
        w.fill(10, 10, 40, 40, 2, 5, "air");
        w.set(10, 40, 5, "water");
        w.set(10, 39, 2, "stone");
        Result r = dig(w, false, 64 + 64);
        assertTrue(r.end().startsWith("ok: done digging the tunnel"), r.log().toString());
        assertTrue(r.log().stream().anyMatch(s -> s.startsWith("water flowing in from outside")), r.log().toString());
        assertTrue(r.placed() <= 3, "only where it enters: " + r.placed());
        assertTrue(w.fluid(10, 40, 5), "the source outside stays");
    }

    @Test
    void aLargeBodyWaitsForTheConfirmation() {
        FakeWorld w = new FakeWorld();
        w.fill(6, 14, 38, 44, 2, 12, "water");          // a lake beside the tunnel
        Result r = dig(w, false, 256);
        assertTrue(r.end().startsWith("blocked by water at "), r.end());
        assertTrue(r.end().endsWith(WaterPlan.LARGE_ASK), r.end());
        Result ok = dig(w, true, 512);
        assertTrue(ok.end().startsWith("ok: done digging the tunnel"), ok.log().toString());
    }

    @Test
    void tooFewBlocksIsBlockedBeforeARoundStarts() {
        FakeWorld w = new FakeWorld();
        w.fill(8, 10, 40, 42, -2, 2, "water");
        Result r = dig(w, false, 64 + 10);              // 64 stay for pickaxes: 10 placeable
        // the first round takes only the slice it has blocks for (the cap in front of the pool: 9); the next slice (15)
        // doesn't fit, and is never begun
        assertEquals(9, r.placed());
        assertTrue(r.end().startsWith("blocked by water at "), r.end());
        assertTrue(r.end().endsWith("I need 15 blocks to seal it and have 1 (cobblestone, cobbled deepslate, stone, dirt...; 64 stay for pickaxes)"), r.end());
    }

    @Test
    void theModelBringsWaterBackWithoutTheShell() {
        // the controls: the model is strict enough to catch a careless order
        // (1) the new-source rule: between two sources, on stone, an empty cell becomes a source
        FakeWorld w = new FakeWorld();
        w.fill(0, 2, 40, 40, 0, 0, "air");
        w.set(0, 40, 0, "water");
        w.set(2, 40, 0, "water");
        FluidSim.settle(w, ClearBox.of(-2, 38, -2, 4, 42, 2));
        assertEquals(ClearWorld.FluidCell.SOURCE, w.fluidCell(1, 40, 0));
        // (2) filling only the inside of a slice and digging it brings the water back from the ring
        FakeWorld v = new FakeWorld();
        v.fill(8, 10, 40, 42, -2, 2, "water");
        v.fill(-3, 7, 40, 42, -1, 1, "air");
        for (Pos p : WaterShell.cross(TUNNEL, new WaterShell.Axis(true, 1), 8)) v.set(p, "cobbled_deepslate");
        FluidSim.settle(v, SIM);
        for (Pos p : WaterShell.cross(TUNNEL, new WaterShell.Axis(true, 1), 8)) v.set(p, "air");    // dug without the ring
        FluidSim.settle(v, SIM);
        assertTrue(v.fluid(8, 41, 0) && v.fluid(5, 40, 0), "the water came back into the tunnel");
    }
}
