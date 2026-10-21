package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.guard.BreakPolicy.BlockFacts;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent;
import io.github.mojolowjo.entropybot.restore.Ledger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** rm-mining (OLD_CODE_REMOVAL item 9): a block the bot placed itself (the ledger's "built: ..." note) is breakable by the bot. */
class OwnPlacedTest {
    static final String OW = "minecraft:overworld";

    @AfterEach
    void unhook() { OwnPlaced.cells = null; }

    @Test
    void myOwnPlacementIsNeverABuiltBlock() {
        Ledger ledger = new Ledger();
        ledger.add(1000, 60, 1000, OW, "minecraft:crafting_table", "minecraft:crafting_table", List.of(), "built: placed", 7, "aa", "built: placed", 0, 0);
        ledger.add(1001, 60, 1000, OW, "minecraft:furnace", "minecraft:furnace", List.of(), "built: station", 7, "aa", "built: station", 0, 0);
        ledger.add(1002, 60, 1000, OW, "minecraft:stone", "minecraft:stone", List.of("minecraft:cobblestone"), null, 7, "aa", "path", 0, 0);
        assertTrue(ledger.placedByMe(OW, 1000, 60, 1000));
        assertTrue(ledger.placedByMe(OW, 1001, 60, 1000));
        assertFalse(ledger.placedByMe(OW, 1002, 60, 1000), "a cell I broke is not a block I placed");
        assertFalse(ledger.placedByMe("minecraft:the_nether", 1000, 60, 1000), "another dimension");
        OwnPlaced.cells = (x, y, z) -> ledger.placedByMe(OW, x, y, z);

        // my table: the guard's built fact is off, so the bot may break it
        boolean built = OwnPlaced.built(true, 1000, 60, 1000);
        assertFalse(built);
        BreakPolicy.Verdict mine = BreakPolicyTest.brk(BreakPolicyTest.SP, Intent.MINE, 1000, 60, 1000, BlockFacts.STONE.withBuilt(built));
        assertNotEquals("built block", mine.reason());
        assertTrue(mine.allowed(), "my own table outside every area: breakable (" + mine.reason() + ")");

        // a stranger's planks next to it stay a built block (the floor)
        boolean theirs = OwnPlaced.built(true, 1003, 60, 1000);
        assertTrue(theirs);
        assertEquals("built block", BreakPolicyTest.brk(BreakPolicyTest.SP, Intent.MINE, 1003, 60, 1000, BlockFacts.STONE.withBuilt(theirs)).reason());
    }

    @Test
    void aFailingHookKeepsTheFloor() {
        OwnPlaced.cells = (x, y, z) -> { throw new IllegalStateException("no world"); };
        assertTrue(OwnPlaced.built(true, 0, 0, 0), "unknown: the floor stays");
        OwnPlaced.cells = null;
        assertTrue(OwnPlaced.built(true, 0, 0, 0));
        assertFalse(OwnPlaced.built(false, 0, 0, 0));
    }
}
