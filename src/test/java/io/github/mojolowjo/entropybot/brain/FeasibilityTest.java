package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.mojolowjo.entropybot.brain.Feasibility.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** 0.26.2 "feasibility before every pick": the precondition table over fake facts, and the brain's use of it. */
class FeasibilityTest {

    /** Known facts, nothing in the bag or storage, no torches/coal/logs/wood/food, no bed; base, camp, mine and farm known. */
    static BrainState empty() {
        BrainState s = new BrainState();
        s.torches = 0; s.coal = 0; s.logs = 0; s.woodItems = 0; s.foodItems = 0; s.foodStock = 0;
        s.shelterBlocks = 0; s.bedNear = false; s.bedInBag = false; s.pickaxes = 0;
        Feasibility.Facts f = s.feas;
        f.known = true;
        for (String p : new String[]{"base", "camp", "mine", "farm"}) f.places.put(p, new int[]{0, 64, 0});
        f.isArea = n -> n.equals("yard");
        f.inFence = p -> Math.abs(p[0]) < 100 && Math.abs(p[2]) < 100;
        f.baseChests = true;
        f.mineMarked = true;
        return s;
    }

    static Feasibility.Verdict v(String chain, BrainState s) { return Feasibility.check(chain, s); }

    // ---- light ----
    @Nested class Light {
        @Test void withTorches() { BrainState s = empty(); s.torches = 1; assertEquals(FEASIBLE, v("light here 8", s).kind()); }
        @Test void withCoalAndWood() { BrainState s = empty(); s.coal = 1; s.logs = 1; s.woodItems = 1; assertEquals(FEASIBLE, v("light here 8", s).kind()); }
        @Test void coalWithoutWoodIsNot() {
            BrainState s = empty(); s.coal = 3;
            Feasibility.Verdict r = v("light here 8", s);
            assertEquals(INFEASIBLE, r.kind());
            assertEquals("torch", r.item());
            assertEquals(FailMemory.Reason.NO_ITEM, r.cls());
        }
        @Test void nothingAtAll() {
            Feasibility.Verdict r = v("light here 8", empty());
            assertEquals(INFEASIBLE, r.kind());
            assertEquals("no torches, nothing to craft them from", r.missing());
            assertEquals(0, r.step());
        }
        @Test void torchesInStorage() { BrainState s = empty(); s.feas.stock.put("torch", 5); assertEquals(FEASIBLE, v("light here 8", s).kind()); }
        @Test void anEarlierCraftGivesThem() { BrainState s = empty(); s.coal = 1; s.logs = 1; assertEquals(FEASIBLE, v("craft torch 4 then light here 8", s).kind()); }
        @Test void anEarlierGatherGivesThem() { assertNotEquals(INFEASIBLE, v("gather torch 16 then light here 8", empty()).kind()); }
        @Test void defaultsAreFeasible() { assertEquals(FEASIBLE, v("light here 8", new BrainState()).kind()); }
    }

    @Nested class Sleep {
        @Test void byDayNot() { BrainState s = empty(); s.bedNear = true; Feasibility.Verdict r = v("sleep", s); assertEquals(INFEASIBLE, r.kind()); assertNull(r.cls()); }
        @Test void nightBedNear() { BrainState s = empty(); s.night = true; s.bedNear = true; assertEquals(FEASIBLE, v("sleep", s).kind()); }
        @Test void nightBedInBag() { BrainState s = empty(); s.night = true; s.bedInBag = true; assertEquals(FEASIBLE, v("sleep", s).kind()); }
        @Test void nightNoBed() { BrainState s = empty(); s.night = true; Feasibility.Verdict r = v("sleep", s); assertEquals(FailMemory.Reason.NO_BED, r.cls()); assertEquals("white_bed", r.item()); }
    }

    @Nested class Spawn {
        @Test void bedNear() { BrainState s = empty(); s.bedNear = true; assertEquals(FEASIBLE, v("spawn", s).kind()); }
        @Test void bagBedIsNotEnough() { BrainState s = empty(); s.bedInBag = true; assertEquals(FailMemory.Reason.NO_BED, v("spawn", s).cls()); }
    }

