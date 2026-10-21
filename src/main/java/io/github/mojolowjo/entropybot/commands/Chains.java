package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import io.github.mojolowjo.entropybot.Quiet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.commands.JobRequests.Request;
import io.github.mojolowjo.entropybot.memory.Limits;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chains, routines, {@code repeat}, runs that survive a restart, rules and the death policy (B7a: the bridge's B6
 * code in Java, same wording): the owner's own automations. rm-execution: AA's options never hand text to it (they run
 * on typed steps, StepRun); while AA is on a rule starts nothing (the door refuses it, rulesTick does not even try). A
 * step that does not end ok ends the chain with its stage and reason: no retry, no detour, no soft step. Everything
 * persistent lives in the commands store's object (routines, rules, run, deaths, deathPolicy, lastDeath). Talks to the
 * game only through {@link Env}, so JUnit drives it with a fake.
 */
public final class Chains {
    public static final long FOREVER = Long.MAX_VALUE; // structural
    static long runResumeMs() { return Tuning.l("chains.runResumeMs"); }

    /** What the command core gives the chains. */
    public interface Env {
        long tick();

        long now();

        ZoneId zone();

        String owner();

        void whisper(String to, String text);

        /** 0.33.0: a chain's end, with its own status (failed or not) for the reply shape. */
        default void whisperEnd(String to, String text, boolean failed) { whisper(to, text); }

        void log(String line);

        /** Runs one command line; a step that starts a job comes back pending (its request). */
        Reply dispatch(String from, String text, boolean internal, JobRequests.Listener l);

        /** B7e N: a line the bot sends itself (the corpse fetch): like a typed one, but it never cancels the owner's pending confirm. */
        default Reply dispatchAuto(String from, String text, JobRequests.Listener l) { return dispatch(from, text, false, l); }

        boolean alive();

        /** A reflex holds jobs still (eating, fighting...). */
        boolean holding();

        /** Fighting, fleeing or retreating (the chain waits before a retry). */
        boolean fighting();

        float health();

        /** A job's request is open. */
        boolean busy();

        int freeSlots();

        int bagRoom();

        Map<String, Integer> inventory();

        JsonObject supplies();

        String orePrefer();

        /** The "mine" place or null. */
        JsonObject minePlace();

        boolean workAllowed(String dim, int x, int z);

        String dim();

        void saved();

        /** Wave 1: where the bot stands {x, y, z}, or null (a retry after a fight walks back first). */
        default int[] pos() { return null; }

        /** C4: the level's day time (ticks; % 24000 = time of day), -1 when unknown ("rule when night|day"). */
        default long dayTime() { return -1; }
    }

    /** A command's answer now (text, maybe null) or later (pending). */
    public record Reply(String text, Request pending) {
        public static Reply now(String text) { return new Reply(text, null); }
    }

    static final class Chain {
        /** AA_CLEANUP 2: who started it; its steps run in that driver scope. */
        final io.github.mojolowjo.entropybot.aa.door.Driver driver = io.github.mojolowjo.entropybot.aa.door.Door.current();
        String name, text, from, lastOk;
        List<String> steps;
        int idx;
        long rounds, round, roundStart;
        /** AA's danger pause (holdFor): no next step before this tick. */
        long holdUntil;
        boolean waiting, replyHandled;
        Request pending;
    }

    private final Env env;
    private final JsonObject mem;
    private Chain chain;
    private Chain deathResume;
    private boolean runResumeChecked, corpsePending, corpseRun;
    private long aliveSince = -1, idleSince = -1;
    private final java.util.Map<String, String> endsByName = new java.util.HashMap<>();
    /** 0.30.5: the chain names whose last end is kept (takeEnd). */
    static final java.util.Set<String> TRACKED_ENDS = java.util.Set.of("climb");

    /** 0.30.5: how the last chain of that name ended (taken: the next call returns null until another one ends). */
    public String takeEnd(String name) { return endsByName.remove(name); }

    /** 0.30.5: is a chain of that name running now? */
    public boolean runningNamed(String name) { return chain != null && chain.name.equals(name); }

    /** 0.30.5: a note added to a chain's end (the stair chains' honest "couldn't get there"); null = none. */
    java.util.function.BiFunction<String, String, String> endNote;

