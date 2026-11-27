package io.github.mojolowjo.entropybot.ghost;

import java.util.List;
import java.util.Locale;

/**
 * Dry-run package 3: one Baritone path walked by the ghost, judged tick by tick with the plan's refusal rules
 * ("Dry-run checker plan (ghost body)", section "How it works", step 4). It holds the path's points (feet blocks), which
 * point the ghost heads for, the ticks spent on it, the hops used, and answers a {@link Verdict} as soon as one is
 * known: PASS when the ghost's feet reach the last point, or REFUSE with the first reason found. Each point gets
 * {@code ghost.pointTicks} (40) ticks; after {@code ghost.hopAfterTicks} without progress it may hop, up to
 * {@code ghost.hops} (3) times (Kestrel's rule).
 *
 * <p>Pure (JUnit: PathCheckTest). Loader notes: none; {@code GhostChecker} fills {@link Facts} from the ghost.
 */
public final class PathCheck {
    public enum Kind { PASS, REFUSE, UNCHECKED }

    public enum Reason {
        NONE("passes"), NO_PROGRESS("no progress toward the next point"), HURT_FALL("a fall that would hurt"),
        HAZARD("touching lava, fire or magma"), OFF_HEIGHT("ends off the goal height"), STUCK("stuck in a cobweb or bush"),
        OUT_OF_AIR("out of breath under water"), NEEDS_BREAK("the path needs a block broken"),
        BUDGET("not checked in time"), REPLACED("Baritone replaced the path first"), DOOR("needs a door or gate opened"),
        ERROR("the ghost failed"), SHORT("no move to check");

        public final String words;

        Reason(String words) { this.words = words; }
    }

    /** The answer: what, why, where (a block), after how many ghost ticks, and a note (a number or the block). */
    public record Verdict(Kind kind, Reason reason, int x, int y, int z, int tick, String note) {
        public static Verdict unchecked(Reason r, String note) { return new Verdict(Kind.UNCHECKED, r, 0, 0, 0, 0, note); }

        public String line() {
            if (kind == Kind.PASS) return "pass (" + tick + " ghost ticks)";
            String at = kind == Kind.REFUSE ? String.format(Locale.ROOT, " at %d %d %d after %d ghost ticks", x, y, z, tick) : "";
            return kind.name().toLowerCase(Locale.ROOT) + ": " + reason.words + at + (note == null || note.isEmpty() ? "" : " (" + note + ")");
        }
    }

    /** What the ghost is like after one tick. {@code fallDamage}: damage of a landing this tick (0 none). */
    public record Facts(double x, double y, double z, int feetX, int feetY, int feetZ, boolean onGround, boolean inLava,
                        boolean touchingFire, boolean onMagma, int fallDamage, boolean outOfAir, boolean stuckBlock) {}

    /** A path point (feet block). */
    public record Point(int x, int y, int z) {}

    static final int LOOK_AHEAD = 4;           // structural: a point may be passed by up to 4 later points (a fast ghost)
    static final double HEIGHT_SLACK = 0.6;    // structural: the plan's "more than 0.6 blocks off the goal height"

    private final List<Point> points;
    private final List<MoveKind> kinds;
    private final int pointTicks, hopAfter, maxHops;
    private int next = 1;            // the point the ghost heads for (0 is where it starts)
    private int onPoint, hops, ticks, stall;
    private double best = Double.MAX_VALUE;

    /** {@code kinds.get(i)} is the move that leads into point i (index 0 unused). */
    public PathCheck(List<Point> points, List<MoveKind> kinds, int pointTicks, int hopAfter, int maxHops) {
        if (points.size() != kinds.size()) throw new IllegalArgumentException(points.size() + " points but " + kinds.size() + " moves");
        this.points = List.copyOf(points);
        this.kinds = List.copyOf(kinds);
        this.pointTicks = pointTicks;
        this.hopAfter = hopAfter;
        this.maxHops = maxHops;
    }

    public int size() { return points.size(); }
    public int next() { return next; }
    public int ticks() { return ticks; }
    public Point target() { return points.get(Math.min(next, points.size() - 1)); }
    public Point source() { return points.get(Math.max(0, Math.min(next, points.size() - 1) - 1)); }
    public MoveKind kind() { return kinds.get(Math.min(next, kinds.size() - 1)); }

    /** May the pilot hop this tick (stalled long enough, hops left)? Counting the hop is {@link #hopped}. */
    public boolean mayHop() { return stall >= hopAfter && hops < maxHops; }

    public void hopped() {
        hops++;
        stall = 0;
    }

    /** A path with no move gives SHORT before any tick. */
    public Verdict start() {
        return points.size() < 2 ? Verdict.unchecked(Reason.SHORT, points.size() + " points") : null;
    }

    /** One ghost tick's facts; a verdict when known, else null (go on). */
    public Verdict tick(Facts f) {
        ticks++;
        if (f.inLava() || f.touchingFire() || f.onMagma())
            return refuse(Reason.HAZARD, f, f.inLava() ? "lava" : f.touchingFire() ? "fire" : "magma");
        if (f.fallDamage() > 0) return refuse(Reason.HURT_FALL, f, f.fallDamage() + " damage");
        if (f.outOfAir()) return refuse(Reason.OUT_OF_AIR, f, "");
        for (int j = next; j < Math.min(points.size(), next + LOOK_AHEAD + 1); j++) {
            Point p = points.get(j);
            if (p.x() == f.feetX() && p.y() == f.feetY() && p.z() == f.feetZ()) {
                next = j + 1;
                onPoint = 0;
                hops = 0;
                stall = 0;
                best = Double.MAX_VALUE;
                if (next >= points.size()) return new Verdict(Kind.PASS, Reason.NONE, p.x(), p.y(), p.z(), ticks, "");
                return null;
            }
        }
        Point goal = points.get(points.size() - 1);
        if (next == points.size() - 1 && f.onGround() && goal.x() == f.feetX() && goal.z() == f.feetZ()
                && Math.abs(f.y() - goal.y()) > HEIGHT_SLACK)
            return refuse(Reason.OFF_HEIGHT, f, String.format(Locale.ROOT, "y %.2f, goal %d", f.y(), goal.y()));
        Point t = points.get(next);
        double d = Math.abs(t.x() + 0.5 - f.x()) + Math.abs(t.y() - f.y()) + Math.abs(t.z() + 0.5 - f.z());
        if (d < best - 0.01) {         // structural: 1 cm counts as progress
            best = d;
            stall = 0;
        } else stall++;
        if (++onPoint > pointTicks) return refuse(f.stuckBlock() ? Reason.STUCK : Reason.NO_PROGRESS, f,
                "point " + next + " of " + (points.size() - 1) + ", " + kinds.get(next).name().toLowerCase(Locale.ROOT));
        return null;
    }

    private Verdict refuse(Reason r, Facts f, String note) {
        return new Verdict(Kind.REFUSE, r, f.feetX(), f.feetY(), f.feetZ(), ticks, note);
    }
}
