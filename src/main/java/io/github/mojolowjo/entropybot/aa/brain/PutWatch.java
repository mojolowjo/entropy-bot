package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.Map;

/**
 * deposit-goal stage 4, review 3 (fault 1, serious: a bot already by the main chest gets [Deposit] alone - NeedChain.stationWalk
 * adds no ToStation for a 0 s walk - and Steps.start runs that first step inside AaRuntime.runTyped, before the task's id is
 * given (current = ++seq), so the put was tied to the previous task's id, -1; no end, cancel or lost runner of the new task
 * ever read it, LineDeposits stayed "on its way" for ever and the goal could loop): the one store of the planned put that is
 * on its way (rule 23) - is a put on, which task it belongs to, what its leg moved - and the one place its end is read.
 *
 * <p>A put that starts inside a new task's start ({@code starting}) is pending until {@link #taskStarted} gives it that
 * task's id; a start refused after the put began ends it not reached ({@link #startRefused}). Only the put's own task's end
 * ({@link #ended}), cancel ({@link #cancelled}) or lost runner ({@link #lost}) reads it; the goals tick's net
 * ({@link #idle}) ends a put whose task is gone with no end read. Each end goes to {@link LineDeposits#putEnded} once and
 * returns its line for the log (rule 7); null: nothing ended. No Minecraft or Baritone types; AaRuntime holds one, the
 * replay tests drive it with no game. Loader notes: none.
 */
public final class PutWatch {
    private final LineDeposits deposits;
    private boolean on;
    /** The task the put belongs to; -1 while it is pending (started inside a new task's start) or no put is on. */
    private long task = -1;
    private boolean pending;
    private Map<String, Integer> moved = Map.of();

    public PutWatch(LineDeposits deposits) { this.deposits = deposits; }

    /** A put is on its way (started, its end not read yet). */
    public boolean on() { return on; }

    /** The task id the put belongs to (-1: none, or still pending). */
    public long task() { return task; }

    /**
     * The planned put's step started: what is owed goes on its way (LineDeposits.putStarted, the take-back cutoff).
     * {@code starting}: it runs inside a NEW task's start, whose id is not given yet - pending until {@link #taskStarted};
     * else it belongs to {@code current}, the task running now (a re-plan or a resume inside it). Returns the line of an
     * earlier put of another task that ended lost here (review 7), null: none.
     */
    public String started(long current, boolean starting) {
        // review 4 (point 3): the same task starting the put again (an escape resume, an in-run re-plan that splices a new
        // Deposit) keeps what its earlier legs moved; the leg's own count starts afresh
        boolean again = on && !pending && !starting && task == current;
        String line = null;
        if (again) legEnded();                       // review 5: a leg whose end was not read comes off the owed amount now
        else if (on) {
            // review 7 (point 3, rule 7): a put still on for another task (or still pending) whose end was never read: it
            // ends here as lost, with what it moved (that part counts for its orders), the rest owed again and carried by
            // this put; said, never cleared silently
            line = over(false, "a new put started (" + (starting ? "a new task's start" : "task " + current) + ") before the end of "
                    + (pending ? "the pending put" : "task " + task + "'s put") + " was read");
        }
        earlier = again ? earlier : Map.of();
        on = true;
        pending = starting;
        task = starting ? -1 : current;
        moved = Map.of();
        deposits.putStarted();
        return line;
    }

    /** Review 4 (point 3): what the earlier legs of this same put moved. */
    private Map<String, Integer> earlier = Map.of();

    /** What the put has moved so far, all its legs (empty: nothing). */
    public Map<String, Integer> movedSoFar() { return sum(earlier, moved); }

    private static Map<String, Integer> sum(Map<String, Integer> a, Map<String, Integer> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        Map<String, Integer> out = new java.util.TreeMap<>(a);
        b.forEach((k, v) -> out.merge(k, v, Integer::sum));
        return Map.copyOf(out);
    }

