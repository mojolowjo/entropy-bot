package io.github.mojolowjo.entropybot.craft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The crafting planner over a {@link RecipeSource}: a faithful port of the bridge's planCraft / planSmelt / pickFuel /
 * recipeNeeds / available / consume / craftingRecipes / smeltingRecipes, plus the name handling around them
 * (resolveItem, craftScore, expandSet, parseCraftTargets, planAll, describeSteps, recipeInfo). No Minecraft types.
 */
public final class CraftPlanner implements Crafter {
    /** How deep missing ingredients are crafted or smelted first (logs -> planks -> sticks -> torch). */
    public static final int MAX_DEPTH = 2;
    /** How many alternatives of a missing crafting ingredient are tried. */
    public static final int CRAFT_ALTS = 40;
    /** How many alternatives of a missing furnace input are tried. */
    public static final int SMELT_ALTS = 10;
    /** {@link #planSmelt}'s error when no furnace recipe makes the item (the bridge's 'none'). */
    public static final String NO_SMELTING = "none";
    /** Counts above this are capped (the bridge had JS numbers; this keeps the int arithmetic from overflowing). */
    public static final int MAX_WANT = 1_000_000;

    public static final List<String> ARMOR_PIECES = List.of("helmet", "chestplate", "leggings", "boots");
    public static final List<String> TOOL_PIECES = List.of("sword", "pickaxe", "axe", "shovel", "hoe");

    private static final Pattern ITEM_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");

    /** Fuel kinds in the order they are picked, and how many items one smelts (coal or charcoal 8, a coal block 80, planks 1). */
    record FuelKind(Pattern re, int per) {}

    static final List<FuelKind> FUEL_KINDS = List.of(new FuelKind(Pattern.compile(":(coal|charcoal)$"), 8),
            new FuelKind(Pattern.compile(":coal_block$"), 80), new FuelKind(Pattern.compile("_planks$"), 1));

    /** Fuel for a furnace step. */
    public record Fuel(String id, int count) {}

    /** One "craft" target: {@code want} of {@code id}. */
    public record Target(String id, int want) {}

