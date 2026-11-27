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
 * Entropy Bot 2026-11-27: the fork's WalkWhy (fork-only class, from the fork's source at dd8235b7)
 */

package io.github.mojolowjo.entropybot.nav.price;

import java.util.List;
import java.util.Objects;

/**
 * Entropy z55 item 2 (run N 21:25:33: "no kept path from here - Baritone searches fresh" 3 s after a complete price from
 * the same feet; the fork said only "cells changed or no path" at debug): why a priced walk did not take its kept path,
 * in words. Pure (unit-tested): the kept price as it was before the walk's expiry check, where the bot stands, the first
 * path cell whose block changed since the price.
 */
public final class WalkWhy {
    private WalkWhy() {}

    /** The kept price as the walk found it: no path, complete, its segments empty, its age and keep in ticks, its start. */
    public record Kept(boolean noPath, boolean complete, boolean noSegments, long ageTicks, int keepTicks, String start) {}

    /**
     * @param kept         the kept price before the expiry check (null: none kept for this goal)
     * @param at           where the bot stands now (Baritone's path start; null: no player)
     * @param firstChanged index of the first changed path cell ({@link #firstChanged}; -1: none, or the price was expired)
     * @param cell         that cell's words ("x y z: was -> now"; "" when unknown)
     */
    public static String of(Kept kept, String at, int firstChanged, String cell) {
        if (kept == null) {
            return "none kept for this goal (never priced, or priced for another goal)";
        }
        if (kept.noPath()) {
            return "the price found no path";
        }
        if (!kept.complete() || kept.noSegments()) {
            return "the price kept no complete path";
        }
        if (kept.ageTicks() > kept.keepTicks()) {
            return "the kept path expired (" + kept.ageTicks() + " ticks old, kept " + kept.keepTicks() + ")";
        }
        if (at == null || !at.equals(kept.start())) {
            return "the bot stands at " + at + ", not at the kept path's start " + kept.start();
        }
        if (firstChanged >= 0) {
            return "a block on the kept path changed since the price" + (cell == null || cell.isEmpty() ? "" : " (" + cell + ")");
        }
        return "the kept path was not valid (no reason found)";
    }

    /** The first index where the blocks differ (a shorter list differs at its end); -1 when equal. */
    public static <T> int firstChanged(List<T> was, List<T> now) {
        int n = Math.min(was.size(), now.size());
        for (int i = 0; i < n; i++) {
            if (!Objects.equals(was.get(i), now.get(i))) {
                return i;
            }
        }
        return was.size() == now.size() ? -1 : n;
    }
}
