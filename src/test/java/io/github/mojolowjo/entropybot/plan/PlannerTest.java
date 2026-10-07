package io.github.mojolowjo.entropybot.plan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.commands.VerbTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** B3: the action table, the goal search, its bound, the routine rule, the goal grammar and the document. */
class PlannerTest {

    /** A fresh spot with trees, free cells for the table and furnace, and the quarry's ground. */
    static PlanFacts forest() {
        PlanFacts f = new PlanFacts().flag("trees", "spot:table", "spot:furnace", "quarry");
        f.feet = new int[]{10, 64, 10};
        f.table = new int[]{8, 64, 10};
        f.furnace = new int[]{8, 64, 12};
        return f;
    }

    static int index(Planner.Result r, String prefix) {
        for (int i = 0; i < r.steps().size(); i++) if (r.steps().get(i).text().startsWith(prefix)) return i;
        return -1;
    }

    @Test
    void tableCoversEveryVerb() {
        assertEquals(List.of(), ActionTable.selfTest());
        for (VerbTable.Verb v : VerbTable.all()) assertNotNull(ActionTable.verbNote(v.name()), v.name());
        for (String a : Planner.ACTIONS) assertTrue(ActionTable.actions().get(a).planner(), a);
    }

    @Test
    void bedFromNothingButWool() {
        PlanFacts f = forest().bag("white_wool", 3);
        Planner.Result r = Planner.plan(f, GoalGrammar.parse("bed", null).needs());
        assertTrue(r.ok(), r.missing() + " " + r.error());
        int cut = index(r, "cut "), planks = index(r, "craft planks"), table = index(r, "craft crafting_table"), place = index(r, "goto 10 64 10 then place crafting_table 8 64 10"),
                bed = index(r, "craft white_bed 1");
        assertTrue(cut == 0 && planks > cut && table > planks && place > table && bed > place, r.costed());
        assertEquals(bed, r.steps().size() - 1, r.costed());
        assertTrue(r.nodes() < Planner.MAX_NODES);
    }

    @Test
    void bedWithoutWoolNamesTheWool() {
        Planner.Result r = Planner.plan(forest(), GoalGrammar.parse("bed", null).needs());
        assertFalse(r.ok());
        assertNull(r.error());
        assertTrue(r.missing().contains("white_wool"), r.missing());
    }

    @Test
    void woolInTheChestsIsFetched() {
        Planner.Result r = Planner.plan(forest().stock("white_wool", 5), GoalGrammar.parse("bed", null).needs());
        assertTrue(r.ok(), r.missing());
        assertTrue(index(r, "get white_wool 3") >= 0, r.costed());
    }

    @Test
    void furnaceWithStone() {
        PlanFacts f = forest().flag("stone");
        Planner.Result r = Planner.plan(f, GoalGrammar.parse("furnace", null).needs());
        assertTrue(r.ok(), r.missing());
        assertTrue(index(r, "craft wooden_pickaxe 1") >= 0 && index(r, "dig ") > index(r, "craft wooden_pickaxe"), r.costed());
        assertTrue(index(r, "goto 10 64 10 then place furnace 8 64 12") > index(r, "craft furnace 1"), r.costed());
        assertTrue(Planner.used(r).containsAll(List.of("cut", "craft", "place", "quarry")));
    }

    @Test
    void furnaceOnDirtNamesTheStone() {
        Planner.Result r = Planner.plan(forest(), GoalGrammar.parse("furnace", null).needs());
        assertFalse(r.ok());
        assertTrue(r.missing().contains("cobblestone") && r.missing().contains("stone"), r.missing());
    }

    @Test
    void shelterOfPlanks() {
        Planner.Result r = Planner.plan(forest(), GoalGrammar.parse("shelter", null).needs());
        assertTrue(r.ok(), r.missing());
        assertTrue(r.chain().endsWith("area here 1 shelter neutral 1 2 then build shell oak_planks shelter"), r.chain());
        assertTrue(index(r, "cut ") == 0, r.costed());
    }

    @Test
    void diamondWithoutAMineNamesTheMissingPlace() {
        Planner.Result r = Planner.plan(forest(), GoalGrammar.parse("diamond 1", null).needs());
        assertFalse(r.ok());
        assertTrue(r.missing().contains("nowhere to mine diamond"), r.missing());
        assertTrue(r.ms() < 200);
    }

