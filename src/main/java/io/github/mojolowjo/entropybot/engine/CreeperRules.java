package io.github.mojolowjo.entropybot.engine;

import io.github.mojolowjo.entropybot.guard.Box;

import java.util.List;
import java.util.Locale;

/**
 * The creeper duel's rules without Minecraft types (B7e C, 2026-10-04), so JUnit can pin them: when to take a creeper on
 * instead of running, the retreat-line check, the hit-and-back cycle, and the bow's aim.
 *
 * <p>Vanilla 1.21.1 facts it rests on (checked in the sources): {@code SwellGoal.canUse} starts the fuse when the target
 * is within 3 blocks ({@code distanceToSqr < 9}) or the creeper is already swelling; {@code SwellGoal.tick} keeps
 * {@code swellDir} at +1 while the target is within 7 ({@code distanceToSqr <= 49}) and in sight, else -1; the goal has
 * the MOVE flag, so a swelling creeper stands still. {@code Creeper.tick} adds swellDir to {@code swell} each tick and
 * explodes at {@code maxSwell} = 30; {@code DATA_SWELL_DIR} and {@code DATA_IS_POWERED} are synced entity data, so the
 * client sees the fuse ({@code getSwellDir}, {@code getSwelling(partial)} = swell / 28) and a charged creeper. The
 * blast is power 3 (6 when charged). {@code Player.attack}: knockback +1 when sprinting with attack strength > 0.9,
 * then sprinting stops; the server accepts a hit while the target's box is within entity reach (3.0) + 1 of the eyes.
 * Bow: full draw after 20 ticks ({@code BowItem.getPowerForTime}), speed 3.0; an arrow moves, then slows by 0.99 and
 * falls 0.05 per tick ({@code AbstractArrow.tick}, {@code getDefaultGravity}).
 */
public final class CreeperRules {
    private CreeperRules() {}

    /** What the bot does about creepers: run as before, fight with a sword or axe, or also shoot from range. */
    public enum Mode {
        FLEE, MELEE, BOW;

        public String word() { return name().toLowerCase(Locale.ROOT); }

