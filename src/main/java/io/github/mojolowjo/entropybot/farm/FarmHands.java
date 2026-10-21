package io.github.mojolowjo.entropybot.farm;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.aa.moves.JobHands;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.List;

/**
 * The farm round's hands (rm-final: no click or camera of the mod's own): each crop to break, ripe crop to right-click or
 * seed to plant is one request to Baritone's job hands ({@link JobHands}: Baritone looks, stands and clicks), run one at a
 * time in the order the round asked, followed every farm step by {@link #pump}. A failed request is named in the log with
 * its stage and the guard's verdict (JobHands' why); the round sees the crop still ripe / the cell still empty and counts
 * its own tries. Client thread only.
 */
public final class FarmHands {
    private FarmHands() {}

    private static final Logger LOG = LogUtils.getLogger();

    /** One request: {@code dig} breaks the cell, else right-clicks it with {@code item} (null = the hand); {@code before} runs once as it starts (the hotbar; false = skip this one), {@code after} once as it ends (the lease). */
    public record Req(boolean dig, int[] cell, String item, String who, java.util.function.BooleanSupplier before, Runnable after) {}

    private static final ArrayDeque<Req> QUEUE = new ArrayDeque<>();
    private static Req current;
    private static boolean started;

    public static synchronized void submit(Req r) { QUEUE.add(r); }

    public static synchronized boolean busy() { return current != null || !QUEUE.isEmpty(); }

    /** Ends everything asked and not done (a new round, a failed round): the hands stop, the leases go. */
    public static synchronized void clear(String why) {
        if (current != null) {
            JobHands.get().stop(why);
            end(current);
            current = null;
        }
        for (Req r : List.copyOf(QUEUE)) end(r);
        QUEUE.clear();
        started = false;
    }

    /** Once per farm step: follows the running request, starts the next one. */
    public static synchronized void pump() {
        if (current == null) {
            current = QUEUE.poll();
            started = false;
            if (current == null) return;
        }
        Req r = current;
        if (!started) {
            started = true;
            if (r.before() != null && !r.before().getAsBoolean()) {
                end(r);
                current = null;
                return;
            }
        }
        int ticks = (int) (20 * io.github.mojolowjo.entropybot.aa.brain.Tuning.get("hands.buildS"));
        JobHands.Result res = r.dig() ? JobHands.get().dig(r.cell(), ticks, r.who()) : r.item() == null ? JobHands.get().use(r.cell(), r.who()) : JobHands.get().click(r.cell(), r.item(), ticks, r.who(), null);
        if (res.running()) return;
        if (res.failed()) LOG.warn("[entropybot] farm hands: {} failed: {}", r.who(), res.why());
        end(r);
        current = null;
    }

    private static void end(Req r) {
        if (r.after() == null) return;
        try {
            r.after().run();
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] farm hands: {} end step failed: {}", r.who(), e.toString());
        }
    }
}