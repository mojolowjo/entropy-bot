package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.GenWhere;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.sense.CaveIndex;
import io.github.mojolowjo.entropybot.sense.Sightings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6b run EP (05:57:33: in a pond at 231 62 85 the dig-down way said "dig down 0" on the assumed 3-deep layer -
 * the stone 4-5 down - and the collect repeated, parked; the 7-8 ore orders dropped every LARGE cave "past
 * cave.exploreMinutes 10"): the dig goes to the base block as the loaded column shows it; a cave's time is n / rate, its
 * minutes cap only the caving step's leg.
 */
class J6bEpTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** EP's column 231 _ 85 from the save (run-saves/runEP-z78j6l.zip, r.0.0.mca): y -> block id. */
    static String epColumn(int y) {
        return switch (y) {
            case 62 -> "minecraft:water";
            case 59, 60, 61, 55, 56 -> "minecraft:dirt";
            case 57, 58, 53, 54 -> "minecraft:stone";
            case 52 -> "minecraft:diorite";
            default -> y > 62 ? "minecraft:air" : "minecraft:stone";
        };
    }

    static NeedChain.World pond(int floor) {
        GenWhere.Base b = new GenWhere.Base("minecraft:stone", 8, "minecraft:deepslate", "noise settings minecraft:overworld");
        return Z74RunACTest.world(Map.of("minecraft:wooden_pickaxe", 1), Map.of()).withWhere(new NeedChain.WhereFacts(w -> null,
                () -> new NeedChain.Surface(0, 231, 63, 85, 0).withFloor(floor), () -> new int[]{231, 62, 85}, () -> b));
    }

    /**
     * EP's pond (the manager's rule 17 ruling): the floor is read through the open water only (dirt at 61, seen through
     * it); the PRICE is the game data's surface layer (3) under it - dig down 4, an estimate; the STEP digs until stone is
     * SEEN under the feet: dirt 61, 60, 59 dug, the feet on the stone at 58 - done there.
     */
    @Test
    void epsPond_pricesTheEstimate_digsUntilStoneIsSeen() {
        Z74RunACTest.data();
        int floor = NeedChain.floorIn(y -> epColumn(y).equals("minecraft:water"), 62);
        assertEquals(61, floor, "the pond's floor under its water (the water column is open and seen)");
        Chain c = NeedChain.resolve("cobblestone", 2, pond(floor));
        assertTrue(c.ok() && !c.explore(), c.why());
        Step.ToBand dig = (Step.ToBand) c.steps().get(0);
        assertEquals(Step.ToBand.digUntil(62 - NeedChain.BASE_REACH, 58, "minecraft:stone,minecraft:deepslate"), dig, "the dig until stone is seen, " + NeedChain.BASE_REACH + " at most: " + c.steps());
        assertTrue(c.why().contains("dig down 4 to stone") && c.why().contains("the pond's floor under its water") && c.why().contains("the dig goes until stone is seen"), c.why());
        // the step, on the blocks as they are seen: the feet go down; done when the block they stand on is stone
        List<Integer> dug = new ArrayList<>();
        int feet = 62;
        while (!dig.doneOn(epColumn(feet - 1))) { dug.add(feet - 1); feet--; }
        assertEquals(List.of(61, 60, 59), dug, "dirt 61-59 dug");
        assertEquals(59, feet, "the feet on the stone at 58: done");
    }

    /** No stone within the cap: the step's named fact (the price never read the column). */
    @Test
    void theCapHit_saysTheFact() {
        Step.ToBand dig = Step.ToBand.digUntil(62 - NeedChain.BASE_REACH, 58, "minecraft:stone,minecraft:deepslate");
        assertFalse(dig.doneOn("minecraft:dirt"));
        assertTrue(dig.doneOn("minecraft:deepslate"));
        assertEquals("no stone within 16 blocks under 231 62 85", dig.capFact(new int[]{231, 62, 85}));
        assertEquals("dig down until stone is seen (to y 46 at most)", dig.text());
        String src;
        try {
            src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/AaRuntime.java"));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertFalse(src.contains("baseTopIn"), "no reader of unseen column blocks remains");
    }

    /** EP's chestplate (8 ore): the LARGE cave stays priced (J6d: by its walks); its caving step is one leg of the cap. */
    @Test
    void epsChestplate_theLargeCaveIsPriced_theStepIsOneLeg() {
        CaveWay large = new CaveWay(179, 62, 51, 62, "minecraft:iron_ore", "iron", Double.NaN, Double.NaN, CaveIndex.Size.LARGE, -1, List.of());
        Chain c = NeedChain.resolve("raw_iron", 8, Z77CaveWayTest.world(large));
        assertTrue(Z77CaveWayTest.caveMined(c), "priced, never 'past cave.exploreMinutes': " + c.why());
        assertFalse(c.why().contains("past cave.exploreMinutes"), c.why());
        Step.Cave k = (Step.Cave) c.steps().stream().filter(s -> s instanceof Step.Cave).findFirst().orElseThrow();
        assertEquals(8, k.n());
        assertEquals((int) Tuning.get("cave.exploreMinutes"), k.minutes(), "the leg: cave.exploreMinutes");
        assertTrue(c.why().contains("+ 8 ore broken"), c.why());
    }

    /**
     * A cave walked to its leg's end with the need unmet (it gained something) stays offered - entered again; done with
     * none of the family left: out, said, never looped.
     */
    @Test
    void aCaveWalkedToItsLegsEnd_isEnteredAgain_notWhenDone() {
        Sightings s = new Sightings();
        s.knowCave("d", new CaveIndex.Known(0, 40, 0, CaveIndex.Size.LARGE, CaveIndex.Depth.STONE, List.of(Sightings.cell(1, 40, 0))), 1);
        List<Long> cells = new ArrayList<>();
        for (int x = 0; x < 200; x++) { cells.add(Sightings.cell(x, 40, 0)); cells.add(Sightings.cell(x, 41, 0)); }   // D15 pass two: 2 high, walkable
        s.caveCells("d", 0, 40, 0, cells);
        s.caveStood("d", 0, 40, 0, 0, 40, 0, 1);
        s.caveWalked("d", 0, 40, 0, 10, 2, 2, true);                    // the leg's end: 10 min, 2 ore seen, the need unmet
        var f = s.caveFacts("d", 0, 40, 0);
        assertTrue(f.offeredFor("iron") && !f.done(), "offered again: the next leg");
        assertEquals(0.2, f.oreSeenPerMinute(), 1e-9, "10 min walked, 2 ore seen: a record fact (J6d: no price reads it)");
        s.caveSaw("d", 0, 40, 0, cells);
        assertFalse(s.caveFacts("d", 0, 40, 0).offeredFor("iron"), "done, no iron left: out (not looped)");
    }
}
