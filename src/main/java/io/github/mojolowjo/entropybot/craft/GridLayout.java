package io.github.mojolowjo.entropybot.craft;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.github.mojolowjo.entropybot.craft.CraftPlanner.shortId;

/**
 * Where one craft of a recipe goes in a crafting grid (what EMI's performFill did for the bridge): the inventory's 2x2
 * grid or a table's 3x3, slots numbered row-major from 0. Shaped recipes sit at the top-left (vanilla matching finds
 * them anywhere); shapeless ones fill the slots in order. Each cell takes the alternative the counts have most of
 * (the first on a tie), and the counts it reserves go down as cells are assigned, so a pickaxe can take 2 oak and 1
 * spruce planks when there are 2 and 1.
 *
 * <p>In both vanilla menus (InventoryMenu, CraftingMenu) slot 0 is the result and grid slot i is menu slot i + 1
 * ({@link #menuSlot}).
 */
public final class GridLayout {
    private GridLayout() {}

    /** The inventory's grid. */
    public static final int INVENTORY = 2;
    /** A crafting table's grid. */
    public static final int TABLE = 3;

    /**
     * Grid slot -> item id, or {@code error} = why it can't be laid out: "&lt;item&gt; needs a crafting table" (too big
     * for the 2x2 grid), "not a crafting recipe", "doesn't fit a crafting grid", or "ingredients ran out" with
     * {@code missing} = the short id of a cell's first alternative there is none of.
     */
    public record Layout(Map<Integer, String> slots, String error, String missing) {
        public boolean ok() {
            return error == null;
        }

        static Layout fail(String why, String missing) {
            return new Layout(Map.of(), why, missing);
        }
    }

    public static final String RAN_OUT = "ingredients ran out";

    /** Grid slot -> the menu's slot index (InventoryMenu and CraftingMenu: the result is 0). */
    public static int menuSlot(int gridSlot) {
        return gridSlot + 1;
    }

    /** One craft of {@code r} in a {@code size} x {@code size} grid from {@code counts} (not changed). */
    public static Layout layout(RecipeData r, int size, Map<String, Integer> counts) {
        if (size != INVENTORY && size != TABLE) throw new IllegalArgumentException("grid size " + size);
        if (!r.crafting()) return Layout.fail("not a crafting recipe", null);
        if (!r.fits(size)) return Layout.fail(size == INVENTORY ? shortId(r.output()) + " needs a crafting table" : "doesn't fit a crafting grid", null);
        Map<String, Integer> left = new LinkedHashMap<>(counts);
        Map<Integer, String> slots = new TreeMap<>();
        List<List<String>> cells = r.cells();
        int next = 0;
        for (int i = 0; i < cells.size(); i++) {
            List<String> alts = cells.get(i);
            int slot;
            if (r.shapeless()) {
                if (alts.isEmpty()) continue;
                slot = next++;
            } else {
                slot = (i / r.width()) * size + i % r.width();
                if (alts.isEmpty()) continue;
            }
            String pick = null;
            int best = 0;
            for (String a : alts) {
                int have = CraftPlanner.get(left, a);
                if (have > best) {
                    best = have;
                    pick = a;
                }
            }
            if (pick == null) return Layout.fail(RAN_OUT, shortId(alts.get(0)));
            left.put(pick, best - 1);
            slots.put(slot, pick);
        }
        return new Layout(Collections.unmodifiableMap(slots), null, null);
    }
}
