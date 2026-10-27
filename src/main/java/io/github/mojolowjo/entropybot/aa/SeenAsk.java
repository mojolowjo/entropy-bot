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
 * 0.39.z77 seen 2: one ask in flight; the veins seen meanwhile wait and go out together in the next ask, at most one per
 * replan.everyMs (the planner's rolling re-score pace); a vein counts as asked only once its re-price ran, for that run only.
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

    // ---- 0.39.z77 seen 2 (review: a cave burst of N veins gave N whole re-prices on N ticks; a vein was marked asked when
    // its re-price never ran, and for the life of the runtime): one ask in flight, the waiting veins coalesced into the next
    // one at the planner's own re-score pace (replan.everyMs, Replan.Timer's), a vein marked asked only when its re-price ran,
    // every mark dropped when the run changes (another order, a new run) ----

    /** A vein waiting to ask: its cell, the words, its distance from the feet when seen (NaN: not known). */
    private record Wait(Cell cell, String text, double dist) {}

    private final java.util.ArrayList<Wait> pending = new java.util.ArrayList<>();
    private List<Wait> flight = List.of();
    private io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact flightFact;
    private long lastAt = Long.MIN_VALUE / 2, run = Long.MIN_VALUE;

    /** The run the asks are for ({@code runId}: Steps.runId): another run (a new order, a new run) - every vein may ask again. */
    public void run(long runId) {
        if (runId == run) return;
        run = runId;
        asked.clear();
        pending.clear();
        flight = List.of();
        flightFact = null;
    }

    private static boolean inVein(Iterable<Cell> cells, Cell c) {
        int[] at = {c.x(), c.y(), c.z()};
        for (Cell a : cells) if (a.family().equals(c.family()) && sameVein(new int[]{a.x(), a.y(), a.z()}, at)) return true;
        return false;
    }

    private static List<Cell> cells(List<Wait> ws) {
        List<Cell> out = new java.util.ArrayList<>();
        for (Wait w : ws) out.add(w.cell());
        return out;
    }

    /**
     * A new cell offered (pure): it waits to ask when it is within {@code reach} of the feet (the sensing reach, Seen.range: a
     * player's sight; {@code feet} null: not tested), a step of the run gets its family ({@link #wants}), and its vein is
     * neither asked, waiting nor in flight. True: it waits.
     */
    public boolean offer(Cell c, int[] feet, List<Step> steps, int from, Function<String, Set<String>> blocksOf, Function<String, List<String>> smeltInputs, int reach) {
        if (c == null || c.family() == null) return false;
        double d = Double.NaN;
        if (feet != null && feet.length >= 3) {                       // structural: x y z
            double dx = c.x() - feet[0], dy = c.y() - feet[1], dz = c.z() - feet[2];
            d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > reach) return false;
        }
        if (inVein(asked, c) || inVein(cells(pending), c) || inVein(cells(flight), c)) return false;
        String w = wants(c.family(), steps, from, blocksOf, smeltInputs);
        if (w == null) return false;
        pending.add(new Wait(c, "seen " + c.family() + " at " + c.x() + " " + c.y() + " " + c.z() + (Double.isNaN(d) ? "" : " (" + Math.round(d) + " away)") + " for " + w, d));
        return true;
    }

    /**
     * The one ask to send now ({@code nowMs}; {@code everyMs}: the planner's re-score pace, replan.everyMs), null: none - nothing
     * waits, an ask is in flight, or the last went out less than {@code everyMs} ago. Every waiting vein goes with it (the
     * re-price reads every seen cell): the nearest names the fact, the line counts the rest ("; 2 more veins seen").
     */
    public io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact take(long nowMs, long everyMs) {
        if (flightFact != null || pending.isEmpty() || nowMs - lastAt < everyMs) return null;
        Wait best = pending.get(0);
        for (Wait w : pending) if (!Double.isNaN(w.dist()) && (Double.isNaN(best.dist()) || w.dist() < best.dist())) best = w;
        int more = pending.size() - 1;
        flight = List.copyOf(pending);
        pending.clear();
        lastAt = nowMs;
        Cell c = best.cell();
        flightFact = io.github.mojolowjo.entropybot.aa.moves.FactChange.seen(c.family(), c.x(), c.y(), c.z(),
                best.text() + (more == 0 ? "" : "; " + more + " more vein" + (more == 1 ? "" : "s") + " seen"));
        return flightFact;
    }

    /** The ask in flight, null: none. */
    public io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact inFlight() { return flightFact; }

    /**
     * The ask in flight ended: {@code ran} - its re-price ran (Steps.seenRan): its veins are asked for this run; else (its fact
     * was overwritten, or never priced) they wait again for the next ask.
     */
    public void ended(boolean ran) {
        if (flightFact == null) return;
        if (ran) {
            for (Wait w : flight) asked.addLast(w.cell());
            while (asked.size() > KEEP) asked.removeFirst();
        } else {
            pending.addAll(flight);
        }
        flight = List.of();
        flightFact = null;
    }

    /**
     * One AA tick over the running order's {@code st} (the adapter's seenTick): the run's marks ({@link #run}), the new cells
     * offered, the ask in flight settled (its re-price ran: asked; no longer waiting there - overwritten or dropped: its veins
     * wait again), the next ask sent ({@link #take}; Steps.repriceAtNextStep). Returns the ask sent, null: none.
     */
    public io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact tick(io.github.mojolowjo.entropybot.aa.moves.Steps st, List<Cell> fresh, int[] feet,
                                                                          Function<String, Set<String>> blocksOf, Function<String, List<String>> smeltInputs, int reach,
                                                                          long nowMs, long everyMs) {
        return tick(st, fresh, feet, blocksOf, smeltInputs, reach, nowMs, everyMs, null, null);
    }

    /**
     * 0.39.z77 seen 3: as above, with the bag and the furnaces' inputs ({@code bag}, {@code furnace}: item id -> count; null:
     * not read, counted as none). While the running step needs no walk ({@link #holds}) nothing is sent - the veins wait. At the
     * send, a waiting vein whose family the run no longer lacks ({@link #lack}) is a source and nothing else: dropped, said
     * once ({@link #notes}), marked for the run.
     */
    public io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact tick(io.github.mojolowjo.entropybot.aa.moves.Steps st, List<Cell> fresh, int[] feet,
                                                                          Function<String, Set<String>> blocksOf, Function<String, List<String>> smeltInputs, int reach,
                                                                          long nowMs, long everyMs, Map<String, Integer> bag, Map<String, Integer> furnace) {
        run(st.runId());
        if (fresh != null) for (Cell c : fresh) offer(c, feet, st.steps(), st.index(), blocksOf, smeltInputs, reach);
        if (flightFact != null) {
            if (st.seenRan(flightFact)) ended(true);
            else if (!st.seenAsking(flightFact)) ended(false);
        }
        int at = st.index();
        if (at < 0 || at >= st.steps().size() || holds(st.steps().get(at))) return null;
        if (flightFact == null && !pending.isEmpty() && nowMs - lastAt >= everyMs) {
            for (java.util.Iterator<Wait> it = pending.iterator(); it.hasNext(); ) {
                Wait w = it.next();
                Cell c = w.cell();
                String word = wants(c.family(), st.steps(), at, blocksOf, smeltInputs);
                int[] nh = lack(c.family(), st.steps(), at, st::usesOf, bag, furnace, blocksOf);
                if (word != null && !(nh[0] > 0 && nh[1] >= nh[0])) continue;
                it.remove();
                asked.addLast(c);
                notes.add("seen " + c.family() + " at " + c.x() + " " + c.y() + " " + c.z() + ": a source, no re-price - "
                        + (word == null ? "no step left gets " + c.family() : "the run lacks no " + c.family() + " (needs " + nh[0] + ", the bag and the furnaces hold " + nh[1] + ")"));
            }
            while (asked.size() > KEEP) asked.removeFirst();
        }
        var f = take(nowMs, everyMs);
        if (f != null) st.repriceAtNextStep(f);
        return f;
    }

    private final List<String> notes = new java.util.ArrayList<>();

    /** 0.39.z77 seen 3: the lines of the veins dropped as covered since the last call (rule 7: a "no" is said). */
    public List<String> notes() {
        List<String> out = List.copyOf(notes);
        notes.clear();
        return out;
    }

    /**
     * 0.39.z77 seen 3 (manager's ruling 2026-10-26 ~21:20; run CU: the bot waiting at the furnace): a running step that needs
     * no walk - a craft, a smelt or cook at a station here, the take of a smelt's output, a placement, the stand - has nothing
     * a vein could interrupt: no ask is sent while it runs (the veins wait for the next step).
     */
    public static boolean holds(Step s) {
        return s instanceof Step.Craft || s instanceof Step.Smelt sm && sm.here() || s instanceof Step.Cook || s instanceof Step.SmeltCollect
                || s instanceof Step.PlaceNear || s instanceof Step.AwaitPaths;
    }

    /**
     * 0.39.z77 seen 3 (pure): {need, have} of the family's items for the run from {@code from} on - need: what the steps take of
     * the words the family's blocks feed (the chain's own counts, StepNeeds with the plan's uses: its reservations); have:
     * the bag's and the furnaces' inputs of the items those words take. need > 0 and have >= need: the run lacks none of it
     * (a vein of it changes nothing). need 0 (nothing takes it: the order's own item, a strip's count): lacking while a step
     * gets it ({@link #wants}).
     */
    public static int[] lack(String family, List<Step> steps, int from, Function<Integer, Map<String, Integer>> usesOf,
                             Map<String, Integer> bag, Map<String, Integer> furnace, Function<String, Set<String>> blocksOf) {
        java.util.Set<String> fed = new java.util.LinkedHashSet<>();
        int need = 0;
        for (int i = Math.max(0, from); steps != null && i < steps.size(); i++) {
            var n = io.github.mojolowjo.entropybot.aa.moves.StepNeeds.of(steps.get(i)).withUses(usesOf == null ? null : usesOf.apply(i));
            for (Map<String, Integer> m : List.of(n.consumes(), n.tagUses()))
                for (var e : m.entrySet()) {
                    if (e.getValue() == null || e.getValue() <= 0 || !gives(e.getKey(), family, blocksOf)) continue;
                    fed.add(e.getKey());
                    need += e.getValue();
                }
        }
        int have = 0;
        for (Map<String, Integer> m : java.util.Arrays.asList(bag, furnace)) {
            if (m == null) continue;
            for (var e : m.entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) continue;
                for (String w : fed) if (w.equals(e.getKey()) || NeedChain.gives(w, e.getKey())) { have += e.getValue(); break; }
            }
        }
        return new int[]{need, have};
    }

    /** Tests and aa status: veins waiting, asked. */
    public int waiting() { return pending.size(); }

    public int askedCount() { return asked.size(); }
}
