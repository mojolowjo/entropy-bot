package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** B7e N item 3: the self-check's rules on fake states, and the idle check's diff. */
class SelfCheckTest {
    static Map<String, Integer> sup(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    /** All set up: nothing to say. */
    static SelfCheck.State good() {
        return new SelfCheck.State(true, 2, true, 6, true, true, new int[]{10, -40, 20}, "north", null,
                sup("minecraft:torch", 32, "minecraft:iron_pickaxe", 1), 20,
                List.of(new SelfCheck.Tool("minecraft:iron_pickaxe", 200, 250)), true, 1000);
    }

    static SelfCheck.State with(SelfCheck.State s, String field, Object v) {
        return new SelfCheck.State(
                field.equals("strict") ? (Boolean) v : s.strict(), field.equals("areas") ? (Integer) v : s.areas(),
                field.equals("baseMarked") ? (Boolean) v : s.baseMarked(), field.equals("baseChests") ? (Integer) v : s.baseChests(),
                field.equals("foodChest") ? (Boolean) v : s.foodChest(), field.equals("homeSet") ? (Boolean) v : s.homeSet(),
                field.equals("mine") ? (int[]) v : s.mine(), s.mineDir(), field.equals("mineGaveUp") ? (String) v : s.mineGaveUp(),
                field.equals("supplies") ? castMap(v) : s.supplies(), field.equals("freeSlots") ? (Integer) v : s.freeSlots(),
                field.equals("tools") ? castList(v) : s.tools(), field.equals("ownerOnline") ? (Boolean) v : s.ownerOnline(),
                field.equals("companionAgeMs") ? (Long) v : s.companionAgeMs());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Integer> castMap(Object o) { return (Map<String, Integer>) o; }

    @SuppressWarnings("unchecked")
    static List<SelfCheck.Tool> castList(Object o) { return (List<SelfCheck.Tool>) o; }

    static SelfCheck.Finding only(SelfCheck.State s) {
        List<SelfCheck.Finding> f = SelfCheck.run(s);
        assertEquals(1, f.size(), "one finding: " + f);
        return f.get(0);
    }

    @Test
    void allGood() {
        assertTrue(SelfCheck.run(good()).isEmpty());
        assertTrue(SelfCheck.report(List.of()).startsWith("check: all good"));
    }

    @Test
    void eachRuleWithItsFix() {
        SelfCheck.Finding f = only(with(good(), "areas", 0));
        assertEquals("areas", f.key());
        assertEquals("area add <name> here 60", f.fix());
        f = only(with(good(), "strict", false));
        assertEquals("logmode", f.key());
        assertEquals("guard mode strict", f.fix());
        assertEquals("areas", only(with(with(good(), "strict", false), "areas", 0)).key(), "no areas says that, not log mode");
        assertEquals("setbase (standing at the base)", only(with(good(), "baseMarked", false)).fix());
        assertEquals("scan base", only(with(good(), "baseChests", 0)).fix());
        assertEquals("food", only(with(good(), "foodChest", false)).key());
        assertEquals("home", only(with(good(), "homeSet", false)).key());
        assertTrue(only(with(good(), "homeSet", false)).fix().startsWith("sethome"));
        assertEquals("deposit", only(with(good(), "freeSlots", 4)).fix());
        assertTrue(SelfCheck.run(with(good(), "freeSlots", 5)).isEmpty());
    }

    @Test
    void mineAtLavaLevelOrGivenUp() {
        SelfCheck.Finding f = only(with(good(), "mine", new int[]{-119, -54, 194}));
        assertEquals("minelava", f.key());
        assertTrue(f.text().contains("-119 -54 194"), f.text());
        assertTrue(f.fix().contains("mark mine -119 -50 194 north"), f.fix());
        assertTrue(SelfCheck.run(with(good(), "mine", new int[]{0, -53, 0})).isEmpty(), "one above lava level is fine");
        f = only(with(good(), "mineGaveUp", "blocked: lava ahead at 58 -55 194"));
        assertEquals("mineblocked", f.key());
        assertTrue(f.text().contains("lava ahead"));
        assertTrue(f.fix().startsWith("stripmine turn left"));
        assertTrue(SelfCheck.run(with(with(good(), "mine", null), "mineGaveUp", "x")).isEmpty(), "no mine marked: nothing about it");
    }

    @Test
    void ironPickaxeSupplyFixKeepsTheOtherLines() {
        SelfCheck.Finding f = only(with(good(), "supplies", sup("minecraft:torch", 32, "minecraft:bread", 16)));
        assertEquals("ironpick", f.key());
        assertEquals("supplies set torch 32, bread 16, iron_pickaxe 1", f.fix());
        assertEquals("supplies set iron_pickaxe 1", only(with(good(), "supplies", sup())).fix());
        assertEquals("ironpick", only(with(good(), "supplies", sup("minecraft:iron_pickaxe", 0))).key());
        assertTrue(SelfCheck.run(with(good(), "supplies", sup("iron_pickaxe", 2))).isEmpty(), "a bare id counts");
    }

    @Test
    void wornTools() {
        SelfCheck.Finding f = only(with(good(), "tools", List.of(new SelfCheck.Tool("minecraft:iron_pickaxe", 25, 250),
                new SelfCheck.Tool("minecraft:iron_pickaxe", 3, 250), new SelfCheck.Tool("minecraft:stone_sword", 100, 131))));
        assertEquals("tool:iron_pickaxe", f.key());
        assertEquals("craft iron_pickaxe", f.fix());
        assertTrue(SelfCheck.run(with(good(), "tools", List.of(new SelfCheck.Tool("minecraft:iron_pickaxe", 26, 250)))).isEmpty());
    }

    @Test
    void companionStaleOnlyWhileTheOwnerIsOnline() {
        SelfCheck.Finding f = only(with(good(), "companionAgeMs", 600_000L));
        assertEquals("companion", f.key());
        assertTrue(f.text().contains("10 min old"));
        assertNull(f.fix());
        assertEquals(f.text(), f.line(), "no fix: no tail");
        assertTrue(SelfCheck.run(with(with(good(), "companionAgeMs", 600_000L), "ownerOnline", false)).isEmpty());
        assertTrue(SelfCheck.run(with(good(), "companionAgeMs", -1L)).isEmpty(), "no companion: nothing to say");
    }

    @Test
    void reportAndIdleDiff() {
        SelfCheck.State bad = with(with(good(), "foodChest", false), "freeSlots", 2);
        List<SelfCheck.Finding> f = SelfCheck.run(bad);
        String r = SelfCheck.report(f);
        assertTrue(r.startsWith("check: 2 things to fix\n1. no food chest marked"), r);
        assertTrue(r.contains("2. my bag is nearly full (2 free slots) - next: deposit"), r);
        SelfCheck.Diff first = SelfCheck.diff(Set.of(), f);
        assertEquals(2, first.added().size());
        assertTrue(first.text().startsWith("self-check: no food chest"));
        // the same problems again: nothing to whisper (the bag count changing doesn't count as new)
        assertTrue(SelfCheck.diff(SelfCheck.keys(f), SelfCheck.run(with(bad, "freeSlots", 1))).empty());
        assertNull(SelfCheck.diff(SelfCheck.keys(f), f).text());
        // food fixed, home lost
        SelfCheck.Diff d = SelfCheck.diff(SelfCheck.keys(f), SelfCheck.run(with(with(good(), "freeSlots", 2), "homeSet", false)));
        assertEquals(List.of("home"), d.added().stream().map(SelfCheck.Finding::key).toList());
        assertEquals(List.of("food"), d.gone());
        assertTrue(d.text().contains("self-check: fixed now: food"), d.text());
    }
}
