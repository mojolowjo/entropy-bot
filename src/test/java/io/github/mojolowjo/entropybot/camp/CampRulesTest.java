package io.github.mojolowjo.entropybot.camp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** C4 + C5 (0.20.1): the bootstrap planner, day/night triggers, the light grid, stock math, junk rules, tool care. */
class CampRulesTest {
    static final int[] FEET = {10, 64, 20}, DIR = {1, 0};
    static final int[] T = {8, 64, 20}, F = {8, 64, 21}, C = {8, 64, 19};

    static BootstrapPlan.Facts facts(Map<String, Integer> inv, boolean table, boolean furnace, boolean chest, boolean camp) {
        return new BootstrapPlan.Facts(inv, table, furnace, chest, camp, FEET, T, F, C, DIR);
    }

    @Test
    void bootstrapFromNothing() {
        BootstrapPlan.Plan p = BootstrapPlan.plan(facts(Map.of(), false, false, false, false));
        assertNull(p.err());
        List<String> s = p.steps();
        assertEquals("setbase 10 64 20", s.get(0));
        assertEquals("mark camp 10 64 20", s.get(1));
        assertTrue(s.get(2).startsWith("chop "), s.toString());
        int chop = Integer.parseInt(s.get(2).split(" ")[1]);
        assertTrue(chop >= 12, "enough logs for table, chest, sticks, wooden pick and charcoal: " + chop);
        assertTrue(s.indexOf("craft crafting_table 1") < s.indexOf("place crafting_table 8 64 20"));
        assertTrue(s.indexOf("place crafting_table 8 64 20") < s.indexOf("craft wooden_pickaxe 1"));
        int firstDig = s.indexOf(BootstrapPlan.quarrySteps(FEET, DIR).get(0));
        assertTrue(firstDig > s.indexOf("craft wooden_pickaxe 1"));
        for (String k : List.of("pickaxe", "axe", "shovel", "sword")) assertTrue(s.indexOf("craft stone_" + k + " 1") > firstDig, k);
        assertTrue(s.contains("place furnace 8 64 21"));
        assertTrue(s.contains("place chest 8 64 19"));
        assertTrue(s.indexOf("gather charcoal 4") > s.indexOf("place furnace 8 64 21"));
        assertTrue(s.indexOf("craft torch 16") > s.indexOf("gather charcoal 4"));
        assertEquals("scan base", s.get(s.size() - 1));
        assertEquals("goto 10 64 20", s.get(s.indexOf("place crafting_table 8 64 20") - 1), "walks back to the camp before placing");
    }

    @Test
    void bootstrapIsIdempotent() {
        Map<String, Integer> inv = new LinkedHashMap<>();
        inv.put("minecraft:stone_pickaxe", 1);
        inv.put("minecraft:iron_axe", 1);
        inv.put("minecraft:stone_shovel", 1);
        inv.put("minecraft:diamond_sword", 1);
        inv.put("minecraft:torch", 20);
        BootstrapPlan.Plan p = BootstrapPlan.plan(facts(inv, true, true, true, true));
        assertTrue(p.steps().isEmpty(), p.steps().toString());
        assertTrue(p.summary().contains("already set up"));
        // only the furnace is missing, with cobblestone in the bag: no quarry, no chop beyond what's needed
        inv.put("minecraft:cobblestone", 10);
        p = BootstrapPlan.plan(facts(inv, true, false, true, true));
        assertEquals(List.of("craft furnace 1", "goto 10 64 20", "place furnace 8 64 21"), p.steps());
    }

    @Test
    void bootstrapWoodenPickaxeNotStone() {
        Map<String, Integer> inv = Map.of("minecraft:wooden_pickaxe", 1, "minecraft:oak_log", 20);
        BootstrapPlan.Plan p = BootstrapPlan.plan(facts(inv, true, true, true, true));
        assertFalse(p.steps().contains("craft wooden_pickaxe 1"), "it has a pickaxe");
        assertTrue(p.steps().contains("craft stone_pickaxe 1"));
        assertFalse(p.steps().stream().anyMatch(x -> x.startsWith("chop")), "20 logs are enough: " + p.steps());
    }

    @Test
    void bootstrapNoRoom() {
        BootstrapPlan.Plan p = BootstrapPlan.plan(new BootstrapPlan.Facts(Map.of(), false, false, false, false, FEET, null, null, null, DIR));
        assertNotNull(p.err());
        assertTrue(p.err().contains("next:"));
    }

