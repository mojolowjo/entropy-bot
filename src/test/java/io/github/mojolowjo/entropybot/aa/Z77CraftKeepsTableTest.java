package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.KeepClear;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.clear.OwnStations.Placed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 craft (run DA 00:35:52-00:37:17, docs/runs/runDA-z78b): in the y 16 tunnel at -11 16 66 the chain placed its
 * crafting table at -11 16 65. "place furnace near me" had no free cell, and the fork's room pass cleared the cell nearest
 * the feet, which was the table ("at -11 16 65 (clearing the block there first)"). "craft oak_slab 12", priced on that
 * table, then walked 77 m to my old table at 23 24 -3 (79.4 s). Run CY 23:40:56 shows the same overwrite. Now the
 * place-near keeps my station that a later craft or smelt uses: a constraint for the fork's pick, never a cell named.
 */
class Z77CraftKeepsTableTest {
    static final String TABLE = "minecraft:crafting_table", FURNACE = "minecraft:furnace";
    static final int[] FEET = {-11, 16, 66};

    @AfterEach
    void clear() { Tuning.reset(); }

    static final Function<String, Set<String>> LOADED = Map.of(ItemWorth.CRAFT, Set.of(TABLE), ItemWorth.SMELT, Set.of(FURNACE))::get;

    /** DA's run after re-plan 2 (00:35:51), from the table's placement on. */
    static List<Step> da() {
        return List.of(
                new Step.Craft("crafting_table", 1),
                new Step.PlaceNear("crafting_table"),
                new Step.Craft("furnace", 1),
                new Step.PlaceNear("furnace"),            // index 3: the running step
                new Step.Craft("oak_planks", 4),
                new Step.Craft("oak_slab", 12),
                new Step.Smelt("iron_ingot", 8, true),
                new Step.Craft("iron_chestplate", 1),
                new Step.Collect("coal", 2),
                new Step.Smelt("iron_ingot", 7, true),
                new Step.Craft("iron_leggings", 1));
    }

    /** My stations in DA at 00:35:53: the table just placed beside the feet, the old table and furnace far up at y 24. */
    static List<Placed> mine() {
        return List.of(new Placed(-11, 16, 65, TABLE), new Placed(23, 24, -3, TABLE), new Placed(22, 24, -2, FURNACE));
    }

    static List<String> text(List<int[]> cells) {
        List<String> out = new ArrayList<>();
        for (int[] c : cells) out.add(c[0] + " " + c[1] + " " + c[2]);
        return out;
    }

    @Test
    void theFurnacesPlacementKeepsTheTableTheSlabsArePricedOn() {
        List<int[]> keep = KeepClear.stations(da(), 4, FEET, Tuning.i("steps.placeNearR"), LOADED, mine());
        assertEquals(List.of("-11 16 65"), text(keep), "run DA: the table at -11 16 65 is kept, the far ones are out of reach");
    }

    @Test
    void aStationNoLaterStepUsesIsNotKept() {
        List<Step> smeltOnly = List.of(new Step.PlaceNear("furnace"), new Step.Smelt("iron_ingot", 8, true), new Step.Collect("coal", 2));
        assertTrue(KeepClear.stations(smeltOnly, 1, FEET, 3, LOADED, mine()).isEmpty(), "only a smelt follows: my table may be cleared (the owner's rule: inside its areas the bot may break anything)");
        List<Step> walkFirst = List.of(new Step.PlaceNear("furnace"), new Step.Walk(40, 70, 40, 2), new Step.Craft("oak_slab", 12));
        assertTrue(KeepClear.stations(walkFirst, 1, FEET, 3, LOADED, mine()).isEmpty(), "the craft comes after a walk: it is done elsewhere");
    }

    @Test
    void aKindNotLoadedKeepsEveryStationOfMineInReach() {
        List<Placed> two = List.of(new Placed(-11, 16, 65, TABLE), new Placed(-12, 16, 66, FURNACE), new Placed(23, 24, -3, TABLE));
        assertEquals(List.of("-11 16 65", "-12 16 66"), text(KeepClear.stations(da(), 4, FEET, 3, k -> null, two)));
    }

    @Test
    void noStationsOfMineNothingKept() {
        assertTrue(KeepClear.stations(da(), 4, FEET, 3, LOADED, List.of()).isEmpty());
        assertTrue(KeepClear.stations(da(), 4, FEET, 3, LOADED, null).isEmpty());
    }
}
