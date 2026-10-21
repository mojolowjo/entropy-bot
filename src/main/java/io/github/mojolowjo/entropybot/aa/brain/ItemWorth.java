package io.github.mojolowjo.entropybot.aa.brain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AA_ROUND4 C / PROGRESSION_PLAN P1 (first slice): an item's worth from what the game's own data says it does, never from
 * a name list. Pure; an adapter ({@code commands.ItemWorthAdapter}) feeds a {@link Source} from the item components and the
 * recipe manager. JUnit ItemWorthTest. Loader notes: the facts come from 1.21.1 data components (TOOL, MAX_DAMAGE,
 * ATTRIBUTE_MODIFIERS, FOOD) and block state hardness/light; a loader without them gives {@link Facts#NONE} and the
 * answer is an explicit unknown.
 *
 * <ul>
 * <li>digging tool: {@code toolK x sqrt(speed) x (1 + level) x ln(1 + durability) x role weight} (role = what the tool
 *     digs: pickaxe 1, axe, shovel, hoe by Tuning).</li>
 * <li>weapon: {@code weaponK x attack^2 x ln(1 + durability)}.</li>
 * <li>armour piece: {@code armourK x (defence + 2 x toughness)}.</li>
 * <li>food: {@code foodK x (nutrition + saturation)} per item.</li>
 * <li>block: {@code hardK x hardness + lightK x light}.</li>
 * <li>otherwise (a modded ingot, a part): a recipe: the sum of the ingredients' worths x (1 + margin) / output count;
 *     any ingredient unknown, or a cycle (ingot <-> block): unknown.</li>
 * <li>no facts, no recipe: {@link Worth#unknown}. Never 0 for "I do not know"; the caller keeps its fallback table.</li>
 * </ul>
 * Every result carries the terms it came from ({@link Worth#why}) for {@code aa worth <item>}.
 */
public final class ItemWorth {
    private ItemWorth() {}

    /** What the game says about one item (zero = does not apply). {@code role}: what a digging tool digs (pickaxe, axe, ...), "weapon", or "". */
    public record Facts(String role, double digSpeed, int digLevel, double durability, double defence, double toughness,
                        double attack, double nutrition, double saturation, double hardness, int light) {
        public static final Facts NONE = new Facts("", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** One recipe for an item: the output count and the ingredient ids (one per slot, the first choice). */
    public record Recipe(int outCount, List<String> inputs) {}

    /** The world's data. {@link #facts} never null; {@link #recipes} empty when the item has none. */
    public interface Source {
        Facts facts(String id);
        List<Recipe> recipes(String id);
        /** Items a furnace makes (smelting recipe results), for the furnace's worth. */
        default List<String> smeltResults() { return List.of(); }
    }

    /** A worth, or an explicit unknown, with the terms. */
    public record Worth(double value, boolean known, String why) {
        public static Worth unknown(String why) { return new Worth(0, false, why); }
        public static Worth of(double v, String why) { return new Worth(v, true, why); }
    }

    private static volatile Source source;
    private static final Map<String, Worth> CACHE = new HashMap<>();
    /** Set when a result was cut by depth or a loop (so it depends on the path and is not memoised). */
    private static boolean cutFlag;

    /** The adapter sets the data (null: none yet); the cache is cleared. */
    public static synchronized void use(Source s) { source = s; CACHE.clear(); }

    public static boolean hasData() { return source != null; }

    static String path(String id) { int i = id.indexOf(':'); return i < 0 ? id : id.substring(i + 1); }

    static String full(String id) { return id.indexOf(':') < 0 ? "minecraft:" + id : id; }

    /** The worth of one item (explicit unknown without data). */
    public static synchronized Worth of(String id) {
        Source s = source;
        if (s == null || id == null) return Worth.unknown("no item data loaded");
        String k = full(id);
        Worth w = CACHE.get(k);
        if (w == null) { cutFlag = false; w = derive(s, k, new HashSet<>(), 0); if (!cutFlag) CACHE.put(k, w); }
        return w;
    }

    /** The worth if known and above 0, else {@code fallback} (the caller's own table). */
    public static double valueOr(String id, double fallback) {
        Worth w = of(id);
        return w.known() && w.value() > 0 ? w.value() : fallback;
    }

    /** What a tool or armour piece is worth: 0 when the data says it is neither (or is unknown). */
    public static double gear(String id) {
        Source s = source;
        if (s == null) return 0;
        Facts f = s.facts(full(id));
        if (f.defence > 0 || f.digSpeed > 0 || f.attack > 0 && f.durability > 0) { Worth w = of(id); return w.known() ? w.value() : 0; }
        return 0;
    }

    /** Food per item (0: not food or unknown). */
    public static double food(String id) {
        Source s = source;
        if (s == null) return 0;
        Facts f = s.facts(full(id));
        return f.nutrition > 0 ? Tuning.get("worth.foodK") * (f.nutrition + f.saturation) : 0;
    }

    /** Energy points (nutrition + saturation) from the data, 0 none. */
    public static double energy(String id) {
        Source s = source;
        if (s == null) return 0;
        Facts f = s.facts(full(id));
        return f.nutrition > 0 ? f.nutrition + f.saturation : 0;
    }

    /** The items a furnace makes, from the recipes (empty: none loaded; the caller keeps its list). */
    public static List<String> smelts() {
        Source s = source;
        return s == null ? List.of() : s.smeltResults();
    }

    /** A sub-result: the memo first; stored only when no depth/loop cut is inside it. */
    private static Worth sub(Source s, String id, Set<String> path, int depth) {
        Worth c = CACHE.get(id);
        if (c != null) return c;
        boolean outer = cutFlag;
        cutFlag = false;
        Worth w = derive(s, id, path, depth);
        if (!cutFlag) CACHE.put(id, w);
        cutFlag |= outer;
        return w;
    }

    static Worth derive(Source s, String id, Set<String> path, int depth) {
        Facts f = s.facts(id);
        String n = path(id);
        if (f.defence > 0) {
            double v = cap(Tuning.get("worth.armourK") * (f.defence + 2 * f.toughness));
            return Worth.of(v, String.format(Locale.ROOT, "armour %.1f x (defence %.0f + 2 x toughness %.0f) = %.1f", Tuning.get("worth.armourK"), f.defence, f.toughness, v));
        }
        if (f.digSpeed > 0) {
            double rw = roleWeight(f.role);
            double v = cap(Tuning.get("worth.toolK") * Math.pow(f.digSpeed, Tuning.get("worth.speedPow")) * (1 + f.digLevel) * Math.log(1 + f.durability) * rw);
            return Worth.of(v, String.format(Locale.ROOT, "%s: %.2f x speed %.1f^%.2f x (1 + level %d) x ln(1 + durability %.0f) x role %.2f = %.1f",
                    f.role.isEmpty() ? "tool" : f.role, Tuning.get("worth.toolK"), f.digSpeed, Tuning.get("worth.speedPow"), f.digLevel, f.durability, rw, v));
        }
        if (f.attack > 0 && f.durability > 0) {
            double v = cap(Tuning.get("worth.weaponK") * f.attack * f.attack * Math.log(1 + f.durability));
            return Worth.of(v, String.format(Locale.ROOT, "weapon: %.3f x attack %.1f^2 x ln(1 + durability %.0f) = %.1f", Tuning.get("worth.weaponK"), f.attack, f.durability, v));
        }
        if (f.nutrition > 0) {
            double v = cap(Tuning.get("worth.foodK") * (f.nutrition + f.saturation));
            return Worth.of(v, String.format(Locale.ROOT, "food: %.2f x (nutrition %.0f + saturation %.1f) = %.2f", Tuning.get("worth.foodK"), f.nutrition, f.saturation, v));
        }
        if (f.hardness > 0 || f.light > 0) {
            double v = cap(Tuning.get("worth.hardK") * f.hardness + Tuning.get("worth.lightK") * f.light);
            return Worth.of(v, String.format(Locale.ROOT, "block: %.3f x hardness %.1f + %.3f x light %d = %.3f", Tuning.get("worth.hardK"), f.hardness, Tuning.get("worth.lightK"), f.light, v));
        }
        // a recipe only
        if (depth >= (int) Tuning.get("worth.depth")) { cutFlag = true; return Worth.unknown(n + ": recipe chain deeper than " + (int) Tuning.get("worth.depth")); }
        if (!path.add(id)) { cutFlag = true; return Worth.unknown(n + ": its recipe loops back to itself"); }
        try {
            Worth best = null;
            String lastWhy = n + ": no facts and no recipe in the game's data";
            for (Recipe r : s.recipes(id)) {
                if (uncraft(s, id, r)) { lastWhy = n + ": only an uncrafting recipe (from its storage block)"; continue; }
                double sum = 0;
                StringBuilder sb = new StringBuilder();
                String bad = null;
                for (String in : r.inputs()) {
                    Worth iw;
                    if (path.contains(full(in))) { cutFlag = true; iw = Worth.unknown(path(in) + " loops"); }
                    else iw = sub(s, full(in), path, depth + 1);
                    if (!iw.known()) { bad = iw.why(); break; }
                    sum += iw.value();
                    sb.append(sb.length() == 0 ? "" : " + ").append(path(in)).append(' ').append(String.format(Locale.ROOT, "%.2f", iw.value()));
                }
                if (bad != null) { lastWhy = n + ": recipe input unknown (" + bad + ")"; continue; }
                double m = Tuning.get("worth.margin");
                double v = Math.max(Tuning.get("worth.floor"), cap(sum * (1 + m) / Math.max(1, r.outCount())));
                if (best == null || v < best.value()) best = Worth.of(v, String.format(Locale.ROOT, "recipe: (%s) x (1 + margin %.2f) / %d = %.3f", sb, m, Math.max(1, r.outCount()), v));
            }
            return best != null ? best : Worth.unknown(lastWhy);
        } finally { path.remove(id); }
    }

    /** One term never passes worth.cap (about 3x a diamond pickaxe): a modded attack 50 cannot swamp the scale. */
    static double cap(double v) { return Math.min(v, Tuning.get("worth.cap")); }

    /** True when a recipe is an uncrafting: every input has its own recipe back from this output (ingot from its block, nuggets). */
    static boolean uncraft(Source s, String id, Recipe r) {
        for (String in : r.inputs()) {
            boolean back = false;
            for (Recipe q : s.recipes(full(in))) {
                boolean all = !q.inputs().isEmpty();
                for (String x : q.inputs()) if (!full(x).equals(id)) all = false;
                if (all) { back = true; break; }
            }
            if (!back) return false;
        }
        return true;
    }

    static double roleWeight(String role) {
        return switch (role == null ? "" : role) {
            case "pickaxe" -> Tuning.get("worth.role.pickaxe");
            case "axe" -> Tuning.get("worth.role.axe");
            case "shovel" -> Tuning.get("worth.role.shovel");
            case "hoe" -> Tuning.get("worth.role.hoe");
            default -> Tuning.get("worth.role.other");
        };
    }

    /** The terms for {@code aa worth <item>}. */
    public static String explain(String id) {
        Worth w = of(id);
        return path(full(id)) + ": " + (w.known() ? String.format(Locale.ROOT, "%.3f", w.value()) : "UNKNOWN") + " - " + w.why()
                + (w.known() ? "" : " (the caller's fallback table applies)");
    }
}
