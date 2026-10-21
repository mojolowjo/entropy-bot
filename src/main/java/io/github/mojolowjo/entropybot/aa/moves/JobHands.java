package io.github.mojolowjo.entropybot.aa.moves;

import java.util.ArrayList;
import java.util.List;

/**
 * Backbone step 3 (BARITONE_BACKBONE.md, owner: "AA is the driver, Baritone is the car"): the hands of the small
 * building jobs (shelter, dig-out, place / pick up a table or furnace, restore, farm planting). A job asks for one
 * thing at a time, every tick, by the same call: break this cell, or right-click this face with this item. The first
 * call starts it on the fork's {@code BurrowProcess.startJob} (Baritone's look, click handling, tool and item choice),
 * the next ones follow it: RUNNING, then DONE once, or FAILED once with the stage, the block and the guard's verdict
 * on the cell. A different request replaces the running one. Decides nothing: the job chose the cell, took its
 * leases and keeps its own tries. Pure (no game classes): JUnit runs it on a fake hand. Never throws.
 */
public final class JobHands {
    /** Warnings (pure class: the game's log is set by {@link #get()}; tests print). */
    static volatile java.util.function.Consumer<String> warn = System.err::println;
    public enum State { RUNNING, DONE, FAILED }

    public record Result(State state, String why) {
        public boolean running() { return state == State.RUNNING; }
        public boolean done() { return state == State.DONE; }
        public boolean failed() { return state == State.FAILED; }
    }

    /** One right-click on block {@code on} with item {@code item} (null = the hand); Baritone picks the face it sees. */
    public record Click(int[] on, String item) {}

    /** What JobHands asks Baritone for. The real one is {@link BaritoneHand}. */
    public interface Hand {
        void start(List<int[]> dig, List<Click> clicks, int timeoutTicks);

        /**
         * Builder placements: Baritone's BuilderProcess builds these cells, each with its item from the bag (a
         * schematic leg); it picks the stand spot, the face, the look and the click. Status / cancel then follow it.
         */
        void startBuild(List<int[]> cells, List<String> items, int timeoutTicks);

        /** RUNNING / DONE / FAILED of the last start, with its line. */
        Car.Status status();

        void cancel(String why);

        /** "; guard on &lt;block&gt; at x y z: allowed / refused: why" for this action ("break" / "place") on the cell, or "". */
        String verdict(String action, int[] cell);

        /** The LookBehavior target this tick (a job that clicks by position itself, the clear engine). */
        void look(double x, double y, double z);
    }


    /** Lost-control restarts (a pause or a cancel from elsewhere) before a request fails. */
    static int retriesMax() { return (int) io.github.mojolowjo.entropybot.aa.brain.Tuning.get("hands.retries"); }

    private static JobHands live;

    /** The game's one (on Baritone). */
    public static synchronized JobHands get() {
        if (live == null) {
            live = new JobHands(new BaritoneHand());
            warn = BaritoneHand::warn;
        }
        return live;
    }

    /** Review 4: leaving the world ends the running request (no clicks resumed in the next world) and forgets the hands. */
    public static synchronized void leftWorld() {
        if (live != null) live.stop("left the world");
        live = null;
    }

    private final Hand hand;
    private String key, who = "", action = "break";
    /** The request has its end (DONE / FAILED): no longer busy, the result waits for its own caller's next call. */
    private boolean finished;
    private Result result;
    private List<int[]> cells = List.of();
    private int[] verdictCell;
    private List<int[]> dig = List.of();
    private List<Click> clicks = List.of();
    private int timeout, retries;
    private Runnable onDone;
    private String lastFailWhy;
    private List<int[]> lastFailCells = List.of();

    public JobHands(Hand hand) {
        this.hand = hand;
    }

