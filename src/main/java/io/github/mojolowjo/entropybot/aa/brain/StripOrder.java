package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.moves.Car;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The strip order (owner 2026-10-22): {@code aa want strip <ore|ore,ore|any> [<y>] [<heading>]}; a bare integer is the
 * Y level, a compass word the heading, both optional in either order. Pure: the words, the ore set from the game's data
 * (ore tags, mining tiers, the catalog's worldgen band), the fork's end texts as a step status.
 */
public final class StripOrder {
    private StripOrder() {}

    public static final List<String> HEADINGS = List.of("north", "east", "south", "west");

    /**
     * ores: the words ("any" alone, or ids / family words); amounts: ore word -> amount for the ores that carry one (none:
     * strip until the bag is full); y, heading: null = default.
     */
    public record Args(List<String> ores, Map<String, Integer> amounts, Integer y, String heading, String err) {
        public boolean any() { return ores != null && ores.size() == 1 && ores.get(0).equals("any"); }
    }

    /** True when the want's words are a strip order. */
    public static boolean is(String arg) {
        return arg != null && arg.trim().toLowerCase(Locale.ROOT).matches("^strip(\\s.*)?$");
    }

    public static final String USAGE = "usage: aa want strip <ore[ amount]>[,<ore[ amount]>...] [<y>] [north|east|south|west]"
            + " (a number right after an ore is its amount; the first number after the list is the Y; \"any\" takes no amount)"
            + " e.g. aa want strip coal_ore 32, aa want strip iron_ore 64,coal_ore 16 east, aa want strip any 40";

    private static Args bad(String why) { return new Args(null, null, null, null, why); }

    /**
     * The words: the list is split at commas; in each part the first word is the ore, a number right after it its amount
     * ("any" takes none); after the list (the last part's rest) the first number is the Y and a compass word the heading.
     */
    public static Args parse(String arg) {
        String s = arg == null ? "" : arg.trim().toLowerCase(Locale.ROOT);
        if (!s.matches("^strip\\s+\\S.*")) return bad(USAGE);
        String[] parts = s.substring(5).trim().split("\\s*,\\s*");
        List<String> ores = new ArrayList<>();
        Map<String, Integer> amounts = new LinkedHashMap<>();
        Integer y = null;
        String heading = null;
        for (int pi = 0; pi < parts.length; pi++) {
            String[] w = parts[pi].trim().split("\\s+");
            String ore = w[0];
            if (ore.isEmpty() || ore.matches("-?\\d+") || HEADINGS.contains(ore)) return bad(USAGE);
            if (ores.contains(ore)) return bad("error: " + ore + " is named twice");
            ores.add(ore);
            int i = 1;
            if (i < w.length && w[i].matches("\\d{1,5}") && !ore.equals("any")) {
                int n = Integer.parseInt(w[i]);
                if (n <= 0) return bad("error: the amount of " + ore + " must be 1 or more");
                amounts.put(ore, n);
                i++;
            }
            boolean last = pi == parts.length - 1;
            for (; i < w.length; i++) {
                String t = w[i];
                if (!last) return bad("error: \"" + t + "\" inside the ore list (Y and heading come after the list); " + USAGE);
                if (t.matches("-?\\d{1,4}")) {
                    if (y != null) return bad("error: two Y levels (" + y + ", " + t + ")");
                    y = Integer.parseInt(t);
                } else if (HEADINGS.contains(t)) {
                    if (heading != null) return bad("error: two headings (" + heading + ", " + t + ")");
                    heading = t;
                } else {
                    return bad("error: \"" + t + "\" is neither a Y level nor a heading; " + USAGE);
                }
            }
        }
        if (ores.contains("any") && ores.size() > 1) return bad("error: \"any\" stands alone (every ore my pickaxe can take)");
        return new Args(ores, amounts, y, heading, null);
    }

    /** The count key of an ore word or id: its family without the namespace ("minecraft:deepslate_coal_ore", "coal" -> "coal"), as the fork counts. */
    public static String countKey(String word, Function<String, String> family) {
        String f = family.apply(word.contains(":") ? word : "minecraft:" + word);
        return f.contains(":") ? f.substring(f.indexOf(':') + 1) : f;
    }

    /**
     * The order's end rule, checked while the strip runs: every ore WITH an amount reached -> DONE "amounts met"; the bag
     * full -> "bag full" (DONE when no ore carries an amount: that is the order; FAILED when an amount is still short).
     * Null: go on. amounts / picked: count key -> n.
     */
    public static Car.Status endRule(Map<String, Integer> amounts, Map<String, Integer> picked, boolean bagFull) {
        if (!amounts.isEmpty()) {
            boolean met = true;
            for (Map.Entry<String, Integer> e : amounts.entrySet()) met &= picked.getOrDefault(e.getKey(), 0) >= e.getValue();
            if (met) return Car.Status.done("ok: strip: amounts met (" + counts(amounts, picked) + ")");
            if (bagFull) return Car.Status.failed("strip: bag full (" + counts(amounts, picked) + ")");
            return null;
        }
        return bagFull ? Car.Status.done("ok: strip: bag full") : null;
    }

    /** "coal 18/32, iron 4/64" (the ores with an amount), then the others' picked counts. */
    public static String counts(Map<String, Integer> amounts, Map<String, Integer> picked) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : amounts.entrySet()) out.add(e.getKey() + " " + picked.getOrDefault(e.getKey(), 0) + "/" + e.getValue());
        for (Map.Entry<String, Integer> e : new java.util.TreeMap<>(picked).entrySet()) if (!amounts.containsKey(e.getKey())) out.add(e.getKey() + " " + e.getValue());
        return String.join(", ", out);
    }

    /** A strip's saved progress (the fork's Progress as plain values, kept per world). */
    public record Saved(boolean started, int x, int y, int z, String heading, int step, int length, int branches,
                        int blocks, int clusters, Map<String, Integer> picked) {
        public Saved {
            picked = Map.copyOf(picked);
        }
    }

    /** aa goals: "strip east y 16: 40 blocks dug, coal 18/32" (no progress yet: "not started"). */
    public static String goalLine(String heading, int y, Map<String, Integer> amounts, Saved p) {
        String head = "strip " + heading + " y " + y + ": ";
        if (p == null) return head + "not started" + (amounts.isEmpty() ? "" : ", " + counts(amounts, Map.of()));
        String c = counts(amounts, p.picked());
        return head + p.blocks() + " blocks dug" + (c.isEmpty() ? "" : ", " + c);
    }

    /**
     * "any": every ore block (oreTiers: ore block id -> its mining tier from the game's tags, Thing.tier's scale) the
     * pickaxe tier takes; sorted.
     */
    public static List<String> anyOres(Map<String, Integer> oreTiers, int pickTier) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : oreTiers.entrySet()) if (e.getValue() <= pickTier) out.add(e.getKey());
        out.sort(null);
        return out;
    }

    /**
     * Named ores: each word is an ore block id ("coal_ore", "minecraft:deepslate_iron_ore") or a family word ("iron");
     * it takes every ore block of the same family (family: the game id -> family function, e.g. OreSpec.family).
     * Returns the ids, or a one-element list "error: ..." when a word names no ore block.
     */
    public static List<String> namedOres(List<String> words, Iterable<String> oreIds, Function<String, String> family) {
        List<String> out = new ArrayList<>();
        for (String w : words) {
            String id = w.contains(":") ? w : "minecraft:" + w;
            String fam = null;
            for (String o : oreIds) if (o.equals(id)) fam = family.apply(o);
            if (fam == null) fam = w;           // a family word
            boolean hit = false;
            for (String o : oreIds) {
                if (family.apply(o).equals(fam)) {
                    hit = true;
                    if (!out.contains(o)) out.add(o);
                }
            }
            if (!hit) return List.of("error: no ore block is called " + w + " (an ore id like coal_ore, a family like iron, or any)");
        }
        out.sort(null);
        return out;
    }

    /** The centre of the ores' worldgen band (their union); null when none of them has one (no worldgen read). */
    public static Integer bandCentre(List<String> ores, Map<String, Thing.Gen> gen) {
        if (gen == null) return null;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (String o : ores) {
            Thing.Gen g = gen.get(o);
            if (g == null) continue;
            lo = Math.min(lo, g.minY());
            hi = Math.max(hi, g.maxY());
        }
        return lo > hi ? null : Math.floorDiv(lo + hi, 2);
    }

    /** The fork's end text as the step's status: "done..." is DONE, anything else FAILED with that text (never silent). */
    public static Car.Status endOf(String forkEnd, String forkStatus) {
        if (forkEnd == null || forkEnd.isBlank()) return Car.Status.failed("strip: the fork's strip ended without a reason (" + forkStatus + ")");
        if (forkEnd.startsWith("done")) return Car.Status.done("ok: " + forkEnd);
        if (forkEnd.equals("lost control")) return Car.Status.lost("strip: lost control (" + forkStatus + ")");
        return Car.Status.failed("strip: " + forkEnd + " (" + forkStatus + ")");
    }
}
