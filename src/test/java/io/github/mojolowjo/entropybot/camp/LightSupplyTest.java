package io.github.mojolowjo.entropybot.camp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.23.1: light gets its torches (fetch, craft from coal/charcoal or logs) or says why not; never 0 torches silently. */
class LightSupplyTest {

    @Test
    void enoughFetchOrCraft() {
        assertEquals(LightGrid.Source.ENOUGH, LightGrid.supply(16, 16, 0, 0, 0, false).source());
        LightGrid.Supply f = LightGrid.supply(2, 16, 20, 0, 0, false);
        assertEquals(LightGrid.Source.FETCH, f.source());
        assertEquals(14, f.count());
        LightGrid.Supply c = LightGrid.supply(2, 16, 0, 2, 0, false);
        assertEquals(LightGrid.Source.CRAFT, c.source());
        assertEquals(8, c.count(), "4 torches a coal");
        LightGrid.Supply l = LightGrid.supply(2, 16, 0, 0, 5, true);
        assertEquals(LightGrid.Source.CRAFT, l.source(), "logs with a furnace near: charcoal first");
        assertEquals(14, l.count());
        assertTrue(l.text().contains("charcoal"));
    }

    @Test
    void theV1bCaseTwoTorchesNoCoalSaysSo() {
        // the V1b note: light here 12 ended at once with 2 torches and no coal - now it lights 2 and names what is missing
        LightGrid.Supply p = LightGrid.supply(2, 16, 0, 0, 0, false);
        assertEquals(LightGrid.Source.PARTIAL, p.source());
        assertTrue(p.text().contains("only 2 torches") && p.text().contains("next: "), p.text());
        LightGrid.Supply logsNoFurnace = LightGrid.supply(2, 16, 0, 0, 8, false);
        assertTrue(logsNoFurnace.text().contains("no furnace"), logsNoFurnace.text());
        LightGrid.Supply none = LightGrid.supply(0, 16, 0, 0, 0, false);
        assertEquals(LightGrid.Source.NONE, none.source());
        assertTrue(none.text().startsWith("error: no torches and nothing to make them from") && none.text().contains("- next: "), none.text());
    }
}
