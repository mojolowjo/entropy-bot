package io.github.mojolowjo.entropybot.aa.brain;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.vocab.HuntRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** BRAIN_PLAN "knowledge from the game at load": the loot JSON parser, the book's plan and the hunt-for rules. */
class LootDataTest {
    static JsonElement j(String s) { return JsonParser.parseString(s.replace('\'', '"')); }

    // vanilla 1.21 gravel: silk touch -> gravel, else flint at table_bonus 10%, else gravel
    static final String GRAVEL = "{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:alternatives','children':["
            + "{'type':'minecraft:item','name':'minecraft:gravel','conditions':[{'condition':'minecraft:match_tool','predicate':{}}]},"
            + "{'type':'minecraft:alternatives','children':[{'type':'minecraft:item','name':'minecraft:flint','conditions':[{'condition':'minecraft:table_bonus','enchantment':'minecraft:fortune','chances':[0.1,0.14,0.25,1.0]}]},"
            + "{'type':'minecraft:item','name':'minecraft:gravel'}]}]}]}]}";
    static final String COW = "{'pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'minecraft:leather','functions':[{'function':'minecraft:set_count','count':{'type':'minecraft:uniform','min':0,'max':2}}]}]},"
            + "{'rolls':1,'entries':[{'type':'minecraft:item','name':'minecraft:beef','functions':[{'function':'minecraft:set_count','count':{'type':'minecraft:uniform','min':1,'max':3}}]}]}]}";
    static final String ZOMBIE = "{'pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'minecraft:rotten_flesh','functions':[{'function':'minecraft:set_count','count':{'type':'minecraft:uniform','min':0,'max':2}}]}]},"
            + "{'rolls':1,'conditions':[{'condition':'minecraft:killed_by_player'},{'condition':'minecraft:random_chance_with_enchanted_bonus','unenchanted_chance':0.025}],"
            + "'entries':[{'type':'minecraft:item','name':'minecraft:iron_ingot'}]}]}";
    static final String CHEST = "{'pools':[{'rolls':{'type':'minecraft:uniform','min':2,'max':4},'entries':["
            + "{'type':'minecraft:item','weight':3,'name':'minecraft:diamond'},{'type':'minecraft:item','weight':1,'name':'minecraft:saddle'},"
            + "{'type':'minecraft:loot_table','value':'minecraft:chests/shared'}]}]}";
    static final String SHARED = "{'pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'minecraft:bread','functions':[{'function':'minecraft:set_count','count':4}]}]}]}";

    @Test
    void parsesBlockMobAndChestTablesWithChanceAndCounts() {
        Map<String, double[]> g = LootBook.parse(j(GRAVEL), null);
        assertEquals(0.1, g.get("minecraft:flint")[0], 1e-6, "flint 10%, silk touch skipped");
        assertEquals(0.9, g.get("minecraft:gravel")[0], 1e-6, "gravel the other 90%");

        Map<String, double[]> cow = LootBook.parse(j(COW), null);
        assertEquals(1.0, cow.get("minecraft:leather")[0], 1e-6);
        assertEquals(2.0, cow.get("minecraft:beef")[0], 1e-6);
        assertEquals(0, cow.get("minecraft:beef")[2], "any kill drops beef");

        Map<String, double[]> z = LootBook.parse(j(ZOMBIE), null);
        assertEquals(0.025, z.get("minecraft:iron_ingot")[0], 1e-6);
        assertEquals(1, z.get("minecraft:iron_ingot")[2], "player kill only");

        Map<String, double[]> c = LootBook.parse(j(CHEST), id -> id.equals("minecraft:chests/shared") ? j(SHARED) : null);
        assertEquals(3 * 0.6, c.get("minecraft:diamond")[0], 1e-6, "3 rolls x 3/5");
        assertEquals(3 * 0.2 * 4, c.get("minecraft:bread")[0], 1e-6, "nested table: 3 rolls x 1/5 x 4");
        assertNull(LootBook.parse(j(CHEST), id -> j(CHEST)).get("minecraft:nothing"), "a self-referencing table ends");
    }

    static LootBook book() {
        List<LootBook.Source> all = new ArrayList<>();
        all.addAll(LootBook.sources(LootBook.Kind.BLOCK, "minecraft:gravel", LootBook.parse(j(GRAVEL), null)));
        all.addAll(LootBook.sources(LootBook.Kind.MOB, "minecraft:cow", LootBook.parse(j(COW), null)));
        all.addAll(LootBook.sources(LootBook.Kind.MOB, "minecraft:zombie", LootBook.parse(j(ZOMBIE), null)));
        all.addAll(LootBook.sources(LootBook.Kind.MOB, "minecraft:spider", Map.of("minecraft:string", new double[]{1, 1, 0})));
        all.add(new LootBook.Source(LootBook.Kind.TRADE, "minecraft:cleric", "minecraft:ender_pearl", 1, 1, false, "5 emerald"));
        return new LootBook(all, "test");
    }

