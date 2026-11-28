package io.github.mojolowjo.entropybot.nav.steer;

import java.util.Locale;

/**
 * Ghost steering S1 (plan to-do/nav-steer.md, m0j 2026-11-27: "simulate the action before doing it"): picks the keys for
 * the next few ticks by trying each {@link Choice#OPTIONS option} on a {@link Sim} for {@code horizon} ticks and keeping
 * the best {@link Score}. It faces the target every tick (an option's {@code turn} is added to that), re-tests every
 * {@code every} ticks, and keeps a new choice at least {@code hold} ticks unless the held one now hurts, so it does
 * not flip-flop. It never acts on an untested choice: when the budget cuts a test short, the options not tried in full
 * are left out, and when none was, it holds the last choice (counted as a cut).
 *
 * <p>It gives up, with a reason, when the real body has not got {@code progress} blocks closer to the aim for
 * {@code giveUpTicks} ticks. It says "arrived" once the feet are in a target cell.
 *
 * <p>Pure (JUnit: SteererTest). Loader notes: none; S2 feeds it the ghost player and the real feet.
 */
public final class Steerer {
    /** May the steerer run one more sim tick now? The live one stops at the per-tick ms budget. */
    @FunctionalInterface
    public interface Budget {
        boolean more();

        Budget NONE = () -> true;
    }

    public enum Status { STEERING, ARRIVED, GAVE_UP }

    /** This tick's answer: the keys, the facing, and why. */
    public record Decision(Choice choice, float yaw, Status status, String why) {}

    private final SteerConfig cfg;
    private Choice held;
    private boolean heldHurts;
    private int heldTicks, sinceTest, stillTicks;
    private double best = Double.MAX_VALUE;
    private long tests, tries, cuts, switches;
    private String last = "none yet";

    public Steerer(SteerConfig cfg) {
        this.cfg = cfg;
    }

    /** Starts afresh for a new target (the progress clock and the held choice). */
    public void restart() {
        held = null;
        heldHurts = false;
        heldTicks = sinceTest = stillTicks = 0;
        best = Double.MAX_VALUE;
    }

    /** One game tick: the real feet at (x, y, z), heading for {@code t}, trying options on {@code sim}. */
    public Decision tick(double x, double y, double z, Target t, Sim sim, Budget budget) {
        if (t.holds(x, y, z)) return new Decision(Choice.STILL, yawTo(x, z, t), Status.ARRIVED, "feet in a target cell");
        double d = t.distance(x, y, z, cfg.upWeight());
        if (d < best - cfg.progress()) {
            best = d;
            stillTicks = 0;
        } else if (++stillTicks > cfg.giveUpTicks()) {
            return new Decision(Choice.STILL, yawTo(x, z, t), Status.GAVE_UP, String.format(Locale.ROOT,
                    "no choice got closer than %.1f blocks for %d ticks (feet at %.1f %.1f %.1f, last: %s)", best, stillTicks, x, y, z, last));
        }
        heldTicks++;
        if (held == null || ++sinceTest >= cfg.every()) test(t, sim, budget);
        Choice c = held == null ? Choice.STILL : held;
        return new Decision(c, yawTo(x, z, t) + c.turn(), Status.STEERING, last);
    }

    private void test(Target t, Sim sim, Budget budget) {
        sinceTest = 0;
        tests++;
        Choice pick = null;
        double pickScore = Double.MAX_VALUE, pickRaw = 0, heldScore = Double.MAX_VALUE;
        boolean pickHurts = false, heldTried = false;
        int full = 0;
        for (Choice o : Choice.OPTIONS) {
            sim.reset();
            int reachedAt = -1, n = 0;
            while (n < cfg.horizon() && reachedAt < 0) {
                if (!budget.more()) break;
                sim.step(o, yawTo(sim.x(), sim.z(), t) + o.turn());
                n++;
                if (t.holds(sim.x(), sim.y(), sim.z())) reachedAt = n;
            }
            if (n < cfg.horizon() && reachedAt < 0) break;                // cut short: this and the rest are untried
            full++;
            tries++;
            double s = Score.of(reachedAt, t.distance(sim.x(), sim.y(), sim.z(), cfg.upWeight()), sim.hurt(), sim.air(), cfg);
            if (o.equals(held)) {
                heldTried = true;
                heldScore = s;
                heldHurts = sim.hurt();
                s -= cfg.keepBonus();
            }
            if (s < pickScore) {
                pickScore = s;
                pickRaw = o.equals(held) ? heldScore : s;
                pick = o;
                pickHurts = sim.hurt();
            }
        }
        if (full < Choice.OPTIONS.size()) cuts++;
        if (pick == null) {
            last = "budget cut before any try; holding " + (held == null ? "still" : held.name());
            return;
        }
        boolean mayChange = held == null || heldTicks >= cfg.hold() || (heldTried && heldHurts) || !heldTried;
        if (!pick.equals(held) && mayChange) {
            held = pick;
            heldHurts = pickHurts;
            heldTicks = 0;
            switches++;
        }
        last = String.format(Locale.ROOT, "%s (score %.2f, %d of %d tried)", held.name(),
                held.equals(pick) ? pickRaw : heldScore,
                full, Choice.OPTIONS.size());
    }

    /** The facing from (x, z) toward the target's aim point (the game's yaw: 0 = south, +z; LookMath.toward's form). */
    static float yawTo(double x, double z, Target t) {
        double dx = t.x() - x, dz = t.z() - z;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** The counters for {@code aa nav status}. */
    public String status() {
        return String.format(Locale.ROOT, "steer: %d tests, %d tries, %d cut short, %d switches; last %s", tests, tries, cuts, switches, last);
    }

    public Choice held() { return held; }
    public long cuts() { return cuts; }
    public long switches() { return switches; }
}
