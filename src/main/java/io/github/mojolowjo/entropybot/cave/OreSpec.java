package io.github.mojolowjo.entropybot.cave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An ore list (B4, docs/BOT_PLAN.md 5.5; the bridge's parseOres / oresWord / talliedSince): "iron,diamond" or
 * "iron_ore deepslate_coal_ore" or "any". A family name ("iron") is every ore block of that metal (iron_ore,
 * deepslate_iron_ore, modded *_iron_ore); an id ("iron_ore", "oritech:nickel_ore") is just that block; "any" is every
 * ore. The order is the priority. Plain Java: the registry's ore ids are handed in.
 */
public final class OreSpec {
    /** One word of the list: its ids, or null for "any". */
    public record Entry(String word, List<String> ids) {}

    public final String label;
    public final List<Entry> list;
    private final List<String> all;

    private OreSpec(List<Entry> list, List<String> all) {
        this.list = Collections.unmodifiableList(list);
        this.all = all;
        List<String> words = new ArrayList<>();
        for (Entry e : list) words.add(e.word());
        this.label = String.join(",", words);
    }

    /** The parse result: a spec, or the error text (no "error: " in front). */
    public record Parsed(OreSpec spec, String err) {}

    /** Every registered ore block id: registry ids ending in "_ore", and ancient debris (the bridge's oreIds). */
    public static List<String> oreIds(Iterable<String> registryIds) {
        List<String> out = new ArrayList<>();
        for (String id : registryIds) if (id.endsWith("_ore") || id.endsWith(":ancient_debris")) out.add(id);
        return out;
    }

    public static Parsed parse(String text, List<String> allOres) {
        List<String> words = new ArrayList<>();
        for (String w : String.valueOf(text == null ? "" : text).toLowerCase().split("[\\s,]+")) if (!w.isEmpty()) words.add(w);
        if (words.isEmpty()) return new Parsed(null, "which ores? e.g. iron,diamond (or \"any\")");
        List<Entry> list = new ArrayList<>();
        for (String raw : words) {
            String w = raw.replaceFirst("^minecraft:", "");
            if (w.equals("any") || w.equals("all")) {
                list.add(new Entry("any", null));
                continue;
            }
            List<String> ids = new ArrayList<>();
            if (w.endsWith("_ore") || w.contains(":") || w.contains("ancient_debris")) {
                String id = w.contains(":") ? w : "minecraft:" + w;
                for (String x : allOres) if (x.equals(id)) ids.add(x);
                if (ids.isEmpty()) return new Parsed(null, "I don't know an ore called " + w);
            } else {
                Pattern re = Pattern.compile("(^|[:_])" + (w.equals("lapis") ? w : w.replaceFirst("s$", "")).replaceAll("[^a-z0-9_]", "") + "_ore$");
                for (String x : allOres) if (re.matcher(x).find()) ids.add(x);
                if (ids.isEmpty()) return new Parsed(null, "no ore is called " + w + " (try iron, coal, copper, gold, diamond, emerald, redstone, lapis, quartz, or an id)");
            }
            list.add(new Entry(w, ids));
        }
        return new Parsed(new OreSpec(list, allOres), null);
    }

    /** The priority of a block id (0 = first), or -1 when the list doesn't name it. */
    public int match(String id) {
        for (int k = 0; k < list.size(); k++) {
            List<String> ids = list.get(k).ids();
            if (ids == null ? all.contains(id) : ids.contains(id)) return k;
        }
        return -1;
    }

    /** Every id the list names ("any": every ore), for the cave search (the bridge's caveOreIds). */
    public Set<String> ids() {
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : list) out.addAll(e.ids() == null ? all : e.ids());
        return out;
    }

    /** "3 iron ores", "1 diamond ore", "5 ores" (for "any"). */
    public String word(int n) {
        return n + " " + (label.equals("any") ? "" : label + " ") + "ore" + (n == 1 ? "" : "s");
    }

    /** How many of the listed ores a tally (ore block id -> mined) holds. */
    public int count(Map<String, Integer> tally) {
        int n = 0;
        for (Map.Entry<String, Integer> e : tally.entrySet()) if (match(e.getKey()) >= 0) n += e.getValue();
        return n;
    }
}
