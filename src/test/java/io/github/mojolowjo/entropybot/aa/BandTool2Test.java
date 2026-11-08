package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
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
 * job-bandtool2 (run JAZ 07:47-07:53: the band way for raw_iron lost every re-price, its stone pickaxe chain standing on a
 * tree walk still computing): the band way's tool chain is the cave way's ({@code NeedChain.toolFirst}); a computing walk
 * in it is charged its straight metres, so the way is priced; an explore-only log source ends both ways in that explore.
 */
class BandTool2Test {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    @Test
    void aComputingTreeWalk_theBandWayIsPricedAndUnderA3400sCave() {
        // every walk at the surface computing (the tree, the stone), the dig column under y 40 priced
        PathPrice paths = (x, y, z, d) -> y < 40 ? PathPrice.Answer.priced(new PathPrice.Walk(FakePaths.walkS(d))) : PathPrice.Answer.computing("");
        Chain c = NeedChain.resolve("raw_iron", 3, BandToolTest.world(Map.of()).withPath(paths));
        String band = BandToolTest.bandPart(c.why());
        assertTrue(band.startsWith("the band way: after stone_pickaxe (<- "), c.why());
        assertTrue(band.contains("dig down to y "), band);
        assertTrue(c.ok(), c.why());
        assertFalse(c.steps().stream().anyMatch(s -> s instanceof Step.AwaitPaths), "priced, not standing: " + c.steps());
        assertTrue(c.seconds() < 3400, "under the JAZ cave's 3400 s: " + c.seconds());
    }

    @Test
    void noLogSourceAtAll_theBandWayCarriesTheExplore() {
        ScenariosTest.world("wooden", "stone");
        NeedChain.World w = ScenariosTest.w(Map.of(), false, false, Map.of("cobblestone", List.of(ScenariosTest.STONE_12)))
                .withMobs(x -> List.of(), new io.github.mojolowjo.entropybot.aa.brain.LootBook(List.of(
                        new io.github.mojolowjo.entropybot.aa.brain.LootBook.Source(io.github.mojolowjo.entropybot.aa.brain.LootBook.Kind.BLOCK, Z77ExploreTest.IRON_ORE, "minecraft:raw_iron", 1, 1, false, null),
                        new io.github.mojolowjo.entropybot.aa.brain.LootBook.Source(io.github.mojolowjo.entropybot.aa.brain.LootBook.Kind.BLOCK, "minecraft:stone", "minecraft:cobblestone", 1, 1, false, null)), "test"))
                .withWhere(new NeedChain.WhereFacts(JobBandChainTest::where, () -> new NeedChain.Surface(0, 0, 64, 0, 0), () -> new int[]{0, 64, 0}));
        Chain c = NeedChain.resolve("raw_iron", 3, w);
        assertTrue(c.why().contains("the band way (after stone_pickaxe (<- "), c.why());
    }

    @Test
    void theLineText_afterThePickaxeThenTheDig() {
        Chain c = NeedChain.resolve("raw_iron", 3, BandToolTest.world(Map.of()));
        String band = BandToolTest.bandPart(c.why());
        assertTrue(band.matches("(?s)the band way: after stone_pickaxe \\(<- .*\\), dig down to y \\d+ .*"), band);
    }
}
