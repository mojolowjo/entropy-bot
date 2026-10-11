package io.github.mojolowjo.entropybot.motor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.27.0 motor arbiter: one move at a time, a tick budget each, a stall rule (no progress for {@link #STALL_TICKS}),
 * the reflexes before a job, everything off when the motor is off, a screen is open or the human drives. Keeps the last
 * {@link #RING} results and the failures of the last hour. Pure (JUnit); loader notes: none.
 */
public final class Arbiter {
    public enum Owner { REFLEX, JOB }

    public static final int STALL_TICKS = 10, RING = 10, FAIL_LIMIT = 20;
    public static final double PROGRESS = 0.05;
    static final long HOUR_MS = 3_600_000L;

    /** What the world allows this tick. */
    public record Gate(boolean on, boolean screen, boolean human, boolean reflexHolds) {
        String refusal() {
            if (!on) return "motor off";
            if (screen) return "a screen is open";
            if (human) return "the human drives (watch steer)";
            return null;
        }
    }

    public record Result(String name, Owner owner, String why, String end, long ticks, long atMs, boolean failed) {
        public String text() {
            return name + " [" + owner.name().toLowerCase() + "] " + end + " after " + ticks + " ticks" + (why == null || why.isEmpty() ? "" : " (" + why + ")");
        }
    }

    private MotorPlan.Plan plan;
    private Owner owner;
    private String why;
    private long start, bestTick;
    private int budget;
    private double best;
    private final ArrayDeque<Result> ring = new ArrayDeque<>();
    private final ArrayDeque<Long> failures = new ArrayDeque<>();
    private String lastFailure;
    private long accepted, refused;

    public synchronized boolean active() { return plan != null; }

    public synchronized MotorPlan.Plan plan() { return plan; }

    public synchronized Owner owner() { return owner; }

    public synchronized String why() { return why; }

    public synchronized long started() { return start; }

    /**
     * Ask for a move. Null = it runs now; else the refusal. A reflex move replaces whatever runs; a job move is refused
     * while another move runs or the reflexes hold. A plan that failed is refused with its reason and counts as a failure.
     */
    public synchronized String request(MotorPlan.Plan p, Owner o, long tick, int budgetTicks, Gate gate, String reason, long ms) {
        String r = gate == null ? "no gate" : gate.refusal();
        if (r == null && o == Owner.JOB && plan != null) r = "busy with " + plan.name();
        if (r == null && o == Owner.JOB && gate.reflexHolds()) r = "the reflexes own the motor";
        if (r == null && (p == null || !p.ok())) {
            r = p == null ? "no plan" : p.why();
            fail(ms, (p == null ? "move" : p.name()) + ": " + r);
            record(new Result(p == null ? "move" : p.name(), o, reason, "refused: " + r, 0, ms, true));
        }
        if (r != null) {
            refused++;
            return r;
        }
        if (plan != null) end(tick, "replaced by " + p.name(), ms, false);
        plan = p;
        owner = o;
        why = reason;
        start = tick;
        bestTick = tick;
        best = Double.MAX_VALUE;
        budget = Math.max(1, budgetTicks);
        accepted++;
        return null;
    }

    /**
     * Each tick while a move runs. remaining: distance left (NaN = no progress rule, e.g. hold); done: the move's own
     * stop condition. Null while it runs, else how it ended (recorded).
     */
    public synchronized String tick(long tick, double remaining, boolean done, Gate gate, long ms) {
        if (plan == null) return "idle";
        String g = gate == null ? "no gate" : gate.refusal();
        if (g != null) return end(tick, "stopped: " + g, ms, false);
        if (done) return end(tick, "done", ms, false);
        if (tick - start >= budget) return end(tick, plan.kind() == MotorPlan.Kind.HOLD ? "done (held " + budget + " ticks)" : "out of time (" + budget + " ticks)",
                ms, plan.kind() != MotorPlan.Kind.HOLD);
        if (!Double.isNaN(remaining)) {
            if (remaining < best - PROGRESS) {
                best = remaining;
                bestTick = tick;
            } else if (tick - bestTick >= STALL_TICKS) return end(tick, "stalled (no progress for " + STALL_TICKS + " ticks, " + Math.round(remaining * 10) / 10.0 + " left)", ms, true);
        }
        return null;
    }

    /** End the running move (the caller's stop); null when none ran. */
    public synchronized String stop(long tick, String reason, long ms) {
        if (plan == null) return null;
        return end(tick, reason, ms, false);
    }

    private String end(long tick, String how, long ms, boolean failed) {
        Result r = new Result(plan.name(), owner, why, how, tick - start, ms, failed);
        record(r);
        if (failed) fail(ms, plan.name() + ": " + how);
        plan = null;
        owner = null;
        why = null;
        return how;
    }

    private void record(Result r) {
        ring.addLast(r);
        while (ring.size() > RING) ring.removeFirst();
    }

    private void fail(long ms, String line) {
        failures.addLast(ms);
        lastFailure = line;
        prune(ms);
    }

    private void prune(long ms) {
        while (!failures.isEmpty() && ms - failures.peekFirst() > HOUR_MS) failures.removeFirst();
    }

    public synchronized int failuresLastHour(long ms) {
        prune(ms);
        return failures.size();
    }

    public synchronized String lastFailure() { return lastFailure; }

    public synchronized List<Result> last() { return new ArrayList<>(ring); }

    public synchronized long acceptedCount() { return accepted; }

    public synchronized long refusedCount() { return refused; }

    /** For check: {key, line, fix} when failures in the last hour exceed {@link #FAIL_LIMIT}. */
    public synchronized String[] finding(long ms) {
        int n = failuresLastHour(ms);
        if (n <= FAIL_LIMIT) return null;
        return new String[]{"motorfail", "the motor's moves failed " + n + " times in the last hour (last: " + lastFailure + ")", "debug motor"};
    }

    public synchronized String lastText() {
        if (ring.isEmpty()) return "no moves yet";
        StringBuilder b = new StringBuilder();
        for (Result r : ring) b.append(b.length() == 0 ? "" : "\n").append("  ").append(r.text());
        return b.toString();
    }
}
