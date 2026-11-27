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
 * Modified for Entropy Bot 2026-11-27: moved to the nav package, JUnit 4 to 5
 */

package io.github.mojolowjo.entropybot.nav.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PathingBlockTypeTest {

    @Test
    public void testBits() {
        for (PathingBlockType type : PathingBlockType.values()) {
            boolean[] bits = type.getBits();
            assertTrue(type == PathingBlockType.fromBits(bits[0], bits[1]));
        }
    }
}
