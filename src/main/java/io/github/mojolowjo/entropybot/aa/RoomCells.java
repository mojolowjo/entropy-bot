package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 0.39.z67 item 1 (run Y 09:26:14 / 09:31:16: "place crafting_table near me: no cell within 3 blocks; none to clear either"
 * while swimming at sea level, and the re-plan offered the same place-near at the same spot): the cells with room the bot
 * knows from its own feet - solid ground it stood on (on the ground, not in water, a block with a sturdy top under the feet).
 * One kept per stretch: a cell is kept when it lies more than {@code place.failedNearM} from the last one kept (so one held
 * place failure covers at most the cells near it), at most {@code place.roomsKept}, the oldest dropped. The chain prices a
 * walk to them ({@link NeedChain.World#withRooms}); Baritone still picks the placing cell there. Client thread only.
 * Pure (JUnit: RoomCellsTest). Loader notes: none here; the runtime reads vanilla Entity#onGround, Entity#isInWater and
 * BlockState#isFaceSturdy(UP) for {@link #stood}.
 */
public final class RoomCells {
    private final Deque<int[]> cells = new ArrayDeque<>();

    /** The feet {@code at} this tick; {@code solid}: on the ground, out of water, a sturdy top under the feet. */
    public void stood(int[] at, boolean solid) {
        if (!solid || at == null || at.length < 3) return;     // structural: x y z
        int[] last = cells.peekLast();
        if (last != null && NeedChain.dist3(last, at) <= NeedChain.placeFailedNearM()) return;
        cells.addLast(new int[]{at[0], at[1], at[2]});
        while (cells.size() > Math.max(1, Tuning.i("place.roomsKept"))) cells.removeFirst();
    }

    /** The kept cells as the chain's spots from {@code me} (null: not known, then none), the newest first. */
    public List<NeedChain.Spot> spots(int[] me) {
        List<NeedChain.Spot> out = new ArrayList<>();
        if (me == null) return out;
        var it = cells.descendingIterator();
        while (it.hasNext()) {
            int[] c = it.next();
            out.add(new NeedChain.Spot(c[0], c[1], c[2], NeedChain.dist3(me, c), null));
        }
        return out;
    }

    /** A new world (or session): none kept. */
    public void clear() { cells.clear(); }

    public int size() { return cells.size(); }
}
