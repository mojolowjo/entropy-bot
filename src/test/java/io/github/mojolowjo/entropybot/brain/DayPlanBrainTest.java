package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.camp.DayNight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** 0.26.3 the day plan inside the brain loop (DayPlanRuntime + Brain + Needs), on a fake game. */
class DayPlanBrainTest {

    static final class Env implements BrainEnv {
        long now = 1_790_000_000_000L, tick, dayTime = 1000;
        String stage = "diamond";
        int wood = 100, foodStock = 100, torches = 32;
        boolean released, danger, campKnown = true;
        int food = 20;
        String ownerJob;
        final List<BrainState.Goal> goals = new ArrayList<>();
        final List<BrainState.NeedItem> needs = new ArrayList<>();
        boolean inWorld = true;
        Consumer<BrainState> extra = s -> {};

        final List<String> started = new ArrayList<>(), whispers = new ArrayList<>(), stopped = new ArrayList<>(), logs = new ArrayList<>();
        boolean running;
        String lastEnd, step;
        final JsonObject store = new JsonObject();
        JsonObject written;
        final BrainTest.MemSink sink = new BrainTest.MemSink();
        final DecisionLog log = new DecisionLog(sink, ZoneId.of("UTC"));
        final Map<String, String> files = new HashMap<>();
        /** goal form -> {chain, why}; deferred: answers wait in pending. */
        final Map<String, String[]> answers = new HashMap<>();
        boolean planner = true, deferred;
        final List<Runnable> pending = new ArrayList<>();
        final List<String> asked = new ArrayList<>();
        String[] subAnswer;

        @Override public long now() { return now; }
        @Override public long tick() { return tick; }

        @Override public BrainState sense() {
            BrainState s = new BrainState();
            s.now = now;
            s.inWorld = inWorld;
            s.dayTime = dayTime;
            s.night = DayNight.night(dayTime);
            s.spawnNearBase = true;
            s.stage = stage;
            s.foodStock = foodStock;
            s.food = food;
            s.torches = torches;
            s.released = released;
            s.danger = danger;
            s.dangerWhy = danger ? "fighting" : "";
            s.ownerJob = ownerJob;
            s.goals.addAll(goals);
            s.needs.addAll(needs);
            s.campPos = campKnown ? new int[]{0, 64, 0} : null;
            s.feas.known = true;
            if (wood > 0) s.feas.bag.put("oak_log", wood);
            s.logs = wood;
            extra.accept(s);
            return s;
        }

        @Override public String start(String chain) {
            started.add(chain);
            running = true;
            return "started: brain - " + chain;
        }

        @Override public boolean jobRunning() { return running; }
        @Override public String lastEnd() { return lastEnd; }
        @Override public void stopJob(String why) { running = false; stopped.add(why); }
        @Override public void whisper(String text) { whispers.add(text); }
        @Override public void log(String line) { logs.add(line); }
        @Override public JsonObject store() { return store; }
        @Override public void saved() {}
        @Override public void writeState(JsonObject o) { written = o; }
        @Override public DecisionLog decisions() { return log; }
        @Override public String readFile(String name) { return files.get(name); }
        @Override public String writeFile(String name, String json) { files.put(name, json); return "ok"; }
        @Override public String chainStep() { return running ? step : null; }

        @Override public boolean planDay(String goal, Consumer<String[]> then) {
            if (!planner) return false;
            asked.add(goal);
            String[] a = answers.getOrDefault(goal, new String[]{null, "no plan"});
            if (deferred) pending.add(() -> then.accept(a));
            else then.accept(a);
            return true;
        }

        @Override public boolean planSubgoal(String item, int n, int maxSteps, Consumer<String[]> then) {
            if (subAnswer == null) return false;
            then.accept(subAnswer);
            return true;
        }
    }

    Env env;
    Brain brain;

    @BeforeEach void setUp() {
        env = new Env();
        env.answers.put("wood 64", new String[]{"cut 64 logs", null});
        env.answers.put("food 32", new String[]{"gather food 32", null});
        env.answers.put("stone tools", new String[]{"craft stone_pickaxe 1", null});
        brain = new Brain(env);
        assertTrue(brain.command("on").startsWith("ok"));
    }

    void loop() {
        env.now += 2000;
        env.tick += 40;
        brain.tick();
    }

    void loops(int n) { for (int i = 0; i < n; i++) loop(); }

    /** Night, then dawn: the selection runs (the sync planner answers in the same loop). */
    void dawn() {
        env.dayTime = 23000;
        loop();
        env.dayTime = 24100;
        loop();
    }

    void lowWoodAndFood() {
        env.wood = 0;
        env.foodStock = 0;
    }

