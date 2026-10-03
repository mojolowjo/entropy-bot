package io.github.mojolowjo.entropybot.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class HotbarRulesTest {
    static HotbarRules.Item it(int slot, String id, int n) { return new HotbarRules.Item(slot, id, n, -1); }

    static HotbarRules.Item food(int slot, String id, int n, int score) { return new HotbarRules.Item(slot, id, n, score); }

    static String known(String q) {
        return switch (q) {
            case "cobblestone", "minecraft:cobblestone" -> "minecraft:cobblestone";
            case "copper_pickaxe" -> "leafscopperbackport:copper_pickaxe";
            default -> null;
        };
    }

    @Test
    void kindsMatchTheirItems() {
        assertTrue(HotbarRules.matches("pickaxe", "minecraft:stone_pickaxe", -1));
        assertTrue(HotbarRules.matches("axe", "minecraft:iron_axe", -1));
        assertFalse(HotbarRules.matches("axe", "minecraft:iron_pickaxe", -1), "a pickaxe is no axe");
        assertTrue(HotbarRules.matches("sword", "leafscopperbackport:copper_sword", -1));
        assertTrue(HotbarRules.matches("food", "minecraft:bread", 55));
        assertFalse(HotbarRules.matches("food", "minecraft:rotten_flesh", -1));
        assertTrue(HotbarRules.matches("torch", "minecraft:torch", -1));
        assertFalse(HotbarRules.matches("torch", "minecraft:redstone_torch", -1));
        assertTrue(HotbarRules.matches("minecraft:cobblestone", "minecraft:cobblestone", -1));
        assertFalse(HotbarRules.matches("pickaxe", "minecraft:air", -1));
        assertEquals("torch", HotbarRules.kind("torches"));
        assertEquals("pickaxe", HotbarRules.kind("pickaxes"));
        assertNull(HotbarRules.kind("cobblestone"));
    }

    @Test
    void setParsesSlotsKindsAndItems() {
        HotbarRules.Change c = HotbarRules.parseSet("1 pickaxe 2 sword 3 food 4 torch 9 cobblestone", HotbarRulesTest::known);
        assertNull(c.err());
        assertEquals(Map.of(1, "pickaxe", 2, "sword", 3, "food", 4, "torch", 9, "minecraft:cobblestone"), c.set());
        assertEquals("1 pickaxe, 2 sword, 3 food, 4 torch, 9 cobblestone", HotbarRules.describe(c.set()));
        assertEquals(Map.of(5, "leafscopperbackport:copper_pickaxe"), HotbarRules.parseSet("5 copper_pickaxe", HotbarRulesTest::known).set());
        assertTrue(HotbarRules.parseSet("0 sword", HotbarRulesTest::known).err().startsWith("error: hotbar slots are 1-9"));
        assertTrue(HotbarRules.parseSet("1 unobtainium", HotbarRulesTest::known).err().startsWith("error: I don't know an item called unobtainium"));
        assertEquals(HotbarRules.USAGE, HotbarRules.parseSet("1", HotbarRulesTest::known).err());
        assertEquals(Map.of(1, "sword"), HotbarRules.parseSet("1 pickaxe, 1 sword", HotbarRulesTest::known).set(), "a later one wins");
        // stored form (the dashboard's contract: {"1":"pickaxe",...})
        assertEquals(Map.of("1", "pickaxe", "4", "torch"), HotbarRules.toStrings(Map.of(4, "torch", 1, "pickaxe")));
        assertEquals(Map.of(1, "pickaxe"), HotbarRules.fromStrings(Map.of("1", "pickaxe", "10", "sword", "x", "food")));
    }

    @Test
    void planMovesOneItemAtATimeAndLeavesMissingKindsAlone() {
        Map<Integer, String> layout = new TreeMap<>(Map.of(1, "pickaxe", 2, "sword", 3, "food", 4, "torch", 5, "axe"));
        List<HotbarRules.Item> inv = new ArrayList<>(List.of(it(0, "minecraft:dirt", 64), it(1, "minecraft:cobblestone", 64), food(14, "minecraft:bread", 10, 55),
                food(15, "minecraft:cooked_beef", 3, 208), it(20, "minecraft:iron_pickaxe", 1), it(21, "minecraft:stone_pickaxe", 1),
                it(22, "minecraft:torch", 40), it(4, "minecraft:gravel", 5)));
        // pickaxe first: the cheapest one (the one stone wears out)
        assertEquals(new HotbarRules.Swap(21, 0), HotbarRules.plan(layout, inv));
        apply(inv, HotbarRules.plan(layout, inv));
        assertNull(HotbarRules.plan(Map.of(2, "sword"), inv), "no sword: slot 2 is left alone");
        assertEquals(new HotbarRules.Swap(15, 2), HotbarRules.plan(layout, inv), "the best food (sword missing, so food next)");
        apply(inv, HotbarRules.plan(layout, inv));
        assertEquals(new HotbarRules.Swap(22, 3), HotbarRules.plan(layout, inv));
        apply(inv, HotbarRules.plan(layout, inv));
        assertNull(HotbarRules.plan(layout, inv), "no axe: slot 5 keeps its gravel, and everything else is in place");
        assertEquals("gravel", find(inv, 4).id().replace("minecraft:", ""));
        // a slot that already holds its kind is never raided by another slot of the same kind
        Map<Integer, String> two = Map.of(1, "pickaxe", 6, "pickaxe");
        List<HotbarRules.Item> picks = new ArrayList<>(List.of(it(0, "minecraft:iron_pickaxe", 1)));
        assertNull(HotbarRules.plan(two, picks));
        // an exact item slot goes before kinds
        Map<Integer, String> exact = Map.of(1, "torch", 2, "minecraft:torch");
        assertEquals(new HotbarRules.Swap(9, 1), HotbarRules.plan(exact, List.of(it(9, "minecraft:torch", 64), it(10, "minecraft:torch", 3))));
        assertEquals(new HotbarRules.Swap(9, 0), HotbarRules.plan(Map.of(1, "sword"), List.of(it(9, "minecraft:iron_sword", 1), it(10, "minecraft:stone_sword", 1))), "swords: the best");
    }

    @Test
    void handSlotUsesTheLaidOutSlotAndOtherwiseTheOldRule() {
        List<HotbarRules.Item> inv = List.of(it(0, "minecraft:stone_pickaxe", 1), it(1, "minecraft:iron_sword", 1), it(12, "minecraft:iron_pickaxe", 1),
                it(13, "minecraft:stone_shovel", 1), it(5, "minecraft:dirt", 3));
        // no layout: a hotbar item is selected where it is, a bag item goes to the selected slot
        assertEquals(1, HotbarRules.handSlot(Map.of(), inv, 1, 4));
        assertEquals(4, HotbarRules.handSlot(Map.of(), inv, 12, 4));
        Map<Integer, String> layout = Map.of(1, "pickaxe", 2, "sword", 5, "torch");
        assertEquals(0, HotbarRules.handSlot(layout, inv, 12, 3), "the iron pickaxe goes into the pickaxe slot (the stone one swaps out)");
        assertEquals(1, HotbarRules.handSlot(layout, inv, 1, 0), "the sword is in its slot already");
        assertEquals(3, HotbarRules.handSlot(layout, inv, 13, 3), "a shovel has no slot: the selected one, which is free");
        assertEquals(2, HotbarRules.handSlot(layout, inv, 13, 4), "the selected slot is laid out (torch): the first free slot nobody laid out");
        assertEquals(5, HotbarRules.handSlot(layout, inv, 5, 4), "a hotbar item with no laid-out slot is selected where it is");
    }

    @Test
    void keepsAndShow() {
        Map<Integer, String> layout = Map.of(1, "pickaxe", 3, "minecraft:cobblestone", 4, "torch");
        List<HotbarRules.Item> inv = List.of(it(0, "minecraft:stone_pickaxe", 1), it(9, "minecraft:cobblestone", 30), it(10, "minecraft:cobblestone", 64), it(3, "minecraft:dirt", 8));
        assertEquals(Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 64), HotbarRules.keeps(layout, inv));
        assertEquals("hotbar: 1 pickaxe, 3 cobblestone, 4 torch (now: 1 stone_pickaxe, 3 -, 4 dirt (wrong))", HotbarRules.show(layout, inv));
        assertEquals(HotbarRules.NO_LAYOUT, HotbarRules.show(Map.of(), inv));
    }

    @Test
    void toolsSetting() {
        assertEquals("iron", HotbarRules.toolOres(null));
        assertEquals("cheapest", HotbarRules.toolOres("cheapest"));
        assertEquals("cheapest", HotbarRules.toolsCommand("ores cheapest")[0]);
        assertEquals("iron", HotbarRules.toolsCommand("ORES iron")[0]);
        assertNull(HotbarRules.toolsCommand("ores gold")[0]);
        assertTrue(HotbarRules.toolsText("iron").startsWith("tools: ores with the iron pickaxe"));
    }

    static HotbarRules.Item find(List<HotbarRules.Item> inv, int slot) {
        for (HotbarRules.Item i : inv) if (i.slot() == slot) return i;
        return null;
    }

    /** A SWAP click: bag slot from and hotbar slot to trade places. */
    static void apply(List<HotbarRules.Item> inv, HotbarRules.Swap s) {
        HotbarRules.Item a = find(inv, s.from()), b = find(inv, s.to());
        inv.remove(a);
        if (b != null) inv.remove(b);
        inv.add(new HotbarRules.Item(s.to(), a.id(), a.count(), a.food()));
        if (b != null) inv.add(new HotbarRules.Item(s.from(), b.id(), b.count(), b.food()));
    }
}
