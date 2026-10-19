package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BRAIN_PLAN 2.12 (B7): building from a schematic ({@code aa build <name> here|at x y z [high|low]}). Pure: the words,
 * which blocks are skipped, the material list against the bag, the layer progress, the next step and the goals line.
 *
 * <p>Research (verified in ..\baritone-src, branch baritone-policy-0.36): Baritone's {@code BuilderProcess} reads
 * .litematic, .schem (Sponge) and .schematic (MCEdit) through {@code ISchematicSystem.getByFile}, builds bottom-up one
 * layer at a time with {@code buildInLayers} (layerOrder false), only places where a neighbour exists (so hanging blocks
 * wait for their support) and pauses with "Missing materials" when nothing it carries fits. So the order is Baritone's;
 * AA plans what Baritone does not: the material list, gathering what the current layer lacks, progress, and the stop
 * reason. Vanilla structure .nbt files are not read (Baritone has no format for them).
 *
 * <p>The adapter ({@code commands.SchematicBuild}) loads the file, counts each cell's item and scans the world every 10 s
 * into {@link Build#scan}; the desire's chain is {@link #chain}.
 */
public final class Blueprint {
    private Blueprint() {}

    public static final String FACT = "aa.builds";
    /** Largest schematic AA takes (cells, air included): the world scan runs on the client tick. */
    public static final int MAX_CELLS = 128_000, MAX_FAILS = 3;

    /** Blocks no item places (or that Baritone cannot place): left out of the list and the progress, said once. */
    static final Set<String> SKIP = Set.of("air", "cave_air", "void_air", "water", "lava", "bubble_column", "fire", "soul_fire",
            "piston_head", "moving_piston", "nether_portal", "end_portal", "end_gateway", "light", "structure_void", "barrier", "frosted_ice");

    /** True for a block id ("minecraft:water", "water") AA leaves out. */
    public static boolean skipped(String blockId) {
        if (blockId == null) return true;
        return SKIP.contains(blockId.startsWith("minecraft:") ? blockId.substring(10) : blockId.contains(":") ? "" : blockId);
    }

    /** The bag's key for an item id: the path for minecraft items (as Inputs.bag), else the path too (the bag has no namespace). */
    public static String bagKey(String itemId) {
        return itemId == null ? "" : itemId.replaceFirst("^.*:", "");
    }

    /** {@code aa build} words: name, origin (null = here), here flag, priority. */
    public record Args(String name, int[] at, boolean here, int prio, String err) {}

    public static final String USAGE = "usage: aa build <name> here|at <x> <y> <z> [high|low] (the file in entropybot/builds: .litematic, .schem or .schematic)";

    public static Args parse(String arg) {
        String[] t = (arg == null ? "" : arg.trim().toLowerCase(Locale.ROOT)).split("\\s+");
        String name = null;
        int[] at = null;
        boolean here = false;
        int prio = 0;
        for (int i = 0; i < t.length; i++) {
            String w = t[i];
            if (w.isEmpty()) continue;
            if (w.equals("here")) here = true;
            else if (w.equals("high")) prio = 1;
            else if (w.equals("low")) prio = -1;
            else if (w.equals("at")) {
                if (i + 3 >= t.length || !t[i + 1].matches("-?\\d{1,8}") || !t[i + 2].matches("-?\\d{1,4}") || !t[i + 3].matches("-?\\d{1,8}"))
                    return new Args(null, null, false, 0, USAGE);
                at = new int[]{Integer.parseInt(t[i + 1]), Integer.parseInt(t[i + 2]), Integer.parseInt(t[i + 3])};
                i += 3;
            } else if (name == null && w.matches("[a-z0-9_][a-z0-9_\\-]{0,63}(\\.(litematic|schem|schematic))?")) name = w.replaceFirst("\\.(litematic|schem|schematic)$", "");
            else return new Args(null, null, false, 0, USAGE);
        }
        if (name == null) return new Args(null, null, false, 0, USAGE);
        if (at == null && !here) return new Args(null, null, false, 0, "aa build " + name + ": where? here (its corner at my feet) or at <x> <y> <z>");
        if (at != null && here) return new Args(null, null, false, 0, "aa build: here or at, not both");
        return new Args(name, at, here, prio, null);
    }

    /** The world scan: per item what is still to place (whole build, current layer), and the layer counts. */
    public static final class Scan {
        public final Map<String, Integer> left, layerLeft;
        public final int placed, total, occupied, layer, layers;

        public Scan(Map<String, Integer> left, Map<String, Integer> layerLeft, int placed, int total, int occupied, int layer, int layers) {
            this.left = left;
            this.layerLeft = layerLeft;
            this.placed = placed;
            this.total = total;
            this.occupied = occupied;
            this.layer = layer;
            this.layers = layers;
        }

        /** Every cell placed or held by another block (Baritone never breaks those: placing only). */
        public boolean done() { return placed + occupied >= total; }
    }

    /** One build goal: the file, where, the full material list (item -> count) and the last scan. */
    public static final class Build {
        public final String name, file;
        /** The dimension it stands in (the adapter sets it; null = any). */
        public String dim;
        /** Items the planner found no way to get (review 1): skipped by the chain, Baritone builds the rest. */
        public final Set<String> unresolved = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** Build runs in a row that placed nothing (review 3): parked at {@link #MAX_FAILS}. */
        public volatile int fails;
        public final int[] origin;
        public final int prio;
        public final long given;
        public final Map<String, Integer> need;
        public final List<String> skippedNote;
        public volatile Scan scan;
        /** The last stop text of the build step (Baritone's "missing" pause, a stall), said in aa goals. */
        public volatile String lastStop;

        public Build(String name, String file, int[] origin, int prio, long given, Map<String, Integer> need, List<String> skippedNote) {
            this.name = name;
            this.file = file;
            this.origin = origin;
            this.prio = prio;
            this.given = given;
            this.need = need;
            this.skippedNote = skippedNote;
        }

        public String id() { return "build." + name; }
    }

    /** Index of the first layer (bottom up) with cells left, or layers when all are done. */
    public static int currentLayer(int[] total, int[] done) {
        for (int i = 0; i < total.length; i++) if (done[i] < total[i]) return i;
        return total.length;
    }

    /** What the bag lacks for {@code left} (item -> shortfall), largest first. */
    public static Map<String, Integer> missing(Map<String, Integer> left, Map<String, Integer> bag) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>();
        for (Map.Entry<String, Integer> e : left.entrySet()) {
            int s = e.getValue() - bag.getOrDefault(bagKey(e.getKey()), 0);
            if (s > 0) l.add(Map.entry(e.getKey(), s));
        }
        l.sort((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : l) out.put(e.getKey(), e.getValue());
        return out;
    }

    @SuppressWarnings("unchecked")
    public static List<Build> of(Blackboard bb) {
        if (bb.fact(FACT) instanceof List<?> l) return (List<Build>) l;
        List<Build> l = new ArrayList<>();
        bb.fact(FACT, l);
        return l;
    }

    public static Build add(Blackboard bb, Build b) {
        of(bb).removeIf(x -> x.name.equals(b.name));
        of(bb).add(b);
        return b;
    }

    /** Parked: another dimension, or not scanned yet (the adapter scans every 10 s). */
    public static String blocked(Blackboard bb, Build b) {
        if (b.dim != null && bb.in.dimension != null && !b.dim.equals(bb.in.dimension)) return b.name + " is in " + b.dim + "; I am not";
        if (b.fails >= MAX_FAILS) return "placed nothing in " + b.fails + " runs" + (b.lastStop != null ? " (" + b.lastStop + ")" : "") + "; aa build " + b.name + " again to retry";
        return b.scan == null ? "not scanned yet" : null;
    }

    public static double score(Blackboard bb, Build b) {
        if (b.scan != null && b.scan.done()) return -1;
        return Math.max(1, Personality.of(bb).get("ownerBase") + b.prio * Goals.HIGH);
    }

    /**
     * The next step: null before the first scan or when done; the current layer lacks an item that the bag has none of
     * -> {@code goal <item> <bag + whole-build shortfall>} (one trip per item); else the build step itself (Baritone
     * places what it carries and the next round gathers the rest).
     */
    public static String chain(Blackboard bb, Build b) {
        Scan s = b.scan;
        if (s == null || s.done()) return null;
        Map<String, Integer> bag = bb.in.bag;
        for (Map.Entry<String, Integer> e : missing(s.layerLeft, bag).entrySet()) {
            String k = bagKey(e.getKey());
            if (bag.getOrDefault(k, 0) > 0 || b.unresolved.contains(k)) continue;   // in the bag: build with it first; no plan: skip
            return "goal " + k + " " + Math.max(e.getValue(), s.left.getOrDefault(e.getKey(), 0));   // none in the bag: the whole build's need
        }
        return "build schematic " + b.name + " " + b.origin[0] + " " + b.origin[1] + " " + b.origin[2];
    }

    /** "house: layer 4/12, 230/400 placed, missing 30 spruce_planks, 12 held by other blocks - next: ...". */
    public static String line(Blackboard bb, Build b, String scoreText) {
        Scan s = b.scan;
        StringBuilder sb = new StringBuilder(b.name);
        if (b.prio != 0) sb.append(b.prio > 0 ? " high" : " low");
        sb.append(": ");
        if (s == null) return sb.append("not scanned yet (").append(scoreText).append(")").toString();
        sb.append("layer ").append(Math.min(s.layer + 1, s.layers)).append("/").append(s.layers).append(", ").append(s.placed).append("/").append(s.total).append(" placed");
        Map<String, Integer> m = missing(s.left, bb.in.bag);
        if (!m.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                if (parts.size() == 4) { parts.add("+" + (m.size() - 4) + " more"); break; }
                parts.add(e.getValue() + " " + bagKey(e.getKey()));
            }
            sb.append(", missing ").append(String.join(", ", parts));
        }
        if (s.occupied > 0) sb.append(", ").append(s.occupied).append(" held by other blocks (I only place)");
        sb.append(" - ").append(scoreText);
        String c = chain(bb, b);
        if (c != null) sb.append(" - next: ").append(c);
        if (b.lastStop != null) sb.append(" [last stop: ").append(b.lastStop).append("]");
        return sb.toString();
    }

    /** The material list for the start reply: "400 blocks: 230 oak_planks, 120 cobblestone, ...". */
    public static String materials(Map<String, Integer> need) {
        int sum = 0;
        List<Map.Entry<String, Integer>> l = new ArrayList<>(need.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : l) {
            sum += e.getValue();
            if (parts.size() < 8) parts.add(e.getValue() + " " + bagKey(e.getKey()));
        }
        if (l.size() > 8) parts.add("+" + (l.size() - 8) + " more kinds");
        return sum + " blocks: " + String.join(", ", parts);
    }
}
