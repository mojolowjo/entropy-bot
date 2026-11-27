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
 * Entropy Bot 2026-11-27 (nav step 8): InventoryBehavior's choices (bestToolAgainst, throwaway) without its item name
 * lists: the tool by the game's own break speed and drop rule, the block by its shape.
 */

package io.github.mojolowjo.entropybot.nav.drive;

import java.util.List;

/**
 * Which bag slot goes into the hand: for a break, the fastest tool that gets the block's drops (else the fastest at all;
 * the game's {@code ItemStack.getDestroySpeed} and {@code isCorrectToolForDrops} give the facts); for a place, the
 * selected stack when it places a full block, else the full-block stack with the most items. A hotbar slot wins a tie
 * (no swap), the selected slot wins over both. The fork's throwaway list (dirt, cobblestone, netherrack, stone) is a
 * name list and is not kept (NoNameListsTest); the bag ledger's reserves are the pilot's (step 9). Pure (JUnit:
 * HotbarChoiceTest). Loader notes: none here; {@link DriveLive} reads the facts with vanilla ItemStack calls.
 */
public final class HotbarChoice {
    private HotbarChoice() {}

    /**
     * One bag slot's facts against the target block.
     *
     * @param index    inventory index, 0-8 the hotbar
     * @param count    items in the stack
     * @param speed    the stack's break speed on the target (1 for a hand)
     * @param correct  the stack gets the target's drops (or the block drops without a tool)
     * @param fullBlock the stack places a full solid block
     */
    public record Slot(int index, int count, float speed, boolean correct, boolean fullBlock) {}

    /**
     * The slot to break with, or -1 when the hand as it is does as well as anything in the bag. The bag lists the
     * selected slot even when it is empty (count 0, speed 1, correct when the block drops without a tool).
     */
    public static int tool(List<Slot> bag, int selected) {
        Slot hand = find(bag, selected);
        Slot best = hand == null ? new Slot(selected, 0, 1f, false, false) : hand;
        for (Slot s : bag) {
            if (s.count() > 0 && better(s, best, selected)) {
                best = s;
            }
        }
        return best.index() == selected ? -1 : best.index();
    }

    /** The slot to place from, or -1 when the bag has no full block. The selected stack stays when it is one. */
    public static int block(List<Slot> bag, int selected) {
        Slot hand = find(bag, selected);
        if (hand != null && hand.fullBlock()) {
            return selected;
        }
        Slot best = null;
        for (Slot s : bag) {
            if (!s.fullBlock()) {
                continue;
            }
            if (best == null || s.count() > best.count() || (s.count() == best.count() && hotbar(s) && !hotbar(best))) {
                best = s;
            }
        }
        return best == null ? -1 : best.index();
    }

    private static boolean better(Slot a, Slot b, int selected) {
        if (a.correct() != b.correct()) {
            return a.correct();
        }
        if (a.speed() != b.speed()) {
            return a.speed() > b.speed();
        }
        if ((a.index() == selected) != (b.index() == selected)) {
            return a.index() == selected;
        }
        return hotbar(a) && !hotbar(b);
    }

    private static boolean hotbar(Slot s) {
        return s.index() < 9;                                              // structural: the hotbar is inventory slots 0-8
    }

    private static Slot find(List<Slot> bag, int index) {
        for (Slot s : bag) {
            if (s.index() == index) {
                return s;
            }
        }
        return null;
    }
}