    /** The new task whose start ran the put's step got its id: a pending put belongs to it. */
    public void taskStarted(long id) {
        if (!on || !pending) return;
        pending = false;
        task = id;
    }

    /** The new task's start was refused after its first step started the put (the start failed at that step): not reached. */
    public String startRefused(String why) {
        if (!on || !pending) return null;
        return over(false, "the deposit task's start was refused after the put began: " + why);
    }

    /**
     * The put's leg moved {@code items} into the chest so far (by the step's ids; empty: nothing). Review 4 (point 2): told
     * at the clicks and again at the count, each time the leg's whole count (a replace, never added twice). No put on:
     * nothing (a deposit that is no planned put).
     */
    public void moved(Map<String, Integer> items) {
        if (!on) return;
        moved = items == null ? Map.of() : Map.copyOf(items);
    }

    /**
     * Review 5: the put's leg (its Deposit step) ended inside its running task (done or failed; the task runs on, it may
     * re-plan and start the put again). What the leg moved comes off what is owed now (LineDeposits.legEnded), so a
     * re-plan inside the task never asks again for it; it is kept in the earlier legs' sum for the end's line. No put on,
     * or pending: nothing.
     */
    public void legEnded() {
        if (!on || moved.isEmpty()) return;
        deposits.legEnded(moved);
        earlier = sum(earlier, moved);
        moved = Map.of();
    }

    /**
     * Task {@code endedTask}'s run ended ({@code done}, else failed at {@code failedAt}): the put of that task reached the
     * chest when the run ended done or failed at a step after the put (LineDeposits.putReached). Another task's end reads
     * nothing.
     */
    public String ended(long endedTask, boolean done, Step failedAt, String msg) {
        if (!on || pending || task != endedTask) return null;
        return over(LineDeposits.putReached(task, endedTask, done, failedAt), msg);
    }

    /** Task {@code taskId} was cancelled ({@code why}): its put did not reach the chest whole. */
    public String cancelled(long taskId, String why) {
        if (!on || pending || task != taskId) return null;
        return over(false, "the deposit task was cancelled: " + why);
    }

    /** Task {@code taskId} lost the runner to another run (a preempt) before its end was read: its put is not reached. */
    public String lost(long taskId) {
        if (!on || pending || task != taskId) return null;
        return over(false, "the deposit task lost the runner before its end was read");
    }

    /** The owner dropped the line's deposit ({@code why}: aa cancel deposit): a put on its way, whatever its task, is not reached. */
    public String dropped(String why) {
        if (!on) return null;
        return over(false, why);
    }

    /**
     * The goals tick's net: a put on whose task is not the one running now ({@code current}) and nothing runs on the
     * runner - its end was never read (a stop, a clear, another run took the runner): not reached. A pending put waits for
     * its task's id (its start is on the stack now).
     */
    public String idle(long current, boolean running) {
        if (!on || pending || current == task || running) return null;
        return over(false, "the run ended with no end read (stopped, or another run took the runner)");
    }

    /** The put's end, read once: LineDeposits.putEnded with what the leg moved; the line for the log. */
    private String over(boolean reached, String why) {
        on = false;
        pending = false;
        task = -1;
        Map<String, Integer> m = movedSoFar();
        Map<String, Integer> leg = moved;            // review 5: the earlier legs came off the owed amount at their ends
        moved = Map.of();
        earlier = Map.of();
        // review 5: every leg's part reached the chest (nothing of the way is left after this leg): reached
        if (!reached && !m.isEmpty()) { deposits.legEnded(leg); if (deposits.wayEmpty()) reached = true; leg = Map.of(); }
        deposits.putEnded(reached, leg);
        return "the line's put " + (reached ? "reached the chest"
                : "did not reach the chest whole (" + (m.isEmpty() ? "nothing went in" : "went in: " + m) + "; the rest is owed again)") + ": " + why;
    }
}
