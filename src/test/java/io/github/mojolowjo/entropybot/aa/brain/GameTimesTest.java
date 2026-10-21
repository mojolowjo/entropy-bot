package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.CoalOptions;
import io.github.mojolowjo.entropybot.aa.IronOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** aa-times: the game's break-time and walk-speed formulas (Player.getDigSpeed, BlockBehaviour.getDestroyProgress). */
class GameTimesTest {
    static final double STONE = 1.5;

    @AfterEach
    void reset() { Unit.setLiveBps(0); }

    @Test
    void stoneByHandIsSevenAndAHalfSeconds() {
        assertEquals(7.5, GameTimes.breakSeconds(STONE, 1, false), 1e-9);
    }

    @Test
    void stonePickaxeIsPointSix() {
        assertEquals(0.6, GameTimes.breakSeconds(STONE, 4, true), 1e-9);          // 11.25 ticks -> 12
    }

    @Test
    void ironPickaxeIsPointFour() {
        assertEquals(0.4, GameTimes.breakSeconds(STONE, 6, true), 1e-9);          // 7.5 ticks -> 8
    }

    @Test
    void efficiencyThreeIsShorter() {
        double plain = GameTimes.breakSeconds(STONE, 4, true);
        double eff3 = GameTimes.breakSeconds(STONE, 4, true, GameTimes.Dig.PLAIN.withEfficiency(3));
        assertTrue(eff3 < plain, eff3 + " < " + plain);
        assertEquals(0.2, eff3, 1e-9);                                           // speed 4 + 10 = 14: 3.2 ticks -> 4
        // efficiency never helps a hand (speed 1): Player.getDigSpeed adds it only above 1
        assertEquals(7.5, GameTimes.breakSeconds(STONE, 1, false, GameTimes.Dig.PLAIN.withEfficiency(3)), 1e-9);
    }

    @Test
    void underWaterIsFiveTimesSlowerWithoutAquaAffinity() {
        double dry = GameTimes.breakSeconds(STONE, 4, true);
        double wet = GameTimes.breakSeconds(STONE, 4, true, GameTimes.Dig.PLAIN.withWater(true));
        assertEquals(5 * dry, wet, 0.2);                                         // 56.25 ticks -> 57 (ceil)
        GameTimes.Dig aqua = new GameTimes.Dig(0, -1, -1, 1, 1.0, true, true);
        assertEquals(dry, GameTimes.breakSeconds(STONE, 4, true, aqua), 1e-9);
    }

    @Test
    void offTheGroundIsFiveTimesSlower() {
        double wet = GameTimes.breakSeconds(STONE, 4, true, GameTimes.Dig.PLAIN.withGround(false));
        assertEquals(3.0, wet, 0.2);
    }

    @Test
    void wrongToolIsHandSpeed() {
        // a stone needs a pickaxe: an axe's speed on it is 1 and it is not the correct tool -> 7.5 s like the hand
        assertEquals(GameTimes.breakSeconds(STONE, 1, false), GameTimes.breakSeconds(STONE, 1, false), 1e-9);
        // a pickaxe of the wrong tier (wood on diamond ore, hardness 3): fast speed but the 100 divisor
        assertEquals(Math.ceil(3 * 100 / 2.0) / 20, GameTimes.breakSeconds(3, 2, false), 1e-9);
    }

    @Test
    void hasteAndFatigue() {
        GameTimes.Dig haste2 = new GameTimes.Dig(0, 1, -1, 1, 0.2, false, true);
        assertEquals(4 * 1.4, GameTimes.digSpeed(4, haste2), 1e-9);
        GameTimes.Dig fatigue1 = new GameTimes.Dig(0, -1, 0, 1, 0.2, false, true);
        assertEquals(1.2, GameTimes.digSpeed(4, fatigue1), 1e-9);
    }

    @Test
    void unbreakableAndInstant() {
        assertTrue(Double.isInfinite(GameTimes.breakSeconds(-1, 100, true)));
        assertEquals(0.05, GameTimes.breakSeconds(0, 1, false), 1e-9);
    }

    @Test
    void walkSpeedDefault() {
        assertEquals(4.317, GameTimes.walkBps(0.1, false), 1e-9);
        assertEquals(4.317, GameTimes.walkBps(0.13, true), 1e-9);                // the sprint modifier taken out
        assertEquals(4.317 * 1.2, GameTimes.walkBps(0.12, false), 1e-9);         // Speed I
        assertEquals(4.317 * 1.3, GameTimes.sprintBps(4.317), 1e-9);
        assertEquals(4.317, GameTimes.walkBps(0, false), 1e-9);                  // unreadable: the constant
    }

    @Test
    void swimSpeedWithDepthStrider() {
        assertEquals(2.2, GameTimes.swimBps(4.317, 0), 1e-9);
        assertEquals(4.317, GameTimes.swimBps(4.317, 1), 1e-9);                  // Depth Strider III: land speed
    }

    @Test
    void unitUsesTheLiveSpeedAndFallsBack() {
        assertEquals(10 / 4.317, Unit.seconds(10), 1e-9);
        Unit.setLiveBps(8.634);
        assertTrue(Unit.live());
        assertEquals(10 / 8.634, Unit.seconds(10), 1e-9);
        assertEquals(8.634, Unit.blocks(1), 1e-9);
        Unit.setLiveBps(0);
        assertFalse(Unit.live());
        assertEquals(10 / 4.317, Unit.seconds(10), 1e-9);
    }

    @Test
    void toolWearKitCarriesTheGameSeconds() {
        ToolWear.Kit plain = new ToolWear.Kit(4f, 131, ToolWear.INF);
        assertEquals(0.6, plain.toolSeconds(), 1e-9);
        assertEquals(7.5, plain.handSeconds(), 1e-9);
        ToolWear.Kit wet = new ToolWear.Kit(4f, 131, ToolWear.INF, 2.85, 37.5);     // read under water
        assertEquals(2.85, wet.toolSeconds(), 1e-9);
        ToolWear.Priced use = ToolWear.best(10, wet);
        assertEquals(ToolWear.Way.USE, use.way());
        assertTrue(use.terms().startsWith("10 x 2.85s"), use.terms());
        assertEquals(10 * 2.85 + 10 / 131.0 * ToolWear.GATHER_S, use.seconds(), 1e-6);   // the blocks at the game's time + wear
    }

    @Test
    void ironAndCoalDigTermsFromTheGame() {
        assertEquals(16 * 3 + 2 * 6, IronOptions.digCost(18), 1e-9);                                   // the old constant
        double live = Unit.blocks(0.6);                                                                  // a stone pickaxe: 2.59 walk-blocks
        assertEquals(16 * live + 2 * (live + 3), IronOptions.digCost(18, live), 1e-9);
        IronOptions.Facts f = new IronOptions.Facts(List.of(), 64, false, 0, false, -1, false, "x", -1, false, false, 0, null, null, null, -1, live);
        assertEquals(live, f.digPerBlock(), 1e-9);
        IronOptions.Facts old = new IronOptions.Facts(List.of(), 64, false, 0, false, -1, false, "x", -1, false, false, 0);
        assertEquals(IronOptions.DIG_BLOCK, old.digPerBlock(), 1e-9);
        CoalOptions.CoalFacts c = new CoalOptions.CoalFacts(8, 1, List.of(), List.of(), List.of(), 0, 0, false, -1, false, 0, live);
        assertEquals(live, c.withNeed(3).digPerBlock(), 1e-9);
    }
}
