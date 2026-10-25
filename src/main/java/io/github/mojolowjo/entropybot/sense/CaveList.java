package io.github.mojolowjo.entropybot.sense;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 0.39.z52 (owner's labelling walk): {@code aa cave list} - the rule line, then every candidate and mouth the bot knows,
 * nearest to the owner first: coordinates, verdict (candidate / dropped: reason / mouth), the shape numbers, and whether
 * the owner has labelled it (an owner patch within {@code cave.mergeM}, horizontal). Pure; the command gathers the entries.
 */
public final class CaveList {
    private CaveList() {}

    public record Entry(int x, int y, int z, String verdict, String numbers) {}

    /** A heightmap candidate; {@code dropReason} null = not dropped (still a candidate). */
    public static Entry candidate(int x, int y, int z, String numbers, String dropReason) {
        return new Entry(x, y, z, dropReason == null ? "candidate" : "dropped: " + dropReason, numbers == null || numbers.isEmpty() ? "no shape numbers" : numbers);
    }

    /** 0.39.z54 A4: a candidate whose hole passed but stays a candidate (the darkest sky alone): the reason. */
    public static Entry held(int x, int y, int z, String numbers, String reason) {
        return new Entry(x, y, z, "candidate: " + reason, numbers == null || numbers.isEmpty() ? "no shape numbers" : numbers);
    }

    /** A confirmed mouth (its stored fact). */
    public static Entry mouth(int x, int y, int z, int pocketCells, int fx, int fy, int fz, boolean walked) {
        return new Entry(x, y, z, "mouth" + (walked ? " (pocket walked)" : ""), pocketCells + " pocket cells, floor " + fx + " " + fy + " " + fz);
    }

    public static String text(String ruleLine, List<Entry> entries, List<CavePatterns.Patch> patterns, double px, double py, double pz) {
        StringBuilder b = new StringBuilder(ruleLine);
        if (entries.isEmpty()) return b.append("\nno candidate or mouth known (none in the loaded chunks, none confirmed)").toString();
        List<Entry> l = new ArrayList<>(entries);
        l.sort(Comparator.comparingDouble(e -> d2(e, px, py, pz)));
        b.append("\n").append(l.size()).append(" known, nearest first (label one: fly there, aa cave yes|no|hill|cliff)");
        double m = io.github.mojolowjo.entropybot.aa.brain.Tuning.get("cave.mergeM");
        for (Entry e : l) {
            String lab = "not labelled";
            for (CavePatterns.Patch p : patterns) {
                if (!p.owner()) continue;
                double dx = p.x() - e.x(), dz = p.z() - e.z();
                if (dx * dx + dz * dz <= m * m) { lab = "labelled " + p.label() + " by owner"; break; }
            }
            b.append("\n").append(e.x()).append(' ').append(e.y()).append(' ').append(e.z()).append(" (").append(Math.round(Math.sqrt(d2(e, px, py, pz))))
                    .append(" m): ").append(e.verdict()).append("; ").append(e.numbers()).append("; ").append(lab);
        }
        return b.toString();
    }

    private static double d2(Entry e, double x, double y, double z) {
        double dx = e.x() + 0.5 - x, dy = e.y() + 0.5 - y, dz = e.z() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
