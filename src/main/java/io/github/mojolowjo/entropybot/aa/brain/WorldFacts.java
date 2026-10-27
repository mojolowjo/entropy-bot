package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Inputs;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 0.39.z77 world (manager's ruling 2026-10-26 ~23:00; run CX 22:42:18 -> 22:43:18: the pickaxe's strip north at y 16 met
 * "water ahead at 18 16 -23", the pickaxe order was met one second later and its held sources (Goals.Goal#sources, one
 * Failures per order) went off the goal list with it, so the helmet ran the same strip to the same water; run CS
 * 19:33-19:37: each order took the same seen ore 20 28 19 to the same no-progress): a source fact one order learned - water
 * or lava ahead on a heading at a Y, no path from a cell, no progress at a source, an unreachable spot - is a fact about the
 * WORLD, not about the order. Kept here by its source key with the cell the feet stood on when it was learned and the
 * order that learned it; every other order's chain (Goals.chainWorld) prices without it while the feet are within
 * failures.moved of that cell - the batch's open orders and later orders of the run (the AA's blackboard).
 *
 * <p>Lifted as the order holds are: the feet more than failures.moved from the cell (a try from there is another try), or
 * the cause gone (a missing-item fact once the bag's total rose, Failures#sweep's rule). The order that learned it keeps
 * its own hold as before (Goals#droppedSources). Not shared: a fact learned with the feet not known (no world position to
 * key it by, rule 15) and the climb way (its one more try is per order, Goals#parkedTick). In memory only.
 * Pure (JUnit: Z77WorldFactsTest). Loader notes: none.
 */
public final class WorldFacts {
    /** The blackboard fact holding the run's world facts. */
    public static final String FACT = "aa.worldFacts";

    /** One fact: its source key, the learner's reason, the feet then, the bag's total then, the order that learned it. */
    public record Rec(String key, String reason, int[] cell, int items, String order) {}

    private final Map<String, Rec> map = new LinkedHashMap<>();
    /** "order id|key" said once: the line that another order's fact holds here. */
    private final Set<String> said = new HashSet<>();

    /** The run's world facts on {@code bb} (made on the first ask). */
    public static WorldFacts of(Blackboard bb) {
        if (bb.fact(FACT) instanceof WorldFacts w) return w;
        WorldFacts w = new WorldFacts();
        bb.fact(FACT, w);
        return w;
    }

    /** The key is a world fact (shared): every source key but the climb way's. */
    static boolean shared(String key) {
        return key != null && !key.isBlank() && !io.github.mojolowjo.entropybot.aa.NeedChain.CLIMB_KEY.equals(key);
    }

    /** {@code order} (its word) learned {@code key} ({@code reason}) with the senses {@code in}; false: not shared (the climb, or no feet). */
    public boolean learned(String key, String reason, String order, Inputs in) {
        if (!shared(key) || in == null || in.pos == null || in.pos.length < 3) return false;     // structural: x y z
        map.put(key, new Rec(key, reason, in.pos.clone(), Failures.items(in), order));
        return true;
    }

    /** Lifted: the feet more than failures.moved from the cell, or a missing-item fact with the bag's total risen. Feet unknown: held. */
    static boolean lifted(Rec r, Inputs in) {
        if (in == null) return false;
        if (in.pos != null && in.pos.length >= 3 && Failures.moved(r.cell(), in.pos) > Failures.movedV()) return true;     // structural: x y z
        return Failures.Cause.of(r.reason()) == Failures.Cause.NO_ITEM && r.items() >= 0 && Failures.items(in) > r.items();
    }

    /** The reason another order reads: the learner's reason, who learned it and where. */
    static String text(Rec r) {
        return r.reason() + " (a world fact: " + r.order() + " learned it at " + r.cell()[0] + " " + r.cell()[1] + " " + r.cell()[2] + ")";
    }

    /**
     * The facts held now for an order whose own held keys are {@code own} (key -> reason): every held fact but the order's
     * own keys - those keep the order's own rules (Goals#droppedSources; a station back once closer). By key, never by the
     * order's id: aa want of the same item again is a new order with the same id and none of its own.
     */
    public Map<String, String> heldFor(Set<String> own, Inputs in) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Rec r : map.values()) if (!own.contains(r.key()) && !lifted(r, in)) out.put(r.key(), text(r));
        return out;
    }

    /** Every fact held now, whoever learned it (key -> reason): the batch's trip key and an explore's end read these. */
    public Map<String, String> heldAt(Inputs in) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Rec r : map.values()) if (!lifted(r, in)) out.put(r.key(), text(r));
        return out;
    }

    /**
     * The line saying that {@code key}, another order's fact, holds for {@code orderWord} here - once per order and key
     * (rule 7: the way is left out with its reason); null: said already.
     */
    public String sayOnce(String order, String orderWord, String key, String why) {
        if (!said.add(order + "|" + key)) return null;
        return orderWord + ": " + key + " held here as a world fact: " + why + "; tried again once the bot is more than "
                + Math.round(Failures.movedV()) + " blocks from there (failures.moved)";
    }

    public int size() { return map.size(); }
}
