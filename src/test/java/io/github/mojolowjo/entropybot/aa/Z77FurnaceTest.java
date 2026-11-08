package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepNeeds;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.craft.OpenWait;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 furnace (run BN, owner's seed, 11:39:27): the furnace at 23 4 12 "did not open" two ticks after the fork's
 * click under ground; the repair then read the fuel inside that furnace as a bag shortage and walked to the surface furnace
 * at -8 63 13 (~60 up), "Baritone stopped short", no way. (1) the open's wait counts from the click and retries once;
 * (2) a furnace that never opens is a station fact, and the repair prices placing one here against the far walk.
 */
class Z77FurnaceTest {
    static final double TOL = 0.1;

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    // ---- part 1: the open's own wait ----

    /** BN: the step's clock was 300+ ticks old; the menu had 2 ticks since the click. Counted from the click: wait. */
    @Test
    void theOpenWaitsFromTheClickNotFromTheStepsStart() {
        assertEquals(OpenWait.Next.WAIT, OpenWait.noMenu(2, false), "2 ticks after the click (BN's failure): wait for the menu");
        assertEquals(80, OpenWait.waitTicks(), "the old literal, its start moved");
        assertEquals(OpenWait.Next.WAIT, OpenWait.noMenu(80, false), "4 s after the click: still waiting");
        assertEquals(OpenWait.Next.RETRY, OpenWait.noMenu(81, false), "no menu in 4 s: one more click");
        assertEquals(OpenWait.Next.FAIL, OpenWait.noMenu(81, true), "after the one retry: the step fails");
        assertEquals(OpenWait.Next.WAIT, OpenWait.noMenu(2, true), "the retry's own wait counts from its click too");
    }

    @Test
    void theFailureSaysTheInteractsFacts() {
        String r = OpenWait.reason("23 4 12", 81, "", 1.62, 4.5, "on it", "furnace");
        assertEquals("the container at 23 4 12 did not open 81 ticks after the click (no menu came; screen: none; eyes 1.6 m from"
                + " its centre, in reach 4.5; crosshair: on it; the block: furnace)", r);
        String far = OpenWait.reason("23 4 12", 81, "ChatScreen", 5.2, 4.5, "on no block", "furnace");
        assertTrue(far.contains("screen: ChatScreen") && far.contains("OUT of reach 4.5") && far.contains("crosshair: on no block"), far);
        assertTrue(OpenWait.reason("1 2 3", 81, null, 1, Double.NaN, null, null).contains("reach not read; crosshair: not read; the block: not read"));
    }

    // ---- part 2: the station fact wins over the bag's shortfall ----

    /**
     * BN 11:39:27.815: with no fact from the hands the failed smelt's fuel (inside the furnace) read as "acacia_slab: 6 of 11
     * in the bag" - a bag fact, the furnace kept. With the open's station fact (Crafting.openRefused) the re-plan gets the
     * station: its key "station " + the smelt kind, the furnace named.
     */
    @Test
    void aFurnaceThatNeverOpenedIsTheStepsFactNotTheFuelInIt() {
        assertTrue(replanFact(true).key().startsWith("station " + ItemWorth.SMELT), replanFact(true).toString());
        assertTrue(replanFact(true).text().contains("furnace at 23 4 12 unusable"), replanFact(true).toString());
        assertTrue(replanFact(false).key().startsWith("bag "), "before the fix: " + replanFact(false));
    }

    private static FactChange.Fact replanFact(boolean stationFact) {
        FactChange.clear();
        FactChange.Fact[] got = new FactChange.Fact[1];
        StepNeeds slabs = new StepNeeds(null, 0, Map.of("minecraft:acacia_slab", 11), null);
        Steps run = new Steps(new Steps.Hands() {
            @Override public String start(Step s) { return null; }
            @Override public Car.Status status(Step s) {
                if (stationFact) FactChange.stationUnusable(ItemWorth.SMELT, "furnace", "23 4 12", "the container at 23 4 12 did not open 81 ticks after the click (...) - after one more click");
                return Car.Status.failed("error: the container at 23 4 12 did not open (while smelting 8 iron_ingot)");
            }
            @Override public void cancel(String why) {}
            @Override public StepNeeds needs(Step s) { return slabs; }
            @Override public StepNeeds.Bag bag() { return new StepNeeds.Bag() {
                @Override public int bestTier(String kind) { return 0; }
                @Override public String bestTool(String kind) { return null; }
                @Override public int usesLeft(String id) { return 0; }
                @Override public int count(String id) { return id.equals("minecraft:acacia_slab") ? 6 : 0; }
            }; }
        });
        run.start("goal.iron_pickaxe", List.of(new Step.Smelt("minecraft:iron_ingot", 8, true)), 0, (failed, fact, why) -> {
            got[0] = fact;
            return new Steps.Routine(List.of(new Step.Wait(1)), null);
        });
        for (int t = 1; t < 5 && got[0] == null; t++) run.tick(t);
        assertNotNull(got[0], run.why());
        return got[0];
    }

    // ---- part 2: the repair's ways after the refusal ----

    /** BN's world after the refusal: the next known furnace is the surface one, 67.1 m from 24 4 12 (32 x, 59 up, 1 z). */
    static final Spot SURFACE = new Spot(-8, 63, 13, 67.1, "minecraft:furnace");

    private static World world(Map<String, Integer> bag) {
        ScenariosTest.world("wooden", "stone");
        Map<String, List<Spot>> ids = new HashMap<>();
        return FakePaths.paths(new World(bag, true, true, w -> ScenariosTest.lookup(ids, w), null, null, null, 0, null,
                k -> ItemWorth.SMELT.equals(k) ? SURFACE : null));
    }

    /**
     * By hand: the walk to the surface furnace 67.1 x 1.3 / 4.317 = 20.21 s; a furnace from the bag's 8 cobblestone at the
     * table here 0.3 + placed 0.15 = 0.45 s. The smelt of 2 is the same both ways, so the placed way is 19.76 s cheaper than
     * the walk (the walked chain priced with no cobblestone: placing ends in an explore, the walk is the honest way).
     */
    @Test
    void underGroundWith8CobblestoneTheNextWayIsPlaceAFurnaceHere() {
        Map<String, Integer> bag = Map.of("minecraft:raw_iron", 2, "minecraft:cobblestone", 8, "minecraft:coal", 2, "minecraft:stone_pickaxe", 1);
        Chain placed = NeedChain.resolve("iron_ingot", 2, world(bag));
        assertTrue(placed.ok(), placed.why());
        int place = -1, smelt = -1;
        for (int i = 0; i < placed.steps().size(); i++) {
            if (placed.steps().get(i) instanceof Step.PlaceNear p && p.item().contains("furnace")) place = i;
            if (placed.steps().get(i) instanceof Step.Smelt && smelt < 0) smelt = i;
        }
        assertTrue(place >= 0 && smelt > place, "place a furnace here, then smelt: " + placed.steps());
        assertTrue(placed.steps().stream().noneMatch(s -> s instanceof Step.ToStation), "no walk to the surface furnace: " + placed.steps());
        assertTrue(placed.dropped().stream().anyMatch(d -> d.startsWith("walk to the known furnace 67 m: 20.2")), placed.dropped().toString());

        Map<String, Integer> none = Map.of("minecraft:raw_iron", 2, "minecraft:coal", 2, "minecraft:stone_pickaxe", 1);
        Chain walked = NeedChain.resolve("iron_ingot", 2, world(none));
        assertTrue(walked.ok(), walked.why());
        assertTrue(walked.steps().stream().anyMatch(s -> s instanceof Step.ToStation t && t.x() == -8 && t.y() == 63 && t.z() == 13), walked.steps().toString());
        assertTrue(walked.dropped().stream().anyMatch(d -> d.startsWith("place a new furnace near me: ")), walked.dropped().toString());
        assertEquals(FakePaths.walkS(67.1) - 0.45 - NeedChain.stationMaterialS(), walked.seconds() - placed.seconds(), TOL, placed.why() + " / " + walked.why());
    }

    /** The carried furnace: placed 0.15 s, nothing crafted. */
    @Test
    void aCarriedFurnaceIsPlacedHere() {
        Map<String, Integer> bag = Map.of("minecraft:raw_iron", 2, "minecraft:furnace", 1, "minecraft:coal", 2, "minecraft:stone_pickaxe", 1);
        Chain c = NeedChain.resolve("iron_ingot", 2, world(bag));
        assertTrue(c.ok(), c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.PlaceNear), c.steps().toString());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Craft k && k.item().contains("furnace")), c.steps().toString());
        Chain made = NeedChain.resolve("iron_ingot", 2, world(Map.of("minecraft:raw_iron", 2, "minecraft:cobblestone", 8, "minecraft:coal", 2, "minecraft:stone_pickaxe", 1)));
        assertEquals(0.3 + NeedChain.stationMaterialS(), made.seconds() - c.seconds(), TOL, "the carried one saves the craft and its materials (D14): " + c.why() + " / " + made.why());
    }
}
