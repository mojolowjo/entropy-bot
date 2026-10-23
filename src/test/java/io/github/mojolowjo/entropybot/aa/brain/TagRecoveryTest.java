package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** tag-recovery (0.39.z18): server-shaped recipe slots (tags expanded to item lists) go back to their tag through the tag index. */
class TagRecoveryTest {
    static final Set<String> PLANKS = Set.of("minecraft:oak_planks", "minecraft:birch_planks", "minecraft:spruce_planks");
    static final Set<String> BURN = Set.of("minecraft:oak_log", "minecraft:birch_log", "minecraft:stripped_oak_log");
    static final Map<String, Set<String>> INDEX = Map.of(
            "minecraft:planks", PLANKS,
            "c:planks_mod", PLANKS,                                    // the same members under another namespace: minecraft: wins
            "minecraft:logs_that_burn", BURN,
            "minecraft:oak_logs", Set.of("minecraft:oak_log", "minecraft:stripped_oak_log"),
            "minecraft:wooden_stuff", Set.of("minecraft:oak_planks", "minecraft:birch_planks", "minecraft:spruce_planks", "minecraft:stick"));

    @Test
    void anExpandedListBecomesTheTagWhoseMembersEqualIt() {
        assertEquals("#minecraft:planks", ItemWorth.needWordOf(List.of("minecraft:birch_planks", "minecraft:oak_planks", "minecraft:spruce_planks"), INDEX));
        assertEquals("#minecraft:logs_that_burn", ItemWorth.needWordOf(List.of("minecraft:oak_log", "minecraft:birch_log", "minecraft:stripped_oak_log"), INDEX));
    }

    @Test
    void oneItemStaysTheItemAndAnUnmatchedListStaysAGroup() {
        assertEquals("minecraft:stick", ItemWorth.needWordOf(List.of("minecraft:stick"), INDEX));
        assertEquals("minecraft:coal|minecraft:charcoal", ItemWorth.needWordOf(List.of("minecraft:coal", "minecraft:charcoal"), INDEX));
        assertEquals("minecraft:oak_planks|minecraft:birch_planks", ItemWorth.needWordOf(List.of("minecraft:oak_planks", "minecraft:birch_planks"), INDEX),
                "a subset of a tag is no tag");
    }

    @Test
    void theShortestPathWinsInTheSameNamespace() {
        Map<String, Set<String>> idx = Map.of("x:long_name", Set.of("a:1", "a:2"), "x:ab", Set.of("a:1", "a:2"));
        assertEquals("#x:ab", ItemWorth.needWordOf(List.of("a:1", "a:2"), idx));
    }
}
