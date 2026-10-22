package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 0.29.0: the drop sweep of a {@code dig ... via baritone}. Baritone's builder never walks over what it broke, so after it
 * (done, gave up) and before a full-bag trip the step walks over the drops lying in the box or one block around it,
 * nearest first, one walk at a time, each walk at most {@link #WALK_TICKS}, the whole sweep at most {@link #MAX_WALKS}
 * walks or {@link #MAX_TICKS} ticks. Which drops count (the stand fence, room in the bag) the caller
 * decides as the plain dig does ({@link ClearJob#dropWanted}); this class picks the order and
 * keeps the cap. Game-free.
 */
public final class DropSweep {
    /** A drop lying in the world: its entity id, its cell, its distance from the bot. */
    public record Item(String id, int x, int y, int z, double d) {}

    public static int maxWalks() { return Tuning.i("dropSweep.maxWalks"); }
    public static long maxTicks() { return Tuning.l("dropSweep.maxTicks"); }  // 60 s
    public static long walkTicks() { return Tuning.l("dropSweep.walkTicks"); }  // 4 s per drop
    /** a walk that went idle this early without the drop gone has given up */
    public static long idleAfter() { return Tuning.l("dropSweep.idleAfter"); }

    public enum Kind { WALK, WAIT, DONE }

    /** What to do now: walk to {@code item}, wait for the walk, or the sweep is over. */
    public record Action(Kind kind, Item item) {
        static final Action WAIT = new Action(Kind.WAIT, null), DONE = new Action(Kind.DONE, null);
    }

    private final ClearBox area;
    private final long start;
    private final Set<String> tried = new HashSet<>();
    private Item current;
    private long currentStart;
    public int walks;
    /** drops still wanted when it ended (capped: the report says "left M drops") */
    public int left;
    public boolean capped;

    /** box: the dug box (the sweep covers it and one block around it); now: the client tick. */
    public DropSweep(ClearBox box, long now) {
        this.area = box.grow(1);
        this.start = now;
    }

    public boolean covers(int x, int y, int z) {
        return area.contains(x, y, z);
    }

    /** The drops to go for: in the area, not tried yet, nearest first. */
    public List<Item> wanted(List<Item> seen) {
        List<Item> out = new ArrayList<>();
        for (Item i : seen) if (covers(i.x(), i.y(), i.z()) && !tried.contains(i.id())) out.add(i);
        out.sort(Comparator.comparingDouble(Item::d));
        return out;
    }

    /**
     * One tick. seen: the drops the caller wants (already filtered by its rules); currentAlive: the drop it walks to is
     * still there; walkIdle: Baritone has nothing to do.
     */
    public Action tick(long now, List<Item> seen, boolean currentAlive, boolean walkIdle) {
        if (current != null) {
            long el = now - currentStart;
            if (currentAlive && el < walkTicks() && !(el > idleAfter() && walkIdle)) return Action.WAIT;
            current = null;
        }
        List<Item> w = wanted(seen);
        if (w.isEmpty()) {
            left = 0;
            return Action.DONE;
        }
        if (walks >= maxWalks() || now - start >= maxTicks()) {
            capped = true;
            left = w.size();
            return Action.DONE;
        }
        current = w.get(0);
        currentStart = now;
        tried.add(current.id());
        walks++;
        return new Action(Kind.WALK, current);
    }

    public Item current() {
        return current;
    }

    /** "picked up N items" plus ", left M drops" when the cap stopped it with drops still there. */
    public String note(int pickedItems) {
        return text(pickedItems, capped ? left : 0);
    }

    /** "picked up N items[, left M drops]" (left: drops a capped sweep left lying). */
    public static String text(int pickedItems, int left) {
        return "picked up " + pickedItems + (pickedItems == 1 ? " item" : " items") + (left > 0 ? ", left " + left + (left == 1 ? " drop" : " drops") : "");
    }
}
