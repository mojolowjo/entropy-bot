package io.github.mojolowjo.entropybot.sense;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 0.39.z50 (owner 2026-10-24: "use the data from the confirmed caves to come up with a pattern caves generally follow";
 * "require a pocket, not a cell"): labelled cave patterns and the cave rule derived from them (S69-S71).
 * <ul>
 * <li>A {@link Patch} is one labelled spot: the 9 x 9 WORLD_SURFACE top heights round it (relative to the centre), the
 *     9 x 9 OCEAN_FLOOR heights when known (the seed read them from the save; the client is not sent OCEAN_FLOOR, so a
 *     live label has none), the hole's depth, its dark pocket, the darkest sky light the pocket fill reached, the label
 *     (cave / cliff / hill / water / unknown: the owner's verdict words) and who labelled it (owner or bot).</li>
 * <li>A new spot is scored by a plain nearest-neighbour vote ({@code cave.patternK}) over the labelled patches: the
 *     patch distance is the smallest of its 8 turns and mirrors (a cave faces any way), each number scaled by its spread
 *     over the patterns. The cave side wins when its weight beats cliff + hill + water; a bot label weighs
 *     {@code cave.botLabelWeight} (0.39.z54 A1, owner's ruling 2026-10-24: 0 while the rule is calibrated; a patch that
 *     weighs 0 takes no place among the k nearest and no part in the rule; the bot writes no patches any more).</li>
 * <li>The rule's thresholds (slope, cliff, depth, pocket, darkest sky) are the caves' own extremes (the loosest a
 *     labelled cave showed; z52: the pocket never below cave.darkPocketCells; z54 A2: the cliff never below
 *     cave.cliffFloor, the depth never below cave.depthFloor); a mouth needs the pocket OR the darkest sky (z52: two dark
 *     proofs; z54 A4: the darkest sky alone confirms only with a vote margin, else the mouth stays a candidate); before any
 *     cave pattern exists the Tuning defaults hold (cave.slopeMin, cave.cliffDrop, cave.depthMin, cave.darkPocketCells,
 *     cave.maxSky).</li>
 * </ul>
 * Files: the world's {@code cave-patterns.json} and the global one (z54 A5: no bundled seed; the game folder's global
 * file is the only source). Pure (JUnit: Z50CavePatternTest, Z52CaveRuleTest, Z54CaveRuleTest). Loader notes: none
 * (ChunkPass and SightingsRuntime read the heights and cells).
 */
public final class CavePatterns {
    public static final int SIDE = 9;            // structural: the 9 x 9 patch of the owner's brief
    public static final int R = SIDE / 2;        // structural

    public record Patch(int x, int y, int z, String label, String by, int depth, int pocket, int darkSky, int[] ws, int[] of) {
        public boolean owner() { return !"bot".equals(by); }
        public boolean cave() { return "cave".equals(label); }
        /** cliff / hill / water: the not-a-cave side; unknown votes for neither. */
        public boolean notCave() { return "cliff".equals(label) || "hill".equals(label) || "water".equals(label); }
        public double weight() { return owner() ? 1 : Tuning.get("cave.botLabelWeight"); }
        /** 0.39.z54 A1: a labelled patch that votes and shapes the rule (cave or not-a-cave side, weight above 0). */
        public boolean votes() { return (cave() || notCave()) && weight() > 0; }
    }

    /**
     * The rule the patterns give: a shape is a candidate with slope >= slope and cliff >= cliff; a mouth needs the rest.
     * {@code floors}: 0.39.z54 A2, the floors that hold a derived threshold up ("cliff 5 (derived 0)"; "" = none).
     */
    public record Rule(int patterns, int slope, int cliff, int depth, int pocket, int darkSky, boolean derived, String floors) {
        public String line() {
            return (derived ? "cave rule from " + patterns + " patterns" : "cave rule (Tuning defaults, no cave pattern yet)")
                    + ": slope >= " + slope + ", cliff >= " + cliff + ", depth >= " + depth + ", pocket >= " + pocket + " or darkest sky <= " + darkSky
                    + (floors.isEmpty() ? "" : " (floors hold: " + floors + ")");
        }
    }

