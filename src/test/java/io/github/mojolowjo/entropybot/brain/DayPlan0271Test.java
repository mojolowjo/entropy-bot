package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.27.1: dayplan clear by day plans afresh, the re-plan-once rule, brain reset parks. */
class DayPlan0271Test {

    // ---- pure ----

    static DayPlan active(String chain) {
        DayPlan p = new DayPlan();
        DayPlan.Facts f = DayPlanTest.facts("nothing");
        p.beginSelection(f, 0, "d1");
        for (String t : p.toPlan()) p.dryResult(t, t.equals("stone tools") ? chain : null, "no plan");
        p.finishSelection(3, 1000, "d1");
        assertEquals("stone tools", p.active().text);
        return p;
    }

    @Test void clearReplansByDayOnly() {
        assertTrue(DayPlan.replanAfterClear(false, false));
        assertFalse(DayPlan.replanAfterClear(true, false));
        assertFalse(DayPlan.replanAfterClear(false, true));
        assertFalse(DayPlan.replanAfterClear(true, true));
    }

    @Test void aMissingItemStartsOneReplan() {
        DayPlan p = active("craft stone_pickaxe 1");
        DayPlan.Result r = p.chainFailed("craft stone_pickaxe 1: 0 cobblestone, I need 3", true);
        assertTrue(p.replanning());
        assertTrue(r.whispers().isEmpty());
        assertTrue(r.log().get(0).contains("replanning stone tools"));
        assertTrue(p.whyLine().endsWith("replanning"), p.whyLine());
        assertTrue(p.brainJson().get("replanning").getAsBoolean());
    }

    @Test void aNewChainServesTheGoal() {
        DayPlan p = active("craft stone_pickaxe 1");
        p.chainFailed("0 cobblestone", true);
        DayPlan.Result r = p.replanned("cut 8 logs then craft wooden_pickaxe 1 then craft stone_pickaxe 1", null);
        assertFalse(p.replanning());
        assertEquals("cut 8 logs then craft wooden_pickaxe 1 then craft stone_pickaxe 1", p.active().chain);
        assertEquals(DayPlan.State.PENDING, p.state());
        assertTrue(r.whispers().isEmpty());
    }

    @Test void theSameChainAgainGivesUp() {
        DayPlan p = active("craft stone_pickaxe 1");
        p.chainFailed("0 cobblestone, I need 3", true);
        DayPlan.Result r = p.replanned(" Craft  stone_pickaxe 1 ", null);
        assertTrue(r.whispers().contains("gave up stone tools: 0 cobblestone, I need 3"), r.whispers().toString());
        assertEquals(DayPlan.GoalState.FAILED, p.goals().get(0).state);
    }

    @Test void noPlanGivesUpWithBothReasons() {
        DayPlan p = active("craft stone_pickaxe 1");
        p.chainFailed("0 cobblestone", true);
        DayPlan.Result r = p.replanned(null, "missing 3 cobblestone");
        assertEquals("gave up stone tools: 0 cobblestone; no plan: missing 3 cobblestone", r.whispers().get(0));
    }

    @Test void alreadyHaveItIsDone() {
        DayPlan p = active("craft stone_pickaxe 1");
        p.chainFailed("0 cobblestone", true);
        p.replanned("", "I already have it");
        assertEquals(DayPlan.GoalState.DONE, p.goals().get(0).state);
    }

    @Test void theSecondFailureGivesUp() {
        DayPlan p = active("craft stone_pickaxe 1");
        p.chainFailed("0 cobblestone", true);
        p.replanned("cut 8 logs then craft stone_pickaxe 1", null);
        DayPlan.Result r = p.chainFailed("no path", true);
        assertFalse(p.replanning());
        assertTrue(r.whispers().contains("gave up stone tools: no path"));
    }

    @Test void anotherClassGivesUpAtOnce() {
        DayPlan p = active("craft stone_pickaxe 1");
        DayPlan.Result r = p.chainFailed("couldn't get there", false);
        assertFalse(p.replanning());
        assertEquals("gave up stone tools: couldn't get there", r.whispers().get(0));
    }

    @Test void updateWaitsWhileReplanning() {
        DayPlan p = active("craft stone_pickaxe 1");
        p.chainFailed("0 cobblestone", true);
        DayPlan.State before = p.state();
        DayPlan.Result r = p.update(new DayPlan.Tick(0, 3_600_000, false, null, false, false, -1, null, null, DayPlanTest.facts("nothing")), 1);
        assertEquals(before, r.to());
        assertEquals(DayPlan.GoalState.ACTIVE, p.goals().get(0).state);      // the stall clock did not abandon it
    }

    @Test void aLateAnswerWithoutReplanIsIgnored() {
        DayPlan p = active("craft stone_pickaxe 1");
        DayPlan.Result r = p.replanned("other chain", null);
        assertEquals("craft stone_pickaxe 1", p.active().chain);
        assertTrue(r.log().isEmpty());
    }

    @Test void replannedFlagRoundTrips() {
        DayPlan p = active("craft stone_pickaxe 1");
        p.chainFailed("0 cobblestone", true);
        p.replanned("cut 8 logs then craft stone_pickaxe 1", null);
        DayPlan q = DayPlan.fromJson(p.toJson().toString());
        assertTrue(q.active().replanned);
    }

