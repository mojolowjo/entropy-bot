package io.github.mojolowjo.entropybot.guard;

import com.google.gson.JsonObject;

/**
 * An axis-aligned box in one dimension, inclusive on all sides. Y defaults to "all of it" when a
 * policy entry gives none, so an area set with four numbers in game covers every height.
 */
public final class Box {
    /** Minecraft's absolute build limits; wider than any world's. */
    public static final int ALL_Y_MIN = -2048, ALL_Y_MAX = 2047;

    public final String name;
    public final String dim;
    public final int x1, y1, z1, x2, y2, z2;
    /**
     * 0.21.2: a ROUND area (the near-me zone): the columns whose horizontal distance to (cx, cz) is at most r, between y1
     * and y2. x1..x2 / z1..z2 are then its bounding square. The shape lives here alone: {@link #contains},
     * {@link #inside}, {@link #gap}, {@link #columnIn}, {@link #clip} and the JSON form ("round": {cx, cz, r}) know it;
     * every other user of a Box (areaAt, areaCovers, leases, the walking fence, the miner's clipping) goes through these.
     */
    public final boolean round;
    public final int cx, cz, r;
    /** V1a (0.22.0): the area's type (JSON "type", absent = neutral). A lease box or a plain box is neutral. */
    public final AreaType type;

    public Box(String name, String dim, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.name = name;
        this.dim = dim;
        this.x1 = Math.min(x1, x2); this.x2 = Math.max(x1, x2);
        this.y1 = Math.min(y1, y2); this.y2 = Math.max(y1, y2);
        this.z1 = Math.min(z1, z2); this.z2 = Math.max(z1, z2);
        this.round = false;
        this.cx = 0; this.cz = 0; this.r = 0;
        this.type = AreaType.NEUTRAL;
    }

    private Box(Box o, AreaType t) {
        this.name = o.name; this.dim = o.dim;
        this.x1 = o.x1; this.y1 = o.y1; this.z1 = o.z1; this.x2 = o.x2; this.y2 = o.y2; this.z2 = o.z2;
        this.round = o.round; this.cx = o.cx; this.cz = o.cz; this.r = o.r;
        this.type = t == null ? AreaType.NEUTRAL : t;
    }

    /** This box with another type. */
    public Box withType(AreaType t) { return new Box(this, t); }

    private Box(String name, String dim, int cx, int cz, int r, int y1, int y2) {
        int rr = Math.max(0, r);
        this.name = name;
        this.dim = dim;
        this.x1 = cx - rr; this.x2 = cx + rr;
        this.z1 = cz - rr; this.z2 = cz + rr;
        this.y1 = Math.min(y1, y2); this.y2 = Math.max(y1, y2);
        this.round = true;
        this.cx = cx; this.cz = cz; this.r = rr;
        this.type = AreaType.NEUTRAL;
    }

    /** A round area: horizontal distance to (cx, cz) at most r (block columns), y1..y2. */
    public static Box round(String name, String dim, int cx, int cz, int r, int y1, int y2) {
        return new Box(name, dim, cx, cz, r, y1, y2);
    }

    /** The column (x, z) lies in this box's footprint (the circle for a round one). */
    public boolean columnIn(int x, int z) {
        if (x < x1 || x > x2 || z < z1 || z > z2) return false;
        if (!round) return true;
        long dx = x - cx, dz = z - cz;
        return dx * dx + dz * dz <= (long) r * r;
    }

    /** Every column of the rectangle x1..x2, z1..z2 lies in the footprint (heights aside): for a circle, its 4 corners. */
    public boolean columnsInside(int ax1, int az1, int ax2, int az2) {
        return columnIn(Math.min(ax1, ax2), Math.min(az1, az2)) && columnIn(Math.min(ax1, ax2), Math.max(az1, az2))
                && columnIn(Math.max(ax1, ax2), Math.min(az1, az2)) && columnIn(Math.max(ax1, ax2), Math.max(az1, az2));
    }

    public boolean contains(String d, int x, int y, int z) {
        return dim.equals(d) && y >= y1 && y <= y2 && columnIn(x, z);
    }

    /**
     * True when this box lies entirely inside {@code o}. For a round {@code o}: the y range fits and all four corner
     * columns lie in the circle (a circle is convex, so the corners decide).
     */
    public boolean inside(Box o) {
        if (!dim.equals(o.dim) || y1 < o.y1 || y2 > o.y2) return false;
        if (!o.round) return x1 >= o.x1 && x2 <= o.x2 && z1 >= o.z1 && z2 <= o.z2;
        return o.columnIn(x1, z1) && o.columnIn(x1, z2) && o.columnIn(x2, z1) && o.columnIn(x2, z2);
    }

    /** Blocks from (x, y, z) to this box (0 = inside); for a round box the horizontal part is ceil(distance - r). */
    public int gap(int x, int y, int z) {
        int h;
        if (round) {
            if (columnIn(x, z)) h = 0;
            else h = (int) Math.max(1, Math.ceil(Math.sqrt((double) (x - cx) * (x - cx) + (double) (z - cz) * (z - cz)) - r));
        } else {
            h = Math.max(Math.max(x1 - x, 0), Math.max(x - x2, Math.max(z1 - z, z - z2)));
        }
        return allY() ? h : Math.max(h, Math.max(y1 - y, y - y2));
    }

