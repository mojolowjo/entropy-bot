package io.github.mojolowjo.entropybot.engine;

/**
 * 0.23.2: the owner's creeper heuristic (2026-10-07), the state machine. On a creeper within {@link #START}: already
 * sprinting away = keep sprinting; standing (or walking anywhere else) = one melee hit when it is in reach (the
 * knockback), then sprint straight away until it is {@link #SAFE} away; then the reflexes re-evaluate (a duel only with
 * the bow setting, or with a big margin). "flee" mode never hits. Pure Java: the reflexes drive the keys.
 */
public final class CreeperSprint {
    public enum Act { HIT, SPRINT, DONE }

    public static final double START = 6;
    public static final double SAFE = 7;
    public static final int MAX_TICKS = 100;
    /** Blocks a tick that count as moving (a sprint is about 0.28). */
    static final double MOVING = 0.15;
    static final double AWAY_DOT = 0.5;

    private boolean active;
    private int creeperId = -1;
    private long startTick;
    private Act last;
    private double dirX, dirZ;
    private String how, endWhy;

    public boolean active() { return active; }

    public int creeperId() { return creeperId; }

    public double dirX() { return dirX; }

    public double dirZ() { return dirZ; }

    public String how() { return how; }

    public String endWhy() { return endWhy; }

    public long startTick() { return startTick; }

    /** Whether the bot is already sprinting away from the creeper: sprinting, moving, and heading within 60 degrees of away. */
    public static boolean sprintingAway(boolean sprinting, double vx, double vz, double awayX, double awayZ) {
        double v = Math.sqrt(vx * vx + vz * vz), a = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (!sprinting || v < MOVING || a < 1e-6) return false;
        return (vx * awayX + vz * awayZ) / (v * a) >= AWAY_DOT;
    }

    /**
     * Starts on a creeper. sprintingAway: {@link #sprintingAway}; mayHit: in reach, may be attacked and the mode is not
     * flee; (vx, vz): the bot's motion; (escX, escZ): the chosen escape heading (unit). Returns the first act.
     */
    public Act start(long now, int id, boolean sprintingAway, boolean mayHit, double vx, double vz, double escX, double escZ) {
        chain = id == lastId && now - lastEnd < CHASE_TICKS ? chain + 1 : 0;
        safe = Math.min(SAFE + 5 * chain, 17);
        active = true;
        creeperId = id;
        startTick = now;
        endTick = now;
        endWhy = null;
        if (sprintingAway) {
            double v = Math.sqrt(vx * vx + vz * vz);
            dirX = vx / v;
            dirZ = vz / v;
            how = "already sprinting away: kept going";
            last = Act.SPRINT;
        } else {
            dirX = escX;
            dirZ = escZ;
            how = mayHit ? "hit, then sprint" : "sprint";
            last = mayHit ? Act.HIT : Act.SPRINT;
        }
        return last;
    }

    /** After a sprint the bot holds still (no job walking it back) while the creeper is this close, for {@link #WATCH_TICKS}. */
    public static final double WATCH = 10;
    public static final int WATCH_TICKS = 100;
    /** A creeper that keeps coming within {@link #CHASE_TICKS} of the last sprint: each new sprint runs 5 further (at most 17). */
    public static final int CHASE_TICKS = 60;

    private int lastId = -1, chain;
    private long lastEnd = Long.MIN_VALUE / 2;
    private double safe = SAFE;
    private long endTick;

    /** The distance this sprint runs to. */
    public double safe() { return safe; }

    /** Whether the bot just sprinted from this creeper (hold still and watch it, don't let the job walk it back). */
    public boolean watching(long now, int id) { return !active && id == lastId && now - lastEnd < WATCH_TICKS; }

    /** One tick while active: dist = the creeper's distance (NaN = gone). */
    public Act step(long now, double dist) {
        if (!active) return Act.DONE;
        endTick = now;
        if (Double.isNaN(dist)) return end("it is gone");
        if (dist >= safe) return end("got " + Math.round(dist * 10) / 10.0 + " blocks away");
        if (now - startTick >= MAX_TICKS) return end("gave up after " + MAX_TICKS / 20 + " s");
        last = Act.SPRINT;
        return last;
    }

    /** Turns the heading (a wall ahead: the reflexes pick a new one from the grid). */
    public void steer(double x, double z) {
        dirX = x;
        dirZ = z;
    }

    public void abort(String why) {
        if (active) end(why);
    }

    private Act end(String why) {
        active = false;
        lastId = creeperId;
        lastEnd = Long.MIN_VALUE / 2;
        if (!"it is gone".equals(why)) lastEnd = endTick;
        endWhy = why;
        last = Act.DONE;
        return Act.DONE;
    }

    /** Whether a creeper duel may follow the sprint: the bow setting always; melee only with a big margin; flee never. */
    public static boolean duelAfter(CreeperRules.Mode mode, boolean bigMargin) {
        return switch (mode) {
            case BOW -> true;
            case MELEE -> bigMargin;
            case FLEE -> false;
        };
    }
}
