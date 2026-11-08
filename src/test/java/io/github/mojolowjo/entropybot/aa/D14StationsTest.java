package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.StationPlace;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.world.SurfaceHeight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** D14: stations placed once, remembered (the station book), priced walk-back against make-new, the surface under leaves. */
class D14StationsTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    @Test
    void theStationBookRoundTripsAndMarksAGoneStationWithItsReason() {
        StationBook b = new StationBook();
        b.placed("crafting_table", 9, 67, 2, 1000);
        b.seen("minecraft:furnace", 8, 61, 3, false, 2000);
        String line = b.gone(9, 67, 2, "the block there is minecraft:air now", 3000);
        assertTrue(line.contains("crafting_table at 9 67 2 is gone: the block there is minecraft:air now"), line);
        assertTrue(b.goneAt(9, 67, 2));
        assertTrue(b.gone(1, 2, 3, "x", 4000).startsWith("stations: nothing in the station book"), "an unknown cell is said");

        StringBuilder note = new StringBuilder();
        StationBook back = StationBook.fromJson(b.toJson(), note);
        assertEquals(b.all(), back.all());
        assertEquals("", note.toString());
        StationBook.Rec t = back.at(9, 67, 2);
        assertEquals("minecraft:crafting_table", t.block());
        assertTrue(t.mine());
        assertFalse(t.present());
        assertEquals(3000, t.lastSeen());
        assertEquals("the block there is minecraft:air now", t.goneWhy());
        StationBook.Rec f = back.at(8, 61, 3);
        assertTrue(f.present() && !f.mine() && f.goneWhy() == null);

        // seen again: present, and still mine
        back.seen("minecraft:crafting_table", 9, 67, 2, false, 5000);
        assertTrue(back.at(9, 67, 2).present() && back.at(9, 67, 2).mine());
    }

    /**
     * Two cases by hand (bag 7 planks + 2 sticks, a wooden pickaxe): a new table = craft 0.3 + place 0.15 + pickaxe 0.3 + the
     * take-along 3.75 + its materials station.materialS 10 = 14.5 s (10.4 s near the table: no take-along there). Table 20 m: walk 20 x 1.3 / 4.317 = 6.0 + craft 0.3 = 6.3 s:
     * walked, the new one dropped. Table 227 m: 68.4 s: a new one placed, the walk dropped.
     */
    @Test
    void thePriceComparisonPicksTheCheaperOfWalkBackAndMakeNew() {
        ScenariosTest.world("wooden");
        Map<String, Integer> bag = Map.of("minecraft:oak_planks", 7, "minecraft:stick", 2);
        Chain near = NeedChain.resolve("wooden_pickaxe", 1, FarStationWearUnreachableTest.world(bag, new Spot(20, 64, 0, 20, "minecraft:crafting_table"), Map.of()));
        assertTrue(near.ok(), near.why());
        assertEquals(6.32, near.seconds(), 0.1, near.why());
        assertTrue(near.steps().stream().noneMatch(s -> s instanceof Step.PlaceNear), near.steps().toString());
        assertTrue(near.dropped().stream().anyMatch(d -> d.startsWith("place a new table near me: ")), near.dropped().toString());

        Chain far = NeedChain.resolve("wooden_pickaxe", 1, FarStationWearUnreachableTest.world(bag, new Spot(227, 64, 0, 227, "minecraft:crafting_table"), Map.of()));
        assertTrue(far.ok(), far.why());
        assertEquals(14.5, far.seconds(), 0.1, far.why());
        assertTrue(far.steps().stream().anyMatch(s -> s instanceof Step.PlaceNear), far.steps().toString());
        assertTrue(far.dropped().stream().anyMatch(d -> d.startsWith("walk to the known table 227 m: 68.")), far.dropped().toString());
    }

    /** The surface for a station is the leaf-free top: a bot under a tree (leaves over the feet) is on the surface. */
    @Test
    void aLeafLayerOverTheFeetIsStillTheSurface() {
        // ground top block at 63, feet at 64, air 64-66, leaves 67-68: MOTION_BLOCKING's first cell over its top = 69
        int noLeaves = SurfaceHeight.noLeaves(true, 69, -64, y -> y >= 64);
        assertEquals(64, noLeaves);
        assertTrue(StationPlace.onSurface(64, noLeaves), "under the tree: on the surface");
        assertFalse(StationPlace.onSurface(64, 69), "WORLD_SURFACE's reading (the leaves' top) would call it under ground");
        assertFalse(StationPlace.onSurface(40, noLeaves), "24 under: under ground");
        assertFalse(StationPlace.onSurface(64, SurfaceHeight.NOT_KNOWN), "not known: never claimed");

        StationPlace.Ask up = StationPlace.ask(new int[]{0, 64, 0}, noLeaves, null);
        assertTrue(up.surface());
        StationPlace.Ask base = StationPlace.ask(new int[]{0, 64, 0}, noLeaves, new int[]{10, 64, 0});
        assertArrayEquals(new int[]{10, 64, 0}, base.center());
        StationPlace.Ask farBase = StationPlace.ask(new int[]{0, 40, 0}, noLeaves, new int[]{100, 64, 0});
        assertArrayEquals(new int[]{0, 40, 0}, farBase.center());
        assertFalse(farBase.surface());
    }
}
