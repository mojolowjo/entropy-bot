package io.github.mojolowjo.entropybot.craft;

import io.github.mojolowjo.entropybot.craft.CraftPlanner.AllPlan;
import io.github.mojolowjo.entropybot.craft.CraftPlanner.Target;
import io.github.mojolowjo.entropybot.craft.CraftPlanner.Targets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.mojolowjo.entropybot.craft.CraftPlanner.shortId;

/**
 * The pure parts of the bridge's craft / smelt / get / need / supplies / restock / kit verbs (startCraft, startSmelt,
 * startGet, needCommand, suppliesCommand, startRestock, startKit): their parsing, their sums and their texts. The
 * walking, chest trips (takeTripSteps) and the jobs stay with the wiring session. "Storage" below is what the chests
 * and the RS network hold as the bot last saw them, summed (the bridge's storageSources).
 */
public final class CraftTexts {
    private CraftTexts() {}

    /** inv plus every storage map, keys in that order (the bridge's "combined"). */
    @SafeVarargs
    public static Map<String, Integer> combine(Map<String, Integer> inv, Map<String, Integer>... storage) {
        Map<String, Integer> out = new LinkedHashMap<>(inv);
        for (Map<String, Integer> s : storage) if (s != null) for (Map.Entry<String, Integer> e : s.entrySet()) out.merge(e.getKey(), e.getValue(), Integer::sum);
        return out;
    }