    /** Break cell x y z (until air) for {@code who}; {@code timeoutTicks} for it. */
    public Result dig(int[] cell, int timeoutTicks, String who) {
        return request("dig " + c(cell), List.of(cell.clone()), List.of(), timeoutTicks, who, null, "break", cell);
    }

    /** Right-click block {@code on} with {@code item} (Baritone picks the face); {@code onDone} runs once when the world changed. */
    public Result click(int[] on, String item, int timeoutTicks, String who, Runnable onDone) {
        Click k = new Click(on.clone(), item);
        return request("click " + c(on) + " " + item, List.of(), List.of(k), timeoutTicks, who, onDone, "place", on.clone());
    }

    /**
     * Builder placements: put block item {@code item} into cell x y z as a one-block schematic leg (Baritone picks
     * where to stand, the face and the click); {@code onDone} runs once when the block is there.
     */
    public Result build(int[] cell, String item, int timeoutTicks, String who, Runnable onDone) {
        return build(List.of(cell.clone()), List.of(item), timeoutTicks, who, onDone);
    }

    /** A few-block schematic leg (a shell, a seal): cells.get(i) gets items.get(i). */
    public synchronized Result build(List<int[]> cells, List<String> items, int timeoutTicks, String who, Runnable onDone) {
        StringBuilder k = new StringBuilder("build");
        for (int i = 0; i < cells.size(); i++) k.append(i == 0 ? " " : ", ").append(c(cells.get(i))).append(' ').append(items.get(i));
        String ks = k.toString();
        if (key != null && ks.equals(key)) return request(ks, List.of(), List.of(), timeoutTicks, who, onDone, "place", cells.get(0));
        if (key != null && !finished) return new Result(State.RUNNING, "waiting: my hands are busy with " + key + (who.isEmpty() ? "" : " for " + who));
        clear();
        key = ks;
        this.who = who == null ? "" : who;
        dig = List.of();
        clicks = List.of();
        buildCells = List.copyOf(cells);
        buildItems = List.copyOf(items);
        timeout = timeoutTicks;
        this.onDone = onDone;
        action = "place";
        verdictCell = cells.get(0);
        retries = 0;
        this.cells = buildCells;
        Result r = start();
        if (r.failed()) {
            Result f = r;
            clear();
            return f;
        }
        return r;
    }

    private List<int[]> buildCells;
    private List<String> buildItems;

    private synchronized Result request(String k, List<int[]> d, List<Click> cl, int t, String w, Runnable done, String act, int[] vc) {
        if (key != null && k.equals(key)) {
            if (!finished) {
                Result r = poll();
                if (!r.running()) clear();      // the caller has it now
                return r;
            }
            Result r = result;                // review 5: read once by its caller, then gone (no cache across requests)
            clear();
            return r;
        }
        // review 3: another caller's request still runs: this one waits (never cancels it)
        if (key != null && !finished) return new Result(State.RUNNING, "waiting: my hands are busy with " + key + (who.isEmpty() ? "" : " for " + who));
        clear();
        key = k;
        who = w == null ? "" : w;
        dig = d;
        clicks = cl;
        timeout = t;
        onDone = done;
        action = act;
        verdictCell = vc;
        retries = 0;
        List<int[]> all = new ArrayList<>(d);
        for (Click x : cl) {
            all.add(x.on());
            all.add(new int[]{x.on()[0], x.on()[1] + 1, x.on()[2]});
        }
        cells = all;
        Result r = start();
        if (r.failed()) {
            Result f = r;
            clear();
            return f;
        }
        return r;
    }

    private Result start() {
        try {
            if (buildCells != null) hand.startBuild(buildCells, buildItems, timeout);
            else hand.start(dig, clicks, timeout);
        } catch (RuntimeException e) {
            return finish(new Result(State.FAILED, key + ": start: " + e));
        }
        return new Result(State.RUNNING, "started " + key + (who.isEmpty() ? "" : " for " + who));
    }