    @Test
    void quarryIsAStaircase() {
        List<String> q = BootstrapPlan.quarrySteps(FEET, DIR);
        assertEquals(BootstrapPlan.QUARRY_LEN, q.size());
        assertEquals("dig 12 63 19 12 63 21", q.get(0));        // 2 east, 3 wide across z, 1 deep
        assertEquals("dig 13 62 19 13 63 21", q.get(1));
        assertEquals("dig 19 56 19 19 63 21", q.get(7));
        List<String> n = BootstrapPlan.quarrySteps(FEET, new int[]{0, -1});
        assertEquals("dig 9 63 18 11 63 18", n.get(0));
    }

    @Test
    void toolTiers() {
        assertEquals(0, BootstrapPlan.tier("minecraft:stone_pickaxe", "axe"));
        assertEquals(2, BootstrapPlan.tier("minecraft:stone_axe", "axe"));
        assertEquals(3, BootstrapPlan.tier("minecraft:iron_pickaxe", "pickaxe"));
        assertEquals(1, BootstrapPlan.tier("minecraft:wooden_sword", "sword"));
    }

    @Test
    void dayAndNight() {
        assertFalse(DayNight.night(1000));
        assertTrue(DayNight.night(13000));
        assertTrue(DayNight.night(24000 * 3 + 20000));
        assertFalse(DayNight.night(23500));
        assertFalse(DayNight.night(-1));
        assertEquals("n0", DayNight.key(13000));
        assertEquals("d1", DayNight.key(23600));       // dawn belongs to the next day
        assertEquals("d1", DayNight.key(25000));
        assertTrue(DayNight.due("night", 13000, ""));
        assertFalse(DayNight.due("night", 14000, "n0"), "once per night");
        assertTrue(DayNight.due("night", 24000 + 13000, "n0"));
        assertFalse(DayNight.due("day", 13000, ""));
        assertTrue(DayNight.due("day", 24000 + 100, "d0"));
        assertFalse(DayNight.due("day", 24000 + 100, "d1"));
        assertFalse(DayNight.due("night", -1, ""));
        assertEquals("06:00", DayNight.clock(0));
        assertEquals("18:00", DayNight.clock(12000));
        assertEquals("00:30", DayNight.clock(18500));
    }

    @Test
    void lightGrid() {
        int[] b = LightGrid.parse("here 8", new int[]{100, 200});
        assertArrayEquals(new int[]{92, 192, 108, 208}, b);
        List<int[]> s = LightGrid.spots(b[0], b[1], b[2], b[3]);
        assertEquals(9, s.size());                     // 16 wide: 3 x 3 spots 6 apart, margin 2
        assertArrayEquals(new int[]{94, 194}, s.get(0));
        assertArrayEquals(new int[]{106, 206}, s.get(8));
        assertArrayEquals(new int[]{0, 0, 30, 12}, LightGrid.parse("30 12 0 0", new int[]{0, 0}));
        assertNull(LightGrid.parse("0 0 500 500", new int[]{0, 0}));
        assertNull(LightGrid.parse("nonsense", new int[]{0, 0}));
        assertArrayEquals(new int[]{-32, -32, 32, 32}, LightGrid.parse("here 99", new int[]{0, 0}));
        assertEquals("placed 14 torches, 3 spots skipped (water/air)", LightGrid.report(14, 3, "water/air"));
        assertEquals("placed 1 torch", LightGrid.report(1, 0, ""));
    }

    @Test
    void stockMath() {
        Map<String, Integer> want = new LinkedHashMap<>();
        want.put("minecraft:torch", 64);
        want.put("minecraft:bread", 16);
        Map<String, Integer> have = Map.of("minecraft:torch", 70, "minecraft:bread", 4);
        assertEquals(Map.of("minecraft:bread", 12), StockRules.shortOf(want, have));
        assertEquals("gather bread 12 then deposit bread", StockRules.chain(StockRules.shortOf(want, have)));
        assertTrue(StockRules.text(want, have).contains("torch 70/64, bread 4/16 - 1 short; next: restock base"));
        assertTrue(StockRules.text(Map.of(), have).startsWith("no stock targets"));
        assertEquals("cobblestone 64", StockRules.toBase("cobblestone 64 to base"));
        assertNull(StockRules.toBase("cobblestone 64"));
    }

