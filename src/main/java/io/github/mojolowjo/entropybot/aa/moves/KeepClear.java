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
 *     its heading, feet and head height, out to the placing radius plus one.</li>
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

    private static void add(List<int[]> out, int x, int y, int z) {
        for (int[] c : out) if (c[0] == x && c[1] == y && c[2] == z) return;
        out.add(new int[]{x, y, z});
    }
}
