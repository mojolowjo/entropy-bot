package io.github.mojolowjo.entropybot.camp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.30.5 (trial 9, finding 3: the run ended at food 5/20 with nothing to eat): the food rules. Pure Java (JUnit).
 *
 * <p>{@code bootstrap} ends with the step {@link #STEP} ("bootstrap food"): with fewer than {@link #WANT} food items
 * in the bag it hunts the allowed animals within 32 and cooks the meat. Hunting is the owner's setting (off by
 * default, it kills animals), so the step never turns it on: it says "hunting is off - next: hunting on". The hunt
 * and the cook run as soft chain steps ({@link #SOFT}): a failed one is reported and the chain still ends ok.
 * {@link #checkFinding} is the first {@code check} item when food is low.
 *
 * <p>Loader notes: none (no game classes). The game side is {@code commands.CampCommands.bootstrapFood}.
 */
public final class FoodPlan {
    private FoodPlan() {}

    /** The bootstrap chain's last step. */
    public static final String STEP = "bootstrap food";
    /** A chain step with this prefix may fail: the chain reports it and goes on (commands.Chains). */
    public static final String SOFT = "soft ";
    /** Food items wanted in the bag at the end of bootstrap. */
    public static final int WANT = 6;
    /** Below this food level (of 20), check lists food first. */
    public static final int LOW = 10;

    static final Set<String> EDIBLE = Set.of("cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton", "cooked_rabbit",
            "cooked_cod", "cooked_salmon", "bread", "apple", "golden_apple", "carrot", "golden_carrot", "baked_potato", "potato",
            "beetroot", "beetroot_soup", "mushroom_stew", "rabbit_stew", "sweet_berries", "glow_berries", "melon_slice",
            "pumpkin_pie", "cookie", "dried_kelp");          // ready food only: raw meat goes to the cook step

    /** Raw meat -> the word cook takes. */
    static final Map<String, String> RAW = Map.of("beef", "beef", "porkchop", "porkchop", "chicken", "chicken", "mutton", "mutton",
            "rabbit", "rabbit", "cod", "cod", "salmon", "salmon");

    /** Animal -> its meat. */
    static final Map<String, String> MEAT = Map.of("cow", "beef", "pig", "porkchop", "chicken", "chicken", "sheep", "mutton");

    /** Better animals first (more food per kill). */
    static final List<String> ORDER = List.of("cow", "pig", "sheep", "chicken");

    static String bare(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    public static boolean edible(String id) {
        return id != null && EDIBLE.contains(bare(id));
    }

    /** Food items in the bag (raw chicken is left out: it poisons). */
    public static int count(Map<String, Integer> inv) {
        int n = 0;
        for (Map.Entry<String, Integer> e : inv.entrySet()) if (e.getValue() > 0 && edible(e.getKey())) n += e.getValue();
        return n;
    }

    /** The raw meat in the bag that cook takes, by its cook word (beef -> 3). */
    public static Map<String, Integer> raw(Map<String, Integer> inv) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : inv.entrySet()) {
            String w = RAW.get(bare(e.getKey()));
            if (w != null && e.getValue() > 0) out.merge(w, e.getValue(), Integer::sum);
        }
        return out;
    }

    /** The animal to hunt: the best kind seen (near: short kind -> count), null when none. */
    public static String pick(Map<String, Integer> near) {
        if (near == null) return null;
        for (String k : ORDER) if (near.getOrDefault(k, 0) > 0) return k;
        return null;
    }

    /** What the food step does: steps to splice (may be empty) and the line it says. */
    public record Decision(List<String> steps, String text) {}

    /**
     * The food step. inv: the bag; huntingOn: the owner's setting; near: allowed animals within 32 (short kind -> count).
     * The text always starts "ok: " (the step never fails the chain) and says what comes next.
     */
    public static Decision decide(Map<String, Integer> inv, boolean huntingOn, Map<String, Integer> near) {
        int have = count(inv);
        if (have >= WANT) return new Decision(List.of(), "ok: food: I carry " + have + " food - nothing to do");
        int need = Math.max(3, WANT - have);
        Map<String, Integer> raw = raw(inv);
        List<String> steps = new ArrayList<>();
        String kind = pick(near);
        int rawN = 0;
        for (int v : raw.values()) rawN += v;
        if (rawN >= need) {                                       // enough raw meat: cook it, no hunt
            for (Map.Entry<String, Integer> e : raw.entrySet()) steps.add(SOFT + "cook " + e.getKey() + " " + e.getValue());
            return new Decision(steps, "ok: food: I carry " + have + " food and " + rawN + " raw - cooking it");
        }
        if (!huntingOn) {
            for (Map.Entry<String, Integer> e : raw.entrySet()) steps.add(SOFT + "cook " + e.getKey() + " " + e.getValue());
            String a = kind == null ? "cow" : kind;
            return new Decision(steps, "ok: food skipped: I carry " + have + " food and hunting is off"
                    + (steps.isEmpty() ? "" : " (cooking the raw meat I have)") + " - next: hunting on then hunt " + need + " " + a + " (then cook " + MEAT.get(a) + " " + need + ")");
        }
        if (kind == null) {
            for (Map.Entry<String, Integer> e : raw.entrySet()) steps.add(SOFT + "cook " + e.getKey() + " " + e.getValue());
            return new Decision(steps, "ok: food: I carry " + have + " food and see no animals within 32"
                    + (steps.isEmpty() ? "" : " (cooking the raw meat I have)") + " - next: hunt " + need + " when you see some");
        }
        String meat = MEAT.get(kind);
        int n = Math.min(need, Math.max(1, near.get(kind) * 2));   // a cow drops 1-3: two per animal seen at most
        steps.add(SOFT + "hunt " + n + " " + kind);
        int cook = n + raw.getOrDefault(meat, 0);
        steps.add(SOFT + "cook " + meat + " " + cook);
        for (Map.Entry<String, Integer> e : raw.entrySet()) if (!e.getKey().equals(meat)) steps.add(SOFT + "cook " + e.getKey() + " " + e.getValue());
        return new Decision(steps, "ok: food: I carry " + have + " food - hunting " + n + " " + kind + " (" + near.get(kind) + " within 32), then cook " + meat + " " + cook);
    }

    /** The bootstrap chain with its food step at the end (always: a camp that has everything still checks food). */
    public static List<String> withFood(List<String> steps) {
        List<String> out = new ArrayList<>(steps);
        out.add(STEP);
        return out;
    }

    /**
     * The first check item: null when food is at {@link #LOW} or more. {key, text, next}. With food in the bag: eat.
     * Else hunt (the best animal near, else cow), or hunting on first.
     */
    public static String[] checkFinding(int food, Map<String, Integer> inv, boolean huntingOn, Map<String, Integer> near) {
        if (food >= LOW) return null;
        if (count(inv) > 0) return new String[]{"hunger", "food " + food + "/20", "eat"};
        Map<String, Integer> raw = raw(inv);
        if (!raw.isEmpty()) {
            Map.Entry<String, Integer> e = raw.entrySet().iterator().next();
            return new String[]{"hunger", "food " + food + "/20 and only raw meat to eat", "cook " + e.getKey() + " " + e.getValue()};
        }
        String k = pick(near);
        String a = k == null ? "cow" : k;
        String hunt = "hunt 4 " + a + " (then cook " + MEAT.get(a) + " 4)";
        String next = huntingOn ? hunt : "hunting on then " + hunt;
        if (k == null) next += " when you see some";
        return new String[]{"hunger", "food " + food + "/20 and nothing to eat", next};
    }
}