    static LootBook.Ctx ctx(boolean hunting, Set<String> allowed, boolean armed) {
        return new LootBook.Ctx(hunting, Set.of("minecraft:cow"), allowed, b -> b.equals("minecraft:gravel"), armed);
    }

    @Test
    void planBreaksKillsOrSaysWhyNot() {
        LootBook b = book();
        assertEquals("mine gravel 30", b.plan("flint", 3, ctx(false, Set.of(), false), false).command(), "break gravel for flint");
        assertEquals("hunt 3 cow for leather 3", b.plan("leather", 3, ctx(true, Set.of(), false), false).command());
        assertTrue(b.plan("leather", 3, ctx(false, Set.of(), false), false).text().contains("hunting is off"));
        LootBook.Plan s = b.plan("string", 2, ctx(true, Set.of(), true), true);
        assertNull(s.command(), "a hostile mob the owner did not allow");
        assertTrue(s.text().contains("animals kind"), s.text());
        assertTrue(b.plan("string", 2, ctx(true, Set.of("minecraft:spider"), false), false).text().contains("fight rules"));
        assertEquals("hunt 2 spider for string 2", b.plan("string", 2, ctx(true, Set.of("minecraft:spider"), true), true).command());
        LootBook.Plan pearl = b.plan("ender_pearl", 1, ctx(true, Set.of(), true), true);
        assertNull(pearl.command());
        assertTrue(pearl.text().contains("cleric"), pearl.text());
        assertTrue(b.sourcesText("flint", 3).contains("break gravel (10%)"), b.sourcesText("flint", 3));
        assertNull(b.plan("dirt", 1, ctx(true, Set.of(), true), true).text(), "nothing known");
    }

    @Test
    void goalChainUsesLootOnlyWhenNoRecipeMakesIt() {
        Blackboard bb = new Blackboard(new Clock.Fake(1_000), new FakePort(), new Tracer());
        Thing.Catalog cat = new Thing.Catalog(List.of("minecraft:flint", "minecraft:leather"), List.of("minecraft:gravel"), Map.of(), Map.of(), null);
        Goals.Goal flint = Goals.add(bb, Thing.resolve("flint", cat), 3, 0, 0);
        assertEquals("goal flint 3", Goals.chain(bb, flint), "no book: the planner");
        bb.fact(LootBook.FACT, book());
        bb.fact(LootBook.CTX_FACT, ctx(true, Set.of(), false));
        assertEquals("mine gravel 30", Goals.chain(bb, flint));
        bb.fact(TechTree.FACT, new TechTree(List.of(new TechTree.Recipe("minecraft:flint", List.of(Set.of("minecraft:gravel"))))));
        assertEquals("goal flint 3", Goals.chain(bb, flint), "a recipe makes it: the planner first");
        Goals.Goal leather = Goals.add(bb, Thing.resolve("leather", cat), 2, 0, 0);
        assertEquals("hunt 2 cow for leather 2", Goals.chain(bb, leather));
        bb.in.bag = Map.of("leather", 1);
        assertEquals("hunt 1 cow for leather 2", Goals.chain(bb, leather), "the hunt stops at the bag total");
        bb.in.bag = Map.of();
        assertTrue(Goals.line(bb, leather, "70").contains("kill cow for leather"), Goals.line(bb, leather, "70"));
    }

    @Test
    void huntForParsesAndKeepsTheProtections() {
        Object[] a = HuntRules.parseFor("3 spider for string 6");
        assertArrayEquals(new Object[]{3, "minecraft:spider", "minecraft:string", 6}, a);
        assertEquals(3, HuntRules.parseFor("3 cow for leather")[3]);
        assertNull(HuntRules.parseFor("3 cow leather"));
        assertNull(HuntRules.parseFor("0 cow for leather"));
        HuntRules.Target spider = new HuntRules.Target("minecraft:spider", false, false, false, false, false, false, true);
        assertFalse(HuntRules.mayTakeFor(spider, false, List.of(), "minecraft:spider"), "hostile, not allowed");
        assertTrue(HuntRules.mayTakeFor(spider, false, List.of("minecraft:spider"), "minecraft:spider"));
        HuntRules.Target horse = new HuntRules.Target("minecraft:horse", false, false, false, false, false, false, true);
        assertTrue(HuntRules.mayTakeFor(horse, true, List.of(), "minecraft:horse"), "passive");
        HuntRules.Target pet = new HuntRules.Target("minecraft:horse", true, false, true, false, false, false, true);
        assertFalse(HuntRules.mayTakeFor(pet, true, List.of(), "minecraft:horse"), "tamed never");
        assertFalse(HuntRules.mayTakeFor(horse, true, List.of(), "minecraft:cow"), "only the wanted mob");
    }
}