    public Chains(Env env, JsonObject mem) {
        this.env = env;
        this.mem = mem;
    }

    public boolean running() { return chain != null; }

    /** The running chain's name, or null (0.24.2: assist knows its own). */
    public String runningName() { Chain c = chain; return c == null ? null : c.name; }

    /** 0.24.2: assist's chains end quietly (it whispers only on an activity change) and never resume after a restart. */
    static boolean quiet(String name) { return "assist".equals(name); }

    /** 0.23.3: the chain's next step's text (its walk is pre-planned while the current step finishes), or null. */
    public String nextStep() {
        Chain c = chain;
        return c == null || c.idx >= c.steps.size() ? null : c.steps.get(c.idx);
    }

    /** 0.28.1: the running chain's steps not done yet (the junk drop keeps what a queued build wants); empty when none. */
    public java.util.List<String> restSteps() {
        Chain c = chain;
        if (c == null) return java.util.List.of();
        int from = Math.max(0, c.idx - 1);
        if (from >= c.steps.size()) return java.util.List.of();
        return new java.util.ArrayList<>(c.steps.subList(from, c.steps.size()));
    }

    public String name() { return chain == null ? null : chain.name; }

    /** 0.36.2 (AA review 6): no next step (or retry) for the next {@code ticks} ticks: AA's danger pause holds the chain. */
    public void holdFor(long ticks) {
        if (chain != null) chain.holdUntil = Math.max(chain.holdUntil, env.tick() + ticks);
    }

    /** AA_STEP3 B (slow mode): asked before a step of a chain is dispatched; true = hold it this tick. */
    public interface StepGate { boolean hold(String chain, int idx, String step); }

    private StepGate stepGate;

    public void stepGate(StepGate g) { stepGate = g; }

    /** The running chain's whole step list (a copy), empty when none: slow mode shows it as the plan. */
    public List<String> stepsNow() {
        Chain c = chain;
        return c == null ? List.of() : new ArrayList<>(c.steps);
    }

    /** rm-execution (rule 7): a gate that throws ends the chain with that stage and reason (never a step run unchecked). */
    private boolean gateHolds(Chain c) {
        try {
            return stepGate.hold(c.name, c.idx, c.steps.get(c.idx));
        } catch (RuntimeException e) {
            endChain("stopped at step " + (c.idx + 1) + " (" + c.steps.get(c.idx) + "): the step gate failed: " + e, true);
            return true;
        }
    }

    /** B1: told how every chain ends (name, message); 0.36.0 (AA P2): a fan-out, the brain and AA both listen. */
    private final List<java.util.function.BiConsumer<String, String>> endHooks = new ArrayList<>();

    /** Adds a listener for every chain's end (name, message). A listener that throws is logged, the others still run. */
    public void onEnd(java.util.function.BiConsumer<String, String> hook) { endHooks.add(hook); }

    /** For tests: how many end listeners are set. */
    int endHookCount() { return endHooks.size(); }

    private void fireEnd(String name, String msg) {
        for (java.util.function.BiConsumer<String, String> h : endHooks) {
            try {
                h.accept(name, msg);
            } catch (RuntimeException e) {
                env.log("chain " + name + ": an end listener failed: " + e);
            }
        }
    }

    /** B1: a corpse trip is due or runs (the brain starts nothing before it). */
    public boolean corpseBusy() { return corpsePending || corpseRun; }

    // ---- chains ----

    public static boolean forever(long rounds) { return rounds == FOREVER; }

    List<String> expandSteps(List<String> steps, int depth) {
        List<String> out = new ArrayList<>();
        JsonObject routines = routines();
        for (String s : steps) {
            String name = s.toLowerCase();
            if (routines.has(name) && depth < 3) out.addAll(expandSteps(Texts.splitChain(routines.get(name).getAsString()), depth + 1));
            else out.add(s);
        }
        return out;
    }

    public JsonObject routines() {
        if (!mem.has("routines") || !mem.get("routines").isJsonObject()) mem.add("routines", new JsonObject());
        return mem.getAsJsonObject("routines");
    }

    public boolean isRoutine(String name) { return routines().has(name.toLowerCase()); }