    @Nested class Shelter {
        @Test void atTheBoundary() { BrainState s = empty(); s.shelterBlocks = Feasibility.SHELTER_MIN; assertEquals(FEASIBLE, v("shelter 5m", s).kind()); }
        @Test void oneShort() {
            BrainState s = empty(); s.shelterBlocks = Feasibility.SHELTER_MIN - 1;
            Feasibility.Verdict r = v("shelter", s);
            assertEquals(INFEASIBLE, r.kind());
            assertEquals("planks", r.item());
            assertEquals(FailMemory.Reason.NO_ITEM, r.cls());
        }
        @Test void minIsNine() { assertEquals(9, Feasibility.SHELTER_MIN); }
        @Test void theBrainsPrepMakesItFeasibleOrUnknown() { assertNotEquals(INFEASIBLE, v("cut 8 logs then craft planks 32 then shelter 5m", empty()).kind()); }
        @Test void prepWithTreesIsFeasible() { BrainState s = empty(); s.feas.trees = true; assertEquals(FEASIBLE, v("cut 8 logs then craft planks 32 then shelter 5m", s).kind()); }
        @Test void statusAlways() { assertEquals(FEASIBLE, v("shelter status", empty()).kind()); }
    }

    @Nested class Deposit {
        @Test void scanned() { assertEquals(FEASIBLE, v("deposit", empty()).kind()); }
        @Test void notScanned() { BrainState s = empty(); s.feas.baseChests = false; assertEquals(FailMemory.Reason.NONE_IN_CHESTS, v("deposit", s).cls()); }
        @Test void noBase() { BrainState s = empty(); s.feas.places.remove("base"); assertEquals(FailMemory.Reason.NO_PLACE, v("deposit", s).cls()); }
        @Test void unknownFacts() { assertEquals(UNKNOWN, v("deposit", new BrainState()).kind()); }
        @Test void noItemForASubPlan() { BrainState s = empty(); s.feas.baseChests = false; assertNull(v("deposit", s).item()); }
    }

    @Nested class Get {
        @Test void inStock() { BrainState s = empty(); s.feas.stock.put("iron_ingot", 3); assertEquals(FEASIBLE, v("get iron_ingot 3", s).kind()); }
        @Test void notInStock() { assertEquals(FailMemory.Reason.NONE_IN_CHESTS, v("get iron_ingot 3", empty()).cls()); }
        @Test void namespaced() { BrainState s = empty(); s.feas.stock.put("iron_ingot", 1); assertEquals(FEASIBLE, v("get minecraft:iron_ingot 3", s).kind()); }
        @Test void foodInStorage() { BrainState s = empty(); s.foodStock = 4; assertEquals(FEASIBLE, v("get food 8", s).kind()); }
        @Test void foodNowhere() { assertEquals(FailMemory.Reason.NONE_IN_CHESTS, v("get food 8", empty()).cls()); }
        @Test void unknownStock() { assertEquals(UNKNOWN, v("get iron_ingot 3", new BrainState()).kind()); }
    }

    @Nested class Craft {
        @Test void torchFromCoalAndLogs() { BrainState s = empty(); s.coal = 1; s.logs = 1; assertEquals(FEASIBLE, v("craft torch 4", s).kind()); }
        @Test void torchBoundary() {
            BrainState s = empty(); s.coal = 1; s.logs = 1;
            assertEquals(INFEASIBLE, v("craft torch 5", s).kind());      // 2 crafts need 2 coal
            Feasibility.Verdict r = v("craft torch 5", s);
            assertEquals("coal", r.item());
        }
        @Test void stonePickaxeNeedsCobble() {
            BrainState s = empty(); s.logs = 1;
            Feasibility.Verdict r = v("craft stone_pickaxe 1", s);
            assertEquals(INFEASIBLE, r.kind());
            assertEquals("cobblestone", r.item());
            assertEquals(3, r.n());
        }
        @Test void stonePickaxeWithCobbleAndTable() { BrainState s = empty(); s.logs = 1; s.feas.bag.put("cobblestone", 3); s.feas.table = true; assertEquals(FEASIBLE, v("craft stone_pickaxe 1", s).kind()); }
        @Test void noTableAndNoPlanksForOne() {
            BrainState s = empty(); s.feas.bag.put("cobblestone", 3); s.feas.bag.put("stick", 2);
            assertEquals("crafting_table", v("craft stone_pickaxe 1", s).item());
        }
        @Test void cobbleInStorage() { BrainState s = empty(); s.logs = 2; s.feas.stock.put("cobblestone", 8); assertEquals(FEASIBLE, v("craft furnace 1", s).kind()); }
        @Test void unknownRecipe() { assertEquals(UNKNOWN, v("craft refinedstorage:basic_processor 4", empty()).kind()); }
        @Test void unknownBagForCobble() { assertEquals(UNKNOWN, v("craft stone_pickaxe 1", new BrainState()).kind()); }
        @Test void planksFromLogs() { BrainState s = empty(); s.logs = 1; assertEquals(FEASIBLE, v("craft planks 4", s).kind()); }
        @Test void planksBoundary() { BrainState s = empty(); s.logs = 1; assertEquals(INFEASIBLE, v("craft planks 8", s).kind()); }
    }

