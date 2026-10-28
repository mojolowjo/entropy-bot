package io.github.mojolowjo.entropybot.sense;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 0.39.z78 J6: the text of {@code aa cave list} - the known caves (Sightings, known by rays) nearest the owner first, each
 * with its entry, size, depth, the rays that credited it and its last walk; then the index volumes credited but not known
 * yet ("2 of 3 rays"); then the rays' counts. Was the heightmap candidates, the mouths and the label patterns (deleted
 * with them). Pure (JUnit: J6CaveIndexTest). Loader notes: none.
 */
public final class CaveList {
    private CaveList() {}

    public static String text(List<Sightings.KnownCave> known, Map<Integer, List<Long>> credited, String rays, double px, double py, double pz) {
        List<String> out = new ArrayList<>();
        List<Sightings.KnownCave> k = new ArrayList<>(known);
        k.sort((a, b) -> Double.compare(d(a.x(), a.y(), a.z(), px, py, pz), d(b.x(), b.y(), b.z(), px, py, pz)));
        out.add(k.isEmpty() ? "known caves: none yet (a cave is known when " + CaveIndex.KNOWN_RAYS + " sight rays credit it)" : "known caves: " + k.size());
        for (Sightings.KnownCave c : k)
            out.add(String.format(Locale.ROOT, "  %s: %.0f m, size %s, depth %s, %d rays%s", c.where(), d(c.x(), c.y(), c.z(), px, py, pz),
                    c.size().name().toLowerCase(Locale.ROOT), c.depth().name().toLowerCase(Locale.ROOT),
                    c.hits().size(), c.walked() == 0 ? "" : c.oreOnWalk() == Sightings.KnownCave.CLOSED ? ", closed for good" : ", walked (" + c.oreOnWalk() + " ore)"));
        if (!credited.isEmpty()) {
            out.add("credited, not known yet: " + credited.size());
            for (var e : credited.entrySet()) {
                int[] f = Sightings.cellOf(e.getValue().get(0));
                out.add("  volume #" + e.getKey() + ": " + e.getValue().size() + " of " + CaveIndex.KNOWN_RAYS + " rays (first at " + f[0] + " " + f[1] + " " + f[2] + ")");
            }
        }
        out.add(rays);
        return String.join("\n", out);
    }

    private static double d(int x, int y, int z, double px, double py, double pz) {
        double dx = x + 0.5 - px, dy = y - py, dz = z + 0.5 - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
