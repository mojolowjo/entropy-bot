package io.github.mojolowjo.entropybot.sense;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 0.39.z78 J6 (the owner 2026-10-27 late: "it needs to be a system we can check. I KNOW if it's a cave; get the
 * information of what caves start like and replicate."): the owner's cave labels on the cave index's volumes and the
 * floor derived from them.
 * <ul>
 * <li>A {@link Label} is the index volume the owner pointed at ({@code aa cave yes|no}) with its measurements at label
 * time - its entry (the opening cell nearest the eyes), its rim (opening cells), its reach (blocks from the opening's
 * lowest cell to its lowest cell; -1: no opening), size and depth - the verdict, the world's seed (null: not known, a
 * server world), the time and the world. The floor can be re-derived and re-checked from the file alone, offline.</li>
 * <li>{@link #derive}: the floor. With fewer than {@link #LABELS_TO_DERIVE} caves labelled yes: the defaults
 * ({@link CaveIndex#MIN_RIM} / {@link CaveIndex#MIN_REACH}). With more: the smallest rim and the smallest reach among the
 * yes labels (the floor lets every cave the owner named pass), each at least {@link #HARD_RIM} / {@link #HARD_REACH} and
 * never above the defaults (labels only widen the floor toward what the owner showed; they never narrow it past the
 * carver's own shape).</li>
 * <li>{@link #check}: the classification against the labels - recall (labelled caves the floor passes) and precision
 * (of the labelled volumes the floor passes, the share that are caves), the misses with their rim and reach; then, when
 * the index is at hand, the credits the labelled caves got this session.</li>
 * </ul>
 * Files: {@code cave-labels.json}, the global one in the bot's folder (every world: the floor reads it) and the world's
 * own; the old {@code cave-patterns.json} (54 heightmap-patch labels) stays archived and unread. Nothing in the planner
 * reads the labels except through the derived floor (rule 17: the floor decides what the index calls a cave; the rays
 * still decide what is known). Offline: the JUnit {@code CaveLabelsOfflineCheck} prints the check for the file named by
 * the CAVE_LABELS environment variable.
 * <p>Run EF (the manager's rim fix): a label stores the MEASURED OPENING ({@link #MEASURE}): its rim (its cell count),
 * its reach (below its own lowest cell) and its cells' coordinates (packed; deflated and base64 when over
 * {@link #CELLS_PLAIN}), so a later fix can measure again offline from the file. Labels from 9710ddad / 2f215a09 carry
 * one rim summed over the whole volume and no cells: the OLD measure - they count for the verdict in the check, never
 * for the derivation, and the check says them. Pure (JUnit: J6CaveLabelsTest). Loader notes: none (ChunkPass reads and writes
 * the files; CaveLabel aims).
 */
public final class CaveLabels {
    /** The yes labels the floor needs before it is derived; fewer: the defaults. */
    public static final int LABELS_TO_DERIVE = 5; // structural: the manager's ruling - fewer is no sample
    /**
     * The smallest rim the floor may take: the cave carver's thinnest tunnel face is 2 cells (cave.json horizontal radius
     * 1.5 x 0.7 = 1.05 a side; CaveWorldCarver), so fewer than 2 opening cells is a crack, never a carver mouth.
     */
    public static final int HARD_RIM = 2; // structural: the carver's thinnest face (the manager's ruling)
    /**
     * The smallest reach the floor may take: the thinnest carver tunnel is 2 cells tall (vertical radius 1.5 x 0.7 x 0.8 =
     * 0.84 a side), so a cave goes at least one more cell than that below its mouth.
     */
    public static final int HARD_REACH = 3; // structural: one cell past the carver's thinnest tunnel (the manager's ruling)
    /** The label measure written now: one opening measured on its own, its cells stored (run EF); 1: the old summed rim. */
    public static final int MEASURE = 2; // structural: a file format version, not a number of the world
    /** Up to this many cells an opening's cells are written as plain [x, y, z]; more: deflated and base64 (a big opening). */
    public static final int CELLS_PLAIN = 256; // structural: a file size bound

    /** One label: the volume's measurements at label time and the owner's verdict ({@code cave}: yes). */
    public record Label(int x, int y, int z, int rim, int reach, String size, String depth, boolean cave, Long seed, long at, String world,
                        int measure, List<Long> cells) {
        public Label {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }

        public String where() { return x + " " + y + " " + z; }

        /** A label of the old measure (one rim summed over the volume, no cells): not used to derive the floor. */
        public boolean old() { return measure < MEASURE; }
    }

    /** The floor: rim and reach, the yes labels it came from, derived (else the defaults). */
    public record Floor(int rim, int reach, int yes, boolean derived) {
        public String line() {
            return "cave floor: rim >= " + rim + ", reach >= " + reach + (derived ? " (derived from " + yes + " caves labelled yes: their smallest, floored at rim " + HARD_RIM + " / reach " + HARD_REACH + ")"
                    : " (the defaults: " + yes + " of " + LABELS_TO_DERIVE + " caves labelled yes needed to derive it)");
        }
    }

    private final List<Label> list = new ArrayList<>();
    private boolean dirty;

    public synchronized List<Label> all() { return List.copyOf(list); }

    public synchronized boolean dirty() { return dirty; }

    public synchronized void saved() { dirty = false; }

    /** A label: one per entry cell and world - a new verdict at the same entry replaces the old one. */
    public synchronized void add(Label l) {
        list.removeIf(o -> o.x() == l.x() && o.y() == l.y() && o.z() == l.z() && java.util.Objects.equals(o.world(), l.world()));
        list.add(l);
        dirty = true;
    }

    /** A label replaced by another (a re-measure: its entry may move to the measured opening's cell). */
    public synchronized void replace(Label old, Label now) {
        list.remove(old);
        list.removeIf(o -> o.x() == now.x() && o.y() == now.y() && o.z() == now.z() && java.util.Objects.equals(o.world(), now.world()));
        list.add(now);
        dirty = true;
    }

    /**
     * The owner's ruling after his first labels: an old-measure label is never relabelled by hand - when its volume is in
     * the index (its entry cell is an index cell) it is measured again per opening ({@link CaveIndex#labelSpot} at that
     * cell: the volume's deciding opening, its rim, reach and cells) and promoted to {@link #MEASURE}; his verdict, the
     * seed, the time and the world stay. Null: not an old label, or its volume not loaded (it stays old). Pure.
     */
    public static Label remeasure(Label old, CaveIndex idx) {
        if (old == null || !old.old() || idx.volumeAt(old.x(), old.y(), old.z()) < 0) return null;
        CaveIndex.Spot s = idx.labelSpot(List.of(new int[]{old.x(), old.y(), old.z()}), null, old.x() + 0.5, old.y() + 0.5, old.z() + 0.5, 0);
        if (s == null) return null;
        return new Label(s.x(), s.y(), s.z(), s.rim(), s.reach(), s.size().name().toLowerCase(Locale.ROOT), s.depth().name().toLowerCase(Locale.ROOT),
                old.cave(), old.seed(), old.at(), old.world(), MEASURE, s.cells());
    }

    /** The floor from the yes labels (see the class note). Pure. */
    public static Floor derive(List<Label> labels) {
        int yes = 0, rim = Integer.MAX_VALUE, reach = Integer.MAX_VALUE;
        for (Label l : labels) {
            if (!l.cave() || l.reach() < 0 || l.old()) continue;  // a yes with no opening says nothing of an opening's size; the old measure summed
            yes++;
            rim = Math.min(rim, l.rim());
            reach = Math.min(reach, l.reach());
        }
        if (yes < LABELS_TO_DERIVE) return new Floor(CaveIndex.MIN_RIM, CaveIndex.MIN_REACH, yes, false);
        return new Floor(Math.min(CaveIndex.MIN_RIM, Math.max(HARD_RIM, rim)), Math.min(CaveIndex.MIN_REACH, Math.max(HARD_REACH, reach)), yes, true);
    }

    /** The floor passes the label's volume as a cave (an opening, rim and reach at least the floor's). */
    public static boolean passes(Label l, Floor f) { return l.reach() >= 0 && l.rim() >= f.rim() && l.reach() >= f.reach(); }

    /** The classification against the labels: the counts and the misses. */
    public record Check(int yes, int no, int yesPass, int noPass, List<Label> missed, List<Label> wrong) {
        /** Labelled caves the floor passes, of the labelled caves (NaN: none labelled yes). */
        public double recall() { return yes == 0 ? Double.NaN : yesPass / (double) yes; }

        /** Labelled caves among the labelled volumes the floor passes (NaN: it passes none). */
        public double precision() { return yesPass + noPass == 0 ? Double.NaN : yesPass / (double) (yesPass + noPass); }
    }

    public static Check check(List<Label> labels, Floor f) {
        int yes = 0, no = 0, yp = 0, np = 0;
        List<Label> missed = new ArrayList<>(), wrong = new ArrayList<>();
        for (Label l : labels) {
            boolean p = passes(l, f);
            if (l.cave()) { yes++; if (p) yp++; else missed.add(l); }
            else { no++; if (p) { np++; wrong.add(l); } }
        }
        return new Check(yes, no, yp, np, missed, wrong);
    }

    /**
     * The text of {@code aa cave check} (and the offline check): the floor, the classification (precision, recall, the
     * misses with their rim and reach), then the credits the labelled caves got ({@code credits}: a label to {rim credits,
     * air credits, known 0/1}, null: its volume not loaded; the whole {@code credits} null: offline, the line left out).
     */
    public static String checkText(List<Label> labels, Floor f, Function<Label, int[]> credits) { return checkText(labels, f, credits, 0); }

    /** As above; {@code remeasured}: the old-measure labels promoted this session (said when any). */
    public static String checkText(List<Label> labels, Floor f, Function<Label, int[]> credits, int remeasured) {
        if (labels.isEmpty()) return "cave check: no labels yet (aa cave yes|no at a volume's opening)\n" + f.line();
        Check c = check(labels, f);
        List<String> out = new ArrayList<>();
        out.add(f.line());
        out.add(String.format(Locale.ROOT, "classification: %d labels (%d caves, %d not); the floor passes %d of %d caves (recall %s) and %d of %d non-caves (precision %s)",
                labels.size(), c.yes(), c.no(), c.yesPass(), c.yes(), pct(c.recall()), c.noPass(), c.no(), pct(c.precision())));
        for (Label l : c.missed()) out.add("  missed cave at " + l.where() + ": " + measures(l) + why(l, f));
        for (Label l : c.wrong()) out.add("  passed non-cave at " + l.where() + ": " + measures(l));
        int old = 0;
        for (Label l : labels) if (l.old()) old++;
        if (remeasured > 0) out.add(remeasured + " labels re-measured (old measure -> per opening, from the index; your verdicts kept)");
        if (old > 0) out.add("old measure: " + old + " label(s) (one rim summed over the whole volume, no opening cells; their volume not loaded yet): counted for the verdict, not used to derive the floor");
        if (credits != null) {
            int rim = 0, air = 0, known = 0, unloaded = 0;
            for (Label l : labels) {
                if (!l.cave()) continue;
                int[] s = credits.apply(l);
                if (s == null) { unloaded++; continue; }
                if (s[0] > 0) rim++;
                if (s[1] > 0) air++;
                if (s[2] > 0) known++;
            }
            out.add("credits this session (of " + c.yes() + " labelled caves): rim credits " + rim + ", air credits " + air + ", known " + known
                    + (unloaded > 0 ? ", " + unloaded + " not loaded" : ""));
        }
        return String.join("\n", out);
    }

    private static String measures(Label l) {
        return (l.old() ? "(old measure) " : "") + "rim " + l.rim() + ", reach " + (l.reach() < 0 ? "none (no opening)" : String.valueOf(l.reach())) + ", size " + l.size() + ", depth " + l.depth()
                + (l.world() == null ? "" : " (" + l.world() + ")");
    }

    private static String why(Label l, Floor f) {
        List<String> w = new ArrayList<>();
        if (l.reach() < 0) w.add("no opening");
        else {
            if (l.rim() < f.rim()) w.add("rim under " + f.rim());
            if (l.reach() < f.reach()) w.add("reach under " + f.reach());
        }
        return w.isEmpty() ? "" : " - " + String.join(", ", w);
    }

    private static String pct(double v) { return Double.isNaN(v) ? "n/a" : Math.round(v * 100) + "%"; }

    // ---- the file ----

    private static List<Long> cellsOf(JsonObject j) {
        List<Long> out = new ArrayList<>();
        if (j.has("cells")) for (JsonElement e : j.getAsJsonArray("cells")) {
            JsonArray q = e.getAsJsonArray();
            out.add(Sightings.cell(q.get(0).getAsInt(), q.get(1).getAsInt(), q.get(2).getAsInt()));
        }
        else if (j.has("cellsZ")) out.addAll(unpack(j.get("cellsZ").getAsString()));
        return out;
    }

    /** Packed cells, 8 bytes each, deflated, base64. */
    static String pack(List<Long> cells) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(8 * cells.size());           // structural: 8 bytes a long
        for (long c : cells) b.putLong(c);
        java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
        d.setInput(b.array());
        d.finish();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];                                                       // structural: a copy buffer
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
    }

    static List<Long> unpack(String s) {
        try {
            java.util.zip.Inflater in = new java.util.zip.Inflater();
            in.setInput(java.util.Base64.getDecoder().decode(s));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];                                                   // structural: a copy buffer
            while (!in.finished()) {
                int n = in.inflate(buf);
                if (n == 0 && (in.needsInput() || in.needsDictionary())) break;
                out.write(buf, 0, n);
            }
            in.end();
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(out.toByteArray());
            List<Long> cells = new ArrayList<>();
            while (b.remaining() >= 8) cells.add(b.getLong());                            // structural: 8 bytes a long
            return cells;
        } catch (java.util.zip.DataFormatException | IllegalArgumentException e) {
            throw new IllegalArgumentException("cave-labels.json: the cellsZ of a label is not readable: " + e.getMessage(), e);   // read() counts it skipped
        }
    }

    public synchronized String toJson() {
        JsonArray a = new JsonArray();
        for (Label l : list) {
            JsonObject j = new JsonObject();
            j.addProperty("x", l.x()); j.addProperty("y", l.y()); j.addProperty("z", l.z());
            j.addProperty("rim", l.rim()); j.addProperty("reach", l.reach());
            j.addProperty("size", l.size()); j.addProperty("depth", l.depth());
            j.addProperty("verdict", l.cave() ? "yes" : "no");
            if (l.seed() != null) j.addProperty("seed", l.seed());
            j.addProperty("at", l.at());
            if (l.world() != null) j.addProperty("world", l.world());
            j.addProperty("measure", l.measure());
            if (l.cells().size() <= CELLS_PLAIN) {
                JsonArray cs = new JsonArray();
                for (long c : l.cells()) {
                    int[] p = Sightings.cellOf(c);
                    JsonArray q = new JsonArray();
                    q.add(p[0]); q.add(p[1]); q.add(p[2]);
                    cs.add(q);
                }
                j.add("cells", cs);
            } else j.addProperty("cellsZ", pack(l.cells()));
            a.add(j);
        }
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        o.add("labels", a);
        return new Gson().toJson(o);
    }

    /** A file's labels; a broken entry is counted in {@code skipped} (the caller says it), never dropped quietly. */
    public static CaveLabels read(String json, int[] skipped) {
        CaveLabels out = new CaveLabels();
        try {
            JsonElement e = new Gson().fromJson(json, JsonElement.class);
            if (e == null || !e.isJsonObject() || !e.getAsJsonObject().has("labels")) { skipped[0]++; return out; }
            for (JsonElement x : e.getAsJsonObject().getAsJsonArray("labels")) {
                try {
                    JsonObject j = x.getAsJsonObject();
                    out.list.add(new Label(j.get("x").getAsInt(), j.get("y").getAsInt(), j.get("z").getAsInt(), j.get("rim").getAsInt(), j.get("reach").getAsInt(),
                            j.get("size").getAsString(), j.get("depth").getAsString(), "yes".equals(j.get("verdict").getAsString()),
                            j.has("seed") ? j.get("seed").getAsLong() : null, j.get("at").getAsLong(), j.has("world") ? j.get("world").getAsString() : null,
                            j.has("measure") ? j.get("measure").getAsInt() : 1, cellsOf(j)));
                } catch (RuntimeException ex) {
                    skipped[0]++;
                }
            }
        } catch (RuntimeException ex) {
            skipped[0]++;
        }
        return out;
    }
}
