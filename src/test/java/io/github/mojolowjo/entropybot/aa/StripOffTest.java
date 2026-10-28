package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.StripWays;
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
 * 0.39.z78 strip-off (owner 2026-10-27: the strip disabled in general): with aa ways strip off the planner offers no strip
 * way - the live World's strip facts answer none (StripWays.gate, AaRuntime.needWorld) - so a batch lacking iron prices a
 * cave, a seen vein or the explore, and never a Step.Strip; the batch's derivation says the switch.
 */
class StripOffTest {
    static final Map<String, Integer> BAG = Map.of("minecraft:stone_pickaxe", 1);

    @AfterEach
    void clear() {
        StripWays.set(true);
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** The live World as AaRuntime builds it: the strip facts through the owner's switch. */
    static NeedChain.World world(CaveWay cw, Spot... seen) {
        ScenariosTest.world("wooden", "stone");
        NeedChain.StripBand band = Z62RunSTest.band("east");
        return ScenariosTest.w(BAG, true, true, seen.length == 0 ? Map.of() : Map.of("raw_iron", List.of(seen)))
                .withStrip(StripWays.gate(word -> word.contains("raw_iron") || word.contains("iron_ore") ? band : null))
                .withCave(word -> cw != null && (word.contains("raw_iron") || word.contains("iron_ore")) ? cw : null);
    }

    static boolean strips(Chain c) { return c.steps().stream().anyMatch(s -> s instanceof Step.Strip || s instanceof Step.ToStrip); }

    @Test
    void stripsOff_aBatchLackingIronPricesCavesSeenVeinsOrTheExplore_neverAStrip() {
        // the control: on, the strip way is the price for 12 iron with nothing seen
        Chain on = NeedChain.resolve("raw_iron", 12, world(null));
        assertTrue(strips(on), on.why());

        StripWays.set(false);
        Chain explore = NeedChain.resolve("raw_iron", 12, world(null));
        assertFalse(strips(explore), explore.why());
        assertTrue(explore.explore(), "nothing else known: the explore - " + explore.why());

        Chain vein = NeedChain.resolve("raw_iron", 1, world(null, Z77CaveWayTest.NEAR));     // the seen vein holds 1
        assertFalse(strips(vein), vein.why());
        assertTrue(vein.ok() && !vein.explore() && vein.steps().stream().anyMatch(s -> s instanceof Step.Collect || s instanceof Step.MineAt), "the seen vein: " + vein.steps());

        Chain cave = NeedChain.resolve("raw_iron", 12, world(Z77CaveWayTest.cave(30, 3)));
        assertFalse(strips(cave), cave.why());
        assertTrue(Z77CaveWayTest.caveMined(cave), "the cave way: " + cave.steps());
        // the batch's derivation: commands.StripOffBatchTest (the kit world with its "where" facts)
    }

    @Test
    void theSwitchIsSaidOncePerOrder() {
        StripWays.set(false);
        assertTrue(StripWays.sayOnce("order 1"));
        assertFalse(StripWays.sayOnce("order 1"), "once per order");
        assertTrue(StripWays.sayOnce("order 2"));
        StripWays.set(true);
        assertFalse(StripWays.sayOnce("order 3"), "on: nothing to say");
        StripWays.set(false);
        assertTrue(StripWays.sayOnce("order 1"), "switched again: said again");
    }
}
