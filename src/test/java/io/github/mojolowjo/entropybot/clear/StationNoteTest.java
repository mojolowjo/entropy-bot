package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.aa.Nearby;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.JobHands;
import io.github.mojolowjo.entropybot.restore.Ledger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** station-note (0.39.z15): the placements hook feeds the station fact; the chain's place-near notes a station it placed. */
@org.junit.jupiter.api.extension.ExtendWith(io.github.mojolowjo.entropybot.aa.brain.VanillaData.class)
class StationNoteTest {
    static final String OW = "minecraft:overworld";
    static final Set<String> TABLE = Set.of("minecraft:crafting_table");

    @AfterEach
    void unhook() {
        OwnStations.placed = null;
        OwnStations.forget = null;
        OwnStations.warn = io.github.mojolowjo.entropybot.Quiet::line;
        OwnStations.newWorld();
    }

    /** A scan that finds nothing; own() is the interface's default (the OwnStations.placed hook). */
    static Nearby.Scan emptyScan() {
        return new Nearby.Scan() {
        @Override public io.github.mojolowjo.entropybot.guard.Policy stationPolicy() { return io.github.mojolowjo.entropybot.aa.TestStations.OPEN; }  // stated: the owner's area over the world
        @Override public String stationDim() { return io.github.mojolowjo.entropybot.aa.TestStations.OW; }
            @Override public List<int[]> logs(int radius) { return List.of(); }
            @Override public List<int[]> find(Set<String> blocks, int radius) { return List.of(); }
            @Override public boolean isLog(String block) { return false; }
            @Override public boolean fairOk(int x, int y, int z) { return true; }
            @Override public boolean leavesNear(int x, int y, int z, int reach) { return false; }
            @Override public boolean allowed(int x, int y, int z) { return true; }
            @Override public String blockAt(int x, int y, int z) { return null; }
            @Override public Boolean open(int x, int y, int z) { return true; }   // stated: every face open (legit sources: NearbyExposureTest)
        };
    }

    @Test
    void aPlacementFromTheHookIsAKnownStationAtAnyDistance() {
        OwnStations.placed = () -> List.of(new OwnStations.Placed(900, 64, 0, "minecraft:crafting_table"), new OwnStations.Placed(3, 64, 0, "minecraft:furnace"));
        List<Spot> l = Nearby.stations(emptyScan(), new int[]{0, 64, 0}, TABLE);
        assertEquals(1, l.size(), l.toString());
        assertEquals(900, l.get(0).x());
        assertEquals(900.0, l.get(0).dist(), 1e-9, "900 m away and not loaded: still known");
        assertEquals("stations: 1 of mine, 0 scanned within 32, nearest crafting_table 900 m at 900 64 0",
                Nearby.stationsLine(l, emptyScan().own(), TABLE, 32));
        assertEquals("stations: none known", Nearby.stationsLine(List.of(), List.of(), TABLE, 32));
    }

    @Test
    void anUnsetHookLogsOnceAndAnswersEmpty() {
        List<String> warned = new ArrayList<>();
        OwnStations.warn = warned::add;
        OwnStations.newWorld();
        assertEquals(List.of(), OwnStations.placed());
        assertEquals(List.of(), OwnStations.placed());
        assertEquals(List.of("station memory: the placements hook is not set"), warned, "once per world, never silent");
        assertTrue(OwnStations.forget(1, 2, 3, "gone").contains("nothing to forget"));
        assertEquals("station memory: the forget hook is not set", warned.get(1));
        OwnStations.newWorld();
        OwnStations.placed();
        assertEquals(3, warned.size(), "a new world warns again");
    }

    /** A fork whose placeNear answers DONE with the cell it chose. */
    static final class Fork implements JobHands.Hand {
        final int[] cell;
        Fork(int[] cell) { this.cell = cell; }
        @Override public void start(List<int[]> dig, List<JobHands.Click> clicks, int timeoutTicks) {}
        @Override public void startBuild(List<int[]> cells, List<String> items, int timeoutTicks, boolean replace) {}
        @Override public void startPlaceNear(String item, int radius, int timeoutTicks) {}
        @Override public boolean reflexActive() { return false; }
        @Override public Car.Status status() { return Car.Status.done("place near me at " + cell[0] + " " + cell[1] + " " + cell[2], cell); }
        @Override public void cancel(String why) {}
        @Override public String verdict(String action, int[] cell) { return ""; }
        @Override public void look(double x, double y, double z) {}
    }

    record Note(int[] cell, String block, String why) {}

    @Test
    void aTableThePlaceNearStepPlacedIsNotedAsMyStationACobblestoneIsNot() {
        List<Note> notes = new ArrayList<>();
        for (String item : List.of("crafting_table", "cobblestone")) {
            JobHands hands = new JobHands(new Fork(new int[]{5, 64, 7}));
            assertTrue(hands.placeNear(item, 3, 200, "steps").running());
            JobHands.Result r = hands.placeNear(item, 3, 200, "steps");
            assertTrue(r.done(), r.toString());
            assertArrayEquals(new int[]{5, 64, 7}, r.cell(), "the fork's chosen cell comes back with DONE");
            String line = OwnStations.afterPlaceNear(item, r.cell(), (c, b, w) -> notes.add(new Note(c, b, w)));
            if (item.equals("crafting_table")) assertEquals("stations: noted my crafting_table at 5 64 7", line);
            else assertNull(line);
        }
        assertEquals(1, notes.size(), "only the table");
        assertArrayEquals(new int[]{5, 64, 7}, notes.get(0).cell());
        assertEquals("minecraft:crafting_table", notes.get(0).block());
        assertEquals("built: station", notes.get(0).why());
    }

    @Test
    void aStationWithNoCellFromTheForkIsAWarningNotAQuietSkip() {
        List<String> warned = new ArrayList<>();
        OwnStations.warn = warned::add;
        String line = OwnStations.afterPlaceNear("furnace", null, (c, b, w) -> fail("nothing to note"));
        assertTrue(line.contains("gave no cell"), line);
        assertEquals(List.of(line), warned);
    }

    @Test
    void theLedgersPlacementsViewIsOnlyMyStationsOfTheDimension() {
        Ledger l = new Ledger();
        l.add(1, 64, 0, OW, "minecraft:crafting_table", "minecraft:crafting_table", List.of(), "built: station", 1, "aa", "built: station", 0, 0);
        l.add(2, 64, 0, OW, "minecraft:cobblestone", "minecraft:cobblestone", List.of(), "built: placed", 1, "aa", "built: placed", 0, 0);
        l.add(3, 64, 0, OW, "minecraft:stone", "minecraft:stone", List.of("minecraft:cobblestone"), null, 1, "aa", "path", 0, 0);
        l.add(4, 64, 0, "minecraft:the_nether", "minecraft:furnace", "minecraft:furnace", List.of(), "built: station", 1, "aa", "built: station", 0, 0);
        l.add(5, 64, 0, OW, "minecraft:dirt", "minecraft:dirt", List.of(), "built: shelter", 1, "aa", "built: shelter", 0, 0);
        List<Ledger.Entry> s = l.builtStations(OW);
        assertEquals(1, s.size(), s.toString());
        assertEquals(1, s.get(0).x);
        assertEquals("minecraft:crafting_table", s.get(0).block);
        assertEquals(1, l.builtStations("minecraft:the_nether").size());
    }
}
