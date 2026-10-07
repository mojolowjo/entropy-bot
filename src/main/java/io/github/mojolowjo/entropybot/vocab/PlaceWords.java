package io.github.mojolowjo.entropybot.vocab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * V1b (VOCABULARY 1): places and markers. {@code place <name> [x y z] [north|south|east|west]} makes a place (was
 * {@code mark}); {@code place <block> x y z} still puts one block down (the word is an item that is a block, and three
 * numbers follow); {@code marker <name> [of <place>] [x y z]} a point under a place (default: the nearest place within
 * {@link #MARKER_RANGE}); {@code places} lists both; {@code go <place> [marker]} or {@code go <marker>} when its name is
 * unique. Markers live in the place's JSON as "markers": [{name, x, y, z}], at most {@link #MAX_MARKERS}. Pure.
 */
public final class PlaceWords {
    private PlaceWords() {}

    public static final int MARKER_RANGE = 32, MAX_MARKERS = 16;
    public static final String NAME_RE = "^[a-z0-9_-]{1,24}$";

    public enum Kind { BLOCK, PLACE, ERROR }

    /** BLOCK: put the block down (rest as given); PLACE: name, rest = the coordinates and direction words. */
    public record Parsed(Kind kind, String name, String rest, String error) {}

    public static Parsed parse(String rest, Predicate<String> isBlock) {
        List<String> w = words(rest);
        if (w.isEmpty()) return new Parsed(Kind.ERROR, null, null, "usage: place <name> [x y z] [north|south|east|west] | place <block> x y z");
        if (w.size() == 4 && ints(w.subList(1, 4)) && isBlock != null && isBlock.test(w.get(0))) return new Parsed(Kind.BLOCK, w.get(0), String.join(" ", w), null);
        String name = w.get(0).toLowerCase(Locale.ROOT);
        if (!name.matches(NAME_RE)) return new Parsed(Kind.ERROR, null, null, "error: a place name is 1-24 letters, digits, _ or - (place farm)");
        return new Parsed(Kind.PLACE, name, String.join(" ", w.subList(1, w.size())), null);
    }

    /** A marker: name, its place (null = the nearest), and the coordinates text ("" = here). */
    public record Marker(String name, String place, String coords, String error) {}

    public static Marker parseMarker(String rest) {
        List<String> w = words(rest == null ? "" : rest.toLowerCase(Locale.ROOT));
        if (w.isEmpty()) return new Marker(null, null, null, "usage: marker <name> [of <place>] [x y z]");
        String name = w.get(0), place = null;
        if (!name.matches(NAME_RE)) return new Marker(null, null, null, "error: a marker name is 1-24 letters, digits, _ or -");
        int i = 1;
        if (i < w.size() && w.get(i).equals("of")) {
            if (i + 1 >= w.size()) return new Marker(null, null, null, "usage: marker <name> of <place> [x y z]");
            place = w.get(i + 1);
            i += 2;
        }
        List<String> rest2 = w.subList(i, w.size());
        if (!rest2.isEmpty() && !(rest2.size() == 3 && ints(rest2))) return new Marker(null, null, null, "usage: marker <name> [of <place>] [x y z]");
        return new Marker(name, place, String.join(" ", rest2), null);
    }

    /** A place as the rules see it: name, dimension, x y z, its markers (each {name, x, y, z}). */
    public record Place(String name, String dim, int x, int y, int z, List<Spot> markers) {}

    public record Spot(String name, int x, int y, int z) {}

    /** The nearest place within range of x y z in that dimension, or null. */
    public static Place nearest(List<Place> places, String dim, int x, int y, int z, int range) {
        Place best = null;
        long bestD = (long) range * range + 1;
        for (Place p : places) {
            if (!p.dim().equals(dim)) continue;
            long dx = p.x() - x, dy = p.y() - y, dz = p.z() - z, d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    /** Where "go &lt;a&gt; [b]" leads: {place, marker or null}, or an error in err[0]. */
    public static Spot resolveGo(List<Place> places, String a, String b, String[] err) {
        String x = a.toLowerCase(Locale.ROOT);
        for (Place p : places) {
            if (!p.name().equals(x)) continue;
            if (b == null || b.isEmpty()) return new Spot(p.name(), p.x(), p.y(), p.z());
            for (Spot m : p.markers()) if (m.name().equalsIgnoreCase(b)) return new Spot(p.name() + " " + m.name(), m.x(), m.y(), m.z());
            err[0] = "error: " + p.name() + " has no marker " + b + " (places lists them)";
            return null;
        }
        List<Spot> hits = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        for (Place p : places) for (Spot m : p.markers()) if (m.name().equalsIgnoreCase(x)) { hits.add(new Spot(p.name() + " " + m.name(), m.x(), m.y(), m.z())); owners.add(p.name()); }
        if (hits.size() == 1) return hits.get(0);
        if (hits.size() > 1) { err[0] = "error: " + x + " is a marker of " + String.join(" and ", owners) + " - say go <place> " + x; return null; }
        err[0] = "I have no place or marker called " + x + " (places lists them)";
        return null;
    }

    /** "places": "base 1 64 2 [furnace 3 64 2, door 0 64 5] | farm ...". */
    public static String list(List<Place> places) {
        if (places.isEmpty()) return "no places yet - place base where you want my base";
        List<String> out = new ArrayList<>();
        for (Place p : places) {
            StringBuilder sb = new StringBuilder(p.name() + " " + p.x() + " " + p.y() + " " + p.z());
            if (!p.markers().isEmpty()) {
                List<String> ms = new ArrayList<>();
                for (Spot m : p.markers()) ms.add(m.name() + " " + m.x() + " " + m.y() + " " + m.z());
                sb.append(" [").append(String.join(", ", ms)).append("]");
            }
            out.add(sb.toString());
        }
        return String.join(" | ", out);
    }

    /** The marker list after adding (or moving) one; an error in err[0] when the place is full. */
    public static List<Spot> withMarker(List<Spot> now, Spot m, String[] err) {
        List<Spot> out = new ArrayList<>();
        boolean moved = false;
        for (Spot s : now) {
            if (s.name().equalsIgnoreCase(m.name())) { out.add(m); moved = true; } else out.add(s);
        }
        if (!moved) {
            if (now.size() >= MAX_MARKERS) { err[0] = "error: I keep at most " + MAX_MARKERS + " markers a place - places forget <marker> first"; return null; }
            out.add(m);
        }
        return out;
    }

    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String t : (s == null ? "" : s.trim()).split("\\s+")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    static boolean ints(List<String> l) {
        for (String s : l) if (!s.matches("^-?\\d+$")) return false;
        return !l.isEmpty();
    }

    /** Unused directions map kept for callers: north/south/east/west. */
    public static boolean isDir(String w) {
        return Map.of("north", 1, "south", 1, "east", 1, "west", 1).containsKey(w == null ? "" : w.toLowerCase(Locale.ROOT));
    }
}