    @Test void clearEndsAReplan() {
        DayPlan p = active("craft stone_pickaxe 1");
        p.chainFailed("0 cobblestone", true);
        p.clear();
        assertFalse(p.replanning());
    }

    @Test void sameChainIgnoresCaseAndSpaces() {
        assertTrue(DayPlan.sameChain("cut 8 logs  then craft x 1", "Cut 8 logs then craft x 1"));
        assertFalse(DayPlan.sameChain("cut 8 logs", "cut 9 logs"));
        assertFalse(DayPlan.sameChain(null, "a"));
    }

    // ---- in the brain loop ----

    DayPlanBrainTest.Env env;
    Brain brain;

    @BeforeEach void setUp() {
        env = new DayPlanBrainTest.Env();
        env.answers.put("wood 64", new String[]{"cut 64 logs", null});
        env.answers.put("food 32", new String[]{"gather food 32", null});
        brain = new Brain(env);
        assertTrue(brain.command("on").startsWith("ok"));
        env.wood = 0;
        env.foodStock = 0;
    }

    void loop() {
        env.now += 2000;
        env.tick += 40;
        brain.tick();
    }

    void dawn() {
        env.dayTime = 23000;
        loop();
        env.dayTime = 24100;
        loop();
    }

    @Test void clearByDayPlansAgainOnTheNextLoop() {
        dawn();
        int asked = env.asked.size();
        String r = brain.dayplan("clear");
        assertTrue(r.startsWith("ok: the day plan is cleared - planning a new one"), r);
        loop();
        assertTrue(env.asked.size() > asked, env.asked.toString());
        assertNotEquals(DayPlan.State.IDLE, brain.dayPlan().plan().state());
        assertFalse(brain.dayPlan().plan().goals().isEmpty());
    }

    @Test void clearHoldWaitsForDawn() {
        dawn();
        int asked = env.asked.size();
        assertTrue(brain.dayplan("clear hold").contains("until the next dawn"));
        loop();
        loop();
        assertEquals(asked, env.asked.size());
        assertEquals(DayPlan.State.IDLE, brain.dayPlan().plan().state());
    }

    @Test void clearAtNightWaitsForDawn() {
        dawn();
        env.dayTime = 37000;      // night of the next day
        loop();
        int asked = env.asked.size();
        assertTrue(brain.dayplan("clear").contains("until the next dawn"));
        loop();
        assertEquals(asked, env.asked.size());
    }

    @Test void aFailedDayGoalChainIsReplannedOnce() {
        dawn();
        loop();
        assertTrue(env.started.stream().anyMatch(c -> c.contains("cut 64 logs")), env.started.toString());
        env.answers.put("wood 64", new String[]{"get oak_log 64", null});
        env.running = false;
        env.lastEnd = "stopped at step 1 (cut 64 logs): not enough axes - i need 1";
        loop();
        long woodAsks = env.asked.stream().filter("wood 64"::equals).count();
        assertEquals(2, woodAsks, env.asked.toString());
        assertEquals("get oak_log 64", brain.dayPlan().plan().active().chain);
        assertFalse(env.whispers.stream().anyMatch(w -> w.startsWith("gave up wood")), env.whispers.toString());
    }

    @Test void aReplanToTheSameChainGivesUp() {
        dawn();
        loop();
        env.running = false;
        env.lastEnd = "stopped at step 1 (cut 64 logs): not enough axes - i need 1";
        loop();
        assertTrue(env.whispers.stream().anyMatch(w -> w.startsWith("gave up wood 64:")), env.whispers.toString());
    }

    @Test void resetParksClearsParksCountsAndMemory() {
        JsonObject b = new JsonObject();
        JsonObject parked = new JsonObject();
        JsonObject p = new JsonObject();
        p.addProperty("until", env.now + 600_000);
        p.addProperty("why", "x");
        parked.add("food", p);
        b.add("parked", parked);
        JsonObject fails = new JsonObject();
        fails.addProperty("tools", 2);
        b.add("fails", fails);
        env.store.add("brain", b);
        String r = brain.command("reset parks");
        assertTrue(r.startsWith("ok: cleared 1 parked need (food), 1 failure count"), r);
        assertTrue(r.contains("the learned table and the settings stay"));
        assertFalse(env.store.getAsJsonObject("brain").has("parked"));
        assertFalse(env.store.getAsJsonObject("brain").has("fails"));
    }

    @Test void resetAllStillMeansSettings() {
        assertTrue(brain.command("set dayPlan.maxGoals 1").startsWith("ok"));
        assertTrue(brain.command("reset all").startsWith("ok: every brain setting is back to its default"));
    }

    @Test void missingOfDropsTheStepHead() {
        assertEquals("cut 64 logs: not enough axes", Brain.missingOf("stopped at step 1 (cut 64 logs): not enough axes"));
        assertEquals("its chain failed", Brain.missingOf(null));
        assertTrue(Brain.dayReplan(FailMemory.Reason.NO_ITEM));
        assertTrue(Brain.dayReplan(FailMemory.Reason.NONE_IN_CHESTS));
        assertFalse(Brain.dayReplan(FailMemory.Reason.NO_PATH));
        assertFalse(Brain.dayReplan(null));
    }
}
