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
 * Entropy Bot 2026-11-27 (nav step 8): the driver's numbers in one value; the click delays are the fork's
 * (Settings.blockBreakSpeed 6 and rightClickSpeed 4, less the base delay of 1), the rest ours.
 */

package io.github.mojolowjo.entropybot.nav.drive;

/**
 * The driver's numbers. Each action ends on an observed state or on its timeout. Pure (JUnit: DriverTest).
 * Loader notes: none.
 *
 * @param maxTurn         degrees the head turns at most per tick on each axis (ours: the fork turned in one tick)
 * @param lookWithin      a look has arrived within this many degrees on both axes
 * @param lookTimeout     ticks a look may take before it fails "not reached"
 * @param walkTicksPerBlock ticks one block of walking may take (walking speed is 4.3 blocks a second, about 4.7 ticks)
 * @param stuckTicks      ticks without a tenth of a block of progress before a walk fails "stuck"
 * @param breakMargin     ticks a break may take beyond the look and the game's own break time
 * @param placeTimeout    ticks a place may take after the look has arrived
 * @param placeDelay      ticks between two right clicks (the fork's rightClickSpeed 4 less its base 1)
 */
public record DriveConfig(float maxTurn, float lookWithin, int lookTimeout, int walkTicksPerBlock, int stuckTicks,
                          int breakMargin, int placeTimeout, int placeDelay) {

    /** The defaults (the click delay the fork's, the rest ours, set on 2026-11-27 for the step 8 tests). */
    public static DriveConfig defaults() {
        return new DriveConfig(30f, 1f, 40, 10, 20, 40, 40, 3);
    }
}
