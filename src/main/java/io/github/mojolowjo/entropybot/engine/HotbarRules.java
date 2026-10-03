package io.github.mojolowjo.entropybot.engine;

import io.github.mojolowjo.entropybot.clear.Tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The hotbar layout the owner sets (package B, 2026-10-03), without Minecraft types so JUnit can pin it:
 * "hotbar set 1 pickaxe 2 sword 3 food 4 torch" (slots 1-9; kinds pickaxe, sword, axe, shovel, hoe, food, torch, or an
 * exact item id), which item belongs in a laid-out slot, the one swap that brings the bag closer to the layout, and
 * which hotbar slot an item goes to when the bot wants it in its hand. The layout lives in commands.json under
 * "hotbar" as {"1":"pickaxe","2":"sword",...} (the dashboard's settings page reads that key); "toolOres" is
 * "iron" or "cheapest".
 */
public final class HotbarRules {
    private HotbarRules() {}

    public static final List<String> KINDS = List.of("pickaxe", "sword", "axe", "shovel", "hoe", "food", "torch");
    public static final String USAGE = "usage: hotbar set <slot> <kind|item> ... (slots 1-9; kinds pickaxe, sword, axe, shovel, hoe, food, torch, or an item id), "
            + "hotbar, hotbar clear <slot>|all";
    public static final String NO_LAYOUT = "no hotbar layout - \"hotbar set 1 pickaxe 2 sword 3 food 4 torch\"";

    /** One bag slot (0-8 the hotbar, 9-35 the rest): its item, how many, and its food score (-1 = nothing to eat). */
    public record Item(int slot, String id, int count, int food) {}

    /** Move bag slot {@code from} into hotbar slot {@code to} (0-8): a SWAP click. */
    public record Swap(int from, int to) {}

    /** A layout change: the slots (1-9) set to a kind or item id, or null with {@code err}. */
    public record Change(Map<Integer, String> set, String err) {}

    /** The kind for a word ("pickaxes" = pickaxe), or null when it is no kind. */
    public static String kind(String word) {
        String w = word == null ? "" : word.toLowerCase();
        if (KINDS.contains(w)) return w;
        if (w.equals("torches")) return "torch";
        if (w.equals("foods")) return "food";
        if (w.endsWith("s") && KINDS.contains(w.substring(0, w.length() - 1))) return w.substring(0, w.length() - 1);
        return null;
    }

    public static boolean isKind(String spec) { return KINDS.contains(spec); }

    static String path(String id) { return id.substring(id.indexOf(':') + 1); }

    /** Does an item belong in a slot laid out for spec? food: its food score (-1 = not something to eat). */
    public static boolean matches(String spec, String id, int food) {
        if (spec == null || id == null || id.isEmpty() || id.equals("minecraft:air")) return false;
        return switch (spec) {
            case "pickaxe" -> id.endsWith("_pickaxe");
            case "sword" -> id.endsWith("_sword");
            case "axe" -> id.endsWith("_axe");          // "_pickaxe" ends in "kaxe", not "_axe"
            case "shovel" -> id.endsWith("_shovel");
            case "hoe" -> id.endsWith("_hoe");
            case "food" -> food >= 0;
            case "torch" -> path(id).equals("torch") || path(id).equals("soul_torch");
            default -> id.equals(spec);
        };
    }

    /** "1 pickaxe 2 sword 3 minecraft:cobblestone" -> {1: pickaxe, 2: sword, 3: ...}; resolve turns an item name into its id (null = unknown). */
    public static Change parseSet(String text, Function<String, String> resolve) {
        List<String> w = new ArrayList<>();
        for (String s : (text == null ? "" : text.trim()).split("[\\s,]+")) if (!s.isEmpty()) w.add(s);
        if (w.isEmpty() || w.size() % 2 != 0) return new Change(null, USAGE);
        Map<Integer, String> out = new TreeMap<>();
        for (int i = 0; i < w.size(); i += 2) {
            String n = w.get(i), what = w.get(i + 1);
            if (!n.matches("^[1-9]$")) return new Change(null, "error: hotbar slots are 1-9 (got \"" + n + "\") - " + USAGE);
            String k = kind(what);
            if (k == null) {
                k = resolve.apply(what.toLowerCase());
                if (k == null) return new Change(null, "error: I don't know an item called " + what + " (kinds: " + String.join(", ", KINDS) + ")");
            }
            out.put(Integer.parseInt(n), k);
        }
        return new Change(out, null);
    }

    /** {"1":"pickaxe",...} as stored -> {1: pickaxe, ...} (bad keys dropped). */
    public static Map<Integer, String> fromStrings(Map<String, String> stored) {
        Map<Integer, String> out = new TreeMap<>();
        if (stored == null) return out;
        for (Map.Entry<String, String> e : stored.entrySet()) {
            if (e.getKey().matches("^[1-9]$") && e.getValue() != null && !e.getValue().isEmpty()) out.put(Integer.parseInt(e.getKey()), e.getValue());
        }
        return out;
    }

    public static Map<String, String> toStrings(Map<Integer, String> layout) {
        Map<String, String> out = new LinkedHashMap<>();
        new TreeMap<>(layout).forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    static String shortId(String id) { return id == null ? "" : id.replaceFirst("^minecraft:", ""); }

    /** "1 pickaxe, 2 sword, 3 food" (or "none"). */
    public static String describe(Map<Integer, String> layout) {
        List<String> parts = new ArrayList<>();
        new TreeMap<>(layout).forEach((k, v) -> parts.add(k + " " + shortId(v)));
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    /** "hotbar": the layout and what each laid-out slot holds now. inv: the 36 bag slots (null or "" = empty). */
    public static String show(Map<Integer, String> layout, List<Item> inv) {
        if (layout.isEmpty()) return NO_LAYOUT;
        List<String> now = new ArrayList<>();
        for (int k : new TreeMap<>(layout).keySet()) {
            Item it = at(inv, k - 1);
            now.add(k + " " + (it == null ? "-" : shortId(it.id()) + (matches(layout.get(k), it.id(), it.food()) ? "" : " (wrong)")));
        }
        return "hotbar: " + describe(layout) + " (now: " + String.join(", ", now) + ")";
    }

    static Item at(List<Item> inv, int slot) {
        for (Item it : inv) if (it != null && it.slot() == slot && it.id() != null && !it.id().isEmpty() && it.count() > 0) return it;
        return null;
    }

    /** How good an item is for a slot of that spec (higher is better); pickaxes and other tools: the cheapest, as the clear uses them. */
    static long rank(String spec, Item it) {
        return switch (spec) {
            case "sword" -> Tools.toolTier(it.id()) * 1000L;
            case "pickaxe", "axe", "shovel", "hoe" -> (10 - Tools.toolTier(it.id())) * 1000L;
            case "food" -> it.food() * 1000L + it.count();
            default -> it.count();
        };
    }

    /** The laid-out slots, exact item ids first (they are the more particular), then kinds, each by slot number. */
    static List<Integer> order(Map<Integer, String> layout) {
        List<Integer> out = new ArrayList<>();
        for (int k : new TreeMap<>(layout).keySet()) if (!isKind(layout.get(k))) out.add(k);
        for (int k : new TreeMap<>(layout).keySet()) if (isKind(layout.get(k))) out.add(k);
        return out;
    }

    /** The hotbar slots (0-8) that already hold what the layout wants there. */
    static boolean[] satisfied(Map<Integer, String> layout, List<Item> inv) {
        boolean[] ok = new boolean[9];
        for (Map.Entry<Integer, String> e : layout.entrySet()) {
            Item it = at(inv, e.getKey() - 1);
            ok[e.getKey() - 1] = it != null && matches(e.getValue(), it.id(), it.food());
        }
        return ok;
    }

    /**
     * The next swap toward the layout, or null when every laid-out slot is right or has nothing to take (a kind the
     * bot doesn't carry leaves its slot alone). Never takes an item out of a slot that already holds what it should.
     */
    public static Swap plan(Map<Integer, String> layout, List<Item> inv) {
        if (layout.isEmpty()) return null;
        boolean[] ok = satisfied(layout, inv);
        for (int k : order(layout)) {
            if (ok[k - 1]) continue;
            String spec = layout.get(k);
            Item best = null;
            for (Item it : inv) {
                if (it == null || it.id() == null || it.count() <= 0 || !matches(spec, it.id(), it.food())) continue;
                if (it.slot() < 9 && ok[it.slot()]) continue;          // it belongs where it is
                if (best == null || rank(spec, it) > rank(spec, best)) best = it;
            }
            if (best != null) return new Swap(best.slot(), k - 1);
        }
        return null;
    }

    /** The laid-out hotbar slot (0-8) for this item: an exact id slot before a kind slot, the one it is in first; -1 = none. */
    public static int layoutSlot(Map<Integer, String> layout, String id, int food, int now) {
        int exact = -1, kind = -1;
        for (int k : new TreeMap<>(layout).keySet()) {
            String spec = layout.get(k);
            if (!matches(spec, id, food)) continue;
            boolean isK = isKind(spec);
            if (!isK && (exact < 0 || k - 1 == now)) exact = k - 1;
            if (isK && (kind < 0 || k - 1 == now)) kind = k - 1;
        }
        return exact >= 0 ? exact : kind;
    }

    /**
     * Where to put bag slot {@code index} so the bot holds it (holdItem, holdBestTool, the reflexes): its laid-out slot;
     * else where it is when it is in the hotbar already; else the selected slot unless it is laid out, else a free slot
     * nobody laid out, else any slot nobody laid out, else the selected one. Without a layout this is the old rule (a
     * hotbar item is selected, a bag item swapped into the selected slot).
     */
    public static int handSlot(Map<Integer, String> layout, List<Item> inv, int index, int selected) {
        Item it = null;
        for (Item i : inv) if (i != null && i.slot() == index) it = i;
        if (it != null) {
            int l = layoutSlot(layout, it.id(), it.food(), index);
            if (l >= 0) return l;
        }
        if (index < 9) return index;
        if (!layout.containsKey(selected + 1)) return selected;
        for (int s = 0; s < 9; s++) if (!layout.containsKey(s + 1) && at(inv, s) == null) return s;
        for (int s = 0; s < 9; s++) if (!layout.containsKey(s + 1)) return s;
        return selected;
    }

    /**
     * What deposit leaves with the bot because the layout names it: {id: count} of the stack in each laid-out slot that
     * holds the right thing, else of the stack the next swaps would bring there (the best match in the bag).
     */
    public static Map<String, Integer> keeps(Map<Integer, String> layout, List<Item> inv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        boolean[] ok = satisfied(layout, inv);
        List<Integer> used = new ArrayList<>();
        for (int k : order(layout)) {
            String spec = layout.get(k);
            Item pick = ok[k - 1] ? at(inv, k - 1) : null;
            if (pick == null) {
                for (Item it : inv) {
                    if (it == null || it.id() == null || it.count() <= 0 || !matches(spec, it.id(), it.food()) || used.contains(it.slot())) continue;
                    if (it.slot() < 9 && ok[it.slot()]) continue;
                    if (pick == null || rank(spec, it) > rank(spec, pick)) pick = it;
                }
            }
            if (pick == null) continue;
            used.add(pick.slot());
            out.merge(pick.id(), pick.count(), Integer::sum);
        }
        return out;
    }

    // ---- "tools ores iron|cheapest" ----

    public static final String TOOLS_USAGE = "usage: tools ores iron|cheapest (iron: ores with the iron pickaxe when I have one; cheapest: the cheapest pickaxe that does the job)";

    /** "iron" (the default) or "cheapest"; anything else is the default. */
    public static String toolOres(String stored) {
        return "cheapest".equals(stored) ? "cheapest" : "iron";
    }

    /** "tools": the policy in words. */
    public static String toolsText(String toolOres) {
        return "tools: " + ("cheapest".equals(toolOres) ? "ores with the cheapest pickaxe that does the job" : "ores with the iron pickaxe (or better) when I have one")
                + "; stone and the rest with the cheapest that does the job (3 stone pickaxes made from my cobblestone when only the ore pickaxe is left) - tools ores iron|cheapest";
    }

    /** "tools ores iron|cheapest" -> {the new value, the reply}, or {null, the usage}. */
    public static String[] toolsCommand(String text) {
        String t = text == null ? "" : text.trim().toLowerCase();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^ores\\s+(iron|cheapest)$").matcher(t);
        if (!m.find()) return new String[]{null, TOOLS_USAGE};
        String v = m.group(1);
        return new String[]{v, v.equals("iron") ? "ok: ores with the iron pickaxe (or better) when I have one, the cheapest pickaxe for the rest"
                : "ok: ores with the cheapest pickaxe that does the job, like everything else"};
    }
}
