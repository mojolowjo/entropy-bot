package io.github.mojolowjo.entropybot.aa;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 planks (run DH 04:38:13-04:39:43): the trip's 64 dark_oak_planks (most for the armour smelt's fuel, 16 composters
 * from 112 slabs; the seen planks at 37 35 7 named the member) were collected 0 of 63; the repair took "cut 16 at 2 69 -5 > craft dark_oak_planks 64" - the only seen tree, an
 * acacia, offered by AaRuntime.treeSighting for the need's dark oak logs - and the craft failed "missing 16 dark_oak_log".
 * A seen tree whose lowest log is read serves only a need whose source blocks hold that log; not read (its chunk not
 * loaded): offered as before, its walk not known until it loads (rule 15).
 */
class Z77TreeSpeciesTest {
    static final Set<String> DARK_OAK = Set.of("minecraft:dark_oak_log", "minecraft:dark_oak_wood", "minecraft:stripped_dark_oak_log", "minecraft:stripped_dark_oak_wood");

    @Test
    void dhsAcaciaTreeIsNoDarkOakSource() {
        assertFalse(Nearby.treeServes(DARK_OAK, "minecraft:acacia_log"), "DH: the acacia at 2 69 -5 for dark_oak_planks");
        assertTrue(Nearby.treeServes(DARK_OAK, "minecraft:dark_oak_log"));
    }

    @Test
    void anyLogNeedAndAnUnreadTreeAreAsBefore() {
        assertTrue(Nearby.treeServes(null, "minecraft:acacia_log"), "no block set: any log (the tree fact)");
        assertTrue(Nearby.treeServes(Set.of("minecraft:acacia_log", "minecraft:oak_log", "minecraft:dark_oak_log"), "minecraft:acacia_log"), "a #logs need takes it");
        assertTrue(Nearby.treeServes(DARK_OAK, null), "not read (chunk not loaded): offered, its walk waits on the chunk");
    }
}