    DayPlan.State state() { return brain.dayPlan().plan().state(); }

    @Test void dawnSelectsWhispersAndSaves() {
        lowWoodAndFood();
        dawn();
        assertTrue(env.whispers.contains("today: wood 64, food 32"), env.whispers.toString());
        assertTrue(env.files.containsKey(DayPlanRuntime.FILE));
        assertEquals(List.of("wood 64", "food 32"), env.asked);
        assertEquals(DayPlan.State.PENDING, state());
    }

    @Test void dawnOnlyOncePerDay() {
        lowWoodAndFood();
        dawn();
        env.dayTime = 25000;
        loops(3);
        assertEquals(1, env.whispers.stream().filter(w -> w.startsWith("today:")).count());
    }

    @Test void noDawnMidDayOrAtDusk() {
        lowWoodAndFood();
        loops(2);
        env.dayTime = 12600;
        loops(2);
        assertTrue(env.asked.isEmpty());
        assertEquals(DayPlan.State.IDLE, state());
    }

    @Test void noDawnOutOfAWorld() {
        lowWoodAndFood();
        env.dayTime = 23000;
        loop();
        env.inWorld = false;
        env.dayTime = 24100;
        loop();
        env.inWorld = true;
        loop();      // the first look after the join never fires
        assertTrue(env.asked.isEmpty());
    }

    @Test void dayGoalStartsWithScore60() {
        lowWoodAndFood();
        env.foodStock = 100;
        dawn();
        loop();
        assertTrue(env.started.stream().anyMatch(c -> c.contains("cut 64 logs")), env.started.toString());
        assertEquals(60, env.written.getAsJsonObject("scores").get("day_goal").getAsInt());
        assertEquals(DayPlan.State.SERVING, stateAfterLoop());
    }

    DayPlan.State stateAfterLoop() {
        loop();
        return state();
    }

    @Test void dayGoalBeatsUpkeep() {
        lowWoodAndFood();
        dawn();
        loop();
        assertEquals("day_goal", brain.lastDecision().need());
        assertTrue(env.written.getAsJsonObject("scores").get("upkeep").getAsInt() < 60);
    }

    @Test void ownerNeedAt80BeatsDayGoal() {
        lowWoodAndFood();
        dawn();
        env.needs.add(new BrainState.NeedItem("minecraft:torch", 64, 0, 0));
        loop();
        assertEquals("need:torch", brain.lastDecision().need());
        assertTrue(env.started.get(env.started.size() - 1).contains("gather torch 64"));
    }

    @Test void ownerNeedAt50LosesToDayGoal() {
        env.files.put(Brain.CONFIG_FILE, "{\"ownerNeedSpan\":0}");
        lowWoodAndFood();
        dawn();
        env.needs.add(new BrainState.NeedItem("minecraft:torch", 64, 0, 0));
        loop();
        assertEquals("day_goal", brain.lastDecision().need());
    }

    @Test void ownerGoalPausesAndQueueKept() {
        lowWoodAndFood();
        dawn();
        loop();
        env.goals.add(new BrainState.Goal("furnace", "craft furnace 1", env.now));
        loop();
        assertEquals(DayPlan.State.PAUSED_OWNER, state());
        assertFalse(env.written.getAsJsonObject("scores").has("day_goal"));
        assertEquals(List.of("wood 64", "food 32"), brain.dayPlan().plan().queueTexts());
        assertTrue(env.stopped.contains("the day plan moved on"));
    }

    @Test void ownerGoalEndsResumes() {
        lowWoodAndFood();
        dawn();
        env.goals.add(new BrainState.Goal("furnace", "craft furnace 1", env.now));
        loop();
        env.goals.clear();
        env.running = false;
        loop();
        loop();
        assertEquals("day_goal", brain.lastDecision().need());
    }

    @Test void ownerGoalRoutineRunningPauses() {
        lowWoodAndFood();
        dawn();
        env.ownerJob = "goal_bed";
        loop();
        assertEquals(DayPlan.State.PAUSED_OWNER, state());
    }

    @Test void dangerInterruptsAndStallWaits() {
        lowWoodAndFood();
        dawn();
        env.danger = true;
        loops(5);
        assertEquals(DayPlan.State.INTERRUPTED, state());
        assertEquals(0, brain.dayPlan().plan().active().noProgressMs);
        env.danger = false;
        loop();
        assertEquals("day_goal", brain.lastDecision().need());
    }