    @Test
    void ironToolsFromAStoneWorld() {
        PlanFacts f = forest().flag("stone", "mine");
        Planner.Result r = Planner.plan(f, GoalGrammar.parse("iron tools", null).needs());
        assertTrue(r.ok(), r.missing() + " " + r.error());
        assertTrue(index(r, "mine strip iron") > index(r, "craft stone_pickaxe"), r.costed());
        assertTrue(index(r, "smelt iron_ingot") > index(r, "mine strip iron"), r.costed());
        assertTrue(index(r, "craft iron_pickaxe") > index(r, "smelt iron_ingot"), r.costed());
        assertTrue(r.steps().size() <= Planner.MAX_STEPS);
    }

    @Test
    void alreadyHaveIsEmpty() {
        Planner.Result r = Planner.plan(forest().bag("minecraft:oak_log", 20), GoalGrammar.parse("wood 16", null).needs());
        assertTrue(r.ok());
        assertTrue(r.steps().isEmpty());
    }

    @Test
    void theBoundStopsTheSearch() {
        Planner.Result r = Planner.plan(forest().flag("stone", "mine"), GoalGrammar.parse("iron tools", null).needs(), 5, 1000);
        assertFalse(r.ok());
        assertTrue(r.error().contains("limit (5 nodes)"), r.error());
    }

    @Test
    void routineRule() {
        assertEquals(RoutineRule.Decision.SAVE, RoutineRule.decide(null, null));
        assertEquals(RoutineRule.Decision.SAVE, RoutineRule.decide(null, "cut 2 logs"));
        assertEquals(RoutineRule.Decision.OVERWRITE, RoutineRule.decide("cut 2 logs", "cut 2 logs"));
        assertEquals(RoutineRule.Decision.KEEP, RoutineRule.decide("cut 4 logs then craft white_bed 1", "cut 2 logs"));
        assertEquals(RoutineRule.Decision.KEEP, RoutineRule.decide("deposit", null), "the owner's own routine of that name");
    }

    @Test
    void grammar() {
        assertEquals("camp here", GoalGrammar.parse("camp", null).chain());
        assertEquals("goal_bed", GoalGrammar.parse("bed", null).routine());
        assertEquals("goal_iron_tools", GoalGrammar.parse("Iron  Tools", null).routine());
        assertEquals(List.of(Planner.Need.item("iron_ingot", 16)), GoalGrammar.parse("iron 16", null).needs());
        assertEquals(List.of(Planner.Need.item("log", 8)), GoalGrammar.parse("wood 8", null).needs());
        assertEquals(List.of(Planner.Need.item("torch", 32)), GoalGrammar.parse("minecraft:torch 32", null).needs());
        assertEquals("goal_diamond_1", GoalGrammar.parse("diamond 1", null).routine());
        assertTrue(GoalGrammar.parse("light yard", n -> false).error().contains("no area called yard"));
        assertEquals("light yard", GoalGrammar.parse("light yard", n -> true).chain());
        assertTrue(GoalGrammar.parse("fly to the moon", null).error().startsWith("I can't plan that"));
        assertTrue(GoalGrammar.parse("iron 0", null).error().contains("1 to"));
        assertTrue(GoalGrammar.parse("create:gear 2", null).error().contains("vanilla"));
        assertEquals(List.of(Planner.Need.fact("shelter")), GoalGrammar.parse("shelter", null).needs());
        assertTrue(GoalGrammar.routineName("a_very_long_goal_name_here").length() <= 20);
    }

    @Test
    void documentShapeAndSize() {
        String table = ActionTable.table().toString();
        assertTrue(table.length() < ActionTable.BUDGET, "size " + table.length());
        JsonObject state = new JsonObject();
        state.addProperty("stage", "wood");
        JsonObject d = JsonParser.parseString(ActionTable.document(state).toString()).getAsJsonObject();
        assertEquals(ActionTable.VERSION, d.get("version").getAsInt());
        assertTrue(d.getAsJsonArray("actions").size() >= Planner.ACTIONS.size());
        assertEquals(VerbTable.all().size(), d.getAsJsonArray("verbs").size());
        assertEquals("wood", d.getAsJsonObject("state").get("stage").getAsString());
        assertTrue(d.get("goals").getAsString().contains("goal bed"));
        assertTrue(ActionTable.shortText("craft").startsWith("craft: craft <item>"));
        assertTrue(ActionTable.shortText("say").contains("not planable"));
        assertTrue(ActionTable.shortText("").startsWith("actions v1"));
    }

    @Test
    void canon() {
        assertEquals("log", PlanFacts.canon("minecraft:spruce_log"));
        assertEquals("planks", PlanFacts.canon("minecraft:birch_planks"));
        assertEquals("fuel", PlanFacts.canon("minecraft:charcoal"));
        PlanFacts f = new PlanFacts().bag("minecraft:stone_pickaxe", 1);
        assertTrue(f.flags.containsAll(List.of("pick1", "pick2")) && !f.flags.contains("pick3"));
    }
}