    /** parseCraftTargets' answer: the targets, or why not (the bridge's wording, without "error: "). */
    public record Targets(List<Target> list, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** planAll's answer: the steps for every target and the counts left, or the error ("&lt;short id&gt;: &lt;why&gt;"). */
    public record AllPlan(List<Step> steps, Map<String, Integer> counts, String error) {
        public boolean ok() {
            return error == null;
        }

        public boolean anySmelt() {
            for (Step s : steps) if (s instanceof Smelt) return true;
            return false;
        }
    }

    private final RecipeSource src;

    public CraftPlanner(RecipeSource src) {
        this.src = src;
    }

    public RecipeSource source() {
        return src;
    }

    // ------------------------------------------------------------------------------------------------------------
    // small helpers (bridge: shortId, endsWith, copyCounts, itemExists, available, consume)
    // ------------------------------------------------------------------------------------------------------------

    /** The id without "minecraft:". */
    public static String shortId(String id) {
        return id != null && id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : String.valueOf(id);
    }

    static Map<String, Integer> copy(Map<String, Integer> c) {
        return new LinkedHashMap<>(c);
    }

    static int get(Map<String, Integer> c, String id) {
        Integer v = c.get(id);
        return v == null ? 0 : v;
    }

    private static void replace(Map<String, Integer> counts, Map<String, Integer> with) {
        counts.clear();
        counts.putAll(with);
    }

    private static int ceilDiv(int a, int b) {
        return (int) Math.ceil(a / (double) b);
    }

    /** A well-formed id the registry knows. */
    public boolean itemExists(String id) {
        return id != null && ITEM_ID.matcher(id).matches() && src.exists(id);
    }

    /** How many of any of {@code alts} the counts hold. */
    public static int available(List<String> alts, Map<String, Integer> counts) {
        int n = 0;
        for (String a : alts) n += get(counts, a);
        return n;
    }

    /** Takes {@code n} of {@code alts} out of the counts, the first alternative first. */
    public static void consume(List<String> alts, int n, Map<String, Integer> counts) {
        for (int i = 0; i < alts.size() && n > 0; i++) {
            int take = Math.min(get(counts, alts.get(i)), n);
            if (take > 0) {
                counts.put(alts.get(i), get(counts, alts.get(i)) - take);
                n -= take;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // recipes
    // ------------------------------------------------------------------------------------------------------------

    /** The crafting-grid recipes that make {@code id}, without repair recipes (which use the item itself). */
    public List<RecipeData> craftingRecipes(String id) {
        List<RecipeData> out = new ArrayList<>();
        if (!itemExists(id)) return out;
        for (RecipeData r : src.recipesFor(id)) if (r.crafting() && id.equals(r.output()) && !r.uses(id)) out.add(r);
        return out;
    }

    /** The furnace (minecraft:smelting) recipes that make {@code id}. */
    public List<RecipeData> smeltingRecipes(String id) {
        List<RecipeData> out = new ArrayList<>();
        if (!itemExists(id)) return out;
        for (RecipeData r : src.recipesFor(id)) if (RecipeData.SMELTING.equals(r.type()) && id.equals(r.output())) out.add(r);
        return out;
    }

    /** The recipe a step names (for {@link GridLayout}), or null. */
    public RecipeData recipe(String recipeId) {
        return src.recipe(recipeId);
    }

    @Override
    public List<Recipe> craftingRecipesFor(String item) {
        List<Recipe> out = new ArrayList<>();
        for (RecipeData r : craftingRecipes(item)) out.add(r.toRecipe());
        return out;
    }

    @Override
    public List<Recipe> craftingRecipesUsing(String item) {
        List<Recipe> out = new ArrayList<>();
        if (!itemExists(item)) return out;
        for (RecipeData r : src.recipesUsing(item)) if (r.crafting()) out.add(r.toRecipe());
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------
    // the planner
    // ------------------------------------------------------------------------------------------------------------

    @Override
    public Plan plan(String item, int n, Map<String, Integer> counts) {
        List<Step> steps = new ArrayList<>();
        String err = planCraft(item, Math.min(n, MAX_WANT), counts, 0, steps);
        return err == null ? new Plan(List.copyOf(steps), null) : Plan.fail(err);
    }

    /**
     * A plan that can only be the furnace (the bridge's planSmelt from depth 0, as "smelt &lt;item&gt; [n]" uses it):
     * its input may be crafted or smelted first. The error is {@link #NO_SMELTING} when no furnace recipe makes it.
     * Counts as in {@link #plan}.
     */
    public Plan planSmelt(String item, int n, Map<String, Integer> counts) {
        List<Step> steps = new ArrayList<>();
        String err = planSmelt(item, Math.min(n, MAX_WANT), counts, 0, steps);
        return err == null ? new Plan(List.copyOf(steps), null) : Plan.fail(err);
    }

    // planCraft: null when planned (steps appended, counts updated), else why not
    private String planCraft(String id, int want, Map<String, Integer> counts, int depth, List<Step> steps) {
        List<RecipeData> recipes = craftingRecipes(id);
        if (recipes.isEmpty()) {
            // no crafting recipe: maybe a furnace makes it (an ingot from raw ore, glass, a processor...)
            String sm = planSmelt(id, want, counts, depth, steps);
            return sm == null ? null : (sm.equals(NO_SMELTING) ? "there is no crafting recipe for " + shortId(id) : sm);
        }
        String lastErr = null;
        for (RecipeData r : recipes) {
            int out = r.outCount();
            int crafts = ceilDiv(want, out);
            Map<String, Integer> trial = copy(counts);
            List<Step> subSteps = new ArrayList<>();
            boolean ok = true;
            List<Need> needs = r.needs();
            for (int k = 0; k < needs.size() && ok; k++) {
                Need n = needs.get(k);
                int missing = n.amount() * crafts - available(n.alts(), trial);
                if (missing > 0) {
                    boolean found = false;
                    if (depth < MAX_DEPTH) {
                        for (int j = 0; j < n.alts().size() && j < CRAFT_ALTS && !found; j++) {
                            Map<String, Integer> trial2 = copy(trial);
                            List<Step> steps2 = new ArrayList<>();
                            if (planCraft(n.alts().get(j), missing, trial2, depth + 1, steps2) == null) {
                                trial = trial2;
                                subSteps.addAll(steps2);
                                found = true;
                            }
                        }
                    }
                    if (!found) {
                        ok = false;
                        lastErr = "missing " + missing + " " + shortId(n.alts().get(0)) + (n.alts().size() > 1 ? " (or similar)" : "");
                    }
                }
                if (ok) consume(n.alts(), n.amount() * crafts, trial);
            }
            if (ok) {
                trial.put(id, get(trial, id) + crafts * out);
                replace(counts, trial);
                steps.addAll(subSteps);
                steps.add(new Craft(r.id(), id, want, crafts, r.needsTable()));
                return null;
            }
        }
        // none of the crafting recipes works: a furnace may still make it
        String sm = planSmelt(id, want, counts, depth, steps);
        if (sm == null) return null;
        return lastErr != null ? lastErr : "cannot craft " + shortId(id);
    }

    /** Fuel for {@code n} items from the counts (coal or charcoal first, then a coal block, then planks), or null. */
    public static Fuel pickFuel(Map<String, Integer> counts, int n) {
        for (FuelKind f : FUEL_KINDS) {
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                int have = e.getValue() == null ? 0 : e.getValue();
                if (have <= 0 || !f.re().matcher(e.getKey()).find()) continue;
                int need = ceilDiv(n, f.per());
                if (have >= need) return new Fuel(e.getKey(), need);
            }
        }
        return null;
    }

    // planSmelt: null when planned, NO_SMELTING without a furnace recipe, else why not
    private String planSmelt(String id, int want, Map<String, Integer> counts, int depth, List<Step> steps) {
        String lastErr = NO_SMELTING;
        for (RecipeData r : smeltingRecipes(id)) {
            int out = Math.max(1, r.outCount());
            int n = ceilDiv(want, out);
            List<Need> needs = r.needs();
            if (needs.size() != 1) continue;
            List<String> alts = needs.get(0).alts();
            Map<String, Integer> trial = copy(counts);
            List<Step> sub = new ArrayList<>();
            int missing = n - available(alts, trial);
            if (missing > 0) {
                boolean found = false;
                for (int j = 0; j < alts.size() && j < SMELT_ALTS && depth < MAX_DEPTH && !found; j++) {
                    Map<String, Integer> trial2 = copy(trial);
                    List<Step> s2 = new ArrayList<>();
                    if (planCraft(alts.get(j), missing, trial2, depth + 1, s2) == null) {
                        trial = trial2;
                        sub = s2;
                        found = true;
                    }
                }
                if (!found) {
                    lastErr = "missing " + missing + " " + shortId(alts.get(0)) + (alts.size() > 1 ? " (or similar)" : "") + " to smelt into " + shortId(id);
                    continue;
                }
            }
            // the furnace takes one kind at a time: the alternative there is most of
            String input = null;
            int best = -1;
            for (String a : alts) {
                if (get(trial, a) > best) {
                    best = get(trial, a);
                    input = a;
                }
            }
            if (best < n) {
                lastErr = "need " + n + " of one kind of " + shortId(alts.get(0)) + " to smelt";
                continue;
            }
            trial.put(input, get(trial, input) - n);
            Fuel fuel = pickFuel(trial, n);
            if (fuel == null) {
                lastErr = "no fuel (coal or charcoal) to smelt " + n + " " + shortId(input);
                continue;
            }
            trial.put(fuel.id(), get(trial, fuel.id()) - fuel.count());
            trial.put(id, get(trial, id) + n * out);
            replace(counts, trial);
            steps.addAll(sub);
            steps.add(new Smelt(r.id(), id, n * out, input, n, fuel.id(), fuel.count()));
            return null;
        }
        return lastErr;
    }

    // ------------------------------------------------------------------------------------------------------------
    // names and targets
    // ------------------------------------------------------------------------------------------------------------

    /** How much of an item's recipe the counts already cover (0..1): the share of its ingredients there is any of. */
    public double craftScore(String id, Map<String, Integer> counts) {
        double best = 0;
        for (RecipeData r : craftingRecipes(id)) {
            List<Need> needs = r.needs();
            int have = 0;
            for (Need n : needs) if (available(n.alts(), counts) > 0) have++;
            if (!needs.isEmpty() && (double) have / needs.size() > best) best = (double) have / needs.size();
        }
        return best;
    }

    /**
     * "planks" / "sticks" / "oak_planks" / "oritech:steel_ingot" -> an item id, or null. Generic names pick the variant
     * the counts can make right now (or hold), vanilla first on a tie.
     */
    public String resolveItem(String q, Map<String, Integer> counts) {
        q = String.valueOf(q).toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return null;
        if (q.indexOf(':') >= 0) return itemExists(q) ? q : null;
        String singular = q.endsWith("s") ? q.substring(0, q.length() - 1) : q;
        if (itemExists("minecraft:" + q)) return "minecraft:" + q;
        if (!singular.equals(q) && itemExists("minecraft:" + singular)) return "minecraft:" + singular;
        String best = null;
        double bestScore = -1;
        for (String id : src.allItemIds()) {
            String path = id.substring(id.indexOf(':') + 1);
            if (!(path.equals(q) || path.equals(singular) || path.endsWith("_" + q) || path.endsWith("_" + singular))) continue;
            double score = craftScore(id, counts) + (get(counts, id) > 0 ? 2 : 0) + (id.startsWith("minecraft:") ? 0.1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = id;
            }
        }
        return best;
    }

    /** "copper" + {@link #ARMOR_PIECES} -> the craftable copper helmet/chestplate/... ids (vanilla first, then the shortest). */
    public List<String> expandSet(String material, List<String> pieces) {
        List<String> ids = src.allItemIds(), out = new ArrayList<>();
        for (String piece : pieces) {
            String best = null;
            for (String id : ids) {
                String path = id.substring(id.indexOf(':') + 1);
                if (!path.contains(material) || !path.endsWith("_" + piece)) continue;
                if (craftingRecipes(id).isEmpty()) continue;
                boolean vanilla = id.startsWith("minecraft:");
                if (best == null || (vanilla && !best.startsWith("minecraft:"))
                        || (vanilla == best.startsWith("minecraft:") && id.length() < best.length())) best = id;
            }
            if (best != null) out.add(best);
        }
        return out;
    }

    private static final Pattern NUMBER_THEN_WORD = Pattern.compile("(\\d+)\\s+(?=[a-z])");
    private static final Pattern NAME_COUNT = Pattern.compile("^(.*?)(?:\\s+(\\d+))?$");
    private static final Pattern ARMOR_OR_TOOLS = Pattern.compile("^(armor|armour|tools)$");

    /** "1234" -> 1234, capped at {@link #MAX_WANT}. */
    static int parseCount(String digits) {
        if (digits.length() > 7) return MAX_WANT;
        return Math.min(Integer.parseInt(digits), MAX_WANT);
    }

    /**
     * "planks 8, copper armor, copper_pickaxe" (or "torch 32 bread 16") -> the targets, or why not ("I don't know an
     * item called x", "I don't know any craftable x armor"). Without a count, 1.
     */
    public Targets parseCraftTargets(String text, Map<String, Integer> counts) {
        String t = NUMBER_THEN_WORD.matcher(String.valueOf(text).toLowerCase(Locale.ROOT)).replaceAll("$1, ");
        List<Target> out = new ArrayList<>();
        for (String part : t.split(",", -1)) {
            Matcher m = NAME_COUNT.matcher(part.trim());
            if (!m.matches() || m.group(1).isEmpty()) continue;
            String q = m.group(1).trim();
            int want = m.group(2) != null ? parseCount(m.group(2)) : 1;
            String[] words = q.split("\\s+");
            if (words.length == 2 && ARMOR_OR_TOOLS.matcher(words[1]).matches()) {
                List<String> set = expandSet(words[0], words[1].equals("tools") ? TOOL_PIECES : ARMOR_PIECES);
                if (set.isEmpty()) return new Targets(List.of(), "I don't know any craftable " + q);
                for (String id : set) out.add(new Target(id, want));
                continue;
            }
            String id = resolveItem(String.join("_", words), counts);
            if (id == null) return new Targets(List.of(), "I don't know an item called " + q);
            out.add(new Target(id, want));
        }
        return new Targets(List.copyOf(out), null);
    }

    /**
     * Plans every target in a row, threading {@code counts} through (like the bridge, the counts are changed by the
     * targets before a failing one: pass a copy). The error is "&lt;short id&gt;: &lt;why&gt;".
     */
    public AllPlan planAll(List<Target> targets, Map<String, Integer> counts) {
        List<Step> steps = new ArrayList<>();
        for (Target t : targets) {
            Plan p = plan(t.id(), t.want(), counts);
            if (!p.ok()) return new AllPlan(List.of(), counts, shortId(t.id()) + ": " + p.error());
            steps.addAll(p.steps());
        }
        return new AllPlan(List.copyOf(steps), counts, null);
    }

    /** "2 oak_planks -> 1 stick -> 4 torch". */
    public static String describeSteps(List<? extends Step> steps) {
        List<String> parts = new ArrayList<>();
        for (Step s : steps) parts.add(s.want() + " " + shortId(s.item()));
        return String.join(" -> ", parts);
    }

    /** "1 torch, 8 oak_planks": the bridge's label for a craft. */
    public static String label(List<Target> targets) {
        List<String> parts = new ArrayList<>();
        for (Target t : targets) parts.add(t.want() + " " + shortId(t.id()));
        return String.join(", ", parts);
    }

    /**
     * The "recipe &lt;item&gt;" verb's reply: the first two crafting-grid recipes (else the first two of any type), e.g.
     * "[minecraft:crafting] makes 4: 1x minecraft:coal (or 1 alternatives), 1x minecraft:stick".
     */
    public String recipeInfo(String text, Map<String, Integer> counts) {
        String q = String.valueOf(text).trim().split("\\s+")[0];
        String id = resolveItem(q, counts);
        if (id == null) return "error: I don't know an item called " + q;
        List<RecipeData> rs = craftingRecipes(id);
        if (rs.isEmpty()) {
            rs = new ArrayList<>();
            for (RecipeData r : src.recipesFor(id)) if (id.equals(r.output())) rs.add(r);
        }
        if (rs.size() > 2) rs = rs.subList(0, 2);
        if (rs.isEmpty()) return "none: no recipes for " + id;
        List<String> lines = new ArrayList<>();
        for (RecipeData r : rs) {
            Map<String, Integer> need = new LinkedHashMap<>();
            for (List<String> cell : r.cells()) {
                if (cell.isEmpty()) continue;
                String key = cell.get(0) + (cell.size() > 1 ? " (or " + (cell.size() - 1) + " alternatives)" : "");
                need.merge(key, 1, Integer::sum);
            }
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : need.entrySet()) parts.add(e.getValue() + "x " + e.getKey());
            lines.add("[" + r.type() + "] makes " + r.outCount() + ": " + String.join(", ", parts));
        }
        return String.join(" | ", lines);
    }
}
