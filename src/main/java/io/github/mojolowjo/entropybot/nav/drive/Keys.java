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
 * Entropy Bot 2026-11-27 (nav step 8): Baritone's InputOverrideHandler key map and PlayerMovementInput's impulse sums
 * as one value with no game types.
 */

package io.github.mojolowjo.entropybot.nav.drive;

import java.util.EnumSet;
import java.util.Set;

/**
 * The pretend keys our driver holds: which are down, and what the movement input makes of them (the sums of Baritone's
 * PlayerMovementInput.tick, which are vanilla KeyboardInput's). Sneak scales both impulses by the multiplier the game
 * passes in (vanilla's; the fork used a fixed 0.3, vanilla's own value without Swift Sneak). Pure (JUnit: KeysTest).
 * Loader notes: none here; {@link NavInput} feeds it to the game.
 */
public final class Keys {

    /** The keys (Baritone's Input enum, the clicks left out: our hands click by call, not by a held key). */
    public enum Key { FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, SPRINT }

    /** What one movement tick makes of the keys. */
    public record Impulse(float forward, float left, boolean up, boolean down, boolean leftKey, boolean rightKey,
                          boolean jumping, boolean sneaking) {}

    private final EnumSet<Key> down = EnumSet.noneOf(Key.class);

    public void press(Key k) { down.add(k); }

    public void release(Key k) { down.remove(k); }

    /** Lets every key go. */
    public void releaseAll() { down.clear(); }

    /** Sets exactly these keys down. */
    public void set(Set<Key> keys) {
        down.clear();
        down.addAll(keys);
    }

    public boolean isDown(Key k) { return down.contains(k); }

    /** True while any key that moves the body is down (Baritone's inControl without the pathing half). */
    public boolean moving() {
        for (Key k : down) {
            if (k != Key.SPRINT) {
                return true;
            }
        }
        return false;
    }

    public Set<Key> down() { return EnumSet.copyOf(down); }

    /** PlayerMovementInput.tick: forward and back cancel, left and right cancel, sneak scales both. */
    public Impulse impulse(float sneakMultiplier) {
        boolean up = isDown(Key.FORWARD), back = isDown(Key.BACK), l = isDown(Key.LEFT), r = isDown(Key.RIGHT), sneak = isDown(Key.SNEAK);
        float f = (up ? 1f : 0f) - (back ? 1f : 0f);
        float s = (l ? 1f : 0f) - (r ? 1f : 0f);
        if (sneak) {
            f *= sneakMultiplier;
            s *= sneakMultiplier;
        }
        return new Impulse(f, s, up, back, l, r, isDown(Key.JUMP), sneak);
    }

    @Override
    public String toString() { return down.isEmpty() ? "no keys" : down.toString().toLowerCase(); }
}
