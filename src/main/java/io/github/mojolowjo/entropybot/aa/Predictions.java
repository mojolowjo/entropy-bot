package io.github.mojolowjo.entropybot.aa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AA_STEP3 B: the prediction ledger, {@code entropybot/predictions.csv}: one row per step of AA's chains, the seconds the
 * time model predicted against the seconds it took, the blocks walked and the health lost meanwhile. Written in the
 * background (the adapter), in normal mode too. {@code aa predict} prints the last 20 rows and the step kinds (first word)
 * whose summed actual seconds differ from the summed prediction by more than {@link #TOLERANCE} over at least
 * {@link #MIN_SAMPLES} samples: where the model is wrong. Pure (JUnit: SlowModeTest). Loader notes: none.
 */
public final class Predictions {
    private Predictions() {}

    public static final String HEADER = "time,objective,key,step,predicted_s,actual_s,blocks,damage";
    public static final double TOLERANCE = 0.25;
    public static final int MIN_SAMPLES = 5, LAST = 20;

    /** One finished step. {@code timeMs} is the wall clock at its end. */
    public record Row(long timeMs, String objective, String key, String step, double predictedS, double actualS, double blocks, double damage) {
        /** (actual - predicted) / predicted; NaN when nothing was predicted. */
        public double error() { return predictedS > 0 ? (actualS - predictedS) / predictedS : Double.NaN; }
    }

    /** The CSV text to append for this row (the header first when the file is new). */
    public static String append(boolean fileHasContent, Row r) {
        return (fileHasContent ? "" : HEADER + "\n") + line(r) + "\n";
    }

    public static String line(Row r) {
        return r.timeMs() + "," + q(r.objective()) + "," + q(r.key()) + "," + q(r.step()) + "," + f(r.predictedS()) + "," + f(r.actualS())
                + "," + f(r.blocks()) + "," + f(r.damage());
    }

    private static String f(double v) { return Double.isFinite(v) ? String.format(Locale.ROOT, "%.1f", v) : ""; }

    private static String q(String s) {
        String t = s == null ? "" : s.replace("\n", " ").replace("\r", " ");
        return t.contains(",") || t.contains("\"") ? "\"" + t.replace("\"", "\"\"") + "\"" : t;
    }

    /** Splits one CSV line (quotes and doubled quotes understood). */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQ) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { b.append('"'); i++; }
                else if (ch == '"') inQ = false;
                else b.append(ch);
            } else if (ch == '"') inQ = true;
            else if (ch == ',') { out.add(b.toString()); b.setLength(0); }
            else b.append(ch);
        }
        out.add(b.toString());
        return out;
    }

    /** The rows of a file's text; a bad line is counted in {@code bad[0]} and skipped (never silently). */
    public static List<Row> parse(String text, int[] bad) {
        List<Row> rows = new ArrayList<>();
        if (text == null) return rows;
        for (String ln : text.split("\n")) {
            String l = ln.strip();
            if (l.isEmpty() || l.startsWith("time,")) continue;
            try {
                List<String> c = split(l);
                if (c.size() != 8) throw new IllegalArgumentException("columns " + c.size());
                rows.add(new Row(Long.parseLong(c.get(0)), c.get(1), c.get(2), c.get(3), d(c.get(4)), d(c.get(5)), d(c.get(6)), d(c.get(7))));
            } catch (RuntimeException e) {
                if (bad != null && bad.length > 0) bad[0]++;
            }
        }
        return rows;
    }

    private static double d(String s) { return s.isEmpty() ? Double.NaN : Double.parseDouble(s); }

    /** A step kind whose predictions are off: samples, summed predicted and actual seconds, the error. */
    public record Off(String key, int n, double predictedS, double actualS, double error) {}

    /** The kinds with at least {@link #MIN_SAMPLES} predicted samples and |error| over {@link #TOLERANCE}, worst first. */
    public static List<Off> off(List<Row> rows) {
        Map<String, double[]> sum = new LinkedHashMap<>();
        for (Row r : rows) {
            if (!(r.predictedS() > 0) || !Double.isFinite(r.actualS())) continue;
            double[] a = sum.computeIfAbsent(r.key(), k -> new double[3]);
            a[0]++;
            a[1] += r.predictedS();
            a[2] += r.actualS();
        }
        List<Off> out = new ArrayList<>();
        for (var e : sum.entrySet()) {
            double[] a = e.getValue();
            double err = (a[2] - a[1]) / a[1];
            if (a[0] >= MIN_SAMPLES && Math.abs(err) > TOLERANCE) out.add(new Off(e.getKey(), (int) a[0], a[1], a[2], err));
        }
        out.sort((x, y) -> Double.compare(Math.abs(y.error()), Math.abs(x.error())));
        return out;
    }

    /** The reply of {@code aa predict}. */
    public static String text(List<Row> rows, int bad, String where) {
        StringBuilder b = new StringBuilder();
        if (rows.isEmpty()) return "aa predict: no rows yet in " + where + " (a row is written when each step of an AA chain ends)" + (bad > 0 ? "; " + bad + " unreadable line(s) skipped" : "");
        b.append("aa predict: last ").append(Math.min(LAST, rows.size())).append(" of ").append(rows.size()).append(" steps (predicted -> actual s, blocks, hp lost):");
        for (int i = Math.max(0, rows.size() - LAST); i < rows.size(); i++) {
            Row r = rows.get(i);
            b.append('\n').append(r.step()).append(": ").append(f(r.predictedS()).isEmpty() ? "?" : f(r.predictedS())).append(" -> ").append(f(r.actualS()))
                    .append(" s, ").append(f(r.blocks())).append(" blocks, ").append(f(r.damage())).append(" hp")
                    .append(Double.isNaN(r.error()) ? "" : String.format(Locale.ROOT, " (%+.0f%%)", r.error() * 100));
        }
        List<Off> off = off(rows);
        if (off.isEmpty()) b.append("\nno step kind is off by more than ").append(Math.round(TOLERANCE * 100)).append("% over ").append(MIN_SAMPLES).append("+ samples");
        else {
            b.append("\nwhere the model is wrong (over ").append(Math.round(TOLERANCE * 100)).append("% off, ").append(MIN_SAMPLES).append("+ samples):");
            for (Off o : off)
                b.append('\n').append(o.key()).append(": ").append(o.n()).append(" samples, predicted ").append(f(o.predictedS())).append(" s, took ").append(f(o.actualS()))
                        .append(" s (").append(String.format(Locale.ROOT, "%+.0f%%", o.error() * 100)).append(')');
        }
        if (bad > 0) b.append("\n").append(bad).append(" unreadable line(s) skipped");
        return b.toString();
    }
}
