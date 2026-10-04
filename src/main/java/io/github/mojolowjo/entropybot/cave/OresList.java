package io.github.mojolowjo.entropybot.cave;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * "ores [name]" (the bridge's oresCommand, minus "prefer", which stays with the bridge's memory until B7e): the ores
 * left in place for players, nearest first; an ore mined since (where the world is loaded) leaves the list. Plain Java.
 */
public final class OresList {
    private OresList() {}

    public static final String CLEARED = "ok: forgot all the ores";

    /** The answer and the keys that turned out to be mined (to forget). */
    public record Answer(String text, List<String> gone) {}

    /**
     * @param ores   "x y z" -> {id, dim, seen}
     * @param mined  true when x y z is loaded and no ore any more
     * @param filter "" or a part of the id ("iron")
     */
    public static Answer list(Map<String, JsonObject> ores, String dim, int mx, int my, int mz, String filter, Predicate<int[]> mined) {
        String t = filter == null ? "" : filter.trim().toLowerCase();
        List<String> gone = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Object[]> list = new ArrayList<>();
        for (Map.Entry<String, JsonObject> e : ores.entrySet()) {
            JsonObject o = e.getValue();
            String odim = o.has("dim") ? o.get("dim").getAsString() : "minecraft:overworld";
            if (!odim.equals(dim)) continue;
            String[] p = e.getKey().trim().split("\\s+");
            if (p.length != 3) continue;
            int[] pos;
            try {
                pos = new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
            } catch (NumberFormatException ex) {
                continue;
            }
            if (mined.test(pos)) {
                gone.add(e.getKey());
                continue;
            }
            String id = o.get("id").getAsString();
            if (!t.isEmpty() && !id.contains(t)) continue;
            counts.merge(id, 1, Integer::sum);
            long dx = pos[0] - mx, dy = pos[1] - my, dz = pos[2] - mz;
            list.add(new Object[]{e.getKey(), id, dx * dx + dy * dy + dz * dz});
        }
        if (list.isEmpty()) return new Answer(!t.isEmpty() ? "no " + t + " listed" : "no ores listed - I list the ones I leave while clearing or strip mining", gone);
        list.sort((a, b) -> Long.compare((Long) a[2], (Long) b[2]));
        List<String> parts = new ArrayList<>(), near = new ArrayList<>();
        counts.forEach((id, n) -> parts.add(n + " " + id));
        for (int i = 0; i < Math.min(5, list.size()); i++) near.add(list.get(i)[1] + " at " + list.get(i)[0]);
        return new Answer(list.size() + " ores left for you: " + String.join(", ", parts) + " | nearest: " + String.join(", ", near), gone);
    }
}
