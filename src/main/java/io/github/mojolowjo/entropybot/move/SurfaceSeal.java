package io.github.mojolowjo.entropybot.move;

import io.github.mojolowjo.entropybot.camp.ShelterPlan;

import java.util.Set;
import java.util.function.Predicate;

/**
 * 0.27.3: the bot's own shelter recognised from stored cells (ShelterJob). rm-routes: the surface dig-out's rules that
 * lived here went with the walk's dig-out (a walk is one Baritone goal; the way up is WayUp's priced option).
 * Loader notes: none (pure).
 */
public final class SurfaceSeal {
    private SurfaceSeal() {}

    /**
     * 0.27.3 (d3): a closed shell around feet with at least one own cell (an underground pit closed by a roof and one
     * wall of mine). own: the stored cells ("x y z").
     */
    public static boolean inOwnShelterLoose(int[] feet, Set<String> own, Predicate<int[]> closed) {
        if (feet == null || own == null || own.isEmpty()) return false;
        for (int[] c : ShelterPlan.allCells(feet)) if (!closed.test(c)) return false;
        return ShelterPlan.anyOwn(feet, own);
    }
}
