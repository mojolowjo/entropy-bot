package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.Nearby;
import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cloud tester 2, probe round 3, w058-2 (2026-11-23): "aa want acacia_log 16" cut 51 acacia_wood ("cut 1 logs - Baritone's
 * chop process on 20 block kind(s)"), the chest got acacia_log 1. The cut's block set is the blocks that DROP the word it
 * is for (the loot data, the collect's source set), kept to the game's logs and the owner's logs kind; the three states of
 * the loot book (loaded, empty, not loaded: rule 15).
 */
class ChopSetTest {
    static final String LOG = "minecraft:acacia_log", BARK = "minecraft:acacia_wood", LEAVES = "minecraft:acacia_leaves", OAK = "minecraft:oak_log";
    /** The game's logs tag as the fake world knows it (the bark block is a log by the tag: that was the fault). */
    static final Set<String> LOGS = Set.of(LOG, BARK, OAK);
    static final Predicate<String> IS_LOG = LOGS::contains, ANY = b -> true;

    static LootBook.Source drop(String block, String item) { return new LootBook.Source(LootBook.Kind.BLOCK, block, item, 1, 1, false, null); }

    /** Each log block drops itself, the bark drops bark; the leaves give a log by chance (a fake, to show the tag filter). */
    static LootBook book() {
        return new LootBook(List.of(drop(LOG, LOG), drop(BARK, BARK), drop(OAK, OAK), drop(LEAVES, LOG)), "test");
    }

    static Set<String> sources(String item, LootBook book) { return Nearby.sourceBlocks(item, book, id -> id); }

    @Test
    void anItemOrderCutsOnlyTheBlocksThatDropIt() {
        LootBook book = book();
        ChopSet.Pick p = ChopSet.of("acacia_log", book, sources(LOG, book), IS_LOG, ANY);
        assertTrue(p.ok(), p.refusal());
        assertEquals(List.of(LOG), p.kept(), "the bark block drops acacia_wood: no source of acacia_log, never in the cut");
        assertEquals(List.of(LEAVES), p.notLogs(), "a source the game does not tag as a log is another hand's");
        assertEquals("kept acacia_log; left out: acacia_leaves (not logs by the game's tag)", p.line());
        // the bark order takes the bark, and only it
        assertEquals(List.of(BARK), ChopSet.of("acacia_wood", book, sources(BARK, book), IS_LOG, ANY).kept());
    }

    @Test
    void aTagWordKeepsEveryLogOfTheTag() {
        // a tag need (any log, for planks or sticks) validly takes the bark too: the lookup's set is every member's block
        ChopSet.Pick p = ChopSet.of("#minecraft:logs", book(), List.of(LOG, BARK, OAK, LEAVES), IS_LOG, ANY);
        assertEquals(List.of(LOG, BARK, OAK), p.kept());
        assertEquals("kept acacia_log, acacia_wood, oak_log; left out: acacia_leaves (not logs by the game's tag)", p.line());
    }

    @Test
    void theOwnersLogsKindStillLeavesOut() {
        LootBook book = book();
        ChopSet.Pick tag = ChopSet.of("#logs", book, List.of(LOG, BARK, OAK), IS_LOG, b -> !b.equals(OAK));
        assertEquals(List.of(LOG, BARK), tag.kept());
        assertEquals(List.of(OAK), tag.excluded());
        assertEquals("kept acacia_log, acacia_wood; left out: oak_log (kinds logs exclude)", tag.line());
        // the ordered item's only block excluded: an error that names the item and why, never the rest of the tag
        ChopSet.Pick none = ChopSet.of("oak_log", book, sources(OAK, book), IS_LOG, b -> !b.equals(OAK));
        assertFalse(none.ok());
        assertTrue(none.kept().isEmpty());
        assertEquals("no log block drops oak_log (the game's loot data; left out: oak_log (kinds logs exclude))", none.refusal());
    }

    @Test
    void noSourceIsAnErrorThatNamesTheItem() {
        LootBook book = book();
        ChopSet.Pick p = ChopSet.of("test:ash_log", book, Set.of(), IS_LOG, ANY);
        assertFalse(p.ok());
        assertEquals("no log block drops test:ash_log (the game's loot data; left out: none)", p.refusal());
    }

    @Test
    void theThreeStatesOfTheLootBook() {
        // not loaded (none read yet, or the background read still runs): not known, whatever the sources say (rule 15)
        ChopSet.Pick unread = ChopSet.of("acacia_log", null, Set.of(LOG, BARK, OAK), IS_LOG, ANY);
        assertFalse(unread.ok());
        assertTrue(unread.kept().isEmpty(), "never a fall back to the whole tag");
        assertTrue(unread.refusal().startsWith(ChopSet.NOT_KNOWN + "acacia_log (the loot book is not read yet"), unread.refusal());
        LootBook.Progress pr = new LootBook.Progress("from the jars");
        pr.done.set(3);
        pr.total.set(9);
        ChopSet.Pick reading = ChopSet.of("#logs", LootBook.reading(pr), Set.of(LOG, BARK), IS_LOG, ANY);
        assertFalse(reading.ok());
        assertTrue(reading.refusal().startsWith(ChopSet.NOT_KNOWN + "#logs (" + LootBook.WAITING + " (3 of 9)"), reading.refusal());
        // loaded and empty (a world with no loot data): the item's own block is its source as the collect has it (Nearby.sourceBlocks)
        LootBook empty = LootBook.empty("test");
        ChopSet.Pick own = ChopSet.of("acacia_log", empty, sources(LOG, empty), IS_LOG, ANY);
        assertTrue(own.ok(), own.refusal());
        assertEquals(List.of(LOG), own.kept());
        assertNull(own.refusal());
    }

    @Test
    void theStepCarriesTheWordAndShowsIt() {
        Step.ChopTree item = new Step.ChopTree(new int[]{-27, 71, 25}, 16, "acacia_log"), tag = new Step.ChopTree(new int[]{10, 64, 0}, 1, "#logs"), bare = new Step.ChopTree(null, 4, null);
        assertEquals("cut 16 acacia_log at -27 71 25", item.text());
        assertEquals("minecraft:acacia_log", item.word());
        assertEquals("cut 1 #logs at 10 64 0", tag.text());
        assertEquals("#" + NeedChain.LOGS_TAG, tag.word());
        assertEquals("cut 4", bare.text());
        assertEquals("#" + NeedChain.LOGS_TAG, bare.word(), "no item named: the game's logs tag");
        // what the step counts and serves is its word: "until N in the bag" is the ordered item, not any log
        assertEquals(java.util.Map.of("minecraft:acacia_log", 16), StepsShort.wants(item));
        assertEquals("minecraft:acacia_log", StepMonitor.output(item));
        assertEquals(java.util.Map.of("minecraft:acacia_log", 16), Serves.own(item));
        assertNull(DryRun.produces(item), "the cut takes the whole tree: no count known");
    }
}
