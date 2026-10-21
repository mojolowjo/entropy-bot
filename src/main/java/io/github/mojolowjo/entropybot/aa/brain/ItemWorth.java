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

    /** What the game says about one item (zero = does not apply). {@code role}: what a digging tool digs (from the game's item tags), WEAPON, or OTHER. */
    public record Facts(Role role, double digSpeed, int digLevel, double durability, double defence, double toughness,
                        double attack, double nutrition, double saturation, double hardness, int light) {
        public static final Facts NONE = new Facts(Role.OTHER, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** A tool's role, decided by the game's item tags (the adapter) and never by an id; OTHER = no known tag (shears, a modded tool). */
    public enum Role { PICKAXE, AXE, SHOVEL, HOE, WEAPON, OTHER }

    /** The role from the tags the item carries (pure; the adapter reads ItemTags PICKAXES/AXES/SHOVELS/HOES), checked before any block data. */
    public static Role roleOf(boolean pickTag, boolean axeTag, boolean shovelTag, boolean hoeTag, boolean digs, boolean attacks) {
        if (pickTag) return Role.PICKAXE;
        if (axeTag) return Role.AXE;
        if (shovelTag) return Role.SHOVEL;
        if (hoeTag) return Role.HOE;
        if (!digs && attacks) return Role.WEAPON;
        return Role.OTHER;
    }

    /** One recipe for an item: the output count and the ingredient ids (one per slot, the first choice). */
    public record Recipe(int outCount, List<String> inputs) {}

    /** The world's data. {@link #facts} never null; {@link #recipes} empty when the item has none. */
    public interface Source {
        Facts facts(String id);
        List<Recipe> recipes(String id);
        /** Items a furnace makes (smelting recipe results), for the furnace's worth. */
        default List<String> smeltResults() { return List.of(); }
        /** Raw item id -> cooked item id, from the cooking recipes (smelting, smoking, campfire) whose result is food. */
        default Map<String, String> cooks() { return Map.of(); }
        /** A plain stone-like block item: in the base-stone tags, or a soft pickaxe/shovel block that is no ore and no block entity. */
        default boolean stoneLike(String id) { return false; }
        /** A workstation block (it opens a menu: the crafting table, any modded bench). */
        default boolean station(String id) { return false; }
        /** An ore block (the game's ores tag): rarity is in no item component, so the tag stands for it. */
        default boolean ore(String id) { return false; }
        /** An item in the game's planks tag. */
        default boolean plank(String id) { return false; }
        /** A food whose FOOD component carries a harmful effect (hunger, poison, nausea) or gives nutrition 1 or less. */
        default boolean junkFood(String id) { return false; }
        /** The game's block and item tags of an id, as "namespace:path" (minecraft:logs, c:ores, minecraft:base_stone_overworld...). */
        default Set<String> tags(String id) { return Set.of(); }
        /** A food whose FOOD component carries a beneficial effect (golden apple, golden carrot, suspicious stew): a special, not plain food. */
        default boolean effectFood(String id) { return false; }
        /** A food with any harmful effect, however unlikely (raw chicken 30 % hunger): fine in a pinch, never a plan's food without cooking. */
        default boolean riskyFood(String id) { return false; }
        /** Every id carrying the tag ("namespace:path"), for the lists a rule once spelled out. */
        default Set<String> withTag(String tag) { return Set.of(); }
        /** Mob id -> the food item its loot table drops (animals the bot may hunt are the friendly ones); empty on a client with no loot tables. */
        default Map<String, String> mobFood() { return Map.of(); }
        /** An item that blocks attacks when used (a shield: the game's BLOCK use animation). */
        default boolean shield(String id) { return false; }
    }

    /** Where a once-only note goes (the adapter sets the mod log; tests may collect). */
    private static volatile java.util.function.Consumer<String> sink = s -> {};
    private static final Set<String> SAID = new HashSet<>();
    public static void logTo(java.util.function.Consumer<String> c) { sink = c == null ? s -> {} : c; }
    /** Says {@code msg} once per {@code key} (an explicit unknown is never silent, never repeated every tick). */
    public static void warnOnce(String key, String msg) {
        boolean first; synchronized (SAID) { first = SAID.add(key); }
        if (first) sink.accept("item data: " + msg);
    }
    public static void resetWarnings() { synchronized (SAID) { SAID.clear(); } }

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
        if (id == null) return 0;
        Facts f = s.facts(full(id));
        return f.nutrition > 0 ? f.nutrition + f.saturation : 0;
    }

    /** The facts of one item (Facts.NONE without data or for an item the game gives none). */
    public static Facts facts(String id) { Source s = source; return s == null || id == null ? Facts.NONE : s.facts(full(id)); }

    /** Raw -> cooked item ids from the cooking recipes (empty: no data; said once). */
    public static Map<String, String> cooks() {
        Source s = source;
        if (s == null) { warnOnce("cooks", "no cooking recipes loaded (nothing is cookable)"); return Map.of(); }
        return s.cooks();
    }

    /** Nutrition of one item from the game's FOOD component (0 none or unknown). */
    public static double nutrition(String id) {
        Source s = source;
        if (s == null) { warnOnce("food", "no item data loaded: no food is known"); return 0; }
        return s.facts(full(id)).nutrition;
    }

    /** What a tool, armour piece, shield or bow is: it has durability (the game's MAX_DAMAGE component), whatever its name or mod. */
    public static boolean damageable(String id) { Source s = source; return s != null && s.facts(full(id)).durability > 0; }

    /** A block item the game gives hardness to (placeable filler or ore); false without data. */
    public static boolean block(String id) { Source s = source; return s != null && s.facts(full(id)).hardness > 0; }

    /** A held light source that breaks at once (a torch-like item): light emission at or over kit.lightMin and no hardness. */
    public static boolean lightSource(String id) {
        Source s = source;
        if (s == null) return false;
        Facts f = s.facts(full(id));
        return f.light >= Tuning.get("kit.lightMin") && f.hardness <= 0;
    }

    /** True when the game tags the id ({@code logs} = minecraft:logs; {@code c:ores} as written). */
    public static boolean hasTag(String id, String tag) {
        Source s = source;
        if (s == null || id == null || tag == null) return false;
        return s.tags(full(id)).contains(tag.indexOf(':') < 0 ? "minecraft:" + tag : tag);
    }
    /** The ids the game tags with {@code tag}, sorted (empty without data: said once). */
    public static List<String> withTag(String tag) {
        Source s = source;
        if (s == null) { warnOnce("tag:" + tag, "no item data loaded: the tag " + tag + " is empty"); return List.of(); }
        List<String> out = new java.util.ArrayList<>(s.withTag(tag.indexOf(':') < 0 ? "minecraft:" + tag : tag));
        java.util.Collections.sort(out);
        return out;
    }
    public static boolean riskyFood(String id) { Source s = source; return s != null && s.riskyFood(full(id)); }
    public static boolean effectFood(String id) { Source s = source; return s != null && s.effectFood(full(id)); }

    /** The raw item a cooking recipe makes this from (null: nothing cooks into it). */
    public static String rawOf(String id) {
        Source s = source;
        if (s == null || id == null) return null;
        String f = full(id);
        for (var e : s.cooks().entrySet()) if (e.getValue().equals(f)) return e.getKey();
        return null;
    }

    /** The first ingredient of the item's cheapest-listed recipe (bread: wheat), or null. */
    public static String firstIngredient(String id) {
        Source s = source;
        if (s == null || id == null) return null;
        for (Recipe r : s.recipes(full(id))) if (!r.inputs().isEmpty()) return r.inputs().get(0);
        return null;
    }
    public static boolean shield(String id) { Source s = source; return s != null && s.shield(full(id)); }

    /** Mob id -> the food its loot table drops (empty without data: said once). */
    public static Map<String, String> mobFood() {
        Source s = source;
        if (s == null || s.mobFood().isEmpty()) { warnOnce("mobFood", "no mob loot tables loaded: no animal is known as food"); return Map.of(); }
        return s.mobFood();
    }
    public static boolean junkFood(String id) { Source s = source; return s != null && s.junkFood(full(id)); }
    public static boolean plank(String id) { Source s = source; return s != null && s.plank(full(id)); }
    public static boolean station(String id) { Source s = source; return s != null && s.station(full(id)); }
    public static boolean stoneLike(String id) { Source s = source; return s != null && s.stoneLike(full(id)); }

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
                    f.role == Role.OTHER ? "tool" : f.role.name().toLowerCase(Locale.ROOT), Tuning.get("worth.toolK"), f.digSpeed, Tuning.get("worth.speedPow"), f.digLevel, f.durability, rw, v));
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
            if (s.ore(id)) v = cap(v * Tuning.get("worth.oreK"));
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

    static double roleWeight(Role role) {
        return switch (role == null ? Role.OTHER : role) {
            case PICKAXE -> Tuning.get("worth.role.pickaxe");
            case AXE -> Tuning.get("worth.role.axe");
            case SHOVEL -> Tuning.get("worth.role.shovel");
            case HOE -> Tuning.get("worth.role.hoe");
            case WEAPON, OTHER -> Tuning.get("worth.role.other");
        };
    }

    /** The terms for {@code aa worth <item>}. */
    public static String explain(String id) {
        Worth w = of(id);
        return path(full(id)) + ": " + (w.known() ? String.format(Locale.ROOT, "%.3f", w.value()) : "UNKNOWN") + " - " + w.why()
                + (w.known() ? "" : " (the caller's fallback table applies)");
    }
}