    public String startChain(String from, String name, String text, long rounds) {
        if (!io.github.mojolowjo.entropybot.aa.door.Door.allow("chain " + name)) return io.github.mojolowjo.entropybot.aa.door.Door.refusal("chain " + name);
        List<String> steps = expandSteps(Texts.splitChain(text), 0);
        if (steps.isEmpty()) return "error: nothing to do";
        String tooLong = stepsRefusal(steps.size());
        if (tooLong != null) return tooLong;
        Chain c = new Chain();
        c.name = name;
        c.text = text;
        c.steps = steps;
        c.rounds = rounds;
        c.round = 1;
        c.from = from;
        c.roundStart = env.tick();
        chain = c;
        saveRun();
        return "started: " + name + " - " + Limits.stepsText(steps)
                + (rounds > 1 ? (forever(rounds) ? " (repeating until \"stop\")" : " (" + rounds + " times)") : "");
    }

    /** Package H: a chain (routines expanded) of more than {@link Limits#CHAIN_STEPS} steps is refused. */
    static String stepsRefusal(int steps) {
        return steps <= Limits.CHAIN_STEPS ? null
                : "error: that is " + steps + " steps with the routines in it, the most I run in one chain is " + Limits.CHAIN_STEPS
                + " - make it shorter (\"repeat <n> <routine>\" repeats one without the copies)";
    }

    /** Package H: a routine or rule body longer than {@link Limits#ROUTINE_TEXT} characters, or too many steps, is refused. */
    String bodyRefusal(String body) {
        if (body.length() > Limits.ROUTINE_TEXT) return "error: that is " + body.length() + " characters, the most I keep for one is " + Limits.ROUTINE_TEXT;
        return stepsRefusal(expandSteps(Texts.splitChain(body), 0).size());
    }

    /** A chain's end; failed unless msg is the "done" end (the callers below say which). */
    void endChain(String msg) { endChain(msg, !msg.startsWith("done")); }

    /** 0.33.0 review: the end with its own status (the end note may rewrite the text, never the status). */
    void endChain(String msg, boolean failed) {
        if (chain == null) return;
        if (endNote != null) {
            try {
                String n = endNote.apply(chain.name, msg);
                if (n != null) msg = n;
            } catch (RuntimeException e) {
                env.log("chain " + chain.name + ": the end note failed: " + e);
            }
        }
        if (!quiet(chain.name)) env.whisperEnd(chain.from, chain.name + ": " + msg, failed);
        env.log("chain " + chain.name + ": " + msg);
        if (TRACKED_ENDS.contains(chain.name)) endsByName.put(chain.name, msg);   // 0.30.5: climb counts its own failures
        fireEnd(chain.name, msg);                                      // B1: the brain (and AA) hear how the chain ended
        chain = null;
        clearRun();
    }

    /** "stop": the chain goes, quietly, and so do the saved run and a chain set aside by a death. */
    public String clear() {
        String n = chain == null ? null : chain.name;
        if (n != null && !io.github.mojolowjo.entropybot.aa.door.Door.allow("stop the chain " + n)) return null;
        chain = null;
        deathResume = null;
        clearRun();
        return n;
    }

    /** 0.36.1: AA's chain is never saved or resumed (AA chooses again after a restart). */
    static final String AA_CHAIN = "aa";

    void saveRun() {
        if (chain == null || quiet(chain.name) || AA_CHAIN.equals(chain.name)) return;
        JsonObject r = new JsonObject();
        r.addProperty("name", chain.name);
        r.addProperty("text", chain.text);
        if (forever(chain.rounds)) r.addProperty("rounds", "forever");
        else r.addProperty("rounds", chain.rounds);
        r.addProperty("round", chain.round);
        r.addProperty("idx", chain.waiting || chain.pending != null ? chain.idx - 1 : chain.idx);
        r.addProperty("from", chain.from);
        r.addProperty("savedAt", env.now());
        mem.add("run", r);
        env.saved();
    }

    void clearRun() {
        if (mem.has("run") && !mem.get("run").isJsonNull()) {
            mem.add("run", JsonNull.INSTANCE);
            env.saved();
        }
    }

