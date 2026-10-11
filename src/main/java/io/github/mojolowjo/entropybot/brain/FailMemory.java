package io.github.mojolowjo.entropybot.brain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 0.26.1 R1 (the 10-07 decision log: {@code night.light} failed "no torches" 12 times, picked again every loop): the
 * brain's failure memory, {@code (chain, reason) -> until}. A brain job that ends failed with a precondition reason is
 * remembered for {@code brain.failMemoryMinutes} (0 = off); every tree leaf that would start that chain is skipped
 * meanwhile (the next sibling, then the needs by score, are tried) with {@code skipped: <chain> failed <REASON> N min ago}.
 * A record goes early when its cause changed: NO_ITEM when an item count the bot senses rose, OUTSIDE when the bot stands
 * in an area, NO_PATH when it moved 16+ blocks, NO_BED when a bed is near (or in the bag) that was not, or came back after it\n * was gone (a spawn that failed at a bed near), NONE_IN_CHESTS when the stock
 * view changed (a scan or a deposit). Fights, the owner's stop, other reasons: never recorded. Owner-ordered chains are
 * never seen here (only the brain's own jobs end through it). In memory only (a restart forgets it). Pure; loader notes: none.
 */
public final class FailMemory {

    public enum Reason { NO_ITEM, OUTSIDE, NONE_IN_CHESTS, NO_PATH, NO_BED, GUARD_REFUSED }

    /** Checked in this order (a guard refusal names "outside my areas" too: OUTSIDE wins, the cause it clears on). */
    private static final Object[][] PATTERNS = {
            {Reason.NONE_IN_CHESTS, Pattern.compile("none of my chests has|no chest (has|holds)")},
            {Reason.OUTSIDE, Pattern.compile("outside (my|every|the) areas?|outside every work area")},
            {Reason.GUARD_REFUSED, Pattern.compile("would refuse|the guard refuse|refused by the guard|guard refused|fence refuse")},
            {Reason.NO_BED, Pattern.compile("no bed")},
            {Reason.NO_PATH, Pattern.compile("no path|couldn't get there|could not get there|can't get there|couldn't reach|can't reach")},
            {Reason.NO_ITEM, Pattern.compile("no torches|i have no |no \\S+ on me|missing \\d|not enough |none on me")},
    };
    /** A fight or the owner: never a precondition (checked first). */
    private static final Pattern NOT_A_CAUSE = Pattern.compile("attacked by|low health|fight|stopped: your|overridden|interrupted|stopped by");

    /** The precondition class of a job's end text, or null (not recorded). */
    public static Reason classify(String end) {
        if (end == null) return null;
        String t = end.toLowerCase(Locale.ROOT);
        if (NOT_A_CAUSE.matcher(t).find()) return null;
        for (Object[] p : PATTERNS) if (((Pattern) p[1]).matcher(t).find()) return (Reason) p[0];
        return null;
    }

    /** One record and what the bot had when it was made (the cause checks). */
    /** bedAll[0]: a bed was near (or in the bag) at the record and every check since (NO_BED clears only when a bed comes back). */
    public record Entry(String chain, Reason reason, long at, long until, int[] pos, int items, long storageSeq, boolean[] bedAll) {}

    private final Map<String, Entry> map = new LinkedHashMap<>();

    static String key(String chain, Reason r) { return norm(chain) + "|" + r; }

    static String norm(String chain) { return chain == null ? "" : chain.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }

    /** The items whose rise clears NO_ITEM: torches, coal, logs, wood, food, pickaxes, a bed in the bag. */
    static int items(BrainState s) {
        return s.torches + s.coal + s.logs + s.woodItems + s.foodItems + s.pickaxes + (s.bedInBag ? 1 : 0);
    }

    /** Records a failed brain job; the reason it was recorded under, or null when not (memory off or not a precondition). */
    public Reason record(String chain, String end, BrainState s, long now, int minutes) {
        if (minutes <= 0 || chain == null) return null;
        Reason r = classify(end);
        if (r == null) return null;
        return record(chain, r, s, now, minutes);
    }

    /** Records under a given reason (R6: a failed spawn is NO_BED whatever it said). */
    public Reason record(String chain, Reason r, BrainState s, long now, int minutes) {
        if (minutes <= 0 || chain == null || r == null) return null;
        map.put(key(chain, r), new Entry(norm(chain), r, now, now + minutes * 60_000L, s.botPos == null ? null : s.botPos.clone(), items(s), s.storageSeq, new boolean[]{PlayRules.bedFor(s)}));
        return r;
    }

    /** The cause of this record changed. */
    static boolean causeGone(Entry e, BrainState s) {
        return switch (e.reason()) {
            case NO_ITEM -> items(s) > e.items();
            case OUTSIDE -> s.areasHere;
            case NO_PATH -> moved(e.pos(), s.botPos) >= 16;
            case NO_BED -> {
                // a bed near now that was not near at the record, or came back after it was gone (R6: the spawn's bed)
                if (!PlayRules.bedFor(s)) { e.bedAll()[0] = false; yield false; }
                yield !e.bedAll()[0];
            }
            case NONE_IN_CHESTS -> s.storageSeq != e.storageSeq();
            case GUARD_REFUSED -> false;
        };
    }

    static double moved(int[] a, int[] b) {
        if (a == null || b == null) return 0;
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Drops what expired or whose cause changed; returns the dropped ones' texts ("light here 8 NO_ITEM: torches rose"). */
    public List<String> sweep(BrainState s, long now) {
        List<String> gone = new ArrayList<>();
        map.values().removeIf(e -> {
            if (e.until() <= now) return true;
            if (causeGone(e, s)) { gone.add(e.chain() + " " + e.reason()); return true; }
            return false;
        });
        return gone;
    }

    /** A deposit or scan the brain ran itself finished: NONE_IN_CHESTS records go. */
    public void storageChanged() { map.values().removeIf(e -> e.reason() == Reason.NONE_IN_CHESTS); }

    /** The live record that blocks this chain, or null. Expired or cause-gone records are dropped first. */
    public Entry blocks(String chain, BrainState s, long now) {
        if (chain == null || map.isEmpty()) return null;
        String n = norm(chain);
        Entry hit = null;
        for (var it = map.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (!e.chain().equals(n)) continue;
            if (e.until() <= now || causeGone(e, s)) { it.remove(); continue; }
            if (hit == null) hit = e;
        }
        return hit;
    }

    /** "light here 8 failed NO_ITEM 3 min ago" (the skipped text; why and the decision log carry it). */
    public static String skipText(Entry e, long now) {
        return e.chain() + " failed " + e.reason() + " " + Math.max(0, (now - e.at()) / 60_000) + " min ago";
    }

    public int size() { return map.size(); }

    public void clear() { map.clear(); }

    /** For status: "light here 8 NO_ITEM 7 min left, ...". */
    public String describe(long now) {
        List<String> w = new ArrayList<>();
        for (Entry e : map.values()) w.add(e.chain() + " " + e.reason() + " " + Math.max(1, (e.until() - now + 59_999) / 60_000) + " min left");
        return String.join(", ", w);
    }
}
