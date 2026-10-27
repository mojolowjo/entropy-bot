package io.github.mojolowjo.entropybot.aa.moves;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 0.39.z62 item 1a (run S 04:57:21-30: the re-plan placed the new crafting table at -219 16 33 beside the bot at -220 16 33,
 * then "strip east feet y 16: 0 blocks ... no path" - the table stood on the tunnel's first cell): the cells a station placed
 * now must keep free because a later step of the chain is known to use them. A CONSTRAINT handed to Baritone's placement
 * (the leg's cell check refuses them); Baritone still picks the cell, the stand, the face and the click.
 * <ul>
 * <li>the next walk ({@link Step.Walk}, {@link Step.ToStrip}): its kept path's first cells near the feet (feet and head
 *     height), from {@code walkCells} (the fork's kept price for that goal; none known: nothing kept for it);</li>
 * <li>a strip that starts at the feet ({@link Step.Strip} at the feet's Y, no walk before it): its tunnel's first cells in
 *     its heading, feet and head height, out to the placing radius plus one;</li>
 * <li>0.39.z77 craft: my stations near the feet that a later craft or smelt step uses ({@link #stations}).</li>
 * </ul>
 * The scan stops at the first walk or strip (after it the bot stands elsewhere). Pure (JUnit: Z62RunSTest).
 * Loader notes: none.
 */
public final class KeepClear {
    private KeepClear() {}

    /**
     * @param steps     the run's steps; {@code from}: the first step after the placement
     * @param feet      the bot's feet now {x, y, z}
     * @param radius    the placing radius (steps.placeNearR)
     * @param walkCells a walk's goal {x, y, z} -> its kept path's cells (feet positions, in walk order); null or empty: none known
     */
    public static List<int[]> cells(List<? extends Step> steps, int from, int[] feet, int radius, Function<int[], List<int[]>> walkCells) {
        List<int[]> out = new ArrayList<>();
        if (steps == null || feet == null) return out;
        int reach = Math.max(1, radius) + 1;       // structural: one cell past the placing radius
        for (int i = Math.max(0, from); i < steps.size(); i++) {
            Step s = steps.get(i);
            int[] goal = s instanceof Step.Walk w ? new int[]{w.x(), w.y(), w.z()} : s instanceof Step.ToStrip t ? new int[]{t.x(), t.y(), t.z()}
                    : s instanceof Step.ToStation st ? new int[]{st.x(), st.y(), st.z()}      // 0.39.z65: the station walk too
                    : s instanceof Step.ToSurface su ? new int[]{su.x(), su.y(), su.z()} : null;      // 0.39.z68: the walk up too
            if (goal != null) {
                List<int[]> path = walkCells == null ? null : walkCells.apply(goal);
                if (path != null) for (int[] c : path) {
                    if (Math.abs(c[0] - feet[0]) > reach || Math.abs(c[1] - feet[1]) > reach || Math.abs(c[2] - feet[2]) > reach) break;
                    add(out, c[0], c[1], c[2]);
                    add(out, c[0], c[1] + 1, c[2]);          // structural: the head cell over the feet cell
                }
                return out;
            }
            if (s instanceof Step.Strip st) {
                if (st.y() == feet[1]) {
                    int[] d = io.github.mojolowjo.entropybot.aa.WayUp.dir(st.heading());
                    for (int k = 1; k <= reach; k++) {
                        add(out, feet[0] + d[0] * k, feet[1], feet[2] + d[1] * k);
                        add(out, feet[0] + d[0] * k, feet[1] + 1, feet[2] + d[1] * k);   // structural: the head cell
                    }
                }
                return out;
            }
        }
        return out;
    }

    /**
     * 0.39.z77 craft (run DA 00:35:52-00:37:17: the furnace's place-near had no free cell in the y 16 tunnel, and the
     * fork's room pass cleared the cell nearest the feet, which held the crafting table the chain had just placed and
     * priced "craft oak_slab 12" on. The craft then walked 77 m to the only other table): my stations within reach
     * (as {@link #cells}: one cell past the placing radius) whose block a station step after the placement uses
     * ({@link StepWait#kindOf}: craft -> the crafting kind, smelt -> the smelting kind). This applies until the first
     * walk or strip (after it the bot stands elsewhere). It is a CONSTRAINT for Baritone's pick (the leg's cell check
     * refuses them), so the fork still picks the cell. A kind whose blocks are not loaded ({@code blocksOfKind} null)
     * keeps every station of mine within reach. A wrongly kept cell only makes the placement fail loudly and re-plan,
     * while a wrongly broken station costs the walk. Pure (JUnit: Z77CraftKeepsTableTest).
     * @param blocksOfKind a recipe kind -> its station blocks (null: not loaded)
     * @param mine         my recorded stations ({@code OwnStations.placed()})
     */
    public static List<int[]> stations(List<? extends Step> steps, int from, int[] feet, int radius,
                                       Function<String, java.util.Set<String>> blocksOfKind, List<io.github.mojolowjo.entropybot.clear.OwnStations.Placed> mine) {
        List<int[]> out = new ArrayList<>();
        if (steps == null || feet == null || mine == null || mine.isEmpty()) return out;
        java.util.Set<String> used = new java.util.HashSet<>();
        boolean any = false;          // a station step whose kind has no loaded blocks: keep every station of mine
        for (int i = Math.max(0, from); i < steps.size(); i++) {
            Step s = steps.get(i);
            if (leaves(s)) break;
            String kind = StepWait.kindOf(s);
            if (kind == null) continue;
            java.util.Set<String> ids = blocksOfKind == null ? null : blocksOfKind.apply(kind);
            if (ids == null) any = true;
            else used.addAll(ids);
        }
        if (!any && used.isEmpty()) return out;
        int reach = Math.max(1, radius) + 1;       // structural: one cell past the placing radius, as cells()
        for (var m : mine) {
            if (m == null) continue;
            if (Math.abs(m.x() - feet[0]) > reach || Math.abs(m.y() - feet[1]) > reach || Math.abs(m.z() - feet[2]) > reach) continue;
            if (any || used.contains(m.block())) add(out, m.x(), m.y(), m.z());
        }
        return out;
    }

    /** The bot stands elsewhere after this step (a walk or a strip): the scans stop here. */
    private static boolean leaves(Step s) {
        return s instanceof Step.Walk || s instanceof Step.ToStrip || s instanceof Step.ToStation || s instanceof Step.ToSurface || s instanceof Step.Strip;
    }

    private static void add(List<int[]> out, int x, int y, int z) {
        for (int[] c : out) if (c[0] == x && c[1] == y && c[2] == z) return;
        out.add(new int[]{x, y, z});
    }
}