    @Test
    void junkRules() {
        Set<String> junk = new LinkedHashSet<>(JunkRules.DEFAULT);
        assertFalse(junk.contains("minecraft:string"), "string is kept");
        List<String> refused = new ArrayList<>();
        Set<String> j2 = JunkRules.add(junk, List.of("sand", "diamond_pickaxe", "raw_iron", "torch"), refused);
        assertTrue(j2.contains("minecraft:sand"));
        assertEquals(List.of("diamond_pickaxe", "raw_iron", "torch"), refused);
        assertFalse(JunkRules.remove(j2, List.of("dirt")).contains("minecraft:dirt"));
        // depositables says how many stay (cobblestone keeps 64)
        Map<String, Integer> dep = new LinkedHashMap<>();
        dep.put("minecraft:dirt", 0);
        dep.put("minecraft:cobblestone", 64);
        dep.put("minecraft:sand", 0);
        dep.put("minecraft:gravel", 0);
        Map<String, Integer> inv = Map.of("minecraft:dirt", 128, "minecraft:cobblestone", 100, "minecraft:sand", 30, "minecraft:gravel", 20);
        Map<String, Integer> plan = JunkRules.plan(junk, dep, inv, "mining gravel 20");
        assertEquals(Map.of("minecraft:dirt", 128, "minecraft:cobblestone", 36), plan, "gravel is what the job collects; sand isn't junk");
        assertEquals(164, JunkRules.total(plan));
        assertTrue(JunkRules.listText(junk, "drop").contains("dirt"));
    }

    @Test
    void toolCare() {
        List<ToolCareRules.Tool> tools = List.of(new ToolCareRules.Tool("minecraft:stone_sword", 5, 131), new ToolCareRules.Tool("minecraft:iron_axe", 200, 250),
                new ToolCareRules.Tool("minecraft:stone_pickaxe", 3, 131));
        Map<String, String> n = ToolCareRules.needs(tools, Map.of());
        assertEquals(Map.of("sword", "minecraft:stone_sword"), n, "pickaxes have their own rule");
        // a second, good sword: nothing to do
        List<ToolCareRules.Tool> two = new ArrayList<>(tools);
        two.add(new ToolCareRules.Tool("minecraft:wooden_sword", 50, 59));
        assertTrue(ToolCareRules.needs(two, Map.of()).isEmpty());
        // the shovel broke (carried before, gone now)
        assertEquals("minecraft:stone_shovel", ToolCareRules.needs(tools, Map.of("shovel", "minecraft:stone_shovel")).get("shovel"));
        assertEquals("shovel", ToolCareRules.kind("minecraft:iron_shovel"));
        assertEquals("pickaxe", ToolCareRules.kind("minecraft:iron_pickaxe"));
        assertEquals("shield", ToolCareRules.kind("minecraft:shield"));
        // the replacement
        assertEquals("get stone_sword 1", ToolCareRules.replacement("sword", "minecraft:stone_sword", Set.of("minecraft:stone_sword"), Map.of()));
        assertEquals("craft stone_sword 1", ToolCareRules.replacement("sword", "minecraft:stone_sword", Set.of(), Map.of("minecraft:cobblestone", 5, "minecraft:stick", 4)));
        assertEquals("craft iron_axe 1", ToolCareRules.replacement("axe", "minecraft:iron_axe", Set.of(), Map.of("minecraft:iron_ingot", 3, "minecraft:oak_planks", 4, "minecraft:cobblestone", 9)));
        assertEquals("craft stone_axe 1", ToolCareRules.replacement("axe", "minecraft:stone_axe", Set.of(), Map.of("minecraft:iron_ingot", 3, "minecraft:oak_planks", 4, "minecraft:cobblestone", 9)));
        assertNull(ToolCareRules.replacement("sword", "minecraft:stone_sword", Set.of(), Map.of()), "nothing to make one from");
        assertEquals("craft shield 1", ToolCareRules.replacement("shield", "minecraft:shield", Set.of(), Map.of("minecraft:iron_ingot", 1, "minecraft:oak_log", 2)));
        assertTrue(ToolCareRules.text(tools).contains("stone_sword 5/131 (worn)"));
    }
}