    @Test void hungerInterruptResumesDayGoal() {
        lowWoodAndFood();
        dawn();
        loop();
        env.food = 6;
        env.extra = s -> s.foodItems = 16;
        loop();
        String last = env.started.get(env.started.size() - 1);
        assertTrue(last.startsWith("eat then") && last.contains("cut 64 logs"), last);
        loop();
        assertTrue(env.running, "the eat-then-goal chain keeps running (the day goal is in it)");
    }

    @Test void nightPausesAndStopsTheGoal() {
        lowWoodAndFood();
        dawn();
        loop();
        env.dayTime = 37000;      // night of day 1
        loop();
        assertEquals(DayPlan.State.PAUSED_NIGHT, state());
        assertFalse(env.written.getAsJsonObject("scores").has("day_goal"));
        assertTrue(env.stopped.contains("the day plan moved on"));
    }

    @Test void dawnAfterNightCarriesOver() {
        lowWoodAndFood();
        dawn();
        env.dayTime = 37000;
        loop();
        env.dayTime = 48100;      // dawn of day 2
        loop();
        assertEquals(2, env.whispers.stream().filter(w -> w.equals("today: wood 64, food 32")).count());
        assertTrue(brain.dayPlan().plan().goals().get(0).carried);
    }

    @Test void releasedDoesNotStopThePlan() {
        lowWoodAndFood();
        dawn();
        env.released = true;
        loops(2);
        assertEquals("day_goal", brain.lastDecision().need());
    }

    @Test void doneStartsThePlanAtOnce() {
        lowWoodAndFood();
        env.dayTime = 3000;
        loops(2);
        assertTrue(env.asked.isEmpty());
        env.released = true;
        loop();
        assertTrue(env.whispers.contains("today: wood 64, food 32"), env.whispers.toString());
    }

    @Test void chainFinishedAdvancesTheQueue() {
        lowWoodAndFood();
        dawn();
        loop();
        env.running = false;
        env.lastEnd = "done: cut 64 logs";
        loop();
        loop();
        assertEquals(DayPlan.GoalState.DONE, brain.dayPlan().plan().goals().get(0).state);
        assertTrue(env.started.get(env.started.size() - 1).contains("gather food 32"), env.started.toString());
    }

    @Test void stallGivesUpWithWhisper() {
        lowWoodAndFood();
        dawn();
        loop();
        env.step = "1/1 cut 64 logs";
        loops(20 * 30 + 2);
        assertTrue(env.whispers.contains("gave up wood 64: no progress for 20 min"), env.whispers.toString());
        assertEquals("food 32", brain.dayPlan().plan().active().text);
    }

    @Test void queueEmptyWhisper() {
        lowWoodAndFood();
        env.foodStock = 100;
        dawn();
        loop();
        assertTrue(brain.dayplan("skip").startsWith("ok: skipped wood 64"));
        assertTrue(env.whispers.contains(DayPlan.QUEUE_EMPTY));
    }

    @Test void skipStopsTheJob() {
        lowWoodAndFood();
        dawn();
        loop();
        String r = brain.dayplan("skip");
        assertEquals("ok: skipped wood 64 - next: food 32", r);
        assertTrue(env.stopped.contains("dayplan skip"));
        assertTrue(env.whispers.contains("gave up wood 64: skipped by you"));
    }

    @Test void clearEmptiesThePlan() {
        lowWoodAndFood();
        dawn();
        assertTrue(brain.dayplan("clear hold").startsWith("ok: the day plan is cleared"));      // 0.27.1: hold = no new plan until dawn
        assertTrue(brain.dayPlan().plan().goals().isEmpty());
        loop();
        assertFalse(env.written.getAsJsonObject("scores").has("day_goal"));
    }

    @Test void nowByDayAndNotAtNight() {
        lowWoodAndFood();
        loop();
        assertTrue(brain.dayplan("now").startsWith("ok: planning the day now"));
        loop();
        assertTrue(env.whispers.contains("today: wood 64, food 32"));
        env.dayTime = 15000;
        loop();
        assertTrue(brain.dayplan("now").startsWith("error: dayplan now works by day"));
    }

    @Test void showAndUsage() {
        assertTrue(brain.dayplan("").startsWith("day plan: none today"));
        assertTrue(brain.dayplan("bogus").startsWith("usage: dayplan"));
    }

    @Test void restartKeepsThePlan() {
        lowWoodAndFood();
        dawn();
        brain = new Brain(env);      // a restart: the plan comes back from dayplan.json
        loop();
        loop();
        assertEquals("day_goal", brain.lastDecision().need());
        assertEquals(List.of("wood 64", "food 32"), brain.dayPlan().plan().queueTexts());
        assertEquals(1, env.whispers.stream().filter(w -> w.startsWith("today:")).count());
    }