    /** The Tuning defaults (no cave pattern yet); 0.39.z54 review 5: the cliff and depth floors hold here too, said in the line. */
    public static Rule defaults() {
        int cliff = Tuning.i("cave.cliffDrop"), depth = Tuning.i("cave.depthMin"), cf = Tuning.i("cave.cliffFloor"), df = Tuning.i("cave.depthFloor");
        List<String> held = new ArrayList<>();
        if (cliff < cf) held.add("cliff " + cf + " (cave.cliffDrop " + cliff + ")");
        if (depth < df) held.add("depth " + df + " (cave.depthMin " + depth + ")");
        return new Rule(0, Tuning.i("cave.slopeMin"), Math.max(cliff, cf), Math.max(depth, df), Tuning.i("cave.darkPocketCells"), Tuning.i("cave.maxSky"), false, String.join(", ", held));
    }

    private final List<Patch> list = new ArrayList<>();
    private boolean dirty;
    /** 0.39.z54 review 3: bumped on every edit of the patterns (add, read). */
    private long edits;

    public synchronized void add(Patch p) { list.add(p); dirty = true; edits++; }

    /** 0.39.z54 review 3: the Tuning entries the walk, the vote, the rule, the verdict and the hold read. */
    static final List<String> RULE_TUNING = List.of("cave.botLabelWeight", "cave.patternK", "cave.cliffFloor", "cave.depthFloor", "cave.darkPocketCells",
            "cave.maxSky", "cave.slopeMin", "cave.cliffDrop", "cave.depthMin", "cave.skyOnlyVoteMargin", "cave.cliffReach", "cave.slopeLen",
            "cave.pocketReach", "cave.floorMaxDepth");

    /**
     * 0.39.z54 review 3: a stamp that changes with every edit of these patterns (and with this object: another world's
     * set) and with any Tuning entry of {@link #RULE_TUNING}; a held candidate is looked at again when it changes.
     */
    public synchronized long stamp() {
        long h = 31L * System.identityHashCode(this) + edits;
        for (String t : RULE_TUNING) h = 31 * h + Double.hashCode(Tuning.get(t));
        return h;
    }

    /** 0.39.z54 review 3: a candidate held at stamp {@code heldAt} (null = not held) stays held while the stamp is the same. */
    public static boolean stillHeld(Long heldAt, long now) { return heldAt != null && heldAt == now; }
    public synchronized List<Patch> all() { return List.copyOf(list); }
    public synchronized int size() { return list.size(); }
    public synchronized boolean dirty() { return dirty; }
    public synchronized void saved() { dirty = false; }

    // ---- the shape numbers of a patch ----

    static int at(int[] ws, int dx, int dz) {
        if (Math.abs(dx) > R || Math.abs(dz) > R) return 0;
        return ws[(dz + R) * SIDE + dx + R];
    }

