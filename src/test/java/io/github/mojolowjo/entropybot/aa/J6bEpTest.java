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

    static NeedChain.World pond(int baseTop) {
        GenWhere.Base b = new GenWhere.Base("minecraft:stone", 8, "minecraft:deepslate", "noise settings minecraft:overworld");
        return Z74RunACTest.world(Map.of("minecraft:wooden_pickaxe", 1), Map.of()).withWhere(new NeedChain.WhereFacts(w -> null,
                () -> new NeedChain.Surface(0, 231, 63, 85, 0).withBaseTop(baseTop), () -> new int[]{231, 62, 85}, () -> b));
    }

    /** EP's pond: the loaded column's first stone at 58 under water and dirt - the dig is 3 blocks (the feet to 59) and runs. */
    @Test
    void epsPond_theDigGoesToTheStoneTheColumnShows() {
        Z74RunACTest.data();
        int baseTop = NeedChain.baseTopIn(J6bEpTest::epColumn, 62, "minecraft:stone", "minecraft:deepslate");
        assertEquals(58, baseTop, "the first stone under the water's top (62) and the dirt (59-61)");
        Chain c = NeedChain.resolve("cobblestone", 2, pond(baseTop));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(new Step.ToBand(59, 59), c.steps().get(0), "the feet down 3 to stand on the stone: " + c.steps());
        assertTrue(c.why().contains("dig down 3 to stone") && c.why().contains("stone first at y 58, the loaded column"), c.why());
        assertFalse(c.why().contains("assumed 3 deep"), "never the assumed layer when the column was read");
    }

    /** A read column with no base block within reach: no such way, said (rule 7). */
    @Test
    void noBaseLayerWithinReach_noWay_said() {
        Z74RunACTest.data();
        assertEquals(NeedChain.Surface.BASE_NONE, NeedChain.baseTopIn(y -> "minecraft:dirt", 62, "minecraft:stone", "minecraft:deepslate"));
        assertEquals(NeedChain.Surface.BASE_NONE, NeedChain.baseTopIn(y -> null, 62, "minecraft:stone", "minecraft:deepslate"), "not loaded: never assumed");
        Chain c = NeedChain.resolve("cobblestone", 2, pond(NeedChain.Surface.BASE_NONE));
        assertTrue(c.why().contains("holds no stone layer within " + NeedChain.BASE_REACH + " blocks of its top (the loaded column)"), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.ToBand b && b.minY() == 59), c.steps().toString());
    }

    /** EP's chestplate (8 ore, 16 min at the prior): the LARGE cave stays priced at n / rate; its caving step is one leg of the cap. */
    @Test
    void epsChestplate_theLargeCaveIsPriced_theStepIsOneLeg() {
        CaveWay large = new CaveWay(179, 62, 51, 62, "minecraft:iron_ore", "iron", 0.5, false, CaveIndex.Size.LARGE, -1, List.of());
        Chain c = NeedChain.resolve("raw_iron", 8, Z77CaveWayTest.world(large));
        assertTrue(Z77CaveWayTest.caveMined(c), "priced, never 'past cave.exploreMinutes': " + c.why());
        assertFalse(c.why().contains("past cave.exploreMinutes"), c.why());
        Step.Cave k = (Step.Cave) c.steps().stream().filter(s -> s instanceof Step.Cave).findFirst().orElseThrow();
        assertEquals(8, k.n());
        assertEquals((int) Tuning.get("cave.exploreMinutes"), k.minutes(), "the leg: cave.exploreMinutes");
        assertTrue(c.why().contains("x 8 ore"), c.why());
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
        for (int x = 0; x < 200; x++) cells.add(Sightings.cell(x, 40, 0));
        s.caveCells("d", 0, 40, 0, cells);
        s.caveStood("d", 0, 40, 0, 0, 40, 0, 1);
        s.caveWalked("d", 0, 40, 0, 10, 2, 2, true);                    // the leg's end: 10 min, 2 ore seen, the need unmet
        var f = s.caveFacts("d", 0, 40, 0);
        assertTrue(f.offeredFor("iron") && !f.done(), "offered again: the next leg");
        assertEquals(0.2, f.rate(0.5), 1e-9, "10 min walked: measured (past the 2 min floor)");
        s.caveSaw("d", 0, 40, 0, cells);
        assertFalse(s.caveFacts("d", 0, 40, 0).offeredFor("iron"), "done, no iron left: out (not looped)");
    }
}
