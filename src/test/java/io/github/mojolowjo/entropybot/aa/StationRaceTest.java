package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepWait;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * station-race (0.39.z17, owner-watched 2026-10-22 on a LAN world: every craft said "stations: none known" one second
 * after "noted my crafting_table", then put a second table down and picked it up again). Rule 15: a fact is loaded, empty
 * or NOT LOADED; not loaded is never cached and never read as none; the asker waits with that reason.
 */
class StationRaceTest {
    static final String TABLE = "minecraft:crafting_table", FURNACE = "minecraft:furnace";

    @AfterEach
    void clear() { TestData.clear(); }

    /** The workstation lookup of a station item (the live one adds the c:player_workstations members). */
    static final class Blocks implements Function<String, Set<String>> {
        int asked;
        @Override public Set<String> apply(String item) { asked++; return Set.of(item); }
    }

    /** A scan that finds nothing; the ledger (own()) is the fake one: my table 900 m away. */
    static Nearby.Scan ledgerScan(List<Nearby.Mine> mine) {
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
            @Override public List<Nearby.Mine> own() { return mine; }
        };
    }

    static final int[] ME = {0, 64, 0};

    @Test
    void notLoadedIsNeverCachedAndTheNextLookupFindsTheLedgersTable() {
        Blocks blocks = new Blocks();
        StationBlocks sb = new StationBlocks(blocks);
        Nearby.Scan scan = ledgerScan(List.of(new Nearby.Mine(900, 64, 0, TABLE)));

        // the join: no item data yet (the 20:19:06 lookup)
        ItemWorth.use(null);
        StationBlocks.Answer a = sb.of(ItemWorth.CRAFT);
        assertInstanceOf(StationBlocks.Answer.NotLoaded.class, a);
        StationFact f = Nearby.stationFact(scan, ME, a);
        assertInstanceOf(StationFact.NotLoaded.class, f, "not loaded is not none: the ledger's table is not denied");
        assertEquals("stations: item data not loaded yet", Nearby.stationsLine(f, scan.own(), Set.of(), 32));

        // the data is in, but this kind's station is not named yet: still not loaded, still not cached
        TestData d = new TestData();
        d.stations.clear();
        d.install();
        assertInstanceOf(StationBlocks.Answer.NotLoaded.class, sb.of(ItemWorth.CRAFT));
        assertTrue(Nearby.stationsLine(Nearby.stationFact(scan, ME, sb.of(ItemWorth.CRAFT)), scan.own(), Set.of(), 32)
                .startsWith("stations: item data not loaded yet"));
        assertEquals(0, blocks.asked, "nothing was computed, so nothing was cached");

        // the station is named: the very next lookup finds my table from the ledger, at any distance
        d.stations.put(ItemWorth.CRAFT, TABLE);
        StationBlocks.Answer loaded = sb.of(ItemWorth.CRAFT);
        assertEquals(new StationBlocks.Answer.Of(Set.of(TABLE)), loaded);
        StationFact known = Nearby.stationFact(scan, ME, loaded);
        StationFact.Known k = assertInstanceOf(StationFact.Known.class, known);
        assertEquals(900, k.nearest().x());
        assertEquals("stations: 1 of mine, 0 scanned within 32, nearest crafting_table 900 m at 900 64 0",
                Nearby.stationsLine(known, scan.own(), Set.of(TABLE), 32));
        sb.of(ItemWorth.CRAFT);
        assertEquals(1, blocks.asked, "a loaded set is cached");

        // a new world (new item data): recomputed
        new TestData().install();
        sb.of(ItemWorth.CRAFT);
        assertEquals(2, blocks.asked, "the cache goes with the item data it came from");
    }

    @Test
    void loadedAndEmptyIsNoneNotNotLoaded() {
        new TestData().install();
        StationFact f = Nearby.stationFact(ledgerScan(List.of()), ME, new StationBlocks(new Blocks()).of(ItemWorth.CRAFT));
        assertInstanceOf(StationFact.None.class, f);
        assertEquals("stations: none known", Nearby.stationsLine(f, List.of(), Set.of(TABLE), 32));
        assertThrows(IllegalArgumentException.class, () -> new StationFact.Known(List.of()), "a known fact is never an empty list");
    }

    @Test
    void theItemDataNamesTheStationOfCraftAndSmelt() {
        new TestData().install();                                  // a fake source: the recipe kinds' toast symbols
        StationBlocks sb = new StationBlocks(new Blocks());
        assertEquals(new StationBlocks.Answer.Of(Set.of(TABLE)), sb.of(ItemWorth.CRAFT));
        assertEquals(new StationBlocks.Answer.Of(Set.of(FURNACE)), sb.of(ItemWorth.SMELT));
        assertEquals(TABLE, ItemWorth.stationOf(ItemWorth.CRAFT));
        assertEquals(FURNACE, ItemWorth.stationOf(ItemWorth.SMELT));
    }

    @Test
    void aCraftStepWaitsWhileNotLoadedAndNeverBeginsItsVerb() {
        AtomicInteger begun = new AtomicInteger();
        StationFact[] fact = {new StationFact.NotLoaded("item data not loaded yet")};
        Function<String, StationFact> facts = k -> fact[0];
        StepWait gate = new StepWait();
        Step craft = new Step.Craft("minecraft:wooden_pickaxe", 1);

        assertNull(gate.start(craft, facts, () -> { begun.incrementAndGet(); return null; }), "started: waiting is no failure");
        assertTrue(gate.waiting());
        for (int i = 0; i < 40; i++) {
            Car.Status st = gate.tick(facts, () -> { begun.incrementAndGet(); return null; }, () -> Car.Status.running("crafting"));
            assertEquals(Car.State.RUNNING, st.state());
            assertTrue(st.why().contains("not loaded"), st.why());
        }
        assertEquals(0, begun.get(), "no plan, no table placed, no 'none known' while not loaded");

        fact[0] = new StationFact.Known(List.of(new Spot(900, 64, 0, 900, TABLE)));
        Car.Status st = gate.tick(facts, () -> { begun.incrementAndGet(); return null; }, () -> Car.Status.running("crafting"));
        assertEquals(1, begun.get(), "loaded: the craft verb starts once");
        assertEquals("crafting", st.why());
        assertFalse(gate.waiting());
    }

    @Test
    void aLoadedFactStartsTheVerbAtOnce() {
        AtomicInteger begun = new AtomicInteger();
        StepWait gate = new StepWait();
        assertNull(gate.start(new Step.Craft("minecraft:wooden_pickaxe", 1), k -> new StationFact.None("none known"), () -> { begun.incrementAndGet(); return null; }));
        assertEquals(1, begun.get());
        assertFalse(gate.waiting());
    }

    // ---- grep tests ----

    static final Path MAIN = Path.of("src/main/java");

    static List<String> grep(Pattern p) throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path f : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) if (p.matcher(lines.get(i)).find()) hits.add(f + ":" + (i + 1) + ": " + lines.get(i).trim());
            }
        }
        return hits;
    }

    @Test
    void theOldOwnTableFallbackIsGone() throws IOException {
        assertEquals(List.of(), grep(Pattern.compile("CRAFT_AND_PLACE|taking my own table back|none near me or the base|putting my own down|OwnTable\\b")));
        assertFalse(Files.exists(MAIN.resolve("io/github/mojolowjo/entropybot/craft/OwnTable.java")));
    }

    /**
     * rule 15: no caller reads a not-loaded station fact as an empty list. The live lookups return the sealed three-state
     * types (a List or Set return would let NOT_LOADED pass as empty), and nothing iterates or size-checks them directly.
     */
    @Test
    void noCallerTreatsNotLoadedAsAnEmptyList() throws IOException {
        String live = Files.readString(MAIN.resolve("io/github/mojolowjo/entropybot/commands/NearbyLive.java"));
        assertTrue(Pattern.compile("\\bStationFact stations\\(String kind\\)").matcher(live).find(), "stations(kind) is a StationFact");
        assertTrue(Pattern.compile("\\bStationFact station\\(String kind,").matcher(live).find(), "station(kind, skip) is a StationFact");
        assertTrue(Pattern.compile("\\bStationBlocks\\.Answer stationBlocks\\(String kind\\)").matcher(live).find(), "stationBlocks(kind) is three-state");
        assertFalse(Pattern.compile("(List<Spot>|Set<String>|int\\[\\]) (stations|station|stationBlocks)\\(String kind").matcher(live).find());
        String crafting = Files.readString(MAIN.resolve("io/github/mojolowjo/entropybot/commands/Crafting.java"));
        assertTrue(Pattern.compile("\\bStationFact knownStation\\(String kind\\)").matcher(crafting).find());
        assertTrue(Pattern.compile("\\bStationFact findTable\\(").matcher(crafting).find());
        assertTrue(Pattern.compile("\\bStationFact findFurnace\\(").matcher(crafting).find());
        assertEquals(List.of(), grep(Pattern.compile(
                "(stations|station|stationBlocks|knownStation|findTable|findFurnace|nearest)\\([^()]*\\)\\s*(\\.(isEmpty|size|get|stream|iterator|contains|spots)\\(|==\\s*null|!=\\s*null)"
                        + "|for \\([^:]+:\\s*[\\w.]*(stations|knownStation|findTable|findFurnace)\\(")),
                "a station fact is switched on (Known / None / NotLoaded), never read as a list or a null");
    }
}
