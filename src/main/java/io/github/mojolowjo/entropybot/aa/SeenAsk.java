package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.moves.DryRun;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import io.github.mojolowjo.entropybot.cave.OreSpec;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 0.39.z77 seen (owner 2026-10-26 ~17:05; to-do/candidate-ore-passed.md, runs BZ + CC: 24 veins near under-ground steps, 12
 * passed by, none priced within 90 s - the running step was never asked): which fresh ore cell asks the running order to be
 * priced again. A cell new to the seen store ({@code Seen.saw}, or the exposed-face patch with fair play off) of a family that
 * one of the run's steps from the running one on gets from the world - a collect's or an explore's word whose source blocks
 * (the loot data) are of that family, an explore's word read through the furnace (ExploreRules.throughSmelts), a strip's ore,
 * a mine's family - within the sensing reach of the feet asks once per vein (a cell of the family within {@link #VEIN} of an
 * asked one is that vein). No periodic scan, no new knob, no change to the rays: the fact itself is the trigger, and the
 * planner prices "collect it now" against what is left of the running pick (rule 1: Goals.reprice, replan.switchMarginS).
 * Pure (JUnit: Z77SeenTest). Loader notes: none (the adapter AaRuntime.seenTick drains Seen's fresh cells on the AA tick).
 */
public final class SeenAsk {
    /** Cells of one family this close on every axis are one vein (ore veins are touching cells; one gap allowed). */
    public static final int VEIN = 2; // structural: the vein's reach in blocks
    /** The veins remembered as asked (the oldest go first). */
    static final int KEEP = 256; // structural

    /** One fresh cell: its family ("iron", the ore index's word) and where. */
    public record Cell(String family, int x, int y, int z) {}

    private final ArrayDeque<Cell> asked = new ArrayDeque<>();

    /** Two cells are one vein: within {@link #VEIN} on every axis. */
    public static boolean sameVein(int[] a, int[] b) {
        if (a == null || b == null || a.length < 3 || b.length < 3) return false;     // structural: x y z
        return Math.abs(a[0] - b[0]) <= VEIN && Math.abs(a[1] - b[1]) <= VEIN && Math.abs(a[2] - b[2]) <= VEIN;
    }

    /** The steps take the vein of {@code cell}: a collect priced at a spot of it, or a mine at it. */
    public static boolean takes(List<Step> steps, int[] cell) {
        if (steps == null || cell == null) return false;
        for (Step s : steps) {
            if (s instanceof Step.Collect c && c.at() != null && sameVein(c.at(), cell)) return true;
            if (s instanceof Step.MineAt m && sameVein(new int[]{m.x(), m.y(), m.z()}, cell)) return true;
        }
        return false;
    }

    /** A block id is of the family (the ore index's word: OreSpec.family). */
    static boolean ofFamily(String block, String family) {
        return block != null && family != null && OreSpec.family(block).equals(family);
    }

    /** A family word as a step names it ("iron", "minecraft:iron_ore", "oritech:nickel") in the ore index's words. */
    static String familyWord(String w) {
        if (w == null) return null;
        String low = w.toLowerCase(Locale.ROOT);
        return OreSpec.family(low.contains(":") ? low : "minecraft:" + low);
    }

    /**
     * The word of the run's steps from {@code from} on that the family's blocks give (the step's own word: "minecraft:raw_iron",
     * a strip's ore id, a mine's family), null: no step of the run gets anything from that family. {@code blocksOf}: a need
     * word's source blocks (the loot data, the adapter's sourceBlocksOf); {@code smeltInputs}: what smelts into an item.
     */
    public static String wants(String family, List<Step> steps, int from, Function<String, Set<String>> blocksOf, Function<String, List<String>> smeltInputs) {
        if (family == null || steps == null || from < 0) return null;
        for (int i = from; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s instanceof Step.Strip st) {
                for (String ore : st.ores()) if (ofFamily(ore, family)) return ore;
                continue;
            }
            if (s instanceof Step.Mine m) { if (family.equals(familyWord(m.family()))) return m.family(); continue; }
            if (s instanceof Step.MineAt m) { if (family.equals(familyWord(m.family()))) return m.family(); continue; }
            if (s instanceof Step.Explore ex) {
                if (ex.until() == null) continue;
                for (String part : ExploreRules.throughSmelts(ex.until(), smeltInputs).split("\\|"))
                    if (gives(part, family, blocksOf)) return part;
                continue;
            }
            Map<String, Integer> got = DryRun.produces(s);
            if (got == null) continue;
            for (String w : got.keySet()) if (gives(w, family, blocksOf)) return w;
        }
        return null;
    }

    private static boolean gives(String word, String family, Function<String, Set<String>> blocksOf) {
        if (word == null || word.isEmpty() || blocksOf == null) return false;
        Set<String> blocks = blocksOf.apply(word);
        if (blocks == null) return false;
        for (String b : blocks) if (ofFamily(b, family)) return true;
        return false;
    }

    /**
     * The ask for {@code c} (the fact's chat words, "seen iron at 3 40 0 (3 away) for minecraft:raw_iron"), null: not asked -
     * beyond {@code reach} of the feet (the sensing reach, Seen.range: a player's sight), a vein asked already, or no step of
     * the run gets that family. An ask is remembered for its vein. {@code feet} null: the reach is not tested (not known).
     */
    public String ask(Cell c, int[] feet, List<Step> steps, int from, Function<String, Set<String>> blocksOf, Function<String, List<String>> smeltInputs, int reach) {
        if (c == null || c.family() == null) return null;
        int[] at = {c.x(), c.y(), c.z()};
        double d = Double.NaN;
        if (feet != null && feet.length >= 3) {                       // structural: x y z
            double dx = c.x() - feet[0], dy = c.y() - feet[1], dz = c.z() - feet[2];
            d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > reach) return null;
        }
        for (Cell a : asked) if (a.family().equals(c.family()) && sameVein(new int[]{a.x(), a.y(), a.z()}, at)) return null;
        String w = wants(c.family(), steps, from, blocksOf, smeltInputs);
        if (w == null) return null;
        asked.addLast(c);
        while (asked.size() > KEEP) asked.removeFirst();
        return "seen " + c.family() + " at " + c.x() + " " + c.y() + " " + c.z() + (Double.isNaN(d) ? "" : " (" + Math.round(d) + " away)") + " for " + w;
    }
}
