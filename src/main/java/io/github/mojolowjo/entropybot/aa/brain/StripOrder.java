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
        }
        // the deposit leg (owner 2026-10-22): a full bag ends this strip JOB done, the order goes on (deposit, back, resume)
        return bagFull ? Car.Status.done(BAG_FULL + (amounts.isEmpty() && picked.isEmpty() ? "" : " (" + counts(amounts, picked) + ")")) : null;
    }

    /** The strip job's bag-full end (the order is not met: the deposit leg follows). */
    public static final String BAG_FULL = "ok: strip: bag full";

    /** The order is met by this end (amounts met, or the tunnel's full length): only those end the order. */
    public static boolean orderMet(String doneText) {
        return doneText != null && !doneText.startsWith(BAG_FULL);
    }

    /** "coal 18/32, iron 4/64" (the ores with an amount), then the others' picked counts. */
    public static String counts(Map<String, Integer> amounts, Map<String, Integer> picked) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : amounts.entrySet()) out.add(e.getKey() + " " + picked.getOrDefault(e.getKey(), 0) + "/" + e.getValue());
        for (Map.Entry<String, Integer> e : new java.util.TreeMap<>(picked).entrySet()) if (!amounts.containsKey(e.getKey())) out.add(e.getKey() + " " + e.getValue());
        return String.join(", ", out);
    }

    /**
     * 0.39.z77 spares 3 (manager 2026-10-26: the logs beat the geometry; a factor is set later from measured runs): the
     * planner's predicted ore a cell per family for each strip it priced (strip key -> count key -> ore a tunnel cell =
     * 1 / the price's cells an ore, the areas' factor in), the last price kept; the strip end's fact line reads it.
     */
    private static final Map<String, Map<String, Double>> PREDICTED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int PREDICTED_MAX = 256;   // structural: a bound on strips priced in one session, not a tuning

    /** The planner's prediction for one family of strip {@code key} (ore a cell; not above 0: not kept). */
    public static void predicted(String key, String family, double perCell) {
        if (key == null || family == null || !(perCell > 0)) return;
        if (PREDICTED.size() >= PREDICTED_MAX && !PREDICTED.containsKey(key)) PREDICTED.clear();
        PREDICTED.computeIfAbsent(key, k -> new java.util.concurrent.ConcurrentHashMap<>()).put(family, perCell);
    }

    /** The predictions kept for strip {@code key} (count key -> ore a cell); empty: none priced in this session. */
    public static Map<String, Double> predicted(String key) {
        Map<String, Double> m = key == null ? null : PREDICTED.get(key);
        return m == null ? Map.of() : Map.copyOf(m);
    }

    public static void clearPredicted() { PREDICTED.clear(); }

    /**
     * 0.39.z77 spares 3: the strip end's fact line - the cells this run of the strip dug (the fork's blocks counter: tunnel
     * cells advanced), the ore it found per family (the fork's picked counts) with the measured ore a cell, and the band's
     * predicted ore a cell for that strip (the planner's price). "strip yield: strip minecraft:deepslate_iron_ore,minecraft:iron_ore|16|west:
     * 58 cells dug; found iron 0 (0.0000 a cell); predicted iron 0.0181 a cell (55.2 cells an ore)". A family with no
     * prediction kept says so (rule 15: not known, never 0).
     */
    public static String yieldLine(String key, int cells, Map<String, Integer> found, Map<String, Double> predicted, Iterable<String> families) {
        java.util.Set<String> fams = new java.util.TreeSet<>();
        for (String f : families) fams.add(f);
        fams.addAll(found.keySet());
        List<String> got = new ArrayList<>(), pred = new ArrayList<>();
        for (String f : fams) {
            int n = found.getOrDefault(f, 0);
            got.add(f + " " + n + " (" + (cells > 0 ? String.format(Locale.ROOT, "%.4f", n / (double) cells) + " a cell" : "no cell dug") + ")");
            Double p = predicted.get(f);
            pred.add(p == null ? f + " not known (no price kept for this strip)"
                    : f + " " + String.format(Locale.ROOT, "%.4f", p) + " a cell (" + String.format(Locale.ROOT, "%.1f", 1 / p) + " cells an ore)");
        }
        return "strip yield: strip " + key + ": " + cells + " cells dug; found " + String.join(", ", got) + "; predicted " + String.join(", ", pred);
    }

    /** A strip's saved progress (the fork's Progress as plain values, kept per world). */
    public record Saved(boolean started, int x, int y, int z, String heading, int step, int length, int branches,
                        int blocks, int clusters, Map<String, Integer> picked) {
        public Saved {
            picked = Map.copyOf(picked);
        }
    }

    /** aa goals: "strip east feet y -40: 120 blocks, coal 40/64, 1 deposit (36 items at x y z)" (no progress yet: "not started"). */
    public static String goalLine(String heading, int y, Map<String, Integer> amounts, Saved p) {
        return goalLine(heading, y, amounts, p, DepositRule.Deposits.NONE);
    }

    public static String goalLine(String heading, int y, Map<String, Integer> amounts, Saved p, DepositRule.Deposits d) {
        String head = "strip " + heading + " feet y " + y + ": ";
        String dep = d == null || d.count() == 0 ? "" : ", " + d.line();
        if (p == null) return head + "not started" + (amounts.isEmpty() ? "" : ", " + counts(amounts, Map.of())) + dep;
        String c = counts(amounts, p.picked());
        return head + p.blocks() + " blocks" + (c.isEmpty() ? "" : ", " + c) + dep;
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

    /**
     * 0.39.z62 item 1b: the headings a planned strip tries, in order: {@code started} (a tunnel of the same ores at the same Y
     * already begun, from its saved progress; null: none) first, then {@code facing}, then the rest clockwise from the facing
     * (north, east, south, west). Each heading once; an unknown word is left out.
     * <p>Rule 1 note (z62 review 4): this order is no price and no choice between ways - the chain prices every heading of a
     * strip at the band equal (the same band facts, the same blocks per ore), so the order only breaks that tie: resume a
     * started tunnel, else the facing. A heading the facts shut (its first cell refused, or blocked at its first cell in this
     * order) is left out, never priced lower.
     */
    public static List<String> headingOrder(String started, String facing) {
        List<String> out = new ArrayList<>();
        if (started != null && HEADINGS.contains(started)) out.add(started);
        int f = facing == null ? -1 : CLOCKWISE.indexOf(facing);
        for (int i = 0; i < CLOCKWISE.size(); i++) {
            String h = CLOCKWISE.get(((f < 0 ? 0 : f) + i) % CLOCKWISE.size());
            if (!out.contains(h)) out.add(h);
        }
        return out;
    }

    /** The compass words clockwise (the turn order of {@link #headingOrder}). */
    private static final List<String> CLOCKWISE = List.of("north", "east", "south", "west");

    /** The fork StripProcess's end reason when its search to the next tunnel cell failed (StripProcess: end("no path")). */
    public static final String NO_PATH = "no path";

    /**
     * 0.39.z62 review 2: the strip's heading is blocked at its FIRST cell - only when the fork ended {@link #NO_PATH} after it
     * stood at the strip's Y ({@code started}: the fork's origin set; before that the "no path" is the way down, not the
     * heading) and before it took a step or dug a block since this start ({@code stepNow}/{@code blocksNow} not past the
     * saved progress it resumed from). Any other end with nothing dug - a broken pickaxe, lava or water ahead, a danger stop,
     * lost control, a pause - is not a fact about the heading.
     */
    public static boolean noPathAtFirstCell(String forkEnd, boolean started, int stepAtStart, int stepNow, int blocksAtStart, int blocksNow) {
        return NO_PATH.equals(forkEnd) && started && stepNow <= stepAtStart && blocksNow <= blocksAtStart;
    }

    /**
     * 0.39.z62 review 4: the heading of a saved strip of exactly {@code ores} at {@code y} among the progress file's keys
     * (Step.Strip#key: the sorted ids, the Y, the heading), checked in {@link #HEADINGS} order; null: none.
     */
    public static String startedHeading(java.util.Set<String> savedKeys, List<String> ores, int y) {
        if (savedKeys == null || ores == null) return null;
        List<String> s = new ArrayList<>(ores);
        s.sort(null);
        String head = String.join(",", s) + "|" + y + "|";
        for (String h : HEADINGS) if (savedKeys.contains(head + h)) return h;
        return null;
    }

    /**
     * 0.39.z62 item 1b / review 4: why a strip from {@code feet} in heading {@code h} is blocked at its first cell (feet and head
     * height), from {@code cell}: a cell {x, y, z} -> why it will not be broken (null: it may be, or not loaded); null: open.
     */
    public static String startBlocked(int[] feet, String h, Function<int[], String> cell) {
        int[] d = io.github.mojolowjo.entropybot.aa.WayUp.dir(h);
        for (int dy = 0; dy <= 1; dy++) {            // structural: the feet and the head cell of the tunnel's first step
            String why = cell.apply(new int[]{feet[0] + d[0], feet[1] + dy, feet[2] + d[1]});
            if (why != null) return why;
        }
        return null;
    }

    /**
     * 0.39.z76 sb4 review (run AV 02:40:45-02:41:36: six strips "no path (0 blocks)" with no reason in the log): the rule's
     * answer for each first cell (feet and head height) of a strip from {@code feet} in heading {@code h}, "first cells:
     * x y z: answer; x y z: answer", from {@code answer} (the break policy's verdict - its rule row and cost - for a cell; null:
     * "not known"), for the blocked-heading fact. Pure.
     */
    public static String firstCells(int[] feet, String h, Function<int[], String> answer) {
        int[] d = io.github.mojolowjo.entropybot.aa.WayUp.dir(h);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int dy = 0; dy <= 1; dy++) {            // structural: the feet and the head cell of the tunnel's first step
            int[] c = {feet[0] + d[0], feet[1] + dy, feet[2] + d[1]};
            String a;
            try {
                a = answer.apply(c);
            } catch (RuntimeException e) {
                a = "the rule could not be read: " + e;
            }
            out.add(c[0] + " " + c[1] + " " + c[2] + ": " + (a == null ? "not known" : a));
        }
        return "first cells: " + String.join("; ", out);
    }

    /**
     * 0.39.z76 sb4 review 2: {@code read}'s first-cells text, or "first cells: the rule could not be read: &lt;e&gt;" when any
     * part of the read throws (the fact keeps its reason, rule 7). Pure.
     */
    public static String guardedFirstCells(java.util.function.Supplier<String> read) {
        try {
            String t = read.get();
            return t == null ? "first cells: the rule not known" : t;
        } catch (RuntimeException e) {
            return "first cells: the rule could not be read: " + e;
        }
    }

    /** The fork's end text as the step's status: "done..." is DONE, anything else FAILED with that text (never silent). */
    public static Car.Status endOf(String forkEnd, String forkStatus) {
        if (forkEnd == null || forkEnd.isBlank()) return Car.Status.failed("strip: the fork's strip ended without a reason (" + forkStatus + ")");
        if (forkEnd.startsWith("done")) return Car.Status.done("ok: " + forkEnd);
        if (forkEnd.startsWith("bag full")) return Car.Status.done(BAG_FULL + " (" + forkStatus + ")");
        if (forkEnd.equals("lost control")) return Car.Status.lost("strip: lost control (" + forkStatus + ")");
        return Car.Status.failed("strip: " + forkEnd + " (" + forkStatus + ")");
    }
}
