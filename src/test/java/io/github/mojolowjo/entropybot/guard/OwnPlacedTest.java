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

        assertTrue(OwnPlaced.at(1000, 60, 1000), "the ledger's own placement is mine");
        assertFalse(OwnPlaced.at(1003, 60, 1000));
    }

    @Test
    void aFailingHookIsNotMine() {
        OwnPlaced.cells = (x, y, z) -> { throw new IllegalStateException("no world"); };
        assertFalse(OwnPlaced.at(0, 0, 0));
        OwnPlaced.cells = null;
        assertFalse(OwnPlaced.at(0, 0, 0));
    }
}
