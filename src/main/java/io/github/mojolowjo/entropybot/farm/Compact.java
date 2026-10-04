package io.github.mojolowjo.entropybot.farm;

import io.github.mojolowjo.entropybot.craft.Crafter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "compact &lt;item&gt; [here | &lt;place&gt; | x y z]" (the bridge's compactPair, startCompact and compactStep) without the
 * game: turns &lt;item&gt; in the chests within {@link #COMPACT_R} blocks into its 9-to-1 (or 4-to-1) block, chest by
 * chest: take a bag-sized batch (a multiple of 9), craft it at the nearest table, put the blocks back into the chest
 * they came from, again until fewer than 9 are left there. The bot's own items stay its own.
 */
public final class Compact {
    private Compact() {}

    public static final int COMPACT_R = 6;
    /** Free slots kept for the crafted blocks. */
    public static final int COMPACT_SPARE = 2;

    /** An item and its block: {@code per} of {@code item} craft into one {@code block}. */
    public record Pair(String item, String block, int per) {}

    /** "inferium_essence" for "mysticalagriculture:inferium_essence": the reports are whispered. */
    public static String bareId(String id) { return id == null ? "" : id.replaceFirst("^[^:]*:", ""); }

    /** "stick" for "minecraft:stick", modded ids stay whole. */
    public static String shortId(String id) { return id == null ? "" : id.replaceFirst("^minecraft:", ""); }

    // ---- compactPair ----

    /** A crafting-grid recipe that takes only one ingredient (9 of it, else 4) and makes one item; null otherwise. */
    private static Pair single(Crafter.Recipe rec, String input) {
        if (rec == null || !"minecraft:crafting".equals(rec.type())) return null;
        List<Crafter.Need> needs = rec.needs();
        if (needs.size() != 1 || (needs.get(0).amount() != 9 && needs.get(0).amount() != 4) || rec.outCount() != 1) return null;
        if (needs.get(0).alts().isEmpty()) return null;
        if (input != null && !needs.get(0).alts().contains(input)) return null;
        return new Pair(input != null ? input : needs.get(0).alts().get(0), rec.output(), needs.get(0).amount());
    }

    /**
     * The pair for an item or its block (full ids): a crafting-grid recipe that takes only &lt;item&gt; (9 of it, else 4)
     * and makes one &lt;block&gt;. Named the block: what is it made of? Null if there is none.
     */
    public static Pair pair(Crafter crafter, String id) {
        Pair best = null;
        for (Crafter.Recipe r : crafter.craftingRecipesUsing(id)) {
            Pair out = single(r, id);
            if (out != null && !out.block().equals(id) && (best == null || out.per() > best.per())) best = out;
        }
        if (best != null) return best;
        // named the block: what is it made of?
        for (Crafter.Recipe r : crafter.craftingRecipesFor(id)) {
            Pair out = single(r, null);
            if (out != null && out.block().equals(id) && !out.item().equals(id) && (best == null || out.per() > best.per())) best = out;
        }
        return best;
    }

    // ---- the command ----

    public static final String USAGE = "error: usage compact <item> [here | <place> | x y z] (e.g. compact inferium_essence)";
    public static final String NO_TABLE = "error: there is no crafting table near me, the chests or the base - next: craft crafting_table, then place crafting_table x y z by the chests";

    public static String unknownItem(String word) { return "error: I don't know an item called " + word; }

    public static String noPair(String id) { return "error: " + shortId(id) + " doesn't craft 9-to-1 (or 4-to-1) into a block"; }

    /** A place error (resolveSpot's, worded for "open") reworded for compact. */
    public static String placeError(String err) { return err.replace("open", "compact <item>"); }

    /** "compact" splits into: the item word, and where ("" or "here" = around the owner when visible, else the bot). */
    public static String[] words(String text) {
        String t = text == null ? "" : text.trim().toLowerCase();
        if (t.isEmpty()) return new String[0];
        String[] w = t.split("\\s+", 2);
        return new String[]{w[0], w.length > 1 ? w[1].trim() : ""};
    }

    /** A compact job to start: its Seq label (closeOnEnd "always"), steps and run state. */
    public record Start(String label, List<PlanStep> steps, Run run) {}

    /**
     * The job for a pair around center (centerLabel: its place name, else "x y z"): walk there first when it is more
     * than 12 blocks from the bot, then compacthere and compactdone.
     */
    public static Start start(Pair pair, int[] center, String centerLabel, int[] me) {
        List<PlanStep> steps = new ArrayList<>();
        if (FarmRules.distSq(center, me) > 12 * 12) steps.add(PlanStep.walk(center, true));
        steps.add(PlanStep.compactHere(center, centerLabel));
        steps.add(PlanStep.of("compactdone"));
        String label = "compacting " + bareId(pair.item()) + " into " + bareId(pair.block()) + " around " + centerLabel;
        return new Start(label, steps, new Run(pair, label));
    }

    // ---- the run ----

    /** One bag slot: item id ("" when empty), count, its stack size. */
    public record Slot(String id, int count, int max) {}

    /** The bag's room for an item: what its part stacks still take, empty slots, its stack size (64 when none is carried). */
    public record BagSpace(int stacks, int empty, int max) {}

    public static BagSpace bagSpace(List<Slot> slots, String id) {
        int stacks = 0, empty = 0, max = 64;
        for (int i = 0; i < 36 && i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (s == null || FarmRules.empty(s.id()) || s.count() <= 0) empty++;
            else if (s.id().equals(id)) {
                max = s.max();
                stacks += max - s.count();
            }
        }
        return new BagSpace(stacks, empty, max);
    }

    /** A step's answer: "next" or why the job fails; steps to splice right after the current one; new label and note (null: unchanged). */
    public record Result(String result, List<PlanStep> splice, String label, String note) {
        static Result of(String r) { return new Result(r, List.of(), null, null); }
    }

    /** The job's state (the bridge's job.cp) and the steps compacthere / compactchest / compactdone. */
    public static final class Run {
        public final Pair pair;
        public final String label;
        int chests, taken, made, left;

        public Run(Pair pair, String label) {
            this.pair = pair;
            this.label = label;
        }

        public int chests() { return chests; }

        public int taken() { return taken; }

        public int made() { return made; }

        public int left() { return left; }

        /**
         * compacthere: arrived; {@code found} = the storage containers (chests, barrels: no machines) within
         * COMPACT_R of the center, as Storage.findContainers lists them. Nearest to the bot first, at most 30.
         */
        public Result here(List<int[]> found, int[] me, String centerLabel) {
            List<int[]> list = new ArrayList<>(found);
            list.sort((a, b) -> Long.compare(FarmRules.distSq(a, me), FarmRules.distSq(b, me)));
            if (list.isEmpty()) return Result.of("no chests or barrels within " + COMPACT_R + " blocks of " + centerLabel);
            List<PlanStep> add = new ArrayList<>();
            for (int i = 0; i < list.size() && i < 30; i++) {
                add.add(PlanStep.walk(list.get(i), false));
                add.add(PlanStep.open(list.get(i)));
                add.add(PlanStep.compactChest(list.get(i), false));
            }
            return new Result("next", add, null, null);
        }

        /**
         * compactchest: the chest is open; take a batch (and come back for more), or move on with the rest left in it.
         *
         * @param open          is a container open (not just the bot's own inventory)?
         * @param have          how many of the item the open container holds (its storage slots, TAKE_ROLES)
         * @param bag           the bot's 36 bag slots
         */
        public Result chest(int[] pos, boolean again, boolean open, int have, List<Slot> bag) {
            if (!open) return Result.of("no container open");
            if (!again) chests += have >= pair.per() ? 1 : 0;
            if (have < pair.per()) {
                left += have;
                return new Result("next", List.of(PlanStep.close()), null, null);
            }
            // the item fills part stacks and all but COMPACT_SPARE empty slots; the blocks need part stacks or those spare slots
            BagSpace si = bagSpace(bag, pair.item()), sb = bagSpace(bag, pair.block());
            long room = Math.min((long) si.stacks() + (long) Math.max(0, si.empty() - COMPACT_SPARE) * si.max(),
                    ((long) sb.stacks() + (long) Math.min(si.empty(), COMPACT_SPARE) * sb.max()) * pair.per());
            int batch = (int) (Math.min(have, room) / pair.per()) * pair.per();
            if (batch < pair.per()) {
                return Result.of("my inventory is too full to carry " + pair.per() + " " + bareId(pair.item()) + " (I keep " + COMPACT_SPARE + " slots free for the blocks)");
            }
            int n = batch / pair.per();
            int keep = 0;                                   // blocks the bot had already stay with it
            for (Slot s : bag) if (s != null && pair.block().equals(s.id())) keep += s.count();
            taken += batch;
            made += n;
            Map<String, Integer> keepMap = new LinkedHashMap<>();
            keepMap.put(pair.block(), keep);
            List<PlanStep> add = List.of(
                    PlanStep.take(pos, pair.item(), batch),
                    PlanStep.close(),
                    PlanStep.craft(pair.block(), n, false),
                    PlanStep.walk(pos, false),
                    PlanStep.open(pos),
                    PlanStep.put(keepMap),
                    PlanStep.compactChest(pos, true));
            return new Result("next", add, label + " (" + made + " " + bareId(pair.block()) + " so far)", null);
        }

        /** compactdone: the report ({@code putLeft}: the blocks the put steps couldn't get back in, Seq's putLeft). */
        public Result done(int putLeft) {
            String note = made != 0
                    ? "turned " + taken + " " + bareId(pair.item()) + " into " + made + " " + bareId(pair.block()) + " in " + chests + " chest" + (chests == 1 ? "" : "s")
                      + ", " + left + " left over" + (putLeft != 0 ? " (" + putLeft + " blocks didn't fit back, I carry them)" : "")
                    : "nothing to compact: no chest here holds " + pair.per() + " or more " + bareId(pair.item()) + (left != 0 ? " (" + left + " in all)" : "");
            return new Result("next", List.of(), label, note);
        }
    }
}
