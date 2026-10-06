package io.github.mojolowjo.entropybot.scout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C8 "scout &lt;north|south|east|west|x z&gt; [n] [&lt;min&gt;m] [from me]" and "find nearest &lt;poi kind|ore&gt;", game-free:
 * the parser, the leg planner (straight legs inside the areas), the compass helper, the report composer and the
 * nearest lookup. Mining runs the walk (a Seq job, like explore); the POI engine and the ores list supply the notes.
 * Loader-free (plain Java), so it moves to another loader unchanged.
 */
public final class ScoutRules {
    private ScoutRules() {}

    public static final int DEFAULT_BLOCKS = 64, MAX_BLOCKS = 256, LEG = 32, DEFAULT_MINUTES = 5, MAX_MINUTES = 30;
    /** What counts as "passed": POIs within this many blocks (x z) of the path, ores within ORE_NEAR. */
    public static final int POI_NEAR = 32, ORE_NEAR = 24;
    public static final String USAGE = "usage: scout <north|south|east|west|x z> [blocks, default 64, max 256] [<min>m] [from me] | scout status";

    public enum Kind { RUN, STATUS, ERROR }

    /** A parsed command: dx dz (unit for a direction, else the target point with point=true). */
    public record Parsed(Kind kind, String dirWord, int dx, int dz, boolean point, int blocks, int minutes, boolean fromMe, String error) {
        static Parsed err(String e) { return new Parsed(Kind.ERROR, null, 0, 0, false, 0, 0, false, e); }
    }

    private static final Pattern MIN = Pattern.compile("^(\\d+)m(in)?$");

    public static Parsed parse(String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return Parsed.err(USAGE);
        if (t.equals("status")) return new Parsed(Kind.STATUS, null, 0, 0, false, 0, 0, false, null);
        boolean fromMe = false;
        if (t.endsWith(" from me")) { fromMe = true; t = t.substring(0, t.length() - 8).trim(); }
        List<String> w = new ArrayList<>(List.of(t.split("\\s+")));
        int minutes = DEFAULT_MINUTES;
        for (int i = w.size() - 1; i >= 0; i--) {
            Matcher m = MIN.matcher(w.get(i));
            if (m.matches()) {
                minutes = Math.min(Math.max(Integer.parseInt(m.group(1)), 1), MAX_MINUTES);
                w.remove(i);
            }
        }
        if (w.isEmpty()) return Parsed.err(USAGE);
        int[] d = unit(w.get(0));
        if (d != null) {
            int n = DEFAULT_BLOCKS;
            if (w.size() > 2) return Parsed.err(USAGE);
            if (w.size() == 2) {
                Integer v = num(w.get(1));
                if (v == null || v < 1) return Parsed.err("error: \"" + w.get(1) + "\" isn't a number of blocks - " + USAGE);
                n = v;
            }
            return new Parsed(Kind.RUN, w.get(0), d[0], d[1], false, Math.min(n, MAX_BLOCKS), minutes, fromMe, null);
        }
        if (w.size() < 2 || w.size() > 3) return Parsed.err(USAGE);
        Integer x = num(w.get(0)), z = num(w.get(1));
        if (x == null || z == null) return Parsed.err("error: I don't know the direction \"" + w.get(0) + "\" - " + USAGE);
        int n = MAX_BLOCKS;
        if (w.size() == 3) {
            Integer v = num(w.get(2));
            if (v == null || v < 1) return Parsed.err("error: \"" + w.get(2) + "\" isn't a number of blocks - " + USAGE);
            n = Math.min(v, MAX_BLOCKS);
        }
        return new Parsed(Kind.RUN, null, x, z, true, n, minutes, fromMe, null);
    }

