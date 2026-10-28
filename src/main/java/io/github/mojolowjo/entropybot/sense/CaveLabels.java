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
 * the CAVE_LABELS environment variable. Pure (JUnit: J6CaveLabelsTest). Loader notes: none (ChunkPass reads and writes
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

    /** One label: the volume's measurements at label time and the owner's verdict ({@code cave}: yes). */
    public record Label(int x, int y, int z, int rim, int reach, String size, String depth, boolean cave, Long seed, long at, String world) {
        public String where() { return x + " " + y + " " + z; }
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

    /** The floor from the yes labels (see the class note). Pure. */
    public static Floor derive(List<Label> labels) {
        int yes = 0, rim = Integer.MAX_VALUE, reach = Integer.MAX_VALUE;
        for (Label l : labels) {
            if (!l.cave() || l.reach() < 0) continue;            // a yes with no opening says nothing of an opening's size
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
    public static String checkText(List<Label> labels, Floor f, Function<Label, int[]> credits) {
        if (labels.isEmpty()) return "cave check: no labels yet (aa cave yes|no at a volume's opening)\n" + f.line();
        Check c = check(labels, f);
        List<String> out = new ArrayList<>();
        out.add(f.line());
        out.add(String.format(Locale.ROOT, "classification: %d labels (%d caves, %d not); the floor passes %d of %d caves (recall %s) and %d of %d non-caves (precision %s)",
                labels.size(), c.yes(), c.no(), c.yesPass(), c.yes(), pct(c.recall()), c.noPass(), c.no(), pct(c.precision())));
        for (Label l : c.missed()) out.add("  missed cave at " + l.where() + ": " + measures(l) + why(l, f));
        for (Label l : c.wrong()) out.add("  passed non-cave at " + l.where() + ": " + measures(l));
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
        return "rim " + l.rim() + ", reach " + (l.reach() < 0 ? "none (no opening)" : String.valueOf(l.reach())) + ", size " + l.size() + ", depth " + l.depth()
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
                            j.has("seed") ? j.get("seed").getAsLong() : null, j.get("at").getAsLong(), j.has("world") ? j.get("world").getAsString() : null));
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
