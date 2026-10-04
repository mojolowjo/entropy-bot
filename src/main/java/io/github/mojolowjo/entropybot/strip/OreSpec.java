package io.github.mojolowjo.entropybot.strip;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * B7d D2: an ore list (the bridge's parseOres, B4): "iron, diamond" = every *iron_ore, then every *diamond_ore (the
 * order is the priority); an id ("iron_ore", "oritech:nickel_ore") is that block; "any" (or "all") every ore. Pure:
 * the registry's ore ids come in. {@link #parse} gives either a list or an error text.
 */
public final class OreSpec {
    /** One word of the list and the block ids it stands for (null = any ore). */
    public record Entry(String word, List<String> ids) {}

    public final String label, err;
    public final List<Entry> list;
    private final List<String> all;

    private OreSpec(String label, List<Entry> list, List<String> all, String err) {
        this.label = label;
        this.list = list;
        this.all = all;
        this.err = err;
    }

    /** The registry ids that count as ores here: "*_ore" and ancient debris. */
    public static boolean oreId(String id) {
        return id.endsWith("_ore") || id.endsWith(":ancient_debris");
    }

    public static OreSpec parse(String text, Collection<String> allIds) {
        List<String> all = new ArrayList<>();
        for (String id : allIds) if (oreId(id)) all.add(id);
        List<String> words = new ArrayList<>();
        for (String w : (text == null ? "" : text).toLowerCase().split("[\\s,]+")) if (!w.isEmpty()) words.add(w);
        if (words.isEmpty()) return new OreSpec(null, null, all, "which ores? e.g. iron,diamond (or \"any\")");
        List<Entry> list = new ArrayList<>();
        for (String word : words) {
            String w = word.replaceFirst("^minecraft:", "");
            if (w.equals("any") || w.equals("all")) {
                list.add(new Entry("any", null));
                continue;
            }
            List<String> ids = new ArrayList<>();
            if (w.endsWith("_ore") || w.contains(":") || w.contains("ancient_debris")) {
                String id = w.contains(":") ? w : "minecraft:" + w;
                for (String x : all) if (x.equals(id)) ids.add(x);
                if (ids.isEmpty()) return new OreSpec(null, null, all, "I don't know an ore called " + w);
            } else {
                Pattern re = Pattern.compile("(^|[:_])" + w.replaceFirst("s$", "").replaceAll("[^a-z0-9_]", "") + "_ore$");
                for (String x : all) if (re.matcher(x).find()) ids.add(x);
                if (ids.isEmpty()) return new OreSpec(null, null, all, "no ore is called " + w
                        + " (try iron, coal, copper, gold, diamond, emerald, redstone, lapis, quartz, or an id)");
            }
            list.add(new Entry(w, ids));
        }
        List<String> labels = new ArrayList<>();
        for (Entry e : list) labels.add(e.word());
        return new OreSpec(String.join(",", labels), list, all, null);
    }

    /** The priority of a block id (0 = first), or -1 when the list doesn't name it. */
    public int match(String id) {
        for (int k = 0; k < list.size(); k++) {
            List<String> ids = list.get(k).ids();
            if (ids == null ? all.contains(id) : ids.contains(id)) return k;
        }
        return -1;
    }

    /** "3 iron ores", "1 diamond ore", "5 ores" (for "any"). */
    public String oresWord(int n) {
        return n + " " + ("any".equals(label) ? "" : label + " ") + "ore" + (n == 1 ? "" : "s");
    }

    /** How many of the listed ores a tally (ore block id -> mined) holds. */
    public int count(Map<String, Integer> tally) {
        int n = 0;
        for (Map.Entry<String, Integer> e : tally.entrySet()) if (match(e.getKey()) >= 0) n += e.getValue();
        return n;
    }
}
