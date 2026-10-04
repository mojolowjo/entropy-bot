package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Water plan: the blocks (count, choice, the junk reserve), the guard's rule, the fence, the report. */
class WaterPlanTest {
    static final ClearBox TUNNEL = WaterScanTest.TUNNEL;

    @Test
    void blocksComeFromJunkAndThePickaxeMaterialStays() {
        Map<String, Integer> inv = Map.of("minecraft:cobblestone", 70, "minecraft:dirt", 5, "minecraft:diamond", 3);
        assertEquals(6 + 5, WaterPlan.available(inv), "64 cobblestone stay for pickaxes");
        assertEquals(List.of("minecraft:cobblestone", "minecraft:cobblestone"), WaterPlan.assignBlocks(2, inv), "FALLBACK order: cobblestone before dirt");
        assertNull(WaterPlan.assignBlocks(12, inv));
        Map<String, Integer> two = Map.of("minecraft:cobbled_deepslate", 66, "minecraft:tuff", 1);
        assertEquals(List.of("minecraft:cobbled_deepslate", "minecraft:cobbled_deepslate", "minecraft:tuff"), WaterPlan.assignBlocks(3, two));
    }

    @Test
    void junkDropKeepsAReserveForSealing() {
        Map<String, Integer> inv = Map.of("minecraft:cobbled_deepslate", 300, "minecraft:tuff", 40);
        // junk drop alone would throw all but the pickaxes' 64
        Map<String, Integer> thrown = Map.of("minecraft:cobbled_deepslate", 236, "minecraft:tuff", 40);
        Map<String, Integer> kept = WaterPlan.keepReserve(thrown, inv, WaterPlan.RESERVE);
        assertEquals(Map.of("minecraft:cobbled_deepslate", 172, "minecraft:tuff", 40), kept, "64 more cobbled deepslate stay");
        // already enough left: nothing changes
        assertEquals(Map.of("minecraft:tuff", 40), WaterPlan.keepReserve(Map.of("minecraft:tuff", 40), inv, WaterPlan.RESERVE));
        // too little to keep a whole reserve: all of it stays
        assertEquals(Map.of(), WaterPlan.keepReserve(Map.of("minecraft:dirt", 10), Map.of("minecraft:dirt", 10), WaterPlan.RESERVE));
    }

    static WaterPlan.Inputs in(FakeWorld w, Bot bot, Pos at, ClearJob.StandCheck fence, java.util.function.Predicate<Pos> allowed) {
        return new WaterPlan.Inputs(w, TUNNEL, bot, at, false, fence, allowed, Map.of("minecraft:cobbled_deepslate", 500));
    }

    static FakeWorld pool() {
        FakeWorld w = new FakeWorld();
        w.fill(-1, 6, 40, 42, -1, 1, "air");
        w.fill(8, 10, 40, 42, -2, 2, "water");
        return w;
    }

    @Test
    void theGuardsRuleStopsTheRoundWithTheCell() {
        FakeWorld w = pool();
        WaterPlan.Round r = WaterPlan.round(in(w, Bot.at(5.5, 40, 0.5), new Pos(8, 41, 0), null, p -> p.z() != 0));
        assertEquals("the guard won't let me place at 8 40 0", r.blocked());
        assertTrue(r.placements().isEmpty());
    }

    @Test
    void withTheFenceItOnlyStandsInsideTheArea() {
        FakeWorld w = pool();
        ClearJob.StandCheck fence = (x, y, z) -> TUNNEL.contains(x, y, z);
        WaterPlan.Round r = WaterPlan.round(in(w, Bot.at(5.5, 40, 0.5), new Pos(8, 41, 0), fence, null));
        assertNull(r.blocked());
        assertFalse(r.placements().isEmpty());
        for (WaterPlan.Placement p : r.placements()) assertTrue(TUNNEL.contains(p.from().x(), p.from().y(), p.from().z()), p.toString());
    }

    @Test
    void theOrderAlwaysHasAFaceToClick() {
        // more water around the pool: every placement still has a face to click when its turn comes
        FakeWorld w = pool();
        w.fill(8, 10, 40, 42, 3, 3, "water");             // 54 sources: not a large body
        w.fill(8, 10, 43, 43, 0, 0, "water");
        WaterPlan.Round r = WaterPlan.round(in(w, Bot.at(5.5, 40, 0.5), new Pos(8, 41, 0), null, null));
        assertNull(r.blocked(), r.what());
        java.util.Set<Pos> placed = new java.util.HashSet<>();
        WaterPlan.Overlay ov = new WaterPlan.Overlay(w);
        for (WaterPlan.Placement p : r.placements()) {
            assertTrue(FloorFill.supported(ov, p.cell()), "a face for " + p.cell());
            ov.solid.add(p.cell());
            assertTrue(placed.add(p.cell()), "each cell once: " + p.cell());
        }
    }

    @Test
    void theReportNamesWhatWasSealed() {
        WaterPlan.Tally t = new WaterPlan.Tally();
        assertEquals("", t.text());
        List<WaterPlan.Placement> ps = List.of(new WaterPlan.Placement(new Pos(377, -45, 854), null, "minecraft:cobblestone", WaterShell.Role.FILL),
                new WaterPlan.Placement(new Pos(381, -44, 853), null, "minecraft:cobblestone", WaterShell.Role.RING));
        t.add(new WaterPlan.Round(null, WaterScan.Kind.SOURCES_INSIDE, ps, false, 2, 1, ""), new WaterShell.Axis(true, 1), TUNNEL);
        assertEquals("sealed 2 water cells (1 source) at x 377-381", t.text());
    }
}
