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
    public enum State { RUNNING, DONE, FAILED }

    public record Result(State state, String why) {
        public boolean running() { return state == State.RUNNING; }
        public boolean done() { return state == State.DONE; }
        public boolean failed() { return state == State.FAILED; }
    }

    /** One right-click on {@code face} ("up", "north"...) of block {@code on} with item {@code item} (null = the hand). */
    public record Click(int[] on, String face, String item) {
        int[] beside() {
            int[] d = switch (face) {
                case "up" -> new int[]{0, 1, 0};
                case "down" -> new int[]{0, -1, 0};
                case "north" -> new int[]{0, 0, -1};
                case "south" -> new int[]{0, 0, 1};
                case "west" -> new int[]{-1, 0, 0};
                default -> new int[]{1, 0, 0};
            };
            return new int[]{on[0] + d[0], on[1] + d[1], on[2] + d[2]};
        }
    }

    /** What JobHands asks Baritone for. The real one is {@link BaritoneHand}. */
    public interface Hand {
        void start(List<int[]> dig, List<Click> clicks, int timeoutTicks);

        /** RUNNING / DONE / FAILED of the last start, with its line. */
        Car.Status status();

        void cancel(String why);

        /** "; guard on &lt;block&gt; at x y z: allowed / refused: why" for this action ("break" / "place") on the cell, or "". */
        String verdict(String action, int[] cell);

        /** The LookBehavior target this tick (a job that clicks by position itself, the clear engine). */
        void look(double x, double y, double z);
    }

    /** Lost-control restarts (a pause or a cancel from elsewhere) before a request fails. */
    static final int RETRIES = 2;
    /** Ticks a final result waits for its caller's next call (tick() saw it first). */
    static final long KEEP_FINAL = 20;

    private static JobHands live;

    /** The game's one (on Baritone). */
    public static synchronized JobHands get() {
        if (live == null) live = new JobHands(new BaritoneHand());
        return live;
    }

    private final Hand hand;
    private String key, who = "", action = "break";
    private List<int[]> cells = List.of();
    private int[] verdictCell;
    private List<int[]> dig = List.of();
    private List<Click> clicks = List.of();
    private int timeout, retries;
    private Runnable onDone;
    private String finalKey;
    private Result finalResult;
    private long now, finalAt;

    public JobHands(Hand hand) {
        this.hand = hand;
    }

    /** Break cell x y z (until air) for {@code who}; {@code timeoutTicks} for it. */
    public Result dig(int[] cell, int timeoutTicks, String who) {
        return request("dig " + c(cell), List.of(cell.clone()), List.of(), timeoutTicks, who, null, "break", cell);
    }

    /** Right-click {@code face} of {@code on} with {@code item}; {@code onDone} runs once when the world changed. */
    public Result click(int[] on, String face, String item, int timeoutTicks, String who, Runnable onDone) {
        Click k = new Click(on.clone(), face, item);
        return request("click " + c(on) + " " + face + " " + item, List.of(), List.of(k), timeoutTicks, who, onDone, "place", k.beside());
    }

    private synchronized Result request(String k, List<int[]> d, List<Click> cl, int t, String w, Runnable done, String act, int[] vc) {
        if (key == null && k.equals(finalKey) && now - finalAt <= KEEP_FINAL) {
            Result r = finalResult;
            finalKey = null;
            finalResult = null;
            return r;
        }
        if (k.equals(key)) {
            Result r = poll();
            if (!r.running()) finalKey = null;          // the caller has it: its next call is a fresh try
            return r;
        }
        if (key != null) safeCancel("replaced by " + w + " (" + k + ")");
        finalKey = null;
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
            all.add(x.beside());
        }
        cells = all;
        return start();
    }

    private Result start() {
        try {
            hand.start(dig, clicks, timeout);
        } catch (RuntimeException e) {
            return end(new Result(State.FAILED, key + ": start: " + e));
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
                Result res = end(new Result(State.DONE, why));
                if (r != null) {
                    try { r.run(); } catch (RuntimeException ignored) { }
                }
                yield res;
            }
            case FAILED -> {
                if (why.contains("took control away") && retries < RETRIES) {
                    retries++;
                    String k = key;
                    Result r = start();
                    yield r.failed() ? r : new Result(State.RUNNING, "restarted " + k + " (" + retries + "/" + RETRIES + ") after: " + why);
                }
                String v = "";
                try { v = hand.verdict(action, verdictCell); } catch (RuntimeException ignored) { }
                yield end(new Result(State.FAILED, "gave up at " + key + ": " + why + (v == null ? "" : v)));
            }
        };
    }

    private Result end(Result r) {
        if (r.failed()) {
            lastFailWhy = r.why();
            lastFailCells = cells;
        }
        finalKey = key;
        finalResult = r;
        finalAt = now;
        key = null;
        onDone = null;
        cells = List.of();
        return r;
    }

    /** Once a client tick: follows a running request so its end (and onDone) is seen without the caller. */
    public synchronized void tick() {
        now++;
        if (key != null) poll();
    }

    /** True while a request runs that touches cell x y z (its break cell, its clicked block or the cell beside). */
    public synchronized boolean busy(int x, int y, int z) {
        if (key == null) return false;
        for (int[] q : cells) if (q[0] == x && q[1] == y && q[2] == z) return true;
        return false;
    }

    /** Ends the running request (the job ended or moved on); nothing when idle. */
    public synchronized void stop(String why) {
        if (key == null) return;
        safeCancel(why);
        key = null;
        onDone = null;
        cells = List.of();
        finalKey = null;
    }

    /** The camera for a job that clicks by position itself: Baritone's LookBehavior target. */
    public void look(double x, double y, double z) {
        try { hand.look(x, y, z); } catch (RuntimeException ignored) { }
    }

    public synchronized boolean idle() { return key == null; }

    /** The last failure line of a request that touched cell x y z, or null (a job's own give-up line names it). */
    public synchronized String lastFailure(int x, int y, int z) {
        for (int[] q : lastFailCells) if (q[0] == x && q[1] == y && q[2] == z) return lastFailWhy;
        return null;
    }

    private String lastFailWhy;
    private List<int[]> lastFailCells = List.of();

    private void safeCancel(String why) {
        try { hand.cancel(why); } catch (RuntimeException ignored) { }
    }

    private static String c(int[] p) {
        return p[0] + " " + p[1] + " " + p[2];
    }
}