    /** Once, a little after the bot is in the world: carry on with the run a restart cut short. */
    public void resumeRun() {
        if (runResumeChecked) return;
        if (env.busy()) return;                       // something runs (a PM right after the start): look again later
        runResumeChecked = true;
        JsonObject r = obj(mem, "run");
        if (r == null || chain != null) return;
        long age = env.now() - num(r, "savedAt", 0);
        String name = str(r, "name", "run");
        if (age > runResumeMs()) {
            env.whisper(env.owner(), "I had \"" + name + "\" running " + Math.round(age / 3600000.0) + " h ago - too long ago to carry on by myself (PM it again)");
            clearRun();
            return;
        }
        String roundsText = r.has("rounds") ? r.get("rounds").getAsString() : "1";
        long rounds = "forever".equals(roundsText) ? FOREVER : Math.max(1, parseLong(roundsText, 1));
        String s = startChain(str(r, "from", env.owner()), name, str(r, "text", ""), rounds);
        if (!s.startsWith("started") || chain == null) {
            // package H: a run the step cap refuses now (a routine in it grew) is dropped, and the owner hears why
            env.whisper(str(r, "from", env.owner()), "I won't carry on with \"" + name + "\" after the restart: " + s.replaceFirst("^error: ", ""));
            clearRun();
            return;
        }
        chain.round = Math.max(1, num(r, "round", 1));
        chain.idx = (int) Math.min(Math.max(num(r, "idx", 0), 0), chain.steps.size());
        saveRun();
        env.whisper(chain.from, "carrying on with " + name + " (round " + chain.round + ", step " + (chain.idx + 1) + ": "
                + (chain.idx < chain.steps.size() ? chain.steps.get(chain.idx) : "the next round") + ") after a " + (age < 120000 ? "reload" : "restart"));
    }

    public void rearmResume() { runResumeChecked = false; }

    public boolean resumeChecked() { return runResumeChecked; }

    /** 0.36.1: with AA on, a saved run is dropped with a line instead of resumed (AA's slot decides what runs). */
    public void dropRun(String why) {
        runResumeChecked = true;
        JsonObject r = obj(mem, "run");
        if (r == null) return;
        String name = str(r, "name", "run");
        env.log("chain " + name + ": the saved run is dropped (" + why + ")");
        if (!AA_CHAIN.equals(name)) env.whisper(str(r, "from", env.owner()), "I won't carry on with \"" + name + "\" after the restart: " + why + " (PM it again)");
        clearRun();
    }

    /** Every 5 ticks: starts the next step once the last one has finished. */
    /**
     * 0.24.3: a step that is a chain of its own (bootstrap) puts its steps right after itself in the running chain.
     * Null when done, else why not (no chain, too many steps).
     */
    public String spliceNext(String text) {
        Chain c = chain;
        if (c == null) return "no chain runs";
        List<String> add = expandSteps(Texts.splitChain(text), 0);
        String tooLong = stepsRefusal(c.steps.size() + add.size());
        if (tooLong != null) return tooLong;
        List<String> all = new ArrayList<>(c.steps);
        all.addAll(Math.min(c.idx, all.size()), add);
        c.steps = all;
        saveRun();
        return null;
    }

    public void stepChain() {
        Chain dc = chain;
        try (var s = io.github.mojolowjo.entropybot.aa.door.Door.scope(dc == null ? io.github.mojolowjo.entropybot.aa.door.Door.current() : dc.driver)) {
            stepChainIn();
        }
    }

