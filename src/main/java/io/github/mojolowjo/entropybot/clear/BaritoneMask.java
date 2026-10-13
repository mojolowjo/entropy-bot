package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.29.0 (docs/DIG_VIA_BARITONE_PLAN.md): {@code dig ... via baritone} hands the box to Baritone's builder, whose break
 * click ignores every Baritone rule (blocksToDisallowBreaking, allowBreak). So this mask is the only filter: the builder's
 * schematic asks it per cell ({@code MaskSchematic.partOfMask}) and a cell it refuses is "not part of the schematic",
 * never clicked. The rules are the plain dig's ({@link ClearEngine#clearableState} plus its hazard, liquid and ore rules)
 * and the guard's (a safe area or a protect box, through {@link Fence}).
 *
 * <p>Fail-closed: any exception while deciding a cell refuses it and is counted ({@link #errors}, the first one kept in
 * {@link #firstError}). Game-free: the adapter (commands/BuilderDig) feeds it the live {@link ClearWorld}.
 */
public final class BaritoneMask {
    /** The guard's per-cell veto: null when the cell may be dug, else why not ("safe area", "protect box home"). */
    public interface Fence {
        String refuse(int x, int y, int z);
    }

    /** What the mask says about one cell. Only {@link #DIG} is handed to the builder. */
    public enum Verdict {
        /** a block the builder may break */
        DIG,
        /** air or a liquid: nothing to break (liquids are the water round's) */
        EMPTY,
        /** not in the box */
        OUTSIDE,
        /** a block entity, a built block (unless force), Baritone's avoid list */
        PROTECTED,
        /** bedrock, barriers */
        UNBREAKABLE,
        /** in a safe area or a protect box */
        FENCED,
        /** lava, fire, magma... (ClearRules.HAZARD_RE) */
        HAZARD,
        /** water or lava above or beside it (opening it would let the liquid in) */
        WATER,
        /** an ore, left for the owner (without "ores") */
        ORE,
        /** deciding threw: refused (fail-closed) */
        ERROR
    }

    public final ClearBox box;
    public final boolean force, keepOres;
    private final Fence fence;

    /**
     * Mask calls, the cells it refused (anything but DIG and EMPTY/OUTSIDE), the exceptions. Atomic: Baritone asks
     * {@link #partOf} from its path-calculation thread while the client thread refreshes the snapshot.
     */
    public final java.util.concurrent.atomic.AtomicLong calls = new java.util.concurrent.atomic.AtomicLong(),
            refused = new java.util.concurrent.atomic.AtomicLong(), errors = new java.util.concurrent.atomic.AtomicLong();
    public volatile String firstError;

    /**
     * The cells the builder may break, as {@link #packed} cells: computed on the client thread ({@link #refresh}) and
     * swapped in whole, so {@link #partOf} never touches the world (Baritone calls it off the client thread, during A*).
     */
    private volatile Set<Long> snapshot = Set.of();

    /** The client thread: the DIG cells of the box now ({@link #digCells}), swapped in. Returns how many. */
    public int refresh(ClearWorld w) {
        long[] ref = new long[1];
        Set<Long> s = digCells(w, ref);
        snapshot = Set.copyOf(s);
        refused.set(ref[0]);
        return s.size();
    }

    public Set<Long> snapshot() {
        return snapshot;
    }

    /**
     * Baritone's question from any thread: is the cell at origin (ox, oy, oz) + (x, y, z) in the snapshot? Never
     * throws (anything odd: false, counted).
     */
    public boolean partOf(int ox, int oy, int oz, int x, int y, int z) {
        try {
            calls.incrementAndGet();
            return snapshot.contains(packed(ox + x, oy + y, oz + z));
        } catch (Throwable t) {
            errors.incrementAndGet();
            if (firstError == null) firstError = t.toString();
            return false;
        }
    }

    /** The size of the air fill over the box: {x2-x1+1, y2-y1+1, z2-z1+1}. */
    public static int[] size(ClearBox b) {
        return new int[]{b.x2() - b.x1() + 1, b.y2() - b.y1() + 1, b.z2() - b.z1() + 1};
    }

    public BaritoneMask(ClearBox box, boolean force, boolean keepOres, Fence fence) {
        this.box = box;
        this.force = force;
        this.keepOres = keepOres;
        this.fence = fence;
    }

    /** The builder's question: may it break x y z (absolute coordinates)? Never throws. */
    public boolean allows(ClearWorld w, int x, int y, int z) {
        calls.incrementAndGet();
        Verdict v;
        try {
            v = verdict(w, x, y, z);
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            if (firstError == null) firstError = x + " " + y + " " + z + ": " + e;
            v = Verdict.ERROR;
        }
        if (v != Verdict.DIG && v != Verdict.EMPTY && v != Verdict.OUTSIDE) refused.incrementAndGet();
        return v == Verdict.DIG;
    }

    /** The rule for one cell (may throw on a broken world; {@link #allows} catches). */
    public Verdict verdict(ClearWorld w, int x, int y, int z) {
        if (!box.contains(x, y, z)) return Verdict.OUTSIDE;
        if (w.air(x, y, z)) return Verdict.EMPTY;
        if (w.fluid(x, y, z)) return liquidBlock(w, x, y, z) ? Verdict.EMPTY : Verdict.WATER;      // waterlogged: a block stands there
        if (w.unbreakable(x, y, z)) return Verdict.UNBREAKABLE;
        if (w.blockEntity(x, y, z)) return Verdict.PROTECTED;            // never, force or not
        if (w.builtBlock(x, y, z) && !force) return Verdict.PROTECTED;
        if (w.avoided(x, y, z)) return Verdict.PROTECTED;
        if (fence != null && fence.refuse(x, y, z) != null) return Verdict.FENCED;
        if (ClearRules.hazard(w.name(x, y, z))) return Verdict.HAZARD;
        if (ClearEngine.nextToLiquid(w, x, y, z)) return Verdict.WATER;
        if (keepOres && w.ore(x, y, z)) return Verdict.ORE;
        return Verdict.DIG;
    }

    /** A plain liquid block (water, lava, a mod fluid), not a waterlogged block: its name is the fluid's. */
    public static boolean liquidBlock(ClearWorld w, int x, int y, int z) {
        String n = w.name(x, y, z), k = w.fluidKind(x, y, z);
        if (n.equals("water") || n.equals("lava")) return true;
        return k != null && (n.equals(k) || n.equals(ClearRules.blockName(k)));
    }

    /** The liquid cell above or beside x y z (the order of {@link ClearEngine#nextToLiquid}), or null. */
    public static Pos liquidNext(ClearWorld w, int x, int y, int z) {
        int[][] sides = { { 0, 1, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
        for (int[] s : sides) {
            if (w.fluid(x + s[0], y + s[1], z + s[2])) return new Pos(x + s[0], y + s[1], z + s[2]);
        }
        return null;
    }

    /** What the box still holds, by verdict, with a few example cells each (the end report). */
    public static final class Leftover {
        public final Map<Verdict, Integer> counts = new EnumMap<>(Verdict.class);
        public final Map<Verdict, List<Pos>> examples = new EnumMap<>(Verdict.class);
        /** the liquid next to the first WATER cell (what "blocked by water at x y z" names), or null */
        public Pos liquid;
        public String liquidKind;
        /** the WATER cells (for the water round's skip list), at most {@link #MAX_WATER} */
        public final Map<String, String> waterCells = new LinkedHashMap<>();
        public static final int MAX_WATER = 200;
        public static final int MAX_EXAMPLES = 3;

        public int count(Verdict v) {
            return counts.getOrDefault(v, 0);
        }

        /** Blocks the builder could still have broken: DIG left over (it did not reach them). */
        public int unreachable() {
            return count(Verdict.DIG);
        }

        void add(Verdict v, int x, int y, int z) {
            counts.merge(v, 1, Integer::sum);
            List<Pos> l = examples.computeIfAbsent(v, k -> new ArrayList<>());
            if (l.size() < MAX_EXAMPLES) l.add(new Pos(x, y, z));
        }
    }

    /** The end re-scan: every non-empty cell of the box by verdict. Exceptions count as ERROR (and in {@link #errors}). */
    public Leftover leftover(ClearWorld w) {
        Leftover out = new Leftover();
        for (int y = box.y2(); y >= box.y1(); y--) {
            for (int x = box.x1(); x <= box.x2(); x++) {
                for (int z = box.z1(); z <= box.z2(); z++) {
                    Verdict v;
                    try {
                        v = verdict(w, x, y, z);
                    } catch (RuntimeException e) {
                        errors.incrementAndGet();
                        if (firstError == null) firstError = x + " " + y + " " + z + ": " + e;
                        v = Verdict.ERROR;
                    }
                    if (v == Verdict.EMPTY || v == Verdict.OUTSIDE) continue;
                    out.add(v, x, y, z);
                    if (v == Verdict.WATER) {
                        if (out.waterCells.size() < Leftover.MAX_WATER) out.waterCells.put(Pos.key(x, y, z), ClearEngine.NEXT_TO_LIQUID);
                        if (out.liquid == null) {
                            try {
                                out.liquid = liquidNext(w, x, y, z);
                                if (out.liquid == null && w.fluid(x, y, z)) out.liquid = new Pos(x, y, z);     // waterlogged itself
                                if (out.liquid != null) out.liquidKind = w.fluidKind(out.liquid.x(), out.liquid.y(), out.liquid.z());
                            } catch (RuntimeException ignored) {}
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The cells the builder may break now ({@link Verdict#DIG}), as {@link #packed} cells (the start snapshot the count uses). */
    public Set<Long> digCells(ClearWorld w) {
        return digCells(w, new long[1]);
    }

    private Set<Long> digCells(ClearWorld w, long[] refusedOut) {
        Set<Long> s = new java.util.HashSet<>();
        for (int y = box.y1(); y <= box.y2(); y++)
            for (int x = box.x1(); x <= box.x2(); x++)
                for (int z = box.z1(); z <= box.z2(); z++) {
                    Verdict v;
                    try {
                        v = verdict(w, x, y, z);
                    } catch (RuntimeException e) {
                        errors.incrementAndGet();
                        if (firstError == null) firstError = x + " " + y + " " + z + ": " + e;
                        v = Verdict.ERROR;
                    }
                    if (v == Verdict.DIG) s.add(packed(x, y, z));
                    else if (v != Verdict.EMPTY && v != Verdict.OUTSIDE) refusedOut[0]++;
                }
        return s;
    }

    /** A cell as one long (26 bits x, 26 bits z, 12 bits y), for the start snapshot. */
    public static long packed(int x, int y, int z) {
        return ((long) (x & 0x3ffffff) << 38) | ((long) (z & 0x3ffffff) << 12) | (y & 0xfff);
    }

    /** The counters for status and debug lines: "mask calls N, refused K, errors E". */
    public String counters() {
        long e = errors.get();
        return "mask calls " + calls.get() + ", refused " + refused.get() + (e > 0 ? ", mask errors " + e : "");
    }
}
