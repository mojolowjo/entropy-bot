package io.github.mojolowjo.entropybot.nav.steer;

import java.util.HashMap;
import java.util.Map;

/**
 * A rough stand-in for the game's player physics, for the steerer's tests only: an axis-by-axis box move against unit
 * block cells, the land numbers (walk 0.1, sprint 1.3x, jump 0.42, gravity 0.08, drag 0.98 and 0.546 / 0.91) and the
 * water numbers (input 0.02, swim up / sink 0.04, drag 0.8, gravity 0.02, current push 0.014, a jump out of the water
 * at a wall) close to vanilla's, air 300 that runs down with the eyes in water, lava and falls over 3 blocks hurt. It is
 * not the game: the live steerer uses the ghost player (S2), which runs the game's own code.
 */
final class FakeWaterSim implements Sim {
    enum Kind { AIR, SOLID, WATER, LAVA }

    /** The world: cells by packed key, AIR when missing; water cells may carry a current (+x, +z per tick). */
    static final class World {
        final Map<Long, Kind> cells = new HashMap<>();
        final Map<Long, double[]> flow = new HashMap<>();

        static long key(int x, int y, int z) { return ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF); }

        Kind at(int x, int y, int z) { return cells.getOrDefault(key(x, y, z), Kind.AIR); }

        World fill(int x0, int y0, int z0, int x1, int y1, int z1, Kind k) {
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) cells.put(key(x, y, z), k);
            return this;
        }

        World current(int x0, int y0, int z0, int x1, int y1, int z1, double fx, double fz) {
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) flow.put(key(x, y, z), new double[]{fx, fz});
            return this;
        }
    }

    static final double HALF = 0.3, HEIGHT = 1.8, EYES = 1.62;

    final World w;
    private final FakeWaterSim real;       // null for the real body; the ghost copies it on reset
    double x, y, z, vx, vy, vz, fall;
    boolean onGround, hitWall, hurt;
    int air = 300;

    FakeWaterSim(World w, double x, double y, double z) {
        this.w = w;
        this.real = null;
        this.x = x; this.y = y; this.z = z;
    }

    /** A ghost of {@code real}: reset copies it. */
    FakeWaterSim(FakeWaterSim real) {
        this.w = real.w;
        this.real = real;
        reset();
    }

    @Override public void reset() {
        if (real == null) return;
        x = real.x; y = real.y; z = real.z; vx = real.vx; vy = real.vy; vz = real.vz; fall = real.fall;
        onGround = real.onGround; hitWall = real.hitWall; air = real.air; hurt = false;
    }

    @Override public double x() { return x; }
    @Override public double y() { return y; }
    @Override public double z() { return z; }
    @Override public boolean hurt() { return hurt; }
    @Override public int air() { return air; }

    boolean touches(Kind k, double y0, double y1) {
        for (int bx = (int) Math.floor(x - HALF); bx <= (int) Math.floor(x + HALF - 1e-9); bx++)
            for (int by = (int) Math.floor(y0); by <= (int) Math.floor(y1 - 1e-9); by++)
                for (int bz = (int) Math.floor(z - HALF); bz <= (int) Math.floor(z + HALF - 1e-9); bz++)
                    if (w.at(bx, by, bz) == k) return true;
        return false;
    }

    private boolean solidAt(double px, double py, double pz) {
        for (int bx = (int) Math.floor(px - HALF); bx <= (int) Math.floor(px + HALF - 1e-9); bx++)
            for (int by = (int) Math.floor(py); by <= (int) Math.floor(py + HEIGHT - 1e-9); by++)
                for (int bz = (int) Math.floor(pz - HALF); bz <= (int) Math.floor(pz + HALF - 1e-9); bz++)
                    if (w.at(bx, by, bz) == Kind.SOLID) return true;
        return false;
    }

    @Override public void step(Choice c, float yaw) {
        boolean water = touches(Kind.WATER, y, y + 0.4);
        double r = Math.toRadians(yaw), f = c.forward(), l = c.left();
        double len = Math.sqrt(f * f + l * l);
        double ix = 0, iz = 0;
        if (len > 0) {
            f /= len; l /= len;
            ix = l * Math.cos(r) - f * Math.sin(r);
            iz = f * Math.cos(r) + l * Math.sin(r);
        }
        if (water) {
            double s = c.sneak() ? 0.006 : 0.02;
            vx += ix * s; vz += iz * s;
            if (c.jump()) vy += 0.04;
            if (c.sneak()) vy -= 0.04;
            double[] fl = w.flow.get(World.key((int) Math.floor(x), (int) Math.floor(y + 0.2), (int) Math.floor(z)));
            if (fl != null) { vx += fl[0] * 0.014; vz += fl[1] * 0.014; }
        } else {
            double s = onGround ? 0.1 * (c.sprint() ? 1.3 : 1.0) * (c.sneak() ? 0.3 : 1.0) : 0.02;
            vx += ix * s; vz += iz * s;
            if (c.jump() && onGround) {
                vy = 0.42;
                if (c.sprint()) { vx += -Math.sin(r) * 0.2; vz += Math.cos(r) * 0.2; }
            }
        }
        move();
        if (water) {
            vx *= 0.8; vz *= 0.8; vy = vy * 0.8 - 0.02;
            if (hitWall && !solidAt(x + vx, y + 1.2, z + vz)) vy = 0.5;      // the jump out of the water at a bank
            fall = 0;
        } else {
            vy = (vy - 0.08) * 0.98;
            double d = onGround ? 0.546 : 0.91;
            vx *= d; vz *= d;
        }
        if (touches(Kind.LAVA, y, y + HEIGHT)) hurt = true;
        boolean eyes = w.at((int) Math.floor(x), (int) Math.floor(y + EYES), (int) Math.floor(z)) == Kind.WATER;
        if (eyes) { if (--air <= 0) hurt = true; } else air = Math.min(300, air + 4);
    }

    private void move() {
        double oy = y;
        if (!solidAt(x, y + vy, z)) y += vy; else { if (vy < 0) y = Math.floor(y + vy) + 1; vy = 0; }
        boolean landed = y == oy && vy == 0 && solidAt(x, y - 0.01, z);
        hitWall = false;
        if (!solidAt(x + vx, y, z)) x += vx; else { vx = 0; hitWall = true; }
        if (!solidAt(x, y, z + vz)) z += vz; else { vz = 0; hitWall = true; }
        if (y < oy) fall += oy - y;
        onGround = landed || solidAt(x, y - 0.01, z);
        if (onGround) {
            if (fall > 3) hurt = true;
            fall = 0;
        }
    }
}
