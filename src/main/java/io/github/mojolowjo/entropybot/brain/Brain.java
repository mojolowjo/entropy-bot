package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B1: the brain loop (docs/BRAIN_LOOP.md): every 2 s sense, events, the tree, start or switch a job through the
 * existing verbs, report. Whispers only on a change (a job it started, with its one-line why; a need it cannot meet);
 * every decision goes to the decision log and {@code why}. A need that failed 3 times in a row parks for 30 minutes.
 * {@code brain off} stops the loop and the job it started; the owner's jobs run on. Settings live in commands.json
 * {@code brain}: on, idle, copy, config, parked, fails. Talks to the game only through {@link BrainEnv}.
 *
 * <p>Errors: each loop catches everything (counted; the first 5 logged in full, then every 1200th); {@code brain status}
 * shows loops/min, the last loop's ms, the errors and the last decision. Loader notes: none (pure Java and Gson).
 */
public final class Brain {
    public static final long HOLD_MS = 600_000;
    /** An owner order within this of a brain start: the strongest label for the learning step (the outcome says overridden_by_owner). */
    static final long OVERRIDE_MS = 60_000;
    /** A job that ends this soon after its start without failing still counts toward parking. */
    static final long QUICK_MS = 5_000;

    private final BrainEnv env;
    private BrainTree tree = new BrainTree();
    /** B4: where the tree came from (built-in / override), why (a refused override's reasons), and the export. */
    private String treeSource = "built-in", treeNote = "";
    private boolean treeLoaded, treeRefused, offWritten;
    private long exportErrors;
    public static final String CONFIG_FILE = "brain-config.json";
    private BrainConfig cfg = BrainConfig.defaults();

    /** The job the brain started. */
    private record Job(String need, String chain, int score, long ref, long at, String base) {}

    private Job job;
    private BrainState prev;
    private BrainTree.Decision last;
    private Needs.Scored lastScored;
    private List<Interrupts.Event> lastEvents = List.of();
    private String lastLogged, lastWhisper;
    private long lastWhisperAt;
    private long lastDecisionAt;
    private int[] nightAt;
    private final ArrayDeque<String> whys = new ArrayDeque<>();
    private final ArrayDeque<Long> loopTimes = new ArrayDeque<>();
    private long loops, errors, lastLoopAt = -1;
    private double lastLoopMs;
    private String lastError, configNote = "";

    public Brain(BrainEnv env) {
        this.env = env;
        this.day = new DayPlanRuntime(env);
    }

    // ---- 0.26.3: the day plan ----

    private final DayPlanRuntime day;

    public DayPlanRuntime dayPlan() { return day; }

    /** "dayplan | dayplan skip | dayplan clear | dayplan now". */
    public String dayplan(String rest) {
        String r = rest == null ? "" : rest.trim();
        boolean night = prev != null ? prev.night : env.sense().night;
        return day.command(r, night, on(), () -> { if (job != null && DayPlanRuntime.NEED.equals(job.need())) stopJob("dayplan " + r); }, env.now());
    }

    // ---- the store ----

    JsonObject data() {
        JsonObject root = env.store();
        if (!root.has("brain") || !root.get("brain").isJsonObject()) root.add("brain", new JsonObject());
        return root.getAsJsonObject("brain");
    }

    static boolean bool(JsonObject o, String k, boolean def) {
        try { return o.has(k) ? o.get(k).getAsBoolean() : def; } catch (RuntimeException e) { return def; }
    }

    static long num(JsonObject o, String k, long def) {
        try { return o.has(k) ? o.get(k).getAsLong() : def; } catch (RuntimeException e) { return def; }
    }