    @Test void brokenFileIsACheckFinding() {
        env.files.put(DayPlanRuntime.FILE, "{oops");
        loop();
        assertTrue(brain.findings().stream().anyMatch(f -> f[0].equals("dayplan")), "check names dayplan.json");
        assertEquals(DayPlan.State.IDLE, state());
    }

    @Test void brainJsonCarriesTheCard() {
        lowWoodAndFood();
        dawn();
        loop();
        JsonObject dp = env.written.getAsJsonObject("dayPlan");
        assertEquals("wood 64", dp.get("active").getAsString());
        assertEquals(2, dp.getAsJsonArray("goals").size());
        assertTrue(dp.get("madeAt").getAsLong() > 0);
    }

    @Test void decisionLogLines() {
        lowWoodAndFood();
        dawn();
        loop();
        List<JsonObject> lines = env.sink.lines();
        assertTrue(lines.stream().anyMatch(o -> o.has("branch") && o.get("branch").getAsString().equals("dayplan") && o.get("to").getAsString().equals("SELECTING")));
        assertTrue(lines.stream().anyMatch(o -> o.has("branch") && o.get("branch").getAsString().equals("pick") && o.has("dayPlan")));
    }

    @Test void whyAndStatusLines() {
        lowWoodAndFood();
        dawn();
        loop();
        env.step = "1/3 cut 64 logs";
        loop();
        assertTrue(brain.why().contains("day plan: wood 64 (1/2), step 1/3 cut 64 logs"), brain.why());
        assertTrue(brain.status().contains("day plan: wood 64 (1/2)"));
    }

    @Test void stuckSelectingFallsToIdle() {
        lowWoodAndFood();
        env.deferred = true;
        dawn();
        assertEquals(DayPlan.State.SELECTING, state());
        assertTrue(brain.why().contains("planning the day"));
        loops(31);
        assertEquals(DayPlan.State.IDLE, state());
        assertTrue(env.whispers.contains("today: nothing to plan - idle list"));
        assertTrue(env.logs.stream().anyMatch(l -> l.contains("did not answer within 60 s")));
    }

    @Test void deferredAnswersArriveAcrossLoops() {
        lowWoodAndFood();
        env.deferred = true;
        dawn();
        loop();
        assertEquals(DayPlan.State.SELECTING, state());
        env.pending.forEach(Runnable::run);
        loop();
        assertTrue(env.whispers.contains("today: wood 64, food 32"));
    }

    @Test void noPlannerDropsAll() {
        lowWoodAndFood();
        env.planner = false;
        dawn();
        assertTrue(env.whispers.contains("today: nothing to plan - idle list"));
    }

    @Test void noSourceAbandons() {
        lowWoodAndFood();
        env.torches = 0;
        env.answers.put("wood 64", new String[]{"light here 8", null});
        env.subAnswer = new String[]{null, "missing coal"};
        dawn();
        loop();
        // 0.27.1: re-planned once first; the same chain again gives it up with what was missing
        assertTrue(env.whispers.stream().anyMatch(w -> w.startsWith("gave up wood 64:") && w.endsWith("no source for torch")), env.whispers.toString());
        assertEquals(2, env.asked.stream().filter("wood 64"::equals).count());
        assertEquals("food 32", brain.dayPlan().plan().active().text);
    }

    @Test void settingsRanges() {
        BrainConfig c = BrainConfig.defaults();
        assertEquals(3, c.i("dayPlan.maxGoals"));
        assertEquals(20, c.i("dayPlan.stallMinutes"));
        assertEquals(60, c.i("dayPlan.score"));
        assertNotNull(c.set("dayPlan.maxGoals", "0"));
        assertNotNull(c.set("dayPlan.maxGoals", "6"));
        assertNull(c.set("dayPlan.maxGoals", "5"));
        assertNotNull(c.set("dayPlan.stallMinutes", "4"));
        assertNotNull(c.set("dayPlan.stallMinutes", "121"));
        assertNull(c.set("dayPlan.stallMinutes", "120"));
        assertNotNull(c.set("dayPlan.score", "19"));
        assertNull(c.set("dayPlan.score", "100"));
        assertTrue(brain.command("set dayplan.maxgoals 1").startsWith("ok: dayPlan.maxGoals = 1"));
    }

    @Test void maxGoalsOneFromSettings() {
        assertTrue(brain.command("set dayPlan.maxGoals 1").startsWith("ok"));
        lowWoodAndFood();
        dawn();
        assertTrue(env.whispers.contains("today: wood 64"), env.whispers.toString());
    }

    @Test void brainOffNoSelection() {
        brain.command("off");
        lowWoodAndFood();
        dawn();
        assertTrue(env.asked.isEmpty());
    }
}
