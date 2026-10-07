package io.github.mojolowjo.entropybot.threat;

import java.util.Arrays;

/**
 * B2 threat test (docs/BRAIN_PLAN.md 5.2): a small block grid around the bot and one reverse breadth-first search from
 * the bot's cell with a mob's moves, giving every cell its walking distance to the bot (in moves; -1 = no path).
 * Pure Java (no Minecraft types): the game thread fills {@link #codes} from a snapshot, a worker runs {@link #search}.
 *
 * <p>Mob moves (vanilla walking): 4 directions, step up 1 (not onto a fence or wall), drop up to 3, a 1x2 body (1x1 for
 * spiders), no doors opened, lava never, water swum (up and down too). Spiders also climb any wall face. Diagonals are
 * left out: a path is at most 1.41x a straight line too long, under the 1.6x budget of {@link ThreatRules}.
 */
public final class ReachGrid {
    public static final byte AIR = 0, SOLID = 1, TALL = 2, LAVA = 3, WATER = 4, LOW = 5;

    public final int sx, sy, sz;
    /** World coordinates of cell (0,0,0). */
    public final int ox, oy, oz;
    /** One code per cell, index {@link #idx}. */
    public final byte[] codes;
    /** The light where a mob could stand (0..15; 0 elsewhere), for the lit-spot retreat. */
    public final byte[] light;

