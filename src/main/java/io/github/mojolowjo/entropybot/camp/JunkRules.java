package io.github.mojolowjo.entropybot.camp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * C5 (inventory hygiene): the junk list ({@code junk add|remove|list|default|mode drop|chest}, commands.json "junk"
 * [ids] and "junkMode"), and what goes when the bag has {@link #LOW} or fewer free slots in the middle of a job. Pure.
 *
 * <p>Never junk, whatever the list says: tools, armor, food, torches, supplies, the hotbar layout, ore drops and other
 * valuables (the caller passes {@code StorageRules.depositables(..., keepValuables=true, ...)}, which already leaves
 * all of those out and keeps 64 cobblestone and 16 coal), and the item a running job collects ({@code collecting}).
 *
 * <p>Loader notes: none.
 */
public final class JunkRules {
    private JunkRules() {}

    public static final int LOW = 4;
    /** The default list (the owner's plan, 2026-10-05): string is kept on purpose. */
    public static final List<String> DEFAULT = List.of("minecraft:cobblestone", "minecraft:dirt", "minecraft:gravel", "minecraft:rotten_flesh",
            "minecraft:spider_eye", "minecraft:cobbled_deepslate", "minecraft:netherrack", "minecraft:andesite", "minecraft:diorite",
            "minecraft:granite", "minecraft:tuff", "minecraft:poisonous_potato");
    /** Never on the list (a typo or a bad idea): these are tools, fuel, food or valuables. */
    static final Pattern NEVER = Pattern.compile("_(pickaxe|axe|shovel|hoe|sword|helmet|chestplate|leggings|boots)$|(^|:)(torch|shield|bow|crossbow|coal|charcoal|diamond|emerald|bread)$|(^|:)raw_");

    public static final String USAGE = "usage: junk list | junk add <item> ... | junk remove <item> ... | junk default | junk mode drop|chest";

    public static String full(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    /** Adds ids; the refused ones (never-junk) come back in refused. */
    public static Set<String> add(Set<String> list, List<String> ids, List<String> refused) {
        Set<String> out = new LinkedHashSet<>(list);
        for (String i : ids) {
            String id = full(i);
            if (NEVER.matcher(id).find()) refused.add(StockRules.shortId(id));
            else out.add(id);
        }
        return out;
    }

    public static Set<String> remove(Set<String> list, List<String> ids) {
        Set<String> out = new LinkedHashSet<>(list);
        for (String i : ids) out.remove(full(i));
        return out;
    }

    /**
     * What to throw (or put in the junk chest): id -> count. depositables: StorageRules.depositables with valuables kept
     * (id -> how many stay); inv: id -> count carried; collecting: the job's text (an item named there stays).
     */
    public static Map<String, Integer> plan(Set<String> junk, Map<String, Integer> depositables, Map<String, Integer> inv, String collecting) {
        Map<String, Integer> out = new LinkedHashMap<>();
        String job = collecting == null ? "" : collecting.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Integer> e : depositables.entrySet()) {
            String id = e.getKey();
            if (!junk.contains(id) || NEVER.matcher(id).find()) continue;
            String bare = id.substring(id.indexOf(':') + 1);
            if (!job.isEmpty() && job.matches("(?s).*\\b" + Pattern.quote(bare) + "\\b.*")) continue;
            int n = inv.getOrDefault(id, 0) - (e.getValue() == null ? 0 : e.getValue());
            if (n > 0) out.put(id, n);
        }
        return out;
    }

    public static int total(Map<String, Integer> plan) {
        int n = 0;
        for (int v : plan.values()) n += v;
        return n;
    }

    public static String listText(Set<String> junk, String mode) {
        List<String> s = new ArrayList<>();
        for (String id : junk) s.add(StockRules.shortId(id));
        return "junk (" + ("chest".equals(mode) ? "into the chest marked junk on base trips" : "dropped when my bag is nearly full mid-job") + "): "
                + (s.isEmpty() ? "nothing" : String.join(", ", s)) + " - cobblestone keeps 64; tools, food, torches, supplies and ores never go";
    }
}
