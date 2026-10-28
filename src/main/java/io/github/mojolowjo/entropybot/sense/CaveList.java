package io.github.mojolowjo.entropybot.sense;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 0.39.z78 J6: the text of {@code aa cave list} - the known caves (Sightings, known by rays) nearest the owner first, each
 * with its entry, size, depth, the rays that credited it and its last walk; then the index volumes credited but not known
 * yet ("2 of 3 rays"); then the index's truth near the bot (run EE, the manager's ruling: every cave volume within
 * cave.exploreTowardM that passes the floor - its opening cell, size, depth, rim and air credits, known - then the dips
 * and the volumes with no opening counted; {@link CaveIndex#truth}); then the index's counts and the rays' counts. Rule
 * 17: the truth part is the owner's debug read only; nothing in the planner reads it. Was the heightmap candidates, the
 * mouths and the label patterns (deleted with them). Pure (JUnit: J6CaveIndexTest). Loader notes: none.
 */
public final class CaveList {
    private CaveList() {}

    public static String text(List<Sightings.KnownCave> known, Map<Integer, List<Long>> credited, String rays, double px, double py, double pz) {
        return text(known, credited, null, 0, null, rays, px, py, pz);
    }

    /** J6b: as below, each known cave with its record's line ({@code record}: the store's facts; null: none). */
    public static String text(List<Sightings.KnownCave> known, java.util.function.Function<Sightings.KnownCave, CaveRecord.Facts> record, Map<Integer, List<Long>> credited,
                              CaveIndex.TruthList truth, int within, String counts, String rays, double px, double py, double pz) {
        String t = text(known, credited, truth, within, counts, rays, px, py, pz);
        if (record == null) return t;
        StringBuilder b = new StringBuilder();
        for (String line : t.split("\n", -1)) {
            b.append(line).append('\n');
            for (Sightings.KnownCave c : known)
                if (line.startsWith("  " + c.where() + ": ")) {
                    CaveRecord.Facts f = record.apply(c);
                    if (f != null) b.append("    record: ").append(f.line()).append('\n');
                }
        }
        return b.substring(0, b.length() - 1);
    }

    /** As above, with the index's truth within {@code within} blocks ({@code truth} null: left out) and its counts line. */
    public static String text(List<Sightings.KnownCave> known, Map<Integer, List<Long>> credited, CaveIndex.TruthList truth, int within, String counts,
                              String rays, double px, double py, double pz) {
        List<String> out = new ArrayList<>();
        List<Sightings.KnownCave> k = new ArrayList<>(known);
        k.sort((a, b) -> Double.compare(d(a.x(), a.y(), a.z(), px, py, pz), d(b.x(), b.y(), b.z(), px, py, pz)));
        out.add(k.isEmpty() ? "known caves: none yet (a cave is known when " + CaveIndex.KNOWN_RAYS + " sight rays credit it)" : "known caves: " + k.size());
        for (Sightings.KnownCave c : k)
            out.add(String.format(Locale.ROOT, "  %s: %.0f m, size %s, depth %s, %d rays%s", c.where(), d(c.x(), c.y(), c.z(), px, py, pz),
                    c.size().name().toLowerCase(Locale.ROOT), c.depth().name().toLowerCase(Locale.ROOT),
                    c.hits().size(), ""));
        if (!credited.isEmpty()) {
            out.add("credited, not known yet: " + credited.size());
            for (var e : credited.entrySet()) {
                int[] f = Sightings.cellOf(e.getValue().get(0));
                out.add("  volume #" + e.getKey() + ": " + e.getValue().size() + " of " + CaveIndex.KNOWN_RAYS + " rays (first at " + f[0] + " " + f[1] + " " + f[2] + ")");
            }
        }
        if (truth != null) {
            out.add("index caves within " + within + " (the index's truth, a debug read; never a way or a target): " + (truth.caves().isEmpty() ? "none" : truth.caves().size()));
            for (CaveIndex.Truth c : truth.caves())
                out.add(String.format(Locale.ROOT, "  volume at %d %d %d: %.0f m, size %s, depth %s, rim %d, air %d, %s", c.x(), c.y(), c.z(), d(c.x(), c.y(), c.z(), px, py, pz),
                        c.size().name().toLowerCase(Locale.ROOT), c.depth().name().toLowerCase(Locale.ROOT), c.rim(), c.air(), c.known() ? "known" : "not known"));
            out.add("  dips " + truth.dips() + ", no opening " + truth.noOpening());
        }
        if (counts != null) out.add(counts);
        out.add(rays);
        return String.join("\n", out);
    }

    /**
     * {@code aa cave list all}: EVERY index volume that passes the floor, nearest first, no radius - "x y z: N m W, size,
     * depth, rim credits R, air credits A, known / not known, labelled yes / no / -" ({@code labelled}: a volume root to the
     * owner's verdict on it); then the dips and the volumes with no opening. Rule 17: the owner's debug read only; nothing
     * in the planner reads it.
     */
    public static String allText(CaveIndex.TruthList truth, Map<Integer, Boolean> labelled, double px, double py, double pz) {
        List<String> out = new ArrayList<>();
        out.add("index caves, every chunk held (the index's truth, a debug read; never a way or a target): " + (truth.caves().isEmpty() ? "none" : truth.caves().size()));
        for (CaveIndex.Truth c : truth.caves()) {
            Boolean v = labelled.get(c.root());
            out.add(String.format(Locale.ROOT, "  %d %d %d: %.0f m %s, size %s, depth %s, rim credits %d, air credits %d, %s, labelled %s", c.x(), c.y(), c.z(),
                    d(c.x(), c.y(), c.z(), px, py, pz), compass(c.x() + 0.5 - px, c.z() + 0.5 - pz), c.size().name().toLowerCase(Locale.ROOT),
                    c.depth().name().toLowerCase(Locale.ROOT), c.rim(), c.air(), c.known() ? "known" : "not known", v == null ? "-" : v ? "yes" : "no"));
        }
        out.add("  dips " + truth.dips() + ", no opening " + truth.noOpening());
        return String.join("\n", out);
    }

    /** The 8-point compass toward dx dz (+x east, +z south: Minecraft's axes); "here" when on the spot. */
    static String compass(double dx, double dz) {
        if (Math.abs(dx) < 0.5 && Math.abs(dz) < 0.5) return "here";     // structural: half a block, the same column
        String[] names = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
        int i = (int) Math.floorMod(Math.round(Math.toDegrees(Math.atan2(dz, dx)) / 45.0), 8L);   // structural: 8 points of 45 degrees
        return names[i];
    }

    private static double d(int x, int y, int z, double px, double py, double pz) {
        double dx = x + 0.5 - px, dy = y - py, dz = z + 0.5 - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
