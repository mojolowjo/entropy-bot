package io.github.mojolowjo.entropybot.aa.moves;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Backbone step 2 (BARITONE_BACKBONE.md, owner: "AA is the driver, Baritone is the car"): the only place AA's options
 * ask for a physical action. Each way is a short list of legs, each leg one {@link Car} command (a Baritone goal or
 * the fork's BurrowProcess) with its own time limit. {@link #tick} follows the car: a leg done starts the next, the
 * last done = ARRIVED, a leg failed or timed out = FAILED with "gave up at &lt;leg&gt;: &lt;the car's reason&gt;" (the
 * stage, the block and the verdict come from the car). Decides nothing: AA picked the way, {@code DangerMoves} found
 * the cells. Pure (no game classes): JUnit runs it on a fake car. Never throws.
 */
public final class Moves {
    public enum Phase { IDLE, RUNNING, ARRIVED, FAILED }

    /** Seconds a leg may take (ticks), per kind. */
    static final long WALK_TICKS = 20 * 30, PILLAR_TICKS = 20 * 15, DIG_TICKS = 20 * 40;

    private record Leg(String name, Consumer<Car> start, long limit) { }

    private final Car car;
    private final Deque<Leg> legs = new ArrayDeque<>();
    private Leg leg;
    private String way = "none", why = "idle";
    private Phase phase = Phase.IDLE;
    private long now, legStart;

    public Moves(Car car) {
        this.car = car;
    }

    // ---- the ways ----

    /** Cover: GoalGetToBlock to the cell out of the shooter's sight. */
    public void cover(int[] cell) {
        begin("cover", new Leg("walk to cover " + c(cell), car -> car.cover(cell), WALK_TICKS));
    }

    /** Flee: GoalRunAway {@code dist} from the hostiles' positions. */
    public void flee(int dist, List<int[]> from) {
        List<int[]> f = List.copyOf(from);
        begin("flee", new Leg("run " + dist + " from " + f.size() + " mob(s)", car -> car.runAway(dist, f), WALK_TICKS));
    }

    /** Pillar: GoalBlock {@code up} over the feet with placing on; Baritone pillars on the bag's throwaway blocks. */
    public void pillar(int[] feet, int up) {
        int[] top = {feet[0], feet[1] + up, feet[2]};
        begin("pillar", new Leg("pillar " + up + " up to " + c(top), car -> car.pillar(top), PILLAR_TICKS));
    }

    /**
     * Burrow: BurrowProcess digs the {@code depth} cells under the feet (the bot drops into each), then seals the cell
     * over the head ({@code depth - 2} under the old feet) from the bag.
     */
    public void burrow(int[] top, int depth) {
        List<int[]> dig = new ArrayList<>();
        for (int k = 1; k <= depth; k++) dig.add(new int[]{top[0], top[1] - k, top[2]});
        int[] seal = {top[0], top[1] - (depth - 2), top[2]};
        begin("burrow", new Leg("burrow " + depth + " down at " + c(top), car -> car.burrow(dig, null, List.of(seal), (int) DIG_TICKS), DIG_TICKS));
    }

    /**
     * Dig-in: BurrowProcess digs the two wall cells beside the entrance ({@code dx, dz} one step), steps into the foot
     * one, and seals the entrance's two cells behind.
     */
    public void digIn(int[] entrance, int dx, int dz) {
        int[] f = {entrance[0] + dx, entrance[1], entrance[2] + dz}, h = {f[0], f[1] + 1, f[2]};
        int[] e2 = {entrance[0], entrance[1] + 1, entrance[2]};
        begin("dig in", new Leg("dig into the wall at " + c(f), car -> car.burrow(List.of(f, h), f, List.of(entrance.clone(), e2), (int) DIG_TICKS), DIG_TICKS));
    }

    /** The camera: Baritone's LookBehavior target this tick. */
    public void lookAt(double x, double y, double z) {
        car.look(x, y, z);
    }

    // ---- the state machine ----

    private void begin(String way, Leg first) {
        if (phase == Phase.RUNNING) car.stop("replaced by " + way);
        legs.clear();
        this.way = way;
        phase = Phase.RUNNING;
        why = "started";
        startLeg(first);
    }

    private void startLeg(Leg l) {
        leg = l;
        legStart = now;
        why = l.name();
        l.start().accept(car);
    }

    /** One tick: follows the car. Returns the phase after it. */
    public Phase tick(long tick) {
        now = tick;
        if (phase != Phase.RUNNING) return phase;
        Car.Status s;
        try {
            s = car.status();
        } catch (RuntimeException e) {
            s = Car.Status.failed("error: " + e);
        }
        if (s == null) s = Car.Status.failed("the car gave no status");
        switch (s.state()) {
            case FAILED -> fail(s.why());
            case DONE -> {
                if (!legs.isEmpty()) startLeg(legs.poll());
                else {
                    phase = Phase.ARRIVED;
                    why = leg.name() + " done" + (s.why() == null || s.why().isEmpty() ? "" : ": " + s.why());
                }
            }
            default -> {
                if (now - legStart > leg.limit()) {
                    car.stop("timed out");
                    fail("timed out after " + leg.limit() / 20 + " s (" + s.why() + ")");
                } else if (s.why() != null) why = leg.name() + ": " + s.why();
            }
        }
        return phase;
    }

    private void fail(String reason) {
        phase = Phase.FAILED;
        why = "gave up at " + (leg == null ? "?" : leg.name()) + ": " + reason;
    }

    /** Ends the way (keys and goals through the car); the danger hold stays until {@link #release()}. */
    public void cancel(String reason) {
        if (phase == Phase.RUNNING) car.stop(reason);
        legs.clear();
        if (phase == Phase.RUNNING) {
            phase = Phase.FAILED;
            why = "cancelled at " + (leg == null ? "?" : leg.name()) + ": " + reason;
        }
    }

    /** Baritone back to the job; Moves idle. */
    public void release() {
        if (phase == Phase.RUNNING) car.stop("released");
        car.release();
        legs.clear();
        leg = null;
        phase = Phase.IDLE;
        way = "none";
    }

    public Phase phase() { return phase; }

    public String way() { return way; }

    /** The last line: the leg running, "... done", or "gave up at &lt;leg&gt;: &lt;reason&gt;". */
    public String why() { return why; }

    private static String c(int[] p) {
        return p[0] + " " + p[1] + " " + p[2];
    }
}
