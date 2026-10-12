package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.26.3 the day plan (docs/DAYPLAN_PLAN.md, roadmap step 3): at dawn the brain picks a few goals itself from the stage
 * and the stock (the candidate table), each one checked by a planner dry run, and serves the active one as the need
 * {@code day_goal}. This class is the pure part: the candidate table, the selection (carry-over first, no duplicates,
 * at most {@code dayPlan.maxGoals}), the state machine, the progress/stall rule and dayplan.json's round trip.
 * {@link DayPlanRuntime} drives it from the brain loop.
 *
 * <p>State machine ({@link State}): IDLE -&gt; SELECTING (dawn, {@code dayplan now}, the owner's {@code done}) -&gt;
 * PENDING (a goal is active, its job not started) -&gt; SERVING (its job runs) &lt;-&gt; INTERRUPTED (another job or
 * danger runs); SERVING/PENDING -&gt; PENDING (done or abandoned: the next goal) or IDLE (the queue is empty);
 * any of them -&gt; PAUSED_OWNER (an owner {@code goal}) -&gt; PENDING when it ends; -&gt; PAUSED_NIGHT (night) -&gt;
 * SELECTING at the next dawn (carry-over). Loader notes: none (pure Java and Gson).
 */
public final class DayPlan {

    public enum State { IDLE, SELECTING, PENDING, SERVING, INTERRUPTED, PAUSED_OWNER, PAUSED_NIGHT }

    public enum GoalState { PENDING, ACTIVE, DONE, FAILED, PAUSED }

    /** A goal in the plan: its form ("iron tools", "wood 64"), state, chain (the planner's), why it ended, progress. */
    public static final class Goal {
        public final String text;
        public GoalState state = GoalState.PENDING;
        public String chain;
        public String why = "";
        public boolean carried;
        public int priority;
        /** Time served without progress (ms); the last step number, counted item and best distance to a step target seen. */
        public long noProgressMs;
        public int lastStep = -1, lastCount = -1;
        public double bestDist = -1;
        /** 0.27.1: re-planned once after a missing item (a second failure gives it up). */
        public boolean replanned;

        public Goal(String text) { this.text = norm(text); }

        boolean open() { return state == GoalState.PENDING || state == GoalState.ACTIVE || state == GoalState.PAUSED; }
    }

    /** One row of the candidate table that applies: priority, goal form, the condition in words. */
    public record Candidate(int priority, String text, String why) {}

    /**
     * What the table reads: the stage, the group's stock (bag + chests + RS, short ids), edible items, the base place, a
     * furnace/table/bed near, a camp place, a main area with dark cells (its name, else null). known: the game side filled
     * it (until then no condition is judged).
     */
    public static final class Facts {
        public String stage = "nothing";
        public final Map<String, Integer> stock = new HashMap<>();
        public int food;
        public boolean baseKnown, furnaceNear, tableNear, bedNear, campKnown, known = true;
        public String darkMainArea;

        public int count(String id) { return stock.getOrDefault(id, 0); }

        /** The planner's kinds: logs = every *_log, *_stem, *_wood; planks = every *_planks. */
        public int wood() {
            int n = 0;
            for (Map.Entry<String, Integer> e : stock.entrySet()) {
                String k = e.getKey();
                if (k.endsWith("_log") || k.endsWith("_stem") || k.endsWith("_wood") || k.endsWith("_planks") || k.endsWith("_hyphae")) n += Math.max(0, e.getValue());
            }
            return n;
        }

        /** An iron pickaxe or better in stock. */
        public boolean ironPick() {
            return count("iron_pickaxe") + count("diamond_pickaxe") + count("netherite_pickaxe") > 0;
        }
    }

    public static final int IRON_WANT = 32, WOOD_WANT = 64, FOOD_WANT = 32;

    /** The candidate table (DAYPLAN_PLAN "Selection"), highest priority first. Pure. */
    public static List<Candidate> candidates(Facts f) {
        List<Candidate> out = new ArrayList<>();
        if (f == null || !f.known) return out;
        String st = f.stage == null ? "nothing" : f.stage;
        if (st.equals("nothing")) out.add(new Candidate(100, "stone tools", "stage nothing"));
        if (st.equals("stone") && !f.ironPick()) out.add(new Candidate(90, "iron tools", "stage stone and no iron pickaxe in stock"));
        if (st.equals("iron") && f.count("iron_ingot") < IRON_WANT) out.add(new Candidate(85, "iron " + IRON_WANT, "stage iron and " + f.count("iron_ingot") + " iron_ingot in stock"));
        if (f.baseKnown && !f.furnaceNear) out.add(new Candidate(80, "furnace", "base known, no furnace near"));
        if (f.baseKnown && !f.tableNear) out.add(new Candidate(75, "table", "base known, no table near"));
        if (f.baseKnown && !f.bedNear) out.add(new Candidate(70, "bed", "base known, no bed near"));
        if (f.wood() < WOOD_WANT) out.add(new Candidate(60, "wood " + WOOD_WANT, f.wood() + " logs and planks in stock"));
        if (f.food < FOOD_WANT) out.add(new Candidate(50, "food " + FOOD_WANT, f.food + " food in stock"));
        if (f.darkMainArea != null) out.add(new Candidate(40, "light " + f.darkMainArea, "main area " + f.darkMainArea + " has dark cells"));
        if (!st.equals("nothing") && !st.equals("wood") && !f.campKnown) out.add(new Candidate(20, "camp", "stage " + st + " and no camp place"));
        return out;
    }

    static final Pattern COUNTED = Pattern.compile("^(iron|wood|food) (\\d+)$");

    /** The goal's condition still holds (it is still wanted). Unknown forms: true (only the chain ends them). */
    public static boolean holds(String text, Facts f) {
        if (f == null || !f.known) return true;
        String t = norm(text);
        Matcher m = COUNTED.matcher(t);
        if (m.find()) {
            int n = Integer.parseInt(m.group(2));
            return counted(t, f) < n;
        }
        String st = f.stage == null ? "nothing" : f.stage;
        return switch (t) {
            case "stone tools" -> st.equals("nothing") || st.equals("wood");
            case "iron tools" -> !f.ironPick();
            case "furnace" -> !f.furnaceNear;
            case "table" -> !f.tableNear;
            case "bed" -> !f.bedNear;
            case "camp" -> !f.campKnown;
            default -> t.startsWith("light ") ? t.substring(6).equals(f.darkMainArea) : true;
        };
    }

    /** The goal's counted item now (iron_ingot, logs + planks, food), -1 for a goal without one. */
    public static int counted(String text, Facts f) {
        if (f == null || !f.known) return -1;
        Matcher m = COUNTED.matcher(norm(text));
        if (!m.find()) return -1;
        return switch (m.group(1)) { case "iron" -> f.count("iron_ingot"); case "wood" -> f.wood(); default -> f.food; };
    }

    /** "Same goal" = same form and item regardless of count: "wood 64" and "wood 32" are one goal. */
    public static String key(String text) {
        String t = norm(text);
        Matcher m = COUNTED.matcher(t);
        if (m.find()) return m.group(1);
        return t.replaceFirst(" \\d+$", "");
    }

    static int countOf(String text) {
        Matcher m = Pattern.compile(" (\\d+)$").matcher(norm(text));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static String norm(String t) { return t == null ? "" : t.trim().toLowerCase(Locale.ROOT).replaceFirst("^goal\\s+", "").replaceAll("\\s+", " "); }

    /** Dawn: the day key ({@code DayNight.key}) changed to a day ("d...") from another one. Never on the first look. */
    public static boolean dawn(String lastKey, String key) {
        return lastKey != null && key != null && key.startsWith("d") && !key.equals(lastKey);
    }

    /** A main area "has dark cells": at least 4 surface cells sampled and a tenth or more of them at block light 0. */
    public static boolean darkEnough(int dark, int sampled) { return sampled >= 4 && dark * 10 >= sampled && dark > 0; }

    /** The stall rule: this much time served without progress is the stall limit or more. */
    public static boolean stalled(long noProgressMs, int stallMinutes) { return noProgressMs >= stallMinutes * 60_000L; }

    static final Pattern XYZ = Pattern.compile("(-?\\d+) (-?\\d+) (-?\\d+)");

    /** A step's target (its first "x y z"), else null. */
    public static int[] target(String step) {
        if (step == null) return null;
        Matcher m = XYZ.matcher(step);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))} : null;
    }

    // ---- the plan ----

    State state = State.IDLE;
    final List<Goal> goals = new ArrayList<>();
    String id = "", dayKey;
    long madeAt;
    /** Selection: the forms in order and their dry-run answers ({chain, why}; chain "" = already have it). */
    final LinkedHashMap<String, Candidate> selecting = new LinkedHashMap<>();
    final Map<String, String[]> dry = new HashMap<>();
    long selectStarted;
    /** The step text the runtime saw last (why / the card). */
    String step;

    public State state() { return state; }

    public List<Goal> goals() { return goals; }

    public String dayKey() { return dayKey; }

    /** The open goals in order (the queue). */
    public List<Goal> queue() {
        List<Goal> q = new ArrayList<>();
        for (Goal g : goals) if (g.open()) q.add(g);
        return q;
    }

    /** The goal being served (ACTIVE or PAUSED), else null. */
    public Goal active() {
        for (Goal g : goals) if (g.state == GoalState.ACTIVE || g.state == GoalState.PAUSED) return g;
        return null;
    }

    public List<String> queueTexts() {
        List<String> t = new ArrayList<>();
        for (Goal g : queue()) t.add(g.text);
        return t;
    }

    /**
     * Starts a selection: the carried goals first (unfinished ones whose condition still holds; the rest dropped as
     * done, named in the returned log lines), then the candidates whose key is not carried. Each needs a dry run;
     * {@link #toPlan()} lists them. State SELECTING.
     */
    public List<String> beginSelection(Facts f, long now, String key) {
        List<String> log = new ArrayList<>();
        selecting.clear();
        dry.clear();
        selectStarted = now;
        dayKey = key;
        Map<String, Integer> carriedPrio = new LinkedHashMap<>();
        for (Goal g : goals) {
            if (!g.open()) continue;
            if (!holds(g.text, f)) {
                log.add("day plan: carried goal " + g.text + " dropped as done (its condition no longer holds)");
                continue;
            }
            String k = key(g.text);
            if (carriedPrio.containsKey(k)) continue;
            carriedPrio.put(k, 1);
            selecting.put(g.text, new Candidate(g.priority, g.text, "carried over"));
        }
        for (Candidate c : candidates(f)) {
            String k = key(c.text());
            String same = null;
            for (String s : selecting.keySet()) if (key(s).equals(k)) same = s;
            if (same != null) {
                // the same goal: keep the larger count
                if (countOf(c.text()) > countOf(same)) {
                    Candidate was = selecting.remove(same);
                    LinkedHashMap<String, Candidate> re = new LinkedHashMap<>();
                    for (Map.Entry<String, Candidate> e : selecting.entrySet()) re.put(e.getKey(), e.getValue());
                    selecting.clear();
                    // keep the carried place at the front
                    selecting.put(c.text(), new Candidate(was.priority(), c.text(), was.why()));
                    selecting.putAll(re);
                }
                continue;
            }
            selecting.put(c.text(), c);
        }
        state = State.SELECTING;
        return log;
    }

    /** The forms the selection waits for (no answer yet). */
    public List<String> toPlan() {
        List<String> out = new ArrayList<>();
        for (String t : selecting.keySet()) if (!dry.containsKey(t)) out.add(t);
        return out;
    }

    /** A dry run's answer: chain (null: no plan; "" = I already have it) and why. */
    public void dryResult(String text, String chain, String why) { dry.put(norm(text), new String[]{chain, why}); }

    /** Every dry run answered. */
    public boolean selectionReady() { return state == State.SELECTING && toPlan().isEmpty(); }

    /** The selection's result: the whisper line, the log lines (drops). */
    public record Selected(String line, List<String> log) {}

    /**
     * Ends the selection (all answered, or the wait ran out: those without an answer are dropped): plannable goals in
     * order until max; "already have it" answers are dropped as done. State PENDING with the first goal ACTIVE, or IDLE.
     */
    public Selected finishSelection(int max, long now, String newId) {
        List<String> log = new ArrayList<>();
        List<Goal> kept = new ArrayList<>();
        for (Map.Entry<String, Candidate> e : selecting.entrySet()) {
            String t = e.getKey();
            String[] r = dry.get(t);
            if (r == null) { log.add("day plan candidate " + t + " dropped: the planner did not answer in time"); continue; }
            if (r[0] == null) { log.add("day plan candidate " + t + " dropped: unplannable (" + (r[1] == null ? "no plan" : r[1]) + ")"); continue; }
            if (r[0].isBlank()) { log.add("day plan candidate " + t + " dropped: nothing to do (" + (r[1] == null ? "I have it" : r[1]) + ")"); continue; }
            if (kept.size() >= max) { log.add("day plan candidate " + t + " left out: " + max + " goals a day (dayPlan.maxGoals)"); continue; }
            Goal g = new Goal(t);
            g.chain = r[0];
            g.priority = e.getValue().priority();
            g.carried = "carried over".equals(e.getValue().why());
            kept.add(g);
        }
        goals.clear();
        goals.addAll(kept);
        selecting.clear();
        dry.clear();
        madeAt = now;
        id = newId;
        if (kept.isEmpty()) {
            state = State.IDLE;
            return new Selected("today: nothing to plan - idle list", log);
        }
        kept.get(0).state = GoalState.ACTIVE;
        state = State.PENDING;
        return new Selected("today: " + String.join(", ", queueTexts()), log);
    }

    /** One loop's view of the world for {@link #update}. */
    public record Tick(long now, long dt, boolean night, String ownerGoal, boolean dayJobRunning, boolean otherBusy,
                       int step, String stepText, int[] pos, Facts facts) {}

    /** What an update did: whispers for the owner, log lines, the state before and after. */
    public record Result(List<String> whispers, List<String> log, State from, State to) {}

    /**
     * One brain loop. Owner goal: pause (an owner goal already queued becomes the active goal). Night: pause until the
     * next dawn. Else: done when the active goal's condition no longer holds; SERVING / INTERRUPTED / PENDING from what
     * runs; progress (a step finished, the counted item rose, 32+ blocks nearer the step's target) resets the stall
     * clock, which counts only while SERVING or PENDING; the stall limit abandons the goal.
     */
    public Result update(Tick t, int stallMinutes) {
        List<String> w = new ArrayList<>(), log = new ArrayList<>();
        State from = state;
        if (state == State.IDLE || state == State.SELECTING || replanning) return new Result(w, log, from, state);
        if (t.ownerGoal() != null) {
            if (state != State.PAUSED_OWNER) {
                Goal match = null;
                for (Goal g : queue()) if (key(g.text).equals(key(t.ownerGoal()))) match = g;
                if (match != null && match != active()) {
                    Goal a = active();
                    if (a != null) a.state = GoalState.PENDING;
                    goals.remove(match);
                    goals.add(goals.indexOf(firstOpen()) < 0 ? goals.size() : goals.indexOf(firstOpen()), match);
                    log.add("day plan: your goal " + t.ownerGoal() + " is in today's plan - it is the active goal now");
                }
                if (match != null) match.state = GoalState.PAUSED;
                else if (active() != null) active().state = GoalState.PAUSED;
                state = State.PAUSED_OWNER;
                log.add("day plan paused: your goal " + t.ownerGoal() + " runs");
            }
            return new Result(w, log, from, state);
        }
        if (state == State.PAUSED_OWNER) {
            state = State.PENDING;
            if (active() != null) active().state = GoalState.ACTIVE;
            log.add("day plan resumes: your goal ended");
        }
        if (t.night()) {
            if (state != State.PAUSED_NIGHT) {
                state = State.PAUSED_NIGHT;
                if (active() != null) active().state = GoalState.PAUSED;
                log.add("day plan paused for the night");
            }
            return new Result(w, log, from, state);
        }
        if (state == State.PAUSED_NIGHT) {          // day again without a new dawn (the plan was made today)
            state = State.PENDING;
            if (active() != null) active().state = GoalState.ACTIVE;
        }
        Goal a = active();
        if (a == null) {
            if (!advance(w, log)) return new Result(w, log, from, state);
            a = active();
        }
        if (t.facts() != null && t.facts().known && !holds(a.text, t.facts())) {
            done(a, "its need is met", w, log);
            return new Result(w, log, from, state);
        }
        state = t.dayJobRunning() ? State.SERVING : t.otherBusy() ? State.INTERRUPTED : State.PENDING;
        if (state != State.INTERRUPTED) {
            boolean progress = false;
            if (t.dayJobRunning() && t.step() >= 0) {
                if (a.lastStep >= 0 && t.step() > a.lastStep) progress = true;      // a chain step finished
                a.lastStep = t.step();                                              // (a new run starts lower: no progress)
            }
            int cnt = counted(a.text, t.facts());
            if (cnt >= 0) {
                if (a.lastCount >= 0 && cnt > a.lastCount) progress = true;
                a.lastCount = cnt;
            }
            int[] tg = target(t.stepText());
            if (tg != null && t.pos() != null) {
                double d = Math.sqrt(Math.pow(tg[0] - t.pos()[0], 2) + Math.pow(tg[2] - t.pos()[2], 2));
                if (a.bestDist < 0) a.bestDist = d;
                else if (a.bestDist - d >= 32) { a.bestDist = d; progress = true; }
            }
            if (progress) a.noProgressMs = 0;
            else a.noProgressMs += Math.max(0, t.dt());
            if (stalled(a.noProgressMs, stallMinutes)) abandon(a, "no progress for " + stallMinutes + " min", w, log);
        }
        step = t.dayJobRunning() ? t.stepText() : null;
        return new Result(w, log, from, state);
    }

    private Goal firstOpen() {
        for (Goal g : goals) if (g.open()) return g;
        return null;
    }

    /** The active goal ended well. */
    public Result done(String why) {
        List<String> w = new ArrayList<>(), log = new ArrayList<>();
        State from = state;
        Goal a = active();
        if (a != null) done(a, why, w, log);
        return new Result(w, log, from, state);
    }

    /** Gives up the active goal (whisper "gave up <goal>: <reason>"); the next one starts. */
    public Result abandon(String reason) {
        List<String> w = new ArrayList<>(), log = new ArrayList<>();
        State from = state;
        Goal a = active();
        if (a != null) abandon(a, reason, w, log);
        return new Result(w, log, from, state);
    }

    private void done(Goal a, String why, List<String> w, List<String> log) {
        a.state = GoalState.DONE;
        a.why = why;
        log.add("day plan: " + a.text + " done (" + why + ")");
        advance(w, log);
    }

    private void abandon(Goal a, String reason, List<String> w, List<String> log) {
        a.state = GoalState.FAILED;
        a.why = reason;
        w.add("gave up " + a.text + ": " + reason);
        log.add("day plan: gave up " + a.text + ": " + reason);
        advance(w, log);
    }

    /** The next pending goal becomes active (PENDING), else IDLE with the "queue empty" whisper. True when one is active. */
    private boolean advance(List<String> w, List<String> log) {
        step = null;
        for (Goal g : goals) if (g.state == GoalState.PENDING) {
            g.state = GoalState.ACTIVE;
            g.noProgressMs = 0;
            state = State.PENDING;
            log.add("day plan: next " + g.text);
            return true;
        }
        state = State.IDLE;
        w.add(QUEUE_EMPTY);
        return false;
    }

    public static final String QUEUE_EMPTY = "day plan done - idle list now";

    // ---- 0.27.1: re-plan once on a missing item ----

    /** The active goal is being re-planned from the current bag and stock (its job is not offered meanwhile). */
    boolean replanning;
    /** What the failed chain missed (the "gave up" text when the re-plan brings nothing new). */
    String failedMissing;

    public boolean replanning() { return replanning; }

    /**
     * The active goal's chain failed (or was judged infeasible) for missing: itemClass (NO_ITEM / NONE_IN_CHESTS) and not
     * re-planned yet: state replanning (the runtime asks the planner); else the goal is given up.
     */
    public Result chainFailed(String missing, boolean itemClass) {
        List<String> w = new ArrayList<>(), log = new ArrayList<>();
        State from = state;
        Goal a = active();
        if (a == null || replanning) return new Result(w, log, from, state);
        String m = missing == null || missing.isBlank() ? "its chain failed" : missing;
        if (itemClass && !a.replanned) {
            a.replanned = true;
            replanning = true;
            failedMissing = m;
            log.add("day plan: replanning " + a.text + " from what I have now (" + m + ")");
        } else abandon(a, m, w, log);
        return new Result(w, log, from, state);
    }

    /** The re-plan's answer: a new chain serves the goal; none, "already have it" or the same chain again ends it. */
    public Result replanned(String chain, String why) {
        List<String> w = new ArrayList<>(), log = new ArrayList<>();
        State from = state;
        Goal a = active();
        boolean was = replanning;
        replanning = false;
        if (a == null || !was) return new Result(w, log, from, state);
        String miss = failedMissing == null ? "its chain failed" : failedMissing;
        if (chain == null) abandon(a, miss + "; no plan: " + (why == null ? "no answer" : why), w, log);
        else if (chain.isBlank()) done(a, "I already have it", w, log);
        else if (sameChain(chain, a.chain)) abandon(a, miss, w, log);
        else {
            a.chain = chain;
            a.noProgressMs = 0;
            a.lastStep = -1;
            if (state != State.PAUSED_NIGHT && state != State.PAUSED_OWNER) state = State.PENDING;
            log.add("day plan: " + a.text + " replanned: " + chain);
        }
        return new Result(w, log, from, state);
    }

    /** Two chains are the same plan (case and spacing aside). */
    public static boolean sameChain(String a, String b) {
        if (a == null || b == null) return a == b;
        return a.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").equals(b.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
    }

    /**
     * 0.27.1 dayplan clear: by day without hold the next loop selects anew (as dayplan now); at night or with hold, only at
     * the next dawn. Pure.
     */
    public static boolean replanAfterClear(boolean night, boolean hold) { return !night && !hold; }

    /** dayplan clear. */
    public void clear() {
        goals.clear();
        selecting.clear();
        dry.clear();
        state = State.IDLE;
        step = null;
        replanning = false;
        failedMissing = null;
    }

    // ---- texts ----

    /** why: "day plan: iron tools (2/3), step 3/7 craft iron_pickaxe 1"; null when there is no plan. */
    public String whyLine() {
        if (state == State.SELECTING) return "day plan: planning the day (" + selecting.size() + " candidates, " + toPlan().size() + " still planning)";
        Goal a = active();
        if (a == null) return goals.isEmpty() ? null : "day plan: " + state.name().toLowerCase(Locale.ROOT) + " (" + doneCount() + "/" + goals.size() + " done)";
        int pos = goals.indexOf(a) + 1;
        if (replanning) return "day plan: " + a.text + " (" + pos + "/" + goals.size() + "), replanning";
        String tail = switch (state) {
            case PAUSED_OWNER -> ", paused: your goal runs";
            case PAUSED_NIGHT -> ", paused for the night";
            case INTERRUPTED -> ", interrupted";
            default -> step != null ? ", step " + step : "";
        };
        return "day plan: " + a.text + " (" + pos + "/" + goals.size() + ")" + tail;
    }

    int doneCount() {
        int n = 0;
        for (Goal g : goals) if (g.state == GoalState.DONE) n++;
        return n;
    }

    /** dayplan: every goal with its state. */
    public String show(long now) {
        if (state == State.SELECTING) return whyLine();
        if (goals.isEmpty()) return "day plan: none today (state " + state.name().toLowerCase(Locale.ROOT) + ") - dayplan now makes one";
        List<String> parts = new ArrayList<>();
        for (Goal g : goals) parts.add(g.text + " " + g.state.name().toLowerCase(Locale.ROOT) + (g.carried ? " (carried)" : "") + (g.why.isEmpty() ? "" : " - " + g.why)
                + (g == active() ? ", " + g.noProgressMs / 60000 + " min without progress" : ""));
        return "day plan " + id + " (" + state.name().toLowerCase(Locale.ROOT) + ", made " + Math.max(0, (now - madeAt) / 60000) + " min ago): " + String.join("; ", parts)
                + (step != null ? " | step " + step : "");
    }

    // ---- dayplan.json ----

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("id", id);
        o.addProperty("state", state.name());
        o.addProperty("dayKey", dayKey);
        o.addProperty("madeAt", madeAt);
        JsonArray a = new JsonArray();
        for (Goal g : goals) {
            JsonObject e = new JsonObject();
            e.addProperty("text", g.text);
            e.addProperty("state", g.state.name());
            e.addProperty("chain", g.chain);
            e.addProperty("why", g.why);
            e.addProperty("carried", g.carried);
            e.addProperty("priority", g.priority);
            e.addProperty("noProgressMs", g.noProgressMs);
            if (g.replanned) e.addProperty("replanned", true);
            a.add(e);
        }
        o.add("goals", a);
        return o;
    }

    /** Reads dayplan.json; throws IllegalArgumentException (with why) on a broken file. A SELECTING plan reads back as IDLE. */
    public static DayPlan fromJson(String text) {
        DayPlan p = new DayPlan();
        if (text == null || text.isBlank()) return p;
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(text);
            if (!e.isJsonObject()) throw new IllegalArgumentException("not a JSON object");
            o = e.getAsJsonObject();
            p.id = o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsString() : "";
            p.dayKey = o.has("dayKey") && !o.get("dayKey").isJsonNull() ? o.get("dayKey").getAsString() : null;
            p.madeAt = o.has("madeAt") ? o.get("madeAt").getAsLong() : 0;
            p.state = o.has("state") ? State.valueOf(o.get("state").getAsString()) : State.IDLE;
            if (o.has("goals")) for (JsonElement ge : o.getAsJsonArray("goals")) {
                JsonObject g = ge.getAsJsonObject();
                Goal x = new Goal(g.get("text").getAsString());
                x.state = GoalState.valueOf(g.get("state").getAsString());
                x.chain = g.has("chain") && !g.get("chain").isJsonNull() ? g.get("chain").getAsString() : null;
                x.why = g.has("why") && !g.get("why").isJsonNull() ? g.get("why").getAsString() : "";
                x.carried = g.has("carried") && g.get("carried").getAsBoolean();
                x.priority = g.has("priority") ? g.get("priority").getAsInt() : 0;
                x.noProgressMs = g.has("noProgressMs") ? g.get("noProgressMs").getAsLong() : 0;
                x.replanned = g.has("replanned") && g.get("replanned").getAsBoolean();
                p.goals.add(x);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        if (p.state == State.SELECTING) p.state = p.active() != null ? State.PENDING : State.IDLE;
        return p;
    }

    /** brain.json's "dayPlan" (the brain page's card) and the decision log's {id, goals, active, state, step}. */
    public JsonObject brainJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("state", state.name());
        o.addProperty("madeAt", madeAt);
        Goal a = active();
        o.addProperty("active", a == null ? null : a.text);
        o.addProperty("step", step);
        if (replanning) o.addProperty("replanning", true);
        JsonArray gs = new JsonArray();
        for (Goal g : goals) {
            JsonObject e = new JsonObject();
            e.addProperty("text", g.text);
            e.addProperty("state", cardState(g));
            if (!g.why.isEmpty()) e.addProperty("why", g.why);
            if (g.carried) e.addProperty("carried", true);
            gs.add(e);
        }
        o.add("goals", gs);
        return o;
    }

    /** Pending / Active / Done / Failed / Paused for the card. */
    String cardState(Goal g) {
        return switch (g.state) {
            case PENDING -> "Pending";
            case ACTIVE -> "Active";
            case DONE -> "Done";
            case FAILED -> "Failed";
            case PAUSED -> "Paused";
        };
    }
}
