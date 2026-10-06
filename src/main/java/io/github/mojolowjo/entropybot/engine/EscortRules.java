package io.github.mojolowjo.entropybot.engine;

import java.util.List;
import java.util.Locale;

/**
 * C7 "escort": the rules without Minecraft types, so JUnit pins them. The bot follows a player and fights what
 * threatens them: a mob that targets the player first, else the one nearest the player within the radius.
 * <p>
 * Whether a mob targets someone is a server-side fact ({@code Mob.getTarget()} is null on the client). What the
 * client does get is the synced "aggressive" flag ({@code Mob.isAggressive()}) and the head yaw, so "targets the
 * player" = aggressive and its head points at the player (within {@link #AIM_DEG}) rather than at the bot.
 */
public final class EscortRules {
    private EscortRules() {}

    public static final int RADIUS = 6;             // "escort me" fights what comes this close to the player
    public static final int RADIUS_MAX = 16;
    public static final double STAND_MIN = 2, STAND_MAX = 4, STAND_AT = 3;   // the bot's distance from the player
    public static final double AIM_DEG = 35;        // head yaw within this of the player = "looks at them"
    public static final int FEED_AT = 6;            // the player's food at or below this: throw them food
    public static final int KEEP_FOOD = 8;          // the bot keeps this many food items for itself
    public static final int FEED_GIVE = 4;
    public static final long FEED_COOLDOWN_MS = 60_000;
    public static final double WARN_DIST = 12;      // a creeper or skeleton this close to the player: whisper once

    /** One mob near the guarded player (positions in blocks, yaw in degrees as Minecraft has it: 0 = south). */
    public record Mob(int key, String id, double x, double y, double z, boolean aggressive, float headYaw, boolean creeper) {}

    /** The parsed command. word: "me" | "player" | "off" | "status" | "error"; name: the player (for "player"). */
    public record Cmd(String word, String name, int radius, String error) {}

    /** "me [r]" | "<player> [r]" | "off" | "status" | "" (= status). */
    public static Cmd parse(String rest) {
        String[] w = rest == null ? new String[0] : rest.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (w.length == 0 || w[0].isEmpty() || w[0].equals("status")) return new Cmd("status", null, 0, null);
        if (w[0].equals("off") || w[0].equals("stop")) return new Cmd("off", null, 0, null);
        if (w.length > 2) return new Cmd("error", null, 0, "usage: escort me [radius] | escort <player> [radius] | escort off | escort status");
        int r = RADIUS;
        if (w.length == 2) {
            if (!w[1].matches("\\d{1,3}")) return new Cmd("error", null, 0, "error: the radius is a number of blocks (2-" + RADIUS_MAX + "), e.g. escort me 10");
            r = Integer.parseInt(w[1]);
            if (r < 2 || r > RADIUS_MAX) return new Cmd("error", null, 0, "error: the radius is 2-" + RADIUS_MAX + " blocks");
        }
        if (w[0].equals("me")) return new Cmd("me", null, r, null);
        if (!w[0].matches("[a-z0-9_]{3,16}")) return new Cmd("error", null, 0, "error: \"" + w[0] + "\" is not a player name");
        // keep the name's case as typed for the whisper; matching players ignores case anyway
        String typed = rest.trim().split("\\s+")[0];
        return new Cmd("player", typed, r, null);
    }

    /** Minecraft's yaw (0 = +z south, 90 = -x west) from a to b. */
    public static double yawTo(double ax, double az, double bx, double bz) {
        return Math.toDegrees(Math.atan2(-(bx - ax), bz - az));
    }

    static double angleDiff(double a, double b) {
        double d = ((a - b) % 360 + 540) % 360 - 180;
        return Math.abs(d);
    }

    /** The mob is aggressive and its head points at the player (more than at the bot). */
    public static boolean targetsPlayer(Mob m, double px, double pz, double bx, double bz) {
        if (!m.aggressive()) return false;
        double toP = angleDiff(m.headYaw(), yawTo(m.x(), m.z(), px, pz));
        double toB = angleDiff(m.headYaw(), yawTo(m.x(), m.z(), bx, bz));
        return toP <= AIM_DEG && toP <= toB;
    }