    private void stepChainIn() {
        Chain c = chain;
        if (c == null || !env.alive()) return;
        if (c.pending != null) {
            Request p = c.pending;
            if (p.replied && !c.replyHandled) {
                c.replyHandled = true;
                if (Texts.stepFailed(p.reply)) {
                    c.pending = null;
                    endChain("stopped at step " + c.idx + " (" + c.steps.get(c.idx - 1) + "): " + String.valueOf(p.reply).replaceFirst("^error: ", ""), true);
                    return;
                }
                if (!p.started) {                              // an instant step: the next one on the next call
                    c.pending = null;
                    saveRun();
                    return;
                }
                c.waiting = true;
                saveRun();
            }
            if (!p.finished) return;                           // its job still runs
            c.pending = null;
            c.waiting = false;
            String st = p.doneMsg == null ? "" : p.doneMsg;
            // rm-execution (rule 7): a step that did not end ok ends the chain with its stage and reason (no retry, no skip)
            if (!st.startsWith("ok") && !st.startsWith("done")) {
                endChain("stopped at step " + c.idx + " (" + c.steps.get(c.idx - 1) + "): " + st, true);
                return;
            }
            c.lastOk = st.replaceFirst("^(ok|done): ", "");
        }
        if (c.holdUntil > 0) {
            if (env.tick() < c.holdUntil) return;
            c.holdUntil = 0;
        }
        String said = c.lastOk != null ? " - " + (c.lastOk.length() > 160 ? c.lastOk.substring(0, 160) + "..." : c.lastOk) : "";
        if (c.idx >= c.steps.size()) {
            if (c.round >= c.rounds) {
                endChain("done" + (c.rounds > 1 ? " (" + c.rounds + " rounds)" : "") + said, false);
                return;
            }
            if (env.tick() - c.roundStart < 200) return;      // a round of instant steps: not faster than every 10 s
            env.whisper(c.from, c.name + ": round " + c.round + " done" + (forever(c.rounds) ? "" : " of " + c.rounds) + said);
            c.lastOk = null;
            c.round++;
            c.idx = 0;
            c.roundStart = env.tick();
        }
        if (stepGate != null && gateHolds(c)) return;         // AA_STEP3 B: slow mode holds the step; asked again next tick
        String step = c.steps.get(c.idx++);
        Reply r;
        try {
            r = env.dispatch(c.from, step, true, null);
        } catch (RuntimeException e) {
            r = Reply.now("error: " + e);
        }
        if (chain != c) return;                                // the step was "stop"
        if (r.pending() != null) {
            c.pending = r.pending();
            c.replyHandled = false;
            saveRun();
            return;
        }
        if (Texts.stepFailed(r.text())) {
            endChain("stopped at step " + c.idx + " (" + step + "): " + String.valueOf(r.text()).replaceFirst("^error: ", ""), true);
            return;
        }
        saveRun();                                             // where it is, for a resume after a restart
    }



    /** 0.26.3: the running chain's step, "3/7 craft iron_pickaxe 1" (the day plan's why and progress), null when none. */
    public String stepText() {
        Chain c = chain;
        if (c == null || c.steps.isEmpty()) return null;
        int i = Math.max(1, Math.min(c.idx, c.steps.size()));
        return i + "/" + c.steps.size() + " " + c.steps.get(i - 1);
    }

    public String chainStatus() {
        Chain c = chain;
        if (c == null) return "nothing queued";
        return c.name + ": step " + Math.min(c.idx, c.steps.size()) + "/" + c.steps.size() + " (" + c.steps.get(Math.max(c.idx - 1, 0)) + ")"
                + (c.rounds > 1 ? ", round " + c.round + (forever(c.rounds) ? "" : "/" + c.rounds) : "")
                + (c.idx < c.steps.size() ? ", next: " + c.steps.get(c.idx) : "");
    }

    /** "routine save <name> <chain>", "routine delete <name>", "routine show <name>", "routines". */
    public String routineCommand(String rest) {
        List<String> p = Texts.words(rest);
        String sub = p.isEmpty() ? "" : p.get(0).toLowerCase(), name = p.size() > 1 ? p.get(1).toLowerCase() : "";
        JsonObject routines = routines();
        if (sub.equals("save") || sub.equals("add")) {
            if (!name.matches("^[a-z0-9_-]{1,20}$")) return "usage: routine save <name> <command> then <command> ...";
            if (Texts.isBuiltin(name)) return "error: \"" + name + "\" is already a command - pick another name";
            String body = String.join(" ", p.subList(Math.min(2, p.size()), p.size()));
            if (Texts.splitChain(body).isEmpty()) return "usage: routine save " + name + " <command> then <command> ...";
            // package H: at most Limits.ROUTINES routines, each at most ROUTINE_TEXT characters and CHAIN_STEPS steps
            String refused = Limits.full(routines.has(name), routines.size(), Limits.ROUTINES, "routines", "delete one first (routine delete <name>; \"routines\" lists them)");
            if (refused == null) refused = bodyRefusal(body);
            if (refused != null) return refused;
            routines.addProperty(name, body);
            env.saved();
            return "saved routine " + name + ": " + Limits.stepsText(Texts.splitChain(body)) + " (PM \"" + name + "\" to run it)";
        }
        if (sub.equals("delete") || sub.equals("remove") || sub.equals("forget")) {
            if (!routines.has(name)) return "I have no routine called " + name + " - next: routines";
            routines.remove(name);
            env.saved();
            return "deleted routine " + name;
        }
        if (sub.equals("show")) {
            return routines.has(name) ? name + ": " + String.join(" > ", Texts.splitChain(routines.get(name).getAsString())) : "I have no routine called " + name + " - next: routines";
        }
        List<String> list = new ArrayList<>(routines.keySet());
        return list.isEmpty() ? "no routines yet - routine save <name> <command> then <command> ..." : "routines: " + String.join(", ", list) + " (routine show <name>)";
    }

