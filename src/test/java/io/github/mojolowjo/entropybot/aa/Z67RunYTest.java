package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.vocab.ExploreWords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z67 (run Y on z66, 09:26:14 / 09:31:16): "place crafting_table near me: no cell within 3 blocks; none to clear either"
 * swimming at sea level (-701.8 62.7 -86.5), re-planned to the same place-near at the same spot, the order stopped. Item 1:
 * the failure is a fact about WHERE it failed; the re-plan places near a cell with room from facts by its priced walk.
 * Item 2: the explore's ring tie went to the west column first (scan order); now the nearest by straight distance.
 */
class Z67RunYTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    static final int[] SEA = {-701, 62, -86};
    static final Map<String, Integer> BAG = Map.of("minecraft:crafting_table", 1, "minecraft:oak_planks", 3, "minecraft:stick", 2);

    static NeedChain.World sea(List<Spot> rooms, boolean failedHere) {
        var w = ScenariosTest.w(BAG, false, false, Map.of()).withRooms(rooms);
        return failedHere ? w.withPlaceFailures(Map.of(NeedChain.placeKey(SEA[0], SEA[1], SEA[2]), "place crafting_table near me failed at -701 62 -86: no cell within 3 blocks"), SEA) : w;
    }

    /** Nothing held: the table is placed near me as before (no walk). */
    @Test
    void noFailureHeldPlacesNearMe() {
        ScenariosTest.world("wooden");
        Chain c = NeedChain.resolve("wooden_pickaxe", 1, sea(List.of(new Spot(-680, 64, -90, 21.4, null)), false));
        assertTrue(c.ok(), c.why());
        assertEquals(new Step.PlaceNear("crafting_table"), c.steps().get(0), c.steps().toString());
        assertFalse(c.steps().stream().anyMatch(s -> s instanceof Step.Walk), c.steps().toString());
    }

    /**
     * Held at this spot: the walk to the cheaper cell with room (shore 21.4 m: 21.4 x 1.3 / 4.317 = 6.44 s; the older ground
     * cell 120 m: 36.1 s, dropped with its seconds), near place.roomNear 1, then the place-near there (Baritone picks the cell).
     */
    @Test
    void heldHereWalksToTheCheapestCellWithRoom() {
        ScenariosTest.world("wooden");
        Spot shore = new Spot(-680, 64, -90, 21.4, null), back = new Spot(-581, 64, -80, 120, null);
        Chain c = NeedChain.resolve("wooden_pickaxe", 1, sea(List.of(back, shore), true));
        assertTrue(c.ok(), c.why());
        assertEquals(new Step.Walk(-680, 64, -90, 1), c.steps().get(0), c.steps().toString());
        assertEquals(new Step.PlaceNear("crafting_table"), c.steps().get(1), c.steps().toString());
        assertEquals(6.44, c.seconds() - NeedChain.resolve("wooden_pickaxe", 1, sea(List.of(), false)).seconds(), 0.05, c.why());
        assertTrue(c.why().contains("near the cell with room at -680 64 -90"), c.why());
        assertTrue(c.dropped().stream().anyMatch(d -> d.contains("near the cell with room at -581 64 -80")), c.dropped().toString());
    }

    /** A cell with room within steps.placeNearR of the failure is one placeNear already looked at: no cell with room. */
    @Test
    void aRoomTheFailedPlaceLookedAtIsNone() {
        ScenariosTest.world("wooden");
        Chain c = NeedChain.resolve("wooden_pickaxe", 1, sea(List.of(new Spot(-702, 62, -85, 1.4, null)), true));
        assertFalse(c.steps().stream().anyMatch(s -> s instanceof Step.PlaceNear), "never the same place-near here again: " + c.steps());
        assertTrue(String.valueOf(c.missing()).contains("no cell with room to place it near is known") || c.why().contains("no cell with room"), c.why() + " | " + c.missing());
    }

    /** The bot farther than place.failedNearM (8) from the failure: placing near me there is not out. */
    @Test
    void farFromTheFailurePlacesNearMeAgain() {
        ScenariosTest.world("wooden");
        var w = ScenariosTest.w(BAG, false, false, Map.of()).withPlaceFailures(Map.of(NeedChain.placeKey(SEA[0], SEA[1], SEA[2]), "x"), new int[]{-690, 64, -86});
        assertNull(w.rooms().hereFailed());
        var near = ScenariosTest.w(BAG, false, false, Map.of()).withPlaceFailures(Map.of(NeedChain.placeKey(SEA[0], SEA[1], SEA[2]), "x"), new int[]{-697, 62, -86});
        assertEquals("x", near.rooms().hereFailed());
    }

    /** The fact names where it failed: a source key the order holds (Goals.replan reads it with ownSourceKey). */
    @Test
    void thePlaceFactNamesItsCell() {
        FactChange.placeFailed("minecraft:crafting_table", "no cell within 3 blocks", SEA);
        FactChange.Fact f = FactChange.take();
        assertEquals("source place at -701 62 -86", f.key());
        assertEquals("place at -701 62 -86", FactChange.ownSourceKey(f));
        assertTrue(f.text().startsWith("place crafting_table near me failed at -701 62 -86: "), f.text());
        FactChange.placeFailed("minecraft:crafting_table", "w", null);
        assertEquals("place minecraft:crafting_table", FactChange.take().key(), "no position known: the plain fact");
    }

    /**
     * The place-near failing again at ANOTHER cell is a new fact: re-planned again, not ended; the same cell from the same
     * feet with the same bag ends it. 0.39.z70 (D2 3.1): the fact key is part of the try ({@link Steps.Attempt}), so z67's
     * place-near rule is the general rule now (sameAgain by text is gone).
     */
    @Test
    void anotherCellIsANewFactTheSameCellEnds() {
        Step place = new Step.PlaceNear("crafting_table");
        List<int[]> at = new ArrayList<>(List.of(SEA, new int[]{-680, 64, -90}, new int[]{-680, 64, -90}));
        List<FactChange.Fact> facts = new ArrayList<>();
        int[][] feet = {SEA.clone()};
        Steps run = new Steps(new Steps.Hands() {
            @Override public String start(Step s) {
                if (s instanceof Step.PlaceNear p) {
                    FactChange.placeFailed(p.item(), "no cell within 3 blocks", at.remove(0));
                    return "error: no cell within 3 blocks";
                }
                if (s instanceof Step.Walk w) feet[0] = new int[]{w.x(), w.y(), w.z()};
                return null;
            }
            @Override public Car.Status status(Step s) { return Car.Status.done("ok"); }
            @Override public void cancel(String why) {}
            @Override public int[] feet() { return feet[0]; }
            @Override public io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag bag() {
                return new io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag() {
                    @Override public int bestTier(String kind) { return 0; }
                    @Override public String bestTool(String kind) { return null; }
                    @Override public int usesLeft(String id) { return io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED; }
                    @Override public int count(String id) { return BAG.getOrDefault(id, 0); }
                };
            }
        });
        // the planner: first the walk to the shore and the place there; then the same place-near from the shore again
        run.start("goal.torch", List.of(place), 0, (failed, fact, why) -> {
            facts.add(fact);
            return new Steps.Routine(facts.size() == 1 ? List.of(new Step.Walk(-680, 64, -90, 1), place) : List.of(place), null);
        });
        for (int t = 1; t <= 20 && run.phase() != Steps.Phase.FAILED; t++) run.tick(t);
        assertEquals(2, facts.size(), "the second failure (at the shore cell) re-planned again: " + run.why());
        assertEquals("source place at -680 64 -90", facts.get(1).key());
        assertEquals(Steps.Phase.FAILED, run.phase(), "the same cell again ends the run: " + run.why());
        assertTrue(run.why().contains("the same way failed twice from the same place with the same bag: place crafting_table near me - place crafting_table near me failed at -680 64 -90"), run.why());
    }

    /** RoomCells: solid ground only, one per place.failedNearM stretch, at most place.roomsKept, newest first. */
    @Test
    void roomCellsKeepSolidGroundSpaced() {
        RoomCells r = new RoomCells();
        r.stood(new int[]{0, 64, 0}, true);
        r.stood(new int[]{3, 64, 0}, true);          // within 8 of the last kept: not kept
        r.stood(new int[]{50, 62, 0}, false);         // swimming: not ground
        assertEquals(1, r.size());
        for (int i = 1; i <= 10; i++) r.stood(new int[]{i * 10, 64, 0}, true);
        assertEquals(8, r.size(), "place.roomsKept");
        List<Spot> s = r.spots(new int[]{100, 64, 0});
        assertEquals(100, s.get(0).x(), "newest first");
        assertEquals(0, s.get(0).dist(), 1e-9);
        assertTrue(r.spots(null).isEmpty(), "feet not known: none");
    }

    /** Item 2: with no direction the ring's nearest chunk by straight distance wins (was: the west column first). */
    @Test
    void exploreRingTieIsTheNearestNotTheWestColumn() {
        // every chunk of ring 1 unseen: the west column came first (-1,-1, a corner); now an edge centre, the nearer to the start
        int[] c = ExploreWords.next(0, 0, 0, 0, null, (x, z) -> false, (x, z) -> false);
        assertEquals(1, Math.abs(c[0]) + Math.abs(c[1]), "an edge centre (1 chunk), not a corner: " + c[0] + "," + c[1]);
        // the bot east of its start: of the ring's edge centres the one back toward the start (west of the bot) is nearer home
        int[] h = ExploreWords.next(5, 0, 0, 0, null, (x, z) -> false, (x, z) -> false);
        assertArrayEquals(new int[]{4, 0}, h);
        // only a corner and an edge unseen on the ring: the edge
        int[] e = ExploreWords.next(0, 0, 0, 0, null, (x, z) -> !((x == -1 && z == -1) || (x == 1 && z == 0)), (x, z) -> false);
        assertArrayEquals(new int[]{1, 0}, e);
    }
}