    /**
     * The box {x1, y1, z1, x2, y2, z2} clipped to this area: one box for a rectangle; for a round area, strips (consecutive
     * x with the same z range merged) that each lie wholly inside the circle. Empty when nothing is left.
     */
    public java.util.List<int[]> clip(int[] b) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        int cy1 = allY() ? b[1] : Math.max(b[1], y1), cy2 = allY() ? b[4] : Math.min(b[4], y2);
        int ax1 = Math.max(b[0], x1), ax2 = Math.min(b[3], x2), az1 = Math.max(b[2], z1), az2 = Math.min(b[5], z2);
        if (ax1 > ax2 || az1 > az2 || cy1 > cy2) return out;
        if (!round) {
            out.add(new int[]{ax1, cy1, az1, ax2, cy2, az2});
            return out;
        }
        int[] cur = null;
        for (int x = ax1; x <= ax2; x++) {
            long dx = x - cx, rest = (long) r * r - dx * dx;
            int za = 1, zb = 0;
            if (rest >= 0) {
                int half = (int) Math.floor(Math.sqrt((double) rest));
                while ((long) (half + 1) * (half + 1) <= rest) half++;
                while (half > 0 && (long) half * half > rest) half--;
                za = Math.max(az1, cz - half);
                zb = Math.min(az2, cz + half);
            }
            if (za > zb) {
                if (cur != null) out.add(cur);
                cur = null;
                continue;
            }
            if (cur != null && cur[2] == za && cur[5] == zb && cur[3] == x - 1) cur[3] = x;
            else {
                if (cur != null) out.add(cur);
                cur = new int[]{x, cy1, za, x, cy2, zb};
            }
        }
        if (cur != null) out.add(cur);
        return out;
    }

    public boolean overlaps(Box o) {
        return dim.equals(o.dim) && x1 <= o.x2 && x2 >= o.x1 && y1 <= o.y2 && y2 >= o.y1 && z1 <= o.z2 && z2 >= o.z1;
    }

    public long volume() {
        return (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
    }

    public boolean allY() {
        return y1 <= ALL_Y_MIN && y2 >= ALL_Y_MAX;
    }

    /** Parses {name?, dim?, x1, z1, x2, z2, y1?, y2?}; a missing y means all heights. */
    public static Box fromJson(JsonObject o, String defaultDim) {
        for (String k : new String[]{"x1", "z1", "x2", "z2"}) {
            if (!o.has(k)) throw new IllegalArgumentException("box needs " + k);
        }
        String dim = o.has("dim") ? o.get("dim").getAsString() : defaultDim;
        int y1 = o.has("y1") ? o.get("y1").getAsInt() : ALL_Y_MIN;
        int y2 = o.has("y2") ? o.get("y2").getAsInt() : ALL_Y_MAX;
        String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : null;
        AreaType type = AreaType.orNeutral(o.has("type") && o.get("type").isJsonPrimitive() ? o.get("type").getAsString() : null);
        return fromJson0(o, dim, y1, y2, name).withType(type);
    }

    private static Box fromJson0(JsonObject o, String dim, int y1, int y2, String name) {
        if (o.has("round") && o.get("round").isJsonObject()) {
            JsonObject rd = o.getAsJsonObject("round");
            for (String k : new String[]{"cx", "cz", "r"}) if (!rd.has(k)) throw new IllegalArgumentException("round box needs " + k);
            return round(name, dim, rd.get("cx").getAsInt(), rd.get("cz").getAsInt(), rd.get("r").getAsInt(), y1, y2);
        }
        return new Box(name, dim, o.get("x1").getAsInt(), y1, o.get("z1").getAsInt(), o.get("x2").getAsInt(), y2, o.get("z2").getAsInt());
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        if (name != null) o.addProperty("name", name);
        o.addProperty("dim", dim);
        o.addProperty("x1", x1); o.addProperty("z1", z1); o.addProperty("x2", x2); o.addProperty("z2", z2);
        if (!allY()) { o.addProperty("y1", y1); o.addProperty("y2", y2); }
        if (type != AreaType.NEUTRAL) o.addProperty("type", type.word());
        if (round) {
            JsonObject rd = new JsonObject();
            rd.addProperty("cx", cx);
            rd.addProperty("cz", cz);
            rd.addProperty("r", r);
            o.add("round", rd);
        }
        return o;
    }

    public String describe() {
        String base = round ? (name == null ? "box" : name) + " a circle of " + r + " around " + cx + " " + cz
                : (name == null ? "box" : name) + " " + x1 + " " + z1 + " to " + x2 + " " + z2;
        return allY() ? base : base + " (y " + y1 + ".." + y2 + ")";
    }

    @Override
    public String toString() {
        return describe() + " in " + dim;
    }
}