    /** {cliff, slope}: the steepest rise within cave.cliffReach columns of the centre, and the rise on its far side within cave.slopeLen. */
    public static int[] shape(int[] ws) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int best = Integer.MIN_VALUE, slope = 0;
        for (int[] d : dirs) {
            int c = 0;
            for (int k = 1; k <= CaveMouths.cliffReach(); k++) c = Math.max(c, at(ws, d[0] * k, d[1] * k));
            int s = 0;
            for (int k = 1; k <= CaveMouths.slopeLen(); k++) s = Math.max(s, at(ws, -d[0] * k, -d[1] * k));
            if (c > best || (c == best && s > slope)) { best = c; slope = s; }
        }
        return new int[]{Math.max(0, best), slope};
    }

    /** The rule from the cave patterns (the loosest numbers a labelled cave showed); the Tuning defaults before any. */
    public synchronized Rule rule() {
        Rule d = defaults();
        int n = 0, slope = Integer.MAX_VALUE, cliff = Integer.MAX_VALUE, depth = Integer.MAX_VALUE, pocket = Integer.MAX_VALUE, sky = Integer.MIN_VALUE;
        for (Patch p : list) {
            if (!p.votes()) continue;          // z54 A1: a bot patch weighs 0 while calibrating: no part in the rule
            n++;
            if (!p.cave()) continue;
            int[] s = shape(p.ws());
            cliff = Math.min(cliff, s[0]);
            slope = Math.min(slope, s[1]);
            depth = Math.min(depth, p.depth());
            pocket = Math.min(pocket, p.pocket());
            sky = Math.max(sky, p.darkSky());
        }
        if (cliff == Integer.MAX_VALUE) return d;
        // 0.39.z52 (owner's ruling "a pocket, not a cell"): the derived pocket never falls below cave.darkPocketCells (seed caves
        // #15 and #17 show pocket 0, which would make the pocket rule pass anything)
        // 0.39.z54 A2 (labelling walk on z53: "cliff >= 7" fell to "cliff >= 1" after one label, the owner's flight to
        // "cliff >= 0, depth >= 5"; the candidates became +1/+1 bumps): the cliff and the depth get floors the same way
        List<String> held = new ArrayList<>();
        int cf = Tuning.i("cave.cliffFloor"), df = Tuning.i("cave.depthFloor");
        if (cliff < cf) held.add("cliff " + cf + " (derived " + cliff + ")");
        if (depth < df) held.add("depth " + df + " (derived " + depth + ")");
        if (pocket < d.pocket()) held.add("pocket " + d.pocket() + " (derived " + pocket + ")");
        return new Rule(n, slope, Math.max(cliff, cf), Math.max(depth, df), Math.max(pocket, d.pocket()), sky, true, String.join(", ", held));
    }

    /** 0.39.z54 A1: the patterns the bot labelled (read from an older file; they weigh cave.botLabelWeight, 0 by default). */
    public synchronized int botEntries() {
        int n = 0;
        for (Patch p : list) if (!p.owner()) n++;
        return n;
    }

    // ---- the nearest-neighbour vote ----

    /** One of the 8 turns / mirrors of a patch. */
    static int[] turn(int[] ws, int t) {
        int[] out = new int[ws.length];
        for (int dz = -R; dz <= R; dz++)
            for (int dx = -R; dx <= R; dx++) {
                int x = dx, z = dz;
                for (int i = 0; i < (t & 3); i++) { int tmp = x; x = -z; z = tmp; }
                if ((t & 4) != 0) x = -x;
                out[(z + R) * SIDE + x + R] = ws[(dz + R) * SIDE + dx + R];
            }
        return out;
    }

    /** The mean squared height difference of two patches, the smallest over the 8 turns / mirrors of b. */
    static double patchD2(int[] a, int[] b) {
        double best = Double.MAX_VALUE;
        for (int t = 0; t < 8; t++) {
            int[] c = turn(b, t);
            double s = 0;
            for (int i = 0; i < a.length; i++) { double d = a[i] - c[i]; s += d * d; }
            best = Math.min(best, s / a.length);
        }
        return best;
    }

    /** The vote: the cave side's weight and the other side's among the k nearest, and the nearest's line. */
    public record Vote(boolean cave, double caveW, double otherW, String nearest) {
        public String line() { return String.format(java.util.Locale.ROOT, "patterns: cave %.1f vs not %.1f (nearest %s)", caveW, otherW, nearest); }
    }

    /**
     * The vote over the labelled patches for a spot. {@code full}: the depth, pocket and darkest sky count too (known after
     * the hole walk); else the patch alone (the heightmap stage). {@code skip}: a patch left out (leave-one-out checks).
     */
    public synchronized Vote vote(int[] ws, int depth, int pocket, int darkSky, boolean full, Patch skip) {
        List<Patch> lab = new ArrayList<>();
        for (Patch p : list) if (p != skip && p.votes()) lab.add(p);      // z54 A1: a 0-weight patch takes no place among the k
        if (lab.isEmpty()) return new Vote(true, 0, 0, "none");
        double sp = 0, md = 0, mp = 0, ms = 0;
        for (Patch p : lab) { for (int v : p.ws()) sp += (double) v * v; md += p.depth(); mp += p.pocket(); ms += p.darkSky(); }
        sp = Math.max(1, Math.sqrt(sp / (lab.size() * (double) SIDE * SIDE)));
        md /= lab.size(); mp /= lab.size(); ms /= lab.size();
        double vd = 0, vp = 0, vs = 0;
        for (Patch p : lab) { vd += sq(p.depth() - md); vp += sq(p.pocket() - mp); vs += sq(p.darkSky() - ms); }
        double sd = Math.max(1, Math.sqrt(vd / lab.size())), so = Math.max(1, Math.sqrt(vp / lab.size())), ss = Math.max(1, Math.sqrt(vs / lab.size()));
        List<double[]> ds = new ArrayList<>();
        for (int i = 0; i < lab.size(); i++) {
            Patch p = lab.get(i);
            double d = patchD2(ws, p.ws()) / (sp * sp);
            if (full) d += sq((depth - p.depth()) / sd) + sq((pocket - p.pocket()) / so) + sq((darkSky - p.darkSky()) / ss);
            ds.add(new double[]{d, i});
        }
        ds.sort(Comparator.comparingDouble(a -> a[0]));
        int k = Math.max(1, Tuning.i("cave.patternK"));
        double cw = 0, ow = 0;
        for (int i = 0; i < Math.min(k, ds.size()); i++) {
            Patch p = lab.get((int) ds.get(i)[1]);
            if (p.cave()) cw += p.weight(); else ow += p.weight();
        }
        Patch n = lab.get((int) ds.get(0)[1]);
        return new Vote(cw > ow, cw, ow, n.label() + " at " + n.x() + " " + n.y() + " " + n.z() + " (" + n.by() + ")");
    }

    private static double sq(double v) { return v * v; }

    // ---- the hole walk and the pocket (rule 17: the hole's own air column, then the near-only fill from a seen mouth) ----

    /** The cells a walk reads (the game's level, or a test grid). */
    public interface Cells {
        boolean air(int x, int y, int z);
        boolean water(int x, int y, int z);
        int sky(int x, int y, int z);
    }

    /** The hole: its floor column, rim (the highest top in the ring), its first non-air cell, depth, sky there, the pocket. */
    public record Hole(int fx, int fz, int rim, int floorY, int depth, int sky, int pocket, int darkSky, boolean water) {
        public String numbers() {
            return "depth " + depth + " (rim " + rim + " to " + fx + " " + floorY + " " + fz + "), sky " + sky + " there, pocket " + pocket
                    + " dark cells, darkest sky " + darkSky + ", water " + (water ? "yes" : "no");
        }
    }

    /**
     * The walk: inside the ring of {@code cave.cliffReach} columns round the candidate the lowest top is the floor column and
     * the highest the rim; from the rim down that column through air to the first non-air cell (at most
     * {@code cave.floorMaxDepth}); depth = rim minus that cell. {@code top(x, z)}: the WORLD_SURFACE top block Y.
     */
    public static Hole walk(int x, int z, java.util.function.IntBinaryOperator top, Cells cells) {
        int r = CaveMouths.cliffReach();
        int fx = x, fz = z, low = top.applyAsInt(x, z), rim = low;
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++) {
                int h = top.applyAsInt(x + dx, z + dz);
                if (h < low) { low = h; fx = x + dx; fz = z + dz; }
                rim = Math.max(rim, h);
            }
        int y = rim, max = Math.max(1, Tuning.i("cave.floorMaxDepth"));
        while (rim - y < max && cells.air(fx, y, fz)) y--;
        int sky = cells.sky(fx, y + 1, fz);
        boolean water = cells.water(fx, y, fz) || cells.water(fx, y + 1, fz);
        int[] pk = pocket(fx, y + 1, fz, rim, cells);
        return new Hole(fx, fz, rim, y, rim - y, sky, pk[0], pk[1], water);
    }

    /**
     * 0.39.z54 A3 + review 2: the walk over GROUND heights ({@code ground}: {@link CaveMouths#NOT_LOADED} for a column of a
     * chunk not loaded). Null when the candidate's own column is not loaded (rule 15: no centre height, no walk - the
     * caller skips the candidate this pass); a neighbour column not loaded reads as the centre's height (no difference).
     */
    public static Hole walkGround(int x, int z, java.util.function.IntBinaryOperator ground, Cells cells) {
        int c = ground.applyAsInt(x, z);
        if (c == CaveMouths.NOT_LOADED) return null;
        return walk(x, z, (cx, cz) -> { int t = ground.applyAsInt(cx, cz); return t == CaveMouths.NOT_LOADED ? c : t; }, cells);
    }

    /**
     * 0.39.z54 review 2: the 9 x 9 patch of ground heights relative to the centre; null when the centre's column is not
     * loaded (never an all-zero patch); a neighbour column not loaded reads 0 (no difference).
     */
    public static int[] patchGround(int x, int z, java.util.function.IntBinaryOperator ground) {
        int c = ground.applyAsInt(x, z);
        if (c == CaveMouths.NOT_LOADED) return null;
        int[] out = new int[SIDE * SIDE];
        for (int dz = -R; dz <= R; dz++)
            for (int dx = -R; dx <= R; dx++) {
                int t = ground.applyAsInt(x + dx, z + dz);
                out[(dz + R) * SIDE + dx + R] = t == CaveMouths.NOT_LOADED ? 0 : t - c;
            }
        return out;
    }

    /**
     * {dark cells, darkest sky}: a flood fill over air from the floor cell, within {@code cave.pocketReach} blocks of it on
     * each axis and under the rim (the hole's own air), counting the cells with sky light at most {@code cave.maxSky}.
     */
    public static int[] pocket(int x, int y, int z, int rim, Cells cells) { return fill(x, y, z, rim, cells, null); }

    /**
     * 0.39.z51 item 1: the dark pocket's cells themselves (the same near-only fill as {@link #pocket}; packed by
     * {@link Sightings#cell}), stored once on the confirmed mouth for the caving step to walk into.
     */
    public static List<Long> pocketCells(int x, int y, int z, int rim, Cells cells) {
        List<Long> out = new java.util.ArrayList<>();
        fill(x, y, z, rim, cells, out);
        return out;
    }

    private static int[] fill(int x, int y, int z, int rim, Cells cells, List<Long> darkCells) {
        int reach = Tuning.i("cave.pocketReach"), maxSky = Tuning.i("cave.maxSky");
        int dark = 0, darkest = 15;     // structural: the brightest sky light
        if (!cells.air(x, y, z)) return new int[]{0, darkest};
        Deque<int[]> q = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        q.add(new int[]{x, y, z});
        seen.add(Seen.key(x, y, z));
        int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty()) {
            int[] p = q.poll();
            int s = cells.sky(p[0], p[1], p[2]);
            if (s <= maxSky) {
                dark++;
                if (darkCells != null) darkCells.add(Sightings.cell(p[0], p[1], p[2]));
            }
            darkest = Math.min(darkest, s);
            for (int[] d : nb) {
                int nx = p[0] + d[0], ny = p[1] + d[1], nz = p[2] + d[2];
                if (Math.abs(nx - x) > reach || Math.abs(ny - y) > reach || Math.abs(nz - z) > reach || ny > rim) continue;
                if (!seen.add(Seen.key(nx, ny, nz))) continue;
                if (cells.air(nx, ny, nz)) q.add(new int[]{nx, ny, nz});
            }
        }
        return new int[]{dark, darkest};
    }

    /** The mouth verdict: null = a cave mouth (the rule's depth met, the pocket OR the darkest sky met, the vote cave), else why not. */
    public static String verdict(Hole h, Rule rule, Vote v) {
        if (h.water()) return "its floor is water (a pond)";
        if (h.depth() < rule.depth()) return "depth " + h.depth() + " < " + rule.depth();
        // 0.39.z52 (owner): two dark proofs, either confirms: the pocket rule OR the darkest-sky rule
        if (h.pocket() < rule.pocket() && h.darkSky() > rule.darkSky())
            return "no dark proof: pocket " + h.pocket() + " < " + rule.pocket() + " dark cells (a cell is not a pocket) and darkest sky " + h.darkSky() + " > " + rule.darkSky();
        if (!v.cave()) return "nearer the not-a-cave patterns (" + v.line() + ")";
        return null;
    }

    /**
     * 0.39.z54 A4 (owner's flight on a new seed: 8 of 10 confirmed mouths had the darkest sky alone, 0 pocket cells; the
     * labelling walk: the owner called 3 of 4 such mouths cliff): for a hole {@link #verdict} passed, null = it confirms
     * (the pocket proof, or the darkest sky with the vote's cave side ahead by {@code cave.skyOnlyVoteMargin}), else why it
     * stays a candidate ("darkest sky only, pocket N, vote cave X vs not Y").
     */
    public static String hold(Hole h, Rule rule, Vote v) {
        if (h.pocket() >= rule.pocket()) return null;
        double margin = Tuning.get("cave.skyOnlyVoteMargin");
        if (v.caveW() - v.otherW() >= margin) return null;
        return String.format(java.util.Locale.ROOT, "darkest sky only, pocket %d, vote cave %.1f vs not %.1f (the darkest sky alone needs the cave side ahead by %.1f)",
                h.pocket(), v.caveW(), v.otherW(), margin);
    }

    // ---- merge: one feature = one candidate ----

    /** Candidates within {@code cave.mergeM} of a lower one merge into it (the lowest point kept). */
    public static List<CaveMouths.Candidate> merge(List<CaveMouths.Candidate> in) {
        List<CaveMouths.Candidate> sorted = new ArrayList<>(in);
        sorted.sort(Comparator.comparingInt(CaveMouths.Candidate::y));
        double m = Tuning.get("cave.mergeM");
        List<CaveMouths.Candidate> out = new ArrayList<>();
        for (CaveMouths.Candidate c : sorted) {
            boolean near = false;
            for (CaveMouths.Candidate o : out) {
                double dx = o.x() - c.x(), dz = o.z() - c.z();
                if (dx * dx + dz * dz <= m * m) { near = true; break; }
            }
            if (!near) out.add(c);
        }
        return out;
    }

    // ---- the file ----

    public synchronized String toJson() {
        JsonArray a = new JsonArray();
        for (Patch p : list) a.add(json(p));
        JsonObject o = new JsonObject();
        o.add("patterns", a);
        return new Gson().toJson(o);
    }

    static JsonObject json(Patch p) {
        JsonObject j = new JsonObject();
        j.addProperty("x", p.x()); j.addProperty("y", p.y()); j.addProperty("z", p.z());
        j.addProperty("label", p.label()); j.addProperty("by", p.by());
        j.addProperty("depth", p.depth()); j.addProperty("pocket", p.pocket()); j.addProperty("darkSky", p.darkSky());
        j.add("ws", ints(p.ws()));
        if (p.of() != null) j.add("of", ints(p.of()));
        return j;
    }

    private static JsonArray ints(int[] v) {
        JsonArray a = new JsonArray();
        for (int i : v) a.add(i);
        return a;
    }

    /** Reads a file's patterns into this; a broken entry is counted in {@code skipped} (the caller says it), never dropped quietly. */
    public synchronized void read(String json, int[] skipped) {
        JsonElement e = new Gson().fromJson(json, JsonElement.class);
        JsonArray a = e != null && e.isJsonObject() && e.getAsJsonObject().has("patterns") ? e.getAsJsonObject().getAsJsonArray("patterns") : e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
        if (a == null) { skipped[0]++; return; }
        edits++;                                  // z54 review 3
        for (JsonElement x : a) {
            try {
                JsonObject j = x.getAsJsonObject();
                int[] ws = arr(j.getAsJsonArray("ws"));
                if (ws.length != SIDE * SIDE) { skipped[0]++; continue; }
                int[] of = j.has("of") ? arr(j.getAsJsonArray("of")) : null;
                list.add(new Patch(j.get("x").getAsInt(), j.get("y").getAsInt(), j.get("z").getAsInt(), j.get("label").getAsString(),
                        j.has("by") ? j.get("by").getAsString() : "owner", j.get("depth").getAsInt(), j.get("pocket").getAsInt(),
                        j.has("darkSky") ? j.get("darkSky").getAsInt() : 15, ws, of));
            } catch (RuntimeException ex) {
                skipped[0]++;
            }
        }
    }

    private static int[] arr(JsonArray a) {
        int[] v = new int[a.size()];
        for (int i = 0; i < v.length; i++) v[i] = a.get(i).getAsInt();
        return v;
    }

    /** aa cave list: the patterns and the rule. */
    public synchronized String text() {
        StringBuilder b = new StringBuilder(rule().line());
        int cave = 0, not = 0, unk = 0, bot = 0;
        for (Patch p : list) { if (p.cave()) cave++; else if (p.notCave()) not++; else unk++; if (!p.owner()) bot++; }
        b.append("\n").append(list.size()).append(" patterns: ").append(cave).append(" cave, ").append(not).append(" not a cave, ").append(unk).append(" unknown (").append(bot)
                .append(" bot-labelled, weighing ").append(Tuning.get("cave.botLabelWeight")).append(")");
        for (Patch p : list) {
            int[] s = shape(p.ws());
            b.append("\n").append(p.label()).append(" ").append(p.x()).append(" ").append(p.y()).append(" ").append(p.z()).append(" (").append(p.by())
                    .append("): cliff ").append(s[0]).append(", slope ").append(s[1]).append(", depth ").append(p.depth()).append(", pocket ").append(p.pocket()).append(", darkest sky ").append(p.darkSky());
        }
        return b.toString();
    }
}
