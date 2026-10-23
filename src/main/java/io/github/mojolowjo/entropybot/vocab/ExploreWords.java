package io.github.mojolowjo.entropybot.vocab;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * V1b (VOCABULARY 6b): {@code explore [north|south|east|west] [minutes]} goes anywhere but a keep-out area and prefers chunks never
 * seen (not in explored.json); {@code find cave|<poi kind>|<structure>|<biome> [minutes]} walks until it finds one and
 * notes it as a point of interest ({@code find <block>} stays the nearby list). Pure.
 */
public final class ExploreWords {
    private ExploreWords() {}

    public static int defaultMinutes() { return Tuning.i("exploreWords.defaultMinutes"); }
    public static int findMinutes() { return Tuning.i("exploreWords.findMinutes"); }
    public static int maxMinutes() { return Tuning.i("exploreWords.maxMinutes"); }
    public static int rings() { return Tuning.i("exploreWords.rings"); }
    /** How far (chunks, either axis) from where it started it goes, so the walk home stays short (256 blocks). */
    public static int maxFromStart() { return Tuning.i("exploreWords.maxFromStart"); }
    public static final List<String> DIRS = List.of("north", "south", "east", "west");

    /** dir: null = any way; minutes 1-30. error: the usage. */
    /** live-facts: {@code until}: a planner word whose source, once known, ends the walk (null: none). */
    public record Args(String dir, int minutes, String error, boolean gather, String until) {
        public Args(String dir, int minutes, String error, boolean gather) { this(dir, minutes, error, gather, null); }
        public Args(String dir, int minutes, String error) { this(dir, minutes, error, true); }
    }

    public static Args parse(String rest, int defMinutes) {
        String dir = null, until = null;
        int min = defMinutes;
        String[] g = gatherOff(rest);
        boolean gather = g[1] == null;
        String[] ws = g[0].toLowerCase(Locale.ROOT).split("\\s+");
        for (int i = 0; i < ws.length; i++) {
            String w = ws[i];
            if (w.isEmpty()) continue;
            if (DIRS.contains(w) && dir == null) dir = w;
            else if (w.matches("^\\d{1,3}m?$")) min = Math.max(1, Math.min(maxMinutes(), Integer.parseInt(w.replace("m", ""))));
            else if (w.equals("until") && i + 1 < ws.length && until == null) until = ws[++i];
            else return new Args(null, 0, "usage: explore [north|south|east|west] [minutes] [until <item>] [gather off]");
        }
        return new Args(dir, min, null, gather, until);
    }

    /** Chunk steps for a direction: north = -z, south = +z, west = -x, east = +x. */
    static int[] step(String dir) {
        return switch (dir) {
            case "north" -> new int[]{0, -1};
            case "south" -> new int[]{0, 1};
            case "west" -> new int[]{-1, 0};
            default -> new int[]{1, 0};
        };
    }

    /**
     * The next chunk {cx, cz} to walk to from chunk cx cz: the nearest one not seen (explored.apply(cx, cz) false) and not
     * refused, ring by ring; with a direction only chunks ahead of the start chunk (sx sz) within a 90 degree cone, the
     * one furthest along first on a ring tie. Null when none within {@link #RINGS}.
     */
    public static int[] next(int cx, int cz, int sx, int sz, String dir, BiPred seen, BiPred refused) {
        int[] st = dir == null ? null : step(dir);
        for (int r = 1; r <= rings(); r++) {
            int[] best = null;
            int bestAhead = Integer.MIN_VALUE;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int x = cx + dx, z = cz + dz;
                if (Math.max(Math.abs(x - sx), Math.abs(z - sz)) > maxFromStart()) continue;
                if (st != null) {
                    int ahead = (x - sx) * st[0] + (z - sz) * st[1], side = Math.abs((x - sx) * st[1]) + Math.abs((z - sz) * st[0]);
                    if (ahead <= 0 || side > ahead) continue;
                }
                if (seen.test(x, z) || refused.test(x, z)) continue;
                int ahead = st == null ? 0 : (x - sx) * st[0] + (z - sz) * st[1];
                if (best == null || ahead > bestAhead) {
                    best = new int[]{x, z};
                    bestAhead = ahead;
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    public interface BiPred { boolean test(int x, int z); }

    /** 0.23.1: a chunk counts as seen when explored.json has it or the map has a painted tile there (null: not known yet = seen). */
    public static boolean seen(boolean explored, Boolean painted) {
        return explored || painted == null || painted;
    }

    /** 0.23.1: the report's tail for what explore/find took on the way: "; took 12 oak_log, 4 coal" ("" for nothing). */
    public static String took(java.util.Map<String, Integer> took) {
        if (took == null || took.isEmpty()) return "";
        List<String> parts = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : took.entrySet()) parts.add(e.getValue() + " " + e.getKey().replaceFirst("^minecraft:", ""));
        return "; took " + String.join(", ", parts);
    }

    // ---- find ----

    public enum FindKind { CAVE, POI, BIOME, BLOCK }

    public record Find(FindKind kind, String what, int minutes, boolean gather) {
        public Find(FindKind kind, String what, int minutes) { this(kind, what, minutes, true); }
    }

    /** 0.23.1: {rest without "gather off", "off" or null}: "gather off" anywhere switches the gathering on the way off. */
    public static String[] gatherOff(String rest) {
        String t = rest == null ? "" : rest.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(^|\\s)gather\\s+off(\\s|$)").matcher(t);
        if (!m.find()) return new String[]{t, null};
        return new String[]{(t.substring(0, m.start()) + " " + t.substring(m.end())).trim(), "off"};
    }

    /**
     * "find &lt;word&gt; [minutes]": cave; a point-of-interest kind or structure (village, mineshaft, trial chamber...);
     * a biome id (plains, minecraft:dark_forest); else a block (the old nearby list, no walking). POI kinds and biomes
     * are checked first.
     */
    public static Find find(String rest, List<String> poiKinds, Predicate<String> isBiome) {
        String[] go = gatherOff(rest);
        boolean gather = go[1] == null;
        rest = go[0];
        String t = rest.trim().toLowerCase(Locale.ROOT);
        int min = findMinutes();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s+(\\d{1,3})m?$").matcher(t);
        if (m.find()) {
            min = Math.max(1, Math.min(maxMinutes(), Integer.parseInt(m.group(1))));
            t = t.substring(0, m.start()).trim();
        }
        if (t.equals("cave") || t.equals("caves")) return new Find(FindKind.CAVE, "cave", min, gather);
        String spaced = t.replace('_', ' ').replaceFirst("^minecraft:", "");
        for (String k : poiKinds) if (k.equals(spaced) || k.replace(' ', '_').equals(t)) return new Find(FindKind.POI, k, min, gather);
        if (!t.isEmpty() && isBiome != null) {
            String id = t.indexOf(':') >= 0 ? t : "minecraft:" + t.replace(' ', '_');
            if (isBiome.test(id)) return new Find(FindKind.BIOME, id, min, gather);
        }
        return new Find(FindKind.BLOCK, rest == null ? "" : rest.trim(), 0);
    }

    /** A cave under the bot: enough dark air below the surface in the scanned box. */
    public static int caveAir() { return Tuning.i("exploreWords.caveAir"); }

    public static boolean isCave(int darkAirBelowSurface) {
        return darkAirBelowSurface >= caveAir();
    }
}
