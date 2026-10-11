package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.camp.DayNight;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.26.3 the day plan's driver (docs/DAYPLAN_PLAN.md), owned by {@link Brain} and run once per brain loop before the
 * needs are scored: the dawn trigger (the level's day key, {@link DayNight#key}, turns to a new day while in a world;
 * also the owner's {@code done} and {@code dayplan now}), the planner dry runs (one per candidate through
 * {@link BrainEnv#planDay}, answered on the planner's worker; the selection waits across loops, {@code why: planning the
 * day}, at most {@link #SELECT_WAIT_MS}), the {@code day_goal} need feed ({@link BrainState#dayGoal}), dayplan.json (via
 * {@link BrainEnv#readFile}/{@link BrainEnv#writeFile}: BotFiles' atomic writes), the whispers, the decision-log lines.
 *
 * <p>Loader notes: none here; the tick is the brain loop's (NeoForge ClientTickEvent.Post through Commands), the day time
 * comes from ClientLevel.getDayTime in BrainRuntime.sense, the dry runs from GoalPlanning's AsyncPlanner worker.
 * Errors: a broken dayplan.json = an empty plan and the {@code check} finding "dayplan"; a planner that throws or never
 * answers drops that candidate (logged); a failed save is logged (the plan lives on in memory). Every state change is one
 * decision-log line.
 */
public final class DayPlanRuntime {
    public static final String FILE = "dayplan.json";
    /** A selection whose dry runs are not all back after this falls through (unanswered candidates dropped, logged). */
    public static final long SELECT_WAIT_MS = 60_000;
    public static final String NEED = "day_goal";

    private final BrainEnv env;
    private DayPlan plan = new DayPlan();
    private boolean loaded, nowAsked;
    private String fileProblem, lastKey, saveError;
    private boolean lastReleased;
    private long lastLoopAt = -1;
    private final Set<String> asked = new HashSet<>();

    public DayPlanRuntime(BrainEnv env) { this.env = env; }

    public DayPlan plan() { return plan; }

    /** check: dayplan.json could not be read (null: fine). */
    public String fileProblem() { return fileProblem; }

    void load() {
        loaded = true;
        String text = env.readFile(FILE);
        if (text == null) return;
        if (text.startsWith("error")) {
            fileProblem = text;
            env.log("brain: day plan: " + FILE + " unreadable (" + text + ") - starting with an empty plan");
            return;
        }
        try {
            plan = DayPlan.fromJson(text);
            fileProblem = null;
        } catch (IllegalArgumentException e) {
            plan = new DayPlan();
            fileProblem = "broken (" + e.getMessage() + ")";
            env.log("brain: day plan: " + FILE + " is broken (" + e.getMessage() + ") - starting with an empty plan");
        }
    }

    void save() {
        String r = env.writeFile(FILE, plan.toJson().toString());
        if (r != null && r.startsWith("error")) {
            if (!r.equals(saveError)) env.log("brain: day plan: " + FILE + " not saved: " + r);
            saveError = r;
        } else {
            saveError = null;
            fileProblem = null;
        }
    }

    /** The table's facts from what the brain sensed (the feasibility facts: bag + stock, near table/furnace; the bed search; places). */
    public static DayPlan.Facts facts(BrainState s) {
        DayPlan.Facts f = new DayPlan.Facts();
        f.known = s.feas != null && s.feas.known;
        f.stage = s.stage;
        if (s.feas != null) {
            for (Map.Entry<String, Integer> e : s.feas.bag.entrySet()) f.stock.merge(e.getKey(), e.getValue(), Integer::sum);
            for (Map.Entry<String, Integer> e : s.feas.stock.entrySet()) f.stock.merge(e.getKey(), e.getValue(), Integer::sum);
            f.furnaceNear = s.feas.furnace;
            f.tableNear = s.feas.table;
        }
        f.food = s.foodStock;
        f.baseKnown = s.basePos != null;
        f.bedNear = s.bedNear;
        f.campKnown = s.campPos != null;
        return f;
    }

    /** The owner's goal (a goal noted for the brain, or a goal_ routine running as the owner's job), else null. */
    static String ownerGoal(BrainState s) {
        if (!s.goals.isEmpty()) return s.goals.get(0).text();
        if (s.ownerJob != null && s.ownerJob.startsWith("goal_")) return s.ownerJob.substring(5).replace('_', ' ');
        return null;
    }

    /**
     * One brain loop, before the needs: dawn / done / dayplan now start a selection; a selection is pumped; else the plan
     * is updated and the active goal offered as s.dayGoal. runningNeed: the brain's running job's need (null none).
     */
    public void loop(BrainState s, BrainConfig c, String runningNeed) {
        if (!loaded) load();
        s.dayGoal = null;
        long now = s.now;
        long dt = lastLoopAt < 0 ? 0 : Math.max(0, Math.min(now - lastLoopAt, 10_000));
        lastLoopAt = now;
        if (!s.inWorld || s.dayTime < 0) {      // not in a world: no dawn (the first look after a join never fires)
            lastKey = null;
            return;
        }
        String key = DayNight.key(s.dayTime);
        DayPlan.Facts f = facts(s);
        boolean dawn = DayPlan.dawn(lastKey, key) && !key.equals(plan.dayKey());
        boolean doneNow = s.released && !lastReleased && lastKey != null && !s.night && !key.equals(plan.dayKey()) && plan.state() == DayPlan.State.IDLE;
        lastKey = key;
        lastReleased = s.released;
        if (plan.state() != DayPlan.State.SELECTING) {
            String why = dawn ? "dawn" : doneNow ? "you said done" : nowAsked && !s.night ? "dayplan now" : null;
            if (nowAsked && s.night) nowAsked = false;
            if (why != null) {
                nowAsked = false;
                begin(f, now, key, why);
            }
        }
        if (plan.state() == DayPlan.State.SELECTING) {
            pump(s, c, f, now);
            context();
            return;
        }
        boolean dayJob = NEED.equals(runningNeed);
        boolean otherBusy = !dayJob && (runningNeed != null || s.danger || s.ownerJob != null || s.passive != null);
        String stepText = dayJob ? env.chainStep() : null;
        int step = -1;
        if (stepText != null) try { step = Integer.parseInt(stepText.substring(0, stepText.indexOf('/'))); } catch (RuntimeException ignored) { step = -1; }
        DayPlan.Result r = plan.update(new DayPlan.Tick(now, dt, s.night, ownerGoal(s), dayJob, otherBusy, step, stepText, s.botPos, f), c.i("dayPlan.stallMinutes"));
        apply(r, now);
        DayPlan.Goal a = plan.active();
        DayPlan.State st = plan.state();
        if (a != null && a.chain != null && !a.chain.isBlank() && (st == DayPlan.State.PENDING || st == DayPlan.State.SERVING || st == DayPlan.State.INTERRUPTED))
            s.dayGoal = new BrainState.DayGoal(a.text, a.chain, plan.whyLine());
        context();
    }

    private void begin(DayPlan.Facts f, long now, String key, String why) {
        DayPlan.State from = plan.state();
        f.darkMainArea = safeDark();
        List<String> log = plan.beginSelection(f, now, key);
        asked.clear();
        log.forEach(env::log);
        env.log("brain: day plan: selecting (" + why + "): " + String.join(", ", plan.toPlan()));
        change(now, from, plan.state(), why);
        save();
    }

    private String safeDark() {
        try { return env.darkMainArea(); } catch (RuntimeException e) {
            env.log("brain: day plan: the dark-area check threw: " + e);
            return null;
        }
    }

    private void pump(BrainState s, BrainConfig c, DayPlan.Facts f, long now) {
        for (String t : plan.toPlan()) {
            if (!asked.add(t)) continue;
            boolean ok;
            try {
                ok = env.planDay(t, r -> plan.dryResult(t, r == null ? null : r[0], r == null ? "no answer" : r[1]));
            } catch (RuntimeException e) {
                ok = false;
                env.log("brain: day plan: the dry run for " + t + " threw: " + e);
            }
            if (!ok && !plan.dry.containsKey(t)) plan.dryResult(t, null, "no planner");
        }
        boolean timeout = now - plan.selectStarted >= SELECT_WAIT_MS;
        if (!plan.selectionReady() && !timeout) return;
        if (timeout && !plan.selectionReady()) env.log("brain: day plan: the planner did not answer within " + SELECT_WAIT_MS / 1000 + " s for " + plan.toPlan() + " - those are dropped");
        DayPlan.State from = plan.state();
        DayPlan.Selected sel = plan.finishSelection(c.i("dayPlan.maxGoals"), now, plan.dayKey());
        sel.log().forEach(l -> env.log("brain: " + l));
        env.whisper(sel.line());
        env.log("brain: " + sel.line());
        change(now, from, plan.state(), sel.line());
        save();
    }

    private void apply(DayPlan.Result r, long now) {
        r.log().forEach(l -> env.log("brain: " + l));
        r.whispers().forEach(env::whisper);
        if (r.from() != r.to()) {
            change(now, r.from(), r.to(), r.log().isEmpty() ? "" : r.log().get(r.log().size() - 1));
            save();
        } else if (!r.log().isEmpty() || !r.whispers().isEmpty()) save();
    }

    private void change(long now, DayPlan.State from, DayPlan.State to, String why) {
        if (from == to) return;
        try { env.decisions().dayPlan(now, from.name(), to.name(), why, plan.brainJson()); } catch (RuntimeException ignored) { }
    }

    private void context() {
        try { env.decisions().dayPlanContext(plan.goals().isEmpty() && plan.state() == DayPlan.State.IDLE ? null : plan.brainJson()); } catch (RuntimeException ignored) { }
    }

    /** The brain's day_goal job finished well. */
    public void jobFinished(long now) {
        apply(plan.done("its chain finished"), now);
    }

    /** The planner says the active goal's step has no source (feasibility found no way). */
    public void abandon(String reason, long now) {
        if (plan.active() == null) return;
        apply(plan.abandon(reason), now);
    }

    /** "dayplan | dayplan skip | dayplan clear | dayplan now". stopDayJob stops the brain's job when it is the day goal's. */
    public String command(String rest, boolean night, boolean brainOn, Runnable stopDayJob, long now) {
        if (!loaded) load();
        String t = rest == null ? "" : rest.trim().toLowerCase();
        String off = brainOn ? "" : " (the brain is off: brain on serves it)";
        switch (t) {
            case "", "show", "status" -> { return plan.show(now) + off; }
            case "skip" -> {
                DayPlan.Goal a = plan.active();
                if (a == null) return "no day goal to skip - " + plan.show(now);
                stopDayJob.run();
                apply(plan.abandon("skipped by you"), now);
                DayPlan.Goal n = plan.active();
                return "ok: skipped " + a.text + (n == null ? " - the plan is done" : " - next: " + n.text);
            }
            case "clear" -> {
                stopDayJob.run();
                DayPlan.State from = plan.state();
                plan.clear();
                change(now, from, plan.state(), "dayplan clear");
                save();
                return "ok: the day plan is cleared - the idle list until the next dawn (dayplan now makes a new one)";
            }
            case "now" -> {
                if (night) return "error: dayplan now works by day - at night the plan waits for dawn";
                if (plan.state() == DayPlan.State.SELECTING) return "already planning the day - " + plan.whyLine();
                nowAsked = true;
                return "ok: planning the day now - the plan comes as a whisper" + off;
            }
            default -> { return "usage: dayplan | dayplan skip | dayplan clear | dayplan now"; }
        }
    }

    /** brain status: "day plan: ..." */
    public String statusLine() {
        String w = plan.whyLine();
        return w == null ? "day plan: none" : w;
    }

    public JsonObject json() {
        JsonObject o = plan.brainJson();
        if (fileProblem != null) o.addProperty("fileProblem", fileProblem);
        return o;
    }

    /** check: {id, text, fix} or null. */
    public String[] finding() {
        if (fileProblem == null) return null;
        return new String[]{"dayplan", FILE + " could not be read (" + fileProblem + ") - the day plan started empty", "dayplan clear (writes a fresh file), or dayplan now"};
    }
}
