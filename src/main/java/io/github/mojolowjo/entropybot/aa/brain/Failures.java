package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Inputs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AA_ROUND2 3: one failure record for the whole AA brain (was: Brain streak/coolUntil, Options.OptionMemory, the idle
 * nodes' Cooldown). A record is (desire, option): option null is the desire itself (the brain's cooldown and its
 * failures in a row, which lower P(success)); an option is a rung picker's way ("DIG_KNOWN 1 2 3", "hunt").
 *
 * <p>Clocks: an option is held {@link #RETRY_MS} x fails (at most {@link #RETRY_MAX_MS}); a desire {@link #CD_BASE_MS}
 * doubling to {@link #CD_MAX_MS} (the brain's numbers, unchanged). {@link #rest} holds a desire without counting a
 * failure (an idle task that ended done, or failed before it started). {@link #sweep} lifts a hold whose cause is gone:
 * NO_ITEM when the bag's total count rose, NO_PATH when the bot moved 16+ blocks (the fail count stays: it keeps
 * weighing). In memory only. Pure (JUnit: FailuresTest). Loader notes: none.
 */
public final class Failures {
    public static final long RETRY_MS = 10 * 60_000, RETRY_MAX_MS = 60 * 60_000, CD_BASE_MS = 120_000, CD_MAX_MS = 16 * 60_000;
    public static final double MOVED = 16;

    /** Why it failed (FailMemory's classes, folded to what the AA sweep can clear). */
    public enum Cause {
        NO_ITEM, NO_PATH, DANGER, OWNER, UNKNOWN;

        public static Cause of(String reason) {
            if (reason == null) return UNKNOWN;
            String t = reason.toLowerCase(Locale.ROOT);
            if (t.contains("stopped: your") || t.contains("overridden")) return OWNER;
            if (t.contains("attacked") || t.contains("low health") || t.contains("died")) return DANGER;
            io.github.mojolowjo.entropybot.brain.FailMemory.Reason r = io.github.mojolowjo.entropybot.brain.FailMemory.classify(reason);
            if (r == io.github.mojolowjo.entropybot.brain.FailMemory.Reason.NO_ITEM) return NO_ITEM;
            if (r == io.github.mojolowjo.entropybot.brain.FailMemory.Reason.NO_PATH) return NO_PATH;
            return UNKNOWN;
        }
    }

    public record Rec(String desire, String option, Cause cause, int[] pos, int items, int fails, long until, String reason) {}

    private final Map<String, Rec> map = new LinkedHashMap<>();

    static String key(String desire, String option) { return desire + "|" + (option == null ? "" : option); }

    /** A failure: counted, held (the option or desire clock); {@code in} null: only the time clears it. */
    public Rec failed(String desire, String option, String reason, long now, Inputs in) {
        Rec r = map.get(key(desire, option));
        int n = r == null ? 1 : r.fails() + 1;
        long hold = option == null ? Math.min(CD_MAX_MS, CD_BASE_MS << Math.min(n - 1, 20)) : Math.min(RETRY_MAX_MS, RETRY_MS * n);
        Rec x = new Rec(desire, option, Cause.of(reason), in == null || in.pos == null ? null : in.pos.clone(), in == null ? -1 : items(in), n, now + hold, reason);
        map.put(key(desire, option), x);
        return x;
    }

    /** A failure counted without a hold (P(success) drops; something else holds it off). */
    public void counted(String desire, String option, String reason) {
        Rec r = map.get(key(desire, option));
        map.put(key(desire, option), new Rec(desire, option, Cause.of(reason), null, -1, r == null ? 1 : r.fails() + 1, r == null ? 0 : r.until(), reason));
    }

    /** Held {@code ms} without a failure (done just now, or failed before it started); never shortens a longer hold. */
    public void rest(String desire, long ms, long now, String reason) {
        Rec r = map.get(key(desire, null));
        long until = now + ms;
        if (r != null && r.until() >= until) return;
        map.put(key(desire, null), new Rec(desire, null, Cause.UNKNOWN, null, -1, r == null ? 0 : r.fails(), until, reason));
    }

    /** The hold's reason with the time left ("no path (3 min)"), or null when not held. */
    public String held(String desire, String option, long now) {
        Rec r = map.get(key(desire, option));
        if (r == null || r.until() <= now) return null;
        return r.reason() + " (" + left(r.until() - now) + ")";
    }

    public boolean blocked(String desire, String option, long now) {
        Rec r = map.get(key(desire, option));
        return r != null && r.until() > now;
    }

    public long until(String desire, String option) {
        Rec r = map.get(key(desire, option));
        return r == null ? 0 : r.until();
    }

    public int fails(String desire, String option) {
        Rec r = map.get(key(desire, option));
        return r == null ? 0 : r.fails();
    }

    public String reason(String desire, String option) {
        Rec r = map.get(key(desire, option));
        return r == null ? null : r.reason();
    }

    /** A success: the desire's own record goes (its options' records stay: they keep weighing). */
    public void succeeded(String desire) { map.remove(key(desire, null)); }

    /** Forget a desire and all its options (aa drop). */
    public void forget(String desire) { map.values().removeIf(r -> r.desire().equals(desire)); }

    /** Lifts every hold whose cause is gone; the lifted "desire/option CAUSE" texts. */
    public List<String> sweep(Inputs in) {
        List<String> out = new ArrayList<>();
        if (in == null) return out;
        for (var e : map.entrySet()) {
            Rec r = e.getValue();
            boolean gone = r.cause() == Cause.NO_ITEM && r.items() >= 0 && items(in) > r.items()
                    || r.cause() == Cause.NO_PATH && r.pos() != null && in.pos != null && moved(r.pos(), in.pos) >= MOVED;
            if (gone && r.until() > 0) {
                out.add(r.desire() + (r.option() == null ? "" : "/" + r.option()) + " " + r.cause());
                e.setValue(new Rec(r.desire(), r.option(), null, r.pos(), r.items(), r.fails(), 0, r.reason()));
            }
        }
        return out;
    }

    /** The records of one desire's options, oldest first. */
    public List<Rec> of(String desire) {
        List<Rec> out = new ArrayList<>();
        for (Rec r : map.values()) if (r.desire().equals(desire) && r.option() != null) out.add(r);
        return out;
    }

    public int size() { return map.size(); }

    /** Records holding now. */
    public int heldCount(long now) {
        int n = 0;
        for (Rec r : map.values()) if (r.until() > now) n++;
        return n;
    }

    /** "held: needs.iron/DIG_KNOWN 1 2 3 no path (8 min)" per record holding now (aa why). */
    public List<String> describe(long now) {
        List<String> out = new ArrayList<>();
        for (Rec r : map.values())
            if (r.until() > now) out.add("held: " + r.desire() + (r.option() == null ? "" : "/" + r.option()) + " " + r.reason() + " (" + left(r.until() - now) + ")");
        return out;
    }

    static String left(long ms) {
        return ms >= 60_000 ? (ms + 59_999) / 60_000 + " min" : (ms + 999) / 1000 + "s";
    }

    /** AA_ROUND2 6: the bag's total count (any item, modded too); the named counts only when no bag map was filled. */
    public static int items(Inputs in) {
        if (in.bag != null && !in.bag.isEmpty()) {
            int n = 0;
            for (Integer v : in.bag.values()) n += v == null ? 0 : v;
            return n;
        }
        return in.wood + in.cobble + in.coal + in.ironIngots + in.torches + in.planks + in.foodItems + in.wool + in.pickaxes;
    }

    static double moved(int[] a, int[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