    static String str(JsonObject o, String k, String def) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def; } catch (RuntimeException e) { return def; }
    }

    public boolean on() {
        migrate();
        return bool(data(), "on", false);
    }

    public boolean copyOn() { return bool(data(), "copy", false); }

    public List<String> idleList() {
        JsonObject d = data();
        List<String> out = new ArrayList<>();
        if (d.has("idle") && d.get("idle").isJsonArray()) for (JsonElement e : d.getAsJsonArray("idle")) if (IdleList.ITEMS.contains(e.getAsString())) out.add(e.getAsString());
        return out.isEmpty() ? IdleList.DEFAULT : out;
    }

    public BrainConfig config() { return cfg; }

    /** The brain's need key for a goal: keyed by its text, never its list index (0.23.1), so a new goal #1 starts clean. */
    public static String goalNeed(String text) {
        return "goal:" + (text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 0.23.1: forgets the parks and failure counts of goals. text null = every goal ({@code goals clear all}); else that goal.
     * brainData is the store's "brain" object. Returns how many entries went.
     */
    public static int forgetGoalParks(JsonObject brainData, String text) {
        if (brainData == null) return 0;
        int n = 0;
        for (String key : new String[]{"parked", "fails"}) {
            if (!brainData.has(key) || !brainData.get(key).isJsonObject()) continue;
            JsonObject o = brainData.getAsJsonObject(key);
            for (String k : new ArrayList<>(o.keySet()))
                if (text == null ? k.startsWith("goal:") : k.equals(goalNeed(text))) { o.remove(k); n++; }
        }
        return n;
    }

    /** Parked needs that are still parked: need -> why. */
    Map<String, String> parked(long now) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonObject d = data();
        if (!d.has("parked") || !d.get("parked").isJsonObject()) return out;
        for (Map.Entry<String, JsonElement> e : d.getAsJsonObject("parked").entrySet()) {
            JsonObject p = e.getValue().getAsJsonObject();
            long until = num(p, "until", 0);
            if (until > now) out.put(e.getKey(), str(p, "why", "?") + ", " + Math.max(1, (until - now) / 60000) + " min left");
        }
        return out;
    }

    /** The old autominer: switched on means the brain on (once), and its log is copied into the decision log (once). */
    void migrate() {
        JsonObject root = env.store();
        if (!root.has("autominer") || !root.get("autominer").isJsonObject()) return;
        JsonObject a = root.getAsJsonObject("autominer");
        JsonObject d = root.has("brain") && root.get("brain").isJsonObject() ? root.getAsJsonObject("brain") : null;
        if (d == null && bool(a, "on", false)) {
            d = data();
            d.addProperty("on", true);
            d.addProperty("since", env.now());
            a.addProperty("on", false);
            env.log("brain: the autominer was on, so the brain is on now");
            env.saved();
        }
        if (d != null && bool(d, "on", false) && !bool(a, "historyCopied", false)) copyHistory(a);
    }

    private void copyHistory(JsonObject a) {
        if (a.has("log") && a.get("log").isJsonArray()) {
            for (JsonElement e : a.getAsJsonArray("log")) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                env.decisions().history(num(o, "at", env.now()), str(o, "what", ""), str(o, "why", ""), str(o, "result", null));
            }
        }
        a.addProperty("historyCopied", true);
        env.saved();
    }

    // ---- verbs ----

    /** "brain on|off|status", "brain copy on|off|status". */
    public String command(String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase();
        if (t.matches("^(get|set|reset)\\b.*")) return settings(rest);         // B4
        if (t.matches("^tree\\b.*")) return treeCommand(t.substring(4));
        JsonObject d = data();
        switch (t) {
            case "on" -> {
                d.addProperty("on", true);
                d.addProperty("since", env.now());
                d.remove("heldUntil");
                env.saved();
                migrate();
                return "ok: brain on - every 2 s I look at my needs and yours (needs), and when nothing is asked I do the idle list ("
                        + String.join(", ", idleList()) + "); why says what I decided";
            }
            case "off" -> {
                d.addProperty("on", false);
                env.saved();
                String stopped = stopJob("brain off");
                return "ok: brain off" + (stopped != null ? " - stopped my job " + stopped : "") + "; your jobs run on";
            }
            case "", "status" -> { return status(); }
            case "copy on", "copy off" -> {
                d.addProperty("copy", t.endsWith("on"));
                env.saved();
                return t.endsWith("on") ? "ok: brain copy on - when you chop, mine or farm (the companion mod tells me), I do the same within "
                        + cfg.i("copyR") + " blocks of you; building is not copied; it stops after " + cfg.i("copyIdleS") + " s of you doing nothing" : "ok: brain copy off";
            }
            case "copy", "copy status" -> {
                BrainState s = prev;
                return "brain copy is " + (copyOn() ? "on" : "off") + (s != null && s.copy != null ? " - " + s.copy.why() : "");
            }
            default -> { return "usage: brain on|off|status | brain copy on|off | brain get [key] | brain set <key> <value> | brain reset <key>|all | brain tree [reload]"; }
        }
    }

    /** "idle list" | "idle list set <items>". */
    public String idle(String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase();
        if (t.equals("list") || t.isEmpty()) return IdleList.show(idleList());
        if (t.startsWith("list set ")) {
            List<String> l = IdleList.parse(t.substring(9));
            if (l == null) return "error: the idle list takes " + String.join(", ", IdleList.ITEMS) + " (each once), e.g. idle list set strip, cave";
            JsonArray a = new JsonArray();
            l.forEach(a::add);
            data().add("idle", a);
            env.saved();
            exportTree();
            return "ok: " + IdleList.show(l);
        }
        if (t.equals("list reset")) {
            data().remove("idle");
            env.saved();
            exportTree();
            return "ok: " + IdleList.show(idleList());
        }
        return "usage: idle list | idle list set restock, strip, cave, farm | idle list reset";
    }

    /** A "stop": the brain waits HOLD_MS before its next decision; the reply's tail ("" when off). */
    public String hold() {
        if (!on()) return "";
        data().addProperty("heldUntil", env.now() + HOLD_MS);
        env.saved();
        return "; the brain waits " + HOLD_MS / 60000 + " min (brain on to go on now)";
    }

    String stopJob(String why) {
        if (job == null) return null;
        Job j = job;
        job = null;
        env.decisions().outcome(env.now(), j.ref(), "interrupted:" + why);
        if (env.jobRunning()) env.stopJob(why);
        return j.chain();
    }

    /** The owner's order while the brain's job runs: the job stops (outcome overridden_by_owner within 60 s of its start). */
    public void yieldTo(String verb) {
        if (job == null) return;
        Job j = job;
        job = null;
        long now = env.now();
        env.decisions().outcome(now, j.ref(), (now - j.at() <= OVERRIDE_MS ? "overridden_by_owner:" : "interrupted:owner ") + verb);
        env.stopJob("your " + verb);
    }

    public String status() {
        long now = env.now();
        StringBuilder b = new StringBuilder("brain is " + (on() ? "on" : "off"));
        long held = num(data(), "heldUntil", 0);
        if (on() && held > now) b.append(" (waiting ").append((held - now + 59999) / 60000).append(" min after a stop)");
        b.append(" - ").append(loopsPerMinute(now)).append(" loops/min, last ").append(Math.round(lastLoopMs * 10) / 10.0).append(" ms")
                .append(lastLoopAt < 0 ? " (never)" : " " + (now - lastLoopAt) / 1000 + " s ago").append(", errors ").append(errors);
        double[] cm = costMinute(now);      // 0.26.1: the snapshot cost counter
        b.append(" | cost ").append(r2(cm[0])).append(" ms a loop (max ").append(r2(cm[1])).append(", last sense ").append(r2(lastSenseMs)).append(" ms; last minute)");
        b.append(" | ").append(feasibilityLine());      // 0.26.2
        b.append(" | ").append(day.statusLine());       // 0.26.3
        if (mem.size() > 0) b.append(" | failure memory: ").append(mem.describe(now));
        if (prev != null) b.append(" | stage ").append(prev.stage);
        if (job != null) b.append(" | job: ").append(job.chain()).append(" (").append(job.need()).append(")");
        if (last != null) b.append(" | last: ").append(last.branch()).append(lastDecisionAt > 0 ? " " + (now - lastDecisionAt) / 1000 + " s ago" : "");
        Map<String, String> p = parked(now);
        if (!p.isEmpty()) b.append(" | parked: ").append(p);
        b.append(" | idle list ").append(String.join(", ", idleList())).append(" | copy ").append(copyOn() ? "on" : "off");
        DecisionLog log = env.decisions();
        b.append(" | log ").append(log.written()).append(" lines").append(log.dropped() > 0 ? ", " + log.dropped() + " dropped (5 MB a day)" : "")
                .append(log.errors() > 0 ? ", " + log.errors() + " write errors (" + log.lastError() + ")" : "");
        if (!configNote.isEmpty()) b.append(" | brain.config ignored: ").append(configNote);
        if (lastError != null) b.append(" | last error: ").append(lastError);
        return b.toString();
    }

    int loopsPerMinute(long now) {
        while (!loopTimes.isEmpty() && now - loopTimes.peekFirst() > 60000) loopTimes.pollFirst();
        return loopTimes.size();
    }

    /** "why": the last decision with its scores, the branch, the stage; the older ones above it. */
    public String why() {
        if (last == null) return on() ? "no decision yet (the brain looks every 2 s)" : "the brain is off - brain on";
        List<String> older = new ArrayList<>(whys);
        String latest = older.isEmpty() ? "" : older.remove(older.size() - 1);
        List<String> sk = new ArrayList<>(lastSkipped);
        if (lastScored != null) sk.addAll(lastScored.skipped());
        String scores = (lastScored == null ? "" : " | scores: " + lastScored.line()) + (sk.isEmpty() ? "" : " | skipped: " + String.join("; ", sk));
        return (older.isEmpty() ? "" : String.join(" | ", older) + "\n") + "latest: " + latest + scores + (prev != null ? " | stage " + prev.stage : "")
                + (lastEvents.isEmpty() ? "" : " | events " + lastEvents)
                + (prev != null ? " | " + NightSafety.status(prev, cfg) : "")
                + (day.plan().whyLine() == null ? "" : " | " + day.plan().whyLine());      // 0.26.3
    }

    /** 0.24.3 "night status": the night safety score, its band and the time to dusk (sensed now). */
    public String nightStatus() {
        BrainState s = env.sense();
        if (!s.inWorld) return "night status: not in a world";
        return NightSafety.status(s, cfg) + " - " + switch (NightSafety.band(NightSafety.score(s), cfg)) {
            case SHELTER -> "at dusk I shelter until day (and start no surface job in the last " + cfg.i("night.duskHours") + " h before it)";
            case UNDERGROUND -> "at night I work only underground or in the lit camp";
            default -> "at night I light my spot and sleep when others do";
        } + (on() ? "" : " (the brain is off: brain on)");
    }

    /** "needs": the brain's scores (a line added to the owner's needs). */
    public String scoresLine() {
        if (lastScored == null) return on() ? "brain scores: none yet" : "brain scores: (brain off)";
        return "brain scores: " + lastScored.line() + (lastScored.skipped().isEmpty() ? "" : " | skipped: " + String.join("; ", lastScored.skipped()));
    }

    /** status: "brain: on (upkeep: mine strip)" or null when off. */
    public String statusPart() {
        if (!on()) return null;
        if (job != null) return "brain: on (" + job.need() + ": " + shortChain(job.chain()) + ")";
        return "brain: on (" + (last == null ? "starting" : last.branch()) + ")";
    }

    static String shortChain(String c) {
        String[] w = c.split("\\s+");
        return w.length >= 2 && !w[1].matches("^\\d.*") ? w[0] + " " + w[1] : w[0];
    }

    /** check findings: {id, text, fix}. */
    public List<String[]> findings() {
        List<String[]> out = new ArrayList<>();
        long now = env.now();
        if (on() && num(data(), "heldUntil", 0) <= now && (lastLoopAt < 0 ? false : now - lastLoopAt > 10_000))
            out.add(new String[]{"brain", "the brain is on but has not looked for " + (now - lastLoopAt) / 1000 + " s", "brain status"});
        if (env.decisions().errors() > 0)
            out.add(new String[]{"brainlog", "the decision log could not be written (" + env.decisions().lastError() + ")", "check the entropybot\\brain folder"});
        if (treeRefused) out.add(new String[]{"braintree", treeNote, "fix " + BrainTreeFile.OVERRIDE + " (or delete it), then brain tree reload"});
        String[] dp = day.finding();      // 0.26.3: a broken dayplan.json
        if (dp != null) out.add(dp);
        if (!configNote.isEmpty()) out.add(new String[]{"brainconfig", "brain settings ignored: " + configNote, "brain get, then brain set or brain reset"});
        for (Map.Entry<String, String> e : parked(now).entrySet())
            out.add(new String[]{"brain:" + e.getKey(), "the brain's need " + e.getKey() + " failed 3 times and is parked (" + e.getValue() + ")", "why"});
        return out;
    }

    // ---- the loop ----

    public void tick() {
        long t0 = System.nanoTime();
        try {
            if (!treeLoaded) {
                cfg = loadConfig();                 // 0.25.1: the reflexes and armour care read the settings with the brain off too
                reloadTree();                       // B4: export brain-tree.json on start
            }
            if (!on()) {
                if (!offWritten) {                  // B4: the page sees "off" once
                    offWritten = true;
                    env.writeState(stateJson(prev));
                }
                return;
            }
            offWritten = false;
            long now = env.now();
            loops++;
            loopTimes.addLast(now);
            lastLoopAt = now;
            cfg = loadConfig();
            long ts = System.nanoTime();
            BrainState s = env.sense();
            lastSenseMs = (System.nanoTime() - ts) / 1e6;
            s.now = now;
            // the job the brain started: still running?
            if (job != null && !env.jobRunning()) endJob(s);
            s.brainJob = job == null ? null : job.chain();
            if (!s.night) nightAt = null;
            spawnFacts(s, now);                                     // 0.26.1 R6
            // 0.26.3: dawn, the selection, the day_goal need (held after a stop: interrupted, the stall clock waits)
            day.loop(s, cfg, num(data(), "heldUntil", 0) > now ? "held" : job == null ? null : job.need());
            // the day goal's job runs but the plan moved on (given up, done, paused by your goal or the night): it stops
            if (job != null && DayPlanRuntime.NEED.equals(job.need()) && (s.dayGoal == null || !job.chain().contains(s.dayGoal.chain()))) {
                stopJob("the day plan moved on");
                s.brainJob = null;
            }
            List<String> cleared = mem.sweep(s, now);               // 0.26.1 R1: expired or cause gone
            if (!cleared.isEmpty()) env.log("brain: failure memory cleared (the cause changed): " + String.join(", ", cleared));
            if (num(data(), "heldUntil", 0) > now) {
                prev = s;
                env.writeState(stateJson(s));
                return;
            }
            lastEvents = Interrupts.events(prev, s, cfg);
            Needs.Scored sc = Needs.score(s, cfg, idleList(), parked(now), job == null ? null : job.need());
            lastScored = sc;
            boolean nightDone = nightAt != null && BrainState.flat(nightAt, s.botPos) <= 16;
            BrainTree.Running running = job == null ? null : new BrainTree.Running(job.need(), job.chain(), job.score());
            List<String> skipped = new ArrayList<>();
            BrainTree.Ctx ctx = new BrainTree.Ctx(s, cfg, sc, running, nightDone, cfg.i("brain.failMemoryMinutes") > 0 ? mem : null, skipped);
            BrainTree.Decision d = feasible(tree.run(ctx), s, ctx, now);      // 0.26.2: feasibility before every start
            lastSkipped = skipped;
            skipWhispers(skipped, d, now);
            act(d, s, sc);
            prev = s;
            env.writeState(stateJson(s));
        } catch (Throwable e) {
            errors++;
            lastError = e.toString();
            if (errors <= 5 || errors % 1200 == 0) env.log("brain: loop error #" + errors + ": " + e);
        } finally {
            lastLoopMs = (System.nanoTime() - t0) / 1e6;
            if (lastLoopAt == env.now()) cost(env.now(), lastLoopMs);
        }
    }

    // ---- 0.26.1: the loop's cost (the brain-snapshot counter: sense + tree, ms per loop over the last minute) ----

    private final ArrayDeque<double[]> costs = new ArrayDeque<>();
    private double lastSenseMs;

    void cost(long now, double ms) {
        costs.addLast(new double[]{now, ms});
        while (!costs.isEmpty() && now - (long) costs.peekFirst()[0] > 60_000) costs.pollFirst();
    }

    /** {avg, max} ms per loop over the last minute (0 when none). */
    double[] costMinute(long now) {
        while (!costs.isEmpty() && now - (long) costs.peekFirst()[0] > 60_000) costs.pollFirst();
        double sum = 0, max = 0;
        for (double[] c : costs) { sum += c[1]; max = Math.max(max, c[1]); }
        return new double[]{costs.isEmpty() ? 0 : sum / costs.size(), max};
    }

    static double r2(double v) { return Math.round(v * 100) / 100.0; }

    // ---- 0.26.1 R1: the failure memory ----

    private final FailMemory mem = new FailMemory();
    private List<String> lastSkipped = List.of();

    /** The failure memory (tests, status). */
    public FailMemory failMemory() { return mem; }

    /** A whisper for the first skip of a (chain, reason) in an hour, only when the skip changed what the bot does. */
    void skipWhispers(List<String> skipped, BrainTree.Decision d, long now) {
        if (skipped.isEmpty()) return;
        JsonObject w = data().has("skipWhispered") && data().get("skipWhispered").isJsonObject() ? data().getAsJsonObject("skipWhispered") : new JsonObject();
        data().add("skipWhispered", w);
        for (String sk : skipped) {
            String kind = sk.replaceFirst(" \\d+ min ago$", "").replaceAll("\\d+ min( ago)?", "N min");      // 0.26.2: stable for the infeasible texts
            if (!parkWhisperDue(num(w, kind, 0), now)) continue;
            w.addProperty(kind, now);
            if (sk.startsWith("infeasible: ")) {      // 0.26.2: its own words
                env.whisper("brain: " + sk + (d.chain() != null ? " - " + d.chain() + " instead" : ""));
                continue;
            }
            env.whisper("brain: skipping " + kind + " (" + sk.replaceFirst("^.* failed \\S+ ", "") + ")" + (d.chain() != null ? " - " + d.chain() + " instead" : ""));
        }
        for (String k : new ArrayList<>(w.keySet())) if (now - num(w, k, 0) > PARK_WHISPER_MS) w.remove(k);
    }

    // ---- 0.26.1 R6: spawn at a bed ----

    /** In-game minutes to ms (1 in-game minute = 1000/60 ticks of 50 ms). */
    static long gameMinutesMs(int minutes) { return Math.round(minutes * 1000.0 / 60 * 50); }

    /** Fills spawnNearBase (sensed, or a spawn the brain set within 32 of the base) and spawnDue (the cooldown passed). */
    void spawnFacts(BrainState s, long now) {
        JsonObject d = data();
        if (d.has("spawnAt") && d.get("spawnAt").isJsonArray()) {
            try {
                var a = d.getAsJsonArray("spawnAt");
                int[] at = {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
                if (s.basePos == null || BrainState.flat(at, s.basePos) <= 32) s.spawnNearBase = true;
            } catch (RuntimeException ignored) {
                d.remove("spawnAt");
            }
        }
        long tried = num(d, "spawnTriedAt", 0);
        s.spawnDue = tried <= 0 || now - tried >= gameMinutesMs(cfg.i("spawn.cooldownMinutes"));
    }

    static boolean failed(String res) {
        return res != null && !res.startsWith("done") && !res.startsWith("ok");
    }

    void endJob(BrainState s) {
        Job j = job;
        job = null;
        long now = env.now();
        String res = env.lastEnd();
        String outcome;
        boolean held = num(data(), "heldUntil", 0) > now;
        if (held) outcome = "overridden_by_owner:stop";
        else if (s.ownerJob != null) outcome = (now - j.at() <= OVERRIDE_MS ? "overridden_by_owner:" : "interrupted:owner ") + s.ownerJob.split("\\s+")[0];
        else if (failed(res)) outcome = "failed:" + res;
        else outcome = "finished";
        env.decisions().outcome(now, j.ref(), outcome.length() > 400 ? outcome.substring(0, 400) : outcome);
        JsonObject d = data();
        // 0.26.1 R1/R6: a failed brain job with a precondition reason is remembered (the owner's stop or order never)
        if (outcome.startsWith("failed")) {
            int minutes = cfg.i("brain.failMemoryMinutes");
            // 0.26.2 fix: a supply prefix's "get X" that found none in the chests is remembered as that get, not as the job
            // 0.26.2: the failing step (numbers and coordinates stripped) AND the originating chain (the pick's own chain,
            // before any prefix or sub-plan) are both remembered; a supply get that found none in the chests: the step only
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile("^stopped at step \\d+ \\(([^)]*)\\)").matcher(res == null ? "" : res);
            String step = sm.find() ? sm.group(1) : null;
            FailMemory.Reason cls = j.need() != null && j.need().equals("spawn") ? FailMemory.Reason.NO_BED : FailMemory.classify(res);
            FailMemory.Reason r = null;
            if (cls != null) {
                boolean ownStep = step == null || FailMemory.stepKeys(j.base()).contains(FailMemory.stepKey(step));
                String why = subWhy.get(FailMemory.norm(j.base()));
                String note = why != null && step != null ? why + "; getting it failed: " + FailMemory.stepKey(step) + " " + cls : null;
                // the chain first: a skip names the chain before the step
                if (ownStep || cls != FailMemory.Reason.NONE_IN_CHESTS || !step.startsWith("get ")) r = mem.record(j.base(), cls, s, now, minutes, note);
                else r = cls;
                if (step != null) mem.recordStep(step, cls, s, now, minutes);
                if (step != null) env.log("brain: remembered step " + FailMemory.stepKey(step) + " (of " + j.base() + ") failed " + cls);
            }
            if (r != null) env.log("brain: remembered " + j.base() + " failed " + r + " for " + minutes + " min");
        } else if (outcome.equals("finished")) {
            String b = j.chain().toLowerCase();
            if (b.startsWith("deposit") || b.contains(" deposit") || b.startsWith("scan") || b.contains(" scan")) mem.storageChanged();
            if ("spawn".equals(j.need()) && s.botPos != null) {
                com.google.gson.JsonArray a = new com.google.gson.JsonArray();
                for (int v : s.botPos) a.add(v);
                d.add("spawnAt", a);
                env.log("brain: spawn set at the bed near " + s.botPos[0] + " " + s.botPos[1] + " " + s.botPos[2]);
            }
        }
        if (!d.has("fails") || !d.get("fails").isJsonObject()) d.add("fails", new JsonObject());
        JsonObject fails = d.getAsJsonObject("fails");
        // a job that "finished" at once and left its need open (a gather that counted the bag, not the group) counts as a failure,
        // so it can't loop every 2 s: three in a row park the need
        boolean quick = outcome.equals("finished") && now - j.at() < QUICK_MS && !j.need().equals("near") && !j.need().equals("night")
                && !j.need().startsWith("goal:") && !j.need().equals(DayPlanRuntime.NEED)      // a goal's routine that ends well is the goal reached, however fast (0.23.0 smoke)
                && !j.base().startsWith("eat") && !j.need().equals("spawn") && !j.need().equals("passive");     // 0.26.1: quick by nature
        if (quick) res = "ended at once (" + (res == null ? "no reply" : res.length() > 100 ? res.substring(0, 100) : res) + ") without meeting the need";
        if (outcome.equals("finished") || outcome.startsWith("failed")) io.github.mojolowjo.entropybot.summary.DaySummary.INSTANCE.need(outcome.equals("finished") && !quick);     // 0.23.6
        if (outcome.startsWith("failed") || quick) {
            int n = (int) num(fails, j.need(), 0) + 1;
            fails.addProperty(j.need(), n);
            if (j.chain().contains("mine strip") && res.matches("(?s).*(blocked:|stuck|couldn't reach the mine|no progress).*")) {
                JsonObject g = new JsonObject();
                g.addProperty("at", now);
                g.addProperty("why", res.replaceFirst("^stopped at step \\d+ \\([^)]*\\): ", ""));
                d.add("stripGaveUp", g);
            }
            if (n >= cfg.i("parkFailures")) {
                if (!d.has("parked") || !d.get("parked").isJsonObject()) d.add("parked", new JsonObject());
                JsonObject p = new JsonObject();
                p.addProperty("until", now + cfg.i("parkMinutes") * 60_000L);
                p.addProperty("why", (res.length() > 160 ? res.substring(0, 160) : res));
                d.getAsJsonObject("parked").add(j.need(), p);
                fails.addProperty(j.need(), 0);
                parkWhisper(d, j.need(), now, "brain: can't " + j.chain() + " - it failed " + n + " times (" + (res.length() > 120 ? res.substring(0, 120) + "..." : res) + "); I leave it for "
                        + cfg.i("parkMinutes") + " min");
            }
        } else if (outcome.equals("finished")) {
            fails.addProperty(j.need(), 0);
            if (j.need().startsWith("goal:")) env.goalDone(j.chain());          // B3: a goal reached leaves the list
            if (j.need().equals(DayPlanRuntime.NEED)) day.jobFinished(now);    // 0.26.3: the day goal's chain finished
        }
        env.saved();
    }

    void act(BrainTree.Decision d, BrainState s, Needs.Scored sc) {
        last = d;
        long now = env.now();
        switch (d.kind()) {
            case START -> start(d, s, sc);
            case SWITCH -> {
                stopJob(d.branch().equals("job.interrupt") ? "interrupt" : "outscored by " + d.need());
                start(d, s, sc);
            }
            default -> {
                if (!d.branch().equals(lastLogged)) {
                    record(d, s, sc, d.reason());
                    lastLogged = d.branch();
                }
            }
        }
        lastDecisionAt = now;
    }

    /** The chain start() runs for a decision (the supply, eat and pre-deposit prefixes) and the why's tail. */
    String[] compose(BrainTree.Decision d, BrainState s) {
        SupplyCheck.Supply sup = SupplyCheck.before(d.chain(), s, 0, 0);
        String eat = PlayRules.eatFirst(d.chain(), s, cfg);          // 0.25.1: cave.eatFirst (why shows it, no extra whisper)
        // 0.26.1 R3: a gathering job picked with few free slots and a base known: deposit first
        String pre = d.branch().equals("pick") ? PlayRules.preDeposit(d.chain(), s, cfg) : null;
        String job0 = pre != null ? pre : d.chain();
        String preWhy = pre == null ? "" : "; " + s.freeSlots + " free slots (brain.preDepositSlots " + cfg.i("brain.preDepositSlots") + "): deposit first";
        String supChain = sup == null ? null : sup.chain();
        if (supChain != null && cfg.i("brain.failMemoryMinutes") > 0) {      // 0.26.2 fix: drop a get the chests could not serve
            List<String> keep = new ArrayList<>();
            for (String st : supChain.split("\\s+then\\s+")) {
                String[] w = st.trim().split("\\s+");
                if (w.length >= 2 && w[0].equals("get") && mem.noneInChests(w[1], s, s.now)) continue;
                keep.add(st.trim());
            }
            supChain = keep.isEmpty() ? null : String.join(" then ", keep);
        }
        String chain = (supChain == null ? "" : supChain + " then ") + (eat == null ? "" : "eat then ") + job0;
        return new String[]{chain, (sup == null ? "" : "; " + sup.why()) + (eat == null ? "" : "; " + eat) + preWhy, sup == null ? null : sup.why()};
    }

    // ---- 0.26.2: feasibility before every start ----

    /** A sub-plan for (chain, item): pending, or found (chain) / not found (why). */
    private record Sub(long at, boolean pending, String chain, String why) {}

    /** A finished sub-plan is used within this, then asked again. */
    static final long SUB_TTL = 300_000;
    private final Map<String, Sub> subs = new LinkedHashMap<>();
    private final ArrayDeque<Long> feasChecked = new ArrayDeque<>(), feasInfeasible = new ArrayDeque<>(), feasSubgoals = new ArrayDeque<>();
    private JsonObject lastFeas;
    /** 0.26.2: a sub-plan chain -> the chain it serves; that chain -> what was missing (the skip note). */
    private final Map<String, String> origins = new LinkedHashMap<>(), subWhy = new LinkedHashMap<>();

    private static void mark(ArrayDeque<Long> q, long now) {
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > 3_600_000L) q.pollFirst();
    }

    private static int hour(ArrayDeque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > 3_600_000L) q.pollFirst();
        return q.size();
    }

    /** status: "feasibility: 12 checked, 2 infeasible, 1 subgoals this hour" (or "feasibility off"). */
    public String feasibilityLine() {
        long now = env.now();
        if (cfg.i("brain.feasibility") <= 0) return "feasibility off";
        return "feasibility: " + hour(feasChecked, now) + " checked, " + hour(feasInfeasible, now) + " infeasible, " + hour(feasSubgoals, now) + " subgoals this hour";
    }

    static JsonObject feasJson(Feasibility.Verdict v, String chain, String subchain) {
        JsonObject o = new JsonObject();
        o.addProperty("chain", chain);
        o.addProperty("step", v.stepText());
        o.addProperty("missing", v.missing());
        o.addProperty("class", v.cls() == null ? null : v.cls().name());
        o.addProperty("subchain", subchain);
        return o;
    }

    /**
     * Judges a START/SWITCH before it runs (BRAIN_PLAN 4.5b). Feasible or UNKNOWN: as is. Infeasible with an item a plan could
     * give: the planner is asked on its worker (this loop defers: NOTHING, "planning a way ..."), a found way runs first
     * ("<sub> then <chain>"); none (or no item): the chain goes into the failure memory under the verdict's class and the
     * tree runs again, so the next sibling or need answers (at most 4 rounds). Owner chains never reach here.
     */
    BrainTree.Decision feasible(BrainTree.Decision d, BrainState s, BrainTree.Ctx ctx, long now) {
        lastFeas = null;
        if (cfg.i("brain.feasibility") <= 0) return d;
        for (int round = 0; round < 4; round++) {
            if (d == null || d.chain() == null || (d.kind() != BrainTree.Kind.START && d.kind() != BrainTree.Kind.SWITCH)) return d;
            Feasibility.Verdict v = Feasibility.check(compose(d, s)[0], s);
            mark(feasChecked, now);
            if (!v.infeasible()) return d;
            mark(feasInfeasible, now);
            String notFound = null;
            if (v.item() != null) {
                String key = FailMemory.norm(d.chain()) + "|" + v.item();
                Sub sub = subs.get(key);
                if (sub != null && !sub.pending() && now - sub.at() > SUB_TTL) { subs.remove(key); sub = null; }
                if (sub == null) {
                    subs.put(key, new Sub(now, true, null, null));
                    boolean asked;
                    try {
                        asked = env.planSubgoal(v.item(), v.n(), cfg.i("brain.subgoalMaxSteps"), r -> subs.put(key, new Sub(env.now(), false, r == null ? null : r[0], r == null ? "no answer" : r[1])));
                    } catch (RuntimeException e) {
                        asked = false;
                        env.log("brain: feasibility: the sub-plan for " + v.item() + " threw: " + e);
                    }
                    if (!asked && subs.get(key).pending()) subs.put(key, new Sub(now, false, null, "no planner"));
                    sub = subs.get(key);
                }
                if (sub.pending()) {
                    lastFeas = feasJson(v, d.chain(), null);
                    return new BrainTree.Decision("feasibility.plan", BrainTree.Kind.NOTHING, null, d.need(), 0,
                            "planning a way to get " + v.item() + " for " + d.chain() + " (" + v.missing() + ")");
                }
                if (sub.chain() != null && !sub.chain().isBlank()) {
                    subs.remove(key);
                    mark(feasSubgoals, now);
                    String sc = sub.chain() + " then " + d.chain();
                    if (origins.size() > 50) origins.clear();
                    if (subWhy.size() > 50) subWhy.clear();
                    origins.put(FailMemory.norm(sc), d.chain());
                    subWhy.put(FailMemory.norm(d.chain()), v.missing());
                    lastFeas = feasJson(v, d.chain(), sub.chain());
                    return new BrainTree.Decision(d.branch(), d.kind(), sub.chain() + " then " + d.chain(), d.need(), d.score(),
                            d.chain() + " needs " + v.item() + ": " + sub.chain() + " first");
                }
                notFound = sub.why();
                if (DayPlanRuntime.NEED.equals(d.need())) day.abandon("no source for " + v.item(), now);      // 0.26.3: the planner found no way
            }
            lastFeas = feasJson(v, d.chain(), null);
            int minutes = cfg.i("brain.failMemoryMinutes");
            if (v.cls() == null) return new BrainTree.Decision("feasibility.skip", BrainTree.Kind.NOTHING, null, d.need(), 0, "infeasible: " + d.chain() + " (" + v.missing() + ")");
            if (minutes <= 0) {          // the failure memory is off: it can't be skipped for long, so it starts as before
                env.log("brain: feasibility: " + d.chain() + " looks infeasible (" + v.missing() + ") but brain.failMemoryMinutes is 0: starting it");
                return d;
            }
            mem.record(d.chain(), v.cls(), s, now, minutes);
            String why = "infeasible: " + d.chain() + " (" + v.missing() + (notFound == null ? "" : "; no plan: " + notFound) + ") - skipped " + minutes + " min";
            env.log("brain: " + why);
            ctx.skipped().add(why);
            d = tree.run(ctx);
        }
        return d;
    }

    private void start(BrainTree.Decision d, BrainState s, Needs.Scored sc) {
        String[] comp = compose(d, s);
        String origin = origins.getOrDefault(FailMemory.norm(d.chain()), d.chain());      // 0.26.2: the pick's own chain
        String chain = comp[0];
        String supWhy = comp[2];
        if (d.need() != null && d.need().equals("spawn")) { data().addProperty("spawnTriedAt", env.now()); env.saved(); }      // R6: the cooldown counts tries
        long ref = record(d, s, sc, d.reason() + comp[1]);
        lastLogged = d.branch();
        String reply = env.start(chain);
        if (reply != null && reply.startsWith("started")) {
            job = new Job(d.need(), chain, d.score(), ref, env.now(), origin);
            io.github.mojolowjo.entropybot.summary.DaySummary.INSTANCE.pick(d.need());      // 0.23.6
            if (d.chain().startsWith("restock")) data().addProperty("restockAt", env.now());
            if (d.need() != null && d.need().equals("night")) nightAt = s.botPos;
            env.saved();
            if (d.branch().equals("passive.leave")) env.whisper(BrainTree.LEFT_WHISPER);      // 0.26.1 R5: exactly this
            else if ((!d.chain().equals(lastWhisper) || env.now() - lastWhisperAt > 60_000)      // the same start twice in a minute: once
                    && parkWhisperDue(num(parkWhispers(data()), d.need() == null ? "" : d.need(), 0), env.now()))      // 0.24.3: a need parked in the last hour starts quietly
                env.whisper("brain: " + d.chain() + " (" + d.reason() + ")" + (supWhy == null ? "" : " - " + supWhy));
            lastWhisper = d.chain();
            lastWhisperAt = env.now();
            env.log("brain: " + d.branch() + " -> " + chain + " (" + d.reason() + ")");
        } else {
            env.decisions().outcome(env.now(), ref, "failed:" + reply);
            // counted like a job that failed at once (3 in a row park it)
            failOnce(new Job(d.need(), chain, d.score(), ref, env.now(), origin), reply);
        }
    }

    /** 0.24.3: a parked need whispers at most once an hour (check keeps listing it). */
    static final long PARK_WHISPER_MS = 3_600_000L;

    static boolean parkWhisperDue(long lastAt, long now) {
        return lastAt <= 0 || now - lastAt >= PARK_WHISPER_MS;
    }

    private static JsonObject parkWhispers(JsonObject d) {
        if (!d.has("parkWhispered") || !d.get("parkWhispered").isJsonObject()) d.add("parkWhispered", new JsonObject());
        return d.getAsJsonObject("parkWhispered");
    }

    private void parkWhisper(JsonObject d, String need, long now, String text) {
        JsonObject w = parkWhispers(d);
        if (!parkWhisperDue(num(w, need, 0), now)) {
            env.log("brain: parked " + need + " quietly (whispered within the hour): " + text);
            return;
        }
        w.addProperty(need, now);
        env.whisper(text);
    }

    private void failOnce(Job j, String reply) {
        JsonObject d = data();
        if (!d.has("fails") || !d.get("fails").isJsonObject()) d.add("fails", new JsonObject());
        JsonObject fails = d.getAsJsonObject("fails");
        int n = (int) num(fails, j.need(), 0) + 1;
        fails.addProperty(j.need(), n);
        if (n >= cfg.i("parkFailures")) {
            if (!d.has("parked") || !d.get("parked").isJsonObject()) d.add("parked", new JsonObject());
            JsonObject p = new JsonObject();
            p.addProperty("until", env.now() + cfg.i("parkMinutes") * 60_000L);
            p.addProperty("why", String.valueOf(reply));
            d.getAsJsonObject("parked").add(j.need(), p);
            fails.addProperty(j.need(), 0);
            parkWhisper(d, j.need(), env.now(), "brain: can't " + j.chain() + " - " + reply + "; I leave it for " + cfg.i("parkMinutes") + " min");
        }
        env.saved();
    }

    private long record(BrainTree.Decision d, BrainState s, Needs.Scored sc, String reason) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (Needs.Option o : sc.options()) scores.put(o.need(), o.score());
        String line = Texts.ago(env.now()) + d.branch() + (d.chain() != null ? ": " + d.chain() : "") + " because " + reason
                + (lastSkipped.isEmpty() ? "" : " (skipped: " + String.join("; ", lastSkipped) + ")");
        whys.addLast(line);
        while (whys.size() > 5) whys.pollFirst();
        return env.decisions().decision(env.now(), env.tick(), d.branch(), scores, d.chain(), reason, s.summary(), lastSkipped, lastFeas);
    }

    /** Small text helpers. */
    static final class Texts {
        static String ago(long now) {
            java.time.LocalTime t = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalTime();
            return String.format("%02d:%02d:%02d ", t.getHour(), t.getMinute(), t.getSecond());
        }
    }

    /** brain.json: the branch taken, the scores, the last why (B4's page). */
    public JsonObject stateJson(BrainState s) {
        JsonObject o = new JsonObject();
        o.addProperty("t", env.now());
        o.addProperty("on", on());
        o.addProperty("held", num(data(), "heldUntil", 0) > env.now());
        if (last != null) {
            o.addProperty("branch", last.branch());
            o.addProperty("kind", last.kind().name().toLowerCase());
            o.addProperty("chosen", last.chain());
            o.addProperty("reason", last.reason());
        }
        JsonObject sc = new JsonObject();
        if (lastScored != null) for (Needs.Option x : lastScored.options()) sc.addProperty(x.need(), x.score());
        o.add("scores", sc);
        JsonArray sk = new JsonArray();
        lastSkipped.forEach(sk::add);      // 0.26.1 R1: the tree's skips first
        if (lastScored != null) lastScored.skipped().forEach(sk::add);
        o.add("skipped", sk);
        JsonArray ev = new JsonArray();
        lastEvents.forEach(e -> ev.add(e.name().toLowerCase()));
        o.add("events", ev);
        if (job != null) {
            JsonObject j = new JsonObject();
            j.addProperty("need", job.need());
            j.addProperty("chain", job.chain());
            j.addProperty("since", job.at());
            o.add("job", j);
        }
        o.addProperty("stage", s == null ? null : s.stage);
        o.addProperty("loopsPerMin", loopsPerMinute(env.now()));
        o.addProperty("lastLoopMs", Math.round(lastLoopMs * 100) / 100.0);
        double[] cm = costMinute(env.now());      // 0.26.1: the snapshot cost counter
        o.addProperty("loopMsAvg", r2(cm[0]));
        o.addProperty("loopMsMax", r2(cm[1]));
        o.addProperty("senseMs", r2(lastSenseMs));
        JsonArray ls = new JsonArray();
        lastSkipped.forEach(ls::add);
        o.add("treeSkipped", ls);
        o.addProperty("failMemory", mem.describe(env.now()));
        if (lastFeas != null) o.add("feasibility", lastFeas);      // 0.26.2
        o.addProperty("feasibilityHour", feasibilityLine());
        o.addProperty("errors", errors);
        o.addProperty("why", whys.isEmpty() ? null : whys.peekLast());
        JsonObject p = new JsonObject();
        parked(env.now()).forEach(p::addProperty);
        o.add("parked", p);
        // B4: the path of the branch taken (uids of brain-tree.json) and the need node, for the page's highlight
        JsonArray path = new JsonArray();
        if (last != null) tree.pathTo(last.branch()).forEach(path::add);
        o.add("path", path);
        if (last != null && last.need() != null) {
            o.addProperty("need", last.need());
            o.addProperty("needNode", BrainTreeFile.needNode(last.need()));
        }
        o.addProperty("tree", treeSource);
        o.add("dayPlan", day.json());      // 0.26.3: the brain page's Day Plan card
        return o;
    }

    // ---- B4: settings (brain get|set|reset) and the tree file ----

    /** brain-config.json, else (never saved there yet) commands.json brain.config; configNote names what was ignored. */
    BrainConfig loadConfig() {
        BrainConfig c = BrainConfig.defaults();
        String f = env.readFile(CONFIG_FILE);
        if (f != null && !f.startsWith("error")) {
            try {
                configNote = c.load(com.google.gson.JsonParser.parseString(f).getAsJsonObject());
                BrainConfig.current(c);
                if (!c.migrated().isEmpty()) migrated(c);
                return c;
            } catch (RuntimeException e) {
                configNote = CONFIG_FILE + " unreadable (" + e.getMessage() + ")";
                return BrainConfig.defaults();
            }
        }
        configNote = c.load(data().has("config") && data().get("config").isJsonObject() ? data().getAsJsonObject("config") : null);
        BrainConfig.current(c);
        if (!c.migrated().isEmpty()) migrated(c);
        return c;
    }

    /** 0.26.1 R7: a saved fullInterrupt was migrated: log it once and save the settings without the old key. */
    private void migrated(BrainConfig c) {
        env.log("brain: settings: " + c.migrated());
        String r = env.writeFile(CONFIG_FILE, c.changed().toString());
        if (r != null && r.startsWith("error")) data().add("config", c.changed());
        else data().remove("config");
        env.saved();
    }

    static String canon(String key) {
        for (String k : BrainConfig.DEFAULTS.keySet()) if (k.equalsIgnoreCase(key)) return k;
        return key.toLowerCase();
    }

    String settings(String rest) {
        String[] w = rest.trim().split("\\s+", 3);
        String sub = w[0].toLowerCase();
        BrainConfig c = loadConfig();
        switch (sub) {
            case "get" -> {
                if (w.length < 2) {
                    List<String> parts = new ArrayList<>();
                    c.all().forEach((k, v) -> parts.add(k + " " + v + (v.equals(BrainConfig.DEFAULTS.get(k)) ? "" : "*")));
                    c.weights().forEach((k, v) -> parts.add(k + " " + BrainConfig.num(v) + "*"));
                    return "brain settings (* changed; brain get <key> for one): " + String.join(", ", parts) + " | idle " + String.join(", ", idleList());
                }
                String k = canon(w[1]);
                if (k.equals("idle")) return IdleList.show(idleList());
                if (BrainConfig.renamed(k) != null) return BrainConfig.renamed(k);      // 0.26.1 R7
                String v = c.get(k);
                if (v == null) return "error: no brain setting " + w[1] + " (brain get lists them)";
                int[] r = BrainConfig.RANGES.get(k);
                return k + " = " + v + " (default " + (r == null ? "1" : BrainConfig.DEFAULTS.get(k)) + ", " + (r == null ? "0 to 5" : r[0] + " to " + r[1]) + "): " + BrainConfig.help(k);
            }
            case "set" -> {
                if (w.length < 3) return "usage: brain set <key> <value> (brain get lists the keys)";
                String k = canon(w[1]);
                if (k.equals("idle")) return idle("list set " + w[2]);
                String was = c.get(k);
                String err = c.set(k, w[2]);
                if (err != null) return "error: " + err;
                return saveConfig(c, "ok: " + k + " = " + c.get(k) + " (was " + was + ")");
            }
            case "reset" -> {
                if (w.length < 2) return "usage: brain reset <key>|all";
                if (w[1].equalsIgnoreCase("all")) return saveConfig(BrainConfig.defaults(), "ok: every brain setting is back to its default");
                String k = canon(w[1]);
                if (k.equals("idle")) return idle("list reset");
                if (!c.reset(k)) return "error: no brain setting " + w[1] + " (brain get lists them)";
                return saveConfig(c, "ok: " + k + " = " + c.get(k) + " (the default)");
            }
            default -> {
                return "usage: brain get [key] | brain set <key> <value> | brain reset <key>|all";
            }
        }
    }

    private String saveConfig(BrainConfig c, String ok) {
        String r = env.writeFile(CONFIG_FILE, c.changed().toString());
        String tail = "";
        if (r != null && r.startsWith("error")) {
            data().add("config", c.changed());      // the file can't be written: kept in commands.json instead
            tail = " (saved in commands.json: " + CONFIG_FILE + " " + r + ")";
        } else data().remove("config");
        env.saved();
        cfg = c;
        BrainConfig.current(c);
        exportTree();
        return ok + tail;
    }

    /** "brain tree [status|reload]". */
    String treeCommand(String rest) {
        String t = rest.trim().toLowerCase();
        if (t.equals("reload")) {
            reloadTree();
            return (treeRefused ? "error: " : "ok: ") + treeNote + "; brain-tree.json written" + (exportErrors > 0 ? " (" + exportErrors + " write errors)" : "");
        }
        if (t.isEmpty() || t.equals("status")) return "brain tree: " + treeSource + " - " + treeNote;
        return "usage: brain tree [status|reload]";
    }

    /** Loads brain-tree.override.json (refused: the built-in tree, a check line), then exports. */
    void reloadTree() {
        treeLoaded = true;
        treeRefused = false;
        String f = env.readFile(BrainTreeFile.OVERRIDE);
        if (f == null) {
            tree = new BrainTree();
            treeSource = "built-in";
            treeNote = "the built-in tree (no " + BrainTreeFile.OVERRIDE + ")";
        } else if (f.startsWith("error")) {
            refuse(f);
        } else {
            BrainTreeFile.Result r = BrainTreeFile.parse(f);
            if (r.ok()) {
                tree = r.tree();
                treeSource = "override";
                treeNote = "the tree from " + BrainTreeFile.OVERRIDE;
                env.log("brain: tree loaded from " + BrainTreeFile.OVERRIDE);
            } else {
                List<String> e = r.errors();
                refuse(String.join("; ", e.size() > 5 ? e.subList(0, 5) : e) + (e.size() > 5 ? " (+" + (e.size() - 5) + " more)" : ""));
            }
        }
        exportTree();
    }

    private void refuse(String why) {
        tree = new BrainTree();
        treeSource = "built-in";
        treeRefused = true;
        treeNote = BrainTreeFile.OVERRIDE + " refused (" + why + ") - using the built-in tree";
        env.log("brain: " + treeNote);
    }

    /** brain-tree.json, generated from the running tree; failures counted. */
    void exportTree() {
        try {
            JsonObject o = BrainTreeFile.export(tree, cfg, idleList(), treeSource, treeNote, env.now(), env.modVersion());
            String r = env.writeFile(BrainTreeFile.EXPORT, o.toString());
            if (r == null || r.startsWith("error")) exportErrors++;
        } catch (RuntimeException e) {
            exportErrors++;
            env.log("brain: brain-tree.json not written: " + e);
        }
    }

    public String treeSource() { return treeSource; }

    public String treeNote() { return treeNote; }

    /** For tests: the running job's chain or null. */
    public String jobChain() { return job == null ? null : job.chain(); }

    public BrainTree.Decision lastDecision() { return last; }

    public BrainTree tree() { return tree; }

}
