package io.github.mojolowjo.entropybot.aa.moves;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 0.39.z75 take 2 (run AJ 20:23:41 torch, 20:24:55 iron_pickaxe: "take crafting_table at -34 70 -55: short by 1: the station
 * still stands" - the take's per-order hold did not carry into the next order, so the next order tried the same take):
 * station takes that ended short, by their cell. Two shorts at one cell hold "take at x y z" for EVERY order (the chain lists
 * the take dropped with the reason, so it prices the next way - a new table from the bag's logs) until the bot stands
 * failures.moved from where the last one failed, or the station block there changed (taken, gone). A take that ends done
 * clears its cell. Pure (JUnit: Z75UndergroundFuelTest). Loader notes: none.
 */
public final class TakeHolds {
    public static final TakeHolds INSTANCE = new TakeHolds();

    /** The same take short this many times at one cell is a fact for every order (once may be chance: the Attempt rule's twice). */
    public static final int TIMES = 2; // structural: the same failure twice from the same place (Steps' Attempt rule)

    /** One cell's short takes: how many, the feet the last one failed from, its words, the station item. */
    public record Hold(int x, int y, int z, String item, int count, int[] from, String why) {
        public Hold {
            from = from == null ? null : from.clone();
        }
    }

    private final Map<String, Hold> holds = new LinkedHashMap<>();

    /** The source key of the take at x y z (NeedChain.sourceKey of the TakeStation step). */
    public static String key(int x, int y, int z) { return "take at " + x + " " + y + " " + z; }

    /** A take of {@code item} at x y z ended short from {@code feet} ({@code why}: its words). */
    public synchronized void shortAt(int x, int y, int z, String item, int[] feet, String why) {
        Hold h = holds.get(key(x, y, z));
        holds.put(key(x, y, z), new Hold(x, y, z, item, h == null ? 1 : h.count() + 1, feet, why));
    }

    /** The take at x y z ended done (or the station is gone): its cell is clear. */
    public synchronized void clear(int x, int y, int z) { holds.remove(key(x, y, z)); }

    /** What may still stand at a cell: the station item's block there now (null: not known, the hold stays, rule 15). */
    public interface Cells { Boolean stands(int x, int y, int z, String item); }

    /**
     * The holds for every order from {@code me}: key -> reason, for NeedChain.World#withoutSources. A hold whose station no
     * longer stands, or that the bot stands {@code moved} from, is released (removed).
     */
    public synchronized Map<String, String> held(int[] me, Cells cells, double moved) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var it = holds.entrySet().iterator(); it.hasNext(); ) {
            Hold h = it.next().getValue();
            Boolean stands = cells == null ? null : cells.stands(h.x(), h.y(), h.z(), h.item());
            if (Boolean.FALSE.equals(stands) || released(h, me, moved)) { it.remove(); continue; }
            if (h.count() >= TIMES) out.put(key(h.x(), h.y(), h.z()), h.count() + " takes short here (held for every order until the bot moves failures.moved): " + h.why());
        }
        return out;
    }

    private static boolean released(Hold h, int[] me, double moved) {
        if (me == null || h.from() == null) return false;
        double dx = me[0] - h.from()[0], dy = me[1] - h.from()[1], dz = me[2] - h.from()[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz) >= moved;
    }

    /** Tests: forget every hold. */
    public synchronized void reset() { holds.clear(); }
}
