package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.DepositRule;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** main-stations (0.39.z35, docs/SCENARIOS.md S40): the owner's main table, furnace and chest; a price, not a rule. */
class MainStationsTest {
    static final Function<String, Set<String>> BLOCKS = k -> ItemWorth.CRAFT.equals(k) ? Set.of("minecraft:crafting_table")
            : ItemWorth.SMELT.equals(k) ? Set.of("minecraft:furnace")
            : MainStations.CONTAINER.equals(k) ? Set.of("minecraft:chest", "minecraft:barrel") : null;

    @AfterEach
    void clear() {
        MainStations.current = null;
        TestData.clear();
        FactChange.clear();
    }

    @Test
    void theKindsAreTheChainsAndAWordNamesOneByItsBlocks() {
        assertEquals(List.of(ItemWorth.CRAFT, ItemWorth.SMELT, MainStations.CONTAINER), MainStations.KINDS);
        assertEquals(ItemWorth.CRAFT, MainStations.kindOf("table", BLOCKS));
        assertEquals(ItemWorth.CRAFT, MainStations.kindOf("crafting_table", BLOCKS));
        assertEquals(ItemWorth.SMELT, MainStations.kindOf("furnace", BLOCKS));
        assertEquals(MainStations.CONTAINER, MainStations.kindOf("chest", BLOCKS));
        assertNull(MainStations.kindOf("anvil", BLOCKS));
        assertNull(MainStations.kindOf("table", k -> null), "not loaded: no kind");
        assertEquals(ItemWorth.SMELT, MainStations.kindOfBlock("minecraft:furnace", BLOCKS));
        assertNull(MainStations.kindOfBlock("minecraft:stone", BLOCKS));
    }

    @Test
    void oneMainPerKindMarkedListedClearedAndSavedPerWorld() {
        MainStations m = new MainStations();
        m.mark(new MainStations.Main(ItemWorth.SMELT, 1, 64, 2, "minecraft:furnace"));
        String r = m.mark(new MainStations.Main(ItemWorth.SMELT, 5, 64, 5, "minecraft:furnace"));
        assertTrue(r.contains("(was minecraft:furnace at 1 64 2)"), r);
        m.mark(new MainStations.Main(MainStations.CONTAINER, 9, 63, 9, "minecraft:chest"));
        assertEquals(2, m.all().size());
        StringBuilder note = new StringBuilder();
        MainStations back = MainStations.fromJson(com.google.gson.JsonParser.parseString(m.toJson().toString()).getAsJsonObject(), note);
        assertEquals(m.all(), back.all());
        assertEquals("", note.toString());
        assertTrue(back.line().contains("chest 9 63 9"), back.line());
        assertTrue(back.clear(ItemWorth.SMELT).contains(" cleared (was minecraft:furnace at 5 64 5)"));
        assertTrue(back.clear(ItemWorth.SMELT).startsWith("no main"));
        var bad = com.google.gson.JsonParser.parseString("{\"mains\":[{\"kind\":\"nope\",\"x\":1,\"y\":2,\"z\":3,\"block\":\"b\"}]}").getAsJsonObject();
        StringBuilder n2 = new StringBuilder();
        assertTrue(MainStations.fromJson(bad, n2).all().isEmpty());
        assertTrue(n2.toString().contains("not a station kind"), "a bad entry is said, never dropped quietly");
    }

    /** S40a: a main furnace 20 m against another 5 m, bonus 30 s: walk 6.0 - 30 against 1.5: the main wins. */
    @Test
    void s40_aMainFurnaceFartherWinsByItsBonus() {
        TestData.planner().install();
        MainStations m = new MainStations();
        m.mark(new MainStations.Main(ItemWorth.SMELT, 20, 64, 0, "minecraft:furnace"));
        MainStations.current = m;
        var s = new NearbyTest.Fake(List.of(), List.of(new int[]{5, 64, 0}, new int[]{20, 64, 0}), p -> true, p -> true,
                Map.of("5 64 0", "minecraft:furnace", "20 64 0", "minecraft:furnace"));
        Nearby.Lookup l = Nearby.lookup(s, NearbyTest.ME, Set.of("minecraft:furnace"));
        assertEquals(20, l.spots().get(0).x(), l.toString());
        assertEquals(30, l.spots().get(0).bonusS(), 1e-9);
        assertTrue(l.priced().stream().anyMatch(p -> p.startsWith("20 64 0: main -30 s")), l.priced().toString());
        assertTrue(Nearby.stationsLine(l.spots(), List.of(), Set.of("minecraft:furnace"), 32).endsWith("(main)"));
    }

    /** S40a': without the main the nearer one stands (the bonus is the whole difference). */
    @Test
    void s40_withoutAMainTheNearerFurnaceStands() {
        TestData.planner().install();
        MainStations.current = new MainStations();
        var s = new NearbyTest.Fake(List.of(), List.of(new int[]{5, 64, 0}, new int[]{20, 64, 0}), p -> true, p -> true,
                Map.of("5 64 0", "minecraft:furnace", "20 64 0", "minecraft:furnace"));
        assertEquals(5, Nearby.lookup(s, NearbyTest.ME, Set.of("minecraft:furnace")).spots().get(0).x());
    }

    /** S40b: a main table 227 m (walk 68.4 s - 30 = 38.4 s) against placing a new one from the bag (0.75 s): placing wins, both shown. */
    @Test
    void s40_aMainTableFarAwayStillLosesToPlacingOne() {
        ScenariosTest.world("wooden");
        MainStations m = new MainStations();
        m.mark(new MainStations.Main(ItemWorth.CRAFT, 227, 70, 0, "minecraft:crafting_table"));
        MainStations.current = m;
        Map<String, Integer> bag = Map.of("minecraft:oak_planks", 7, "minecraft:stick", 2);
        Chain c = NeedChain.resolve("wooden_pickaxe", 1, FarStationWearUnreachableTest.world(bag,
                new Spot(227, 70, 0, 227, "minecraft:crafting_table").withBonus(MainStations.bonusS()), Map.of()));
        assertTrue(c.ok(), c.why());
        assertEquals(14.5, c.seconds(), 0.1, c.why());        // 0.75 + the table taken along after the craft (owner 2026-10-29: 3.75 s) + D14 station.materialS 10
        assertTrue(c.steps().stream().anyMatch(st -> st instanceof Step.PlaceNear), c.steps().toString());
        assertTrue(c.dropped().stream().anyMatch(d -> d.startsWith("walk to the known table 227 m (main -30 s): 38.")), c.dropped().toString());
    }

    /** The deposit picks the main chest first: main 20 s, another 5 s, bonus 30 s. */
    @Test
    void theDepositPicksTheMainChest() {
        int[] near = {5, 64, 0}, main = {20, 64, 0};
        Map<String, Double> cost = Map.of("5 64 0", 5.0, "20 64 0", 20.0);
        DepositRule.Choice ch = DepositRule.choose(List.of(near, main), k -> cost.get(DepositRule.at(k)),
                k -> DepositRule.at(k).equals("20 64 0") ? 30.0 : 0.0, false, false);
        assertEquals(DepositRule.How.KNOWN, ch.how());
        assertArrayEquals(main, ch.at(), ch.why());
        assertTrue(ch.why().contains("-30 s bonus"), ch.why());
        assertArrayEquals(near, DepositRule.choose(List.of(near, main), k -> cost.get(DepositRule.at(k)), false, false).at(), "no bonus: the nearer");
    }
}