    public ReachGrid(int sx, int sy, int sz, int ox, int oy, int oz) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        codes = new byte[sx * sy * sz];
        light = new byte[sx * sy * sz];
    }

    /** A grid from text layers (tests): layers[y][z] is a row of x chars: '#' solid, '.' air, 'F' fence, 'L' lava, 'W' water, '_' low. */
    public static ReachGrid parse(String[][] layers) {
        int sy = layers.length, sz = layers[0].length, sx = layers[0][0].length();
        ReachGrid g = new ReachGrid(sx, sy, sz, 0, 0, 0);
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
            char c = layers[y][z].charAt(x);
            g.codes[g.idx(x, y, z)] = switch (c) {
                case '#' -> SOLID;
                case 'F' -> TALL;
                case 'L' -> LAVA;
                case 'W' -> WATER;
                case '_' -> LOW;
                default -> AIR;
            };
        }
        return g;
    }

    public int idx(int x, int y, int z) { return (y * sz + z) * sx + x; }

    public boolean in(int x, int y, int z) { return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz; }

    /** Out of the grid: above = air, below = solid ground (the edges stay walkable rather than cut off). */
    byte code(int x, int y, int z) {
        if (!in(x, y, z)) return y < 0 ? SOLID : AIR;
        return codes[idx(x, y, z)];
    }

    static boolean pass(byte c) { return c == AIR || c == WATER || c == LOW; }

    static boolean floorish(byte c) { return c == SOLID || c == TALL || c == LOW; }

    /** A 1x2 body stands here: feet and head free, something under the feet (or in water / on a low block). */
    boolean walkable(int x, int y, int z) {
        byte f = code(x, y, z);
        if (!pass(f) || !pass(code(x, y + 1, z))) return false;
        return f == WATER || f == LOW || floorish(code(x, y - 1, z));
    }

    /** A spider (1 high) holds on here: floor below, a wall beside it, or water. */
    boolean clingable(int x, int y, int z) {
        byte f = code(x, y, z);
        if (!pass(f)) return false;
        if (f == WATER || f == LOW || floorish(code(x, y - 1, z))) return true;
        return floorish(code(x + 1, y, z)) || floorish(code(x - 1, y, z)) || floorish(code(x, y, z + 1)) || floorish(code(x, y, z - 1))
                || code(x, y + 1, z) == SOLID;
    }

    private static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};

    /** A mob in cell b can move to the neighbouring cell a (|dx|+|dz| = 1, any dy) by walking. */
    boolean walkEdge(int bx, int by, int bz, int ax, int ay, int az) {
        if (!walkable(bx, by, bz) || !walkable(ax, ay, az)) return false;
        int dy = ay - by;
        if (dy == 0) return true;
        if (dy == 1) {
            if (code(ax, by, az) == TALL) return false;             // a fence or wall is 1.5 high: no jump
            return pass(code(bx, by + 2, bz));                       // room to jump
        }
        if (dy < 0 && dy >= -3) {
            for (int y = ay + 2; y <= by + 1; y++) if (!pass(code(ax, y, az))) return false;   // the column it drops down
            return true;
        }
        return false;
    }

    /** A spider: walk-like moves with a 1-high body, plus climbing straight up or down a wall. */
    boolean spiderEdge(int bx, int by, int bz, int ax, int ay, int az) {
        if (!clingable(bx, by, bz) || !clingable(ax, ay, az)) return false;
        int dy = ay - by;
        boolean vertical = bx == ax && bz == az;
        if (vertical) return dy == 1 || dy == -1;
        if (dy == 0) return true;
        if (dy == 1) return pass(code(bx, by + 1, bz));
        if (dy < 0 && dy >= -3) {
            for (int y = ay + 1; y <= by; y++) if (!pass(code(ax, y, az))) return false;
            return true;
        }
        return false;
    }

    /**
     * The reverse search: for every cell, the number of moves a mob there needs to reach (bx, by, bz). The bot's own cell
     * is moved up or down one when it isn't standable (a slab, a ladder). Cells with no path stay -1.
     */
    public int[] search(int bx, int by, int bz, boolean spider) {
        int[] dist = new int[codes.length];
        Arrays.fill(dist, -1);
        int sy0 = by;
        if (!stand(bx, by, bz, spider)) {
            if (stand(bx, by + 1, bz, spider)) sy0 = by + 1;
            else if (stand(bx, by - 1, bz, spider)) sy0 = by - 1;
        }
        if (!in(bx, sy0, bz)) return dist;
        int[] queue = new int[codes.length];
        int head = 0, tail = 0;
        int s = idx(bx, sy0, bz);
        dist[s] = 0;
        queue[tail++] = s;
        int layer = sx * sz;
        while (head < tail) {
            int a = queue[head++];
            int ay = a / layer, az = (a % layer) / sx, ax = a % sx;
            int nd = dist[a] + 1;
            for (int k = 0; k < 4; k++) {
                int bxx = ax + DX[k], bzz = az + DZ[k];
                for (int dy = -1; dy <= 3; dy++) {
                    int byy = ay + dy;
                    if (!in(bxx, byy, bzz)) continue;
                    int b = idx(bxx, byy, bzz);
                    if (dist[b] >= 0) continue;
                    boolean ok = spider ? spiderEdge(bxx, byy, bzz, ax, ay, az) : walkEdge(bxx, byy, bzz, ax, ay, az);
                    if (ok) {
                        dist[b] = nd;
                        queue[tail++] = b;
                    }
                }
            }
            // straight up/down: spiders climbing, any mob swimming
            for (int dy = -1; dy <= 1; dy += 2) {
                int byy = ay + dy;
                if (!in(ax, byy, az)) continue;
                int b = idx(ax, byy, az);
                if (dist[b] >= 0) continue;
                boolean ok = spider ? spiderEdge(ax, byy, az, ax, ay, az)
                        : code(ax, byy, az) == WATER && code(ax, ay, az) == WATER;
                if (ok) {
                    dist[b] = nd;
                    queue[tail++] = b;
                }
            }
        }
        return dist;
    }

    private boolean stand(int x, int y, int z, boolean spider) {
        return in(x, y, z) && (spider ? clingable(x, y, z) : walkable(x, y, z));
    }

    /** The distance of a mob standing in world block (x, y, z): its cell, else one up or down; -1 none, -2 outside the grid. */
    public int distAt(int[] dist, int wx, int wy, int wz) {
        int x = wx - ox, y = wy - oy, z = wz - oz;
        if (!in(x, y, z)) return -2;
        int best = -1;
        for (int dy : new int[]{0, -1, 1}) {
            if (!in(x, y + dy, z)) continue;
            int d = dist[idx(x, y + dy, z)];
            if (d >= 0 && (best < 0 || d < best)) best = d;
        }
        return best;
    }

    /** The nearest reachable standing cell with light >= minLight within maxDist moves: world {x, y, z, dist}, or null. */
    public int[] nearestLit(int[] dist, int minLight, int maxDist) {
        int best = -1, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < dist.length; i++) {
            int d = dist[i];
            if (d < 0 || d > maxDist || d >= bestD || light[i] < minLight) continue;
            best = i;
            bestD = d;
        }
        if (best < 0) return null;
        int layer = sx * sz;
        return new int[]{best % sx + ox, best / layer + oy, (best % layer) / sx + oz, bestD};
    }
}
