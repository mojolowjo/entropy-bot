package io.github.mojolowjo.entropybot.ai;

import io.github.mojolowjo.entropybot.ai.provider.Provider;
import io.github.mojolowjo.entropybot.ai.provider.ProviderException;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 0.35.0: the AI operator's state machine. Pure Java: every decision (when to think, the caps, the failure counters,
 * the pause after the owner's stop) is here, driven with a time in ms, so JUnit runs it with a fake clock and a fake
 * provider. The adapter only (1) calls {@link #poll} each tick and sends the returned request on the worker, (2)
 * gives the answer back with {@link #onCompletion}/{@link #onFailure} on the tick, (3) runs the returned command and
 * reports with {@link #onCommandResult}. An idle bot with no goal never thinks, so it costs nothing.
 *
 * <p>Not thread-safe: every call is on the game thread (the network runs elsewhere and only hands results over).
 */
public final class AiLoop {
    public enum State { OFF, IDLE, THINKING, RUNNING, WAIT_CONFIRM, PAUSED, COOLDOWN, STOPPED }

    /** Where the loop's words go: whispers to the owner, ai.log, the trial notes. */
    public interface Sink {
        void notice(String text);

        void log(long now, String line);

        void trial(long now, String wanted, String command, String answer);
    }

    /** The game as the loop sees it this tick. busy: a job, chain, gather... is running. */
    public record World(boolean inWorld, boolean busy, boolean dead, java.util.Map<String, Integer> inv) {
        public World(boolean inWorld, boolean busy, boolean dead) { this(inWorld, busy, dead, null); }
    }

    static final long HEARTBEAT_MS = 60_000, EVENT_GAP_MS = 30_000, CONFIRM_WAIT_MS = 60_000, OUTAGE_RETRY_MS = 300_000, CAP_RETRY_MS = 300_000,
            WATCHDOG_EXTRA_MS = 15_000;
    static final long[] BACKOFF_MS = {5_000, 20_000};
    static final int PER_MINUTE = 20, WAITS_BEFORE_NUDGE = 3, SAME_LIMIT = 3;
    public static final String AUTO_GOAL = "(autonomous) get established on your own: shelter or a house, tools, food, then better tools and armour; "
            + "pick the next useful job";

    private final AiSettings s;
    private final CommandGate gate;
    private final PromptBuilder prompt;
    private final Budget budget;
    private final Sink sink;

    private State state = State.OFF;
    private String stopReason, goal, lastState, lastError, trigger, lastCmd, prevCmd, prevReply, cooldownNoticed;
    private boolean want, busyAtSend, nudged, prevBusy, prevDead, autoGoal;
    private long nextAt, lastThinkAt = Long.MIN_VALUE / 2, lastEventAt = Long.MIN_VALUE / 2, sentAt, confirmUntil;
    private long reserved;
    // 0.35.3: per goal: model calls, the bag at the start, each command line's count
    private int goalThinks;
    private java.util.Map<String, Integer> goalStartInv, lastInv;
    private final java.util.Map<String, Integer> goalLines = new java.util.HashMap<>();
    private final java.util.Map<String, String> goalReplies = new java.util.HashMap<>();
    private static final java.util.regex.Pattern ASKS_TO_SAY = java.util.regex.Pattern.compile("(?i)\\bchat\\b|\\b(?:say|tell|announce|greet)\\s+(?:to\\s+)?(?!me\\b|us\\b|the owner\\b)\\S+");
    /** 0.35.4 review: read-only verbs are never a "repeat". */
    
    private int failureStreak, waitStreak, sameCount, lastStatus;
    private final Deque<PromptBuilder.Turn> history = new ArrayDeque<>();
    private final Deque<Long> minute = new ArrayDeque<>();
    private final Deque<String> recentCommands = new ArrayDeque<>();
    private final Set<String> deniedNoticed = new HashSet<>();
    // counters for ai status
    private long thinks, commands, inTok, outTok, latencyMs, r429, timeouts, empties, rejects, denyHits, extraLines;
    private double usd;

    public AiLoop(AiSettings s, CommandGate gate, PromptBuilder prompt, Budget budget, Sink sink) {
        this.s = s;
        this.gate = gate;
        this.prompt = prompt;
        this.budget = budget;
        this.sink = sink;
    }

    public State state() { return state; }

    public String stopReason() { return stopReason; }

    public String goal() { return goal; }

    public String lastError() { return lastError; }

    public long sentAt() { return sentAt; }

    public int failureStreak() { return failureStreak; }

    public boolean on() { return state != State.OFF; }

    // ---- owner controls ----

    /** ai on: from OFF or STOPPED to IDLE (a goal, if one is set, goes on). */
    public void enable(long now) {
        if (state == State.OFF || state == State.STOPPED || state == State.COOLDOWN || state == State.PAUSED) state = State.IDLE;
        stopReason = null;
        failureStreak = 0;
        String p = budget.startProblem();
        if (p != null) stop(p, now);
        else {
            sink.log(now, "on (" + s.provider + " " + s.model + ")");
            if (goal != null) trigger("on");
        }
    }

    /** ai off: nothing more is sent; the goal is dropped. */
    public void disable(long now) {
        state = State.OFF;
        goal = null;
        want = false;
        sink.log(now, "off");
    }

    /** ai stop: stopped until ai on or the owner's next request. */
    public void stopByOwner(long now) {
        if (state == State.OFF) return;
        stop("stopped by the owner (ai stop)", now, false);
    }

    /** The owner's plain stop: the AI pauses until the owner's next request (else it would start the bot again at once). */
    public void ownerStop(long now) {
        if (state == State.OFF || state == State.STOPPED) return;
        state = State.PAUSED;
        want = false;
        sink.log(now, "paused: the owner said stop");
    }

    /** A request from the owner: the new goal. false: the operator is off (the caller says so). */
    public boolean ownerRequest(String text, long now) {
        if (state == State.OFF) return false;
        goal = PromptBuilder.cut(text.trim(), PromptBuilder.GOAL_MAX);
        autoGoal = false;
        newGoal();
        if (state != State.THINKING && state != State.RUNNING && state != State.WAIT_CONFIRM) state = State.IDLE;
        stopReason = null;
        failureStreak = 0;
        waitStreak = 0;
        nudged = false;
        sameCount = 0;
        cooldownNoticed = null;
        nextAt = now;
        trigger("owner");
        sink.log(now, "goal from the owner: " + goal);
        sink.trial(now, goal, "(new goal)", "");
        return true;
    }

    // ---- game events ----

    /** A job the AI started ended (the end text the owner would see). */
    public void onJobEnd(String text, long now) {
        if (text != null && !text.isBlank()) lastState = "the job ended: " + text;
        if (state == State.OFF || state == State.STOPPED || state == State.PAUSED) return;
        sink.trial(now, goal, "(job end)", text);
        if (state == State.RUNNING || state == State.WAIT_CONFIRM) state = State.IDLE;
        if (goal != null) trigger("job-end");
    }

    /** NEW-type news while a goal is active (at most one think from news per 30 s). */
    public void onNews(String text, long now) {
        if (goal == null || state == State.OFF || state == State.STOPPED || state == State.PAUSED) return;
        if (now - lastEventAt < EVENT_GAP_MS) return;
        lastEventAt = now;
        lastState = "NEW: " + text + (lastState == null ? "" : "\n" + lastState);
        trigger("news");
    }

    private void trigger(String why) {
        if (!want) trigger = why;
        want = true;
    }

    // ---- the tick ----

    /** The request to send now, or null. Never called while one is in flight (it returns null then). */
    public Provider.Request poll(long now, World w) {
        boolean wasBusy = prevBusy, wasDead = prevDead;
        prevBusy = w.busy();
        prevDead = w.dead();
        if (w.inv() != null) lastInv = w.inv();
        if (goal != null && goalStartInv == null && w.inv() != null) goalStartInv = new java.util.HashMap<>(w.inv());
        if (state == State.OFF || state == State.STOPPED || state == State.PAUSED || state == State.THINKING) return null;
        if (!w.inWorld() || w.dead()) return null;
        if (wasDead) onNews("the bot died and respawned: run death first", now);
        if (state == State.WAIT_CONFIRM) {
            if (w.busy()) state = State.RUNNING;                   // the owner confirmed: the job runs
            else if (now >= confirmUntil) {
                state = State.IDLE;
                lastState = "the confirm question was not answered (or was cancelled): run status";
                if (goal != null) trigger("confirm-end");
            } else return null;
        }
        if (wasBusy && !w.busy() && goal != null && state != State.WAIT_CONFIRM) trigger("job-end");
        if (state == State.RUNNING && !w.busy()) state = State.IDLE;
        if (state == State.COOLDOWN) {
            if (now < nextAt) return null;
            state = State.IDLE;
            stopReason = null;
            trigger("retry");
        }
        if (goal == null) {
            if (!s.autonomous || w.busy()) return null;
            goal = AUTO_GOAL;
            autoGoal = true;
            newGoal();
            trigger("autonomous");
        }
        if (!want && !w.busy() && now - lastThinkAt >= HEARTBEAT_MS) trigger("heartbeat");
        if (!want || now < nextAt) return null;
        while (!minute.isEmpty() && now - minute.peekFirst() >= 60_000) minute.pollFirst();
        if (minute.size() >= PER_MINUTE) return null;
        String sp = budget.startProblem();
        if (sp != null) {
            stop(sp, now);
            return null;
        }
        if (s.maxThinksPerGoal > 0 && goalThinks >= s.maxThinksPerGoal) {
            endGoal("it used " + goalThinks + " model calls, the cap per request (Thinks/goal on the AI screen)", now);
            return null;
        }
        Provider.Request r = prompt.build(goal, notes(), lastState, history, s.maxOutputTokens, s.temperature, Duration.ofSeconds(s.timeoutSeconds()));
        long est = PromptBuilder.estimate(r);
        Budget.Cap cap = budget.refuse(now, est);
        if (cap != null) {
            if (cap.hourly() || s.resumeAfterCap) cooldown(cap.reason(), CAP_RETRY_MS, now);
            else stop(cap.reason(), now);
            return null;
        }
        state = State.THINKING;
        sentAt = now;
        want = false;
        busyAtSend = w.busy();
        thinks++;
        lastThinkAt = now;
        minute.addLast(now);
        reserved = est;
        goalThinks++;
        budget.ledger().record(now, est, 0, 1);          // 0.35.1 review: counted when sent (a timeout or a failure costs too)
        return r;
    }

    private String notes() {
        List<String> n = new ArrayList<>();
        if (failureStreak > 0) n.add(failureStreak + " failure(s) in a row");
        if (waitStreak > 0) n.add("you answered wait " + waitStreak + "x while the bot was idle");
        if (sameCount >= 2) n.add("the last command gave the same result " + sameCount + "x");
        String met = GoalMet.hint(goal, goalStartInv, lastInv);
        if (met != null) n.add(met);
        return String.join("; ", n);
    }

    /** True when the request outlived its timeout + 15 s: it was counted as a timeout; the adapter drops that thread. */
    public boolean watchdog(long now) {
        if (state != State.THINKING) return false;
        if (now - sentAt <= s.timeoutSeconds() * 1000L + WATCHDOG_EXTRA_MS) return false;
        onFailure(new ProviderException(ProviderException.Kind.TIMEOUT, 0, "no answer in " + (now - sentAt) / 1000 + " s (watchdog)"), now);
        return true;
    }

    /** The answer: the command to run now, or null. */
    public String onCompletion(Provider.Completion c, long now) {
        if (state != State.THINKING) return null;                     // paused or stopped meanwhile: dropped
        state = State.IDLE;
        inTok += c.inTokens();
        outTok += c.outTokens();
        latencyMs += c.millis();
        double cost = budget.cost(c.costUsd(), c.inTokens(), c.outTokens());
        usd += cost;
        budget.ledger().record(now, c.inTokens() + c.outTokens() - reserved, cost, 0);   // the estimate made exact
        if (!s.paid && !Budget.freeService(s) && cost > 0) {
            stop(String.format(Locale.ROOT, "a paid answer ($%.5f) while paid models are off", cost), now);
            return null;
        }
        extraLines += CommandGate.extraLines(c.text());
        CommandGate.Verdict v = gate.check(c.text());
        sink.log(now, String.format(Locale.ROOT, "think#%d trigger=%s in=%dt out=%dt $%.4f %.1fs | %s %s", thinks, trigger, c.inTokens(), c.outTokens(), cost,
                c.millis() / 1000.0, v.kind().name().toLowerCase(Locale.ROOT), v.line().isEmpty() ? "" : "cmd: " + v.line()));
        switch (v.kind()) {
            case EMPTY -> {
                empties++;
                // 0.35.2: a reasoning model that spent the whole answer on thinking says so
                failure("length".equals(c.finish()) ? "the model ran out of tokens while thinking - raise max output tokens or turn reasoning off"
                        : "the model returned nothing", now, false, false);
                return null;
            }
            case WAIT -> {
                handleWait(now);
                return null;
            }
            case FINISHED -> {
                sink.notice(summary());
                sink.trial(now, goal, "finished", "");
                goal = null;
                autoGoal = false;
                waitStreak = 0;
                nudged = false;
                failureStreak = 0;
                return null;
            }
            case UNKNOWN, DENIED, BAD -> {
                rejects++;
                if (v.kind() == CommandGate.Kind.DENIED) {
                    denyHits++;
                    if (deniedNoticed.add(v.verb() == null ? v.line() : v.verb()))
                        sink.notice("the model wanted \"" + PromptBuilder.cut(v.line(), 80) + "\" - not allowed for the AI operator (you can run it yourself)");
                }
                lastState = v.error();
                addTurn(v.line(), v.error());
                sink.trial(now, goal, v.line(), v.error());
                failure("the model sent an invalid command: " + PromptBuilder.cut(v.line(), 60) + " (" + v.error().replaceFirst("^error: ", "") + ")", now, true, false);
                return null;
            }
            default -> {
                // 0.35.3: say only when the owner asked to talk to players (the mod tells the owner results itself)
                if (!ASKS_TO_SAY.matcher(goal == null ? "" : goal).find() && saysSomething(v.line())) {
                    String e = "error: say is only for talking to other players when the owner asked for it; the mod tells the owner results itself - when the request is met reply finished";
                    lastState = e;
                    addTurn(v.line(), e);
                    failure("the model used say to report: " + PromptBuilder.cut(v.line(), 60), now, true, false);
                    return null;
                }
                // 0.35.3: a stronger loop guard: twice in a row, or 3 times in one request, ends the request
                // 0.35.4 review: only a repeat whose result did not change counts (same command AND same reply); read-only verbs never
                String key = v.line().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                int same = goalLines.getOrDefault(key, 0);
                boolean inRow = v.line().equalsIgnoreCase(lastCmd);
                if (!readOnly(v.line()) && ((inRow && same >= 2) || same >= SAME_LIMIT)) {
                    endGoal("the model repeated \"" + PromptBuilder.cut(v.line(), 60) + "\" and nothing changed" + (inRow ? " (in a row)" : " (" + same + " times)"), now);
                    return null;
                }
                if (v.line().equalsIgnoreCase(lastCmd) && sameCount >= SAME_LIMIT - 1) {
                    String e = "error: the same command " + SAME_LIMIT + "x in a row and nothing changed: try something else";
                    lastState = e;
                    addTurn(v.line(), e);
                    sameCount = 0;
                    failure("the same command " + SAME_LIMIT + "x: " + PromptBuilder.cut(v.line(), 60), now, true, false);
                    return null;
                }
                failureStreak = 0;
                waitStreak = 0;
                nudged = false;
                lastCmd = v.line();
                commands++;
                recentCommands.addLast(v.line());
                while (recentCommands.size() > 3) recentCommands.pollFirst();
                state = State.RUNNING;
                nextAt = now + s.minGapMs;
                return v.line();
            }
        }
    }

    private static boolean isVerb(String word, String name) {
        io.github.mojolowjo.entropybot.commands.VerbTable.Verb v = io.github.mojolowjo.entropybot.commands.VerbTable.of(word);
        return (v == null ? word : v.name()).equals(name);
    }

    static boolean readOnly(String line) {
        for (String st : io.github.mojolowjo.entropybot.commands.Texts.splitChain(line)) {
            String l = st.trim().toLowerCase().replaceAll("\\s+", " ");
            if (!(l.equals("status") || l.equals("aa") || l.equals("aa status") || l.equals("aa why"))) return false;
        }
        return true;
    }
    private static boolean saysSomething(String line) {
        for (String st : io.github.mojolowjo.entropybot.commands.Texts.splitChain(line))
            if (isVerb(io.github.mojolowjo.entropybot.commands.Texts.verbAndRest(st)[0], "say")) return true;
        return false;
    }

    private void newGoal() {
        goalThinks = 0;
        goalLines.clear();
        goalReplies.clear();
        goalStartInv = lastInv == null ? null : new java.util.HashMap<>(lastInv);
        lastCmd = null;
    }

    /** The one line the owner gets when a request is done: what it was, what ran, the last result. */
    String summary() {
        String g = autoGoal ? "the autonomous goal" : PromptBuilder.cut(goal, 60);
        String ran = recentCommands.isEmpty() ? "nothing" : String.join(", ", recentCommands);
        String last = lastState == null ? "" : lastState.split("\n")[0];
        return "done: " + g + " - ran " + PromptBuilder.cut(ran, 80) + (last.isBlank() ? "" : " - last: " + PromptBuilder.cut(last, 90));
    }

    /** 0.35.3: the mod ends the request itself (a loop, the thinks cap), with one line to the owner. */
    private void endGoal(String reason, long now) {
        sink.notice("I stopped working on \"" + PromptBuilder.cut(goal, 60) + "\": " + reason);
        sink.log(now, "goal ended: " + reason);
        sink.trial(now, goal, "(goal ended)", reason);
        goal = null;
        autoGoal = false;
        want = false;
        if (state != State.RUNNING) state = State.IDLE;
    }

    private void handleWait(long now) {
        failureStreak = 0;
        if (goal == null || busyAtSend) return;
        waitStreak++;
        if (waitStreak < WAITS_BEFORE_NUDGE) return;
        if (!nudged) {
            nudged = true;
            lastState = "the goal is not finished and the bot is idle: pick an OPTIONS line or a command (reply finished if the goal is done)";
            nextAt = now + s.minGapMs;
            trigger("nudge");
            sink.log(now, "nudge: " + waitStreak + " waits while idle");
        } else {
            stop("model idle: it answered wait " + waitStreak + " times while the bot was idle (open ai log)", now);
        }
    }

    /** A provider error (after its own retries). */
    public void onFailure(ProviderException e, long now) {
        if (state != State.THINKING) return;
        state = State.IDLE;
        lastStatus = e.status;
        if (e.kind == ProviderException.Kind.RATE) r429++;
        if (e.kind == ProviderException.Kind.TIMEOUT) timeouts++;
        String why = e.reason();
        sink.log(now, "think#" + thinks + " trigger=" + trigger + " failed: " + why);
        if (e.fatal()) {
            lastError = why;
            stop(why, now);
            return;
        }
        boolean outage = e.kind == ProviderException.Kind.NETWORK || e.kind == ProviderException.Kind.SERVER || e.kind == ProviderException.Kind.TIMEOUT
                || e.kind == ProviderException.Kind.RATE;
        failure(why, now, false, outage);
    }

    private void failure(String reason, long now, boolean rejection, boolean outage) {
        failureStreak++;
        lastError = reason;
        if (failureStreak >= s.maxFailures) {
            if (outage && s.resumeAfterOutage && goal != null) {
                cooldown("the AI service is not answering (" + reason + ")", OUTAGE_RETRY_MS, now);
                return;
            }
            stop("failures: " + reason, now);
            return;
        }
        nextAt = now + (rejection ? s.minGapMs : BACKOFF_MS[Math.min(failureStreak - 1, BACKOFF_MS.length - 1)]);
        trigger("retry");
    }

    /** The command the adapter ran and its shaped reply; jobStarted: a job runs now (its end comes as onJobEnd). */
    public void onCommandResult(String command, String reply, boolean jobStarted, long now) {
        String r = reply == null || reply.isBlank() ? "ok" : reply;
        if (command.equalsIgnoreCase(prevCmd) && r.equals(prevReply)) sameCount++;
        else sameCount = 1;
        prevCmd = command;
        prevReply = r;
        // 0.35.4: how many runs of this line in this request gave the same reply as the run before (1 = first run or a changed reply)
        String key = command.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String before = goalReplies.put(key, r);
        goalLines.put(key, before != null && before.equals(r) ? goalLines.getOrDefault(key, 1) + 1 : 1);
        addTurn(command, r);
        lastState = r;
        sink.trial(now, goal, command, r);
        sink.log(now, "ran: " + command + " | reply: " + PromptBuilder.cut(r.replace('\n', ' '), 200));
        if (state != State.RUNNING) return;                           // paused by a stop meanwhile
        if (r.startsWith("confirm?")) {
            state = State.WAIT_CONFIRM;
            confirmUntil = now + CONFIRM_WAIT_MS;
            sink.notice("I want to run \"" + PromptBuilder.cut(command, 100) + "\" - say confirm if that's ok");
            return;
        }
        if (!jobStarted) {
            state = State.IDLE;
            if (goal != null) trigger("result");
        }
    }

    private void addTurn(String cmd, String reply) {
        history.addLast(new PromptBuilder.Turn(cmd, PromptBuilder.cut(reply, PromptBuilder.HISTORY_REPLY)));
        while (history.size() > PromptBuilder.HISTORY) history.pollFirst();
    }

    private void stop(String reason, long now) { stop(reason, now, true); }

    private void stop(String reason, long now, boolean tell) {
        state = State.STOPPED;
        stopReason = reason;
        want = false;
        sink.log(now, "stopped: " + reason);
        if (tell) sink.notice("stopped: " + reason);
    }

    private void cooldown(String reason, long ms, long now) {
        state = State.COOLDOWN;
        stopReason = reason;
        nextAt = now + ms;
        sink.log(now, "waiting " + ms / 60_000 + " min: " + reason);
        if (!reason.equals(cooldownNoticed)) {
            cooldownNoticed = reason;
            sink.notice(reason + " - I try again in " + ms / 60_000 + " min");
        }
    }

    /** ai status's lines (no key in them). */
    public List<String> status(long now) {
        List<String> l = new ArrayList<>();
        String st = state.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        l.add("AI operator: " + st + (stopReason != null && (state == State.STOPPED || state == State.COOLDOWN) ? " - " + stopReason : "")
                + (state == State.PAUSED ? " (until your next request)" : ""));
        l.add("goal: " + (goal == null ? "none" : PromptBuilder.cut(goal, 120)));
        l.add("spent: " + budget.spentLine(now));
        l.add(String.format(Locale.ROOT, "counts: %d thinks, %d commands, %d+%d tokens, $%.4f, avg %.1fs, 429s %d, timeouts %d, empty %d, rejected %d (denied %d), extra lines %d",
                thinks, commands, inTok, outTok, usd, thinks == 0 ? 0.0 : latencyMs / 1000.0 / Math.max(1, thinks), r429, timeouts, empties, rejects, denyHits, extraLines));
        l.add("last commands: " + (recentCommands.isEmpty() ? "none" : String.join(" ; ", recentCommands)));
        l.add("last error: " + (lastError == null ? "none" : lastError) + (lastStatus > 0 ? " (http " + lastStatus + ")" : "") + "; failure streak " + failureStreak);
        return l;
    }

    /** One short line for the screen and state.json. */
    public String shortLine() {
        String st = state.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        if ((state == State.STOPPED || state == State.COOLDOWN) && stopReason != null) return st + ": " + stopReason;
        return st + (lastCmd != null ? ", last: " + PromptBuilder.cut(lastCmd, 40) : "");
    }
}

