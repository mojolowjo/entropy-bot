package io.github.mojolowjo.entropybot.camp;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The food rules (the food check's first item, ready and raw food by the game's FOOD data, the water food ways). Pure Java (JUnit).
 */
public final class FoodPlan {
    private FoodPlan() {}

    /** Below this food level (of 20), check lists food first. */
    public static int low() { return Tuning.i("foodPlan.low"); }

    /** Ready food by the game's FOOD data: food, not poison (harmful or nutrition 1 or less is junk), not raw (a cooking recipe makes something of it). */
    static boolean ready(String id) {
        return io.github.mojolowjo.entropybot.aa.brain.ItemWorth.food(id) > 0 && !io.github.mojolowjo.entropybot.aa.brain.ItemWorth.junkFood(id) && !raw(id);
    }

    /** Raw food that cook takes: a cooking recipe's input that is no plantable crop. */
    public static boolean raw(String id) {
        String f = id.contains(":") ? id : "minecraft:" + id;
        return io.github.mojolowjo.entropybot.aa.brain.ItemWorth.cooks().containsKey(f) && !io.github.mojolowjo.entropybot.aa.brain.ItemWorth.hasTag(f, "villager_plantable_seeds");
    }

    /** Animal (short kind) -> its meat (short), from the loot tables. */
    static String meat(String animal) {
        String m = io.github.mojolowjo.entropybot.aa.brain.ItemWorth.mobFood().get("minecraft:" + animal);
        return m == null ? null : bare(m);
    }

    /** Better animals first (more food per kill: the cooked meat's energy, then id). */
    public static List<String> order() {
        List<String> l = new ArrayList<>();
        for (String a : new java.util.TreeSet<>(io.github.mojolowjo.entropybot.aa.brain.ItemWorth.mobFood().keySet())) l.add(bare(a));
        l.sort(java.util.Comparator.comparingDouble((String a) -> -io.github.mojolowjo.entropybot.aa.brain.FoodModel.energyOf(io.github.mojolowjo.entropybot.aa.brain.FoodModel.cooked(meat(a)) == null ? meat(a) : io.github.mojolowjo.entropybot.aa.brain.FoodModel.cooked(meat(a)))).thenComparing(a -> a));
        return l;
    }

    static String bare(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    public static boolean edible(String id) {
        return id != null && ready(id);
    }

    /** Food items in the bag (raw meat is left out: it goes to the cook step). */
    public static int count(Map<String, Integer> inv) {
        int n = 0;
        for (Map.Entry<String, Integer> e : inv.entrySet()) if (e.getValue() > 0 && edible(e.getKey())) n += e.getValue();
        return n;
    }

    /** The raw food in the bag that cook takes, by its cook word (beef -> 3). */
    public static Map<String, Integer> raw(Map<String, Integer> inv) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : inv.entrySet()) {
            if (e.getValue() > 0 && raw(e.getKey())) out.merge(bare(e.getKey()), e.getValue(), Integer::sum);
        }
        return out;
    }
    /** The animal to hunt: the best kind seen (near: short kind -> count), null when none. */
    public static String pick(Map<String, Integer> near) {
        if (near == null) return null;
        for (String k : order()) if (near.getOrDefault(k, 0) > 0) return k;
        return null;
    }

    /**
     * The first check item: null when food is at {@link #LOW} or more. {key, text, next}. With food in the bag: eat.
     * Else hunt (the best animal near, else cow), or hunting on first.
     */
    public static String[] checkFinding(int food, Map<String, Integer> inv, boolean huntingOn, Map<String, Integer> near) {
        if (food >= low()) return null;
        if (count(inv) > 0) return new String[]{"hunger", "food " + food + "/20", "eat"};
        Map<String, Integer> raw = raw(inv);
        if (!raw.isEmpty()) {
            Map.Entry<String, Integer> e = raw.entrySet().iterator().next();
            return new String[]{"hunger", "food " + food + "/20 and only raw meat to eat", "cook " + e.getKey() + " " + e.getValue()};
        }
        String k = pick(near);
        String a = k == null ? "cow" : k;
        String hunt = "hunt 4 " + a + " (then cook " + meat(a) + " 4)";
        String next = huntingOn ? hunt : "hunting on then " + hunt;
        if (k == null) next += " when you see some";
        return new String[]{"hunger", "food " + food + "/20 and nothing to eat", next};
    }

    /** One water food way for the FOOD options (aa/FoodOptions on aa-food): name, the command chain, why; or why not. */
    /** rm-execution2: a water way's typed steps (AA's food set runs them on aa/moves/Steps). */
    public record Way(String name, java.util.List<io.github.mojolowjo.entropybot.aa.moves.Step> steps, String why) {}

    /**
     * aa-kelpfish: the two water ways. kelp: kelp seen in water near -> a kelp cut then a cook with a furnace (dried kelp);
     * fishing: water near and a rod in the bag or the rod recipe's ingredients (a craft first) -> a fish step. The reasons for
     * the ways that can't run go to {@code dropped} (so the options line says why, not nothing).
     */
    public static List<Way> waterWays(Map<String, Integer> inv, boolean kelpNear, boolean waterNear, boolean furnace, List<String> dropped) {
        List<Way> out = new ArrayList<>();
        if (kelpNear && furnace) out.add(new Way("kelp", java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Kelp((int) io.github.mojolowjo.entropybot.aa.brain.Tuning.get("steps.kelpN"))), "kelp columns seen in the water, smelted to dried kelp"));
        else if (dropped != null) dropped.add(kelpNear ? "kelp: seen, but raw kelp is no food and there is no furnace" : "kelp: none seen in the water near");
        int rod = inv.getOrDefault(io.github.mojolowjo.entropybot.vocab.FishRules.ROD, 0) + inv.getOrDefault(io.github.mojolowjo.entropybot.vocab.FishRules.ROD.replaceFirst("^minecraft:", ""), 0);
        String why = io.github.mojolowjo.entropybot.vocab.FishRules.rodWhy(rod > 0, inv);
        if (!waterNear) { if (dropped != null) dropped.add("fishing: no water near"); }
        else if (why != null) { if (dropped != null) dropped.add("fishing: " + why); }
        else {
            var fish = new io.github.mojolowjo.entropybot.aa.moves.Step.Fish((int) io.github.mojolowjo.entropybot.aa.brain.Tuning.get("steps.fishMin"));
            out.add(new Way("fish", rod > 0 ? java.util.List.of(fish) : java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Craft(io.github.mojolowjo.entropybot.vocab.FishRules.ROD.replaceFirst("^minecraft:", ""), 1), fish),
                    "water near and " + (rod > 0 ? "a rod in the bag" : "the ingredients for a rod")));
        }
        return out;
    }
}
