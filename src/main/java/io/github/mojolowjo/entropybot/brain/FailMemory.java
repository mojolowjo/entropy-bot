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

    /** NO_PLACE (0.26.1 live check): a place, area or mine the chain names is not marked; it only expires. */
    public enum Reason { NO_ITEM, OUTSIDE, NONE_IN_CHESTS, NO_PATH, NO_BED, GUARD_REFUSED, NO_PLACE }

    /** Checked in this order (a guard refusal names "outside my areas" too: OUTSIDE wins, the cause it clears on). */
    private static final Object[][] PATTERNS = {
            {Reason.NONE_IN_CHESTS, Pattern.compile("none of my chests has|no chest (has|holds)")},
            {Reason.OUTSIDE, Pattern.compile("outside (my|every|the) areas?|outside every work area")},
            {Reason.GUARD_REFUSED, Pattern.compile("would refuse|the guard refuse|refused by the guard|guard refused|fence refuse")},
            {Reason.NO_BED, Pattern.compile("no bed")},
            {Reason.NO_PLACE, Pattern.compile("i have no (place|area|mine)\\b|no places yet|i have no place, area|no (camp|base) place")},
            {Reason.NO_PATH, Pattern.compile("no path|couldn't get there|could not get there|can't get there|couldn't reach|can't reach|no trees in my areas|chopped nothing|couldn't get at its trunk")},
            {Reason.NO_ITEM, Pattern.compile("no torches|i have no |no \\S+ on me|missing \\d|not enough |none on me|i need \\d+ |i need .* there is none|no furnace recipe")},
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
    public record Entry(String chain, Reason reason, long at, long until, int[] pos, int items, long storageSeq, boolean[] bedAll, String note) {}

    private final Map<String, Entry> map = new LinkedHashMap<>();

    static String key(String chain, Reason r) { return norm(chain) + "|" + r; }

    static String norm(String chain) { return chain == null ? "" : chain.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }

    /** The items whose rise clears NO_ITEM: torches, coal, logs, wood, food, pickaxes, a bed in the bag, shelter blocks. */
    static int items(BrainState s) {
        return s.torches + s.coal + s.logs + s.woodItems + s.foodItems + s.pickaxes + (s.bedInBag ? 1 : 0) + s.shelterBlocks;      // shelterBlocks: planks, cobblestone, dirt
    }

    /** Records a failed brain job; the reason it was recorded under, or null when not (memory off or not a precondition). */
    public Reason record(String chain, String end, BrainState s, long now, int minutes) {
        if (minutes <= 0 || chain == null) return null;
        Reason r = classify(end);
        if (r == null) return null;
        return record(chain, r, s, now, minutes);
    }

    /** Records under a given reason (R6: a failed spawn is NO_BED whatever it said). */
    public Reason record(String chain, Reason r, BrainState s, long now, int minutes) { return record(chain, r, s, now, minutes, null); }

    /** 0.26.2: with a note for the skip text ("no wooden_pickaxe; getting one failed: cut logs NO_PATH"). */
    public Reason record(String chain, Reason r, BrainState s, long now, int minutes, String note) {
        if (minutes <= 0 || chain == null || r == null) return null;
        map.put(key(chain, r), new Entry(norm(chain), r, now, now + minutes * 60_000L, s.botPos == null ? null : s.botPos.clone(), items(s), s.storageSeq, new boolean[]{PlayRules.bedFor(s)}, note));
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
            case GUARD_REFUSED, NO_PLACE -> false;
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

    /**
     * 0.26.2 fix: the hash of what storage holds (the stock totals less the bag), so NONE_IN_CHESTS clears on a scan or
     * deposit, never on a change of the bag alone. Keys compared without "minecraft:".
     */
    public static int storageHash(Map<String, Integer> totals, Map<String, Integer> bag) {
        Map<String, Integer> b = new java.util.HashMap<>();
        if (bag != null) bag.forEach((k, v) -> b.merge(k.substring(k.indexOf(':') + 1), v == null ? 0 : v, Integer::sum));
        java.util.TreeMap<String, Integer> out = new java.util.TreeMap<>();
        if (totals != null) totals.forEach((k, v) -> {
            String s = k.substring(k.indexOf(':') + 1);
            int n = (v == null ? 0 : v) - b.getOrDefault(s, 0);
            if (n > 0) out.merge(s, n, Integer::sum);
        });
        return out.hashCode();
    }

    /** 0.26.2 fix: a live record says "get <item> ..." found none in the chests (any count): a supply prefix skips that get. */
    public boolean noneInChests(String item, BrainState s, long now) {
        Entry st = blocks("get " + norm(item), s, now);
        if (st != null && st.reason() == Reason.NONE_IN_CHESTS) return true;
        String pre = "get " + norm(item) + " ";
        for (Entry e : new ArrayList<>(map.values()))
            if (e.reason() == Reason.NONE_IN_CHESTS && (e.chain() + " ").startsWith(pre) && blocks(e.chain(), s, now) != null) return true;
        return false;
    }

    /** A deposit or scan the brain ran itself finished: NONE_IN_CHESTS records go. */
    public void storageChanged() { map.values().removeIf(e -> e.reason() == Reason.NONE_IN_CHESTS); }

    /** The live record that blocks this chain, or null. Expired or cause-gone records are dropped first. */
    public Entry blocks(String chain, BrainState s, long now) {
        if (chain == null || map.isEmpty()) return null;
        String n = norm(chain);
        java.util.Set<String> keys = new java.util.HashSet<>(stepKeys(chain));
        Entry hit = null;
        for (var it = map.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (!e.chain().equals(n) && !(e.chain().startsWith(STEP) && keys.contains(e.chain().substring(STEP.length())))
                    && !(e.reason() == Reason.NONE_IN_CHESTS && sameGet(e.chain(), n))) continue;
            if (e.until() <= now || causeGone(e, s)) { it.remove(); continue; }
            if (hit == null) hit = e;
        }
        return hit;
    }

    /** "light here 8 failed NO_ITEM 3 min ago" (the skipped text; why and the decision log carry it). */
    public static String skipText(Entry e, long now) {
        if (e.note() != null) return "infeasible: " + e.chain() + " (" + e.note() + " " + Math.max(0, (now - e.at()) / 60_000) + " min ago) - skipped "
                + Math.max(1, (e.until() - now + 59_999) / 60_000) + " min";
        return e.chain() + " failed " + e.reason() + " " + Math.max(0, (now - e.at()) / 60_000) + " min ago";
    }

    /** 0.26.2 fix: two single-step gets of the same item ("get food 4", "get food 8") are one for NONE_IN_CHESTS. */
    static boolean sameGet(String a, String b) {
        String[] x = a.split(" "), y = b.split(" ");
        return x.length >= 2 && y.length >= 2 && x.length <= 3 && y.length <= 3 && x[0].equals("get") && y[0].equals("get") && x[1].equals(y[1]);
    }

    /** 0.26.2: step records are kept as "step:<key>". */
    public static final String STEP = "step:";

    /**
     * 0.26.2: a step without its numbers: lower case, "minecraft:" dropped, every integer word (counts, coordinates, ~ ^
     * relative ones) dropped. "cut 3 logs" -> "cut logs", "goto 10 87 38" -> "goto", "place crafting_table 8 87 36" ->
     * "place crafting_table", "get food 4" -> "get food", "mine strip any 32" -> "mine strip any".
     */
    public static String stepKey(String step) {
        List<String> out = new ArrayList<>();
        for (String w : norm(step).split(" ")) {
            String x = w.startsWith("minecraft:") ? w.substring(10) : w;
            if (x.isEmpty() || x.matches("[~^]?-?\\d*")) continue;
            out.add(x);
        }
        return String.join(" ", out);
    }

    public static List<String> stepKeys(String chain) {
        List<String> out = new ArrayList<>();
        for (String st : norm(chain).split(" then ")) if (!st.isBlank()) out.add(stepKey(st));
        return out;
    }

    /** 0.26.2: a failing step, by its key: it blocks every chain holding a step with that key. */
    public Reason recordStep(String step, Reason r, BrainState s, long now, int minutes) {
        if (step == null || stepKey(step).isEmpty()) return null;
        return record(STEP + stepKey(step), r, s, now, minutes, null);
    }

    public int size() { return map.size(); }

    public void clear() { map.clear(); }

    /** 0.27.1: {chain records, step records} (for brain reset parks). */
    public int[] counts() {
        int steps = 0;
        for (Entry e : map.values()) if (e.chain().startsWith(STEP)) steps++;
        return new int[]{map.size() - steps, steps};
    }

    /** For status: "light here 8 NO_ITEM 7 min left, ...". */
    public String describe(long now) {
        List<String> w = new ArrayList<>();
        for (Entry e : map.values()) w.add(e.chain() + " " + e.reason() + " " + Math.max(1, (e.until() - now + 59_999) / 60_000) + " min left");
        return String.join(", ", w);
    }
}
