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
    static long walkTicks() { return (long) (20 * io.github.mojolowjo.entropybot.aa.brain.Tuning.get("moves.walkS")); }
    static long pillarTicks() { return (long) (20 * io.github.mojolowjo.entropybot.aa.brain.Tuning.get("moves.pillarS")); }
    static long digTicks() { return (long) (20 * io.github.mojolowjo.entropybot.aa.brain.Tuning.get("moves.digS")); }

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
        begin("cover", new Leg("walk to cover " + c(cell), car -> car.cover(cell), walkTicks()));
    }

    /** Flee: GoalRunAway {@code dist} from the hostiles' positions. */
    public void flee(int dist, List<int[]> from) {
        List<int[]> f = List.copyOf(from);
        begin("flee", new Leg("run " + dist + " from " + f.size() + " mob(s)", car -> car.runAway(dist, f), walkTicks()));
    }

    /** Pillar: GoalBlock {@code up} over the feet with placing on; Baritone pillars on the bag's throwaway blocks. */
    public void pillar(int[] feet, int up) {
        int[] top = {feet[0], feet[1] + up, feet[2]};
        begin("pillar", new Leg("pillar " + up + " up to " + c(top), car -> car.pillar(top), pillarTicks()));
    }

    /**
     * Dig-in: BurrowProcess digs the two wall cells beside the entrance ({@code dx, dz} one step), steps into the foot
     * one, and seals the entrance's two cells behind.
     */
    public void digIn(int[] entrance, int dx, int dz) {
        int[] f = {entrance[0] + dx, entrance[1], entrance[2] + dz}, h = {f[0], f[1] + 1, f[2]};
        int[] e2 = {entrance[0], entrance[1] + 1, entrance[2]};
        begin("dig in", new Leg("dig into the wall at " + c(f), car -> car.burrow(List.of(f, h), f, List.of(entrance.clone(), e2), (int) digTicks()), digTicks()));
    }

    // ---- backbone step 4: the reflexes' ways (a car made with the reflex priority runs them) ----

    /** Ticks a reflex leg may take. */
    static long reflexTicks() { return (long) (20 * io.github.mojolowjo.entropybot.aa.brain.Tuning.get("moves.reflexS")); }

    /** Air: the head in open air (the surface or a pocket); Baritone picks the cell. */
    public void swimUp() {
        begin("swim up", new Leg("swim up to air", Car::breathe, reflexTicks()));
    }

    /** Reflex choosers: the fork's "burrow here" ({@code up}: the cells over the head, out of water); the fork picks them. */
    public void burrowHere(String way, int depth, boolean up) {
        begin(way, new Leg(way + " " + depth + (up ? " up" : " down") + " here", car -> car.burrowHere(depth, up, (int) digTicks()), digTicks()));
    }

    /** Reflex choosers: the feet in water; Baritone picks the water. */
    public void toWater(String way) {
        begin(way, new Leg(way, Car::toWater, reflexTicks()));
    }

    /** The creeper flee: GoalRunAway {@code dist} from the hostiles, leaning toward FleeHeading's {@code prefer}. */
    public void flee(int dist, List<int[]> from, double[] prefer) {
        List<int[]> f = List.copyOf(from);
        double[] h = prefer == null ? null : prefer.clone();
        begin("flee", new Leg("run " + dist + " from " + f.size() + " mob(s)" + (h == null ? "" : " on the flee heading"),
                car -> car.runAway(dist, f, h), reflexTicks()));
    }

    /** Lava / fire: GoalRunAway {@code dist} from the hazard cells ({@code what}: "lava", "fire"). */
    public void escape(String what, int dist, List<int[]> cells) {
        List<int[]> f = List.copyOf(cells);
        begin("escape " + what, new Leg("run " + dist + " from " + f.size() + " " + what + " cell(s)", car -> car.runAway(dist, f, null), reflexTicks()));
    }

    /** Baritone holds a path for the running leg's goal (the air reflex's jump gap is over). */
    public boolean pathReady() {
        return phase == Phase.RUNNING && car.pathReady();
    }

    /** The tick the running leg started (for the latency line). */
    public long legStart() { return legStart; }

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

    /**
     * 0.39.s7 (staged: a danger burrow 'timed out after 40 s' on its first tick): the clock while no way runs, so a way
     * started between ticks starts its leg at the tick now, not at the last tick a way ran.
     */
    public void clock(long tick) {
        if (phase != Phase.RUNNING) now = tick;
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
