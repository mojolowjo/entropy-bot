package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.AaDesires;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** aa-kit: the kit's worth, the recover after a death, the stash before dusk, the camp's chest chain. */
@org.junit.jupiter.api.extension.ExtendWith(io.github.mojolowjo.entropybot.aa.brain.VanillaData.class)
class KitTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    @Test
    void worthOfToolsFoodAndLoot() {
        double stonePick = ItemWorth.of("minecraft:stone_pickaxe").value();
        assertEquals(stonePick, Kit.toolValue("minecraft:stone_pickaxe"), 1e-9);
        assertTrue(Kit.toolValue("stone_sword") > 0 && Kit.toolValue("iron_chestplate") > 0);
        assertEquals(0, Kit.toolValue("iron_ore"));
        assertTrue(Kit.foodValue("cooked_beef") > 5);
        assertTrue(Kit.kept("bread") && Kit.kept("torch") && !Kit.kept("raw_iron"));
        Map<String, Integer> bag = Map.of("stone_pickaxe", 1, "raw_iron", 10, "cobblestone", 50);
        double prior = Tuning.get("kit.unknown");
        assertEquals(stonePick + 10 * prior + 50 * Kit.itemValue("cobblestone"), Kit.worth(bag), 1e-9);
        assertEquals(10 * prior, Kit.lootWorth(bag), 1e-9);      // plain blocks are no loot; raw_iron has no data: the logged prior
    }

    @Test
    void missingIsWhatTheBagLacks() {
        Map<String, Integer> dead = Map.of("stone_pickaxe", 1, "raw_iron", 10);
        double pick = Kit.itemValue("stone_pickaxe"), raw = Kit.itemValue("raw_iron");
        assertEquals(pick + 10 * raw, Kit.missing(dead, Map.of()), 1e-9);
        assertEquals(0, Kit.missing(dead, dead), "keepInventory: nothing to go back for");
        assertEquals(5 * raw, Kit.missing(dead, Map.of("stone_pickaxe", 1, "raw_iron", 5)), 1e-9);
    }

    /** A modded pack: nothing here is in any list, the roles come from the item data alone. */
    @Test
    void moddedGearFoodLightBenchAndOreAreHandledByRole() {
        TestData d = TestData.vanilla();
        d.tool("moddium:drill", ItemWorth.Role.PICKAXE, 9, 2, 700).food("moddium:glow_stew", 8, 3).block("moddium:lamp", 0, 15).block("moddium:bench", 2.5, 0)
                .block("moddium:rune_ore", 3, 0).block("moddium:rubble", 0.3, 0);
        d.station.add("moddium:bench");
        d.ore.add("moddium:rune_ore");
        d.stone.add("moddium:rubble");
        d.install();
        assertTrue(Kit.toolValue("moddium:drill") > 0, "a fake tool with speed 9 is gear");
        assertEquals(ItemWorth.of("moddium:drill").value(), Kit.toolValue("moddium:drill"), 1e-9);
        assertTrue(Kit.foodValue("moddium:glow_stew") > 0 && Kit.kept("moddium:glow_stew"));
        assertTrue(Kit.kept("moddium:drill") && Kit.kept("moddium:lamp") && Kit.kept("moddium:bench"), "gear, light and a bench are kept by role");
        assertFalse(Kit.kept("moddium:rune_ore") || Kit.kept("moddium:rubble"));
        assertEquals(0.01 * 3 * Tuning.get("worth.oreK"), Kit.itemValue("moddium:rune_ore"), 1e-9, "a modded ore: the ore tag stands for rarity");
        assertTrue(Kit.lootWorth(Map.of("moddium:rune_ore", 2)) > 0);
        assertEquals(0, Kit.lootWorth(Map.of("moddium:rubble", 50)), "filler is no loot");
    }

    @Test
    void anItemWithNoDataIsTheLoggedPriorNeverZero() {
        java.util.List<String> said = new java.util.ArrayList<>();
        ItemWorth.logTo(said::add);
        assertEquals(Tuning.get("kit.unknown"), Kit.itemValue("moddium:mystery"));
        Kit.itemValue("moddium:mystery");
        assertEquals(1, said.stream().filter(s -> s.contains("mystery")).count(), "said once");
        ItemWorth.logTo(null);
    }

    @Test
    void pThereRunsOutWithTheClock() {
        assertEquals(1, Kit.pThere(0, 0));
        assertEquals(0.5, Kit.pThere(120_000, 30), 1e-9);
        assertEquals(0, Kit.pThere(300_000, 0));
        assertEquals(-1, Kit.recoverValue(30, 0));
        assertEquals(-1, Kit.recoverValue(1, 1));
        assertEquals(15, Kit.recoverValue(30, 0.5), 1e-9);
        assertEquals(60 + 60 * 2, Kit.recoverCost(60, 1), 1e-9);
    }

    @Test
    void deathRecordedThenDroppedAfterTheClock() {
        bb.in.pos = new int[]{100, 64, 0};
        bb.in.bag = Map.of("stone_pickaxe", 1, "bread", 4);
        Kit.died(bb, clock.now());
        assertNotNull(Kit.of(bb));
        bb.in.bag = Map.of();                       // respawned with nothing
        bb.in.pos = new int[]{0, 64, 0};
        Desire d = AaDesires.recover();
        double v = d.score.applyAsDouble(bb);
        assertTrue(v > 10, "a stone pickaxe (bread not counted), close in time: " + v);
        assertFalse(d.metNow(bb));
        clock.advance(Kit.DESPAWN_MS);
        assertEquals(-1, d.score.applyAsDouble(bb));
        assertNull(Kit.of(bb), "dropped after the clock");
    }

    @Test
    void recoverScoresInTheBrainWithItsCost() {
        bb.in.pos = new int[]{0, 64, 0};
        bb.in.bag = Map.of("iron_pickaxe", 1);
        Kit.died(bb, clock.now());
        bb.in.bag = Map.of();
        Brain br = new Brain("brain", "t", AaDesires.recover());
        var s = br.scoreAll(bb).get(0);
        assertTrue(s.score() > 0 && s.parts().contains("live"), s.parts());
    }

    @Test
    void stashRisesBeforeDuskAndBeforeAFarTrip() {
        assertEquals(0, Kit.soon(1000, false, false));
        assertEquals(0.5, Kit.soon(Night.DUSK - Kit.stashLead() / 2, false, false), 1e-9);
        assertEquals(1, Kit.soon(Night.DUSK + 10, false, false));
        assertEquals(0, Kit.soon(15_000, true, false), "night: the shelter's turn");
        assertEquals(1, Kit.soon(1000, false, true), "a far trip");
        assertEquals(-1, Kit.stashValue(2, 1, 1));
        assertEquals(-1, Kit.stashValue(20, 0, 1), "Peaceful");
        assertEquals(20, Kit.stashValue(20, 1, 1), 1e-9);
        assertEquals(Kit.stashMax(), Kit.stashValue(500, 1, 1));
    }

    @Test
    void stashParkedWithoutAChestAndScoredWithOne() {
        bb.in.hungerDrains = true;
        bb.in.bag = Map.of("raw_iron", 40);
        bb.in.dayTime = Night.DUSK - 10;
        bb.in.basePos = new int[]{0, 64, 0};
        bb.in.pos = new int[]{0, 64, 0};
        Brain br = new Brain("brain", "t", AaDesires.stash(null));
        assertTrue(br.scoreAll(bb).get(0).skip().contains("no base chest"));
        bb.in.baseChests = true;
        var s = br.scoreAll(bb).get(0);
        assertNull(s.skip());
        assertTrue(s.score() > 20, s.parts());
    }

}