    /** What a plan over {@code combined} takes from storage: combined - left - inv, where positive (combined's order). */
    public static Map<String, Integer> fromStorage(Map<String, Integer> combined, Map<String, Integer> left, Map<String, Integer> inv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : combined.entrySet()) {
            int n = e.getValue() - CraftPlanner.get(left, e.getKey()) - CraftPlanner.get(inv, e.getKey());
            if (n > 0) out.put(e.getKey(), n);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // need <item> [n]
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The "need &lt;item&gt; [n]" verb's reply: what crafting it takes from the inventory, else from the inventory and
     * storage (and what comes from storage), else what is missing. {@code rsKnown}: a grid is remembered (memory.rsGrid).
     */
    public static String needReply(CraftPlanner p, String text, Map<String, Integer> inv, Map<String, Integer> storage, boolean rsKnown) {
        Targets targets = p.parseCraftTargets(text, inv);
        if (!targets.ok()) return "error: " + targets.error();
        if (targets.list().isEmpty()) return "usage: need <item> [n]";
        AllPlan r = p.planAll(targets.list(), new LinkedHashMap<>(inv));
        if (r.ok()) return "I can make that from what I carry: " + CraftPlanner.describeSteps(r.steps()) + (r.anySmelt() ? " (with the furnace)" : "");
        Map<String, Integer> combined = combine(inv, storage);
        r = p.planAll(targets.list(), new LinkedHashMap<>(combined));
        if (!r.ok()) return "missing: " + r.error() + " (counting my chests" + (rsKnown ? " and the RS network" : "") + ")";
        List<String> used = new ArrayList<>();
        for (Map.Entry<String, Integer> e : fromStorage(combined, r.counts(), inv).entrySet()) used.add(e.getValue() + " " + shortId(e.getKey()));
        return "I can make it: " + CraftPlanner.describeSteps(r.steps()) + "; from storage: " + String.join(", ", used);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // craft <things> (startCraft)
    // ---------------------------------------------------------------------------------------------------------------

    public static final String CRAFT_WHAT = "error: craft what?";

    /** Planned from the inventory alone failed and no storage is known. */
    public static String craftNoStorage(String label, String err) {
        return "error: can't craft " + label + " - " + err + " (PM \"scan\" and I will check the base chests)";
    }

    /** Planned with storage failed too. */
    public static String craftEvenWithStorage(String label, String err, boolean rsKnown) {
        return "error: can't craft " + label + " - " + err + " (even counting the chests I know" + (rsKnown ? " and the RS network" : "") + ")";
    }

    /** A furnace step with no furnace known (craftPlanSteps' error), wrapped as startCraft does. */
    public static String craftNoFurnace(String label, String item) {
        return "error: can't craft " + label + " - " + CraftJob.noFurnace(item);
    }

    /** The craft job's label when it fetches first ("getting materials from 2 place(s), then crafting 1 torch"). */
    public static String craftSeqLabel(int sourcesUsed, String label) {
        return (sourcesUsed > 0 ? "getting materials from " + sourcesUsed + " place(s), then " : "") + "crafting " + label;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // smelt <item> [n] / get <item> [n]
    // ---------------------------------------------------------------------------------------------------------------

    /** "&lt;name words&gt; [n]": the name (spaces as _, lowercased) and the count, or {@code dflt} without one. */
    public record NameCount(String name, String words, int n) {}

    private static final Pattern NAME_COUNT = Pattern.compile("^(.*?)(?:\\s+(\\d+))?$");

    /** Parses "iron ingot 9" -> (iron_ingot, "iron ingot", 9); null for an empty text. */
    public static NameCount parseNameCount(String text, int dflt) {
        String t = String.valueOf(text == null ? "" : text).trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        Matcher m = NAME_COUNT.matcher(t);
        if (!m.matches()) return null;
        return new NameCount(m.group(1).replaceAll("\\s+", "_"), m.group(1), m.group(2) != null ? CraftPlanner.parseCount(m.group(2)) : dflt);
    }

    public static final String SMELT_USAGE = "error: usage smelt <item it makes> [n], e.g. smelt iron_ingot 9";

    public static String unknownItem(String words) {
        return "error: I don't know an item called " + words;
    }

    public static String noFurnaceRecipe(String item) {
        return "error: no furnace recipe makes " + shortId(item);
    }

    /** planSmelt failed even counting storage ({@link CraftPlanner#NO_SMELTING} -> "no recipe"). */
    public static String cantSmelt(String label, String err) {
        return "error: can't smelt " + label + " - " + (CraftPlanner.NO_SMELTING.equals(err) ? "no recipe" : err);
    }

    public static String smeltSeqLabel(boolean fetches, String label) {
        return (fetches ? "getting materials, then " : "") + "smelting " + label;
    }

    public static final String GET_USAGE = "error: usage get <item> [n]";
    /** get's count without one: a stack. */
    public static final int GET_DEFAULT = 64;

    public static String getNone(String item, boolean rsKnown) {
        return "error: none of my chests" + (rsKnown ? " or the RS network" : "") + " has " + shortId(item) + " (as far as I know - \"scan base\" / \"rs\")";
    }

    public static String getSeqLabel(String item, int n, int have) {
        return "getting " + Math.min(n, have) + " " + shortId(item) + (have < n ? " (all I know of)" : "");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // supplies / restock / kit
    // ---------------------------------------------------------------------------------------------------------------

    public static final String SUPPLIES_CLEARED = "ok: no supplies to keep";
    public static final String SUPPLIES_SET_USAGE = "usage: supplies set torch 32, bread 16, stone_pickaxe 3";
    public static final String NO_SUPPLIES = "no supplies set - \"supplies set torch 32, bread 16\"";

    /** "supplies set ..." -> id -> want (later duplicates win, like the bridge's object), or null with {@code err[0]} set. */
    public static Map<String, Integer> parseSupplies(CraftPlanner p, String text, Map<String, Integer> inv, String[] err) {
        Targets targets = p.parseCraftTargets(text, inv);
        if (!targets.ok()) {
            err[0] = "error: " + targets.error();
            return null;
        }
        if (targets.list().isEmpty()) {
            err[0] = SUPPLIES_SET_USAGE;
            return null;
        }
        Map<String, Integer> s = new LinkedHashMap<>();
        for (Target t : targets.list()) s.put(t.id(), t.want());
        return s;
    }

    /** "supplies (have/want): torch 0/32, bread 0/16 - "restock" tops them up", or {@link #NO_SUPPLIES}. */
    public static String suppliesReply(Map<String, Integer> supplies, Map<String, Integer> inv) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : supplies.entrySet()) parts.add(shortId(e.getKey()) + " " + CraftPlanner.get(inv, e.getKey()) + "/" + e.getValue());
        return parts.isEmpty() ? NO_SUPPLIES : "supplies (have/want): " + String.join(", ", parts) + " - \"restock\" tops them up";
    }

    /**
     * What "restock" does: {@code take} = what to fetch from storage, {@code craftText} = what to craft afterwards
     * ("torch 12, bread 4", for the optional craft step; empty for none), {@code label} = the job's label; or
     * {@code reply} set when there is nothing to do.
     */
    public record Restock(Map<String, Integer> take, String craftText, String label, String reply) {}

    public static Restock restock(Map<String, Integer> supplies, Map<String, Integer> inv, Map<String, Integer> storage) {
        Map<String, Integer> take = new LinkedHashMap<>();
        List<String> craft = new ArrayList<>();
        boolean any = false;
        for (Map.Entry<String, Integer> e : supplies.entrySet()) {
            String k = e.getKey();
            int shortBy = e.getValue() - CraftPlanner.get(inv, k);
            if (shortBy <= 0) continue;
            any = true;
            int have = storage == null ? 0 : CraftPlanner.get(storage, k);
            if (have > 0) take.put(k, Math.min(shortBy, have));
            if (have < shortBy) craft.add(shortId(k) + " " + (shortBy - Math.min(shortBy, have)));
        }
        if (!any) return new Restock(Map.of(), "", null, supplies.isEmpty() ? "error: " + NO_SUPPLIES : "ok: I have all my supplies");
        Set<String> names = new LinkedHashSet<>();
        for (String k : take.keySet()) names.add(shortId(k));
        for (String c : craft) names.add(shortId(c.split(" ")[0]));
        return new Restock(take, String.join(", ", craft), "restocking " + String.join(", ", names), null);
    }

    /** "kit &lt;material&gt;": the set's pieces (armor, then tools) and those not in {@code have} (carried or worn). */
    public record Kit(List<String> all, List<String> missing) {}

    public static final String KIT_USAGE = "error: usage kit <material>, e.g. kit copper";

    public static Kit kit(CraftPlanner p, String material, Map<String, Integer> have) {
        List<String> all = new ArrayList<>(p.expandSet(material, CraftPlanner.ARMOR_PIECES));
        all.addAll(p.expandSet(material, CraftPlanner.TOOL_PIECES));
        List<String> missing = new ArrayList<>();
        for (String id : all) if (CraftPlanner.get(have, id) <= 0) missing.add(id);
        return new Kit(List.copyOf(all), List.copyOf(missing));
    }

    public static String kitUnknown(String material) {
        return "error: I don't know any craftable " + material + " gear";
    }

    /** With nothing missing, the wear verb's error becomes this. */
    public static String kitComplete(String material) {
        return "ok: I already have the full " + material + " kit";
    }
}
