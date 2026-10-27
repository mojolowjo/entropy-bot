package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.StripBand;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 explore (run DC, laptop, 2026-10-27 01:37:05, docs/runs/runDC-z78c): the strip east at feet y 16 ended "no path"
 * after 21 cells, inside its tunnel. The fact held the way's own key "strip ores|16|east", and NeedChain.World#withoutSources
 * then dropped the whole strip way (it skips only heading keys), so north, south and west were never offered. The order
 * climbed 49 blocks (105 s) and explored 5 minutes on the surface (495 blocks). The fix: a no-path inside a feet strip's
 * tunnel is a fact about the HEADING at that Y (FactChange#stripNoPath). The next open heading at the same Y is the way.
 * It is priced, so it ranks before every way that ends in an explore (NeedChain's comparator).
 */
class Z77DcHeadingTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** Both iron ores drop one raw_iron (the game's loot data). */
    static final LootBook BOOK = new LootBook(List.of(
            new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:iron_ore", "minecraft:raw_iron", 1, 1, false, null),
            new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:deepslate_iron_ore", "minecraft:raw_iron", 1, 1, false, null)), "test");

    /** DC's shape: the bot in the east tunnel at feet y 16, 49 blocks of rock over it, the surface cell 50 up; the band at its feet. */
    static NeedChain.World dc(StripBand b) {
        return Z62RunSTest.world(Map.of("minecraft:stone_pickaxe", 1), b)
                .withMobs(x -> List.of(), BOOK)
                .withWhere(new NeedChain.WhereFacts(Z77ExploreTest::where, () -> new NeedChain.Surface(49, -228, 66, 48, 17.0),
                        () -> new int[]{b.x(), b.feetY(), b.z()}));
    }

    static Step.Strip eastStrip() {
        return new Step.Strip(Z62RunSTest.IRON, Map.of("iron", 12), 16, "east", 0);
    }

    @Test
    void aNoPathInsideTheTunnel_holdsTheHeading_andTheNextHeadingIsTheWay() {
        ScenariosTest.world("wooden", "stone");
        StripBand band = Z62RunSTest.band("east");
        assertEquals(List.of("east", "south", "west", "north"), band.headings(), "all four headings open at the feet, as at 01:37");
        Chain before = NeedChain.resolve("raw_iron", 12, dc(band));
        assertTrue(before.ok() && !before.explore(), before.why());
        assertEquals("east", Z62RunSTest.strip(before).heading(), "before: the strip east");

        // the fact the run's end now gives
        String why = "strip: no path (strip east feet y 16: 21 blocks, 0 clusters mined); the next cell (Baritone's goal) -1 16 20: allowed";
        FactChange.stripNoPath(eastStrip(), 21, why);
        FactChange.Fact f = FactChange.take();
        assertEquals("source " + NeedChain.headingKey(16, "east"), f.key());
        assertEquals(NeedChain.headingKey(16, "east"), FactChange.ownSourceKey(f), "the heading, not the way's key, is held");
        assertTrue(f.text().contains("found no path after 21 cells: " + why), f.text());

        // run DC as it was: the way's own key held - no strip at all, the climb and the surface explore
        Chain was = NeedChain.resolve("raw_iron", 12, dc(band).withoutSources(Map.of(band.key(), why)));
        assertTrue(was.explore(), was.why());
        assertTrue(was.steps().stream().noneMatch(s -> s instanceof Step.Strip), was.why());
        assertTrue(was.steps().stream().anyMatch(s -> s instanceof Step.Explore), was.why());

        // the fix: the order's own hold (the heading) - the strip south at y 16, priced, no explore
        Chain now = NeedChain.resolve("raw_iron", 12, dc(band).withoutSources(Map.of(FactChange.ownSourceKey(f), f.text())));
        assertTrue(now.ok() && !now.explore(), now.why());
        assertEquals("south", Z62RunSTest.strip(now).heading(), "the next heading clockwise");
        assertEquals(16, Z62RunSTest.strip(now).y());
        assertTrue(now.steps().stream().noneMatch(s -> s instanceof Step.Climb || s instanceof Step.Explore), now.why());

        // the other orders read it as a world fact kept by the tunnel's start (Goals.replan -> WorldFacts.scoped): the same
        String world = NeedChain.headingFrom(NeedChain.headingKey(16, "east"), NeedChain.stripStart(band));
        assertEquals("south", Z62RunSTest.strip(NeedChain.resolve("raw_iron", 12, dc(band).withoutSources(Map.of(world, f.text())))).heading());

        // every heading ended so at y 16: no strip way, and only then the climb and the surface explore
        Map<String, String> all = new java.util.HashMap<>();
        for (String h : band.headings()) all.put(NeedChain.headingKey(16, h), "no path");
        Chain none = NeedChain.resolve("raw_iron", 12, dc(band).withoutSources(all));
        assertTrue(none.explore() && none.steps().stream().noneMatch(s -> s instanceof Step.Strip), none.why());
    }

    /** A strip from fresh ground keeps its own key (its tunnel only), as before; lava or no path down still drop the way. */
    @Test
    void aStripFromFreshGround_keepsItsOwnKey() {
        Step.Strip fresh = new Step.Strip(Z62RunSTest.IRON, Map.of("iron", 12), 16, "east", 0, "area 3,4");
        FactChange.stripNoPath(fresh, 9, "strip: no path (9 blocks)");
        FactChange.Fact f = FactChange.take();
        assertEquals("source strip " + fresh.key(), f.key());
        assertNull(FactChange.ownSourceKey(f), "the failed step's key applies");
    }
}