    // ---- the death policy ----

    /** The bot died: count it and plan the corpse trip; a running chain is set aside (AA's deaths are AA's to price). */
    public void noteDeath() {
        long now = env.now();
        JsonArray kept = recentDeaths(now);
        kept.add(now);
        mem.add("deaths", kept);
        if (chain != null) {
            Chain c = chain;
            c.idx = Math.max(c.waiting || c.pending != null ? c.idx - 1 : c.idx, 0);
            c.waiting = false;
            c.pending = null;
            c.holdUntil = 0;
            c.lastOk = null;
            deathResume = c;
            chain = null;                                      // quietly: it carries on after the corpse
        }
        corpsePending = !(mem.has("deathPolicy") && !mem.get("deathPolicy").getAsBoolean());
        env.saved();
    }

    JsonArray recentDeaths(long now) {
        JsonArray out = new JsonArray();
        if (mem.has("deaths") && mem.get("deaths").isJsonArray()) {
            for (JsonElement e : mem.getAsJsonArray("deaths")) {
                try {
                    if (now - e.getAsLong() < 3600000) out.add(e.getAsLong());
                } catch (RuntimeException caught) { Quiet.warn("Chains.recentDeaths failed, carrying on with the fallback", caught); }
            }
        }
        return out;
    }

    /** Every 20 ticks: after a respawn (alive 5 s, health 14+), fetch the corpse, then carry on with the chain. */
    public void deathTick(boolean dead) {
        if (dead) {
            aliveSince = -1;
            return;
        }
        if (aliveSince < 0) aliveSince = env.tick();
        if (corpseRun && !env.busy()) {
            corpseRun = false;
            if (deathResume != null && chain == null) {
                chain = deathResume;
                chain.roundStart = env.tick();
                env.whisper(chain.from, "back from my corpse - carrying on with " + chain.name);
                saveRun();
            }
            deathResume = null;
            return;
        }
        if (!corpsePending || env.tick() - aliveSince < 100 || env.health() < 14 || env.busy() || env.holding()) return;
        corpsePending = false;
        JsonObject d = obj(mem, "lastDeath");
        String why = null;
        if (d == null) why = "I have no note of where";
        else if (d.has("dim") && !str(d, "dim", "").equals(env.dim())) why = "it is in " + str(d, "dim", "");
        else if (num(d, "y", 0) < -60) why = "it is below y -60";
        if (why != null) {
            env.whisper(env.owner(), "I won't fetch my corpse: " + why + " (" + (d == null ? "?" : num(d, "x", 0) + " " + num(d, "y", 0) + " " + num(d, "z", 0)) + ")");
            corpseRun = true;                                  // the chain still carries on (next deathTick)
            return;
        }
        corpseRun = true;
        Reply r = env.dispatchAuto(env.owner(), "death", q -> {
            if (!JobRequests.quiet(q.doneMsg)) env.whisper(env.owner(), q.doneMsg.replaceFirst("^ok: ", ""));
        });
        if (r.pending() == null && (r.text() == null || !r.text().startsWith("started"))) env.whisper(env.owner(), "couldn't go for my corpse: " + r.text());
    }