    @Nested class Gather {
        @Test void inStock() { BrainState s = empty(); s.feas.stock.put("iron_ingot", 1); assertEquals(FEASIBLE, v("gather iron_ingot 16", s).kind()); }
        @Test void elseUnknown() { assertEquals(UNKNOWN, v("gather iron_ingot 16", empty()).kind()); }
        @Test void woodWithTrees() { BrainState s = empty(); s.feas.trees = true; assertEquals(FEASIBLE, v("gather oak_log 16", s).kind()); }
        @Test void torchesFromCoal() { BrainState s = empty(); s.coal = 2; s.logs = 1; assertEquals(FEASIBLE, v("gather torch 16", s).kind()); }
    }

    @Nested class Mine {
        @Test void stripWithPick() { BrainState s = empty(); s.feas.pickTier = 2; assertEquals(FEASIBLE, v("mine strip any 32", s).kind()); }
        @Test void stripNoMine() { BrainState s = empty(); s.feas.pickTier = 2; s.feas.mineMarked = false; assertEquals(FailMemory.Reason.NO_PLACE, v("mine strip any 32", s).cls()); }
        @Test void stripNoPick() { Feasibility.Verdict r = v("mine strip any 32", empty()); assertEquals("wooden_pickaxe", r.item()); assertEquals(FailMemory.Reason.NO_ITEM, r.cls()); }
        @Test void tierBoundary() {
            BrainState s = empty(); s.feas.pickTier = 2;
            assertEquals(FEASIBLE, v("mine strip iron 16", s).kind());
            assertEquals("iron_pickaxe", v("mine strip diamond 16", s).item());
            s.feas.pickTier = 3;
            assertEquals(FEASIBLE, v("mine strip diamond 16", s).kind());
        }
        @Test void aListNeedsTheLowestTier() { BrainState s = empty(); s.feas.pickTier = 2; assertEquals(FEASIBLE, v("mine strip iron,diamond 16", s).kind()); }
        @Test void materialsCount() { BrainState s = empty(); s.pickMaterials = true; assertEquals(FEASIBLE, v("mine strip iron 16", s).kind()); }
        @Test void pickInStorage() { BrainState s = empty(); s.feas.stock.put("iron_pickaxe", 1); assertEquals(FEASIBLE, v("mine strip diamond 16", s).kind()); }
        @Test void caveKnown() { BrainState s = empty(); s.feas.pickTier = 1; s.feas.caveKnown = true; assertEquals(FEASIBLE, v("mine cave any 32 20m", s).kind()); }
        @Test void caveUnknown() { BrainState s = empty(); s.feas.pickTier = 1; assertEquals(UNKNOWN, v("mine cave any 32 20m", s).kind()); }
        @Test void unknownFacts() { assertEquals(UNKNOWN, v("mine strip any 32", new BrainState()).kind()); }
        @Test void oreTiers() { assertEquals(1, Feasibility.oreTier("any")); assertEquals(2, Feasibility.oreTier("iron")); assertEquals(3, Feasibility.oreTier("deepslate_diamond_ore")); }
    }

    @Nested class Walk {
        @Test void goKnown() { assertEquals(FEASIBLE, v("go base", empty()).kind()); }
        @Test void goArea() { assertEquals(FEASIBLE, v("go yard", empty()).kind()); }
        @Test void goUnknownPlace() { assertEquals(FailMemory.Reason.NO_PLACE, v("go village", empty()).cls()); }
        @Test void goOutsideFence() { BrainState s = empty(); s.feas.places.put("camp", new int[]{500, 64, 0}); assertEquals(FailMemory.Reason.OUTSIDE, v("go camp", s).cls()); }
        @Test void gotoInside() { assertEquals(FEASIBLE, v("goto 10 64 10", empty()).kind()); }
        @Test void gotoOutside() { assertEquals(FailMemory.Reason.OUTSIDE, v("goto 500 64 10", empty()).cls()); }
        @Test void gotoFenceUnknown() { BrainState s = empty(); s.feas.inFence = null; assertEquals(UNKNOWN, v("goto 500 64 10", s).kind()); }
        @Test void homeNeedsBase() { BrainState s = empty(); s.feas.places.clear(); assertEquals(FailMemory.Reason.NO_PLACE, v("dismiss then home", s).cls()); }
    }

