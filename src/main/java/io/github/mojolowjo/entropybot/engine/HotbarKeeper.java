package io.github.mojolowjo.entropybot.engine;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * When the hotbar keeper makes its next swap (package B round 2, 2026-10-03; the rules for which swap are
 * {@link HotbarRules}), without Minecraft types so JUnit can pin it. Three moments, one swap at a time and at least
 * {@link #GAP} ticks apart:
 * <ul>
 * <li><b>idle</b> (no job but a walk or a wait, no reflex): after {@link #IDLE_TICKS} of quiet, as round 1 did;</li>
 * <li><b>job start</b>: for {@link #START_TICKS} after a new job shows up, the whole layout, so a mine starts with its
 *     pickaxe in the pickaxe slot;</li>
 * <li><b>refill</b>: a laid-out slot that held its item and is empty now (a pickaxe broke, the last torch was placed,
 *     the food was eaten) gets the next match from the bag while the job runs.</li>
 * </ul>
 * Never with a menu open, an item on the cursor, an item in use or during a reflex; while a job runs, never mid-swing
 * or while a block is being broken. One exception: a tool slot that emptied (the tool broke while it was used) is
 * refilled even then, as long as the swap doesn't take the item the bot is holding: the break in progress lost its
 * tool anyway, and a swap into another slot doesn't touch the hand. While a job runs, a swap that takes the held item
 * moves the selection along with it, so the bot keeps holding what it held.
 */
public final class HotbarKeeper {
    /** Ticks of quiet before an idle swap; ticks between two swaps; how long after a job starts the layout is applied. */
    public static final int IDLE_TICKS = 40, GAP = 10, START_TICKS = 200;

    /**
     * One look at the bot. busy: a job other than a walk or a wait, or a reflex (reflex: one of the reflexes holds the
     * bot). job: the running job's identity (null = none, or a walk or a wait). menu: a screen or container is open.
     * selected: the selected hotbar slot (0-8). inv: the 36 bag slots.
     */
    public record Look(long tick, boolean busy, boolean reflex, Object job, boolean menu, boolean using, boolean carried,
                       boolean swinging, boolean destroying, int selected, List<HotbarRules.Item> inv) {}

    /** The swap to make, the selected slot afterwards, and why ("idle", "job start", "refill"). */
    public record Move(HotbarRules.Swap swap, int select, String why) {}

    private long quietSince, lastSwap = Long.MIN_VALUE / 2, startUntil = Long.MIN_VALUE / 2;
    private Object lastJob;
    private Map<Integer, String> lastLayout = Map.of();
    private final boolean[] wasRight = new boolean[9];
    private final TreeSet<Integer> refill = new TreeSet<>();

    /** The laid-out slots (1-9) waiting for a refill (for tests and the log). */
    public java.util.Set<Integer> refills() { return java.util.Collections.unmodifiableSet(refill); }

    public Move decide(Map<Integer, String> layout, Look l) {
        long t = l.tick();
        if (layout == null || layout.isEmpty()) {
            lastLayout = Map.of();
            refill.clear();
            java.util.Arrays.fill(wasRight, false);
            quietSince = t;
            return null;
        }
        // a new job opens the start window; no job (or a walk) closes it
        if (l.job() == null) startUntil = Long.MIN_VALUE / 2;
        else if (!l.job().equals(lastJob)) startUntil = t + START_TICKS;
        lastJob = l.job();
        // a laid-out slot that held its item at the last look and is empty now
        boolean[] ok = HotbarRules.satisfied(layout, l.inv());
        boolean same = layout.equals(lastLayout);
        for (int k : layout.keySet()) {
            if (same && wasRight[k - 1] && HotbarRules.at(l.inv(), k - 1) == null) refill.add(k);
        }
        refill.removeIf(k -> !layout.containsKey(k) || ok[k - 1]);
        System.arraycopy(ok, 0, wasRight, 0, 9);
        if (!same) lastLayout = new TreeMap<>(layout);

        boolean blocked = l.menu() || l.using() || l.carried();
        if (!l.busy()) {
            refill.clear();                      // idle: the whole layout comes back after the quiet
            if (blocked || l.swinging() || l.destroying()) {
                quietSince = t;
                return null;
            }
            if (t - quietSince < IDLE_TICKS || t - lastSwap < GAP) return null;
            HotbarRules.Swap s = HotbarRules.plan(layout, l.inv());
            if (s == null) return null;
            lastSwap = t;
            return new Move(s, l.selected(), "idle");
        }
        quietSince = t;
        if (l.reflex() || blocked || t - lastSwap < GAP) return null;
        HotbarRules.Swap s = null;
        String why = null;
        if (!refill.isEmpty()) {
            s = HotbarRules.plan(layout, l.inv(), refill);
            why = "refill";
        }
        if (s == null && t < startUntil) {
            s = HotbarRules.plan(layout, l.inv());
            why = "job start";
            if (s == null) startUntil = Long.MIN_VALUE / 2;      // the layout is in place: the window is done
        }
        if (s == null) return null;
        if (l.swinging() || l.destroying()) {
            boolean toolBroke = "refill".equals(why) && HotbarRules.isTool(layout.get(s.to() + 1)) && s.from() != l.selected();
            if (!toolBroke) return null;
        }
        lastSwap = t;
        return new Move(s, s.from() == l.selected() ? s.to() : l.selected(), why);
    }
}
