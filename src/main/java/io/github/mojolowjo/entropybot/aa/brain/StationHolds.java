package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.NeedChain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 0.39.z78 J5 slice 1 (rule 23: AA holds every fact; to-do/j5-knowledge-audit.md D1, D8, B1; run DU 16:29:01-05, run DT
 * 16:19:41): the ONE store of the stations held out - was five: the order's sources and the world facts (a walk to the
 * station that failed), the craft's session refusals (a table that answered no click, a station whose use the fork gave up
 * on), and the brain's Failures. Every reader reads it: the chain (Goals.chainWorld: held keys "station at x y z", listed in
 * the dropped ways), the station lookup the craft and the "table near" fact use (Crafting.knownStation skips a held one),
 * the stations line (each held one said with its order and reason), the trip key, aa why and the explore end.
 * <ul>
 * <li>{@link #walkFailed}: the walk to the station ended short with the station out of reach - held for every order (a
 * world fact, named by the order that learned it), back once the bot stands failures.moved + the 8-block band closer to it
 * than where the walk failed, held again within failures.moved closer (the band WorldFacts and the order holds used).</li>
 * <li>{@link #refused}: the station never opened / its use was given up on from every stand - held for every order while
 * that block stands there (a re-placed block is another station: its block id keys it, as the refusal sets did).</li>
 * </ul>
 * A walk not needed (the station already in reach) holds nothing (StepHands.toStation). In memory per world (reset with
 * the world). Pure (JUnit: J5StationHoldsTest). Loader notes: none here; the block at a cell comes from the game side.
 */
public final class StationHolds {
    public static final StationHolds INSTANCE = new StationHolds();

    /**
     * One held station: its cell, the order that learned it (null: a refusal, for every order), the reason, the feet when
     * the walk failed (null: a refusal), the block id at the cell when refused (null: a walk hold), the lift state.
     */
    public record Hold(int x, int y, int z, String order, String reason, int[] from, String block) {
        public Hold {
            from = from == null ? null : from.clone();
        }

        public String key() { return NeedChain.stationKey(x, y, z); }

        /** "held for &lt;order&gt;: &lt;reason&gt;" / "refused for every order: &lt;reason&gt;" - how every reader says it (rule 7). */
        public String said() { return (order == null ? "refused for every order" : "held for " + order) + ": " + reason; }
    }

    private final Map<String, Hold> holds = new LinkedHashMap<>();
    /** The walk holds the bot stands far enough closer to now (the band's lifted state, by key). */
    private final java.util.Set<String> back = new java.util.HashSet<>();

    /** The walk to the station at x y z ended short from {@code feet} with it out of reach ({@code order}: who learned it). */
    public synchronized void walkFailed(String order, int x, int y, int z, String reason, int[] feet) {
        Hold h = new Hold(x, y, z, order, reason, feet, null);
        holds.put(h.key(), h);
        back.remove(h.key());
    }

    /** The station at x y z ({@code block}: its block id now) is refused for every order: it never opened / its use was given up. */
    public synchronized void refused(int x, int y, int z, String block, String reason) {
        Hold h = new Hold(x, y, z, null, reason, null, block);
        holds.put(h.key(), h);
        back.remove(h.key());
    }

    /**
     * The holds that hold now, key -&gt; {@link Hold#said} - from {@code feet} (null: not known, every walk hold held, its state
     * kept), {@code blockAt}: the block id at a cell now (null answer: not known, the refusal held - rule 15; a refusal whose
     * block changed is gone). Updates the lift state.
     */
    public synchronized Map<String, String> heldAt(int[] feet, Function<int[], String> blockAt) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var it = holds.values().iterator(); it.hasNext(); ) {
            Hold h = it.next();
            if (h.block() != null) {
                String now = blockAt == null ? null : blockAt.apply(new int[]{h.x(), h.y(), h.z()});
                if (now != null && !now.equals(h.block())) { it.remove(); continue; }     // another block there: another station
            } else if (feet != null && h.from() != null) {
                int[] at = {h.x(), h.y(), h.z()};
                double gain = Failures.moved(h.from(), at) - Failures.moved(feet, at);
                if (gain >= Failures.movedV() + WorldFacts.MARGIN) back.add(h.key());
                else if (gain < Failures.movedV()) back.remove(h.key());
            }
            if (!back.contains(h.key())) out.put(h.key(), h.said());
        }
        return out;
    }

    /** The hold of the station at x y z now ({@link #heldAt}'s rules), null: not held. */
    public synchronized String held(int x, int y, int z, int[] feet, Function<int[], String> blockAt) {
        return heldAt(feet, blockAt).get(NeedChain.stationKey(x, y, z));
    }

    /** aa why: "station at x y z: held for &lt;order&gt;: &lt;reason&gt;" per hold holding now. */
    public synchronized List<String> lines(int[] feet, Function<int[], String> blockAt) {
        List<String> out = new ArrayList<>();
        for (var e : heldAt(feet, blockAt).entrySet()) out.add(e.getKey() + ": " + e.getValue());
        return out;
    }

    /**
     * The stations line's part (rule 7: a held station is shown as held wherever it is listed): " | held: x y z held for
     * &lt;order&gt;: &lt;reason&gt;; ..." for each of {@code spots} held now; "" when none is.
     */
    public synchronized String note(List<NeedChain.Spot> spots, int[] feet, Function<int[], String> blockAt) {
        if (spots == null || spots.isEmpty() || holds.isEmpty()) return "";
        Map<String, String> now = heldAt(feet, blockAt);
        List<String> said = new ArrayList<>();
        for (NeedChain.Spot s : spots) {
            String h = now.get(NeedChain.stationKey(s.x(), s.y(), s.z()));
            if (h != null) said.add(s.x() + " " + s.y() + " " + s.z() + " " + h);
        }
        return said.isEmpty() ? "" : " | held: " + String.join("; ", said);
    }

    /** A new world (or tests): every hold forgotten. */
    public synchronized void reset() {
        holds.clear();
        back.clear();
    }
}
