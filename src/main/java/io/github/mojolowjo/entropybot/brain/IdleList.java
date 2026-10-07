package io.github.mojolowjo.entropybot.brain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * B1: the upkeep leaf, the lowest need (the old autominer's choices, BRAIN_LOOP answer 1): the owner's idle list in
 * order, each item skipped when it can't run. The default is restock, strip, cave, farm. The picks and their wording
 * are the autominer's ({@code Chains.autominerDecide}, whose tests stay; a parity test checks both agree). The bag-full
 * deposit is the brain's "bag" need, not an upkeep item. Pure.
 */
public final class IdleList {
    private IdleList() {}

    public static final List<String> ITEMS = List.of("restock", "strip", "cave", "farm", "explore");
    public static final List<String> DEFAULT = List.of("restock", "strip", "cave", "farm");
    /** A restock runs at most this often (the autominer's 10 minutes). */
    public static final long RESTOCK_EVERY_MS = 600_000;
    /** A strip mine that gave up is left alone this long (caving instead). */
    public static final long GAVE_UP_MS = 1_800_000;

    /** What the upkeep items need to know. */
    public static final class Facts {
        public long now;
        /** A supply that is short (its short id), else null; when the last restock started (-1: never). */
        public String shortSupply;
        public long restockAt = -1;
        public String ores = "any";
        /** The marked mine {x, y, z} (null: none), and whether it has a direction and lies inside the areas. */
        public int[] mine;
        public boolean mineReady;
        /** Why the strip mine gave up within {@link #GAVE_UP_MS}, else null. */
        public String mineGaveUp;
        /** The farm place, else null. */
        public int[] farm;
    }

    /** One runnable item: its name, its chain, why, where it works (null: where the bot is or at the base). */
    public record Pick(String item, String chain, String why, int[] where) {}

    /** Every runnable item of the list, in the list's order. */
    public static List<Pick> candidates(List<String> list, Facts f) {
        List<Pick> out = new ArrayList<>();
        String ores = f.ores == null || f.ores.isEmpty() ? "any" : f.ores;
        for (String item : list) {
            switch (item) {
                case "restock" -> {
                    if (f.shortSupply != null && (f.restockAt < 0 || f.now - f.restockAt > RESTOCK_EVERY_MS))
                        out.add(new Pick(item, "restock", f.shortSupply + " is short of my supplies", null));
                }
                case "strip" -> {
                    if (f.mineGaveUp == null && f.mine != null && f.mineReady)
                        out.add(new Pick(item, "mine strip " + ores + " 32", "my mine at " + f.mine[0] + " " + f.mine[1] + " " + f.mine[2] + " is ready", f.mine));
                }
                case "cave" -> {
                    String why = f.mineGaveUp != null ? "my mine could not go on (" + cut(f.mineGaveUp, 160) + "), so I go caving"
                            : f.mine == null || !f.mineReady ? "I have no mine marked, so I go caving" : "caving";
                    out.add(new Pick(item, "mine cave " + ores + " 32 20m", why, null));
                }
                case "farm" -> {
                    if (f.farm != null) out.add(new Pick(item, "farm", "the farm at " + f.farm[0] + " " + f.farm[1] + " " + f.farm[2], f.farm));
                }
                case "explore" -> out.add(new Pick(item, "explore 5", "new land in my areas", null));
                default -> { }
            }
        }
        return out;
    }

    static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    /** "strip, cave farm" -> the list; null when a word is not an item or one is named twice, or the list is empty. */
    public static List<String> parse(String text) {
        List<String> out = new ArrayList<>();
        for (String w : (text == null ? "" : text.toLowerCase(Locale.ROOT)).split("[,\\s]+")) {
            if (w.isEmpty()) continue;
            if (!ITEMS.contains(w) || out.contains(w)) return null;
            out.add(w);
        }
        return out.isEmpty() ? null : out;
    }

    public static String show(List<String> list) {
        return "idle list: " + String.join(", ", list) + " (items: " + String.join(", ", ITEMS) + "; idle list set strip, cave)";
    }
}