    /** "deaths", "death policy on|off". */
    public String deathsCommand(String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase();
        int n = recentDeaths(env.now()).size();
        if (t.matches("^policy (on|off)$")) {
            mem.addProperty("deathPolicy", t.endsWith("on"));
            env.saved();
        }
        boolean off = mem.has("deathPolicy") && !mem.get("deathPolicy").getAsBoolean();
        return n + " death" + (n == 1 ? "" : "s") + " in the last hour; fetching my corpse after a death is " + (off ? "OFF" : "on")
                + io.github.mojolowjo.entropybot.threat.HitLog.INSTANCE.deathText(java.time.ZoneId.systemDefault());     // 0.24.4
    }

    // ---- rules ----

    private static final Pattern RULE_EVERY = Pattern.compile("^(every)\\s+(\\d+)(m|h)\\s+do\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RULE_AT = Pattern.compile("^(at)\\s+(\\d{1,2}):(\\d{2})\\s+do\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RULE_FULL = Pattern.compile("^(when)\\s+(full)()\\s+do\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RULE_IDLE = Pattern.compile("^(when)\\s+idle\\s+(\\d+)(m)\\s+do\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    /** C4: "rule when night do sleep", "rule when day do ...": once per night (or day). */
    private static final Pattern RULE_DAYNIGHT = Pattern.compile("^(when)\\s+(night|day)()\\s+do\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    JsonArray rules() {
        if (!mem.has("rules") || !mem.get("rules").isJsonArray()) mem.add("rules", new JsonArray());
        return mem.getAsJsonArray("rules");
    }

    /** "rule every 30m do <chain>", "rule at 06:30 do ...", "rule when full do ...", "rule when idle 10m do ...", "rules", "rule delete <n>". */
    public String ruleCommand(String rest) {
        String t = rest == null ? "" : rest.trim();
        JsonArray list = rules();
        if (t.isEmpty() || t.equalsIgnoreCase("list")) {
            if (list.isEmpty()) return "no rules - e.g. rule every 30m do farm, rule at 06:30 do deposit, rule when full do deposit, rule when idle 10m do restock";
            List<String> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                JsonObject x = list.get(i).getAsJsonObject();
                out.add("#" + (i + 1) + " " + str(x, "kind", "") + " " + str(x, "arg", "") + " do " + str(x, "text", ""));
            }
            return String.join(" | ", out);
        }
        Matcher d = Pattern.compile("^(delete|remove)\\s+(\\d+)$", Pattern.CASE_INSENSITIVE).matcher(t);
        if (d.find()) {
            int i = Integer.parseInt(d.group(2)) - 1;
            if (i < 0 || i >= list.size()) return "I have no rule #" + d.group(2);
            JsonObject r = list.remove(i).getAsJsonObject();
            env.saved();
            return "deleted rule: " + str(r, "kind", "") + " " + str(r, "arg", "") + " do " + str(r, "text", "");
        }
        Matcher m = null;
        for (Pattern p : new Pattern[]{RULE_EVERY, RULE_AT, RULE_FULL, RULE_IDLE, RULE_DAYNIGHT}) {
            Matcher x = p.matcher(t);
            if (x.find()) {
                m = x;
                break;
            }
        }
        if (m == null) return "usage: rule every <n>m|h do <commands> | rule at HH:MM do ... | rule when full do ... | rule when idle <n>m do ... | rule when night|day do ... | rules | rule delete <n>";
        String kind = m.group(1).toLowerCase(), arg, text = m.group(4);
        if (expandSteps(Texts.splitChain(text), 0).isEmpty()) return "error: nothing to do";
        // package H: at most Limits.RULES rules ("rules" lists each one whole)
        String refused = Limits.full(false, list.size(), Limits.RULES, "rules", "delete one first (rule delete <n>; \"rules\" lists them)");
        if (refused == null) refused = bodyRefusal(text);
        if (refused != null) return refused;
        if (kind.equals("every")) arg = m.group(2) + m.group(3).toLowerCase();
        else if (kind.equals("at")) arg = ("0" + m.group(2)).substring(("0" + m.group(2)).length() - 2) + ":" + m.group(3);
        else if (m.pattern() == RULE_DAYNIGHT) arg = m.group(2).toLowerCase();
        else arg = t.toLowerCase().contains("idle") ? "idle " + m.group(2) + "m" : "full";
        JsonObject r = new JsonObject();
        r.addProperty("kind", kind);
        r.addProperty("arg", arg);
        r.addProperty("text", text);
        r.addProperty("last", env.now());
        list.add(r);
        env.saved();
        return "ok: rule #" + list.size() + ": " + kind + " " + arg + " do " + String.join(" > ", Texts.splitChain(text));
    }

    /**
     * Every 100 ticks: the first rule that is due starts as a chain (only while nothing else runs). rm-execution: the
     * owner's automations never fire while AA is on (one driver: AA alone chooses then; the door would refuse it too).
     */
    public void rulesTick() {
        if (io.github.mojolowjo.entropybot.aa.door.Door.aaOn()) {
            idleSince = -1;
            return;
        }
        try (var s = io.github.mojolowjo.entropybot.aa.door.Door.scope(io.github.mojolowjo.entropybot.aa.door.Driver.RULE)) {
            rulesTickIn();
        }
    }

    private void rulesTickIn() {
        long now = env.now();
        if (chain != null || env.busy() || env.holding()) {
            idleSince = -1;
            return;
        }
        if (idleSince < 0) idleSince = now;
        JsonArray list = rules();
        for (int i = 0; i < list.size(); i++) {
            JsonObject r = list.get(i).getAsJsonObject();
            String kind = str(r, "kind", ""), arg = str(r, "arg", "");
            long last = num(r, "last", 0);
            boolean due = false;
            if (kind.equals("every")) {
                long n = leadingInt(arg) * (arg.endsWith("h") ? 3600000L : 60000L);
                due = now - last >= n;
            } else if (kind.equals("at")) {
                try {
                    String[] hm = arg.split(":");
                    ZonedDateTime d = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), env.zone()).withSecond(0).withNano(0)
                            .withHour(0).withMinute(0).plusHours(Integer.parseInt(hm[0])).plusMinutes(Integer.parseInt(hm[1]));
                    long at = d.toInstant().toEpochMilli();
                    due = now >= at && last < at;
                } catch (RuntimeException caught) { Quiet.warn("Chains.rulesTickIn failed, carrying on with the fallback", caught); }
            } else if (arg.equals("night") || arg.equals("day")) {
                due = io.github.mojolowjo.entropybot.camp.DayNight.due(arg, env.dayTime(), str(r, "fired", ""));
                if (due) r.addProperty("fired", io.github.mojolowjo.entropybot.camp.DayNight.key(env.dayTime()));
            } else if (arg.equals("full")) {
                due = env.bagRoom() <= 4 && now - last > 120000;
            } else if (arg.startsWith("idle")) {
                long n = leadingInt(arg.split(" ")[1]) * 60000L;
                due = now - idleSince >= n && now - last > n;
            }
            if (!due) continue;
            r.addProperty("last", now);
            env.saved();
            env.log("rule #" + (i + 1) + " (" + kind + " " + arg + ") -> " + str(r, "text", ""));
            String s = startChain(env.owner(), "rule #" + (i + 1), str(r, "text", ""), 1);
            // package H: a rule whose chain is refused (too many steps since a routine in it grew) says so, once
            if (!s.startsWith("started")) {
                env.log("rule #" + (i + 1) + " refused: " + s);
                if (!s.equals(str(r, "refused", ""))) {
                    r.addProperty("refused", s);
                    env.whisper(env.owner(), "rule #" + (i + 1) + " (" + kind + " " + arg + ") didn't start: " + s.replaceFirst("^error: ", ""));
                }
            } else if (r.has("refused")) {
                r.remove("refused");
            }
            idleSince = -1;
            return;
        }
    }

    // ---- small JSON helpers ----

    static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    static String str(JsonObject o, String k, String def) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return def;
        try { return o.get(k).getAsString(); } catch (RuntimeException e) {
            Quiet.warn("Chains.str failed, carrying on with the fallback", e); return def; }
    }

    static long num(JsonObject o, String k, long def) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return def;
        try { return (long) Math.floor(o.get(k).getAsDouble()); } catch (RuntimeException e) {
            Quiet.warn("Chains.num failed, carrying on with the fallback", e); return def; }
    }

    static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (RuntimeException e) {
            Quiet.warn("Chains.parseLong failed, carrying on with the fallback", e); return def; }
    }

    static long leadingInt(String s) {
        Matcher m = Pattern.compile("^\\s*(\\d+)").matcher(s == null ? "" : s);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }
}