    /**
     * The mob to fight for the player, or -1: within the radius of the player (a mob targeting them counts up to
     * twice the radius, a skeleton shoots from afar), targeting them first, then the nearest to them.
     */
    public static int pick(List<Mob> mobs, double px, double py, double pz, double bx, double bz, int radius) {
        int best = -1;
        boolean bestTargets = false;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < mobs.size(); i++) {
            Mob m = mobs.get(i);
            double d = dist(m.x(), m.y(), m.z(), px, py, pz);
            boolean t = targetsPlayer(m, px, pz, bx, bz);
            if (d > (t ? radius * 2.0 : radius)) continue;
            if (best < 0 || (t && !bestTargets) || (t == bestTargets && d < bestD)) {
                best = i;
                bestTargets = t;
                bestD = d;
            }
        }
        return best;
    }

    /**
     * Where the bot should stand when nothing threatens: null when it is 2-4 blocks from the player already, else
     * the spot 3 blocks from the player on the bot's side (never in their face; behind them would need their look
     * direction, which the client of another player gives but the companion does not). {x, z}.
     */
    public static double[] standoff(double px, double pz, double bx, double bz) {
        double dx = bx - px, dz = bz - pz, d = Math.sqrt(dx * dx + dz * dz);
        if (d >= STAND_MIN && d <= STAND_MAX) return null;
        if (d < 0.1) { dx = 1; dz = 0; d = 1; }
        return new double[]{px + dx / d * STAND_AT, pz + dz / d * STAND_AT};
    }

    /**
     * Between a creeper and the player: on the line from the player to the creeper, half way but at least 1.5 from
     * the player and at least 1.5 from the creeper (a hit from there knocks it away from both). {x, z}.
     */
    public static double[] interpose(double px, double pz, double cx, double cz) {
        double dx = cx - px, dz = cz - pz, d = Math.sqrt(dx * dx + dz * dz);
        if (d < 0.1) return new double[]{px, pz};
        double at = Math.max(Math.min(d / 2, d - 1.5), Math.min(1.5, d / 2));
        return new double[]{px + dx / d * at, pz + dz / d * at};
    }

    /**
     * How many food items to throw the player now: 0 unless their food is known and at or below {@link #FEED_AT},
     * the last throw was {@link #FEED_COOLDOWN_MS} ago, and the bot carries more than its own {@link #KEEP_FOOD}.
     * playerFood -1 = unknown (an old companion without the food field).
     */
    public static int feed(int playerFood, int carried, long nowMs, long lastFedMs) {
        if (playerFood < 0 || playerFood > FEED_AT) return 0;
        if (nowMs - lastFedMs < FEED_COOLDOWN_MS) return 0;
        return Math.max(0, Math.min(FEED_GIVE, carried - KEEP_FOOD));
    }

    /** A creeper or a skeleton kind (stray, wither skeleton, bogged count) is worth a warning. */
    public static boolean warnKind(String id) {
        if (id == null) return false;
        String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return p.equals("creeper") || p.contains("skeleton") || p.equals("stray") || p.equals("bogged");
    }

    /**
     * The once-per-mob warning while escorting, or null: a creeper or skeleton that came within {@link #WARN_DIST}
     * of the player and is closer than last time (approaching). The player's look direction is unknown, so the
     * text names the compass side.
     */
    public static String warning(String id, double prevDist, double dist, double px, double pz, double mx, double mz, boolean warnedBefore) {
        if (warnedBefore || !warnKind(id) || dist > WARN_DIST || !(dist < prevDist)) return null;
        String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return "Careful: a " + name.replace('_', ' ') + " approaching from the " + compass(px, pz, mx, mz) + ", " + Math.round(dist) + " blocks from you.";
    }

    /** The compass side of m seen from p (north = -z). */
    public static String compass(double px, double pz, double mx, double mz) {
        double deg = Math.toDegrees(Math.atan2(mx - px, -(mz - pz)));     // 0 = north, 90 = east
        deg = (deg + 360) % 360;
        String[] n = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return n[(int) Math.round(deg / 45) % 8];
    }

    static double dist(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx, dy = ay - by, dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
