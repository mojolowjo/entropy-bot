/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Modified for Entropy Bot 2026-11-27: built from a list of earlier positions and avoidance zones handed in, no player context
 */

package io.github.mojolowjo.entropybot.nav.search;

import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;

import java.util.List;

/**
 * Cost factors per cell for one search: cells of the previous path cost {@code backtrackCostFavoringCoefficient} times
 * as much (0.5: going back over the old path is favoured), everything else x1.
 *
 * <p>Entropy Bot: built from the previous path's positions and the coefficient, no player context; Baritone's mob and
 * spawner avoidance (which reads the live world) comes with the game side in step 8 through {@link #multiply}.
 * Pure (JUnit: SearchMatchForkTest). Loader notes: none (fastutil).
 */
public final class Favoring {

    private final Long2DoubleOpenHashMap favorings;

    public Favoring(List<BetterBlockPos> previous, double coeff) { // create one just from previous path, no mob avoidances
        favorings = new Long2DoubleOpenHashMap();
        favorings.defaultReturnValue(1.0D);
        if (coeff != 1D && previous != null) {
            previous.forEach(pos -> favorings.put(BetterBlockPos.longHash(pos), coeff));
        }
    }

    /** No previous path: every cell x1. */
    public static Favoring none() {
        return new Favoring(null, 1D);
    }

    /** Baritone's Avoidance.applySpherical: multiply this cell's factor (an avoidance zone adds its cells this way). */
    public void multiply(long hash, double factor) {
        favorings.put(hash, favorings.get(hash) * factor);
    }

    public boolean isEmpty() {
        return favorings.isEmpty();
    }

    public double calculate(long hash) {
        return favorings.get(hash);
    }
}