        /** null or unknown = the default, MELEE. */
        public static Mode parse(String s) {
            if (s == null) return MELEE;
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "flee", "run", "off" -> FLEE;
                case "bow" -> BOW;
                default -> MELEE;
            };
        }
    }

    public static final double LOOK = 16;            // the creeper must be the only one within this
    public static final float ENGAGE_HEALTH = 12f;    // take one on only at this health or more
    public static final float ABORT_HEALTH = 8f;      // ... and stop the duel at this or less
    public static final int PROTECT_MARGIN = 16;      // never a duel within this of a protect box
    public static final double LINE_NEED = 5;         // a clear straight retreat line this long
    public static final double HIT = 3.0;             // hit from this close (center to center)
    public static final double WAIT_MIN = 4;          // wait for the cooldown at 4-6 blocks
    public static final double WAIT_MAX = 6;
    public static final double SAFE = 7;              // beyond this the fuse cools (SwellGoal: distanceToSqr > 49)
    public static final double VALVE = 4.5;           // swelling this close with no room: run
    public static final float READY = 0.95f;          // attack strength for a full (knockback) hit
    public static final float SWELL_OK = 0.2f;        // charge only while the fuse is below this
    public static final float SWELL_ABORT = 0.4f;     // a charge that meets a fuse this far along turns back
    public static final int CHARGE_TICKS = 40;        // a charge that hasn't arrived by then waits again
    public static final int MAX_MISSES = 4;           // charges in a row without damage, then give up
    public static final int GIVE_UP_TICKS = 600;      // 30 s per duel
    public static final double BOW_MIN = 8, BOW_MAX = 15;
    public static final int BOW_DRAW = 20;            // full draw
    public static final double ARROW_SPEED = 3.0;

    // ---- engage or flee ----

    /**
     * What the live side sees when a creeper comes within the look radius. weaponRank: 2 a sword, 1 an axe, 0 neither;
     * creepers: how many within {@link #LOOK}; otherMonsters: another monster counts as a threat; lineClear: how far the
     * straight retreat line is walkable; lineOpen: it isn't a tunnel; banned: this creeper was given up on.
     */
    public record Situation(Mode mode, int weaponRank, float health, int creepers, boolean otherMonsters, boolean powered,
                            boolean nearProtect, boolean nearBuilds, double lineClear, boolean lineOpen, String lineStop,
                            boolean banned) {}

    /** Why the bot runs instead of fighting, or null: take it on. */
    public static String refusal(Situation s) {
        if (s.mode() == Mode.FLEE) return "creepers: flee is set";
        if (s.banned()) return "gave up on this one";
        if (s.weaponRank() <= 0) return "no sword or axe";
        if (s.health() < ENGAGE_HEALTH) return "health " + fmt(s.health()) + " is under " + Math.round(ENGAGE_HEALTH);
        if (s.creepers() != 1) return s.creepers() + " creepers within " + Math.round(LOOK);
        if (s.otherMonsters()) return "another monster is near";
        if (s.powered()) return "it is charged";
        if (s.nearProtect()) return "it is within " + PROTECT_MARGIN + " of a protect box";
        if (s.nearBuilds()) return "it is next to built blocks";
        if (s.lineClear() < LINE_NEED) return "no room to back off (" + (s.lineStop() == null ? "blocked" : s.lineStop()) + " after " + fmt(s.lineClear()) + ")";
        if (!s.lineOpen()) return "too narrow here (a tunnel)";
        return null;
    }

    /** Built things an explosion would hurt: any block entity (chest, furnace, sign...) or 6+ built blocks near it. */
    public static boolean nearBuilds(int blockEntities, int builtBlocks) {
        return blockEntities > 0 || builtBlocks >= 6;
    }

    /** True when (x, y, z) lies within margin blocks of one of the boxes in dim. */
    public static boolean nearBox(List<Box> boxes, String dim, double x, double y, double z, int margin) {
        if (boxes == null) return false;
        for (Box b : boxes) {
            if (!b.dim.equals(dim)) continue;
            double dx = Math.max(Math.max(b.x1 - x, 0), x - (b.x2 + 1));
            double dy = Math.max(Math.max(b.y1 - y, 0), y - (b.y2 + 1));
            double dz = Math.max(Math.max(b.z1 - z, 0), z - (b.z2 + 1));
            if (dx * dx + dy * dy + dz * dz <= (double) margin * margin) return true;
        }
        return false;
    }

    // ---- the retreat line ----

    /** One block as the retreat check sees it. FREE: no collision; FLOOR: something to stand on; BAD: fences, fire, cactus... */
    public enum Cell { FREE, FLOOR, LIQUID, BAD }

    /** The world (live: the client level; tests: a fixture). */
    public interface Grid {
        Cell at(int x, int y, int z);
    }

    /** clear: how far (blocks) the line is walkable; open: it isn't a 1-3 wide tunnel; stop: why it ended (null = long enough). */
    public record Line(double clear, boolean open, String stop) {}

    static final double STEP = 0.25;
    static final double HALF_WIDTH = 0.3;             // the player is 0.6 wide

    /**
     * The straight line from (px, pz) in direction (ux, uz) (normalised here): three lanes (the centre and both edges of
     * the player) walked in quarter blocks, each cell needing 2 free blocks over a floor, at most one step up or down, no
     * liquid, no hazard. open: at 4 of the first 5 whole blocks, one side has 3 free blocks at head height.
     */
    public static Line retreatLine(Grid g, double px, int footY, double pz, double ux, double uz, double need) {
        double len = Math.sqrt(ux * ux + uz * uz);
        if (len < 1e-6) return new Line(0, false, "no direction");
        ux /= len;
        uz /= len;
        double perpX = -uz, perpZ = ux;
        double clear = need;
        String stop = null;
        for (double off : new double[]{0, -HALF_WIDTH, HALF_WIDTH}) {
            Object[] r = lane(g, px + perpX * off, pz + perpZ * off, footY, ux, uz, need);
            double c = (Double) r[0];
            if (c < clear) {
                clear = c;
                stop = (String) r[1];
            }
        }
        // the tunnel test on the centre lane
        int opens = 0, ys = footY;
        int lastX = floor(px), lastZ = floor(pz);
        for (int k = 1; k <= 5; k++) {
            double cx = px + ux * k, cz = pz + uz * k;
            int bx = floor(cx), bz = floor(cz);
            if (bx != lastX || bz != lastZ) {
                int ny = stepInto(g, lastX, lastZ, bx, bz, ys);
                if (ny != Integer.MIN_VALUE) ys = ny;
                lastX = bx;
                lastZ = bz;
            }
            if (sideOpen(g, cx, cz, ys, perpX, perpZ, 1) || sideOpen(g, cx, cz, ys, perpX, perpZ, -1)) opens++;
        }
        return new Line(clear, opens >= 4, clear >= need ? null : stop);
    }

    private static boolean sideOpen(Grid g, double cx, double cz, int y, double perpX, double perpZ, int sign) {
        for (int d = 1; d <= 3; d++) {
            if (g.at(floor(cx + perpX * d * sign), y + 1, floor(cz + perpZ * d * sign)) != Cell.FREE) return false;
        }
        return true;
    }

    /** {clear (Double), why it stopped (String)} for one lane. */
    private static Object[] lane(Grid g, double sx, double sz, int footY, double ux, double uz, double need) {
        int y = footY, lastX = floor(sx), lastZ = floor(sz);
        for (double t = STEP; t <= need + 1e-9; t += STEP) {
            int bx = floor(sx + ux * t), bz = floor(sz + uz * t);
            if (bx == lastX && bz == lastZ) continue;
            int ny = stepInto(g, lastX, lastZ, bx, bz, y);
            if (ny == Integer.MIN_VALUE) return new Object[]{t - STEP, why(g, bx, bz, y)};
            y = ny;
            lastX = bx;
            lastZ = bz;
        }
        return new Object[]{need, null};
    }

    /** The foot height after stepping from (fx, fz) at foot height y into (x, z), or MIN_VALUE when it can't be walked. */
    static int stepInto(Grid g, int fx, int fz, int x, int z, int y) {
        Cell below2 = g.at(x, y - 2, z), below = g.at(x, y - 1, z), feet = g.at(x, y, z), head = g.at(x, y + 1, z), above = g.at(x, y + 2, z);
        if (feet == Cell.LIQUID || head == Cell.LIQUID || below == Cell.LIQUID) return Integer.MIN_VALUE;
        if (feet == Cell.FREE && head == Cell.FREE) {
            if (below == Cell.FLOOR) return y;
            if (below == Cell.FREE && below2 == Cell.FLOOR) return y - 1;     // one down
            return Integer.MIN_VALUE;                                       // a drop, a hazard below
        }
        // one up: a floor block at the feet, room above it, and room to jump where the bot stands
        if (feet == Cell.FLOOR && head == Cell.FREE && above == Cell.FREE && g.at(fx, y + 2, fz) == Cell.FREE) return y + 1;
        return Integer.MIN_VALUE;
    }

    private static String why(Grid g, int x, int z, int y) {
        Cell below2 = g.at(x, y - 2, z), below = g.at(x, y - 1, z), feet = g.at(x, y, z), head = g.at(x, y + 1, z);
        if (feet == Cell.LIQUID || head == Cell.LIQUID || below == Cell.LIQUID) return "liquid";
        if (feet == Cell.BAD || head == Cell.BAD || below == Cell.BAD) return "a hazard";
        if (feet == Cell.FREE && head == Cell.FREE && below == Cell.FREE) return below2 == Cell.FREE ? "a drop" : "a hazard";
        return "a wall";
    }

    private static int floor(double v) { return (int) Math.floor(v); }

    // ---- the hit-and-back cycle ----

    public enum Phase { WAIT, CHARGE, BACK, SHOOT }

    /** What the live side does this tick. HIT: attack now (then back off); FLEE: end the duel and run as before. */
    public enum Act { WAIT, CHARGE, HIT, BACK, SHOOT, FLEE }

    /**
     * One tick's view. dist: centre to centre; swellDir/swelling: the creeper's fuse (swelling 0..1); cooldown: the attack
     * strength 0..1; room: how far the retreat line is clear from here; stalled: backing off made no progress lately;
     * bowReady: bow mode, a bow and arrows, in sight, 8-15 away; pathIn: the ground towards it is walkable (no hole, no water).
     */
    public record Obs(double dist, int swellDir, float swelling, float cooldown, double room, boolean stalled, boolean bowReady, boolean pathIn) {}

    /** The duel's state machine. {@link #why} says why the last FLEE came. */
    public static final class Cycle {
        private Phase phase = Phase.WAIT;
        private long start = Long.MIN_VALUE, phaseAt;
        private int hits, charges, misses;
        private String why;

        public Phase phase() { return phase; }
        public int hits() { return hits; }
        public int charges() { return charges; }
        public String why() { return why; }

        /** The creeper lost health: the miss count starts over. */
        public void damaged() { misses = 0; }

        public Act step(long now, Obs o) {
            if (start == Long.MIN_VALUE) {
                start = now;
                phaseAt = now;
            }
            if (now - start >= GIVE_UP_TICKS) return flee("gave up after " + GIVE_UP_TICKS / 20 + " s");
            boolean swelling = o.swellDir() > 0;
            switch (phase) {
                case WAIT, SHOOT -> {
                    // a lit fuse within 7 keeps burning (SwellGoal): get beyond 7, or run when there's no room
                    if (swelling && o.dist() <= SAFE) {
                        if (o.room() >= 1) return to(Phase.BACK, now, Act.BACK);
                        return flee("it is swelling " + fmt(o.dist()) + " away and I can't back off");
                    }
                    if (o.cooldown() >= READY && o.swelling() < SWELL_OK && o.room() >= LINE_NEED && o.pathIn() && o.dist() <= BOW_MIN) {
                        // judged only here, so the damage of the last hit has time to show (a server round trip)
                        if (misses >= MAX_MISSES) return flee("gave up: " + MAX_MISSES + " charges without damage");
                        charges++;
                        misses++;
                        return to(Phase.CHARGE, now, Act.CHARGE);
                    }
                    if (o.bowReady() && o.swelling() < SWELL_OK) return to(Phase.SHOOT, now, Act.SHOOT);
                    if (o.dist() < WAIT_MIN && o.room() >= 1) return to(Phase.BACK, now, Act.BACK);
                    return to(Phase.WAIT, now, Act.WAIT);
                }
                case CHARGE -> {
                    if (o.dist() <= HIT && o.cooldown() >= READY) {
                        hits++;
                        return to(Phase.BACK, now, Act.HIT);
                    }
                    if (o.swelling() >= SWELL_ABORT) return to(Phase.BACK, now, Act.BACK);
                    if (!o.pathIn()) return to(Phase.WAIT, now, Act.WAIT);
                    if (now - phaseAt >= CHARGE_TICKS) return to(Phase.WAIT, now, Act.WAIT);
                    return Act.CHARGE;
                }
                case BACK -> {
                    if (o.dist() > SAFE || (!swelling && o.dist() >= VALVE)) return to(Phase.WAIT, now, Act.WAIT);
                    if (o.room() < 0.5 || o.stalled()) {
                        if (swelling) return flee("cornered while it swells " + fmt(o.dist()) + " away");
                        return to(Phase.WAIT, now, Act.WAIT);
                    }
                    return Act.BACK;
                }
            }
            return Act.WAIT;
        }

        private Act to(Phase p, long now, Act a) {
            if (phase != p) {
                phase = p;
                phaseAt = now;
            }
            return a;
        }

        private Act flee(String w) {
            why = w;
            return Act.FLEE;
        }
    }

    // ---- the bow ----

    /** {height of the arrow when it has flown horiz blocks, ticks it took}, or null when it never gets that far (40 ticks). */
    static double[] arrowAt(double speed, double elevRad, double horiz) {
        double x = 0, y = 0, vx = speed * Math.cos(elevRad), vy = speed * Math.sin(elevRad);
        for (int t = 1; t <= 40; t++) {
            double nx = x + vx, ny = y + vy;
            if (nx >= horiz) {
                double f = vx <= 0 ? 0 : (horiz - x) / vx;
                return new double[]{y + vy * f, t - 1 + f};
            }
            x = nx;
            y = ny;
            vx *= 0.99;
            vy = vy * 0.99 - 0.05;
        }
        return null;
    }

    /**
     * The pitch (Minecraft's xRot, degrees, negative = up) that puts a full-draw arrow dy above the shooting point
     * horiz blocks away on the low arc, or NaN out of range.
     */
    public static double bowPitch(double horiz, double dy, double speed) {
        if (horiz <= 0) return dy >= 0 ? -90 : 90;
        double lo = Math.toRadians(-45), hi = Math.toRadians(40);
        double[] top = arrowAt(speed, hi, horiz);
        if (top == null || top[0] < dy) return Double.NaN;
        double[] bottom = arrowAt(speed, lo, horiz);
        if (bottom != null && bottom[0] > dy) return -Math.toDegrees(lo);
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            double[] h = arrowAt(speed, mid, horiz);
            if (h != null && h[0] >= dy) hi = mid;
            else lo = mid;
        }
        return -Math.toDegrees(hi);
    }

    /** Ticks a full-draw arrow takes to fly horiz blocks at that pitch (for leading a walking target); 0 when unknown. */
    public static double flightTicks(double horiz, double pitchDeg, double speed) {
        double[] h = arrowAt(speed, Math.toRadians(-pitchDeg), horiz);
        return h == null ? 0 : h[1];
    }

    static String fmt(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }
}
