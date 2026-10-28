package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Inputs;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.39.z77 world (manager's ruling 2026-10-26 ~23:00; run CX 22:42:18 -> 22:43:18: the pickaxe's strip north at y 16 met
 * "water ahead at 18 16 -23", the pickaxe order was met one second later and its held sources (Goals.Goal#sources, one
 * Failures per order) went off the goal list with it, so the helmet ran the same strip to the same water; run CS
 * 19:33-19:37: each order took the same seen ore 20 28 19 to the same no-progress): a source fact one order learned - water
 * or lava ahead on a heading at a Y, no path from a cell, no progress at a source, an unreachable spot - is a fact about the
 * WORLD, not about the order. Kept here by its source key with the cell the feet stood on when it was FIRST learned and the
 * order that learned it; every other order's chain (Goals.chainWorld) prices without it while it holds - the batch's open
 * orders and later orders of the run (the AA's blackboard; a new world is a new run).
 *
 * <p>When it holds (0.39.z77 world 3, review):
 * <ul>
 * <li>Any fact: gone (removed) when its cause is gone - a missing-item fact once the bag's total rose (Failures#sweep's
 * rule); a fact naming "water/lava ahead at x y z" once that cell reads not fluid while loaded and in sight range
 * ({@link #FLUID_FACT}, the game side's answer; not loaded or out of range: no answer, rule 15).</li>
 * <li>A strip fact ({@link #stripFact}) is about its tunnel, never the bot's distance: the way key names the tunnel
 * (ores, Y, heading, a fresh start's area); a heading blocked or ended at its Y is kept by the tunnel's start as well
 * ({@link #scoped}: "|from area ax,az"), so the same heading from another start is another fact. Lifts: the cause re-read,
 * a fresh start elsewhere (another key), the run's end. 0.39.z77 world 4 (coordinator's decision): one whose cause names no
 * cell (a yield end, "no path" in the tunnel, blocked at its start) holds for the run - the exact tunnel only, said so.</li>
 * <li>Any other fact holds by distance with hysteresis: held within failures.moved of the cell, lifted only beyond
 * failures.moved + {@link #MARGIN}, held again only within failures.moved - feet jittering at the edge never flip it
 * (the batch trip's key reads these, BatchWay.trip).</li>
 * </ul>
 * The order that learned it keeps its own hold as before (Goals#droppedSources). Not shared: a fact learned with the feet
 * not known (no world position, rule 15). The climb way is shared since run EJ (its one more try forgets it, Goals#parkedTick). A read
 * refreshes the states (the removals, the hysteresis). In memory only. Pure (JUnit: Z77WorldFactsTest). Loader notes:
 * none here; the fluid answer is AaRuntime#fluidAt (vanilla ClientLevel#isLoaded / #getFluidState).
 */
public final class WorldFacts {
    /** The blackboard fact holding the run's world facts. */
    public static final String FACT = "aa.worldFacts";
    /** The blackboard fact: cell {x, y, z} -> fluid there (TRUE / FALSE), null: not loaded or out of sight range. */
    public static final String FLUID_FACT = "aa.worldFacts.fluidAt";

    /** structural: the hysteresis band past failures.moved (blocks) before a distance-held fact lifts; feet jitter ~1-2 blocks. */
    static final double MARGIN = 8; // structural: hysteresis band (review 2's "larger structural margin")

    /** One fact: its store key, the learner's reason, the feet when first learned, the bag's total then, the learner, the cause cell (null: none named). */
    public record Rec(String key, String reason, int[] cell, int items, String order, int[] cause) {}

    private static final class Entry {
        final Rec rec;
        boolean in = true;           // the hysteresis state (distance facts): held now
        Entry(Rec r) { rec = r; }
    }

    private final Map<String, Entry> map = new LinkedHashMap<>();
    /** "order id|key" said once: the line that another order's fact holds here. */
    private final Set<String> said = new HashSet<>();

    /** The run's world facts on {@code bb} (made on the first ask). */
    public static WorldFacts of(Blackboard bb) {
        if (bb.fact(FACT) instanceof WorldFacts w) return w;
        WorldFacts w = new WorldFacts();
        bb.fact(FACT, w);
        return w;
    }

    /**
     * The key is a world fact (shared): every source key. 0.39.z78 run EJ (rule 7; run EJ 01:33:40-01:47:52: six orders each
     * ran the same failed climb at -6 56 18, 36 tries): the climb way's key too - one failed climb at a cell is one fact for
     * every order there; its one more try (Goals#parkedTick) forgets it here as well ({@link #forget}).
     */
    static boolean shared(String key) {
        return key != null && !key.isBlank() && !standHold(key);
    }

    /**
     * 0.39.z78 J6c run EU (1c; 10:40:37: "stood at -92 79 -218" and "step stand while paths compute ... stuck 40 s" held for
     * all four armour orders): a stand's hold is the order's own wait, never a world outcome - the stood cell
     * (NeedChain.stoodKey) and the stand step's own key. Not shared.
     */
    static boolean standHold(String key) {
        return key.startsWith(io.github.mojolowjo.entropybot.aa.NeedChain.STOOD_AT) || key.startsWith("step " + STAND_TEXT);
    }

    /** The stand step's text start (Step.AwaitPaths#text). */
    private static final String STAND_TEXT = "stand while paths compute";

    /** 0.39.z78 run EJ: {@code key} is no longer held for any order (the climb's one more try); said again if learned again. */
    public void forget(String key) {
        if (key == null) return;
        map.remove(key);
        said.removeIf(s -> s.endsWith("|" + key));
    }

    /** 0.39.z78 run EJ: {@code key} is held now for an order whose own held keys are {@code own}. */
    public boolean holds(String key, Set<String> own, Inputs in, Function<int[], Boolean> fluid) {
        return key != null && heldFor(own, in, fluid).containsKey(key);
    }

    /**
     * 0.39.z77 world 2 (manager 2026-10-26, run CX 22:43:21: the helmet 17.0 blocks from the pickaxe's water-ahead strip ran
     * it to the same water in 0.0 s - the fork's strip resumes its saved tunnel by its key from anywhere): a strip fact
     * (the way's key "strip ores|Y|heading[|from area ax,az]", or a heading at its Y "strip heading y Y h") is about the
     * tunnel, never about where the bot stands.
     */
    static boolean stripFact(String key) {
        return key != null && (key.startsWith(io.github.mojolowjo.entropybot.aa.NeedChain.STRIP_KEY)
                || key.startsWith(io.github.mojolowjo.entropybot.aa.NeedChain.STRIP_HEADING));
    }

    /**
     * 0.39.z77 world 3 (review 1): the store key - a heading fact ("strip heading y Y h") with its tunnel's start
     * ({@code start}: "area ax,az", NeedChain#stripStart) appended (NeedChain#headingFrom), every other key as it is.
     */
    static String scoped(String key, String start) {
        if (key == null || !key.startsWith(io.github.mojolowjo.entropybot.aa.NeedChain.STRIP_HEADING) || start == null) return key;
        if (key.contains("|from ")) return key;              // 0.39.z78 strip: a fresh start's hold is scoped already (NeedChain.stripHold)
        return io.github.mojolowjo.entropybot.aa.NeedChain.headingFrom(key, start);
    }

    private static final Pattern CAUSE = Pattern.compile("(?:water|lava) ahead at (-?\\d+) (-?\\d+) (-?\\d+)");

    /** The cell a reason names as its cause ("water ahead at 8 17 0"), null: none. */
    static int[] causeCell(String reason) {
        if (reason == null) return null;
        Matcher m = CAUSE.matcher(reason);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))} : null;
    }

    /**
     * {@code order} (its word) learned {@code key} ({@code reason}) with the senses {@code in}, the tunnel's start
     * {@code start} for a heading fact (null: none); false: not shared (the climb, no feet). The same key learned again
     * while it holds keeps its first cell (review 3: the hold's centre never walks with the bot); one that no longer holds
     * here (lifted by distance) is a fact learned anew.
     */
    public boolean learned(String key, String reason, String order, Inputs in, String start) {
        if (!shared(key) || in == null || in.pos == null || in.pos.length < 3) return false;     // structural: x y z
        String k = scoped(key, start);
        Entry e = map.get(k);
        if (e != null && e.in) return true;
        map.put(k, new Entry(new Rec(k, reason, in.pos.clone(), Failures.items(in), order, causeCell(reason))));
        return true;
    }

    public boolean learned(String key, String reason, String order, Inputs in) { return learned(key, reason, order, in, null); }

    /** Refreshes every entry on {@code in} and {@code fluid}: the causes gone are removed, the distance states moved. */
    void refresh(Inputs in, Function<int[], Boolean> fluid) {
        map.values().removeIf(e -> gone(e.rec, in, fluid));
        if (in == null || in.pos == null || in.pos.length < 3) return;         // structural: x y z; feet unknown: states kept
        for (Entry e : map.values()) {
            if (stripFact(e.rec.key())) continue;
            double d = Failures.moved(e.rec.cell(), in.pos);
            if (d <= Failures.movedV()) e.in = true;
            else if (d > Failures.movedV() + MARGIN) e.in = false;
        }
    }

    /** The cause is gone: a missing-item fact with the bag's total risen, or the named cause cell read not fluid. */
    static boolean gone(Rec r, Inputs in, Function<int[], Boolean> fluid) {
        if (in != null && Failures.Cause.of(r.reason()) == Failures.Cause.NO_ITEM && r.items() >= 0 && Failures.items(in) > r.items()) return true;
        if (r.cause() == null || fluid == null) return false;
        return Boolean.FALSE.equals(fluid.apply(r.cause().clone()));
    }

    /** The reason another order reads: the learner's reason, who learned it and where. */
    static String text(Rec r) {
        return r.reason() + " (a world fact: " + r.order() + " learned it at " + r.cell()[0] + " " + r.cell()[1] + " " + r.cell()[2] + ")";
    }

    @SuppressWarnings("unchecked")
    static Function<int[], Boolean> fluid(Blackboard bb) {
        return bb.fact(FLUID_FACT) instanceof Function<?, ?> f ? (Function<int[], Boolean>) f : null;
    }

    /**
     * The facts held now for an order whose own held keys are {@code own} (store key -> reason): every held fact but the
     * order's own keys - those keep the order's own rules (Goals#droppedSources; a station back once closer). By key, never
     * by the order's id: aa want of the same item again is a new order with the same id and none of its own.
     */
    public Map<String, String> heldFor(Set<String> own, Inputs in, Function<int[], Boolean> fluid) {
        refresh(in, fluid);
        Map<String, String> out = new LinkedHashMap<>();
        for (Entry e : map.values()) if (e.in && !own.contains(e.rec.key())) out.put(e.rec.key(), text(e.rec));
        return out;
    }

    public Map<String, String> heldFor(Blackboard bb, Set<String> own) { return heldFor(own, bb.in, fluid(bb)); }

    /** Every fact held now, whoever learned it (store key -> reason): the batch's trip key and an explore's end read these. */
    public Map<String, String> heldAt(Inputs in, Function<int[], Boolean> fluid) { return heldFor(Set.of(), in, fluid); }

    public Map<String, String> heldAt(Blackboard bb) { return heldAt(bb.in, fluid(bb)); }

    /**
     * The line saying that {@code key}, another order's fact, holds for {@code orderWord} here - once per order and key
     * (rule 7: the way is left out with its reason); null: said already.
     */
    public String sayOnce(String order, String orderWord, String key, String why) {
        if (!said.add(order + "|" + key)) return null;
        Entry e = map.get(key);
        // 0.39.z77 world 4 (coordinator's decision): a strip fact with no cause cell to re-read (a yield end, "no path" in the
        // tunnel, blocked at its start) holds for the run; its key is the exact tunnel, so the rest stays open
        if (stripFact(key) && (e == null || e.rec.cause() == null))
            return orderWord + ": " + key + " held as a world fact: " + why + "; held for this run: the cause cannot be re-read;"
                    + " another start or heading is open";
        if (stripFact(key))
            return orderWord + ": " + key + " held as a world fact: " + why + "; that tunnel is tried again when its cause is gone"
                    + " (the water or lava cell seen without it), from another start, or in the next run";
        return orderWord + ": " + key + " held here as a world fact: " + why + "; tried again once the bot is more than "
                + Math.round(Failures.movedV() + MARGIN) + " blocks from there (failures.moved + " + Math.round(MARGIN) + ")";
    }

    public int size() { return map.size(); }
}