    @Nested class Whole {
        @Test void eatWithFoodStock() { BrainState s = empty(); s.foodStock = 1; assertEquals(FEASIBLE, v("eat", s).kind()); }
        @Test void eatNothing() { assertEquals(INFEASIBLE, v("eat", empty()).kind()); }
        @Test void firstInfeasibleStepWins() {
            BrainState s = empty(); s.feas.baseChests = false;
            Feasibility.Verdict r = v("go base then deposit then light here 8", s);
            assertEquals(1, r.step());
            assertEquals("deposit", r.stepText());
        }
        @Test void unknownVerbs() {
            for (String c : new String[]{"farm", "restock", "explore 5", "come", "smelt collect", "goal_bed", "dismiss"}) assertEquals(UNKNOWN, v(c, empty()).kind(), c);
        }
        @Test void unknownThenInfeasibleIsInfeasible() { assertEquals(INFEASIBLE, v("farm then light here 8", empty()).kind()); }
        @Test void neverThrows() {
            assertEquals(UNKNOWN, v(null, empty()).kind());
            assertEquals(UNKNOWN, v("   ", empty()).kind());
            assertNotNull(v("craft", empty()));
            assertNotNull(v("goto a b c", empty()));
            assertNotNull(v("get", empty()));
        }
    }

    // ---- the brain ----

    static BrainTest.Fake brain(java.util.function.Consumer<BrainState> set) {
        BrainTest.Fake f = BrainTest.on();
        BrainState s = f.state;
        s.feas.known = true;
        s.feas.mineMarked = true;
        s.feas.places.put("base", new int[]{0, 64, 0});
        s.feas.baseChests = true;
        set.accept(s);
        return f;
    }

    @Nested class Integration {
        @Test void feasibleStartsAsBefore() {
            BrainTest.Fake f = brain(s -> s.feas.pickTier = 2);
            Brain b = new Brain(f);
            b.tick();
            assertEquals(1, f.started.size());
            assertTrue(f.started.get(0).contains("mine strip"), f.started.toString());
            assertTrue(b.feasibilityLine().startsWith("feasibility: 1 checked, 0 infeasible"), b.feasibilityLine());
        }

        @Test void missingItemDefersToThePlannerThenRunsTheSubchainFirst() {
            BrainTest.Fake f = brain(s -> {});      // pick tier 0, no materials: the strip mine lacks a pickaxe
            f.planner = true;
            Brain b = new Brain(f);
            b.tick();
            assertTrue(f.started.isEmpty(), f.started.toString());
            assertEquals("feasibility.plan", b.lastDecision().branch());
            assertTrue(b.lastDecision().reason().startsWith("planning a way to get wooden_pickaxe for mine strip"), b.lastDecision().reason());
            assertEquals(1, f.planAsked.size());
            assertEquals("1 wooden_pickaxe max 6", f.planAsked.get(0));
            f.loop();
            b.tick();                               // still pending: not asked twice
            assertEquals(1, f.planAsked.size());
            assertTrue(f.started.isEmpty());
            f.planPending.get(0).accept(new String[]{"cut 2 logs then craft wooden_pickaxe 1", null});
            f.loop();
            b.tick();
            assertEquals(1, f.started.size());
            assertTrue(f.started.get(0).startsWith("cut 2 logs then craft wooden_pickaxe 1 then "), f.started.get(0));
            assertTrue(b.lastDecision().reason().contains("needs wooden_pickaxe: cut 2 logs then craft wooden_pickaxe 1 first"), b.lastDecision().reason());
            assertTrue(b.feasibilityLine().endsWith("1 subgoals this hour"), b.feasibilityLine());
        }

