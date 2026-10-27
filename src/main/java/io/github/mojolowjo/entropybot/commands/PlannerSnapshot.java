package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.PathPrice;
import io.github.mojolowjo.entropybot.aa.PlanNodes;
import io.github.mojolowjo.entropybot.aa.brain.GenWhere;
import io.github.mojolowjo.entropybot.aa.brain.StripAreas;
import io.github.mojolowjo.entropybot.aa.brain.ToolUses;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.39.z78 D6 (design "the snapshot is today's NeedChain.World, frozen"): the facts the planner worker resolves from,
 * copied on the client tick. {@link #take}: every fact the request's last resolve read (its record, NeedChain.Read) is
 * asked of the live World now - the seen sources, the walks (the path asks are posted here, on the client tick), the
 * strips, the caves, the stations, the uses, the "where" facts, the strip areas and the main stations - and the World's
 * plain facts (the bag in its live order, the stations near, the holds, the rooms, the horizon) are copied. The frozen
 * World answers from these copies only: a fact not in the snapshot does not exist for the planner - it answers "not
 * known" (a walk computing, no source seen, no strip, ...), the read is recorded like any other, and the result's check
 * on the client tick finds it differs from the live answer, so that result is never applied; the next snapshot carries it.
 * {@code version}: the port's request counter; {@code takenAt}: the tick. No Minecraft types (JUnit: D6WorkerTest).
 * Loader notes: none.
 */
public final class PlannerSnapshot {
    private final NeedChain.World world;
    private final LinkedHashMap<String, Integer> bag;
    private final NeedChain.Extras extras;
    private final Map<PlanNodes.Fact, Object> answers;
    private final long version, takenAt;

    private PlannerSnapshot(NeedChain.World world, LinkedHashMap<String, Integer> bag, NeedChain.Extras extras, Map<PlanNodes.Fact, Object> answers,
                            long version, long takenAt) {
        this.world = world;
        this.bag = bag;
        this.extras = extras;
        this.answers = answers;
        this.version = version;
        this.takenAt = takenAt;
    }

    public NeedChain.World world() { return world; }

    /** The live World's bag in its live order (NeedChain.initialBag on the worker reads this). */
    public LinkedHashMap<String, Integer> bag() { return new LinkedHashMap<>(bag); }

    public NeedChain.Extras extras() { return extras; }

    public long version() { return version; }

    public long takenAt() { return takenAt; }

    /** The fact was copied into this snapshot (a fact read that was not is a "not known" answer, a miss). */
    public boolean copied(PlanNodes.Fact f) { return answers.containsKey(f); }

    /** The facts copied. */
    public Set<PlanNodes.Fact> facts() { return answers.keySet(); }

    /**
     * The snapshot of {@code live} for {@code keys} (the facts of the request's last result; none: the first resolve),
     * {@code known}: answers already asked of {@code live} this tick (the result's check), reused. Client tick.
     */
    public static PlannerSnapshot take(NeedChain.World live, Collection<PlanNodes.Fact> keys, Map<PlanNodes.Fact, Object> known, long version, long takenAt) {
        Map<PlanNodes.Fact, Object> a = new HashMap<>();
        for (PlanNodes.Fact f : keys) a.put(f, known != null && known.containsKey(f) ? known.get(f) : NeedChain.ask(live, null, f));
        NeedChain.World w = new NeedChain.World(live.bag(), live.tableNear(), live.furnaceNear(),
                word -> list(a, new PlanNodes.Fact("seen", word)),
                type -> list(a, new PlanNodes.Fact("mobs", type)),
                live.loot(),
                word -> (int[]) get(a, new PlanNodes.Fact("band", word), null),
                live.horizonBlocks(),
                live.path() == null ? null : new FrozenPath(a),
                kind -> (NeedChain.Spot) get(a, new PlanNodes.Fact("station", kind), null),
                id -> (Integer) get(a, new PlanNodes.Fact("uses", id), ToolUses.UNLIMITED),
                live.held(),
                word -> (NeedChain.StripBand) get(a, new PlanNodes.Fact("strip", word), null),
                word -> (NeedChain.CaveWay) get(a, new PlanNodes.Fact("cave", word), null),
                live.rooms(),
                new NeedChain.WhereFacts(word -> (GenWhere.Fact) get(a, new PlanNodes.Fact("where", word), null),
                        () -> (NeedChain.Surface) get(a, new PlanNodes.Fact("surface", null), null),
                        () -> { int[] f = (int[]) get(a, new PlanNodes.Fact("feet", null), null); return f == null ? null : f.clone(); },
                        () -> (GenWhere.Base) get(a, new PlanNodes.Fact("base", null), null)));
        return new PlannerSnapshot(w, NeedChain.liveBag(live), new FrozenExtras(a), Map.copyOf(withNulls(a)), version, takenAt);
    }

    /** Map.copyOf refuses nulls: the copied answers with each null kept as a marker. */
    private static Map<PlanNodes.Fact, Object> withNulls(Map<PlanNodes.Fact, Object> a) {
        Map<PlanNodes.Fact, Object> out = new HashMap<>();
        for (var e : a.entrySet()) out.put(e.getKey(), e.getValue() == null ? NULL : e.getValue());
        return out;
    }

    private static final Object NULL = new Object();

    /** The copied answer of {@code f}, or {@code missing} ("not known": not in the snapshot). */
    private static Object get(Map<PlanNodes.Fact, Object> a, PlanNodes.Fact f, Object missing) {
        return a.containsKey(f) ? a.get(f) : missing;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> list(Map<PlanNodes.Fact, Object> a, PlanNodes.Fact f) {
        Object v = get(a, f, List.of());
        return v == null ? List.of() : (List<T>) v;
    }

    /** The walks as copied: a walk not asked here is "computing" (its ask is posted by the result's check on the client tick). */
    private record FrozenPath(Map<PlanNodes.Fact, Object> a) implements PathPrice {
        @Override public Answer price(int x, int y, int z, double dist) {
            return (Answer) get(a, new PlanNodes.Fact("price", new NeedChain.PathKey(x, y, z, dist, false)), Answer.computing(null));
        }

        @Override public Answer priceInto(int x, int y, int z, double dist) {
            return (Answer) get(a, new PlanNodes.Fact("price", new NeedChain.PathKey(x, y, z, dist, true)), Answer.computing(null));
        }

        @Override public double estimate(int x, int y, int z) {
            Object v = get(a, new PlanNodes.Fact("estimate", List.of(x, y, z)), Double.NaN);
            return v == null ? Double.NaN : (Double) v;
        }

        @Override public Walk toBlock(int x, int y, int z) { return (Walk) get(a, new PlanNodes.Fact("toBlock", List.of(x, y, z)), null); }

        /** The worker posts nothing: a ranked-out walk is an effect, run on the client tick when the result is applied. */
        @Override public void rankedOut(int x, int y, int z) {}

        @Override public boolean pending() { return Boolean.TRUE.equals(get(a, new PlanNodes.Fact("pending", null), false)); }

        @Override public long landed() { Object v = get(a, new PlanNodes.Fact("landed", null), 0L); return v == null ? 0 : (Long) v; }

        @Override public int firstAnswersPending() { Object v = get(a, new PlanNodes.Fact("first answers pending", null), -1); return v == null ? -1 : (Integer) v; }
    }

    /** The strip areas and the main stations as copied (not copied: no faces seen, no fresh start, not a main). */
    private record FrozenExtras(Map<PlanNodes.Fact, Object> a) implements NeedChain.Extras {
        @Override public StripAreas.Yield along(int x, int y, int z, String heading, Collection<String> ores) {
            Object v = get(a, new PlanNodes.Fact("areas along", List.of(x, y, z, heading, List.copyOf(ores))), StripAreas.Yield.NONE);
            return v == null ? StripAreas.Yield.NONE : (StripAreas.Yield) v;
        }

        @Override public int[] freshStart(int x, int y, int z, String heading) {
            int[] v = (int[]) get(a, new PlanNodes.Fact("fresh start", List.of(x, y, z, heading)), null);
            return v == null ? null : v.clone();
        }

        @Override public boolean isMain(NeedChain.Spot sp) { return Boolean.TRUE.equals(get(a, new PlanNodes.Fact("main", sp), false)); }
    }
}
