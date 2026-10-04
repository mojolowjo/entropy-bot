package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B7d review fixes (D1 table trip): the bot's own crafting table is armed only after its own click on a free cell, the
 * pickup breaks exactly a crafting table and only while armed (on every attempt), and a failed trip keeps the pickup.
 */
class TableTripTest {
    static final Pos CELL = new Pos(1, 53, 0);

    @Test
    void armingNeedsOurClickOnAFreeCell() {
        assertTrue(PlaceRules.ourClick(true, "ok: SUCCESS"));
        assertFalse(PlaceRules.ourClick(false, "ok: SUCCESS"), "the cell held something before the click");
        assertFalse(PlaceRules.ourClick(true, PlaceRules.ALREADY_THERE), "\"already there\" never arms");
        assertFalse(PlaceRules.ourClick(false, PlaceRules.ALREADY_THERE));
        assertFalse(PlaceRules.ourClick(true, "error: nothing in reach to place crafting_table against at 1 53 0"));
        assertFalse(PlaceRules.ourClick(true, null));

        assertTrue(PlaceRules.armsTable(true, "minecraft:crafting_table"));
        assertFalse(PlaceRules.armsTable(false, "minecraft:crafting_table"), "a table that turned up during the walk");
        assertFalse(PlaceRules.armsTable(true, "somemod:oak_crafting_table"), "exactly the vanilla table");
        assertFalse(PlaceRules.armsTable(true, "minecraft:air"));
        assertFalse(PlaceRules.armsTable(true, null));
    }

    @Test
    void pickupBreaksOnlyAnArmedCraftingTable() {
        assertTrue(PlaceRules.pickupMayBreak(true, "minecraft:crafting_table"));
        assertFalse(PlaceRules.pickupMayBreak(false, "minecraft:crafting_table"));
        assertFalse(PlaceRules.pickupMayBreak(true, "minecraft:oak_planks"));
        assertFalse(PlaceRules.pickupMayBreak(true, "minecraft:oak_door"));
        assertFalse(PlaceRules.pickupMayBreak(true, null));
    }

    @Test
    void theExactIdGateHoldsOnEveryCheckEvenForced() {
        FakeWorld w = new FakeWorld();
        w.set(CELL, "oak_planks");
        AtomicBoolean armed = new AtomicBoolean(true);
        ClearJob pickup = ClearJob.start(new ClearJob.Options().only(List.of(CELL)).force(true).soft(true).keepOres(false)
                .exactId(PlaceRules.TABLE_ID).armed(armed::get), null, null);
        // someone swapped the table for planks: force alone would break them, the gate doesn't
        assertTrue(ClearEngine.clearableState(w, CELL.x(), CELL.y(), CELL.z(), true), "force alone allows planks");
        assertFalse(ClearEngine.clearable(w, pickup, CELL.x(), CELL.y(), CELL.z()));
        assertFalse(pickup.allows(w, CELL.x(), CELL.y(), CELL.z()));
        w.set(CELL, "crafting_table");
        assertTrue(pickup.allows(w, CELL.x(), CELL.y(), CELL.z()), "exactly a crafting table, armed");
        armed.set(false);
        assertFalse(pickup.allows(w, CELL.x(), CELL.y(), CELL.z()), "disarmed: nothing at all");
        // a plain job has no gate
        ClearJob plain = ClearJob.start(new ClearJob.Options().only(List.of(CELL)).force(true), null, null);
        w.set(CELL, "oak_planks");
        assertTrue(ClearEngine.clearable(w, plain, CELL.x(), CELL.y(), CELL.z()));
    }

    @Test
    void theRunNeverBreaksAReplacedTable() {
        FakeWorld w = new FakeWorld();
        w.set(CELL, "oak_planks");
        RunDriver d = new RunDriver(w, Bot.at(0.5, 53, 0.5)).simInventory(100000);
        String msg = d.clear(new ClearJob.Options().only(List.of(CELL)).force(true).soft(true).keepOres(false)
                .label(PlaceRules.pickupLabel(CELL)).exactId(PlaceRules.TABLE_ID).armed(() -> true));
        assertEquals("oak_planks", w.get(CELL), msg);
        assertTrue(msg.startsWith("ok"), msg);

        // the control: the same forced clear with the planks' own id breaks them (the gate isn't blocking everything)
        RunDriver d2 = new RunDriver(w, Bot.at(0.5, 53, 0.5)).simInventory(100000);
        d2.clear(new ClearJob.Options().only(List.of(CELL)).force(true).soft(true).keepOres(false).exactId("minecraft:oak_planks"));
        assertEquals("air", w.get(CELL));

        // disarmed mid-way: nothing is broken
        w.set(CELL, "oak_planks");
        RunDriver d3 = new RunDriver(w, Bot.at(0.5, 53, 0.5)).simInventory(100000);
        d3.clear(new ClearJob.Options().only(List.of(CELL)).force(true).soft(true).exactId("minecraft:oak_planks").armed(() -> false));
        assertEquals("oak_planks", w.get(CELL));
    }

    @Test
    void aFailedTripKeepsTheArmedPickup() {
        // [place (failed: index 0)] [craft] [pickup, armed] [another pickup, not armed]
        List<String> removed = List.of("place", "craft", "pickup:armed", "pickup");
        assertEquals(List.of("pickup:armed"), PlaceRules.keptOnCatch(removed, s -> s.endsWith(":armed")));
        // the craft failed after the place: the pickup stays
        assertEquals(List.of("pickup:armed"), PlaceRules.keptOnCatch(List.of("craft", "pickup:armed"), s -> s.endsWith(":armed")));
        // the pickup itself failed: it is not put back (no loop); the job's end names the table instead
        assertEquals(List.of(), PlaceRules.keptOnCatch(List.of("pickup:armed"), s -> s.endsWith(":armed")));
        assertEquals(List.of(), PlaceRules.keptOnCatch(List.of(), s -> true));
    }
}
