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
 * Entropy Bot 2026-11-27: the fork's GoalOnPath (fork-only class, from the fork's source at dd8235b7), over our goals
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.price.PathMemory.Cell;
import io.github.mojolowjo.entropybot.nav.search.goals.Goal;
import io.github.mojolowjo.entropybot.nav.search.goals.GoalBlock;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Entropy D11 (the re-join): the goal of a JOIN search - any of the kept path's remaining cells. {@link #isInGoal} is a set
 * lookup; {@link #heuristic} is the least of Baritone's own block heuristic ({@link GoalBlock#calculate}) over a SAMPLE of the
 * cells (the first, then every n-th, then the last; {@link #SAMPLES} at most) so the A* stays cheap on a long path (a
 * GoalComposite over hundreds of cells would ask hundreds of heuristics per node). The sample's heuristic is an estimate at or
 * above the true one for a cell between samples only by the cells' spacing - the join search is bounded by its node budget
 * anyway. Pure (JUnit: GoalOnPathTest).
 */
public final class GoalOnPath implements Goal {
    /** structural: the heuristic's sample size */
    static final int SAMPLES = 16;

    private final Set<Long> cells = new HashSet<>();
    private final List<Cell> sample = new ArrayList<>();
    private final int size;

    public GoalOnPath(List<Cell> remaining) {
        if (remaining == null || remaining.isEmpty()) {
            throw new IllegalArgumentException("a join goal needs cells");
        }
        for (Cell c : remaining) {
            cells.add(BetterBlockPos.longHash(c.x(), c.y(), c.z()));
        }
        size = remaining.size();
        int step = Math.max(1, remaining.size() / SAMPLES);
        for (int i = 0; i < remaining.size(); i += step) {
            sample.add(remaining.get(i));
        }
        Cell last = remaining.get(remaining.size() - 1);
        if (!sample.get(sample.size() - 1).equals(last)) {
            sample.add(last);
        }
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return cells.contains(BetterBlockPos.longHash(x, y, z));
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double best = Double.POSITIVE_INFINITY;
        for (Cell c : sample) {
            best = Math.min(best, GoalBlock.calculate(c.x() - x, y - c.y(), c.z() - z));
        }
        return best;
    }

    /** The cells the goal spans. */
    public int size() {
        return size;
    }

    @Override
    public String toString() {
        return "GoalOnPath{" + size + " cells, from " + sample.get(0).text() + " to " + sample.get(sample.size() - 1).text() + "}";
    }
}
