package io.github.mojolowjo.entropybot.camp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C4: {@code stock}, a target for the BASE chests (unlike {@code supplies}, which is the bag). Stored in commands.json
 * "stock" {id: n}. Pure: the have/want text, what is short, and the chain {@code restock base} runs (gather each short
 * item, then put just that item away in the base chests).
 *
 * <p>Loader notes: none.
 */
public final class StockRules {
    private StockRules() {}

    public static final int MAX_ITEMS = 32;
    public static final String USAGE = "usage: stock targets | stock set <item> <n> | stock clear <item>|all | restock base";

    /** What is short: id -> missing (have: the base chests' notes, id -> count). */
    public static Map<String, Integer> shortOf(Map<String, Integer> want, Map<String, Integer> have) {
        Map<String, Integer> out = new LinkedHashMap<>();
        want.forEach((id, n) -> {
            int h = have.getOrDefault(id, 0);
            if (h < n) out.put(id, n - h);
        });
        return out;
    }

    /** "stock: cobblestone 40/256, torch 64/64 (base chests, as last seen)". */
    public static String text(Map<String, Integer> want, Map<String, Integer> have) {
        if (want.isEmpty()) return "no stock targets - e.g. stock set torch 64 (the base chests should hold that many; restock base fills them)";
        List<String> parts = new ArrayList<>();
        want.forEach((id, n) -> parts.add(shortId(id) + " " + have.getOrDefault(id, 0) + "/" + n));
        int s = shortOf(want, have).size();
        return "stock (base chests, as last seen): " + String.join(", ", parts) + (s > 0 ? " - " + s + " short; next: restock base" : " - all there");
    }

    /** The chain that fills the base chests: "gather <id> <n> then deposit <id>" per short item. */
    public static String chain(Map<String, Integer> shortItems) {
        List<String> steps = new ArrayList<>();
        shortItems.forEach((id, n) -> {
            steps.add("gather " + shortId(id) + " " + n);
            steps.add("deposit " + shortId(id));
        });
        return String.join(" then ", steps);
    }

    /** "gather <item> <n> to base": the gather line without "to base", or null when it doesn't end so. */
    public static String toBase(String gatherRest) {
        if (gatherRest == null) return null;
        String t = gatherRest.trim();
        if (!t.toLowerCase().matches("^.*\\s+to\\s+base$")) return null;
        return t.replaceFirst("(?i)\\s+to\\s+base$", "").trim();
    }

    public static String shortId(String id) {
        return id != null && id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
