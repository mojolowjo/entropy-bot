package io.github.mojolowjo.entropybot.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.33.0 (NEW line): a tool, weapon or armour piece that broke, and one about to break (under {@link #LOW_PCT} % left).
 * Fed once a second with the damageable items per slot; plain Java (the game side reads the slots). Loader API: none.
 * A "broke" is a slot whose item was at most {@link #NEAR_LEFT} uses from breaking and is now gone (not moved: the same
 * item id with that little left is nowhere else); a "low" note is given once per item until it is repaired or swapped.
 */
public final class ToolWear {
    public static final int LOW_PCT = 5, NEAR_LEFT = 3;

    /** One damageable item in a slot: id (path, e.g. "iron_pickaxe"), uses left, the item's max. */
    public record Worn(String id, int left, int max) {}

    private Map<Integer, Worn> last = new HashMap<>();
    private final Set<String> warned = new HashSet<>();

    /** The news for this second's slots (slot index to item; damageable items only). Never null. */
    public synchronized List<String> update(Map<Integer, Worn> now) {
        List<String> out = new ArrayList<>();
        for (var e : last.entrySet()) {
            Worn was = e.getValue(), is = now.get(e.getKey());
            if (was.left() > NEAR_LEFT || (is != null && is.id().equals(was.id()))) continue;
            boolean moved = false;
            for (Worn w : now.values()) if (w.id().equals(was.id()) && w.left() <= was.left()) { moved = true; break; }
            if (!moved) { out.add(name(was.id()) + " broke"); warned.remove(key(e.getKey(), was.id())); }
        }
        Set<String> keep = new HashSet<>();
        for (var e : now.entrySet()) {
            Worn w = e.getValue();
            if (w.max() <= 0) continue;
            String k = key(e.getKey(), w.id());
            if (w.left() * 100 < LOW_PCT * w.max()) {
                keep.add(k);
                if (warned.add(k)) out.add(name(w.id()) + " about to break (" + w.left() + " uses left)");
            }
        }
        warned.retainAll(keep);
        last = new HashMap<>(now);
        return out;
    }

    private static String key(int slot, String id) { return id; }      // per item kind: a tool moved to another slot is not warned twice

    static String name(String id) { return id.replace('_', ' '); }
}