    private static Integer num(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    /** north = -z, south = +z, east = +x, west = -x (also n/s/e/w); null otherwise. */
    public static int[] unit(String word) {
        return switch (word) {
            case "north", "n" -> new int[]{0, -1};
            case "south", "s" -> new int[]{0, 1};
            case "east", "e" -> new int[]{1, 0};
            case "west", "w" -> new int[]{-1, 0};
            default -> null;
        };
    }

    /** The 8-point compass word from a block offset (north = -z); "here" for 0 0. */
    public static String compass(int dx, int dz) {
        if (dx == 0 && dz == 0) return "here";
        double a = Math.toDegrees(Math.atan2(dx, -dz));          // 0 = north, 90 = east
        if (a < 0) a += 360;
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return names[(int) Math.round(a / 45.0) % 8];
    }

    /** The legs' end points and where the plan was cut (outside the areas or the guard), or null. */
    public record Plan(List<int[]> legs, int[] cutAt, String cutWhy, int blocks, String dirText) {}

    /**
     * Straight legs of at most {@link #LEG} from sx sz toward the direction (or the point), at most {@code blocks} long;
     * a leg end outside the areas (or refused) cuts the plan at the last good end. allowed(x, z): inside and walkable.
     */
    public static Plan legs(int sx, int sz, Parsed p, BiPredicate<Integer, Integer> allowed) {
        double ux, uz;
        int total;
        String dirText;
        if (p.point()) {
            int ddx = p.dx() - sx, ddz = p.dz() - sz;
            double len = Math.sqrt((double) ddx * ddx + (double) ddz * ddz);
            if (len < 1) return new Plan(List.of(), null, null, 0, "here");
            ux = ddx / len;
            uz = ddz / len;
            total = (int) Math.min(Math.round(len), p.blocks());
            dirText = "toward " + p.dx() + " " + p.dz();
        } else {
            ux = p.dx();
            uz = p.dz();
            total = p.blocks();
            dirText = p.dirWord().length() == 1 ? word(p.dirWord()) : p.dirWord();
        }
        List<int[]> out = new ArrayList<>();
        int[] cut = null;
        for (int d = Math.min(LEG, total); ; d = Math.min(d + LEG, total)) {
            int x = sx + (int) Math.round(ux * d), z = sz + (int) Math.round(uz * d);
            if (!allowed.test(x, z)) {
                cut = new int[]{x, z};
                break;
            }
            out.add(new int[]{x, z});
            if (d >= total) break;
        }
        return new Plan(out, cut, cut == null ? null : "outside my areas", total, dirText);
    }

    private static String word(String w) {
        return switch (w) { case "n" -> "north"; case "s" -> "south"; case "e" -> "east"; default -> "west"; };
    }

    /** A point of interest or an ore for the report and the nearest lookup. */
    public record Spot(String kind, int x, int y, int z, int poiId) {}

    /** Horizontal distance from x z to the segment a-b. */
    public static double distToPath(int x, int z, int ax, int az, int bx, int bz) {
        double vx = bx - ax, vz = bz - az, wx = x - ax, wz = z - az;
        double l2 = vx * vx + vz * vz;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, (wx * vx + wz * vz) / l2));
        double px = ax + t * vx - x, pz = az + t * vz - z;
        return Math.sqrt(px * px + pz * pz);
    }

    /**
     * The report: "scouted 64 north: cave at x y z (48 blocks), 3 iron_ore near x y z, village at ..., 2 zombie;
     * no path past x z (why)". start: x y z; reached: the furthest x z; pois/ores: everything known (filtered to the
     * path here); mobs: entity type path -> count; blocked: "x z (why)" or null.
     */
    public static String report(Plan plan, int[] start, int[] reached, List<Spot> pois, List<Spot> ores, Map<String, Integer> mobs,
                                String blocked, String why) {
        int gone = (int) Math.round(Math.sqrt(Math.pow(reached[0] - start[0], 2) + Math.pow(reached[1] - start[2], 2)));
        StringBuilder sb = new StringBuilder("scouted " + gone + " " + plan.dirText());
        if (why != null) sb.append(" (").append(why).append(")");
        List<String> parts = new ArrayList<>();
        List<Spot> near = new ArrayList<>();
        for (Spot s : pois) if (distToPath(s.x(), s.z(), start[0], start[2], reached[0], reached[1]) <= POI_NEAR) near.add(s);
        near.sort(Comparator.comparingDouble(s -> dist(s, start)));
        for (int i = 0; i < Math.min(6, near.size()); i++) {
            Spot s = near.get(i);
            parts.add(s.kind() + " at " + s.x() + " " + s.y() + " " + s.z() + " (" + Math.round(dist(s, start)) + " blocks"
                    + (s.poiId() >= 0 ? ", poi " + s.poiId() : "") + ")");
        }
        if (near.size() > 6) parts.add("+" + (near.size() - 6) + " more places (\"poi\")");
        Map<String, List<Spot>> byOre = new LinkedHashMap<>();
        List<Spot> oreNear = new ArrayList<>();
        for (Spot s : ores) if (distToPath(s.x(), s.z(), start[0], start[2], reached[0], reached[1]) <= ORE_NEAR) oreNear.add(s);
        oreNear.sort(Comparator.comparingDouble(s -> dist(s, start)));
        for (Spot s : oreNear) byOre.computeIfAbsent(s.kind(), k -> new ArrayList<>()).add(s);
        byOre.forEach((id, l) -> parts.add(l.size() + " " + id + " near " + l.get(0).x() + " " + l.get(0).y() + " " + l.get(0).z()));
        List<String> mobParts = new ArrayList<>();
        mobs.forEach((k, n) -> mobParts.add(n + " " + k));
        if (!mobParts.isEmpty()) parts.add(String.join(", ", mobParts));
        sb.append(": ").append(parts.isEmpty() ? "nothing to note" : String.join(", ", parts));
        if (blocked != null) sb.append("; no path past ").append(blocked);
        else if (plan.cutAt() != null) sb.append("; my areas end at ").append(plan.cutAt()[0]).append(" ").append(plan.cutAt()[1]);
        return sb.toString();
    }

    private static double dist(Spot s, int[] from) {
        double dx = s.x() - from[0], dz = s.z() - from[2];
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** "find nearest &lt;thing&gt;": the nearest POI whose kind contains it, or ore whose id contains it. */
    public static String nearest(String query, List<Spot> pois, List<Spot> ores, int[] me) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return "usage: find nearest <poi kind|ore> (spawner, village, cave, lava, iron...)";
        Spot best = null;
        double bd = Double.MAX_VALUE;
        for (List<Spot> l : List.of(pois, ores)) {
            for (Spot s : l) {
                if (!s.kind().toLowerCase(Locale.ROOT).contains(q)) continue;
                double dx = s.x() - me[0], dy = s.y() - me[1], dz = s.z() - me[2];
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d < bd) { bd = d; best = s; }
            }
        }
        if (best == null) return "I know no " + q + " - none in my points of interest (\"poi\") or the ores list (\"ores\")";
        return "nearest " + q + ": " + best.kind() + (best.poiId() >= 0 ? " (poi " + best.poiId() + ")" : "") + " at " + best.x() + " " + best.y()
                + " " + best.z() + ", " + Math.round(bd) + " blocks " + compass(best.x() - me[0], best.z() - me[2]);
    }
}
