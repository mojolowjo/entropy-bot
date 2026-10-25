package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.door.Door;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z66 (run X on z65, 2026-10-25 08:25-08:45, the full iron armor order). Item 1: a typed step starting while AA's own
 * put-back holds the slot halts it (Door.aaSlot HALT_RESTORE, as AA's task start) - never an order stopped on "busy".
 * Item 2: an ore block drops its raw item, not itself (the silk-touch drop is no source): no "smelt iron_ore &lt;- dig
 * iron_ore" chain, whose collect counted iron_ore while the bag gained raw_iron and read the end as unreachable; and a way
 * ending in such a hopeless explore no longer beats the bag's raw iron waiting on a path.
 */
class Z66RunXTest {

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    /** The game's loot data as the book reads it: the ores drop raw_iron (the silk-touch iron_ore entry skipped), a log itself. */
    static final LootBook BOOK = new LootBook(List.of(
            new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:iron_ore", "minecraft:raw_iron", 1, 1, false, null),
            new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:deepslate_iron_ore", "minecraft:raw_iron", 1, 1, false, null),
            new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:stone", "minecraft:cobblestone", 1, 1, false, null),
            new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:oak_log", "minecraft:oak_log", 1, 1, false, null)), "test");

    /** Every item places its own block of the same id but raw_iron and coal (as the registry says for these). */
    static String ownBlock(String item) { return item.endsWith("raw_iron") || item.endsWith(":coal") ? null : item; }

    // ---- item 2: the source blocks ----

    @Test
    void anOreBlockIsNoSourceOfItsOwnItemOnlyOfItsDrop() {
        assertEquals(Set.of(), Nearby.sourceBlocks("iron_ore", BOOK, Z66RunXTest::ownBlock), "iron_ore drops raw_iron: no block gives the item iron_ore");
        assertEquals(Set.of(), Nearby.sourceBlocks("deepslate_iron_ore", BOOK, Z66RunXTest::ownBlock));
        assertEquals(Set.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"), Nearby.sourceBlocks("raw_iron", BOOK, Z66RunXTest::ownBlock));
        assertEquals(Set.of("minecraft:oak_log"), Nearby.sourceBlocks("oak_log", BOOK, Z66RunXTest::ownBlock), "a log drops itself");
        assertEquals(Set.of("minecraft:glass"), Nearby.sourceBlocks("glass", BOOK, Z66RunXTest::ownBlock), "no drops read for the block: kept as before");
        assertEquals(Set.of("minecraft:iron_ore"), Nearby.sourceBlocks("iron_ore", null, Z66RunXTest::ownBlock), "no loot book: kept as before (rule 15)");
    }

    @Test
    void anItemNothingGivesButATooledDropHasNoSourceInTheGamesData() {
        String why = Nearby.noSource("iron_ore", BOOK);
        assertNotNull(why);
        assertTrue(why.startsWith("no block or mob gives iron_ore (the game's loot data: the iron_ore block drops raw_iron"), why);
        assertNull(Nearby.noSource("raw_iron", BOOK), "raw_iron: the ores drop it");
        assertNull(Nearby.noSource("oak_log", BOOK));
        assertNull(Nearby.noSource("glass", BOOK), "no drops read for it: not known, not 'none'");
        assertNull(Nearby.noSource("iron_ore", null));
        assertNull(Nearby.noSource("iron_ore", LootBook.reading(new LootBook.Progress("test"))), "the book still reading: not known (rule 15)");
        assertNull(Nearby.noSource("#minecraft:iron_ores", BOOK), "a tag word: its members' sources");
    }

    /** The planner data with run X's furnace recipes: iron_ingot from raw_iron, iron_ore and deepslate_iron_ore. */
    private static void world() {
        TestData d = ScenariosTest.world("wooden", "stone");
        d.smelt("iron_ingot", "iron_ore").smelt("iron_ingot", "deepslate_iron_ore");
        d.install();
    }

    private static boolean exploresFor(Chain c, String item) {
        for (Step s : c.steps())
            if (s instanceof Step.Explore e && e.until() != null && Arrays.asList(e.until().split("\\|")).contains(item)) return true;
        return false;
    }

    /** Nothing known: the chain's explore is for raw_iron's source, the iron_ore ways dropped with the loot data's fact. */
    @Test
    void theIronIngotChainNeverExploresForTheOresOwnItem() {
        world();
        Chain c = NeedChain.resolve("iron_ingot", 4, ScenariosTest.w(Map.of("minecraft:coal", 15), true, true, Map.of()).withMobs(w -> List.of(), BOOK));
        assertFalse(exploresFor(c, "iron_ore"), c.why() + " " + c.steps());
        assertFalse(exploresFor(c, "deepslate_iron_ore"), c.why() + " " + c.steps());
        assertTrue(c.dropped().stream().anyMatch(d -> d.contains("no block or mob gives iron_ore")), c.dropped().toString());
    }

    /**
     * Run X 08:40:40: 10 raw_iron in the bag (boots need 4 ingots), the furnace's walks not known yet (the known furnace
     * computing, the cobblestone for a new one computing). Before z66 "smelt 4 iron_ore &lt;- explore" stood (an explore
     * way stands, a way waiting on a path does not) and won; now the iron_ore ways fail on the loot data and the chain
     * waits on the path / explores while it computes - never the explore for iron_ore.
     */
    @Test
    void runX_theBagsRawIronWaitingOnAPathIsNotBeatenByAHopelessExplore() {
        world();
        Spot furnace = new Spot(400, 16, 0, 400, "minecraft:furnace", 1);
        Spot stone = new Spot(12, 16, 3, 12, "minecraft:stone", 40);
        ScenariosTest.FakePath fake = new ScenariosTest.FakePath().computing(furnace).computing(stone);
        NeedChain.World w = ScenariosTest.w(Map.of("minecraft:raw_iron", 10, "minecraft:coal", 15, "minecraft:stone_pickaxe", 1), true, false,
                        Map.of("cobblestone", List.of(stone)))
                .withMobs(x -> List.of(), BOOK).withPath(fake).withStations(k -> ItemWorth.SMELT.equals(k) ? furnace : null);
        Chain c = NeedChain.resolve("iron_boots", 1, w);
        assertFalse(exploresFor(c, "iron_ore"), c.why() + " " + c.steps());
        assertFalse(exploresFor(c, "deepslate_iron_ore"), c.why() + " " + c.steps());
        assertTrue(c.dropped().stream().anyMatch(d -> d.contains("no block or mob gives iron_ore")), c.dropped().toString());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Collect), "no dig: the bag holds the raw iron: " + c.steps());
        // the paths land: the bag's raw iron is smelted and the boots crafted
        fake.path(furnace, 400).path(stone, 12);
        Chain done = NeedChain.resolve("iron_boots", 1, w.withPath(fake));
        assertTrue(done.ok() && !done.explore(), done.why());
        assertTrue(done.steps().stream().anyMatch(s -> s instanceof Step.Smelt k && k.item().equals("iron_ingot") && k.n() == 4), done.steps().toString());
        assertTrue(done.steps().stream().noneMatch(s -> s instanceof Step.Collect k && k.item().contains("iron")), "no iron dug (cobblestone for a new furnace here beats the 400 m walk): " + done.steps());
    }

    // ---- item 1: the slot at a step's start ----

    /** Run X 08:38:24: the put-back of the stopped job held the slot; a step start halts it like AA's task start. */
    @Test
    void aStepStartHaltsAAsOwnPutBackAndOnlyThat() {
        assertEquals(Door.Slot.HALT_RESTORE, Door.aaSlot(false, true, true), "the put-back alone: halted, its blocks wait in the ledger");
        assertEquals(Door.Slot.BUSY, Door.aaSlot(false, true, false), "any other job: busy as before");
        assertEquals(Door.Slot.FREE, Door.aaSlot(false, false, false));
    }
}