        @Test void notFoundIsRememberedAndSkipped() {
            BrainTest.Fake f = brain(s -> {});
            f.planner = true;
            Brain b = new Brain(f);
            b.tick();
            f.planPending.get(0).accept(new String[]{null, "missing 3 cobblestone"});
            f.loop();
            b.tick();
            assertEquals(1, b.failMemory().size());
            assertTrue(b.failMemory().describe(f.now).contains("NO_ITEM"), b.failMemory().describe(f.now));
            assertTrue(b.why().contains("infeasible: mine strip"), b.why());
            assertTrue(b.why().contains("no plan: missing 3 cobblestone"), b.why());
            f.loop();
            b.tick();
            assertEquals(1, f.planAsked.size(), "not re-evaluated every loop");
        }

        @Test void noPlannerSkipsAtOnce() {
            BrainTest.Fake f = brain(s -> {});
            Brain b = new Brain(f);
            b.tick();
            assertEquals(1, b.failMemory().size());
            assertFalse(f.started.stream().anyMatch(c -> c.contains("mine strip")), f.started.toString());
        }

        @Test void noItemSkipNeedsNoPlanner() {
            BrainTest.Fake f = brain(s -> { s.feas.pickTier = 2; s.feas.mineMarked = false; });
            f.planner = true;
            Brain b = new Brain(f);
            b.tick();
            assertTrue(f.planAsked.isEmpty());
            assertTrue(b.failMemory().describe(f.now).contains("NO_PLACE"), b.failMemory().describe(f.now));
        }

        @Test void offStartsAsToday() {
            BrainTest.Fake f = brain(s -> {});
            f.files.put(Brain.CONFIG_FILE, "{\"brain.feasibility\":0}");
            Brain b = new Brain(f);
            b.tick();
            assertTrue(f.started.stream().anyMatch(c -> c.contains("mine strip")), f.started.toString());
            assertEquals("feasibility off", b.feasibilityLine());
        }

        @Test void failMemoryOffStartsAsToday() {
            BrainTest.Fake f = brain(s -> {});
            f.files.put(Brain.CONFIG_FILE, "{\"brain.failMemoryMinutes\":0}");
            Brain b = new Brain(f);
            b.tick();
            assertTrue(f.started.stream().anyMatch(c -> c.contains("mine strip")), f.started.toString());
        }

        @Test void unknownFactsStartAsToday() {
            BrainTest.Fake f = BrainTest.on();
            Brain b = new Brain(f);
            b.tick();
            assertEquals(1, f.started.size());
            assertEquals(0, b.failMemory().size());
        }

        @Test void ownerChainsAreUntouched() {
            BrainTest.Fake f = brain(s -> s.ownerJob = "light here 8");
            Brain b = new Brain(f);
            b.tick();
            assertTrue(f.started.isEmpty());
            assertEquals("owner", b.lastDecision().branch());
            assertTrue(b.feasibilityLine().startsWith("feasibility: 0 checked"), b.feasibilityLine());
        }

        @Test void statusShowsTheLine() {
            BrainTest.Fake f = brain(s -> s.feas.pickTier = 2);
            Brain b = new Brain(f);
            b.tick();
            assertTrue(b.status().contains("feasibility: 1 checked, 0 infeasible, 0 subgoals this hour"), b.status());
        }

        @Test void brainJsonAndTheLogCarryTheVerdict() {
            BrainTest.Fake f = brain(s -> {});
            f.planner = true;
            Brain b = new Brain(f);
            b.tick();
            JsonObject fe = f.written.getAsJsonObject("feasibility");
            assertNotNull(fe);
            assertEquals("NO_ITEM", fe.get("class").getAsString());
            assertTrue(fe.get("step").getAsString().startsWith("mine strip"));
            assertTrue(f.sink.files.values().stream().anyMatch(l -> l.toString().contains("\"feasibility\"")), "the decision log line");
        }

        @Test void settingsKeys() {
            BrainConfig c = BrainConfig.defaults();
            assertEquals(1, c.i("brain.feasibility"));
            assertEquals(6, c.i("brain.subgoalMaxSteps"));
            assertNotNull(c.set("brain.subgoalMaxSteps", "13"), "13 is out of range");
        }
    }

    @Test void deferredMapKeyIgnoresCase() { assertEquals(FailMemory.norm("Light  Here 8"), FailMemory.norm("light here 8")); }

    @Test void stockMapIsShortIds() { BrainState s = empty(); s.feas.stock.putAll(Map.of("torch", 1)); assertEquals(FEASIBLE, v("light here 8", s).kind()); }
}