    private Result poll() {
        Car.Status s;
        try {
            s = hand.status();
        } catch (RuntimeException e) {
            s = Car.Status.failed("status: " + e);
        }
        if (s == null) s = Car.Status.failed("the hand gave no status");
        String why = s.why() == null ? "" : s.why();
        return switch (s.state()) {
            case RUNNING -> new Result(State.RUNNING, why);
            case DONE -> {
                Runnable r = onDone;
                onDone = null;
                if (r != null) {
                    try {
                        r.run();
                    } catch (RuntimeException e) {
                        // no silent failure: the job's own "when done" step broke, so the request is FAILED with why
                        warn.accept("[entropybot] job hands: " + key + " done, but its done step failed: " + e);
                        yield finish(new Result(State.FAILED, "gave up at " + key + ": done, but its done step failed: " + e));
                    }
                }
                yield finish(new Result(State.DONE, why));
            }
            case FAILED -> {
                if (why.contains("took control away") && retries < retriesMax()) {
                    retries++;
                    String k = key;
                    Result r = start();
                    yield r.failed() ? r : new Result(State.RUNNING, "restarted " + k + " (" + retries + "/" + retriesMax() + ") after: " + why);
                }
                String v = "";
                try { v = hand.verdict(action, verdictCell); } catch (RuntimeException e) { v = "; guard verdict unknown (" + e + ")"; warn.accept("[entropybot] job hands: verdict for " + key + ": " + e); }
                yield finish(new Result(State.FAILED, "gave up at " + key + ": " + why + (v == null ? "" : v)));
            }
        };
    }

    private Result finish(Result r) {
        if (r.failed()) {
            lastFailWhy = r.why();
            lastFailCells = cells;
        }
        finished = true;
        result = r;
        onDone = null;
        return r;
    }

    private void clear() {
        key = null;
        finished = false;
        result = null;
        onDone = null;
        cells = List.of();
        buildCells = null;
        buildItems = null;
    }

    /** Once a client tick: follows a running request so its end (and onDone) is seen without the caller. */
    public synchronized void tick() {
        if (key != null && !finished) poll();
    }

    /** True while a request runs that touches cell x y z (its break cell, its clicked block or the cell beside). */
    public synchronized boolean busy(int x, int y, int z) {
        if (key == null || finished) return false;
        for (int[] q : cells) if (q[0] == x && q[1] == y && q[2] == z) return true;
        return false;
    }

    /** Ends the running request (the job ended or moved on); nothing when idle. */
    public synchronized void stop(String why) {
        if (key == null) return;
        if (!finished) safeCancel(why);
        clear();
    }

    /**
     * Review 2: a danger move takes Baritone's burrow process: the running request ends FAILED "preempted by ..."
     * (its caller reads that on its next call), never silently.
     */
    public synchronized void preempt(String why) {
        if (key == null || finished) return;
        safeCancel("preempted by " + why);
        finish(new Result(State.FAILED, "gave up at " + key + ": preempted by " + why));
    }

    /** The camera for a job that clicks by position itself: Baritone's LookBehavior target. */
    public void look(double x, double y, double z) {
        try { hand.look(x, y, z); } catch (RuntimeException e) { warn.accept("[entropybot] job hands: look at " + x + " " + y + " " + z + " failed: " + e); }
    }

    public synchronized boolean idle() { return key == null || finished; }

    /** The last failure line of a request that touched cell x y z, or null (a job's own give-up line names it). */
    public synchronized String lastFailure(int x, int y, int z) {
        for (int[] q : lastFailCells) if (q[0] == x && q[1] == y && q[2] == z) return lastFailWhy;
        return null;
    }

    private void safeCancel(String why) {
        try { hand.cancel(why); } catch (RuntimeException e) { warn.accept("[entropybot] job hands: cancel of " + key + " (" + why + ") failed: " + e); }
    }

    private static String c(int[] p) {
        return p[0] + " " + p[1] + " " + p[2];
    }
}
