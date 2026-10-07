package io.github.mojolowjo.entropybot.vocab;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * V1b (VOCABULARY 6b): {@code explore [north|south|east|west] [minutes]} may leave the areas and prefers chunks never
 * seen (not in explored.json); {@code find cave|<poi kind>|<structure>|<biome> [minutes]} walks until it finds one and
 * notes it as a point of interest ({@code find <block>} stays the nearby list). Outside the areas a cluster of built
 * blocks, any container or bed or sign, or a named mob within 16 is someone's base: keep off, never take, note it as a
 * {@code safe} candidate. Pure.
 */
public final class ExploreWords {
    private ExploreWords() {}

    public static final int DEFAULT_MINUTES = 5, FIND_MINUTES = 10, MAX_MINUTES = 30, RINGS = 24;
    /** How far (chunks, either axis) from where it started it goes, so the walk home stays short (256 blocks). */
    public static final int MAX_FROM_START = 16;
    public static final List<String> DIRS = List.of("north", "south", "east", "west");

    /** dir: null = any way; minutes 1-30. error: the usage. */
    public record Args(String dir, int minutes, String error) {}

    public static Args parse(String rest, int defMinutes) {
        String dir = null;
        int min = defMinutes;
        for (String w : (rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT)).split("\\s+")) {
            if (w.isEmpty()) continue;
            if (DIRS.contains(w) && dir == null) dir = w;
            else if (w.matches("^\\d{1,3}m?$")) min = Math.max(1, Math.min(MAX_MINUTES, Integer.parseInt(w.replace("m", ""))));
            else return new Args(null, 0, "usage: explore [north|south|east|west] [minutes]");
        }
        return new Args(dir, min, null);
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
        for (int r = 1; r <= RINGS; r++) {
            int[] best = null;
            int bestAhead = Integer.MIN_VALUE;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int x = cx + dx, z = cz + dz;
                if (Math.max(Math.abs(x - sx), Math.abs(z - sz)) > MAX_FROM_START) continue;
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

    // ---- base detection ----

    public static final int BASE_BUILT = 6, BASE_R = 16;

    /** What a scan within 16 blocks saw: built blocks (the floor's list), block entities (containers, beds, signs), named mobs. */
    public record Scan(int built, int blockEntities, int named) {}

    /** Someone's base: at least 6 built blocks, or any block entity, or a named mob. */
    public static boolean isBase(Scan s) {
        return s.built() >= BASE_BUILT || s.blockEntities() > 0 || s.named() > 0;
    }

    /** The log line / whisper for a base found while exploring. */
    public static String baseNote(Scan s, int x, int y, int z) {
        return "someone's base near " + x + " " + y + " " + z + " (" + s.built() + " built blocks, " + s.blockEntities() + " containers/beds/signs, " + s.named()
                + " named mobs) - I keep 16 blocks off and take nothing; a safe-area candidate: area here 16 <name> safe";
    }

    // ---- find ----

    public enum FindKind { CAVE, POI, BIOME, BLOCK }

    public record Find(FindKind kind, String what, int minutes) {}

    /**
     * "find &lt;word&gt; [minutes]": cave; a point-of-interest kind or structure (village, mineshaft, trial chamber...);
     * a biome id (plains, minecraft:dark_forest); else a block (the old nearby list, no walking). POI kinds and biomes
     * are checked first.
     */
    public static Find find(String rest, List<String> poiKinds, Predicate<String> isBiome) {
        String t = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        int min = FIND_MINUTES;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s+(\\d{1,3})m?$").matcher(t);
        if (m.find()) {
            min = Math.max(1, Math.min(MAX_MINUTES, Integer.parseInt(m.group(1))));
            t = t.substring(0, m.start()).trim();
        }
        if (t.equals("cave") || t.equals("caves")) return new Find(FindKind.CAVE, "cave", min);
        String spaced = t.replace('_', ' ').replaceFirst("^minecraft:", "");
        for (String k : poiKinds) if (k.equals(spaced) || k.replace(' ', '_').equals(t)) return new Find(FindKind.POI, k, min);
        if (!t.isEmpty() && isBiome != null) {
            String id = t.indexOf(':') >= 0 ? t : "minecraft:" + t.replace(' ', '_');
            if (isBiome.test(id)) return new Find(FindKind.BIOME, id, min);
        }
        return new Find(FindKind.BLOCK, rest == null ? "" : rest.trim(), 0);
    }

    /** A cave under the bot: enough dark air below the surface in the scanned box. */
    public static final int CAVE_AIR = 40;

    public static boolean isCave(int darkAirBelowSurface) {
        return darkAirBelowSurface >= CAVE_AIR;
    }
}
